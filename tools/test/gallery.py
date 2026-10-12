#!/usr/bin/env python3
"""Screenshot gallery: capture every registered display and publish one page.

The registry (`tools/test/gallery-displays.json`) lists scenarios. Each one is a
`visual.py` command plus the frame labels that command must capture. `run`
drives the scenarios in order on one emulator, reads each run's
`events.jsonl`, and writes `build/gallery/<run-id>/index.html` with every frame
grouped by screen. A registered display that was not captured, a capture no
display claims, and a scenario that failed are all called out at the top of the
page (and in the exit status), so the gallery doubles as a coverage check.

    tools/test/gallery.py list
    tools/test/gallery.py run                      # every non-optional scenario
    tools/test/gallery.py run --only media-controls,route --no-build
    tools/test/gallery.py run --include-optional --serial emulator-5554
    tools/test/gallery.py run --emulators 3          # shard over 3 emulators
    tools/test/gallery.py run --serials emulator-5554,emulator-5556
    tools/test/gallery.py build build/visual-inspection/2026*/   # from old runs

Sharding keeps the result coherent: every APK variant (and, for scenarios that
run JUnit on device, its instrumented APK) is built once before any device is
touched and installed by exact path; all devices must report the same
build fingerprint, screen size, density, font scale, locale and animation
scales; each scenario chain starts from reset app preferences/databases; a
scenario and the ones declared `after` it run on one device in order; and the
page is laid out in registry order, recording which device made each frame.
"""

from __future__ import annotations

import argparse
import base64
import fnmatch
import html
import json
import os
import shutil
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

ROOT = Path(__file__).resolve().parents[2]
VISUAL = ROOT / "tools" / "test" / "visual.py"
REGISTRY = ROOT / "tools" / "test" / "gallery-displays.json"
DEFAULT_OUTPUT = ROOT / "build" / "gallery"
DEFAULT_IMAGE_WIDTH = 720
JAVA_HOME = os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@17")
APP_ID = "com.curvemaps.app"
APK_VARIANTS = ("bundled", "mapless")
DEFAULT_AVD = os.environ.get("ANDROID_AVD", "Pixel_10_Pro")
UNKNOWN_SCENARIO_SECONDS = 120.0


class GalleryError(RuntimeError):
    pass


# --------------------------------------------------------------------------
# Registry
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class Display:
    title: str
    label: str | None = None
    pattern: str | None = None
    minimum: int = 1
    optional: bool = False
    setup: bool = False  # shared preamble frames: folded away on the page

    @property
    def key(self) -> str:
        return self.label or self.pattern or ""

    def matches(self, label: str) -> bool:
        if self.label is not None:
            return label == self.label
        return fnmatch.fnmatchcase(label, self.pattern or "")

    def required_count(self) -> int:
        return 0 if self.optional else self.minimum


@dataclass(frozen=True)
class Scenario:
    id: str
    group: str
    args: tuple[str, ...]
    displays: tuple[Display, ...]
    optional: bool = False
    after: str | None = None
    apk: str = "bundled"

    def command_args(self, defaults: dict[str, str]) -> list[str]:
        out = []
        for arg in self.args:
            for key, value in defaults.items():
                arg = arg.replace("{" + key + "}", value)
            if "{" in arg and "}" in arg:
                raise GalleryError(f"{self.id}: unknown placeholder in {arg!r}")
            out.append(arg)
        return out


@dataclass(frozen=True)
class Registry:
    scenarios: tuple[Scenario, ...]
    shared: tuple[Display, ...]
    defaults: dict[str, str]

    def scenario(self, scenario_id: str) -> Scenario:
        for scenario in self.scenarios:
            if scenario.id == scenario_id:
                return scenario
        raise GalleryError(f"unknown scenario {scenario_id!r}")


def _display(raw: dict[str, Any], where: str) -> Display:
    label, pattern = raw.get("label"), raw.get("pattern")
    if (label is None) == (pattern is None):
        raise GalleryError(f"{where}: a display needs exactly one of label/pattern: {raw}")
    if not raw.get("title"):
        raise GalleryError(f"{where}: display {label or pattern} has no title")
    unknown = set(raw) - {"label", "pattern", "title", "min", "optional", "setup"}
    if unknown:
        raise GalleryError(f"{where}: unknown display keys {sorted(unknown)}")
    minimum = int(raw.get("min", 1))
    if minimum < 0:
        raise GalleryError(f"{where}: min must be >= 0")
    return Display(title=raw["title"], label=label, pattern=pattern,
                   minimum=minimum, optional=bool(raw.get("optional", False)),
                   setup=bool(raw.get("setup", False)))


def load_registry(path: Path = REGISTRY) -> Registry:
    raw = json.loads(path.read_text(encoding="utf-8"))
    scenarios: list[Scenario] = []
    seen: set[str] = set()
    for entry in raw.get("scenarios", []):
        scenario_id = entry.get("id")
        if not scenario_id or scenario_id in seen:
            raise GalleryError(f"scenario id missing or duplicated: {scenario_id!r}")
        seen.add(scenario_id)
        if not entry.get("args"):
            raise GalleryError(f"{scenario_id}: args missing")
        displays = tuple(_display(d, scenario_id) for d in entry.get("displays", []))
        if not displays:
            raise GalleryError(f"{scenario_id}: registers no displays")
        keys = [d.key for d in displays]
        if len(keys) != len(set(keys)):
            raise GalleryError(f"{scenario_id}: duplicate display keys")
        apk = entry.get("apk", "bundled")
        if apk not in APK_VARIANTS:
            raise GalleryError(f"{scenario_id}: apk must be one of {APK_VARIANTS}")
        scenarios.append(Scenario(
            id=scenario_id,
            group=entry.get("group") or scenario_id,
            args=tuple(entry["args"]),
            displays=displays,
            optional=bool(entry.get("optional", False)),
            after=entry.get("after"),
            apk=apk,
        ))
    ids = {scenario.id for scenario in scenarios}
    for scenario in scenarios:
        if scenario.after is not None and scenario.after not in ids:
            raise GalleryError(f"{scenario.id}: after names unknown scenario {scenario.after!r}")
        seen_chain, cursor = {scenario.id}, scenario
        while cursor.after is not None:
            if cursor.after in seen_chain:
                raise GalleryError(f"{scenario.id}: `after` forms a cycle")
            seen_chain.add(cursor.after)
            cursor = next(s for s in scenarios if s.id == cursor.after)
    shared = tuple(_display(d, "shared") for d in raw.get("shared", []))
    return Registry(tuple(scenarios), shared, dict(raw.get("defaults", {})))


# --------------------------------------------------------------------------
# Frames and classification
# --------------------------------------------------------------------------

@dataclass
class Frame:
    label: str
    path: Path
    at: str = ""
    visible: list[str] = field(default_factory=list)
    scenario: str = ""
    run_dir: str = ""
    device: str = ""
    image: str = ""  # gallery-relative image path, filled when written


@dataclass
class ScenarioResult:
    scenario: Scenario
    frames: list[Frame]
    status: str = "ok"  # ok | failed | skipped
    detail: str = ""
    seconds: float = 0.0
    run_dirs: list[str] = field(default_factory=list)
    serial: str = ""


@dataclass
class Classified:
    by_display: list[tuple[Display, list[Frame]]]
    shared: list[tuple[Display, list[Frame]]]
    unregistered: list[Frame]
    missing: list[tuple[Display, int]]  # display, captured count


def read_run_frames(run_dir: Path) -> list[Frame]:
    events = run_dir / "events.jsonl"
    if not events.exists():
        return []
    frames: list[Frame] = []
    for line in events.read_text(encoding="utf-8").splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if event.get("event") != "screenshot":
            continue
        path = Path(event.get("path", ""))
        if not path.is_absolute():
            path = run_dir / path.name
        if not path.exists():
            # Runs copied elsewhere keep their file names.
            path = run_dir / Path(event.get("path", "")).name
        visible = []
        for node in event.get("visible", []):
            text = (node.get("text") or "").strip()
            description = (node.get("description") or "").strip()
            entry = text or description
            if text and description and text != description:
                entry = f"{text} · {description}"
            if entry and entry not in visible:
                visible.append(entry)
        frames.append(Frame(label=event.get("label", path.stem), path=path,
                            at=event.get("at", ""), visible=visible, run_dir=str(run_dir)))
    return frames


def classify(scenario: Scenario, frames: Iterable[Frame], shared: tuple[Display, ...]) -> Classified:
    by_display = [(display, []) for display in scenario.displays]
    by_shared = [(display, []) for display in shared]
    unregistered: list[Frame] = []
    for frame in frames:
        for display, bucket in by_display:
            if display.matches(frame.label):
                bucket.append(frame)
                break
        else:
            for display, bucket in by_shared:
                if display.matches(frame.label):
                    bucket.append(frame)
                    break
            else:
                unregistered.append(frame)
    missing = [(display, len(bucket)) for display, bucket in by_display
               if len(bucket) < display.required_count()]
    return Classified(by_display, [(d, b) for d, b in by_shared if b], unregistered, missing)


def best_scenario_for(frames: list[Frame], registry: Registry) -> Scenario | None:
    """For `build` from old runs: the scenario whose displays claim most frames."""
    best, best_score = None, 0
    for scenario in registry.scenarios:
        score = sum(1 for frame in frames
                    if any(display.matches(frame.label) for display in scenario.displays))
        if score > best_score:
            best, best_score = scenario, score
    return best


# --------------------------------------------------------------------------
# Running scenarios
# --------------------------------------------------------------------------

_HELP_CACHE: dict[str, str] = {}


def command_accepts(command: str, flag: str) -> bool:
    if command not in _HELP_CACHE:
        result = subprocess.run([sys.executable, str(VISUAL), command, "--help"],
                                capture_output=True, text=True, cwd=ROOT)
        _HELP_CACHE[command] = result.stdout
    return flag in _HELP_CACHE[command]


DEBUG_APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
# Instrumented APK for scenarios whose visual.py command runs JUnit on device
# (currently `ui-sizes`). Built and frozen beside the app APK so every shard
# installs one identical pair.
DEBUG_TEST_APK = ROOT / "app" / "build" / "outputs" / "apk" / "androidTest" / "debug" / "app-debug-androidTest.apk"


def sha256(path: Path) -> str:
    import hashlib

    return hashlib.sha256(path.read_bytes()).hexdigest()


def prebuild_apks(needed: set[str], apk_dir: Path, *, no_build: bool,
                  test_variants: set[str] | None = None) -> tuple[dict[str, Path], dict[str, Path]]:
    """Builds each needed APK variant once and freezes a copy for every shard.

    Map-less is built first so the bundled build is what stays at the usual
    Gradle output path afterwards (the instrumented tiers expect it there).
    Variants in [test_variants] also get their androidTest APK frozen from the
    same build; --no-build never assembles anything and fails actionably when
    either artifact is missing.
    """
    test_variants = set(test_variants or ())
    apk_dir.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    frozen: dict[str, Path] = {}
    frozen_tests: dict[str, Path] = {}
    for variant in [v for v in ("mapless", "bundled") if v in needed]:
        flags = ["-Pmoto.bundleData=false"] if variant == "mapless" else []
        if no_build:
            if variant == "mapless":
                continue  # reported per scenario as skipped
            if not DEBUG_APK.is_file():
                raise GalleryError(f"--no-build but {DEBUG_APK} is missing")
        else:
            print(f"gallery: building the {variant} debug APK", flush=True)
            subprocess.run(["./gradlew", "-q", ":app:assembleDebug", *flags], cwd=ROOT, env=env, check=True)
        target = apk_dir / f"{variant}.apk"
        shutil.copyfile(DEBUG_APK, target)
        frozen[variant] = target
        if variant not in test_variants:
            continue
        if no_build:
            if not DEBUG_TEST_APK.is_file():
                raise GalleryError(
                    f"--no-build but the instrumented APK {DEBUG_TEST_APK} is missing; "
                    "build :app:assembleDebugAndroidTest or drop --no-build"
                )
        else:
            print(f"gallery: building the {variant} instrumented APK", flush=True)
            subprocess.run(["./gradlew", "-q", ":app:assembleDebugAndroidTest", *flags],
                           cwd=ROOT, env=env, check=True)
        test_target = apk_dir / f"{variant}-androidTest.apk"
        shutil.copyfile(DEBUG_TEST_APK, test_target)
        frozen_tests[variant] = test_target
    return frozen, frozen_tests


def run_scenario(scenario: Scenario, registry: Registry, runs_dir: Path, *,
                 serial: str | None, timeout: float, apk: Path | None = None,
                 test_apk: Path | None = None,
                 assume_defaults: bool = False) -> ScenarioResult:
    args = scenario.command_args(registry.defaults)
    command = [sys.executable, str(VISUAL), "--output-dir", str(runs_dir)]
    if serial:
        command += ["--serial", serial]
    if apk is not None:
        command += ["--apk", str(apk)]
    if test_apk is not None and command_accepts(args[0], "--test-apk"):
        args += ["--test-apk", str(test_apk)]
    if assume_defaults:
        command += ["--assume-default-settings"]
    elif command_accepts(args[0], "--no-build"):
        args.append("--no-build")
    command += args
    print(f"gallery: [{scenario.id}] {' '.join(args)}", flush=True)
    started = time.monotonic()
    run_dirs: list[str] = []
    tail: list[str] = []
    error_message = ""
    timed_out = threading.Event()
    process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True)

    def kill() -> None:
        timed_out.set()
        process.kill()

    watchdog = threading.Timer(timeout, kill)
    watchdog.start()
    try:
        assert process.stdout is not None
        for line in process.stdout:
            tail = (tail + [line.rstrip()])[-25:]
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if event.get("event") == "error":
                error_message = str(event.get("message", ""))
            if event.get("event") == "screenshot":
                print(f"gallery: [{scenario.id}]   {event.get('label')}", flush=True)
                run_dir = str(Path(event["path"]).parent)
                if run_dir not in run_dirs:
                    run_dirs.append(run_dir)
            elif event.get("event") == "complete" and event.get("run_dir"):
                if event["run_dir"] not in run_dirs:
                    run_dirs.append(event["run_dir"])
        code = process.wait()
    finally:
        watchdog.cancel()
        if process.stdout is not None:
            process.stdout.close()
    frames = []
    for run_dir in run_dirs:
        for frame in read_run_frames(Path(run_dir)):
            frame.scenario = scenario.id
            frames.append(frame)
    seconds = time.monotonic() - started
    for frame in frames:
        frame.device = serial or ""
    if timed_out.is_set():
        return ScenarioResult(scenario, frames, "failed", f"timed out after {timeout:.0f} s",
                              seconds, run_dirs, serial or "")
    if code != 0:
        headline = error_message or (tail[-1] if tail else "")
        return ScenarioResult(scenario, frames, "failed",
                              f"exit {code}: {headline}\n\n" + "\n".join(tail[-8:]), seconds, run_dirs,
                              serial or "")
    return ScenarioResult(scenario, frames, "ok", "", seconds, run_dirs, serial or "")


# --------------------------------------------------------------------------
# Devices and sharding
# --------------------------------------------------------------------------

def sdk_dir() -> Path:
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(key):
            return Path(os.environ[key]).expanduser()
    props = ROOT / "local.properties"
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.partition("=")[2].replace("\\:", ":")).expanduser()
    return Path.home() / "Library/Android/sdk"


def adb(*args: str, serial: str | None = None, check: bool = False, timeout: float = 60) -> str:
    command = [str(sdk_dir() / "platform-tools/adb")] + (["-s", serial] if serial else []) + list(args)
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    if check and result.returncode != 0:
        raise GalleryError(f"adb {' '.join(args)} failed on {serial}: {result.stderr.strip()}")
    return result.stdout


def online_devices() -> list[str]:
    rows = [line.split() for line in adb("devices").splitlines()[1:]]
    return [row[0] for row in rows if len(row) >= 2 and row[1] == "device"]


def emulator_processes() -> list[str]:
    result = subprocess.run(["ps", "-axo", "args="], capture_output=True, text=True)
    return [line for line in result.stdout.splitlines() if "qemu-system" in line and "-avd" in line]


def kill_emulators(serials: Iterable[str], *, timeout: float = 60) -> None:
    """Shuts the given emulators down and waits until adb no longer lists them."""
    serials = [s for s in serials if s.startswith("emulator-")]
    for serial in serials:
        print(f"gallery: shutting down {serial}", flush=True)
        adb("emu", "kill", serial=serial)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and set(serials) & set(online_devices()):
        time.sleep(1)


def boot_emulators(count: int, avd: str, *, timeout: float = 300) -> list[str]:
    """Boots [count] read-only, host-GPU instances of [avd]; returns their serials.

    Several instances of one AVD only run when every one is `-read-only`, so a
    writable instance already running is shut down first. Instances are
    windowed with `-gpu host`: a headless instance falls back to software
    GLES (SwANGLE), whose frames would not match the host-GPU ones.
    """
    if count <= 0:
        return []
    writable = [line for line in emulator_processes() if "-read-only" not in line]
    if writable:
        print("gallery: a writable emulator blocks read-only instances; shutting it down", flush=True)
        kill_emulators(online_devices())
    emulator = sdk_dir() / "emulator/emulator"
    taken = {int(s.split("-")[1]) for s in online_devices() if s.startswith("emulator-")}
    ports, port = [], 5554
    while len(ports) < count:
        if port not in taken:
            ports.append(port)
        port += 2
    log_dir = ROOT / "build" / "gallery"
    log_dir.mkdir(parents=True, exist_ok=True)
    for port in ports:
        print(f"gallery: booting {avd} read-only on port {port}", flush=True)
        subprocess.Popen(
            [str(emulator), f"@{avd}", "-read-only", "-port", str(port), "-gpu", "host",
             "-no-snapshot-save", "-no-boot-anim", "-no-audio"],
            stdout=(log_dir / f"emulator-{port}.log").open("ab"), stderr=subprocess.STDOUT,
            start_new_session=True)
    serials = [f"emulator-{port}" for port in ports]
    deadline = time.monotonic() + timeout
    pending = set(serials)
    while pending and time.monotonic() < deadline:
        for serial in list(pending):
            if adb("shell", "getprop", "sys.boot_completed", serial=serial).strip() == "1":
                pending.discard(serial)
        time.sleep(2)
    if pending:
        kill_emulators(serials)
        raise GalleryError(f"emulators did not boot within {timeout:.0f} s: {sorted(pending)} "
                           f"(see build/gallery/emulator-<port>.log)")
    return serials


def device_profile(serial: str) -> dict[str, str]:
    """What must be identical across shards for their frames to be comparable."""
    def shell(*args: str) -> str:
        return adb("shell", *args, serial=serial).strip()

    return {
        "fingerprint": shell("getprop", "ro.build.fingerprint"),
        "size": shell("wm", "size").splitlines()[-1].split(":")[-1].strip() if shell("wm", "size") else "",
        "density": shell("wm", "density").splitlines()[-1].split(":")[-1].strip() if shell("wm", "density") else "",
        "fontScale": shell("settings", "get", "system", "font_scale"),
        "locale": shell("getprop", "persist.sys.locale") or shell("getprop", "ro.product.locale"),
        "animations": ",".join(shell("settings", "get", "global", key) for key in (
            "window_animation_scale", "transition_animation_scale", "animator_duration_scale")),
        "nightMode": shell("cmd", "uimode", "night"),
        # Host GPU vs software GLES draws the map differently.
        "gles": next((line.split(":", 1)[1].strip() for line in
                      adb("shell", "dumpsys", "SurfaceFlinger", serial=serial).splitlines()
                      if line.strip().startswith("GLES:")), ""),
    }


def check_coherent(profiles: dict[str, dict[str, str]]) -> None:
    serials = list(profiles)
    reference = profiles[serials[0]]
    problems = []
    for serial in serials[1:]:
        for key, value in profiles[serial].items():
            if value != reference.get(key):
                problems.append(f"{key}: {serials[0]}={reference.get(key)!r} but {serial}={value!r}")
    if problems:
        raise GalleryError("devices differ, so their screenshots would not be comparable:\n  "
                           + "\n  ".join(problems))


def reset_app_state(serial: str) -> None:
    """Every chain starts from the same app state, whichever shard runs it.

    Preferences and databases (route settings, B&W, saved rides, welcome
    completion) are cleared; files (the seeded basemap) are kept so shards do
    not each recopy hundreds of MB. Skipped when the app is not installed yet.
    """
    if not adb("shell", "pm", "path", APP_ID, serial=serial).strip():
        return
    adb("shell", "am", "force-stop", APP_ID, serial=serial)
    adb("shell", f"run-as {APP_ID} sh -c 'rm -rf shared_prefs databases'", serial=serial)


def build_chains(selected: list[Scenario], registry: Registry) -> list[list[Scenario]]:
    """Groups `after` dependencies into ordered chains, pulling in predecessors."""
    chosen = {s.id for s in selected}
    for scenario in list(selected):
        cursor = scenario
        while cursor.after is not None:
            if cursor.after not in chosen:
                print(f"gallery: adding {cursor.after} (needed before {cursor.id})", flush=True)
                chosen.add(cursor.after)
            cursor = registry.scenario(cursor.after)
    ordered = [s for s in registry.scenarios if s.id in chosen]
    roots = [s for s in ordered if s.after is None or s.after not in chosen]
    chains = []
    for root in roots:
        chain, cursor = [root], root
        while True:
            follower = next((s for s in ordered if s.after == cursor.id), None)
            if follower is None:
                break
            chain.append(follower)
            cursor = follower
        chains.append(chain)
    return chains


def previous_durations(output_root: Path) -> dict[str, float]:
    reports = sorted(output_root.glob("*/gallery.json"))
    for report in reversed(reports):
        try:
            data = json.loads(report.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        durations = {s["id"]: float(s["seconds"]) for s in data.get("scenarios", []) if s.get("seconds")}
        if durations:
            return durations
    return {}


def run_sharded(chains: list[list[Scenario]], registry: Registry, serials: list[str], runs_dir: Path, *,
                apks: dict[str, Path], timeout: float, durations: dict[str, float],
                on_result: Any, test_apks: dict[str, Path] | None = None) -> list[ScenarioResult]:
    """Longest chains first, one worker per device, each pulling the next chain."""
    queue = sorted(chains, key=lambda chain: -sum(
        durations.get(s.id, UNKNOWN_SCENARIO_SECONDS) for s in chain))
    lock = threading.Lock()
    results: list[ScenarioResult] = []

    def worker(serial: str) -> None:
        while True:
            with lock:
                if not queue:
                    return
                chain = queue.pop(0)
            reset_app_state(serial)
            for position, scenario in enumerate(chain):
                apk = apks.get(scenario.apk)
                test_apk = (test_apks or {}).get(scenario.apk)
                if apk is None:
                    result = ScenarioResult(scenario, [], "skipped",
                                            f"needs the {scenario.apk} APK (drop --no-build to build it)",
                                            serial=serial)
                else:
                    try:
                        # Only the chain's first scenario starts from the reset state.
                        result = run_scenario(scenario, registry, runs_dir / serial, serial=serial,
                                              timeout=timeout, apk=apk, test_apk=test_apk,
                                              assume_defaults=position == 0)
                    except Exception as error:  # a crashed worker must still report
                        result = ScenarioResult(scenario, [], "failed", f"gallery error: {error!r}",
                                                serial=serial)
                with lock:
                    results.append(result)
                    on_result(result)

    threads = [threading.Thread(target=worker, args=(serial,), daemon=True) for serial in serials]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    order = {s.id: i for i, s in enumerate(registry.scenarios)}
    return sorted(results, key=lambda r: order.get(r.scenario.id, len(order)))


# --------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------

def make_image(source: Path, target: Path, width: int) -> Path:
    """Writes a downscaled JPEG (sips on macOS, else Pillow), else copies the PNG."""
    target.parent.mkdir(parents=True, exist_ok=True)
    jpeg = target.with_suffix(".jpg")
    for existing in (jpeg, target.with_suffix(".png")):
        if existing.exists() and existing.stat().st_mtime >= source.stat().st_mtime:
            return existing
    if shutil.which("sips"):
        result = subprocess.run(
            ["sips", "-s", "format", "jpeg", "-s", "formatOptions", "80",
             "--resampleWidth", str(width), str(source), "--out", str(jpeg)],
            capture_output=True)
        if result.returncode == 0 and jpeg.exists():
            return jpeg
    try:
        from PIL import Image  # type: ignore

        with Image.open(source) as image:
            ratio = width / image.width
            image = image.convert("RGB").resize((width, round(image.height * ratio)))
            image.save(jpeg, "JPEG", quality=80)
        return jpeg
    except Exception:
        png = target.with_suffix(".png")
        shutil.copyfile(source, png)
        return png


def git_revision() -> str:
    try:
        head = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT,
                              capture_output=True, text=True, check=True).stdout.strip()
        dirty = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT,
                               capture_output=True, text=True).stdout.strip()
        return head + (" + uncommitted changes" if dirty else "")
    except Exception:
        return "unknown"


def write_gallery(results: list[ScenarioResult], registry: Registry, out_dir: Path, *,
                  width: int, inline: bool, title: str, meta: dict[str, Any] | None = None) -> dict[str, Any]:
    out_dir.mkdir(parents=True, exist_ok=True)
    image_dir = out_dir / "img"
    sections, missing_all, unregistered_all, failed = [], [], [], []
    captured = 0
    for result in results:
        classified = classify(result.scenario, result.frames, registry.shared)
        for frame in result.frames:
            if not frame.path.exists():
                continue
            name = f"{result.scenario.id}--{frame.path.stem}"
            image = make_image(frame.path, image_dir / name, width)
            if inline:
                mime = "image/jpeg" if image.suffix == ".jpg" else "image/png"
                frame.image = f"data:{mime};base64," + base64.b64encode(image.read_bytes()).decode()
            else:
                frame.image = f"img/{image.name}"
            captured += 1
        missing_all += [(result.scenario, d, n) for d, n in classified.missing]
        unregistered_all += [(result.scenario, f) for f in classified.unregistered]
        if result.status in ("failed", "skipped"):
            failed.append(result)
        sections.append((result, classified))
    if inline and image_dir.exists():
        shutil.rmtree(image_dir)

    required = sum(1 for result in results for d in result.scenario.displays
                   if d.required_count() > 0)
    summary = {
        "title": title,
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "revision": git_revision(),
        "frames": captured,
        "requiredDisplays": required,
        "missing": [{"scenario": s.id, "display": d.key, "captured": n, "needed": d.required_count()}
                    for s, d, n in missing_all],
        "unregistered": [{"scenario": s.id, "label": f.label} for s, f in unregistered_all],
        "failed": [{"scenario": r.scenario.id, "status": r.status, "detail": r.detail} for r in failed],
        "scenarios": [{"id": r.scenario.id, "status": r.status, "seconds": round(r.seconds, 1),
                       "frames": len(r.frames), "device": r.serial, "runDirs": r.run_dirs}
                      for r in results],
        **(meta or {}),
    }
    (out_dir / "gallery.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    (out_dir / "index.html").write_text(render_html(sections, summary), encoding="utf-8")
    return summary


def _e(value: Any) -> str:
    return html.escape(str(value), quote=True)


def _frame_html(frame: Frame, display_title: str, index: int) -> str:
    seen = "".join(f"<li>{_e(text)}</li>" for text in frame.visible[:60])
    details = (f'<details><summary>On screen ({len(frame.visible)})</summary><ul>{seen}</ul></details>'
               if frame.visible else "")
    return (
        f'<figure class="shot" data-search="{_e((display_title + " " + frame.label + " " + frame.scenario).lower())}">'
        f'<button class="thumb" type="button" data-index="{index}" aria-label="Enlarge {_e(frame.label)}">'
        f'<img loading="lazy" src="{_e(frame.image)}" alt="{_e(display_title)}: {_e(frame.label)}"></button>'
        f'<figcaption><b>{_e(display_title)}</b><code>{_e(frame.label)}</code>{details}</figcaption></figure>'
    )


def render_html(sections: list[tuple[ScenarioResult, Classified]], summary: dict[str, Any]) -> str:
    index = 0
    groups: dict[str, list[str]] = {}
    for result, classified in sections:
        scenario = result.scenario
        cards: list[str] = []
        setup_cards: list[str] = []
        for display, frames in classified.shared:
            if not display.setup:
                continue
            for frame in frames:
                if frame.image:
                    setup_cards.append(_frame_html(frame, display.title, index))
                    index += 1
        for display, frames in classified.by_display + [(d, f) for d, f in classified.shared if not d.setup]:
            for frame in frames:
                if frame.image:
                    cards.append(_frame_html(frame, display.title, index))
                    index += 1
            needed = display.required_count()
            if len(frames) < needed:
                cards.append(
                    f'<figure class="shot missing" data-search="{_e((display.title + " " + display.key).lower())}">'
                    f'<div class="hole">Not captured<br><code>{_e(display.key)}</code>'
                    f'<small>{len(frames)} of {needed}</small></div>'
                    f'<figcaption><b>{_e(display.title)}</b></figcaption></figure>')
        for frame in classified.unregistered:
            if frame.image:
                cards.append(_frame_html(frame, "Unregistered frame", index).replace(
                    'class="shot"', 'class="shot unregistered"', 1))
                index += 1
        status = {"ok": "", "failed": '<span class="pill bad">Failed</span>',
                  "skipped": '<span class="pill bad">Skipped</span>'}.get(result.status, "")
        detail = f'<pre class="err">{_e(result.detail)}</pre>' if result.detail else ""
        block = (
            f'<section class="scenario" id="{_e(scenario.id)}">'
            f'<header><h3>{_e(scenario.id)}</h3>{status}'
            f'<span class="meta">{len(result.frames)} frames · {result.seconds:.0f} s'
            f'{" · " + _e(result.serial) if result.serial else ""}</span>'
            f'<code class="cmd">visual.py {_e(" ".join(scenario.args))}</code></header>'
            f'{detail}<div class="grid">{"".join(cards)}</div>'
            + (f'<details class="setup"><summary>Setup frames ({len(setup_cards)})</summary>'
               f'<div class="grid">{"".join(setup_cards)}</div></details>' if setup_cards else "")
            + '</section>'
        )
        groups.setdefault(scenario.group, []).append(block)

    nav = "".join(f'<a href="#g-{_e(g)}">{_e(g)}</a>' for g in groups)
    body = "".join(
        f'<section class="group" id="g-{_e(g)}"><h2>{_e(g)}</h2>{"".join(blocks)}</section>'
        for g, blocks in groups.items())

    alerts = []
    if summary["failed"]:
        alerts.append('<li class="bad"><b>Failed or skipped scenarios:</b> ' +
                      ", ".join(f'<a href="#{_e(f["scenario"])}">{_e(f["scenario"])}</a>'
                                for f in summary["failed"]) + "</li>")
    if summary["missing"]:
        alerts.append('<li class="bad"><b>Missing displays:</b> ' +
                      ", ".join(f'{_e(m["scenario"])}/<code>{_e(m["display"])}</code>'
                                for m in summary["missing"]) + "</li>")
    if summary["unregistered"]:
        alerts.append('<li class="warn"><b>Unregistered frames</b> (add them to gallery-displays.json): ' +
                      ", ".join(f'<code>{_e(u["label"])}</code>' for u in summary["unregistered"]) + "</li>")
    if not alerts:
        alerts.append('<li class="good">Every registered display was captured.</li>')

    devices = summary.get("devices") or {}
    if devices:
        first = next(iter(devices.values()))
        alerts.append(
            f'<li class="good">{len(devices)} device{"s" if len(devices) != 1 else ""} '
            f'({_e(", ".join(devices))}), identical: {_e(first.get("size", ""))} @ '
            f'{_e(first.get("density", ""))} dpi, font {_e(first.get("fontScale", ""))}, '
            f'{_e(first.get("fingerprint", ""))}</li>')
    for name, digest in (summary.get("apks") or {}).items():
        alerts.append(f'<li class="good">{_e(name)} APK sha256 <code>{_e(digest[:16])}</code></li>')
    for name, digest in (summary.get("testApks") or {}).items():
        alerts.append(f'<li class="good">{_e(name)} androidTest APK sha256 <code>{_e(digest[:16])}</code></li>')
    return PAGE.format(
        title=_e(summary["title"]),
        generated=_e(summary["generatedAt"]),
        revision=_e(summary["revision"]),
        frames=summary["frames"],
        scenarios=len(sections),
        missing=len(summary["missing"]),
        unregistered=len(summary["unregistered"]),
        alerts="".join(alerts),
        nav=nav,
        body=body,
    )


PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>{title}</title>
<style>
/* Contact sheet: groups → scenarios → phone frames; a sticky filter bar; a lightbox. */
:root {{
  --bg:#f2f3f5; --panel:#fff; --ink:#1c1c1e; --muted:#5b6068; --line:#e0e2e5;
  --brand:#0b70c2; --bad:#b42318; --bad-bg:#fdecea; --warn:#946200; --warn-bg:#fff4d6;
  --good:#1a7f37; --good-bg:#e7f5ec; color-scheme:light;
  --body: system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
  --mono: ui-monospace,Menlo,Consolas,monospace;
}}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{
  --bg:#121315; --panel:#1c1c1e; --ink:#f2f3f5; --muted:#a3a8b0; --line:#2e3034;
  --brand:#5aa9ea; --bad:#ff8a80; --bad-bg:#3a1714; --warn:#f0c060; --warn-bg:#3a2e10;
  --good:#7fd69a; --good-bg:#16301f; color-scheme:dark; }} }}
:root[data-theme="dark"] {{
  --bg:#121315; --panel:#1c1c1e; --ink:#f2f3f5; --muted:#a3a8b0; --line:#2e3034;
  --brand:#5aa9ea; --bad:#ff8a80; --bad-bg:#3a1714; --warn:#f0c060; --warn-bg:#3a2e10;
  --good:#7fd69a; --good-bg:#16301f; color-scheme:dark; }}
* {{ box-sizing:border-box; }}
body {{ margin:0; background:var(--bg); color:var(--ink); font:14px/1.5 var(--body); }}
.wrap {{ max-width:1400px; margin:0 auto; padding-inline:16px; padding-block:24px 64px; }}
h1 {{ font-size:28px; margin:0 0 4px; }}
h2 {{ font-size:22px; margin:40px 0 8px; padding-top:8px; border-top:2px solid var(--ink); }}
h3 {{ font-size:15px; margin:0; font-family:var(--mono); }}
.sub {{ color:var(--muted); margin:0; }}
.stats {{ display:flex; flex-wrap:wrap; gap:8px 20px; margin:12px 0; color:var(--muted); }}
.stats b {{ color:var(--ink); font-variant-numeric:tabular-nums; }}
.alerts {{ list-style:none; padding:0; margin:12px 0; display:grid; gap:6px; }}
.alerts li {{ padding:8px 12px; border-radius:8px; overflow-wrap:anywhere; }}
.alerts .bad {{ background:var(--bad-bg); color:var(--bad); }}
.alerts .warn {{ background:var(--warn-bg); color:var(--warn); }}
.alerts .good {{ background:var(--good-bg); color:var(--good); }}
.alerts a {{ color:inherit; }}
.bar {{ position:sticky; top:env(safe-area-inset-top,0px); z-index:5; background:var(--bg);
  display:flex; flex-wrap:wrap; gap:8px; align-items:center; padding:10px 0; border-bottom:1px solid var(--line); }}
.bar input {{ flex:1 1 220px; min-width:0; padding:8px 12px; border-radius:8px; border:1px solid var(--line);
  background:var(--panel); color:var(--ink); font:inherit; }}
.bar nav {{ display:flex; flex-wrap:wrap; gap:6px; }}
.bar nav a {{ padding:6px 10px; border-radius:999px; background:var(--panel); border:1px solid var(--line);
  color:var(--ink); text-decoration:none; font-size:13px; }}
.scenario {{ margin-top:20px; }}
.scenario header {{ display:flex; flex-wrap:wrap; gap:6px 12px; align-items:baseline; margin-bottom:10px; }}
.meta {{ color:var(--muted); font-size:13px; }}
.cmd {{ flex-basis:100%; color:var(--muted); font:12px/1.4 var(--mono); overflow-wrap:anywhere; }}
.pill {{ font-size:12px; padding:2px 8px; border-radius:999px; }}
.pill.bad {{ background:var(--bad-bg); color:var(--bad); }}
.err {{ background:var(--bad-bg); color:var(--bad); padding:10px; border-radius:8px; overflow-x:auto;
  font:12px/1.4 var(--mono); white-space:pre-wrap; }}
.grid {{ display:grid; grid-template-columns:repeat(auto-fill,minmax(170px,1fr)); gap:16px; }}
.shot {{ margin:0; display:grid; gap:6px; align-content:start; min-width:0; }}
.thumb {{ padding:0; border:1px solid var(--line); border-radius:16px; overflow:hidden; background:#000;
  cursor:zoom-in; display:block; width:100%; }}
.thumb:focus-visible {{ outline:3px solid var(--brand); outline-offset:2px; }}
.thumb img {{ display:block; width:100%; height:auto; }}
.shot figcaption {{ font-size:12.5px; color:var(--muted); min-width:0; }}
.shot figcaption b {{ display:block; color:var(--ink); font-size:13px; }}
.shot code {{ font:11.5px var(--mono); overflow-wrap:anywhere; }}
.shot details {{ margin-top:4px; }}
.shot details ul {{ margin:4px 0 0; padding-left:16px; max-height:220px; overflow:auto; }}
.shot.unregistered .thumb {{ border:2px dashed var(--warn); }}
.hole {{ aspect-ratio:1280/2856; max-width:100%; border:2px dashed var(--bad); border-radius:16px; color:var(--bad);
  display:grid; place-content:center; text-align:center; gap:4px; padding:8px; background:var(--bad-bg); }}
.hidden {{ display:none !important; }}
.setup {{ margin-top:12px; }}
.setup > summary {{ cursor:pointer; color:var(--muted); font-size:13px; padding:4px 0; }}
.setup .grid {{ margin-top:8px; opacity:.9; }}
.lightbox {{ position:fixed; inset:0; background:rgba(0,0,0,.88); z-index:10; display:grid;
  grid-template-columns:auto 1fr auto; align-items:center; gap:8px; padding:16px; }}
.lightbox img {{ max-height:calc(100vh - 80px); max-width:100%; justify-self:center; border-radius:12px; }}
.lightbox p {{ grid-column:1/-1; color:#fff; text-align:center; margin:0; font:13px var(--mono); }}
.lightbox button {{ background:rgba(255,255,255,.15); color:#fff; border:0; border-radius:999px;
  width:44px; height:44px; font-size:20px; cursor:pointer; }}
@media (prefers-reduced-motion: no-preference) {{ .thumb img {{ transition:transform .15s; }}
  .thumb:hover img {{ transform:scale(1.02); }} }}
</style></head>
<body><div class="wrap">
<h1>{title}</h1>
<p class="sub">Generated {generated} · {revision}</p>
<div class="stats"><span><b>{frames}</b> frames</span><span><b>{scenarios}</b> scenarios</span>
<span><b>{missing}</b> missing</span><span><b>{unregistered}</b> unregistered</span></div>
<ul class="alerts">{alerts}</ul>
<div class="bar"><input id="filter" type="search" placeholder="Filter by title or label" aria-label="Filter frames">
<nav>{nav}</nav></div>
{body}
</div>
<div class="lightbox hidden" id="lightbox" role="dialog" aria-modal="true" aria-label="Screenshot">
<button type="button" id="prev" aria-label="Previous">&#8249;</button><img id="lb-img" alt="">
<button type="button" id="next" aria-label="Next">&#8250;</button><p id="lb-cap"></p></div>
<script>
(() => {{
  const filter = document.getElementById('filter');
  filter.addEventListener('input', () => {{
    const q = filter.value.trim().toLowerCase();
    document.querySelectorAll('.shot').forEach(el => el.classList.toggle('hidden', !!q && !el.dataset.search.includes(q)));
  }});
  const thumbs = [...document.querySelectorAll('.thumb')];
  const box = document.getElementById('lightbox'), img = document.getElementById('lb-img'), cap = document.getElementById('lb-cap');
  let at = 0;
  const show = i => {{
    const visible = thumbs.filter(t => !t.closest('.shot').classList.contains('hidden'));
    if (!visible.length) return;
    at = (i + visible.length) % visible.length;
    const t = visible[at], fig = t.closest('.shot');
    img.src = t.querySelector('img').src;
    cap.textContent = fig.querySelector('b').textContent + ' · ' + fig.querySelector('code').textContent;
    box.classList.remove('hidden');
  }};
  thumbs.forEach(t => t.addEventListener('click', () => {{
    const visible = thumbs.filter(x => !x.closest('.shot').classList.contains('hidden'));
    show(visible.indexOf(t));
  }}));
  document.getElementById('prev').onclick = e => {{ e.stopPropagation(); show(at - 1); }};
  document.getElementById('next').onclick = e => {{ e.stopPropagation(); show(at + 1); }};
  box.addEventListener('click', e => {{ if (e.target === box) box.classList.add('hidden'); }});
  document.addEventListener('keydown', e => {{
    if (box.classList.contains('hidden')) return;
    if (e.key === 'Escape') box.classList.add('hidden');
    if (e.key === 'ArrowLeft') show(at - 1);
    if (e.key === 'ArrowRight') show(at + 1);
  }});
}})();
</script></body></html>
"""


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def select_scenarios(registry: Registry, only: str | None, skip: str | None,
                     include_optional: bool) -> list[Scenario]:
    chosen = list(registry.scenarios)
    if only:
        wanted = [s.strip() for s in only.split(",") if s.strip()]
        chosen = [registry.scenario(s) for s in wanted]
    elif not include_optional:
        chosen = [s for s in chosen if not s.optional]
    if skip:
        skipped = {s.strip() for s in skip.split(",")}
        unknown = skipped - {s.id for s in registry.scenarios}
        if unknown:
            raise GalleryError(f"unknown scenario(s) to skip: {sorted(unknown)}")
        chosen = [s for s in chosen if s.id not in skipped]
    return chosen


def finish(summary: dict[str, Any], out_dir: Path, *, allow_missing: bool, strict: bool) -> int:
    print(json.dumps({"event": "gallery", "index": str(out_dir / "index.html"),
                      "frames": summary["frames"], "missing": len(summary["missing"]),
                      "unregistered": len(summary["unregistered"]),
                      "failed": [f["scenario"] for f in summary["failed"]]}), flush=True)
    if summary["failed"]:
        return 1
    if summary["missing"] and not allow_missing:
        return 1
    if summary["unregistered"] and strict:
        return 1
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--registry", type=Path, default=REGISTRY)
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("list", help="Print the registered scenarios and displays.")

    def output_options(p: argparse.ArgumentParser) -> None:
        p.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
        p.add_argument("--width", type=int, default=DEFAULT_IMAGE_WIDTH, help="Image width in px.")
        p.add_argument("--inline", action="store_true", help="Embed images: one self-contained HTML file.")
        p.add_argument("--title", default="CurveMaps display gallery")
        p.add_argument("--allow-missing", action="store_true", help="Exit 0 even if registered displays are missing.")
        p.add_argument("--strict", action="store_true", help="Also fail on unregistered frames.")

    run = commands.add_parser("run", help="Capture the scenarios and write the gallery.")
    run.add_argument("--only", help="Comma-separated scenario ids, in this order.")
    run.add_argument("--skip", help="Comma-separated scenario ids to leave out.")
    run.add_argument("--include-optional", action="store_true", help="Also run optional scenarios (buildings).")
    run.add_argument("--serial", help="One ADB serial to own for the whole run.")
    run.add_argument("--serials", help="Comma-separated ADB serials to shard across (each owned exclusively).")
    run.add_argument("--emulators", type=int, default=0,
                     help="Shard across N emulators, booting read-only instances of --avd as needed.")
    run.add_argument("--avd", default=DEFAULT_AVD, help="AVD for booted emulators.")
    run.add_argument("--keep-emulators", action="store_true",
                     help="Leave the emulators running (by default every emulator the run used is shut down).")
    run.add_argument("--no-build", action="store_true",
                     help="Use the existing (bundled) debug APK; map-less scenarios are skipped.")
    run.add_argument("--timeout", type=float, default=1800, help="Seconds per scenario.")
    output_options(run)

    build = commands.add_parser("build", help="Write a gallery from existing visual.py run directories.")
    build.add_argument("runs", nargs="+", type=Path)
    output_options(build)

    args = parser.parse_args(argv)
    registry = load_registry(args.registry)

    if args.command == "list":
        for scenario in registry.scenarios:
            flag = " (optional)" if scenario.optional else ""
            print(f"{scenario.id}{flag} [{scenario.group}]: visual.py {' '.join(scenario.command_args(registry.defaults))}")
            for display in scenario.displays:
                need = display.required_count()
                print(f"    {display.key:<44} {display.title}" + ("" if need == 1 else f"  (needs {need})"))
        return 0

    stamp = datetime.now().strftime("%Y%m%dT%H%M%S")
    out_dir = args.output_dir / stamp

    if args.command == "build":
        results = []
        for run_dir in args.runs:
            frames = read_run_frames(run_dir)
            scenario = best_scenario_for(frames, registry)
            if scenario is None:
                print(f"gallery: {run_dir} matches no scenario; skipped", file=sys.stderr)
                continue
            for frame in frames:
                frame.scenario = scenario.id
            results.append(ScenarioResult(scenario, frames, "ok", f"from {run_dir}", 0.0, [str(run_dir)]))
        summary = write_gallery(results, registry, out_dir, width=args.width, inline=args.inline, title=args.title)
        return finish(summary, out_dir, allow_missing=args.allow_missing, strict=args.strict)

    scenarios = select_scenarios(registry, args.only, args.skip, args.include_optional)
    chains = build_chains(scenarios, registry)

    booted: list[str] = []
    serials: list[str] = []
    try:
        if args.serials:
            serials = [s.strip() for s in args.serials.split(",") if s.strip()]
        elif args.serial:
            serials = [args.serial]
        elif args.emulators:
            wanted = min(args.emulators, len(chains))  # an idle shard adds nothing
            online = online_devices()
            if wanted > len(online[:wanted]) and any("-read-only" not in line for line in emulator_processes()):
                online = []  # a writable instance blocks read-only ones; boot_emulators replaces it
            serials = online[:wanted]
            booted = boot_emulators(wanted - len(serials), args.avd)
            serials += booted
        else:
            serials = online_devices()
            if len(serials) > 1:
                raise GalleryError("several devices are online: pass --serial, --serials or --emulators")
            if not serials:
                booted = boot_emulators(1, args.avd)
                serials = booted
        missing = [s for s in serials if s not in online_devices()]
        if missing:
            raise GalleryError(f"not online: {missing}")

        profiles = {serial: device_profile(serial) for serial in serials}
        check_coherent(profiles)
        needed = {s.apk for chain in chains for s in chain}
        # Scenarios whose command runs on-device JUnit need the frozen androidTest APK.
        test_variants = {s.apk for chain in chains for s in chain
                         if command_accepts(s.command_args(registry.defaults)[0], "--test-apk")}
        apks, test_apks = prebuild_apks(needed, out_dir / "apk", no_build=args.no_build,
                                        test_variants=test_variants)
        meta = {
            "devices": profiles,
            "apks": {name: sha256(path) for name, path in apks.items()},
            "testApks": {name: sha256(path) for name, path in test_apks.items()},
        }
        print(f"gallery: {sum(len(c) for c in chains)} scenarios in {len(chains)} chains "
              f"over {len(serials)} device(s): {', '.join(serials)}", flush=True)
        durations = previous_durations(args.output_dir)
        done: list[ScenarioResult] = []
        order = {sc.id: i for i, sc in enumerate(registry.scenarios)}

        def on_result(result: ScenarioResult) -> None:
            done.append(result)
            status = "ok" if result.status == "ok" else \
                f"{result.status.upper()} ({result.detail.splitlines()[0] if result.detail else ''})"
            print(f"gallery: [{result.scenario.id}] {status}, {len(result.frames)} frames, "
                  f"{result.seconds:.0f} s on {result.serial}", flush=True)
            # Rewrite the page after every scenario so a long run is inspectable early.
            write_gallery(sorted(done, key=lambda r: order.get(r.scenario.id, 0)), registry, out_dir,
                          width=args.width, inline=False, title=args.title, meta=meta)

        started = time.monotonic()
        results = run_sharded(chains, registry, serials, out_dir / "runs", apks=apks,
                              timeout=args.timeout, durations=durations, on_result=on_result,
                              test_apks=test_apks)
        meta["wallSeconds"] = round(time.monotonic() - started, 1)
        summary = write_gallery(results, registry, out_dir, width=args.width, inline=args.inline,
                                title=args.title, meta=meta)
        print(f"gallery: finished in {meta['wallSeconds']:.0f} s wall clock", flush=True)
        return finish(summary, out_dir, allow_missing=args.allow_missing, strict=args.strict)
    finally:
        # Every emulator the run used, booted or found running, on success,
        # failure or Ctrl-C alike.
        if not args.keep_emulators:
            kill_emulators(set(serials) | set(booted))


if __name__ == "__main__":
    try:
        sys.exit(main())
    except GalleryError as error:
        print(f"gallery: {error}", file=sys.stderr)
        sys.exit(2)
