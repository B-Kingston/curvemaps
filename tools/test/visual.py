#!/usr/bin/env python3
"""ADB-driven visual route runner and interactive inspection helpers.

This intentionally drives the installed app through Android's accessibility
tree and input service. The app stays in the foreground after each command so
an agent can inspect a screenshot, issue another command, and inspect again.
"""

from __future__ import annotations

import argparse
import bisect
import hashlib
import json
import math
import os
import re
import shlex
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from xml.etree import ElementTree


APP_ID = "com.curvemaps.app"
ACTIVITY = f"{APP_ID}/.MainActivity"
DEFAULT_AVD = "Pixel_10_Pro"
WINDOW_DUMP = "/sdcard/curvemaps-visual-window.xml"
ROUTE_SNAPSHOT = "cache/visual-route.json"
FIX_ACK = "cache/visual-fix-ack.json"
CAMERA_SNAPSHOT = "cache/visual-camera.json"
GUIDANCE_SNAPSHOT = "cache/visual-guidance.json"
LEVEL_PREFIX = "Ride style level "
BACK_TO_ENDPOINTS = "Back to From and To"
ROUND_TRIP_TOGGLE = "Round trip"
ROUND_TRIP_LENGTH = "Round trip length"
ROUND_TRIP_MENU_TITLE = "Measure the ride by"
ROUTE_CARD_SAVE = "Save to saved rides"
SAVED_ROUTES_CLOSE = "Close saved routes"
# Ride-style slider stops, in level order (RideStyle.kt).
STYLE_NAMES = ("fastest", "mostly-fast", "balanced", "curvy", "twisty", "very-twisty", "max-twisty")
# curve-shading frames: about 2 km of road across the screen, then a few hundred metres of bends.
CURVE_SHADING_TRANSITION_ZOOM = 13.5
CURVE_SHADING_BENDS_ZOOM = 15.0
STYLE_LABELS = ("Fastest", "Mostly fast", "Balanced", "Curvy", "Twisty", "Very twisty", "Max twisty")
# Ride-style distinctness thresholds, mirroring AlternativePolicy (Kotlin):
# two stops are the same ride unless one of them rides a continuous stretch of
# clamp(5% of the ride, 750 m, 3 km) that the other never uses, and they share
# under 99% of the longer ride. The debug route snapshot carries no edge ids,
# so the check below uses polyline proximity instead.
STYLE_SAME_RIDE_SHARE = 0.99
STYLE_MEANINGFUL_STRETCH_FRACTION = 0.05
STYLE_MIN_MEANINGFUL_STRETCH_M = 750.0
STYLE_MAX_MEANINGFUL_STRETCH_M = 3_000.0
# Routes from one graph repeat the exact node coordinates of every road they
# share, so this only absorbs coordinate rounding; it stays tight enough that
# a swapped side road is not mistaken for the road it left.
STYLE_GEOMETRY_TOLERANCE_M = 12.0
DEFAULT_OUTPUT = Path("build/visual-inspection")
# A tap right after a wait (or a capture right after it) reuses that wait's
# accessibility dump when nothing was sent to the device since and it is this
# fresh. `uiautomator dump` waits for UI idle, which a live map rarely is, so a
# dump costs 1-3 s. VISUAL_TREE_REUSE_S=0 disables reuse.
TREE_REUSE_SECONDS = float(os.environ.get("VISUAL_TREE_REUSE_S", "1.0"))

# Debug map-performance sweep (MapPerfSweepQueue / MapPerfSweepRunner).
PERF_SWEEP_ACTION = "com.curvemaps.app.DEBUG_MAP_PERF_SWEEP"
PERF_RESULT_PREFIX = "cache/map-perf-result-"
PERF_TARGETS = {
    "cbd": (-27.4679, 153.0281),
    "houses": (-27.4616, 153.0466),
}
DEFAULT_PERF_REPORT = Path("build/brisbane-performance-report.md")

# Debug Maps-screen preview (VisualMapsPreview): deterministic download,
# import, and delete states for screenshots without a live server or SAF.
MAPS_PREVIEW_ACTION = "com.curvemaps.app.DEBUG_VISUAL_MAPS_STATE"

# Mirrors RouteScreen's guidance camera policy. CameraFollowPolicyTest pins the
# Kotlin side, so drift shows up as a failed JVM suite before a visual run.
GUIDANCE_TILT_DEGREES = 58.0
GUIDANCE_RIDER_VERTICAL_FRACTION = 0.70
GUIDANCE_ZOOM_BANDS = ((7.0, 18.0), (14.0, 17.0), (22.0, 16.0), (31.0, 15.0), (math.inf, 14.0))
DEFAULT_SPEED_CASES = "0,8,15,23,32"

# Distance between injected fixes while riding the route. NavigationSession
# searches at most 40 vertices ahead of the matched position, so the runner
# advances in small steps (as progress_to does) instead of teleporting.
ROUTE_FOLLOW_STEP_M = 100.0

# Screen-size matrix suite (CommonScreenSizeTest). The matrix JSON is the single
# source of windows, font scales, and the official references the README cites;
# this runner must never copy those numbers. The suite renders production
# Compose surfaces only: the native MapLibre map and routing are covered by the
# separate on-device tiers.
UI_SIZE_TEST_CLASS = "com.curvemaps.app.ui.CommonScreenSizeTest"
UI_SIZE_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
UI_SIZE_TEST_PACKAGE = f"{APP_ID}.test"
UI_SIZE_LOG_TAG = "CurveMaps.UiSizes"
# Frame labels are ui-size-{caseId}-font-{fontScale}-{surface}; the surfaces are
# fixed so a gallery display can claim every frame of one surface with min counts.
UI_SIZE_LABEL_PREFIX = "ui-size-"
UI_SIZE_SURFACES = ("endpoints", "planner", "ride", "settings", "welcome")
UI_SIZE_MATRIX = Path("app/src/androidTest/assets/ui-screen-sizes.json")
UI_SIZE_CAPTURE_DIR = "cache/ui-size-captures"
UI_SIZE_APP_APK = Path("app/build/outputs/apk/debug/app-debug.apk")
UI_SIZE_TEST_APK = Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
UI_SIZE_TIMEOUT_S = 1800.0


class VisualError(RuntimeError):
    pass


def emit(event: str, **values: Any) -> None:
    print(json.dumps({"event": event, **values}, ensure_ascii=False), flush=True)


def run_optional(action: Any, *arguments: Any, **kwargs: Any) -> None:
    """Best-effort restore step that never masks the original failure.

    Screenshot runs must put the device back the way they found it (dark-map
    preference, notification access, ride state); a restore step failing is
    reported but must not replace the error that triggered the cleanup.
    """
    try:
        action(*arguments, **kwargs)
    except Exception as error:  # noqa: BLE001 - cleanup must not mask failures
        emit(
            "restore_warning",
            action=getattr(action, "__name__", str(action)),
            error=str(error),
        )


def sdk_directory(root: Path) -> Path:
    configured = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if configured:
        return Path(configured).expanduser()
    properties = root / "local.properties"
    if properties.is_file():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                value = line.partition("=")[2].replace("\\:", ":")
                return Path(value).expanduser()
    return Path.home() / "Library/Android/sdk"


class VisualHarness:
    def __init__(self, args: argparse.Namespace) -> None:
        # Extra Gradle arguments for build_and_install (e.g. -Pmoto.bundleData=false).
        self.extra_gradle_args: list[str] = []
        self.root = Path(__file__).resolve().parents[2]
        self.sdk = sdk_directory(self.root)
        self.adb = Path(os.environ.get("ADB", str(self.sdk / "platform-tools/adb")))
        self.emulator = Path(os.environ.get("EMULATOR", str(self.sdk / "emulator/emulator")))
        self.avd = args.avd or os.environ.get("ANDROID_AVD", DEFAULT_AVD)
        self.requested_serial = args.serial or os.environ.get("ANDROID_SERIAL")
        output_dir = Path(args.output_dir).expanduser() if args.output_dir else DEFAULT_OUTPUT
        if not output_dir.is_absolute():
            output_dir = self.root / output_dir
        self.output_dir = output_dir
        self.run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + f"-{os.getpid()}"
        self.run_dir = self.output_dir / self.run_id
        self.serial: str | None = None
        self.sequence = 0
        self.last_capture_sha = ""
        # A prebuilt APK to install instead of building (gallery shards share one).
        self.apk_override: Path | None = Path(args.apk).expanduser().resolve() if getattr(args, "apk", None) else None
        # Accessibility-dump reuse: bumped by every device command except the dump's own.
        self.device_generation = 0
        self.dumping = False
        self.tree_cache: tuple[float, int, list[dict[str, Any]], str] | None = None
        self.last_replay_fix: tuple[float, float, float | None, int | None] | None = None

    def command(
        self,
        *arguments: str,
        check: bool = True,
        timeout: float = 60,
        input_file: Any = None,
    ) -> subprocess.CompletedProcess[str]:
        if not self.dumping:
            self.device_generation += 1
        completed = subprocess.run(
            [str(self.adb), *(str(arg) for arg in arguments)],
            cwd=self.root,
            stdin=input_file,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        if check and completed.returncode != 0:
            details = completed.stderr.strip() or completed.stdout.strip()
            raise VisualError(
                f"ADB command failed ({completed.returncode}): "
                f"{' '.join(shlex.quote(str(arg)) for arg in arguments)}"
                + (f"\n{details}" if details else "")
            )
        return completed

    def device_command(
        self,
        *arguments: str,
        check: bool = True,
        timeout: float = 60,
        input_file: Any = None,
    ) -> subprocess.CompletedProcess[str]:
        if not self.serial:
            raise VisualError("No ADB device selected. Run the launch command first.")
        return self.command(
            "-s", self.serial, *arguments, check=check, timeout=timeout, input_file=input_file
        )

    def raw_device_bytes(self, *arguments: str, timeout: float = 60) -> bytes:
        if not self.serial:
            raise VisualError("No ADB device selected. Run the launch command first.")
        completed = subprocess.run(
            [str(self.adb), "-s", self.serial, *arguments],
            cwd=self.root,
            capture_output=True,
            timeout=timeout,
        )
        if completed.returncode != 0:
            details = completed.stderr.decode(errors="replace").strip()
            raise VisualError(f"ADB command failed: {' '.join(arguments)}\n{details}")
        return completed.stdout

    def device_rows(self) -> list[tuple[str, str]]:
        completed = self.command("devices", check=True)
        rows: list[tuple[str, str]] = []
        for line in completed.stdout.splitlines()[1:]:
            fields = line.split()
            if len(fields) >= 2:
                rows.append((fields[0], fields[1]))
        return rows

    def ensure_device(self, *, start_if_missing: bool) -> None:
        if not self.adb.is_file():
            raise VisualError(
                f"adb was not found at {self.adb}. Set ANDROID_SDK_ROOT, ANDROID_HOME, or ADB."
            )
        self.command("start-server", check=True, timeout=20)
        rows = self.device_rows()
        online = [serial for serial, state in rows if state == "device"]

        if self.requested_serial:
            if self.requested_serial not in online:
                if not start_if_missing:
                    raise VisualError(
                        f"Android device {self.requested_serial!r} is not online. "
                        "Check `adb devices`."
                    )
                if any(serial == self.requested_serial for serial, _ in rows):
                    self._wait_for_serial(self.requested_serial)
                    online = [serial for serial, state in self.device_rows() if state == "device"]
                else:
                    raise VisualError(
                        f"Android device {self.requested_serial!r} is not online. "
                        "Connect it or choose a running emulator."
                    )
            self.serial = self.requested_serial
        elif len(online) == 1:
            self.serial = online[0]
        elif len(online) > 1:
            raise VisualError(
                "More than one Android device is online. Set ANDROID_SERIAL or pass --serial."
            )
        elif start_if_missing:
            pending_emulators = [
                serial for serial, state in rows
                if serial.startswith("emulator-") and state in {"offline", "device"}
            ]
            if pending_emulators:
                self._wait_for_serial(pending_emulators[0])
                self.serial = pending_emulators[0]
            else:
                self.start_emulator()
                self._wait_for_any_device()
        else:
            raise VisualError("No Android device is online. Run `visual.py launch` to start the AVD.")

        self.wait_for_boot()

    def start_emulator(self) -> None:
        if not self.emulator.is_file():
            raise VisualError(
                f"Android emulator was not found at {self.emulator}. "
                "Set ANDROID_SDK_ROOT, ANDROID_HOME, or EMULATOR."
            )
        avds = subprocess.run(
            [str(self.emulator), "-list-avds"],
            cwd=self.root,
            capture_output=True,
            text=True,
            timeout=20,
        )
        if avds.returncode != 0:
            raise VisualError(avds.stderr.strip() or "Could not list Android virtual devices.")
        if self.avd not in avds.stdout.splitlines():
            raise VisualError(
                f"AVD {self.avd!r} was not found. Set ANDROID_AVD or create the AVD once."
            )
        self.output_dir.mkdir(parents=True, exist_ok=True)
        emulator_log = (self.output_dir / "emulator.log").open("ab")
        subprocess.Popen(
            [str(self.emulator), "-avd", self.avd, "-no-snapshot-load"],
            cwd=self.root,
            stdin=subprocess.DEVNULL,
            stdout=emulator_log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        emit("emulator_started", avd=self.avd, log=str(self.output_dir / "emulator.log"))

    def _wait_for_serial(self, wanted: str, timeout: float = 180) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            states = dict(self.device_rows())
            if states.get(wanted) == "device":
                return
            time.sleep(1)
        raise VisualError(f"Android device {wanted!r} did not come online within {timeout:g}s.")

    def _wait_for_any_device(self, timeout: float = 180) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            online = [serial for serial, state in self.device_rows() if state == "device"]
            if len(online) == 1:
                self.serial = online[0]
                return
            if len(online) > 1:
                raise VisualError(
                    "More than one Android device came online. Set ANDROID_SERIAL or pass --serial."
                )
            time.sleep(1)
        raise VisualError(f"The {self.avd} emulator did not appear within {timeout:g}s.")

    def wait_for_boot(self, timeout: float = 180) -> None:
        assert self.serial
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = self.device_command("shell", "getprop", "sys.boot_completed", check=False)
            if result.stdout.strip() == "1":
                return
            time.sleep(1)
        raise VisualError(f"Android device {self.serial} did not finish booting within {timeout:g}s.")

    def installed_apk_matches(self, apk: Path) -> bool:
        """True when the device already has byte-identical [apk] installed."""
        local = hashlib.sha256(apk.read_bytes()).hexdigest()
        path = self.device_command("shell", "pm", "path", APP_ID, check=False).stdout
        base = next((line.partition(":")[2].strip() for line in path.splitlines()
                     if line.startswith("package:") and line.strip().endswith("base.apk")), "")
        if not base:
            return False
        remote = self.device_command("shell", "sha256sum", base, check=False).stdout.split()
        return bool(remote) and remote[0] == local

    def build_and_install(self, *, build: bool, skip_map: bool, refresh_map: bool) -> None:
        apk = self.root / "app/build/outputs/apk/debug/app-debug.apk"
        if self.apk_override is not None:
            apk, build = self.apk_override, False
        if build:
            env = os.environ.copy()
            env.setdefault("JAVA_HOME", "/opt/homebrew/opt/openjdk@17")
            gradle = subprocess.run(
                ["./gradlew", ":app:assembleDebug", *self.extra_gradle_args],
                cwd=self.root,
                env=env,
                text=True,
            )
            if gradle.returncode != 0:
                raise VisualError(f"Gradle build failed with exit code {gradle.returncode}.")
        if not apk.is_file():
            raise VisualError(f"Debug APK is missing at {apk}. Build it or remove --no-build.")
        if self.installed_apk_matches(apk):
            emit("apk_reused", path=str(apk))
        else:
            self.device_command("install", "-r", str(apk), timeout=240)
        for permission in (
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        ):
            self.device_command("shell", "pm", "grant", APP_ID, permission, check=False)

        if not skip_map:
            archive = self.root / "data/tiles/queensland.pmtiles"
            if not archive.is_file() or archive.stat().st_size == 0:
                raise VisualError(
                    f"Basemap archive is missing at {archive}. Build it or pass --skip-map."
                )
            installed = self.device_command(
                "shell", f"run-as {APP_ID} sh -c 'test -s files/tiles/basemap.pmtiles'",
                check=False,
            )
            if refresh_map or installed.returncode != 0:
                self.device_command("shell", "run-as", APP_ID, "mkdir", "-p", "files/tiles")
                remote_command = f"run-as {APP_ID} sh -c 'cat > files/tiles/basemap.pmtiles'"
                with archive.open("rb") as map_file:
                    copied = self.command(
                        "-s", self.serial or "", "shell", remote_command,
                        check=False, timeout=300, input_file=map_file,
                    )
                if copied.returncode != 0:
                    raise VisualError(
                        "Could not install the PMTiles basemap into the emulator. "
                        + copied.stderr.strip()
                    )
                emit("basemap_installed", bytes=archive.stat().st_size)
            else:
                emit("basemap_reused", path="files/tiles/basemap.pmtiles")

    def launch_app(self, *, build: bool, skip_map: bool, refresh_map: bool) -> None:
        self.ensure_device(start_if_missing=True)
        self.build_and_install(build=build, skip_map=skip_map, refresh_map=refresh_map)
        self.device_command("shell", "am", "force-stop", APP_ID)
        self.device_command("shell", "am", "start", "-n", ACTIVITY)
        # A fresh install opens on the welcome carousel; skip it. Keep the
        # dialog-window check so a late system dialog cannot hide behind START.
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            nodes, _ = self.hierarchy()
            # Skip appears only after the safety page's "I understand".
            if any(node["description"] == "Skip welcome" for node in nodes):
                self.tap_node("description", "Skip welcome")
                continue
            if any(node["text"] == "I understand" for node in nodes):
                self.tap_node("text", "I understand")
                time.sleep(0.6)
                continue
            if any(node["text"] == "Welcome to CurveMaps" for node in nodes):
                self.capture("00-welcome-carousel")
                self.tap_node("text", "Next")
                time.sleep(0.6)
                continue
            if any(node["text"] == "START" for node in nodes):
                windows = self.device_command("shell", "dumpsys", "window", "windows").stdout
                blocking_dialog = any(
                    f"package={APP_ID}" in window and "DIM_BEHIND" in window
                    and "isVisible=true" in window
                    for window in re.split(r"(?=  Window #\d+ )", windows)
                )
                if not blocking_dialog:
                    break
            time.sleep(0.2)
        else:
            raise VisualError("The planner did not become available after startup setup.")
        self.capture("00-launched")

    def hierarchy(self, *, max_age: float | None = None) -> tuple[list[dict[str, Any]], str]:
        """Dumps the accessibility tree; with [max_age], may reuse the last dump.

        Reuse needs both: no device command since that dump, and the dump no
        older than [max_age] seconds. Polling waits always pass None.
        """
        cached = self.tree_cache
        if (
            max_age is not None and max_age > 0 and cached is not None
            and cached[1] == self.device_generation
            and time.monotonic() - cached[0] <= max_age
        ):
            return cached[2], cached[3]
        self.dumping = True
        try:
            nodes, xml_text = self._dump_hierarchy()
        finally:
            self.dumping = False
        self.tree_cache = (time.monotonic(), self.device_generation, nodes, xml_text)
        return nodes, xml_text

    def _dump_hierarchy(self) -> tuple[list[dict[str, Any]], str]:
        xml_text = ""
        last_error = ""
        for attempt in range(8):
            # A launch/IME transition can make uiautomator fail before writing
            # XML. Never accept the previous window's dump as the current UI.
            self.device_command("shell", "rm", "-f", WINDOW_DUMP)
            self.device_command(
                "shell", "uiautomator", "dump", "--compressed", WINDOW_DUMP,
                timeout=30, check=False,
            )
            dump = self.device_command("shell", "cat", WINDOW_DUMP, timeout=30, check=False)
            xml_text = dump.stdout
            if xml_text.lstrip().startswith("<?xml") or xml_text.lstrip().startswith("<hierarchy"):
                break
            last_error = dump.stderr.strip() or dump.stdout.strip() or "empty accessibility dump"
            time.sleep(0.25 if attempt < 3 else 0.5)
        else:
            raise VisualError(f"Android did not provide an accessibility tree: {last_error}")
        try:
            root = ElementTree.fromstring(xml_text)
        except ElementTree.ParseError as error:
            raise VisualError(f"Could not parse the Android accessibility tree: {error}") from error
        nodes: list[dict[str, Any]] = []
        for element in root.iter("node"):
            item = {
                "text": element.attrib.get("text", ""),
                "description": element.attrib.get("content-desc", ""),
                "bounds": element.attrib.get("bounds", ""),
                "class": element.attrib.get("class", ""),
                "clickable": element.attrib.get("clickable") == "true",
                "enabled": element.attrib.get("enabled") != "false",
                "focused": element.attrib.get("focused") == "true",
                "checked": element.attrib.get("checked") == "true",
                "checkable": element.attrib.get("checkable") == "true",
                "selected": element.attrib.get("selected") == "true",
                "visible": element.attrib.get("visible-to-user") != "false",
            }
            if any(item[key] for key in ("text", "description")):
                nodes.append(item)
        return nodes, xml_text

    def matching_nodes(
        self,
        kind: str,
        value: str,
        *,
        contains: bool = False,
        visible_only: bool = True,
        max_age: float | None = None,
    ) -> list[dict[str, Any]]:
        nodes, _ = self.hierarchy(max_age=max_age)
        key = "text" if kind == "text" else "description"
        matches = [
            node for node in nodes
            if (not visible_only or node["visible"])
            and (
                value in node[key] if contains else node[key] == value
            )
        ]
        return matches

    def wait_for_node(
        self,
        kind: str,
        value: str,
        *,
        contains: bool = False,
        timeout: float = 30,
    ) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            matches = self.matching_nodes(kind, value, contains=contains)
            if matches:
                return matches[0]
            time.sleep(0.5)
        nodes, _ = self.hierarchy()
        visible = [
            f"{node['text'] or node['description']} [{node['bounds']}]"
            for node in nodes if node["visible"] and (node["text"] or node["description"])
        ]
        raise VisualError(
            f"Timed out waiting for {kind} {value!r}. Visible nodes: "
            + "; ".join(visible[:35])
        )

    def wait_for_no_node(self, kind: str, value: str, *, contains: bool = False, timeout: float = 10) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if not self.matching_nodes(kind, value, contains=contains):
                return
            time.sleep(0.5)
        raise VisualError(f"{kind} {value!r} is still shown after {timeout:.0f} s.")

    def wait_for_any_text(self, values: tuple[str, ...], *, timeout: float = 30) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            nodes, _ = self.hierarchy()
            for node in nodes:
                if node["visible"] and node["text"] in values:
                    return node
            time.sleep(0.4)
        raise VisualError(f"Timed out waiting for any of these controls: {', '.join(values)}.")

    @staticmethod
    def center(bounds: str) -> tuple[int, int, int, int]:
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if not match:
            raise VisualError(f"Invalid Android screen bounds: {bounds!r}")
        return tuple(int(part) for part in match.groups())  # type: ignore[return-value]

    def select_node(
        self,
        kind: str,
        value: str,
        *,
        contains: bool = False,
        index: int = 0,
    ) -> dict[str, Any]:
        matches = self.matching_nodes(kind, value, contains=contains, max_age=TREE_REUSE_SECONDS)
        if not matches:
            raise VisualError(f"Could not find visible {kind} {value!r}.")
        if index < 0 or index >= len(matches):
            raise VisualError(
                f"{kind} {value!r} matched {len(matches)} node(s); index {index} is out of range."
            )
        return matches[index]

    def tap_point(self, x: int, y: int) -> None:
        self.device_command("shell", "input", "tap", str(x), str(y))

    def tap_node(
        self,
        kind: str,
        value: str,
        *,
        contains: bool = False,
        index: int = 0,
    ) -> dict[str, Any]:
        node = self.select_node(kind, value, contains=contains, index=index)
        x1, y1, x2, y2 = self.center(node["bounds"])
        self.tap_point((x1 + x2) // 2, (y1 + y2) // 2)
        return node

    def type_into(
        self,
        kind: str,
        selector: str,
        value: str,
        *,
        contains: bool = False,
        replace: bool = True,
        verify: bool = False,
    ) -> None:
        """Types into a field. With ``verify``, the field is read back and
        corrected: select-all can race the field's focus right after the tap,
        leaving prefilled text in place or eating the first typed characters.
        """
        if not value.isascii() or any(ord(char) < 32 for char in value):
            raise VisualError("ADB text entry accepts printable ASCII only.")
        node = self.tap_node(kind, selector, contains=contains)
        if replace:
            selected = self.device_command(
                "shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A",
                check=False,
            )
            if selected.returncode != 0:
                # Older Android images may not provide input keycombination.
                self.device_command("shell", "input", "keyevent", "KEYCODE_MOVE_END")
                visible_text = node["text"]
                if visible_text:
                    self.device_command(
                        "shell", "input", "keyevent",
                        *("KEYCODE_DEL" for _ in visible_text),
                    )
        else:
            # From/To select their whole entry when a tap focuses them; move
            # the caret to the end so the typed text is appended, not a
            # replacement.
            time.sleep(0.2)
            self.device_command("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        adb_text = value.replace("%", "%25").replace(" ", "%s")
        remote = "input text " + shlex.quote(adb_text)
        self.device_command("shell", remote)
        if not verify:
            return
        for _ in range(3):
            time.sleep(0.4)
            current = self.field_text(kind, selector, contains=contains)
            if current == value:
                return
            self.device_command("shell", "input", "keyevent", "KEYCODE_MOVE_END")
            if current:
                self.device_command(
                    "shell", "input", "keyevent",
                    *("KEYCODE_DEL" for _ in range(len(current) + 2)),
                )
            self.device_command("shell", remote)
        raise VisualError(f"The field {selector!r} did not accept {value!r}.")

    def field_text(self, kind: str, selector: str, *, contains: bool = False) -> str | None:
        """The editable text behind a field. Compose exposes a field's content
        description and its EditText as separate nodes with the same bounds."""
        matches = self.matching_nodes(kind, selector, contains=contains)
        if not matches:
            return None
        nodes, _ = self.hierarchy()
        bounds = matches[0]["bounds"]
        for node in nodes:
            if node["bounds"] == bounds and "EditText" in node["class"]:
                return node["text"]
        return matches[0]["text"]

    def press_key(self, key: str) -> None:
        self.device_command("shell", "input", "keyevent", key)

    def wait_for_keyboard(self, shown: bool, timeout: float = 10.0) -> None:
        """Wait for the real IME state before judging result placement."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            state = self.device_command("shell", "dumpsys", "input_method").stdout
            visible = bool(re.search(r"mInputShown=true", state) and
                           re.search(r"mIsInputViewShown=true", state))
            if visible and shown:
                windows = self.device_command("shell", "dumpsys", "window", "windows").stdout
                ime = next((window for window in re.split(r"(?=  Window #\d+ )", windows)
                            if "u0 InputMethod}" in window), "")
                inset = re.search(r"mGivenContentInsets=\[0,(\d+)\]", ime)
                frame = re.search(r"\bframe=\[\d+,(\d+)\]\[\d+,(\d+)\]", ime)
                # Gboard can report shown while displaying only a floating
                # hardware-keyboard toolbar. Require a real resized viewport.
                visible = bool(inset and frame and
                               int(frame[2]) - int(frame[1]) - int(inset[1]) > 200)
            if visible == shown:
                # Let the resize animation's final layout land as well.
                time.sleep(0.3)
                return
            time.sleep(0.1)
        raise VisualError(f"Keyboard did not become {'visible' if shown else 'hidden'}.")

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 450) -> None:
        self.device_command(
            "shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(duration_ms)
        )

    def scroll_down(self, *, fraction: float = 0.45) -> None:
        """Swipes up inside the screen to reveal content below the fold."""
        nodes, _ = self.hierarchy()
        # Swipe across the extent of everything visible. Picking one "big"
        # node is fragile: a banner or card can be the first large node, and
        # a swipe inside it never travels far enough to scroll the screen.
        bounds: tuple[int, int, int, int] | None = None
        for node in nodes:
            if not node["visible"]:
                continue
            match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node["bounds"])
            if not match:
                continue
            x1, y1, x2, y2 = (int(part) for part in match.groups())
            if bounds is None:
                bounds = (x1, y1, x2, y2)
            else:
                bounds = (min(bounds[0], x1), min(bounds[1], y1), max(bounds[2], x2), max(bounds[3], y2))
        if bounds is not None and (bounds[2] - bounds[0] <= 100 or bounds[3] - bounds[1] <= 100):
            bounds = None
        if bounds is None:
            raise VisualError("Could not determine the screen bounds for scrolling.")
        x1, y1, x2, y2 = bounds
        start_fraction = 0.72 if fraction >= 0 else 0.28
        start_y = y1 + int((y2 - y1) * start_fraction)
        end_y = y1 + int((y2 - y1) * (start_fraction - fraction))
        self.swipe((x1 + x2) // 2, start_y, (x1 + x2) // 2, end_y)

    def scroll_to_node(
        self,
        kind: str,
        value: str,
        *,
        contains: bool = False,
        max_swipes: int = 6,
        toward_top: bool = False,
    ) -> dict[str, Any]:
        """Scrolls the current screen until a visible node matches, then returns it."""
        for _ in range(max_swipes):
            matches = self.matching_nodes(kind, value, contains=contains)
            if matches:
                return matches[0]
            self.scroll_down(fraction=-0.45 if toward_top else 0.45)
            time.sleep(0.5)
        return self.wait_for_node(kind, value, contains=contains, timeout=5)

    def broadcast_maps_preview(self, scenario: str) -> None:
        """Shows one deterministic Maps-screen state (debug builds only)."""
        self.device_command(
            "shell", "am", "broadcast",
            "-a", MAPS_PREVIEW_ACTION,
            "-p", APP_ID,
            "--es", "scenario", scenario,
            check=False,
        )
        time.sleep(0.3)

    def capture(self, label: str, *, include_tree: bool = True) -> Path:
        self.ensure_capture_dirs()
        self.sequence += 1
        safe_label = re.sub(r"[^a-zA-Z0-9_-]+", "-", label).strip("-") or "screen"
        png = self.raw_device_bytes("exec-out", "screencap", "-p", timeout=60)
        if not png.startswith(b"\x89PNG\r\n\x1a\n"):
            raise VisualError("The emulator did not return a PNG screenshot.")
        file_path = self.run_dir / f"{self.sequence:02d}-{safe_label}.png"
        file_path.write_bytes(png)
        self.last_capture_sha = hashlib.sha256(png).hexdigest()
        latest_tmp = self.output_dir / "latest.png.tmp"
        latest_tmp.write_bytes(png)
        latest_tmp.replace(self.output_dir / "latest.png")

        visible_nodes: list[dict[str, Any]] = []
        if include_tree:
            try:
                visible_nodes, _ = self.hierarchy(max_age=TREE_REUSE_SECONDS)
            except VisualError as error:
                visible_nodes = [{"text": f"accessibility dump failed: {error}", "description": "", "visible": True}]
        event = {
            "event": "screenshot",
            "label": label,
            "path": str(file_path),
            "latest": str(self.output_dir / "latest.png"),
            "sha256": hashlib.sha256(png).hexdigest(),
            "visible": [
                {"text": node["text"], "description": node["description"]}
                for node in visible_nodes if node["visible"]
            ],
        }
        self.run_dir.mkdir(parents=True, exist_ok=True)
        with (self.run_dir / "events.jsonl").open("a", encoding="utf-8") as events:
            events.write(json.dumps({"at": utc_now(), **event}, ensure_ascii=False) + "\n")
        emit("screenshot", **{key: value for key, value in event.items() if key != "event"})
        return file_path

    def capture_routing_start(self, frames: int = 8) -> None:
        """Capture "04-routing" while the Finding card shows.

        START slides to the ride styles at once, and the "Finding this ride…"
        card (its loading indicator always moving) stays only until the first
        ride is searched, about a second. An accessibility dump takes longer
        than that, so this takes a burst of bare screenshots right after the
        tap. The frames before the pane had moved equal the endpoints frame and
        the ones after the card settled repeat each other; `04-routing` is the
        middle of the changing frames between them (the last frame when none
        changed), and the rest are filed as routing-NN.
        """
        before = self.last_capture_sha
        shots: list[bytes] = []
        for _ in range(frames):
            png = self.raw_device_bytes("exec-out", "screencap", "-p", timeout=60)
            if not png.startswith(b"\x89PNG\r\n\x1a\n"):
                raise VisualError("The emulator did not return a PNG screenshot.")
            shots.append(png)
        shas = [hashlib.sha256(png).hexdigest() for png in shots]
        moving = [
            index for index, sha in enumerate(shas)
            if sha != before and (index + 1 >= len(shas) or shas[index + 1] != sha)
        ]
        chosen = moving[len(moving) // 2] if moving else len(shots) - 1
        for index, png in enumerate(shots):
            if index != chosen and (not moving or index not in moving):
                continue
            self.adopt_frame("04-routing" if index == chosen else f"routing-burst-{index:02d}", png)
        self.last_capture_sha = shas[-1]

    def adopt_frame(self, label: str, png: bytes, *, metadata: dict[str, Any] | None = None) -> Path:
        """Files an externally produced PNG as a harness frame, same as capture().

        The screen-size suite renders and exports its own frames on the device;
        this records them in the run's events.jsonl with the suite's labels so
        gallery.py can classify and publish them. No accessibility dump: the
        frames come from JVM-side Compose, not from the app's window.
        """
        self.ensure_capture_dirs()
        self.sequence += 1
        safe_label = re.sub(r"[^a-zA-Z0-9_-]+", "-", label).strip("-") or "screen"
        file_path = self.run_dir / f"{self.sequence:02d}-{safe_label}.png"
        file_path.write_bytes(png)
        latest_tmp = self.output_dir / "latest.png.tmp"
        latest_tmp.write_bytes(png)
        latest_tmp.replace(self.output_dir / "latest.png")
        event = {
            "event": "screenshot",
            "label": label,
            "path": str(file_path),
            "latest": str(self.output_dir / "latest.png"),
            "sha256": hashlib.sha256(png).hexdigest(),
            "visible": [],
            **(metadata or {}),
        }
        with (self.run_dir / "events.jsonl").open("a", encoding="utf-8") as events:
            events.write(json.dumps({"at": utc_now(), **event}, ensure_ascii=False) + "\n")
        emit("screenshot", **{key: value for key, value in event.items() if key != "event"})
        return file_path

    def ensure_capture_dirs(self) -> None:
        self.run_dir.mkdir(parents=True, exist_ok=True)
        self.output_dir.mkdir(parents=True, exist_ok=True)

    def set_style(self, target: int, *, capture_drag: bool = False) -> None:
        if not 0 <= target < len(STYLE_NAMES):
            raise VisualError(f"Ride style must be 0–{len(STYLE_NAMES) - 1} ({', '.join(STYLE_NAMES)}).")
        node = self.wait_for_node("description", LEVEL_PREFIX, contains=True, timeout=10)
        match = re.search(r"(\d+)\s*$", node["description"])
        if not match:
            raise VisualError(f"Could not read the current ride style: {node['description']!r}.")
        current = int(match.group(1))
        x1, y1, x2, y2 = self.center(node["bounds"])
        cy = round((y1 + y2) / 2)
        # Track inset is 24 dp inside the slider's accessible 64 dp target.
        inset = (y2 - y1) * 24 / 64
        span = x2 - x1 - 2 * inset
        direction = 1 if target > current else -1
        for level in range(current, target, direction):
            sx = round(x1 + inset + span * (level + 0.5) / len(STYLE_NAMES))
            ex = round(x1 + inset + span * (level + direction + 0.5) / len(STYLE_NAMES))
            desired = level + direction
            if capture_drag:
                self.tree_cache = None
                process = subprocess.Popen(
                    [str(self.adb), "-s", self.serial, "shell", "input", "swipe",
                     str(sx), str(cy), str(ex), str(cy), "6000"],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                )
                try:
                    time.sleep(4.3)
                    self.capture(f"style-drag-{desired}", include_tree=False)
                finally:
                    _, errors = process.communicate(timeout=10)
                if process.returncode:
                    raise VisualError(f"Slider drag failed: {errors.decode(errors='replace')}")
                self.tree_cache = None
            else:
                self.swipe(sx, cy, ex, cy, 650)
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                descriptions = [
                    candidate["description"]
                    for candidate in self.matching_nodes(
                        "description", LEVEL_PREFIX, contains=True
                    )
                ]
                if f"{LEVEL_PREFIX}{desired}" in descriptions:
                    break
                time.sleep(0.25)
            else:
                raise VisualError(
                    f"Ride style slider did not reach level {desired}; current labels: {descriptions}."
                )
        time.sleep(0.35)

    def wait_for_route_card(
        self,
        level: int,
        timeout: float,
        *,
        frames: str | None = None,
        frame_interval: float = 2.0,
    ) -> str:
        """Wait until the route card shows the ride for stop [level].

        The styles pane (and its RIDE pill) appears the moment START is
        pressed; the card holds a "Finding the … ride" placeholder until that
        style's search is done, so the card, not RIDE, says a route is ready.
        With [frames], captures `<frames>-NN` every [frame_interval] seconds
        meanwhile, so the wave can be seen growing hump by hump.
        """
        label = STYLE_LABELS[level]
        deadline = time.monotonic() + timeout
        next_frame = time.monotonic()
        frame_index = 1
        while time.monotonic() < deadline:
            for description in self.route_card_descriptions():
                if description.startswith(f"Route 1: {label},"):
                    return description
            errors = self.matching_nodes("text", "No match", contains=True) + self.matching_nodes(
                "text", "No meaningfully different", contains=True
            )
            if errors:
                raise VisualError("Route failed: " + errors[0]["text"])
            if frames is not None and time.monotonic() >= next_frame:
                self.capture(f"{frames}-{frame_index:02d}", include_tree=False)
                frame_index += 1
                next_frame = time.monotonic() + frame_interval
            time.sleep(0.4)
        raise VisualError(f"The {label} route did not appear within {timeout:g}s.")

    def route_card_descriptions(self) -> list[str]:
        return [
            node["description"]
            for node in self.matching_nodes("description", "Route ", contains=True)
            if re.match(r"Route \d+:", node["description"])
        ]

    def capture_styles(self, args: argparse.Namespace) -> None:
        """Route once, then capture every ride-style stop's card and geometry.

        Each stop's debug route export is written beside its screenshot, and
        the seven rides must be meaningfully different: a duplicate or a tiny
        road swap fails the run instead of passing as "just a different ride".
        """
        # A previous run's export must never pass as this stop's geometry, so
        # settle the device first and remember the export that already exists.
        self.ensure_device(start_if_missing=not getattr(args, "reuse_app", False))
        try:
            previous_created = self.read_route_snapshot().get("createdAtMillis")
            if not isinstance(previous_created, int):
                previous_created = None
        except (VisualError, OSError):
            previous_created = None
        plan_args = argparse.Namespace(**vars(args))
        plan_args.style = 0
        plan_args.plan_only = True
        plan_args.route_index = 1
        self.route(plan_args)
        self.ensure_capture_dirs()
        rides: list[dict[str, Any]] = []
        for level, name in enumerate(STYLE_NAMES):
            if level > 0:
                self.set_style(level, capture_drag=True)
                # The route changes while the finger is down. The drag frame
                # also shows up to two grey routes from the previous stops.
                self.wait_for_route_card(level, args.timeout)
                time.sleep(1.0)
            snapshot = self.wait_for_route_snapshot_refresh(previous_created, args.timeout)
            created = snapshot.get("createdAtMillis")
            if isinstance(created, int):
                previous_created = created
            selected = snapshot["routes"][int(snapshot.get("selectedIndex", 0))]
            points = selected["points"]
            _, distance_m = cumulative_distance(points)
            exported = self.run_dir / f"style-{level}-{name}.json"
            exported.write_text(json.dumps(snapshot, ensure_ascii=False), encoding="utf-8")
            cards = self.route_card_descriptions()
            emit(
                "style_ready",
                level=level,
                style=name,
                cards=cards,
                snapshot=str(exported),
                points=len(points),
                distanceM=round(distance_m, 1),
            )
            rides.append({"level": level, "style": name, "points": points, "cards": cards})
            self.capture(f"style-{level}-{name}")
        # Back slides the From/To fields in again with the route kept on the
        # map; START answers from the finished plan and slides the styles back.
        self.tap_node("description", "Back to From and To")
        self.wait_for_node("text", "START", timeout=10)
        time.sleep(0.8)
        self.capture("styles-back-to-fields")
        self.tap_node("text", "START")
        self.wait_for_route_card(len(STYLE_NAMES) - 1, args.timeout)
        time.sleep(0.8)
        self.capture("styles-reopened")
        # Verify last, so a failing stop still leaves every frame as evidence.
        self.verify_style_distinctness(rides)

    def capture_curve_shading(self, args: argparse.Namespace) -> None:
        """Plan one ride style and capture its route line easing toward the curvy colour.

        The overview is the planner's own framing; the debug camera then frames
        where the twistiest stretch begins (the colour transition) and the
        twistiest bends themselves, flat, so the gradient can be judged up close.
        """
        plan_args = argparse.Namespace(**vars(args))
        plan_args.plan_only = True
        plan_args.route_index = 1
        self.route(plan_args)
        self.ensure_capture_dirs()
        snapshot = self.read_route_snapshot()
        points = snapshot["routes"][int(snapshot.get("selectedIndex", 0))]["points"]
        transition, bends = twistiest_stretch(points)
        emit("curve_shading", style=STYLE_NAMES[args.style], points=len(points),
             transition=points[transition], bends=points[bends])
        self.capture("curve-shading-overview")
        lat, lon = points[transition]
        self.inspect_camera(lat, lon, CURVE_SHADING_TRANSITION_ZOOM, 0.0)
        self.capture("curve-shading-transition")
        lat, lon = points[bends]
        self.inspect_camera(lat, lon, CURVE_SHADING_BENDS_ZOOM, 0.0)
        self.capture("curve-shading-bends")

    def capture_round_trip(self, args: argparse.Namespace) -> None:
        """Plan a round trip from --from: toggle it beside To, pick the measure
        on the segmented selector in the panel, step to the wished time or
        distance (or a back-by time), then capture every stop's loop.

        Every stop must be a loop that comes back to its start, and the seven
        loops must be different rides; a stop that fails either is the bug.
        """
        problem = None if args.back_by else round_trip_on_grid(
            None if args.distance is not None else args.minutes, args.distance)
        if problem:
            raise VisualError(problem)
        if not args.reuse_app:
            self.launch_app(build=not args.no_build, skip_map=args.skip_map, refresh_map=args.refresh_map)
        else:
            self.ensure_device(start_if_missing=False)
            self.wait_for_any_text(("START", "RIDE"), timeout=15)
        if self.matching_nodes("description", BACK_TO_ENDPOINTS):
            self.tap_node("description", BACK_TO_ENDPOINTS)
            self.wait_for_node("text", "START", timeout=10)
        try:
            previous_created = self.read_route_snapshot().get("createdAtMillis")
            if not isinstance(previous_created, int):
                previous_created = None
        except (VisualError, OSError):
            previous_created = None
        self.type_into("description", "From", args.from_text)
        time.sleep(0.4)
        self.capture("round-trip-01-from")
        toggle = self.wait_for_node("description", ROUND_TRIP_TOGGLE, timeout=10)
        if self.matching_nodes("description", ROUND_TRIP_LENGTH, contains=True):
            raise VisualError("Round trip was already on before the toggle was tapped.")
        # Switching it on also closes From's keyboard and results.
        x1, y1, x2, y2 = self.center(toggle["bounds"])
        self.tap_point((x1 + x2) // 2, (y1 + y2) // 2)
        row = self.wait_for_node("description", ROUND_TRIP_LENGTH, contains=True, timeout=10)
        self.capture("round-trip-02-toggled-on")
        emit("round_trip_on", row=row["description"])

        # The measure selector (a segmented button) shows with the stepper.
        self.wait_for_node("description", ROUND_TRIP_MENU_TITLE, timeout=10)
        time.sleep(0.4)
        self.capture("round-trip-03-measure-selector")
        if args.back_by:
            option, amount = "Back by", None
        elif args.distance is not None:
            option, amount = "Distance", args.distance
        else:
            option, amount = "Ride time", args.minutes
        if option == "Ride time":
            # Ride time is already selected: picking it shows nothing, so the
            # picked frame selects Distance first, then the wished measure.
            self.tap_node("text", "Distance")
            time.sleep(0.4)
            self.capture("round-trip-04-measure-picked")
            self.tap_node("text", option)
            time.sleep(0.4)
        else:
            self.tap_node("text", option)
            time.sleep(0.4)
            self.capture("round-trip-04-measure-picked")
        row = self.wait_for_node("description", ROUND_TRIP_LENGTH, contains=True, timeout=10)
        first = "Shorter round trip" if self.round_trip_at_maximum(row, amount) else "Longer round trip"
        self.tap_node("description", first)
        time.sleep(0.3)
        self.capture("round-trip-05-stepped")
        if amount is not None:
            self.step_round_trip_to(amount)
        row = self.wait_for_node("description", ROUND_TRIP_LENGTH, contains=True, timeout=10)
        expected = row["description"].split(", ", 1)[-1]
        time.sleep(0.3)
        self.capture("round-trip-06-length-set")
        emit("round_trip_length", row=row["description"])

        self.tap_node("text", "START")
        emit("routing_started", from_text=args.from_text, round_trip=expected)
        self.wait_for_route_card(0, args.timeout, frames="round-trip-growing", frame_interval=args.progress_interval)
        time.sleep(0.8)
        rides: list[dict[str, Any]] = []
        for level, name in enumerate(STYLE_NAMES):
            if level > 0:
                self.set_style(level)
                self.wait_for_route_card(level, args.timeout)
                time.sleep(1.0)
            snapshot = self.wait_for_route_snapshot_refresh(previous_created, args.timeout)
            created = snapshot.get("createdAtMillis")
            if isinstance(created, int):
                previous_created = created
            selected = snapshot["routes"][int(snapshot.get("selectedIndex", 0))]
            points = selected["points"]
            _, distance_m = cumulative_distance(points)
            gap = distance_metres(points[0][0], points[0][1], points[-1][0], points[-1][1])
            exported = self.run_dir / f"round-trip-style-{level}-{name}.json"
            exported.write_text(json.dumps(snapshot, ensure_ascii=False), encoding="utf-8")
            cards = self.route_card_descriptions()
            emit("round_trip_style_ready", level=level, style=name, cards=cards, snapshot=str(exported),
                 distanceM=round(distance_m, 1), startToFinishM=round(gap, 1))
            rides.append({"level": level, "style": name, "points": points, "cards": cards, "gap": gap})
            self.capture(f"round-trip-style-{level}-{name}")
        self.tap_node("description", BACK_TO_ENDPOINTS)
        self.wait_for_node("text", "START", timeout=10)
        time.sleep(0.8)
        self.capture("round-trip-07-back-to-fields")
        # Verify last, so a failing stop still leaves every frame as evidence.
        open_loops = [f"{ride['style']} ends {ride['gap']:.0f} m from its start" for ride in rides if ride["gap"] > 50.0]
        if open_loops:
            raise VisualError("Round trips must come back to their start: " + "; ".join(open_loops))
        self.verify_style_distinctness(rides)

    def round_trip_at_maximum(self, row: dict[str, Any], target: int | None) -> bool:
        """Whether the first demo press must go shorter: the value is already at its range's end."""
        current = round_trip_amount(row["description"])
        if current is None:
            return False
        return current >= (400 if row["description"].endswith(" km") else 480)

    def step_round_trip_to(self, amount: int) -> None:
        """Press the round trip's stepper until its value reads [amount] (minutes or km)."""
        for _ in range(80):
            row = self.wait_for_node("description", ROUND_TRIP_LENGTH, contains=True, timeout=10)
            current = round_trip_amount(row["description"])
            if current is None:
                raise VisualError(f"Unreadable round trip length: {row['description']}")
            if current == amount:
                return
            self.tap_node("description", "Longer round trip" if current < amount else "Shorter round trip")
            time.sleep(0.15)
        raise VisualError(f"The round trip stepper never reached {amount}.")

    def wait_for_text_gone(self, text: str, timeout: float) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if not self.matching_nodes("text", text):
                return
            time.sleep(0.2)
        raise VisualError(f"'{text}' was still shown after {timeout:.0f} s.")

    def set_route_settings(
        self,
        *,
        avoid_motorways: bool | None,
        block_unpaved: bool | None,
        ride_suggestions: bool | None = None,
        include_elevation: bool | None = None,
        avoid_stops_towns: bool | None = None,
    ) -> None:
        if all(value is None for value in (avoid_motorways, block_unpaved, ride_suggestions,
                                           include_elevation, avoid_stops_towns)):
            return
        self.open_settings_menu()
        self.capture("settings-menu-open")
        self.tap_node("text", "Route settings")
        self.wait_for_node("description", "Avoid motorways", timeout=10)
        self.capture("route-settings-open")
        # Every row is a whole-row toggle, so the row's description is the
        # target. uiautomator on current emulator images reports
        # checked="false" for every Compose toggle, so each state is read from
        # (and verified against) the app's persisted route_preferences instead
        # of the tree. The ride-factor tick boxes sit below the switches.
        # (label, preference key, wanted, the app's default when unsaved)
        rows = (("Avoid motorways", "avoid_motorways", avoid_motorways, False),
                ("Block unpaved roads", "block_unpaved_roads", block_unpaved, False),
                ("Ride suggestions", "ride_suggestions", ride_suggestions, True),
                ("Hills and elevation", "include_elevation", include_elevation, False),
                ("Avoid stops and towns", "avoid_stops_and_towns", avoid_stops_towns, False))
        wanted_flags = {key: wanted for _, key, wanted, _ in rows if wanted is not None}
        defaults = {key: default for _, key, _, default in rows}
        persisted = self.route_preference_flags()
        for label, key, wanted, default in rows:
            if wanted is None or persisted.get(key, default) == wanted:
                continue
            self.scroll_to_node("description", label)
            # Let a scroll fling settle: a tap during a fling only stops it.
            time.sleep(0.8)
            self.tap_node("description", label)
            time.sleep(0.4)
        if include_elevation is not None or avoid_stops_towns is not None:
            self.capture("route-settings-factors")
        self.capture("route-settings-edited")
        self.tap_node("text", "Apply")
        self.wait_for_any_text(("START", "RIDE"), timeout=60)
        if wanted_flags:
            persisted = self.route_preference_flags()
            wrong = {key: persisted.get(key, defaults[key]) for key, wanted in wanted_flags.items()
                     if persisted.get(key, defaults[key]) != wanted}
            if wrong:
                raise VisualError(f"Route settings were not applied as requested: {wrong} (wanted {wanted_flags}).")
            emit("route_settings_applied", **wanted_flags)
        self.capture("route-settings-applied")

    def route_preference_flags(self) -> dict[str, bool]:
        """Boolean entries of the app's route_preferences (debug builds allow run-as)."""
        xml = self.device_command(
            "shell", "run-as", APP_ID, "cat", "shared_prefs/route_preferences.xml", check=False,
        ).stdout
        return {name: value == "true"
                for name, value in re.findall(r'<boolean name="([^"]+)" value="(true|false)"', xml)}

    def open_settings_menu(self) -> None:
        if not self.matching_nodes("text", "Sound settings"):
            self.tap_node("description", "Planning settings")
        self.wait_for_node("text", "Sound settings", timeout=10)

    def capture_settings_menu(self, *, build: bool, skip_map: bool, refresh_map: bool) -> None:
        """Capture the planner menu card, a screen opened from it, and dismissal."""
        self.launch_app(build=build, skip_map=skip_map, refresh_map=refresh_map)
        self.open_settings_menu()
        self.wait_for_node("text", "Map credits", contains=True, timeout=10)
        self.capture("settings-menu-open")
        # Every screen opened from the card exits through the shared back
        # button, returning to the closed planner.
        self.tap_node("text", "Route settings")
        self.wait_for_node("description", "Back", timeout=10)
        self.capture("settings-screen-open")
        self.tap_node("description", "Back")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if not self.matching_nodes("text", "Target maximum shared roads"):
                break
            time.sleep(0.2)
        else:
            raise VisualError("The route-settings screen did not close after Back.")
        self.capture("settings-screen-back-to-planner")
        # Re-open the card and verify the system back button dismisses it.
        self.open_settings_menu()
        self.press_key("KEYCODE_BACK")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if not self.matching_nodes("text", "Sound settings"):
                break
            time.sleep(0.2)
        else:
            raise VisualError("Settings menu did not dismiss after Back.")
        self.capture("settings-menu-dismissed")

    def capture_search_results(self, args: argparse.Namespace) -> None:
        """Capture From's POI action and search while the IME changes available space."""
        self.ensure_device(start_if_missing=True)
        setting = "show_ime_with_hard_keyboard"
        previous = self.device_command("shell", "settings", "get", "secure", setting).stdout.strip()
        self.device_command("shell", "settings", "put", "secure", setting, "1")
        try:
            if previous != "1":
                ime = self.device_command("shell", "settings", "get", "secure", "default_input_method").stdout.strip()
                package = ime.partition("/")[0]
                if re.fullmatch(r"[A-Za-z0-9_.]+", package):
                    # The emulator's IME caches hardware-keyboard mode. Let
                    # Android rebind it with the temporary fixture setting.
                    self.device_command("shell", "am", "force-stop", package)
            self.launch_app(build=not args.no_build, skip_map=args.skip_map, refresh_map=args.refresh_map)
            self.capture("search-planner-poi-icon")
            self.tap_node("description", args.field)
            self.wait_for_keyboard(shown=True)
            self.capture("search-focused-keyboard")
            self.type_into("description", args.field, args.query)
            self.capture("search-query-entered", include_tree=False)
            self.wait_for_search_completion(args.field, args.query)
            self.wait_for_search_window()
            self.capture("search-results-keyboard-visible")
            self.press_key("KEYCODE_BACK")
            self.wait_for_keyboard(shown=False)
            self.capture("search-results-keyboard-dismissed")
        finally:
            if previous == "null":
                run_optional(self.device_command, "shell", "settings", "delete", "secure", setting)
            else:
                run_optional(self.device_command, "shell", "settings", "put", "secure", setting, previous)

    def wait_for_search_completion(self, field: str, query: str, timeout: float = 30.0) -> None:
        pid = self.device_command("shell", "pidof", APP_ID).stdout.strip().split()[0]
        expected = f'[{field}] "{query}" -> '
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            logs = self.device_command("logcat", "--pid=" + pid, "-d", "-v", "brief",
                                       "CurveMaps.SearchField:D", "*:S").stdout
            matching = [line for line in logs.splitlines() if expected in line]
            if matching:
                if "no results" in matching[-1]:
                    raise VisualError(f"The fixture search {query!r} returned no results.")
                (self.run_dir / "search-completion.txt").write_text(matching[-1] + "\n")
                time.sleep(0.3)
                return
            time.sleep(0.1)
        raise VisualError(f"The fixture search {query!r} did not complete in app PID {pid}.")

    def wait_for_search_window(self, timeout: float = 15.0) -> None:
        """Wait for the results sheet, which is part of the planner panel (same window),
        and check it neither overlaps nor cuts the From/To fields below it."""
        sheet = self.wait_for_node("description", "Search results", timeout=timeout)
        sheet_bottom = self.center(sheet["bounds"])[3]
        for field in ("From", "To"):
            for node in self.matching_nodes("description", field):
                top = self.center(node["bounds"])[1]
                if top < sheet_bottom:
                    raise VisualError(
                        f"The search results (bottom {sheet_bottom}) overlap the {field} field (top {top})."
                    )
        (self.run_dir / "search-results-window.txt").write_text(json.dumps(sheet, default=str) + "\n")

    def capture_first_run(self, args: argparse.Namespace) -> None:
        """Capture the first run: each welcome-carousel page, then the no-map card, Maps, Back.

        Installs the map-less APK (``-Pmoto.bundleData=false``: no graph or
        geocoder inside, the build that is distributed) unless ``--bundled`` is
        given, wipes the app's data so there is no basemap, no installed map and
        no welcome completion, and launches. Location is pre-granted unless
        ``--ask-location``, which captures the system dialog over the location
        page and accepts it. The SAF picker is never opened: the card's
        controls are only captured, and Maps is reached through its own button.
        """
        if not args.bundled and self.apk_override is None:
            self.extra_gradle_args = ["-Pmoto.bundleData=false"]
        self.launch_app(build=not args.no_build, skip_map=True, refresh_map=False)
        self.device_command("shell", "am", "force-stop", APP_ID)
        self.device_command("shell", "pm", "clear", APP_ID)
        location_permissions = (
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        )
        if not args.ask_location:
            for permission in location_permissions:
                self.device_command("shell", "pm", "grant", APP_ID, permission, check=False)
        self.device_command("shell", "am", "start", "-n", ACTIVITY)
        self.wait_for_node("text", "Welcome to CurveMaps", timeout=60)
        self.capture("first-run-01-welcome")
        self.tap_node("text", "Next")
        self.wait_for_node("text", "Ride safely", timeout=10)
        if self.matching_nodes("description", "Skip welcome"):
            raise VisualError("Skip must stay hidden until the safety page is acknowledged.")
        self.capture("first-run-02-safety")
        self.tap_node("text", "I understand")
        if not args.ask_location:
            self.wait_for_node("text", "Your location", timeout=10)
        else:
            # The system dialog covers the page the moment it settles.
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                if self.matching_nodes("text", "While using the app"):
                    self.capture("first-run-03-location-dialog")
                    self.tap_node("text", "While using the app")
                    break
                time.sleep(0.3)
            else:
                raise VisualError("Arriving on the location page did not show the system location dialog.")
        self.wait_for_node("text", "Location allowed", timeout=10)
        self.capture("first-run-04-location")
        self.tap_node("text", "Next")
        self.wait_for_node("text", "Music while you ride", timeout=10)
        self.capture("first-run-05-media")
        if self.matching_nodes("text", "Not now"):
            self.tap_node("text", "Not now")
        else:
            self.tap_node("text", "Next")
        self.wait_for_node("text", "Offline maps", timeout=10)
        time.sleep(0.5)
        self.capture("first-run-06-map")
        # Wiped data has no basemap, so both builds offer Choose map file here;
        # only the map-less build also offers Download in Maps.
        if args.bundled and self.matching_nodes("text", "Download in Maps"):
            raise VisualError("A build that bundles Queensland must not offer the Download in Maps shortcut.")
        self.tap_node("text", "Later")
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if self.matching_nodes("text", "Choose map file"):
                break
            time.sleep(0.3)
        else:
            raise VisualError("The first-run map card did not appear on a wiped install.")
        self.capture("first-run-card")
        if args.bundled:
            if self.matching_nodes("text", "Open Maps"):
                raise VisualError("A build that bundles Queensland must not offer the Open Maps shortcut.")
            return
        self.wait_for_node("text", "Map package needed", timeout=10)
        self.tap_node("text", "Open Maps")
        self.wait_for_node("description", "Map service", timeout=15, contains=True)
        if self.matching_nodes("text", "Queensland (bundled)"):
            raise VisualError("The map-less build lists a bundled Queensland map.")
        self.capture("first-run-maps-screen")
        self.press_key("KEYCODE_BACK")
        self.wait_for_node("text", "Choose map file", timeout=10)
        self.capture("first-run-back-to-card")
        # Back (or a tap outside) dismisses the dialog, so the toolbar and its
        # settings menu (Maps, Load map file) stay reachable without a map.
        self.press_key("KEYCODE_BACK")
        self.wait_for_no_node("text", "Choose map file", timeout=10)
        self.wait_for_node("description", "Planning settings", timeout=10)
        self.capture("first-run-card-dismissed")

    def capture_maps_settings(self, args: argparse.Namespace) -> None:
        """Capture the Maps screen: fixed map service, grouped catalog, one-tap install, search, Back.

        No server is ever contacted: Browse maps is deliberately not tapped,
        and the connected/catalog/search/download/install/delete states come
        from the debug Maps-preview bridge (VisualMapsPreview), so this
        scenario needs no network, no map server, and no SAF picker.
        """
        self.launch_app(build=not args.no_build, skip_map=args.skip_map, refresh_map=args.refresh_map)
        self.open_settings_menu()
        self.tap_node("text", "Maps")
        self.wait_for_node("description", "Browse maps", timeout=15)
        self.wait_for_node("description", "Import package file", timeout=15)
        if self.matching_nodes("description", "Map server address"):
            raise VisualError("The Maps screen must not offer a server address field.")
        self.capture("maps-settings-empty")

        # The live service's 22-region catalog: grouped by continent, only the
        # installed region's continent open, the rest collapsed.
        self.broadcast_maps_preview("catalog-world")
        self.wait_for_node("description", "Australia and Oceania, 9 regions", timeout=10)
        self.capture("maps-preview-catalog-world")
        if self.matching_nodes("description", "Download and install wales"):
            raise VisualError("Collapsed continents must hide their regions.")
        self.scroll_to_node("description", "Europe, 6 regions")
        self.tap_node("description", "Europe, 6 regions")
        self.scroll_to_node("description", "Download and install wales")
        self.capture("maps-preview-catalog-expanded")
        self.scroll_to_node("description", "Filter regions", toward_top=True)
        self.type_into("description", "Filter regions", "cali", verify=True)
        self.press_key("KEYCODE_BACK")  # dismiss the IME
        self.wait_for_node("description", "Download and install socal", timeout=10)
        if self.matching_nodes("description", "Europe, 6 regions"):
            raise VisualError("A region search must hide continents without a match.")
        self.capture("maps-preview-catalog-search")
        self.tap_node("description", "Clear region filter")

        # A failed load shows a dismissible, actionable error banner.
        self.broadcast_maps_preview("server-error")
        self.wait_for_node("description", "Dismiss error", timeout=10)
        self.capture("maps-preview-server-error")

        # Deterministic intermediate states: a terminal job keeps Download
        # visible, a failed job keeps Retry visible, and every download/import
        # step offers its next explicit action.
        previews = (
            ("service-connected", "Refresh maps", "maps-preview-service-connected"),
            ("catalog-ready", "Download and install queensland", "maps-preview-catalog-ready"),
            ("build-following", "Stop waiting for tasmania", "maps-preview-build-following"),
            ("search-results", "Get map tasmania", "maps-preview-search-results"),
            ("installing", "Installing Queensland…", "maps-preview-installing"),
            ("build-failed", "Retry package queensland", "maps-preview-build-failed"),
            ("download-progress", "Cancel download", "maps-preview-download-progress"),
            ("download-retrying", "Cancel download", "maps-preview-download-retrying"),
            ("download-verifying", "Cancel download", "maps-preview-download-verifying"),
            ("download-interrupted", "Resume download", "maps-preview-download-interrupted"),
            ("package-saved", "Import saved file", "maps-preview-package-saved"),
            ("recovered-package", "Save downloaded package to device", "maps-preview-recovered-package"),
        )
        for scenario, description, label in previews:
            self.broadcast_maps_preview(scenario)
            if description.endswith("…"):
                self.scroll_to_node("text", description)
            else:
                self.scroll_to_node("description", description)
            self.capture(label)

        # Installed maps list: real installed size, a rubbish bin per entry, and
        # a confirmation that names the dataset and the reclaimed space.
        self.broadcast_maps_preview("installed")
        self.scroll_to_node("description", "Delete Queensland map")
        self.capture("maps-preview-installed")
        self.tap_node("description", "Delete Queensland map")
        self.wait_for_node("text", "Delete installed map?", timeout=10)
        self.capture("maps-delete-confirmation")
        self.tap_node("description", "Cancel delete Queensland map")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if not self.matching_nodes("text", "Delete installed map?"):
                break
            time.sleep(0.2)
        else:
            raise VisualError("The delete confirmation did not dismiss after Cancel.")
        self.capture("maps-delete-cancelled")

        # Map updates: before the catalog loads, one Check for updates button;
        # a different package fingerprint on the service offers Update on the
        # installed row; after the update one copy remains and reads Up to date.
        self.broadcast_maps_preview("update-check")
        self.scroll_to_node("description", "Check for map updates")
        if self.matching_nodes("description", "Update Queensland map"):
            raise VisualError("Nothing may claim an update before the catalog is loaded.")
        self.capture("maps-preview-update-check")
        self.broadcast_maps_preview("update-available")
        self.scroll_to_node("description", "Update Queensland map")
        self.wait_for_node("text", "Update available", timeout=10, contains=True)
        self.capture("maps-preview-update-available")
        self.broadcast_maps_preview("updated")
        self.scroll_to_node("text", "Up to date")
        if self.matching_nodes("description", "Update Queensland map"):
            raise VisualError("An updated map must not offer Update again.")
        if len(self.matching_nodes("description", "Delete Queensland map")) != 1:
            raise VisualError("An update must leave exactly one Queensland copy listed.")
        self.capture("maps-preview-updated")

        # Active-map deletion shows the pending drain state truthfully.
        self.broadcast_maps_preview("removal-pending")
        self.scroll_to_node("text", "releasing this map", contains=True)
        self.capture("maps-preview-removal-pending")
        self.broadcast_maps_preview("removal-retry")
        self.scroll_to_node("text", "The map is still in use. Try deleting it again shortly.", toward_top=True)
        self.capture("maps-preview-removal-retry")
        self.broadcast_maps_preview("import-deferred")
        self.scroll_to_node("text", "Queensland is installed. It switches on when this ride ends. The selected file was left where it was.", toward_top=True)
        self.capture("maps-preview-import-deferred")
        self.broadcast_maps_preview("install-deferred")
        self.scroll_to_node("text", "Queensland is installed. It switches on when this ride ends.", toward_top=True)
        self.capture("maps-preview-install-deferred")
        self.broadcast_maps_preview("clear")

        # Back closes the Maps screen and returns to the planner rail.
        self.press_key("KEYCODE_BACK")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if not self.matching_nodes("description", "Map service", contains=True):
                break
            time.sleep(0.2)
        else:
            raise VisualError("The Maps screen did not close after Back.")
        self.capture("maps-settings-back-to-planner")

    def capture_oss_licences(self, args: argparse.Namespace) -> None:
        """Capture Open-source licences: the list, library rows, two full texts, and Back.

        Everything shown is bundled in the APK (build-time AboutLibraries data
        plus collected NOTICE files); nothing is fetched. The header back
        button closes a licence text, system Back closes the list.
        """
        self.launch_app(build=not args.no_build, skip_map=args.skip_map, refresh_map=args.refresh_map)
        self.open_settings_menu()
        self.capture("oss-licences-settings-row")
        self.tap_node("text", "Open-source licences")
        self.wait_for_node("description", "OpenStreetMap data, ODbL-1.0", timeout=15)
        self.capture("oss-licences-list")

        # The data entry: the ODbL in full, with the OSM copyright line.
        self.tap_node("description", "OpenStreetMap data, ODbL-1.0")
        self.wait_for_node("description", "Licence text for OpenStreetMap data", timeout=10)
        self.wait_for_node("text", "© OpenStreetMap contributors", contains=True, timeout=10)
        self.capture("oss-licences-text-odbl")
        self.tap_node("description", "Back")
        self.wait_for_node("description", "OpenStreetMap data, ODbL-1.0", timeout=10)

        # A library from the dependency graph: GraphHopper under Apache-2.0.
        self.scroll_to_node("description", "GraphHopper Core, Apache-2.0", max_swipes=30)
        self.capture("oss-licences-libraries")
        self.tap_node("description", "GraphHopper Core, Apache-2.0")
        self.wait_for_node("description", "Licence text for GraphHopper Core", timeout=10)
        self.wait_for_node("text", "Apache License 2.0", timeout=10)
        self.capture("oss-licences-text-apache")
        self.press_key("KEYCODE_BACK")
        self.wait_for_node("description", "GraphHopper Core, Apache-2.0", timeout=10)
        self.capture("oss-licences-back-to-list")

        self.press_key("KEYCODE_BACK")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if not self.matching_nodes("description", "Open-source licence list"):
                break
            time.sleep(0.2)
        else:
            raise VisualError("Open-source licences did not close after Back.")
        self.wait_for_any_text(("START", "RIDE"), timeout=10)
        self.capture("oss-licences-back-to-planner")

    def open_gpx_from_another_app(self, source: Path, *, share: bool = False) -> None:
        """Hands a GPX file to CurveMaps the way Files or a chat app does.

        The file is copied into the debug app's own files (no storage
        permission needed) and opened through the exported OpenGpxActivity
        with VIEW ("Open with") or SEND ("Share to"), so the real hand-over to
        the running planner is what gets captured.
        """
        if not source.is_file():
            raise VisualError(f"GPX file not found: {source}")
        remote = f"files/visual-gpx/{source.name}"
        self.device_command("shell", "run-as", APP_ID, "mkdir", "-p", "files/visual-gpx")
        with source.open("rb") as gpx_file:
            copied = self.command(
                "-s", self.serial or "", "shell", f"run-as {APP_ID} sh -c 'cat > {remote}'",
                check=False, timeout=60, input_file=gpx_file,
            )
        if copied.returncode != 0:
            raise VisualError("Could not copy the GPX file into the app. " + copied.stderr.strip())
        uri = f"file:///data/user/0/{APP_ID}/{remote}"
        if share:
            self.device_command(
                "shell", "am", "start", "-n", f"{APP_ID}/.OpenGpxActivity", "-a", "android.intent.action.SEND",
                "-t", "application/gpx+xml", "--eu", "android.intent.extra.STREAM", uri,
            )
        else:
            self.device_command(
                "shell", "am", "start", "-n", f"{APP_ID}/.OpenGpxActivity", "-a", "android.intent.action.VIEW",
                "-d", uri, "-t", "application/gpx+xml",
            )
        emit("gpx_opened", file=str(source), share=share)

    def capture_gpx(self, args: argparse.Namespace) -> None:
        """Capture GPX import: a followed track, Back, the preview, rotation, a stop list and a refusal.

        A detailed track (the committed Mount Glorious loop, or --file) is
        opened from "another app" and followed road for road: the card names
        the file and a note replaces the ride-style slider. Back shows the GPX
        rows; the preview sheet says how the file is ridden; a rotation keeps
        the import and START routes it again. A planner's stop list is shared
        and keeps the slider. A file that is not a GPX is refused plainly.
        """
        fixtures = self.root / "app/src/androidTest/assets/gpx"
        track = Path(args.file).expanduser().resolve() if args.file else fixtures / "mount-glorious-loop.gpx"
        self.launch_app(build=not args.no_build, skip_map=args.skip_map, refresh_map=args.refresh_map)
        original = {key: self.device_command("shell", "settings", "get", "system", key).stdout.strip()
                    for key in ("accelerometer_rotation", "user_rotation")}
        try:
            self.device_command("shell", "settings", "put", "system", "accelerometer_rotation", "0")
            self.device_command("shell", "settings", "put", "system", "user_rotation", "0")
            self.open_gpx_from_another_app(track)
            self.wait_for_node("description", "Route 1: ", contains=True, timeout=180)
            followed = bool(self.matching_nodes("description", "GPX track:", contains=True))
            if followed:
                self.wait_for_no_node("description", "GPX track: Following your track", contains=True, timeout=30)
                self.wait_for_no_node("description", "Checking the route", contains=True, timeout=30)
            time.sleep(1)
            self.capture("gpx-opened")
            note = self.matching_nodes("description", "GPX track:", contains=True)
            emit("gpx_route", followed=followed, note=note[0]["description"] if note else None)

            self.tap_node("description", "Back to From and To")
            self.wait_for_node("description", "Preview GPX route", contains=True, timeout=15)
            self.capture("gpx-endpoints")
            self.tap_node("description", "Preview GPX route", contains=True)
            self.wait_for_node("description", "GPX route milestones", timeout=15)
            time.sleep(1)
            self.capture("gpx-preview")
            self.tap_node("description", "Close route preview")
            self.wait_for_no_node("description", "GPX route milestones", timeout=10)

            # Rotation recreates the planner: the import must come back whole.
            self.device_command("shell", "settings", "put", "system", "user_rotation", "1")
            time.sleep(3)
            self.wait_for_node("description", "Preview GPX route", contains=True, timeout=30)
            self.capture("gpx-after-rotation")
            self.device_command("shell", "settings", "put", "system", "user_rotation", "0")
            time.sleep(3)
            self.wait_for_node("description", "Preview GPX route", contains=True, timeout=30)
            self.tap_node("text", "START")
            self.wait_for_node("description", "Route 1: ", contains=True, timeout=180)
            time.sleep(1)
            self.capture("gpx-start-again")

            # A planner's stop list keeps the ride styles between its stops.
            self.open_gpx_from_another_app(fixtures / "nambour-stops.gpx", share=True)
            self.wait_for_node("description", "Ride style level", contains=True, timeout=180)
            self.wait_for_node("description", "Route 1: ", contains=True, timeout=180)
            time.sleep(1)
            self.capture("gpx-stops")

            # A file that is not a GPX ride is refused in plain words.
            not_gpx = self.output_dir / "not-a-ride.gpx"
            not_gpx.write_text("Fuel at Woodford, lunch at Dayboro\n")
            self.open_gpx_from_another_app(not_gpx)
            self.wait_for_node("text", "This file isn't a GPX file.", timeout=30)
            self.capture("gpx-not-a-gpx")
            self.tap_node("text", "OK")
        finally:
            for key, value in original.items():
                if value and value != "null":
                    self.device_command("shell", "settings", "put", "system", key, value, check=False)

    def apply_black_and_white(self, mode: str) -> None:
        """Toggles the ride-map style through its accessible control, no capture.

        The accessibility state changes before MapLibre finishes loading the
        alternate local style, so a real toggle waits for the style and route
        line to repaint; an already-correct state returns immediately.
        """
        if mode not in {"on", "off"}:
            raise VisualError("Black-and-white mode must be 'on' or 'off'.")
        target = f"Dark ride map, {mode}"
        enabled = mode == "on"
        opposite = f"Dark ride map, {'off' if enabled else 'on'}"
        if not self.matching_nodes("description", target):
            if not self.matching_nodes("description", opposite):
                raise VisualError(
                    "Black-and-white mode is available during an active ride; start a route first."
                )
            self.tap_node("description", opposite)
            self.wait_for_node("description", target, timeout=10)

        # Accessibility changes before style/tile/overlay loading completes.
        # Request-driven native queries are only a readiness gate; the captured
        # pixels still need inspection to prove readability and visibility.
        self.wait_for_ride_map(dark=enabled)
        time.sleep(0.5)

    def set_black_and_white(self, mode: str) -> None:
        self.apply_black_and_white(mode)
        self.capture(f"ride-map-black-and-white-{mode}")
        emit("ride_map_mode", black_and_white=mode == "on", screenshot=str(self.output_dir / "latest.png"))

    def capture_icon_states(self) -> None:
        """Capture appearance and speaker states through accessible controls."""
        self.capture("guidance-icons-initial")
        original_dark = bool(self.matching_nodes("description", "Dark ride map, on"))
        self.set_black_and_white("off")
        self.set_black_and_white("on")
        self.set_black_and_white("on" if original_dark else "off")
        self.tap_node("description", "Voice guidance settings", contains=True)
        self.capture("guidance-icons-voice-settings")
        switch = self.wait_for_node("description", "Spoken turn guidance", timeout=8)
        original_voice = switch["checked"]
        for index, enabled in enumerate((False, True, original_voice)):
            switch = self.wait_for_node("description", "Spoken turn guidance", timeout=8)
            if switch["checked"] != enabled:
                self.tap_node("description", "Spoken turn guidance")
            self.tap_node("text", "Apply")
            time.sleep(1)
            self.capture(f"guidance-icons-voice-{'on' if enabled else 'off'}")
            if index < 2:
                self.tap_node("description", "Voice guidance settings", contains=True)

    def grant_media_listener(self, granted: bool) -> None:
        """Grant/revoke this app's notification-listener (media session) access."""
        component = (
            f"{APP_ID}/com.curvemaps.app.media.MediaNotificationListenerService"
        )
        command = "allow_listener" if granted else "disallow_listener"
        self.device_command("shell", "cmd", "notification", command, component, check=False)
        time.sleep(1.5)

    def media_listener_granted(self) -> bool:
        """Whether this app's notification listener is currently enabled.

        Reads the platform's own enabled-listener setting (a colon-separated
        list), so the runner can restore the exact starting state instead of
        assuming access was off.
        """
        component = (
            f"{APP_ID}/com.curvemaps.app.media.MediaNotificationListenerService"
        )
        completed = self.device_command(
            "shell",
            "settings",
            "get",
            "secure",
            "enabled_notification_listeners",
            check=False,
        )
        value = (completed.stdout or "").strip()
        return any(entry.strip() == component for entry in value.split(":"))

    def set_media_session(self, state: str, title: str, artist: str) -> None:
        """Drive the debug-only synthetic media session used for screenshots."""
        self.device_command(
            "shell", "am", "broadcast",
            "-a", "com.curvemaps.app.DEBUG_VISUAL_MEDIA_SESSION",
            "-p", APP_ID,
            "--es", "state", state,
            "--es", "title", title,
            "--es", "artist", artist,
            check=False,
        )
        time.sleep(0.6)

    def ride_pinned(self, timeout: float = 3.0) -> bool:
        """Whether the ride's task is in its Picture-in-Picture window, polling up to ``timeout`` seconds."""
        pattern = re.compile(r"\* Task\{[^}]*A=\d+:" + re.escape(APP_ID) + r" [^}]*mode=pinned")
        deadline = time.monotonic() + timeout
        while True:
            activities = self.device_command("shell", "dumpsys", "activity", "activities", check=False).stdout
            if pattern.search(activities):
                return True
            if time.monotonic() >= deadline:
                return False
            time.sleep(0.25)

    def capture_media_controls(self, args: argparse.Namespace) -> None:
        """Capture media states or the focused inactivity interaction."""
        if not args.reuse_app:
            self.launch_app(
                build=not args.no_build,
                skip_map=args.skip_map,
                refresh_map=args.refresh_map,
            )
        else:
            self.ensure_device(start_if_missing=False)
            self.wait_for_any_text(("START", "RIDE"), timeout=15)

        if not self.matching_nodes("text", "RIDE"):
            self.type_into("description", "From", args.from_text)
            self.type_into("description", "To", args.to_text)
            self.press_key("KEYCODE_ENTER")
            planner_action = self.wait_for_any_text(("START", "RIDE"), timeout=15)
            if planner_action["text"] == "START":
                self.tap_node("text", "START")
                self.wait_for_node("text", "RIDE", timeout=args.route_timeout)

        # Park before RIDE so an old California fix cannot trigger an
        # off-route recalculation or pull the camera outside the offline map.
        route_start = None
        if self.serial and self.serial.startswith("emulator-"):
            snapshot = self.read_route_snapshot()
            selected = snapshot["routes"][snapshot.get("selectedIndex", 0)]
            route_start = point_at_percent(selected["points"], 0.0)
            lat, lon = route_start
            self.device_command("emu", "geo", "fix", f"{lon:.7f}", f"{lat:.7f}")
        self.tap_node("text", "RIDE")
        self.wait_for_node("text", "End", timeout=30)
        if route_start is not None:
            self.inject_location(*route_start, speed_mps=0.0)
        self.capture("media-ride-active")
        if args.inactivity_only:
            self.capture_media_inactivity()
            return

        # Record the exact starting state so the finally block restores it: the
        # runner must never leave notification access force-granted for the
        # next command or another test.
        initial_access = self.media_listener_granted()
        original_dark = bool(self.matching_nodes("description", "Dark ride map, on"))
        panel_open = False
        try:
            if args.cold_start:
                self.set_media_session("cold-enable", args.title, args.artist)
            self.grant_media_listener(True)

            # Playing, in colour and in B&W ride mode. Force colour first so a
            # persisted dark-map preference from an earlier run cannot make the
            # "colour" capture indistinguishable from the monochrome one.
            self.set_media_session("playing", args.title, args.artist)
            if original_dark:
                self.set_black_and_white("off")
            self.tap_node("description", "Media controls")
            panel_open = True
            self.wait_for_node("description", "Media control center", timeout=10)
            self.capture("media-panel-color-playing")
            self.tap_node("description", "Close media controls")
            panel_open = False
            # Style settling is independent of the inactivity timer; switch
            # with the panel closed and give each capture a fresh open window.
            self.set_black_and_white("on")
            self.tap_node("description", "Media controls")
            panel_open = True
            self.capture("media-panel-bw-playing")

            # Paused.
            self.tap_node("description", "Close media controls")
            panel_open = False
            self.set_media_session("paused", args.title, args.artist)
            time.sleep(1.0)
            self.tap_node("description", "Media controls")
            panel_open = True
            self.capture("media-panel-bw-paused")

            # No player (access still granted, session stopped).
            self.tap_node("description", "Close media controls")
            panel_open = False
            self.set_media_session("stop", args.title, args.artist)
            time.sleep(1.0)
            self.tap_node("description", "Media controls")
            panel_open = True
            self.wait_for_node("description", "Media control center", timeout=10)
            self.capture("media-panel-bw-no-player")

            if args.cold_start:
                # Each disclosure frame needs a fresh inactivity window. Reuse
                # the located node for its tap, so redundant hierarchy dumps
                # cannot consume that window between a screenshot and a click.
                def tap_music_control(description: str, frame: str | None = None) -> None:
                    node = self.scroll_to_node("description", description)
                    if frame is not None:
                        self.capture(frame, include_tree=False)
                    x1, y1, x2, y2 = self.center(node["bounds"])
                    self.tap_point((x1 + x2) // 2, (y1 + y2) // 2)

                self.tap_node("description", "Close media controls")
                panel_open = False
                self.tap_node("description", "Media controls")
                panel_open = True
                tap_music_control("Show music apps", "media-music-apps-collapsed")
                if self.matching_nodes("description", "Change music app"):
                    tap_music_control("Change music app")
                    tap_music_control("Choose music app CurveMaps", "media-music-app-choice")
                self.scroll_to_node("description", "Start music in CurveMaps")
                self.capture("media-cold-player-before-start", include_tree=False)
                tap_music_control("Hide music apps")
                # The fixture has no active session before Start. Exercise the
                # same installed-player entry point a stopped Spotify uses.
                tap_music_control("Show music apps", "media-music-apps-hidden-again")
                tap_music_control("Start music in CurveMaps")
                # Reopen to restore the panel's top after scrolling to Start.
                time.sleep(0.5)
                self.press_key("KEYCODE_BACK")
                panel_open = False
                self.tap_node("description", "Media controls")
                panel_open = True
                self.wait_for_node("text", "Cold-start music", timeout=10)
                self.wait_for_node("description", "Pause", timeout=10)
                self.capture("media-cold-player-playing")
                self.tap_node("description", "Pause")
                self.wait_for_node("description", "Play", timeout=10)
                self.capture("media-cold-player-paused")

                # Media-button start: a player that refuses the background
                # browser but starts from one PLAY press on its media button,
                # the signal a headset sends. The ride never leaves the front.
                self.tap_node("description", "Close media controls")
                panel_open = False
                self.set_media_session("cold-button", args.title, args.artist)
                self.tap_node("description", "Media controls")
                panel_open = True
                tap_music_control("Show music apps")
                tap_music_control("Start music in CurveMaps")
                deadline = time.monotonic() + 10
                button_play = False
                while not button_play and time.monotonic() < deadline:
                    prefs = self.device_command(
                        "shell", "run-as", APP_ID, "cat", "shared_prefs/visual_music.xml", check=False,
                    ).stdout
                    button_play = 'name="buttonPlay" value="true"' in prefs
                    if not button_play:
                        time.sleep(0.25)
                emit("media_cold_player_button_play", button_play=button_play)
                if not button_play:
                    raise VisualError("The player's media button never started playback.")
                self.wait_for_node("description", "Pause", timeout=10)
                self.capture("media-cold-player-button")

                # Foreground start: a player that refuses the background
                # browser and has no working media button opens its own play
                # screen over the ride, which shrinks to its Picture-in-Picture
                # window. The fixture plays 5 s after its screen opens, then
                # the ride returns by itself. (The fixture player shares this
                # app's UID and is visible, so Android allows that return
                # here; over a real player it blocks it.)
                self.tap_node("description", "Close media controls")
                panel_open = False
                self.set_media_session("cold-refuse", args.title, args.artist)
                self.tap_node("description", "Media controls")
                panel_open = True
                tap_music_control("Show music apps")
                tap_music_control("Start music in CurveMaps")
                # The ride is still in front for a moment after the tap, so
                # first prove the player's own screen opened, then the window.
                deadline = time.monotonic() + 10
                foreground_play = False
                while not foreground_play and time.monotonic() < deadline:
                    prefs = self.device_command(
                        "shell", "run-as", APP_ID, "cat", "shared_prefs/visual_music.xml", check=False,
                    ).stdout
                    foreground_play = 'name="foregroundPlay" value="true"' in prefs
                    if not foreground_play:
                        time.sleep(0.25)
                emit("media_cold_player_foreground_play", foreground_play=foreground_play)
                if not foreground_play:
                    raise VisualError("The refusing player's own screen never opened.")
                pinned = self.ride_pinned()
                emit("media_cold_player_pip", pinned=pinned)
                if not pinned:
                    raise VisualError("The ride did not shrink to its Picture-in-Picture window.")
                # The window first shows the app icon over the shrinking
                # screen; let the PiP layout draw before the frame.
                time.sleep(2.0)
                self.capture("media-cold-player-pip", include_tree=False)
                self.wait_for_node("description", "Media control center", timeout=15)
                self.wait_for_node("description", "Pause", timeout=10)
                if self.ride_pinned(timeout=0):
                    raise VisualError("The ride came back but stayed in its Picture-in-Picture window.")
                self.capture("media-cold-player-returned")

            # Permission needed (notification access revoked).
            self.tap_node("description", "Close media controls")
            panel_open = False
            self.grant_media_listener(False)
            self.tap_node("description", "Media controls")
            panel_open = True
            self.capture("media-panel-bw-permission-needed")
            # System Back closes the panel instead of leaving the ride; capture
            # the dismissed state as evidence.
            self.press_key("KEYCODE_BACK")
            panel_open = False
            time.sleep(0.5)
            self.capture("media-panel-dismissed-with-back")
            panel_open = True
            self.capture_media_inactivity()
            panel_open = False
        finally:
            # Restore exactly what the runner found, never force-grant.
            if args.cold_start:
                run_optional(self.set_media_session, "cold-disable", args.title, args.artist)
            if panel_open:
                run_optional(self.tap_node, "description", "Close media controls")
            run_optional(self.set_media_session, "stop", args.title, args.artist)
            run_optional(self.grant_media_listener, initial_access)
            run_optional(self.set_black_and_white, "on" if original_dark else "off")
            run_optional(self.tap_node, "text", "End")
        emit("media_controls_captured", restored_notification_access=initial_access)

    def capture_media_inactivity(self) -> None:
        """Capture the real panel's open timer, reset, and inactivity close."""
        self.tap_node("description", "Media controls")
        panel = self.wait_for_node("description", "Media control center", timeout=10)
        opened_at = time.monotonic()
        x1, y1, x2, _ = self.center(panel["bounds"])
        self.capture("media-panel-inactivity-full", include_tree=False)
        time.sleep(max(0.0, 7.5 - (time.monotonic() - opened_at)))
        # Tap unused top padding: it must restart the timer without issuing a
        # transport or volume command.
        self.tap_point((x1 + x2) // 2, y1 + 2)
        interacted_at = time.monotonic()
        time.sleep(max(0.0, 7.5 - (time.monotonic() - interacted_at)))
        if not self.matching_nodes("description", "Media control center"):
            raise VisualError("A panel touch did not restart the media inactivity timer")
        self.capture("media-panel-inactivity-half", include_tree=False)
        deadline = interacted_at + 17.0
        while time.monotonic() < deadline:
            if not self.matching_nodes("description", "Media control center"):
                break
            time.sleep(0.25)
        else:
            raise VisualError("Media panel did not close 15 seconds after its last interaction")
        self.capture("media-panel-inactivity-closed", include_tree=False)
        emit("media_inactivity_captured", timer_reset_by="blank-panel-touch", close="15s inactivity")



    def capture_save_bubble(self) -> None:
        """Save the shown ride both ways and capture the save bubble.

        The route card's bookmark button opens the bubble already saving and
        it confirms "Saved"; a long-press on the card opens it at its prompt.
        Both save the ride into the app's saved routes.
        """
        self.tap_node("description", ROUTE_CARD_SAVE)
        self.wait_for_node("text", "Saved", timeout=15)
        self.capture("save-bubble-saved")
        self.wait_for_text_gone("Saved", timeout=10)
        card = self.select_node("description", "Route 1: ", contains=True)
        x1, y1, x2, y2 = self.center(card["bounds"])
        # A long-press is a swipe that never moves: about 900 ms.
        self.device_command(
            "shell", "input", "swipe",
            str(x1 + (x2 - x1) // 4), str((y1 + y2) // 2), str(x1 + (x2 - x1) // 4), str((y1 + y2) // 2), "900",
        )
        self.wait_for_node("text", "Save this route", timeout=10)
        self.capture("save-bubble-prompt")
        self.tap_node("description", "Save route")
        self.wait_for_node("text", "Saved", timeout=15)
        self.wait_for_text_gone("Saved", timeout=10)

    def save_shown_ride(self) -> None:
        """Save the ride on the route card with its bookmark button and wait out the bubble."""
        self.tap_node("description", ROUTE_CARD_SAVE)
        # The "Saved" confirmation lingers under a second, shorter than one
        # accessibility dump can be relied on to catch, so the bubble is only
        # waited out here; the ride count on the list page proves the save.
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if not self.matching_nodes("text", "Save this route") and not self.matching_nodes("text", "Saved"):
                break
            time.sleep(0.3)
        else:
            raise VisualError("The save bubble did not dismiss itself.")
        time.sleep(0.8)

    def open_saved_routes(self) -> None:
        """Open the Saved routes page from the planner toolbar and wait for its top bar."""
        self.tap_node("description", "Saved routes")
        self.wait_for_node("description", SAVED_ROUTES_CLOSE, timeout=10)
        self.wait_for_node("text", "Saved routes", timeout=10)

    def saved_route_cards(self) -> list[dict[str, Any]]:
        return self.matching_nodes("description", "Saved route: ", contains=True)

    def capture_saved_routes(self, args: argparse.Namespace) -> None:
        """Capture the Saved routes page: empty, two rides, a comment, delete confirmation, Open.

        Starts from an empty store (the saved_routes database is deleted with
        the app stopped), plans one route, saves it, switches to a second ride
        style and saves that too, so the list has two cards. Nothing is
        actually deleted: the confirmation is cancelled.
        """
        if args.second_style == args.style:
            raise VisualError("--second-style must differ from --style so the two saved rides differ.")
        self.launch_app(build=not args.no_build, skip_map=args.skip_map, refresh_map=args.refresh_map)
        self.device_command("shell", "am", "force-stop", APP_ID)
        self.device_command(
            "shell", "run-as", APP_ID, "sh", "-c", "'rm -f databases/saved_routes.db*'",
        )
        self.device_command("shell", "am", "start", "-n", ACTIVITY)
        self.wait_for_any_text(("START", "RIDE"), timeout=60)

        # Empty state, before anything is saved.
        self.open_saved_routes()
        self.wait_for_node("text", "No saved routes yet", timeout=10)
        if self.saved_route_cards():
            raise VisualError("The saved-routes store was not empty at the start.")
        self.capture("saved-routes-empty")
        self.tap_node("description", SAVED_ROUTES_CLOSE)
        self.wait_for_no_node("description", SAVED_ROUTES_CLOSE, timeout=10)

        # Two different rides over the same endpoints.
        args.reuse_app = True
        args.plan_only = True
        self.route(args)
        first = self.wait_for_route_card(args.style, 10)
        self.save_shown_ride()
        emit("saved_ride", style=STYLE_NAMES[args.style], card=first)
        self.set_style(args.second_style)
        second = self.wait_for_route_card(
            args.second_style, args.timeout, frames="styles-growing", frame_interval=args.progress_interval
        )
        time.sleep(0.6)
        self.save_shown_ride()
        emit("saved_ride", style=STYLE_NAMES[args.second_style], card=second)

        # The list.
        self.open_saved_routes()
        self.wait_for_node("text", "2 rides", timeout=10)
        self.wait_for_node("text", "No comments yet.", timeout=10)
        if not self.saved_route_cards():
            raise VisualError("Saved routes opened with two rides but showed no ride card.")
        self.capture("saved-routes-list")

        # A comment on the first card.
        toggle = self.select_node("description", "Comments for saved route: ", contains=True)
        self.tap_point(*self.node_center(toggle))
        self.wait_for_node("text", "Done", timeout=10)
        self.wait_for_node("text", "Add a comment", timeout=10)
        self.type_into("text", "Add a comment", args.comment)
        self.capture("saved-routes-comment-composer")
        self.tap_node("description", "Add comment")
        # The comment lands above the composer, which stays open and empty
        # until its toggle ("Done") closes it.
        self.wait_for_node("text", args.comment, timeout=15)
        self.tap_node("text", "Done")
        self.wait_for_no_node("text", "Add a comment", timeout=10)
        self.wait_for_node("text", args.comment, timeout=10)
        time.sleep(0.4)
        self.capture("saved-routes-commented")

        # Delete asks first; Cancel keeps the ride.
        self.tap_node("description", "Delete saved route")
        self.wait_for_node("text", "Delete saved route?", timeout=10)
        self.wait_for_node("text", "Cancel", timeout=10)
        self.capture("saved-routes-delete-confirmation")
        self.tap_node("text", "Cancel")
        self.wait_for_no_node("text", "Delete saved route?", timeout=10)
        self.wait_for_node("text", "2 rides", timeout=10)
        self.capture("saved-routes-delete-cancelled")

        # Open loads the ride into the planner.
        self.tap_node("text", "Open")
        self.wait_for_no_node("description", SAVED_ROUTES_CLOSE, timeout=15)
        deadline = time.monotonic() + args.timeout
        while time.monotonic() < deadline and not self.route_card_descriptions():
            time.sleep(0.8)
        if not self.route_card_descriptions():
            self.capture("saved-routes-open-timeout")
            raise VisualError("Opening a saved ride did not bring back a route card.")
        time.sleep(0.8)
        self.capture("saved-routes-opened")

    @staticmethod
    def node_center(node: dict[str, Any]) -> tuple[int, int]:
        x1, y1, x2, y2 = VisualHarness.center(node["bounds"])
        return (x1 + x2) // 2, (y1 + y2) // 2

    def capture_eta_layouts(self) -> None:
        """Capture the planner at 320 dp with 2x text; restore device settings."""
        size = self.device_command("shell", "wm", "size").stdout
        override = re.search(r"Override size: (\d+x\d+)", size)
        physical = re.search(r"Physical size: (\d+)x(\d+)", size)
        density = self.device_command("shell", "wm", "density").stdout
        densities = re.findall(r"(?:Physical|Override) density: (\d+)", density)
        if physical is None or not densities:
            raise VisualError("Cannot read the device size and density for ETA capture.")
        original_font = self.device_command("shell", "settings", "get", "system", "font_scale").stdout.strip()
        width = round(320 * int(densities[-1]) / 160)
        height = max(int(physical.group(2)), width * 2)
        try:
            self.device_command("shell", "wm", "size", f"{width}x{height}")
            self.device_command("shell", "settings", "put", "system", "font_scale", "2.0")
            action = self.wait_for_any_text(("START", "RIDE"), timeout=30)
            if action["text"] == "START":
                self.tap_node("text", "START")
            self.wait_for_node("text", "RIDE", timeout=300)
            time.sleep(2)
            self.capture("planner-eta-narrow-large-text")
        finally:
            self.device_command("shell", "wm", "size", override.group(1) if override else "reset")
            if original_font == "null":
                self.device_command("shell", "settings", "delete", "system", "font_scale")
            else:
                self.device_command("shell", "settings", "put", "system", "font_scale", original_font)
            action = self.wait_for_any_text(("START", "RIDE"), timeout=30)
            if action["text"] == "START":
                self.tap_node("text", "START")
            self.wait_for_node("text", "RIDE", timeout=300)

    def route(
        self,
        args: argparse.Namespace,
        *,
        before_ride: Any = None,
    ) -> None:
        if args.plan_only and args.black_and_white is not None:
            raise VisualError("--black-and-white requires ride mode; omit --plan-only.")
        if not args.reuse_app:
            self.launch_app(
                build=not args.no_build,
                skip_map=args.skip_map,
                refresh_map=args.refresh_map,
            )
        else:
            self.ensure_device(start_if_missing=False)
            self.wait_for_any_text(("START", "RIDE"), timeout=15)

        if getattr(args, "assume_default_settings", False):
            # Fresh app state (the gallery resets it): avoid/block are off and
            # suggestions on, so only an explicit change needs the dialog.
            self.set_route_settings(avoid_motorways=True if args.avoid_motorways else None,
                                    block_unpaved=True if args.block_unpaved else None,
                                    include_elevation=True if getattr(args, "hills", False) else None,
                                    avoid_stops_towns=True if getattr(args, "stops_towns", False) else None)
        else:
            self.set_route_settings(avoid_motorways=args.avoid_motorways, block_unpaved=args.block_unpaved,
                                    ride_suggestions=True if args.command == "suggestions" else None,
                                    include_elevation=getattr(args, "hills", False),
                                    avoid_stops_towns=getattr(args, "stops_towns", False))
        self.capture("01-options-ready")

        from_current_location = getattr(args, "from_current_location", False)
        from_endpoint_text = "Current location" if from_current_location else args.from_text
        current_location: tuple[float, float] | None = None
        if from_current_location:
            if not self.serial or not self.serial.startswith("emulator-"):
                raise VisualError("The current-location visual scenario requires an Android emulator.")
            try:
                latitude_text, longitude_text = args.location.split(",", 1)
                latitude, longitude = float(latitude_text), float(longitude_text)
            except (AttributeError, ValueError) as error:
                raise VisualError("--location must be a latitude,longitude pair.") from error
            if (
                not all(map(math.isfinite, (latitude, longitude)))
                or not -90.0 <= latitude <= 90.0
                or not -180.0 <= longitude <= 180.0
            ):
                raise VisualError("--location must contain valid geographic coordinates.")
            current_location = (latitude, longitude)
            fix_sent_at = time.monotonic()
            self.device_command("emu", "geo", "fix", str(longitude), str(latitude))
            self.tap_node("description", "Use current location")
            self.capture("02-current-location-requested")
            self.wait_for_node("text", "Current location", timeout=10)
            emit("current_location_selected", elapsed_seconds=round(time.monotonic() - fix_sent_at, 3))
            self.wait_for_node("text", "START", timeout=10)
            self.capture("02-current-location-selected")
        else:
            self.type_into("description", "From", args.from_text)
            time.sleep(0.4)
            self.capture("02-from-entered")
        self.type_into("description", "To", args.to_text)
        self.press_key("KEYCODE_ENTER")
        planner_action = self.wait_for_any_text(("START", "RIDE"), timeout=15)
        self.capture("03-endpoints-ready")

        if planner_action["text"] == "START":
            self.tap_node("text", "START")
            emit(
                "routing_started",
                from_text=from_endpoint_text,
                to_text=args.to_text,
                style=STYLE_NAMES[args.style],
                avoid_motorways=args.avoid_motorways,
                block_unpaved=args.block_unpaved,
            )
            self.capture_routing_start()
            # START slides straight to the ride styles; the wave grows a hump
            # per finished style while the card waits for the selected one.
            deadline = time.monotonic() + args.timeout
            next_progress = time.monotonic() + args.progress_interval
            progress_index = 1
            while time.monotonic() < deadline:
                cards = self.route_card_descriptions()
                if any(card.startswith("Route 1: ") for card in cards):
                    break
                errors = self.matching_nodes("text", "No match", contains=True) + self.matching_nodes(
                    "text", "No meaningfully different", contains=True
                )
                if errors:
                    self.capture("route-error")
                    raise VisualError("Route failed: " + errors[0]["text"])
                if time.monotonic() >= next_progress:
                    self.capture(f"routing-{progress_index:02d}")
                    progress_index += 1
                    next_progress = time.monotonic() + args.progress_interval
                time.sleep(0.8)
            else:
                self.capture("route-timeout")
                logs = self.app_logs(lines=50)
                raise VisualError(
                    f"Routing did not reach the RIDE state within {args.timeout:g}s.\n"
                    f"Recent app logs:\n{logs}"
                )
            emit("existing_route_ready", from_text=from_endpoint_text, to_text=args.to_text)

        if not args.style:
            self.wait_for_route_card(0, timeout=10)
        if args.route_index > 1:
            raise VisualError("The planner shows one route per ride style; choose a different --style instead of --route-index.")
        # The ride-style slider only exists once the search has every option
        # ready; it replaces the From/To fields.
        if args.style:
            self.set_style(args.style)
            # The higher stops are often still searching: watch the wave grow.
            self.wait_for_route_card(
                args.style, args.timeout, frames="styles-growing", frame_interval=args.progress_interval
            )
            time.sleep(0.6)
        self.capture("05-route-ready")
        if getattr(args, "save_bubble", False):
            self.capture_save_bubble()
        if getattr(args, "eta_layouts", False):
            self.capture_eta_layouts()

        if args.plan_only:
            return
        snapshot = self.wait_for_route_snapshot(args.route_index - 1)
        selected_route = snapshot["routes"][args.route_index - 1]
        start = point_at_percent(selected_route["points"], 0.0)
        if current_location is not None and distance_metres(
            start[0],
            start[1],
            current_location[0],
            current_location[1],
        ) > 1_500.0:
            raise VisualError(
                "The route did not start near the selected emulator location; "
                "the From coordinate may be stale."
            )
        if not self.serial or not self.serial.startswith("emulator-"):
            emit("gps_start_not_changed", reason="selected ADB device is not an Android emulator")
        else:
            self.device_command("emu", "geo", "fix", str(start[1]), str(start[0]))
        if before_ride is not None:
            before_ride()
        self.tap_node("text", "RIDE")
        self.wait_for_node("text", "End", timeout=30)
        if self.serial and self.serial.startswith("emulator-"):
            self.inject_location(start[0], start[1])
            time.sleep(args.step_delay)
            self.capture("ride-start-location")
        # Let the ride HUD and map camera settle before saving the first ride frame.
        time.sleep(5)
        self.capture("06-ride-active")
        if args.black_and_white is not None:
            modes = ("on", "off") if args.black_and_white == "both" else (args.black_and_white,)
            for mode in modes:
                self.set_black_and_white(mode)
        if args.progress_percent is not None:
            self.progress_to(args.progress_percent, args.step_delay)

    def read_route_snapshot(self) -> dict[str, Any]:
        raw = self.raw_device_bytes(
            "exec-out", "run-as", APP_ID, "cat", ROUTE_SNAPSHOT, timeout=30
        )
        try:
            snapshot = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise VisualError(f"Could not read the debug route geometry export: {error}") from error
        if snapshot.get("version") != 1 or not snapshot.get("routes"):
            raise VisualError("The debug route geometry export is empty or has an unsupported version.")
        return snapshot

    def wait_for_route_snapshot(self, selected_index: int, timeout: float = 20) -> dict[str, Any]:
        if selected_index < 0:
            raise VisualError("Route index must be one or greater.")
        deadline = time.monotonic() + timeout
        last_error = "route geometry has not been written yet"
        while time.monotonic() < deadline:
            try:
                snapshot = self.read_route_snapshot()
                if selected_index < len(snapshot["routes"]) and snapshot.get("selectedIndex") == selected_index:
                    return snapshot
                last_error = (
                    f"selected route {snapshot.get('selectedIndex')} is not route {selected_index + 1}"
                )
            except VisualError as error:
                last_error = str(error)
            time.sleep(0.25)
        raise VisualError(f"Timed out waiting for route geometry: {last_error}")

    def wait_for_route_snapshot_refresh(
        self,
        previous_created_millis: int | None,
        timeout: float = 30.0,
    ) -> dict[str, Any]:
        """Wait for the debug export to be rewritten for the just-selected style."""
        deadline = time.monotonic() + timeout
        last_error = "route geometry has not been rewritten yet"
        while time.monotonic() < deadline:
            try:
                snapshot = self.read_route_snapshot()
                created = snapshot.get("createdAtMillis")
                if isinstance(created, int) and (
                    previous_created_millis is None or created > previous_created_millis
                ):
                    return snapshot
                last_error = f"route geometry is still the previous export (createdAtMillis={created})"
            except VisualError as error:
                last_error = str(error)
            time.sleep(0.25)
        raise VisualError(
            f"Timed out waiting for the selected ride style's route geometry: {last_error}"
        )

    def verify_style_distinctness(self, rides: list[dict[str, Any]]) -> None:
        """Fail when two ride-style stops are the same ride or a tiny swap.

        The planner promises seven meaningfully different rides, so a
        duplicate or a stretch under clamp(5% of the ride, 750 m, 3 km) of
        distinct road (or 99% shared) is the bug this command exists to catch.
        """
        problems: list[str] = []
        for higher in range(len(rides)):
            for lower in range(higher):
                first, second = rides[higher], rides[lower]
                if not styles_are_same_ride(first["points"], second["points"]):
                    continue
                shared, stretch = route_geometry_diversity(first["points"], second["points"])
                problems.append(
                    f"{first['style']} repeats {second['style']} "
                    f"(shared {shared * 100:.1f}%, longest distinct stretch {stretch:.0f} m)"
                )
        if problems:
            raise VisualError(
                "The ride-style stops are not seven different rides: " + "; ".join(problems)
            )

    def inject_location(
        self,
        lat: float,
        lon: float,
        *,
        speed_mps: float | None = None,
        timestamp_ms: int | None = None,
        allow_estimate: bool = False,
        accuracy_m: float = 1.0,
        timeout: float = 20,
    ) -> dict[str, Any]:
        if not self.serial or not self.serial.startswith("emulator-"):
            raise VisualError(
                "Percentage route progress needs an Android emulator; ADB location injection "
                "is not available on a physical device."
            )
        request_id = time.time_ns()
        arguments = [
            "shell", "am", "broadcast",
            "-a", "com.curvemaps.app.DEBUG_VISUAL_ROUTE_FIX",
            "-p", APP_ID,
            "--es", "request_id", str(request_id),
            "--es", "lat", f"{lat:.7f}",
            "--es", "lon", f"{lon:.7f}",
        ]
        if not math.isfinite(accuracy_m) or accuracy_m < 0.0:
            raise VisualError("Injected accuracy must be finite and non-negative metres.")
        arguments += ["--ef", "accuracy", f"{accuracy_m:.4f}"]
        if speed_mps is not None:
            if not math.isfinite(speed_mps) or speed_mps < 0.0:
                raise VisualError("Injected speed must be finite and non-negative metres/second.")
            arguments += ["--ef", "speed", f"{speed_mps:.4f}"]
        if timestamp_ms is not None:
            arguments += ["--es", "timestamp_ms", str(timestamp_ms)]
        if allow_estimate:
            # Opts guidance into GPS-gap estimation once injections stop;
            # ordinary injected fixes keep frames still between captures.
            arguments += ["--ez", "allow_estimate", "true"]
        self.device_command(*arguments, timeout=timeout)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            try:
                raw_ack = self.raw_device_bytes(
                    "exec-out", "run-as", APP_ID, "cat", FIX_ACK, timeout=15
                )
                ack = json.loads(raw_ack.decode("utf-8"))
                if ack.get("requestId") == request_id:
                    # Keep this owned emulator's parked provider on the same
                    # point. UI inspection can exceed the 20 s debug authority
                    # window; a stale California fix must not pull the camera
                    # out of the offline Queensland map between screenshots.
                    self.device_command("emu", "geo", "fix", f"{lon:.7f}", f"{lat:.7f}")
                    self.last_replay_fix = (lat, lon, speed_mps, timestamp_ms)
                    return ack
            except (VisualError, UnicodeDecodeError, json.JSONDecodeError):
                pass
            time.sleep(0.05)
        raise VisualError(
            "The app did not acknowledge the debug route fix. Start ride mode first and "
            "make sure the installed app is a debug build."
        )

    def read_camera(self) -> dict[str, Any] | None:
        """Reads the debug camera probe; None until the map has settled once."""
        try:
            raw = self.raw_device_bytes(
                "exec-out", "run-as", APP_ID, "cat", CAMERA_SNAPSHOT, timeout=15
            )
            camera = json.loads(raw.decode("utf-8"))
        except (VisualError, UnicodeDecodeError, json.JSONDecodeError):
            return None
        return camera if isinstance(camera, dict) else None

    def inspect_camera(self, lat: float, lon: float, zoom: float, tilt: float, bearing: float = 0.0) -> None:
        """Debug-only deterministic view, without synthetic map gestures."""
        self.device_command(
            "shell", "am", "broadcast", "-a", "com.curvemaps.app.DEBUG_VISUAL_CAMERA", "-p", APP_ID,
            "--es", "request_id", str(time.time_ns()), "--es", "lat", str(lat), "--es", "lon", str(lon),
            "--es", "zoom", str(zoom), "--es", "tilt", str(tilt), "--es", "bearing", str(bearing),
        )
        camera = self.wait_for_camera("inspection view", lambda probe: camera_near(probe, lat, lon)
            and abs(probe_number(probe, "zoom") - zoom) < 0.1
            and abs(probe_number(probe, "tilt") - tilt) < 0.5)
        time.sleep(2)
        emit("inspection_camera", camera=camera)

    def wait_for_ride_map(self, *, dark: bool, timeout: float = 30.0) -> dict[str, Any]:
        # A style switch is asynchronous. Keep the replay's real session/camera
        # authoritative and exercise its first post-reload location update.
        if self.last_replay_fix is not None:
            lat, lon, speed, timestamp_ms = self.last_replay_fix
            self.inject_location(lat, lon, speed_mps=speed, timestamp_ms=timestamp_ms)
        deadline = time.monotonic() + timeout
        probe: dict[str, Any] | None = None
        # Every request issued during this wait is post-switch, so an answer to
        # any of them is current. A busy device can take longer than one poll
        # to answer; matching only the newest id discarded every late answer.
        issued: set[int] = set()
        while time.monotonic() < deadline:
            request_id = time.time_ns()
            issued.add(request_id)
            self.device_command("shell", "am", "broadcast", "-a", "com.curvemaps.app.DEBUG_VISUAL_MAP_PROBE",
                                "-p", APP_ID, "--es", "request_id", str(request_id))
            answer_deadline = min(deadline, time.monotonic() + 2.0)
            while time.monotonic() < answer_deadline:
                time.sleep(0.25)
                try:
                    answer = json.loads(self.raw_device_bytes(
                        "exec-out", "run-as", APP_ID, "cat", "cache/visual-map-probe.json"))
                except (VisualError, json.JSONDecodeError):
                    continue
                if answer.get("requestId") not in issued:
                    continue
                probe = answer
                if (probe.get("dark") == dark and probe.get("basemapFeatures", 0) > 0
                        and probe.get("routeReady") is True and probe.get("riderFeatures", 0) > 0):
                    emit("ride_map_ready", probe=probe)
                    return probe
                break  # answered, not ready yet: ask again
        raise VisualError(f"Ride map/route/rider never finished rendering after style switch (wanted dark={dark}): {probe}")

    def buildings(self, args: argparse.Namespace) -> None:
        """Offline house/city/occlusion/style/lifecycle evidence on an explicitly owned AVD."""
        if not self.requested_serial:
            raise VisualError("buildings requires --serial or ANDROID_SERIAL for an exclusively owned device.")
        self.ensure_device(start_if_missing=False)
        original = {key: self.device_command("shell", "settings", "get", "system", key).stdout.strip()
                    for key in ("accelerometer_rotation", "user_rotation")}
        airplane = self.device_command("shell", "settings", "get", "global", "airplane_mode_on").stdout.strip()
        permissions = ("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")
        package_state = self.device_command("shell", "dumpsys", "package", APP_ID, check=False).stdout
        originally_granted = {permission: bool(re.search(re.escape(permission) + r": granted=true", package_state))
                              for permission in permissions}
        original_dark: bool | None = None
        locations = self.device_command("shell", "dumpsys", "location", check=False).stdout
        parked_gps = re.search(r"last location=Location\[gps (-?[\d.]+),(-?[\d.]+)", locations)
        try:
            self.device_command("shell", "settings", "put", "system", "accelerometer_rotation", "0")
            self.device_command("shell", "settings", "put", "system", "user_rotation", "0")
            self.launch_app(build=not args.no_build, skip_map=False, refresh_map=False)
            time.sleep(5)  # Allow the first live-location centring to finish before inspection.
            self.device_command("shell", "cmd", "connectivity", "airplane-mode", "enable")
            for label, lat, lon, zoom, tilt in (
                ("buildings-flat-overview", -27.4679, 153.0281, 13.0, 0.0),
                ("buildings-below-threshold", -27.4679, 153.0281, 13.9, 58.0),
                ("buildings-threshold", -27.4679, 153.0281, 14.0, 58.0),
                ("buildings-city-intermediate", -27.4679, 153.0281, 16.0, 58.0),
                ("buildings-city-towers", -27.4679, 153.0281, 18.0, 58.0),
                ("buildings-residential-houses", -27.4616, 153.0466, 18.5, 58.0),
            ):
                self.inspect_camera(lat, lon, zoom, tilt)
                self.capture(label)
            # A short real GraphHopper route through the dense CBD. All controls
            # are selected through semantics; fixes drive the real navigation session.
            route_args = argparse.Namespace(from_text="-27.4698,153.0251", to_text="-27.4570,153.0350",
                style=0, avoid_motorways=None, block_unpaved=None, route_index=1, timeout=300,
                progress_interval=5, progress_percent=None, step_delay=0.2, black_and_white=None,
                plan_only=False, reuse_app=True, no_build=True, skip_map=False, refresh_map=False)
            self.route(route_args)
            original_dark = bool(self.matching_nodes("description", "Dark ride map, on"))
            self.set_black_and_white("off")
            snapshot = self.read_route_snapshot()
            points = snapshot["routes"][snapshot["selectedIndex"]]["points"]
            cumulative, distance = cumulative_distance(points)
            for index, speed in enumerate((0.0, 8.0, 15.0, 23.0, 32.0)):
                offset = min(100.0 + index * 120.0, distance * 0.65)
                lat, lon = self.inject_route_fix(points, cumulative, offset, speed_mps=speed)
                self.wait_for_camera("navigation speed band", lambda p: p.get("guidance") is True
                    and camera_near(p, lat, lon) and abs(probe_number(p, "zoom") - guidance_zoom_band(speed)) < 0.25)
                time.sleep(2)
                self.wait_for_node("text", "End", timeout=10)
                self.wait_for_node("description", "Ride data: ", contains=True, timeout=10)
                self.capture(f"buildings-city-navigation-speed-{speed:g}")
            # Deliberately put towers between camera and street; pitched viewpoints
            # expose depth occlusion that layer-order checks cannot prove away.
            offset = min(580.0, distance * 0.65)
            lat, lon = self.inject_route_fix(points, cumulative, offset, speed_mps=0.0)
            time.sleep(1)
            for bearing in (45.0, 225.0):
                self.inject_route_fix(points, cumulative, offset, speed_mps=0.0)
                time.sleep(1)
                self.inspect_camera(lat, lon, 17.0, 58.0, bearing)
                self.capture(f"buildings-route-silhouette-{bearing:g}")
            self.progress_to(65.0, 0.2)
            self.set_black_and_white("on")
            self.set_black_and_white("off")
            self.device_command("shell", "settings", "put", "system", "user_rotation", "1")
            time.sleep(3)
            # This main-base app recreates the planner on orientation change
            # (navigation is not persisted). Start a fresh landscape ride rather
            # than pretending a stale session survived, or changing that policy.
            self.route(route_args)
            self.set_black_and_white("off")
            self.wait_for_node("text", "End", timeout=15)
            self.capture("buildings-landscape-navigation")
            self.tap_node("text", "End")
            self.wait_for_node("text", "START", timeout=15)
            self.capture("buildings-landscape-end-reachable")
            self.device_command("shell", "settings", "put", "system", "user_rotation", "0")
            time.sleep(3)
            self.route(route_args)
            self.set_black_and_white("on" if original_dark else "off")
            self.tap_node("text", "End")
            self.wait_for_node("text", "START", timeout=15)
            self.capture("buildings-end-planning-restore")
            self.device_command("shell", "am", "force-stop", APP_ID)
            self.device_command("shell", "am", "start", "-n", ACTIVITY)
            self.wait_for_node("text", "START", timeout=30)
            self.inspect_camera(-27.4616, 153.0466, 18.5, 58.0)
            self.capture("buildings-relaunch-installed-map-houses")
        finally:
            if original_dark is not None:
                try:
                    if self.matching_nodes("description", "Dark ride map, ", contains=True):
                        self.set_black_and_white("on" if original_dark else "off")
                except (VisualError, subprocess.TimeoutExpired, OSError) as error:
                    emit("appearance_restore_warning", message=str(error))
            self.device_command("shell", "cmd", "connectivity", "airplane-mode", "enable" if airplane == "1" else "disable", check=False)
            for key, value in original.items():
                if value == "null":
                    self.device_command("shell", "settings", "delete", "system", key, check=False)
                else:
                    self.device_command("shell", "settings", "put", "system", key, value, check=False)
            for permission, granted in originally_granted.items():
                if not granted:
                    self.device_command("shell", "pm", "revoke", APP_ID, permission, check=False)
            if parked_gps and self.serial and self.serial.startswith("emulator-"):
                self.device_command("emu", "geo", "fix", parked_gps.group(2), parked_gps.group(1), check=False)

    def installed_map_bytes(self) -> int | None:
        result = self.device_command(
            "shell", "run-as", APP_ID, "sh", "-c", "ls -l files/tiles/basemap.pmtiles",
            check=False, timeout=20,
        )
        match = re.search(r"\s(\d+)\s+\d{4}-\d{2}-\d{2}", result.stdout)
        return int(match.group(1)) if match else None

    def gpu_identity(self) -> dict[str, str]:
        """Best-effort GPU identity so a perf report can be attributed.

        Vulkan map rendering goes through ``cmd gpu vkjson``'s device; the
        emulator's OpenGL ES path is what ``dumpsys SurfaceFlinger`` reports.
        Both are optional evidence: a missing probe never fails the run.
        """
        identity: dict[str, str] = {}
        vkjson = self.device_command("shell", "cmd", "gpu", "vkjson", check=False, timeout=30).stdout
        match = re.search(r'"deviceName"\s*:\s*"([^"]*)"', vkjson)
        if match and match.group(1):
            identity["vulkanDeviceName"] = match.group(1)
        surface_flinger = self.device_command("shell", "dumpsys", "SurfaceFlinger", check=False, timeout=30).stdout
        gles = re.search(r"GLES:\s*(\S.*)", surface_flinger)
        if gles:
            identity["glesRenderer"] = gles.group(1).strip()
        return identity

    def set_emulator_location(self, lat: float, lon: float) -> None:
        if not self.serial or not self.serial.startswith("emulator-"):
            raise VisualError("Cold sweep positioning is available only on an Android emulator.")
        self.device_command("emu", "geo", "fix", str(lon), str(lat))

    def restart_app(self) -> None:
        self.device_command("shell", "am", "force-stop", APP_ID)
        self.device_command("shell", "am", "start", "-n", ACTIVITY)
        self.wait_for_node("text", "START", timeout=60)

    def queue_map_perf_sweep(self, plan: dict[str, Any]) -> int:
        request_id = int(plan["requestId"])
        payload = json.dumps(plan, separators=(",", ":"))
        # The device shell re-tokenizes adb's argument list, so the JSON must
        # reach `am` as one single-quoted argument (labels are already
        # restricted to [a-z0-9-], so no quote can appear inside the plan).
        if "'" in payload:
            raise VisualError("Perf sweep plans must not contain single quotes.")
        self.device_command(
            "shell",
            f"am broadcast -a {PERF_SWEEP_ACTION} -p {APP_ID} --es plan '{payload}'",
        )
        return request_id

    def read_map_perf_result(self, request_id: int, *, timeout: float) -> dict[str, Any]:
        path = f"{PERF_RESULT_PREFIX}{request_id}.json"
        deadline = time.monotonic() + timeout
        last_error = ""
        while time.monotonic() < deadline:
            try:
                raw = self.raw_device_bytes("exec-out", "run-as", APP_ID, "cat", path, timeout=20)
                result = json.loads(raw.decode("utf-8"))
                if isinstance(result, dict) and result.get("requestId") == request_id:
                    return result
            except (VisualError, UnicodeDecodeError, json.JSONDecodeError) as error:
                last_error = str(error)
            time.sleep(0.4)
        raise VisualError(
            f"Sweep {request_id} never exported a report within {timeout:.0f}s ({last_error})."
        )

    def run_perf_sweep(
        self,
        case: str,
        mode: str,
        lat: float,
        lon: float,
        zoom: float,
        phase: str,
        *,
        settle: bool,
        args: argparse.Namespace,
    ) -> dict[str, Any]:
        """Queues one sweep, waits for its exported report, and keeps evidence."""
        request_id = time.time_ns()
        label = f"{case}-{mode}-{phase}"
        plan = perf_sweep_plan(
            request_id=request_id,
            label=label,
            motion=args.motion,
            lat=lat,
            lon=lon,
            zoom=zoom,
            tilt=args.tilt,
            duration_ms=int(args.duration * 1000),
            building_mode=mode,
            settle=settle,
        )
        self.queue_map_perf_sweep(plan)
        if args.screenshot_motion:
            time.sleep(max(0.7, args.duration * 0.45))
            self.capture(f"{label}-motion", include_tree=False)
        result = self.read_map_perf_result(request_id, timeout=args.duration + 45)
        result["case"] = case
        result["phase"] = phase
        result["zoom"] = zoom
        result["requestedTilt"] = args.tilt
        self.ensure_capture_dirs()
        report_path = self.run_dir / f"{label}.json"
        report_path.write_text(json.dumps(result, indent=2), encoding="utf-8")
        all_stats = perf_segment(result)
        emit(
            "perf_sweep",
            case=case,
            mode=mode,
            phase=phase,
            label=label,
            report=str(report_path),
            completed=result.get("completed"),
            interrupted=result.get("interrupted"),
            frames=int(perf_number(all_stats, "frames")),
            fps=round(perf_primary_fps(result), 2),
            p50Ms=round(perf_number(all_stats, "p50Ms"), 2),
            p95Ms=round(perf_number(all_stats, "p95Ms"), 2),
            p99Ms=round(perf_number(all_stats, "p99Ms"), 2),
            stalls50=int(perf_number(all_stats, "stalls50")),
            stalls100=int(perf_number(all_stats, "stalls100")),
        )
        return result

    def perf(self, args: argparse.Namespace) -> None:
        """Repeatable native-frame benchmark of the real offline map.

        Cold runs measure tile/geometry loading on a fresh process (the app is
        restarted and parked far away first so the case's tiles are genuinely
        cold); warm runs repeat the same motion on the settled map. Building
        modes A/B the extrusion rendering with the identical camera plan.
        """
        if not self.requested_serial:
            raise VisualError("perf requires --serial or ANDROID_SERIAL for an exclusively owned device.")
        targets = [item.strip() for item in args.targets.split(",") if item.strip()]
        if not targets:
            raise VisualError("perf needs at least one target.")
        for target in targets:
            if target not in PERF_TARGETS:
                raise VisualError(
                    f"Unknown perf target '{target}'. Known targets: {', '.join(sorted(PERF_TARGETS))}."
                )
        zoom_plan = perf_zoom_plan(targets, args.zooms)
        modes = [item.strip().lower() for item in args.building_modes.split(",") if item.strip()]
        for mode in modes:
            if mode not in ("current", "opaque", "hidden"):
                raise VisualError(f"Unknown building mode '{mode}'.")
        if args.repeat < 1:
            raise VisualError("perf needs --repeat of at least 1.")
        if not 1.0 <= args.duration <= 60.0:
            raise VisualError("perf --duration must be between 1 and 60 seconds.")

        self.launch_app(build=not args.no_build, skip_map=args.skip_map, refresh_map=args.refresh_map)
        archive_bytes = self.installed_map_bytes()
        gpu = self.gpu_identity()
        original_location = None
        if self.serial and self.serial.startswith("emulator-"):
            locations = self.device_command("shell", "dumpsys", "location", check=False).stdout
            parked = re.search(r"last location=Location\[gps (-?[\d.]+),(-?[\d.]+)", locations)
            if parked:
                original_location = (float(parked.group(1)), float(parked.group(2)))
        results: list[dict[str, Any]] = []
        try:
            for mode in modes:
                for target in targets:
                    lat, lon = PERF_TARGETS[target]
                    for zoom in zoom_plan[target]:
                        case = f"brisbane-{target}-z{zoom:g}"
                        if not args.no_cold and mode == modes[0]:
                            # Park the emulator far away so the app's one-shot
                            # initial centring cannot warm the case tiles and
                            # cannot yank the camera mid-sweep.
                            if original_location is not None:
                                self.set_emulator_location(-16.9203, 145.7710)
                            self.restart_app()
                            time.sleep(args.settle)
                            results.append(
                                self.run_perf_sweep(case, mode, lat, lon, zoom, "cold", settle=False, args=args)
                            )
                        if not args.no_warm:
                            self.inspect_camera(lat, lon, zoom, args.tilt)
                            for repeat in range(1, args.repeat + 1):
                                results.append(
                                    self.run_perf_sweep(case, mode, lat, lon, zoom, "warm", settle=True, args=args)
                                )
                                if repeat == 1 and args.screenshots:
                                    # A settled view of the case camera is the
                                    # building-visibility evidence; it runs
                                    # after the sweep so it never perturbs the
                                    # measured frames.
                                    self.inspect_camera(lat, lon, zoom, args.tilt)
                                    self.capture(f"{case}-{mode}-view", include_tree=False)
        finally:
            if original_location is not None:
                self.set_emulator_location(*original_location)

        generated = utc_now()
        summary = {
            "generated": generated,
            "serial": self.serial,
            "avd": self.avd,
            "archiveBytes": archive_bytes,
            "deviceGpu": gpu,
            "zooms": {target: list(zoom_plan[target]) for target in targets},
            "durationSeconds": args.duration,
            "motion": args.motion,
            "tilt": args.tilt,
            "repeat": args.repeat,
            "sweeps": results,
        }
        self.ensure_capture_dirs()
        summary_path = self.run_dir / "perf-summary.json"
        summary_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
        report = perf_report_markdown(
            results,
            device=f"{self.serial} ({self.avd})",
            generated=generated,
            app_build="debug (assembleDebug; MapLibre backend per sweep 'backend' object)",
            archive_bytes=archive_bytes,
            device_gpu=gpu,
        )
        report_path = Path(args.report).expanduser()
        if not report_path.is_absolute():
            report_path = self.root / report_path
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(report, encoding="utf-8")
        emit(
            "perf_report",
            report=str(report_path),
            summary=str(summary_path),
            sweeps=len(results),
            warm=[result for result in results if result.get("phase") == "warm"],
        )

    def wait_for_camera(
        self,
        description: str,
        predicate: Any,
        *,
        timeout: float = 10.0,
        not_before_ms: int | None = None,
    ) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        camera: dict[str, Any] | None = None
        while time.monotonic() < deadline:
            candidate = self.read_camera()
            if candidate is not None:
                camera = candidate
                if not_before_ms is not None and candidate.get("createdAtMillis", 0) < not_before_ms:
                    time.sleep(0.25)
                    continue
                if predicate(candidate):
                    return candidate
            time.sleep(0.25)
        raise VisualError(
            f"The guidance camera never matched {description}. Last probe: {camera}"
        )

    def ride_progress_percent(self, total_distance_m: float) -> float:
        nodes, _ = self.hierarchy()
        hud = next(
            (node["description"] for node in nodes if node["description"].startswith("Ride data: ")),
            None,
        )
        if hud is None:
            raise VisualError("The ride HUD is not visible; start a route with RIDE before setting progress.")
        remaining = re.search(r",\s*([0-9,.]+)\s*(km|m) left,", hud)
        if not remaining:
            raise VisualError(f"Could not read remaining route distance from the HUD: {hud!r}.")
        remaining_m = float(remaining.group(1).replace(",", ""))
        if remaining.group(2) == "km":
            remaining_m *= 1000
        covered_m = max(0.0, min(total_distance_m, total_distance_m - remaining_m))
        return 100.0 * covered_m / total_distance_m

    def progress_to(
        self,
        target_percent: float,
        step_delay: float = 0.15,
    ) -> None:
        if not 0.0 <= target_percent <= 100.0:
            raise VisualError("Route progress must be between 0 and 100 percent.")
        if step_delay < 0.0 or step_delay > 5.0:
            raise VisualError("Step delay must be between 0 and 5 seconds.")
        if not self.serial or not self.serial.startswith("emulator-"):
            raise VisualError("Percentage route progress is available only on an Android emulator.")

        self.wait_for_node("text", "End", timeout=10)
        initial_snapshot = self.read_route_snapshot()
        snapshot_index = int(initial_snapshot.get("selectedIndex", 0))
        snapshot = self.wait_for_route_snapshot(snapshot_index)
        route = snapshot["routes"][snapshot_index]
        points = route["points"]
        cumulative, total_distance = cumulative_distance(points)
        if total_distance <= 0.0:
            raise VisualError("The selected route has no measurable length.")

        current_percent = self.ride_progress_percent(total_distance)
        restarted = target_percent + 0.5 < current_percent
        if restarted:
            self.tap_node("text", "End")
            self.wait_for_node("text", "START", timeout=15)
            self.capture("progress-restart-planner")
            self.tap_node("text", "START")
            route_deadline = time.monotonic() + 300
            next_progress_capture = time.monotonic() + 2
            while time.monotonic() < route_deadline:
                if self.matching_nodes("text", "RIDE"):
                    break
                errors = self.matching_nodes("text", "No match", contains=True) + self.matching_nodes(
                    "text", "No meaningfully different", contains=True
                )
                if errors:
                    self.capture("progress-restart-route-error")
                    raise VisualError("Could not restore the original route: " + errors[0]["text"])
                if time.monotonic() >= next_progress_capture:
                    self.capture("progress-restarting-route")
                    next_progress_capture = time.monotonic() + 2
                time.sleep(0.8)
            else:
                self.capture("progress-restart-timeout")
                raise VisualError("Timed out recalculating the route after a backward progress jump.")

            refreshed = self.wait_for_route_snapshot(0)
            refreshed_index = min(snapshot_index, len(refreshed["routes"]) - 1)
            if refreshed_index > 0:
                self.tap_node("description", f"Route {refreshed_index + 1}:", contains=True)
                time.sleep(0.5)
                refreshed = self.wait_for_route_snapshot(refreshed_index)
            route = refreshed["routes"][refreshed_index]
            points = route["points"]
            cumulative, total_distance = cumulative_distance(points)
            if total_distance <= 0.0:
                raise VisualError("The recalculated route has no measurable length.")
            self.capture("progress-restarted-route-ready")
            self.tap_node("text", "RIDE")
            self.wait_for_node("text", "End", timeout=20)
            start_lat, start_lon = point_at_offset(points, cumulative, 0.0)
            self.inject_location(start_lat, start_lon)
            time.sleep(max(0.1, step_delay))
            current_percent = 0.0
            self.capture("progress-restarted-at-start")

        start_offset = total_distance * current_percent / 100.0
        target_offset = total_distance * target_percent / 100.0
        if target_offset < start_offset:
            start_offset = 0.0
        start_segment = max(0, bisect.bisect_right(cumulative, start_offset) - 1)
        target_segment = max(0, bisect.bisect_right(cumulative, target_offset) - 1)
        # NavigationSession searches at most 40 vertices ahead. Keeping these
        # injected fixes within 25 vertices makes large percentage jumps follow
        # the real matcher path instead of teleporting past its search window.
        fix_offsets: list[float] = []
        segment = start_segment + 25
        while segment < len(points) - 1 and segment < target_segment:
            fix_offsets.append(cumulative[segment])
            segment += 25
        if target_offset > start_offset or not fix_offsets:
            fix_offsets.append(target_offset)

        capture_indices = {
            max(0, round((len(fix_offsets) - 1) * fraction / 6))
            for fraction in range(1, 7)
        }
        capture_indices.discard(0)
        for index, offset in enumerate(fix_offsets):
            lat, lon = point_at_offset(points, cumulative, offset)
            self.inject_location(lat, lon)
            if step_delay:
                time.sleep(step_delay)
            if index in capture_indices:
                self.capture(f"progress-{target_percent:g}-step-{index + 1}")

        # The emulator accepts the final GPS fix asynchronously. Wait until
        # the HUD reflects the requested point (rounded to whole distance units).
        tolerance_percent = max(0.5, 1200.0 / total_distance * 100.0)
        deadline = time.monotonic() + 20
        observed_percent = current_percent
        while time.monotonic() < deadline:
            observed_percent = self.ride_progress_percent(total_distance)
            if abs(observed_percent - target_percent) <= tolerance_percent:
                break
            time.sleep(0.5)
        else:
            raise VisualError(
                f"Navigation HUD reached {observed_percent:.1f}% instead of "
                f"{target_percent:.1f}% after {len(fix_offsets)} GPS fixes."
            )
        self.capture(f"progress-{target_percent:g}-percent")
        emit(
            "route_progress",
            requested_percent=target_percent,
            observed_percent=round(observed_percent, 1),
            fixes_sent=len(fix_offsets),
            restarted_from_start=restarted,
            coordinate=point_at_offset(points, cumulative, target_offset),
        )

    def inject_route_fix(
        self,
        points: list[list[float]],
        cumulative: list[float],
        offset_m: float,
        *,
        speed_mps: float | None = None,
    ) -> tuple[float, float]:
        lat, lon = point_at_offset(points, cumulative, offset_m)
        self.inject_location(lat, lon, speed_mps=speed_mps)
        return lat, lon

    def inject_route_span(
        self,
        points: list[list[float]],
        cumulative: list[float],
        start_m: float,
        end_m: float,
        *,
        step_m: float,
        speed_mps: float | None,
    ) -> tuple[float, float]:
        if step_m <= 0.0:
            raise VisualError("The turn step must be a positive distance in metres.")
        steps = max(1, int(round(abs(end_m - start_m) / step_m)))
        position = (0.0, 0.0)
        for index in range(steps + 1):
            offset = start_m + (end_m - start_m) * index / steps
            position = self.inject_route_fix(points, cumulative, offset, speed_mps=speed_mps)
            time.sleep(0.2)
        return position

    @staticmethod
    def settle_for_capture(args: argparse.Namespace) -> None:
        """Lets the 600 ms camera animation and the repaint frame land before capturing."""
        time.sleep(max(0.0, args.settle))

    @staticmethod
    def ride_map_modes(black_and_white: str | None) -> list[str | None]:
        """Requested ride-map styles per frame; `None` leaves the style alone."""
        if black_and_white is None:
            return [None]
        if black_and_white == "both":
            return ["off", "on"]
        return [black_and_white]

    def capture_ride_frame(self, args: argparse.Namespace, label: str) -> None:
        """Captures one guidance frame once per requested ride-map style.

        With `--black-and-white both`, every guidance frame (street-close,
        each speed band's z14-z18 zoom, and the turn) is saved in colour and in
        B&W, including the compact sound/moon rail and its toggle states.
        The street-close frames show the raised arrow and its dark grayscale
        outline over the routed road at the same camera state.
        A single requested mode keeps the historical label with
        no suffix, so existing runs keep their artifact names.
        """
        for mode in self.ride_map_modes(args.black_and_white):
            suffix = ""
            if args.black_and_white == "both":
                suffix = "-bw" if mode == "on" else "-color"
            if mode is not None:
                self.apply_black_and_white(mode)
            self.capture(label + suffix)
            if mode is not None:
                emit(
                    "ride_map_mode_frame",
                    label=label + suffix,
                    black_and_white=mode == "on",
                    screenshot=str(self.output_dir / "latest.png"),
                )

    def capture_follow_pause(self, args: argparse.Namespace, lat: float, lon: float) -> None:
        """Pan inside accessible map bounds, capture the pause, then prove automatic return."""
        for mode in self.ride_map_modes(args.black_and_white):
            if mode is not None:
                self.apply_black_and_white(mode)
            suffix = "-bw" if mode == "on" else "-color" if mode == "off" else ""
            self.wait_for_camera("locked follow before pan", lambda probe: camera_near(probe, lat, lon))
            if self.matching_nodes("description", "Map"):
                node = self.select_node("description", "Map")
            else:
                node = self.select_node(
                    "description", "Showing a Map created with MapLibre", contains=True,
                )
            left, top, right, bottom = self.center(node["bounds"])
            width, height = right - left, bottom - top
            self.swipe(
                int(left + width * 0.30), int(top + height * 0.45),
                int(left + width * 0.65), int(top + height * 0.50), 500,
            )
            paused = self.read_camera()
            if paused is None or not paused.get("followSuspended"):
                raise VisualError(f"A locked map pan did not report its follow pause: {paused}")
            self.capture("nav-camera-gesture-paused" + suffix, include_tree=False)
            camera = self.wait_for_camera(
                "automatic return after the gesture pause",
                lambda probe: not probe.get("followSuspended") and camera_near(probe, lat, lon)
                and abs(probe_number(probe, "tilt") - GUIDANCE_TILT_DEGREES) <= 1.0,
            )
            self.wait_for_node("description", "Rider lock on; tap to release", timeout=10)
            self.capture("nav-camera-gesture-returned" + suffix)
            emit("nav_camera", stage="gesture-pause-return", paused=paused, camera=camera)

    def suggestions(self, args: argparse.Namespace) -> None:
        """Drive real GPS, cadence, routing, banner and acceptance; no fake proposal."""
        if args.style == 0 or args.plan_only or args.progress_percent is not None:
            raise VisualError("suggestions needs a fresh non-Fastest ride; omit --plan-only and --progress.")
        speed = args.speed
        if not math.isfinite(speed) or speed < 25 / 3.6:
            raise VisualError("suggestions speed must be at least 25 km/h.")
        if not math.isfinite(args.max_distance) or args.max_distance < 5_000:
            raise VisualError("suggestions --max-distance must be at least 5000 metres.")
        self.route(args)
        snapshot = self.read_route_snapshot()
        baseline = snapshot["routes"][snapshot["selectedIndex"]]
        points, distances = baseline["points"], baseline["cumulativeDistanceM"]
        clock_ms = 1_000
        offset = 0.0
        self.inject_location(*point_at_offset(points, distances, offset), speed_mps=speed, timestamp_ms=clock_ms)
        self.capture_ride_frame(args, "suggestions-before-check")

        def state() -> dict[str, Any]:
            raw = self.raw_device_bytes("exec-out", "run-as", APP_ID, "cat", "cache/visual-suggestion.json")
            return json.loads(raw.decode("utf-8"))

        # Include vertices: percentage teleports can bypass the matcher's forward window.
        end = min(args.max_distance, distances[-1] - 15_000)
        offsets = sorted({*(value for value in distances if 0 < value <= end),
                          *(float(value) for value in range(100, int(end) + 1, 100))})
        proposal = None
        for next_offset in offsets:
            clock_ms += max(1, round((next_offset - offset) / speed * 1_000))
            ack = self.inject_location(*point_at_offset(points, distances, next_offset),
                                       speed_mps=speed, timestamp_ms=clock_ms)
            offset = next_offset
            check_deadline = time.monotonic() + args.timeout
            while ack.get("suggestionCheckInFlight"):
                if time.monotonic() >= check_deadline:
                    raise VisualError("Suggestion routing check did not finish within --timeout.")
                time.sleep(0.1)
                ack = self.inject_location(*point_at_offset(points, distances, offset),
                                           speed_mps=speed, timestamp_ms=clock_ms)
            if int(offset) // 1_000 != int(next_offset - 0.1) // 1_000:
                emit("suggestion_replay", offset_m=round(offset), remaining_m=ack.get("remainingDistanceM"))
            if ack.get("state") != "OnRoute":
                raise VisualError(f"suggestions replay left the baseline at {offset:.0f} m: {ack}")
            time.sleep(0.04)
            current = state()
            if current["state"].startswith("pending:"):
                proposal = current
                break
            # Let an asynchronous routing check finish before advancing past its fork.
            if offset >= 5_000 and int(offset) % 100 == 0:
                time.sleep(args.step_delay)
        if proposal is None:
            self.capture_ride_frame(args, "suggestions-no-offer")
            raise VisualError(f"No real diversion qualified within {offset:.0f} m. Inspect CurveMaps.Suggest logs.")
        emit("suggestion_proposed", **{key: proposal[key] for key in ("state", "extraTimeS", "extraDistanceM", "curvyGainM")})
        self.wait_for_node("description", "Take scenic diversion", timeout=10)
        self.capture_ride_frame(args, "suggestions-offer")
        if args.decision in ("take", "skip"):
            self.tap_node("description", "Take scenic diversion" if args.decision == "take" else "Skip scenic diversion")
            wanted = "accepted:" if args.decision == "take" else "skipped:"
        else:
            wanted = "accepted:" if args.decision == "ride" else "expired:"
            replay_points = proposal["points"] if args.decision == "ride" else points
            replay_distances = proposal["cumulativeDistanceM"] if args.decision == "ride" else distances
            start = max(0.0, offset - proposal["baselineOffsetM"]) if args.decision == "ride" else offset
            fork = proposal["divergeM"] + (0 if args.decision == "ride" else proposal["baselineOffsetM"])
            finish = min(replay_distances[-1], fork + 500)
            replay_offsets = sorted({*(value for value in replay_distances if start < value <= finish),
                                     *(value / 1.0 for value in range(int(start) + 20, int(finish), 20)), finish})
            previous = start
            reminded = False
            for next_offset in replay_offsets:
                clock_ms += max(1, round((next_offset - previous) / speed * 1_000))
                self.inject_location(*point_at_offset(replay_points, replay_distances, next_offset),
                                     speed_mps=speed, timestamp_ms=clock_ms)
                previous = next_offset
                if not reminded and fork - next_offset <= max(150, speed * 8):
                    self.capture_ride_frame(args, "suggestions-near-fork")
                    reminded = True
                if state()["state"].startswith(wanted):
                    break
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            current = state()
            if current["state"].startswith(wanted):
                self.capture_ride_frame(args, "suggestions-" + args.decision)
                emit("suggestion_resolved", state=current["state"])
                return
            time.sleep(0.1)
        raise VisualError(f"Suggestion did not resolve as {wanted}: {current}")

    def pace(self, args: argparse.Namespace) -> None:
        """Replay real route model times on a deterministic navigation clock."""
        if args.plan_only or args.progress_percent is not None:
            raise VisualError("pace needs a fresh ride; omit --plan-only and --progress.")
        self.route(args)
        snapshot = self.read_route_snapshot()
        route = snapshot["routes"][snapshot["selectedIndex"]]
        points = route["points"]
        distances = route.get("cumulativeDistanceM")
        times = route.get("cumulativeTimeS")
        if not distances or not times or distances[-1] < 650:
            raise VisualError("pace needs a debug model-time export and a route at least 650 m long.")

        def model_time(offset: float) -> float:
            index = min(bisect.bisect_right(distances, offset) - 1, len(distances) - 2)
            length = distances[index + 1] - distances[index]
            fraction = (offset - distances[index]) / length if length > 0 else 0
            return times[index] + fraction * (times[index + 1] - times[index])

        clock_ms = 1_000
        offset = 0.0
        raw_delta = 0.0
        expected: float | None = None
        lat, lon = point_at_offset(points, distances, offset)
        ack = self.inject_location(lat, lon, speed_mps=0.0, timestamp_ms=clock_ms)
        if ack.get("paceDeltaS") is not None:
            raise VisualError("pace must start with an unmeasured navigation session.")

        def checkpoint(label: str) -> None:
            observed = ack.get("paceDeltaS")
            if ack.get("state") != "OnRoute":
                raise VisualError(f"pace left the route at {label}: {ack}")
            if expected is None:
                if observed is not None:
                    raise VisualError(f"pace published before minimum movement: {ack}")
                description = "pace delta unavailable"
            else:
                if observed is None or abs(observed - expected) > 0.2:
                    raise VisualError(f"pace at {label}: expected {expected:.3f} s, got {observed}")
                # NavigationHudFormat.deltaDescription: whole seconds, rounded half up on the magnitude.
                seconds = math.floor(abs(observed) + 0.5)
                unit = "second" if seconds == 1 else "seconds"
                description = ("pace on plan" if seconds == 0
                               else f"pace {seconds} {unit} {'ahead' if observed < 0 else 'behind'}")
            self.wait_for_node("description", description, contains=True, timeout=10)
            self.capture_ride_frame(args, f"pace-{label}")
            emit("pace_checkpoint", label=label, offset_m=offset,
                 expected_delta_s=expected, observed_delta_s=observed)

        checkpoint("00-unmeasured")
        for label, end, factor in (("01-on-plan", 200.0, 1.0),
                                   ("02-losing", 400.0, 1.5),
                                   ("05-recovering", 600.0, 0.5)):
            # Include vertices so even dense geometry stays inside the matcher's window.
            offsets = sorted({end, *(value for value in distances if offset < value < end),
                              *(value for value in range(20, int(end), 20) if value > offset)})
            for next_offset in offsets:
                planned = model_time(next_offset) - model_time(offset)
                elapsed_ms = max(1, round(planned * factor * 1_000))
                speed = (next_offset - offset) / (elapsed_ms / 1_000)
                if elapsed_ms > 30_000 or speed < 1.0:
                    raise VisualError("pace replay requires moving road segments with intervals at most 30 s.")
                clock_ms += elapsed_ms
                raw_delta += elapsed_ms / 1_000 - planned
                lat, lon = point_at_offset(points, distances, next_offset)
                ack = self.inject_location(lat, lon, speed_mps=speed, timestamp_ms=clock_ms)
                offset = next_offset
                if offset >= 150:
                    expected = raw_delta if expected is None else expected + 0.25 * (raw_delta - expected)
            checkpoint(label)
            if end == 400:
                clock_ms += 300_000
                ack = self.inject_location(lat, lon, speed_mps=0.0, timestamp_ms=clock_ms)
                checkpoint("03-stopped")
                clock_ms += 60_000
                ack = self.inject_location(lat, lon, speed_mps=10.0, timestamp_ms=clock_ms)
                checkpoint("04-gap-resynced")

    def read_guidance(self) -> dict[str, Any] | None:
        """Reads the debug guidance probe (position source, remaining distance)."""
        try:
            raw = self.raw_device_bytes("exec-out", "run-as", APP_ID, "cat", GUIDANCE_SNAPSHOT, timeout=15)
            return json.loads(raw.decode("utf-8"))
        except (VisualError, UnicodeDecodeError, json.JSONDecodeError):
            return None

    def wait_for_guidance_source(self, source: str, *, timeout: float) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        latest: dict[str, Any] | None = None
        while time.monotonic() < deadline:
            latest = self.read_guidance()
            if latest and latest.get("positionSource") == source:
                return latest
            time.sleep(0.1)
        raise VisualError(f"Guidance never reported position source {source!r}; last probe: {latest}")

    def gps_loss(self, args: argparse.Namespace) -> None:
        """Ride, lose GPS, watch progress continue along the route, then recover.

        Approach fixes keep estimation off so the riding frame is still. The
        last moving fix opts into estimation and injection stops (the debug
        fix authority keeps the emulator's parked provider out), so guidance
        must keep advancing as "estimated" with "No GPS" on the data bar.
        A recovery fix --recover-offset metres from the estimate (positive:
        the rider was quicker) must ease the marker back ("reconciling")
        rather than jump, and the ride settles on live GPS. The HUD's "No GPS"
        badge follows fix arrivals, not estimation: fresh coarse fixes hide
        it without ending the estimate. Finally the rider stops and GPS goes
        quiet: nothing is estimated, but the badge says there is no fix.
        """
        if args.plan_only or args.progress_percent is not None:
            raise VisualError("gps-loss needs a fresh ride; omit --plan-only and --progress.")
        if not 2.0 <= args.speed <= 40.0:
            raise VisualError("--speed must be between 2 and 40 m/s so the rider counts as moving.")
        self.route(args)
        snapshot = self.read_route_snapshot()
        route = snapshot["routes"][snapshot["selectedIndex"]]
        points = route["points"]
        distances, total = cumulative_distance(points)
        if total < 2_500:
            raise VisualError("gps-loss needs a route at least 2.5 km long.")

        offset = 0.0
        while offset < args.approach:
            self.inject_route_fix(points, distances, offset, speed_mps=args.speed)
            offset += 25.0
        self.settle_for_capture(args)
        self.capture_ride_frame(args, "gps-loss-00-riding")

        # The last fix before the tunnel: estimation may start 3 s later.
        lat, lon = point_at_offset(points, distances, offset)
        ack = self.inject_location(lat, lon, speed_mps=args.speed, allow_estimate=True)
        lost_at = time.monotonic()
        live_remaining = float(ack["remainingDistanceM"])
        emit("gps_loss", stage="lost", offset_m=round(offset), remaining_m=round(live_remaining))

        first = self.wait_for_guidance_source("estimated", timeout=8.0)
        self.wait_for_node("description", "GPS signal lost, position estimated", contains=True, timeout=5)
        self.wait_for_node("description", "No GPS fix, position estimated", contains=True, timeout=5)
        self.capture_ride_frame(args, "gps-loss-01-estimating")
        emit("gps_loss", stage="estimating", seconds=round(time.monotonic() - lost_at, 1),
             remaining_m=round(first["remainingDistanceM"]))

        time.sleep(max(0.0, args.gap - (time.monotonic() - lost_at)))
        later = self.wait_for_guidance_source("estimated", timeout=3.0)
        advanced = live_remaining - float(later["remainingDistanceM"])
        if advanced < args.speed * 0.5 * (args.gap - 3.0):
            raise VisualError(f"The estimate only advanced {advanced:.0f} m in {args.gap:.0f} s without GPS.")
        self.capture_ride_frame(args, "gps-loss-02-estimating-later")
        emit("gps_loss", stage="estimating", seconds=round(time.monotonic() - lost_at, 1),
             advanced_m=round(advanced))

        # Android can still supply coarse fallback fixes while guidance keeps
        # the more precise route estimate. The badge must follow those arrivals.
        fallback_at = total - float(later["remainingDistanceM"])
        lat, lon = point_at_offset(points, distances, fallback_at)
        for _ in range(3):
            ack = self.inject_location(
                lat, lon, speed_mps=args.speed, accuracy_m=500.0, allow_estimate=True,
            )
            if ack.get("positionSource") != "estimated":
                raise VisualError(f"A coarse fallback fix ended the estimate: {ack}")
            time.sleep(0.25)
        self.wait_for_no_node("description", "No GPS fix", contains=True, timeout=2.0)
        self.capture_ride_frame(args, "gps-loss-02-fallback-fixes")
        emit("gps_loss", stage="fallback-fixes", source=ack["positionSource"], badge=False)

        # GPS returns. The estimate is total - remaining along this route.
        estimated_at = total - float(self.wait_for_guidance_source("estimated", timeout=3.0)["remainingDistanceM"])
        recovered = min(max(offset + 10.0, estimated_at + args.recover_offset), total - 100.0)
        lat, lon = point_at_offset(points, distances, recovered)
        recovery_at = time.monotonic()
        ack = self.inject_location(lat, lon, speed_mps=args.speed, allow_estimate=True)
        if ack.get("positionSource") != "reconciling":
            raise VisualError(f"GPS recovery did not ease the marker back: {ack}")
        self.capture_ride_frame(args, "gps-loss-03-recovering")
        emit("gps_loss", stage="recovering", estimate_m=round(estimated_at), live_m=round(recovered))

        lat, lon = point_at_offset(points, distances, recovered + args.speed * (time.monotonic() - recovery_at))
        ack = self.inject_location(lat, lon, speed_mps=args.speed, allow_estimate=True)
        if ack.get("positionSource") == "reconciling":
            self.capture_ride_frame(args, "gps-loss-04-recovering-mid")
        else:
            emit("gps_loss", stage="glide-landed-before-mid-frame", source=ack.get("positionSource"))

        # Settle on live GPS with estimation off again, so the frame holds.
        while time.monotonic() - recovery_at < 3.5:
            time.sleep(0.5)
        settled = recovered + args.speed * (time.monotonic() - recovery_at)
        self.inject_route_fix(points, distances, settled, speed_mps=args.speed)
        live = self.wait_for_guidance_source("gps", timeout=5.0)
        self.wait_for_node("text", "km/h", timeout=5)
        self.wait_for_no_node("description", "No GPS fix", contains=True, timeout=5)
        self.settle_for_capture(args)
        self.capture_ride_frame(args, "gps-loss-05-live")
        emit("gps_loss", stage="live", offset_m=round(settled), remaining_m=round(live["remainingDistanceM"]))

        # Pull over, then lose GPS while stopped: never estimated, still told.
        for _ in range(4):
            self.inject_route_fix(points, distances, settled, speed_mps=0.0)
        lat, lon = point_at_offset(points, distances, settled)
        self.inject_location(lat, lon, speed_mps=0.0, allow_estimate=True)
        self.wait_for_node("description", "No GPS fix", timeout=8)
        stopped = self.wait_for_guidance_source("gps", timeout=3.0)
        self.settle_for_capture(args)
        self.capture_ride_frame(args, "gps-loss-06-stopped-no-fix")
        emit("gps_loss", stage="stopped-no-fix", remaining_m=round(stopped["remainingDistanceM"]))

    def navigation_camera(self, args: argparse.Namespace) -> None:
        """Captures the guidance camera and rider-lock states during a ride.

        Repeatable frames cover navigation start, street-close framing, rider
        lock release while fixes advance, relock at the latest fix, one frame
        per speed band, an off-rider inspection with a lock cycle, a heading
        turn, and camera restore after END. The debug camera probe makes each
        state checkable, not only viewable. Queued debug fixes own the guidance
        session while they flow, so the emulator's live provider cannot pull
        the camera away. With `--black-and-white both`, every frame is captured
        in colour and B&W. The street-close and speed-band frames also show the
        raised arrow and its outline against the full-width white routed road.
        """
        self.navigation_camera_steps(args)

    def navigation_camera_steps(self, args: argparse.Namespace) -> None:
        if args.plan_only:
            raise VisualError("nav-camera starts guidance; omit --plan-only.")
        speeds = parse_speed_cases(args.speed_cases)
        run_started_ms = time.time_ns() // 1_000_000
        planning_holder: list[dict[str, Any] | None] = []
        self.route(args, before_ride=lambda: planning_holder.append(self.read_camera()))
        planning_camera = planning_holder[0] if planning_holder else None
        self.capture_ride_frame(args, "nav-camera-00-guidance-start")

        snapshot = self.read_route_snapshot()
        index = int(snapshot.get("selectedIndex", 0))
        route = snapshot["routes"][index]
        points = route["points"]
        cumulative, total_distance = cumulative_distance(points)
        if total_distance <= 0.0:
            raise VisualError("The selected route has no measurable length.")

        # Street-close framing: stationary at the closest band, rider low in
        # the viewport, forward tilt. This is the frame the reference view
        # describes. Ride the first metres rather than teleporting, so the
        # session's forward vertex window always contains the next fix.
        street_offset = min(max(40.0, total_distance * 0.01), total_distance * 0.5)
        street_lat, street_lon = self.inject_route_span(
            points,
            cumulative,
            min(40.0, street_offset),
            street_offset,
            step_m=ROUTE_FOLLOW_STEP_M,
            speed_mps=0.0,
        )
        camera = self.wait_for_camera(
            "street-close framing",
            lambda probe: probe.get("guidance") is True
            and camera_near(probe, street_lat, street_lon)
            and abs(probe_number(probe, "zoom") - guidance_zoom_band(0.0)) <= 0.25
            and abs(probe_number(probe, "tilt") - GUIDANCE_TILT_DEGREES) <= 1.0,
            not_before_ms=run_started_ms - 2_000,
        )
        self.settle_for_capture(args)
        self.capture_ride_frame(args, "nav-camera-01-street-close")
        emit("nav_camera", stage="street-close", camera=camera)

        expected_zoom = guidance_zoom_band(0.0)
        self.tap_node("description", "Zoom out")
        initial_zoomed_out = self.wait_for_camera(
            "ride zoom-out before releasing the rider lock",
            lambda probe: probe.get("guidance") is True
            and camera_near(probe, street_lat, street_lon)
            and probe_number(probe, "zoom") < expected_zoom - 0.5
        )
        self.capture_ride_frame(args, "nav-camera-lock-on-zoomed-out")
        # Switching the ride style restarts the guidance marker collector, which
        # can restore its speed-band zoom after the earlier zoom-out snapshot.
        # Reapply the inspection zoom after the final style capture so the lock
        # release comparison uses a fresh settled native camera snapshot.
        self.tap_node("description", "Zoom out")
        zoomed_out = self.wait_for_camera(
            "ride zoom-out after the final style capture",
            lambda probe: probe.get("guidance") is True
            and probe.get("cameraLocked") is True
            and camera_near(probe, street_lat, street_lon)
            and probe_number(probe, "zoom") < expected_zoom - 0.5
            and probe.get("createdAtMillis", 0) > initial_zoomed_out.get("createdAtMillis", 0),
        )
        self.tap_node("description", "Rider lock on; tap to release")
        self.wait_for_node("description", "Rider lock off; tap to follow", timeout=10)
        unlocked_offset = min(street_offset + 200.0, total_distance * 0.5)
        unlocked_lat, unlocked_lon = self.inject_route_span(
            points,
            cumulative,
            street_offset,
            unlocked_offset,
            step_m=ROUTE_FOLLOW_STEP_M,
            speed_mps=0.0,
        )
        expected_relock_bearing = bearing_degrees(
            point_at_offset(points, cumulative, max(0.0, unlocked_offset - 40.0)),
            point_at_offset(points, cumulative, min(total_distance, unlocked_offset + 40.0)),
        )
        self.settle_for_capture(args)
        camera = self.wait_for_camera(
            "released lock to preserve the inspection camera while fixes advance",
            lambda probe: probe.get("guidance") is True
            and camera_near(probe, street_lat, street_lon)
            and abs(probe_number(probe, "zoom") - probe_number(zoomed_out, "zoom")) <= 0.25
        )
        self.capture_ride_frame(args, "nav-camera-rider-lock-off")
        emit(
            "nav_camera",
            stage="rider-lock-off",
            rider=(unlocked_lat, unlocked_lon),
            camera=camera,
        )

        self.tap_node("description", "Rider lock off; tap to follow")
        camera = self.wait_for_camera(
            "rider lock to restore guidance framing at the latest fix",
            lambda probe: probe.get("guidance") is True
            and camera_near(probe, unlocked_lat, unlocked_lon)
            and abs(probe_number(probe, "zoom") - expected_zoom) <= 0.25
            and abs(probe_number(probe, "tilt") - GUIDANCE_TILT_DEGREES) <= 1.0
            and bearing_distance_degrees(
                probe_number(probe, "bearing"),
                expected_relock_bearing,
            ) <= 8.0,
        )
        self.capture_ride_frame(args, "nav-camera-rider-lock-relocked")
        emit("nav_camera", stage="rider-lock-reenabled", camera=camera)

        self.capture_follow_pause(args, unlocked_lat, unlocked_lon)

        # Use the debug inspection camera to create a repeatable off-rider
        # view, then exercise the same unlock/relock control without relying
        # on emulator-specific synthetic drag dispatch.
        off_rider_lat = unlocked_lat + 0.005
        off_rider_lon = unlocked_lon + 0.005
        self.inspect_camera(
            off_rider_lat,
            off_rider_lon,
            expected_zoom,
            25.0,
            expected_relock_bearing,
        )
        self.capture_ride_frame(args, "nav-camera-lock-on-inspection")
        self.tap_node("description", "Rider lock on; tap to release")
        self.wait_for_node("description", "Rider lock off; tap to follow", timeout=10)
        self.capture_ride_frame(args, "nav-camera-lock-off-inspection")
        self.tap_node("description", "Rider lock off; tap to follow")
        camera = self.wait_for_camera(
            "explicit relock from the off-rider inspection view",
            lambda probe: probe.get("guidance") is True
            and camera_near(probe, unlocked_lat, unlocked_lon)
            and abs(probe_number(probe, "zoom") - expected_zoom) <= 0.25
            and abs(probe_number(probe, "tilt") - GUIDANCE_TILT_DEGREES) <= 1.0,
        )
        self.capture_ride_frame(args, "nav-camera-recenter-restored")
        emit("nav_camera", stage="explicit-relock", camera=camera)

        # One frame per speed band; the rider keeps moving along the route so
        # the camera also has to re-centre, not just re-zoom.
        speed_offset = min(max(street_offset + 150.0, total_distance * 0.03), total_distance * 0.5)
        zoom_step = max(120.0, total_distance * 0.01)
        for speed in speeds:
            expected_zoom = guidance_zoom_band(speed)
            next_offset = min(speed_offset + zoom_step, total_distance * 0.8)
            fix_lat, fix_lon = self.inject_route_span(
                points,
                cumulative,
                speed_offset,
                next_offset,
                step_m=ROUTE_FOLLOW_STEP_M,
                speed_mps=speed,
            )
            camera = self.wait_for_camera(
                f"zoom band {expected_zoom:g} at {speed:g} m/s",
                lambda probe, expected_zoom=expected_zoom, lat=fix_lat, lon=fix_lon: probe.get("guidance") is True
                and camera_near(probe, lat, lon)
                and abs(probe_number(probe, "zoom") - expected_zoom) <= 0.25,
            )
            self.settle_for_capture(args)
            self.capture_ride_frame(args, f"nav-camera-02-speed-{speed:g}")
            emit(
                "nav_camera",
                stage="speed-band",
                speed_mps=speed,
                expected_zoom=expected_zoom,
                camera=camera,
            )
            speed_offset = next_offset

        # Heading turn: ride a straight approach, then through a real corner,
        # so the heading-up camera and the rider chevron both rotate.
        turn = find_route_turn(
            points,
            cumulative,
            threshold_deg=args.turn_threshold,
            min_offset_m=max(speed_offset + args.turn_step, total_distance * 0.05),
            max_offset_m=total_distance * 0.95,
        )
        if turn is None:
            emit("nav_camera", stage="heading-turn", status="no-turn-found")
            self.capture_ride_frame(args, "nav-camera-03-straight-heading")
        else:
            turn_offset, incoming, outgoing = turn
            approach_end = max(0.0, turn_offset - 25.0)
            approach_lat, approach_lon = self.inject_route_span(
                points,
                cumulative,
                max(speed_offset, max(0.0, turn_offset - 200.0)),
                approach_end,
                step_m=args.turn_step,
                speed_mps=args.turn_speed,
            )
            camera = self.wait_for_camera(
                f"turn approach heading (want bearing {incoming:.1f}±30 near "
                f"{approach_lat:.6f},{approach_lon:.6f}; turn at {turn_offset:.0f} m, outgoing {outgoing:.1f})",
                lambda probe, lat=approach_lat, lon=approach_lon: probe.get("guidance") is True
                and camera_near(probe, lat, lon)
                and bearing_distance_degrees(probe_number(probe, "bearing"), incoming) <= 30.0,
            )
            self.settle_for_capture(args)
            self.capture_ride_frame(args, "nav-camera-03-turn-approach")
            emit("nav_camera", stage="turn-approach", camera=camera, expected_bearing=incoming)

            self.inject_route_fix(
                points, cumulative, turn_offset + args.turn_step * 0.5, speed_mps=args.turn_speed
            )
            self.settle_for_capture(args)
            self.capture_ride_frame(args, "nav-camera-04-turn-mid")

            exit_end = turn_offset + args.turn_step * 5.0
            exit_lat, exit_lon = self.inject_route_span(
                points,
                cumulative,
                turn_offset + args.turn_step,
                exit_end,
                step_m=args.turn_step,
                speed_mps=args.turn_speed,
            )
            camera = self.wait_for_camera(
                "post-turn heading",
                lambda probe, lat=exit_lat, lon=exit_lon: probe.get("guidance") is True
                and camera_near(probe, lat, lon)
                and bearing_distance_degrees(probe_number(probe, "bearing"), outgoing) <= 35.0,
            )
            self.settle_for_capture(args)
            self.capture_ride_frame(args, "nav-camera-05-turn-exited")
            observed_delta = abs(
                bearing_delta_degrees(incoming, probe_number(camera, "bearing"))
            )
            expected_delta = abs(bearing_delta_degrees(incoming, outgoing))
            emit(
                "nav_camera",
                stage="turn-exited",
                camera=camera,
                observed_delta_deg=round(observed_delta, 1),
                expected_delta_deg=round(expected_delta, 1),
            )
            if observed_delta < max(10.0, expected_delta / 3.0):
                raise VisualError(
                    "The guidance camera did not follow the route heading through the turn "
                    f"(observed {observed_delta:.1f} deg of an expected {expected_delta:.1f} deg)."
                )

        # Exit restores the planning camera: no tilt, no heading lock.
        self.tap_node("text", "End")
        self.wait_for_node("text", "START", timeout=20)
        camera = self.wait_for_camera(
            "planning camera restore",
            lambda probe: probe.get("guidance") is not True
            and abs(probe_number(probe, "tilt")) <= 1.0,
            timeout=15.0,
        )
        self.settle_for_capture(args)
        self.capture("nav-camera-06-exit-restored-planning")
        emit("nav_camera", stage="exit-restore", camera=camera, planning_camera=planning_camera)
        if planning_camera is not None:
            zoom_drift = abs(probe_number(camera, "zoom") - probe_number(planning_camera, "zoom"))
            bearing_drift = bearing_distance_degrees(
                probe_number(camera, "bearing"), probe_number(planning_camera, "bearing")
            )
            if zoom_drift > 1.5 or (math.isfinite(bearing_drift) and bearing_drift > 15.0):
                raise VisualError(
                    "Exit did not restore the planning camera: "
                    f"zoom drift {zoom_drift:.2f}, bearing drift {bearing_drift:.2f}."
                )

    def lane_guidance(self, args: argparse.Namespace) -> None:
        """Rides the route and captures each maneuver that shows lane guidance.

        The guidance card exposes the lane strip as a "Lane guidance: ..."
        content description. Fixes advance in --step metres; each time a new
        lane recommendation appears, an approach frame is captured, then one
        more after riding past that maneuver so the strip's hand-over to the
        next turn is visible. Fails when the ride ends without any lane
        guidance, which catches a graph imported without lane data.
        """
        if args.plan_only:
            raise VisualError("lanes starts guidance; omit --plan-only.")
        self.route(args)
        self.capture_ride_frame(args, "lanes-00-guidance-start")
        snapshot = self.read_route_snapshot()
        route = snapshot["routes"][int(snapshot.get("selectedIndex", 0))]
        points = route["points"]
        cumulative, total_distance = cumulative_distance(points)
        seen: list[str] = []
        pending_exit: str | None = None
        offset = 0.0
        while offset < total_distance and len(seen) < args.max_captures:
            offset = min(total_distance, offset + args.step)
            self.inject_route_fix(points, cumulative, offset, speed_mps=args.speed)
            nodes = self.matching_nodes("description", "Lane guidance:", contains=True)
            guidance = self.matching_nodes("description", "Navigation guidance:", contains=True)
            card = guidance[0]["description"] if guidance else ""
            lane = nodes[0]["description"] if nodes else ""
            if pending_exit is not None and card != pending_exit:
                self.settle_for_capture(args)
                self.capture_ride_frame(args, f"lanes-{len(seen):02d}-after")
                emit("lanes", stage="after", offset_m=round(offset), card=card, lanes=lane or None)
                pending_exit = None
            if lane and f"{card}|{lane}" not in seen:
                seen.append(f"{card}|{lane}")
                self.settle_for_capture(args)
                self.capture_ride_frame(args, f"lanes-{len(seen):02d}-approach")
                emit("lanes", stage="approach", offset_m=round(offset), card=card, lanes=lane)
                pending_exit = card
        emit("lanes", stage="done", captured=len(seen), ridden_m=round(offset), total_m=round(total_distance))
        if not seen:
            raise VisualError(
                "No lane guidance appeared on this ride. Check that the graph was imported with "
                "tools/gh/MotoGraphImport.java (preflight checks for moto_lanes)."
            )

    def app_logs(self, *, lines: int) -> str:
        result = self.device_command(
            "logcat", "-d", "-t", str(lines),
            "-s", "CurveMaps.RouteScreen:I", "CurveMaps.Router:I",
            "CurveMaps.SearchField:D", "CurveMaps.GeocodeCtrl:I", "*:S",
            check=False,
            timeout=30,
        )
        return result.stdout.strip()

    def ui_size_apks(self, *, build: bool, test_apk: str | None) -> tuple[Path, Path]:
        """Resolves (or builds) the app APK and the instrumented (androidTest) APK.

        --no-build skips every Gradle invocation and fails actionably instead;
        a frozen pair from gallery.py keeps every shard on the same build.
        """
        app_apk = self.apk_override or (self.root / UI_SIZE_APP_APK)
        instrumented_apk = Path(test_apk).expanduser() if test_apk else (self.root / UI_SIZE_TEST_APK)
        if not instrumented_apk.is_absolute():
            instrumented_apk = self.root / instrumented_apk
        if build:
            targets = [":app:assembleDebugAndroidTest"]
            if self.apk_override is None:
                targets.insert(0, ":app:assembleDebug")
            env = os.environ.copy()
            env.setdefault("JAVA_HOME", "/opt/homebrew/opt/openjdk@17")
            gradle = subprocess.run(
                ["./gradlew", *targets, *self.extra_gradle_args],
                cwd=self.root,
                env=env,
                text=True,
            )
            if gradle.returncode != 0:
                raise VisualError(f"Gradle build failed with exit code {gradle.returncode}.")
        for path, kind, fix in (
            (app_apk, "app", "Drop --no-build to build it."),
            (instrumented_apk, "androidTest", "Build :app:assembleDebugAndroidTest, drop --no-build, or pass --test-apk."),
        ):
            if not path.is_file():
                raise VisualError(f"The {kind} APK is missing at {path}. {fix}")
        return app_apk, instrumented_apk

    def install_ui_size_apks(self, app_apk: Path, instrumented_apk: Path) -> None:
        if self.installed_apk_matches(app_apk):
            emit("apk_reused", path=str(app_apk))
        else:
            self.device_command("install", "-r", "-t", str(app_apk), timeout=240)
        self.device_command("install", "-r", "-t", str(instrumented_apk), timeout=240)
        emit("instrumented_apk_installed", path=str(instrumented_apk))

    def ui_size_component(self) -> str:
        listing = self.device_command("shell", "pm", "list", "instrumentation", check=False).stdout
        component = ui_size_instrumentation_component(listing)
        if component is None:
            raise VisualError(
                f"No instrumentation for {APP_ID} is installed after installing the androidTest APK. "
                f"Expected {UI_SIZE_TEST_PACKAGE}/{UI_SIZE_RUNNER}."
            )
        return component

    def ui_sizes(self, args: argparse.Namespace) -> None:
        """Runs the CommonScreenSizeTest matrix and adopts its exported frames.

        The Compose suite owns the windows, font scales, and rendering; this
        runner owns build/install/instrumentation and turns the suite's cache
        exports into harness frames. Frames are imported before any failure is
        raised, so a failing configuration still has screenshot evidence, and
        the matrix JSON stays the only source of counts.
        """
        matrix = load_ui_size_matrix(self.root)
        if not matrix:
            raise VisualError(f"The UI size matrix is missing or empty: {self.root / UI_SIZE_MATRIX}")
        cases = parse_ui_size_cases(args.cases, matrix) if args.cases else None
        font_scales = (
            parse_ui_size_font_scales(args.font_scales, matrix) if args.font_scales else None
        )
        expected = ui_size_expected_frames(cases, font_scales, matrix)
        self.ensure_device(start_if_missing=True)
        app_apk, instrumented_apk = self.ui_size_apks(build=not args.no_build, test_apk=args.test_apk)
        self.install_ui_size_apks(app_apk, instrumented_apk)
        component = self.ui_size_component()

        self.ensure_capture_dirs()
        # Export before assertions and collect before anything uninstalls the app.
        self.device_command("shell", "run-as", APP_ID, "rm", "-rf", UI_SIZE_CAPTURE_DIR, check=False)
        self.device_command("logcat", "-c", check=False)
        instrument = [
            "shell", "am", "instrument", "-w",
            "-e", "class", UI_SIZE_TEST_CLASS,
            "-e", "uiSizeCaptures", "true",
        ]
        if cases:
            instrument += ["-e", "uiSizeCases", ",".join(cases)]
        if font_scales:
            instrument += ["-e", "uiSizeFontScales", ",".join(font_scales)]
        instrument.append(component)

        completed: subprocess.CompletedProcess[str] | None = None
        timed_out = False
        output = ""
        try:
            completed = self.device_command(*instrument, check=False, timeout=args.timeout)
            output = completed.stdout + completed.stderr
        except subprocess.TimeoutExpired as error:
            timed_out = True
            self.device_command("shell", "am", "force-stop", APP_ID, check=False)
            parts = [value for value in (error.stdout, error.stderr) if value]
            output = "\n".join(
                part if isinstance(part, str) else part.decode(errors="replace") for part in parts
            )
        log_path = self.run_dir / "instrumentation.log"
        log_path.write_text(output + ("\n[timed out]\n" if timed_out else ""), encoding="utf-8")
        emit("instrumentation", component=component, timed_out=timed_out, log=str(log_path))

        logcat = self.device_command(
            "logcat", "-d", "-v", "brief", "-s", UI_SIZE_LOG_TAG, check=False, timeout=60
        )
        log_records = ui_size_records_from_logs(logcat.stdout.splitlines())

        listing_text = self.device_command(
            "shell", "run-as", APP_ID, "find", UI_SIZE_CAPTURE_DIR, "-type", "f", check=False, timeout=60
        ).stdout
        listing = [line.strip() for line in listing_text.splitlines() if line.strip()]
        payloads: list[dict[str, Any]] = []
        problems: list[str] = []
        for path in sorted(entry for entry in listing if entry.endswith(".json")):
            raw = self.device_command("exec-out", "run-as", APP_ID, "cat", path, check=False, timeout=60)
            if raw.returncode != 0:
                problems.append(f"could not read {path}")
                continue
            try:
                payloads.append(json.loads(raw.stdout))
            except json.JSONDecodeError:
                problems.append(f"{path}: invalid capture JSON")
        records, record_problems = ui_size_capture_records(payloads, listing)
        problems += record_problems
        if not records and log_records:
            records, fallback_problems = ui_size_capture_records(log_records, listing)
            problems += [f"Log.i fallback: {problem}" for problem in fallback_problems]
            if records:
                problems.append("no capture JSON was exported; imported from Log.i metadata")

        frames: list[dict[str, Any]] = []
        for record in records:
            try:
                png = self.raw_device_bytes(
                    "exec-out", "run-as", APP_ID, "cat", record["devicePath"], timeout=120
                )
            except VisualError as error:
                problems.append(f"{record['label']}: {error}")
                continue
            if not png.startswith(b"\x89PNG\r\n\x1a\n"):
                problems.append(f"{record['label']}: {record['devicePath']} is not a PNG")
                continue
            path = self.adopt_frame(record["label"], png, metadata={
                "surface": record.get("surface"),
                "widthDp": record.get("widthDp"),
                "heightDp": record.get("heightDp"),
                "fontScale": record.get("fontScale"),
                "devicePath": record.get("devicePath"),
            })
            frames.append({
                "label": record["label"],
                "surface": record.get("surface"),
                "widthDp": record.get("widthDp"),
                "heightDp": record.get("heightDp"),
                "fontScale": record.get("fontScale"),
                "devicePath": record.get("devicePath"),
                "path": str(path),
                "sha256": hashlib.sha256(png).hexdigest(),
            })

        surfaces = {surface: sum(1 for frame in frames if frame["surface"] == surface)
                    for surface in UI_SIZE_SURFACES}
        manifest = {
            "command": "ui-sizes",
            "testClass": UI_SIZE_TEST_CLASS,
            "matrix": str(UI_SIZE_MATRIX),
            "cases": cases,
            "fontScales": font_scales,
            "expectedFrames": expected,
            "importedFrames": len(frames),
            "surfaces": surfaces,
            "logRecords": len(log_records),
            "problems": problems,
            "frames": frames,
        }
        (self.run_dir / "ui-sizes.json").write_text(
            json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )
        emit("ui_size_summary", run_dir=str(self.run_dir), imported=len(frames),
             expected=expected, surfaces=surfaces, problems=problems[:10])

        failure = ui_size_failure_summary(output) if completed is not None else None
        if failure is None and timed_out:
            failure = f"instrumentation timed out after {args.timeout:g}s"
        elif failure is None and completed is not None and completed.returncode != 0:
            failure = f"adb am instrument exited {completed.returncode}"
        if failure is None and problems:
            failure = "invalid screenshot exports: " + "; ".join(problems[:3])
        if failure is None and expected is not None and len(frames) != expected:
            failure = f"imported {len(frames)} frames, expected {expected}"
        if failure is None and not frames:
            failure = "the suite exported no frames"
        if failure is not None:
            # Record the run directory even with zero frames, so gallery.py can
            # still report the failure against this run.
            emit("complete", run_dir=str(self.run_dir),
                 latest=str(self.output_dir / "latest.png"), failed=True)
            raise VisualError(f"{failure}. See {self.run_dir / 'ui-sizes.json'}")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def perf_pan_metres(zoom: float) -> float:
    """Roughly one screen width of motion at [zoom], so every case sweeps the
    same amount of visible map instead of a few pixels at z14."""
    return 300.0 * 2.0 ** (18.0 - zoom)


def perf_zoom_plan(targets: list[str], zooms_argument: str) -> dict[str, list[float]]:
    """Parses the perf --zooms grammar into a zoom list per target.

    Comma-separated items are either a default zoom that applies to every
    target (`18`) or a target-specific zoom (`houses=18.5`). A target with at
    least one target-specific zoom uses only those; every other target uses the
    default zooms. This is what lets one run cover the acceptance matrix
    (`cbd=14,cbd=16,cbd=18,houses=18.5`) without re-running the command and
    hand-merging reports.
    """
    defaults: list[float] = []
    overrides: dict[str, list[float]] = {}
    for raw in zooms_argument.split(","):
        item = raw.strip()
        if not item:
            continue
        target: str | None = None
        value = item
        if "=" in item:
            head, _, tail = item.partition("=")
            target = head.strip()
            value = tail.strip()
            if target not in targets:
                raise VisualError(
                    f"Zoom override '{item}' names target '{target}', which --targets did not select."
                )
        try:
            zoom = float(value)
        except ValueError as error:
            raise VisualError(f"Invalid perf zoom '{item}'.") from error
        if not 0.0 <= zoom <= 22.0:
            raise VisualError(f"Perf zoom '{item}' is outside the supported 0..22 range.")
        if target is None:
            defaults.append(zoom)
        else:
            overrides.setdefault(target, []).append(zoom)
    plan = {target: list(overrides.get(target, defaults)) for target in targets}
    missing = [target for target, values in plan.items() if not values]
    if missing:
        raise VisualError(
            "No zoom selected for perf target(s): "
            + ", ".join(missing)
            + ". Pass default zooms or `target=zoom` overrides."
        )
    return plan


def perf_sweep_plan(
    *,
    request_id: int,
    label: str,
    motion: str,
    lat: float,
    lon: float,
    zoom: float,
    tilt: float,
    duration_ms: int,
    building_mode: str,
    settle: bool,
) -> dict[str, Any]:
    """Builds the JSON plan the debug receiver queues; mirrors MapPerfSweepRequest."""
    pan_m = perf_pan_metres(zoom)
    d_lat = (pan_m * math.cos(math.radians(45.0))) / 111_320.0
    d_lon = (pan_m * math.sin(math.radians(45.0))) / (
        111_320.0 * math.cos(math.radians(lat))
    )
    return {
        "requestId": request_id,
        "label": label,
        "motion": motion,
        "lat": lat,
        "lon": lon,
        "latEnd": lat + d_lat,
        "lonEnd": lon + d_lon,
        "zoomStart": zoom,
        "zoomEnd": min(zoom + 1.0, 18.5),
        "tilt": tilt,
        "bearingStart": 0.0,
        "bearingEnd": 90.0,
        "durationMs": duration_ms,
        "settle": settle,
        "buildingMode": building_mode,
    }


def perf_segment(result: dict[str, Any], name: str = "all") -> dict[str, Any]:
    segments = result.get("segments") or {}
    segment = segments.get(name)
    return segment if isinstance(segment, dict) else {}


def perf_number(source: dict[str, Any], key: str) -> float:
    value = source.get(key)
    return float(value) if isinstance(value, (int, float)) else 0.0


def perf_primary_fps(result: dict[str, Any]) -> float:
    """The primary cadence evidence for one sweep.

    `nativeFps.harmonic` comes from MapLibre's per-render-thread frame listener
    (`1e9/delta` per frame, elapsed-average recovered by the app, first
    pre-sweep idle interval discarded). The finish-callback timestamp series is
    the secondary series: it is taken inside the app's callback, so anything
    that coalesces or delays that callback would bias it.
    """
    native = result.get("nativeFps") or {}
    harmonic = perf_number(native, "harmonic")
    if harmonic > 0.0:
        return harmonic
    return perf_number(perf_segment(result), "fps")


def perf_merge(args: argparse.Namespace) -> None:
    """Merges `perf` run summaries into one labelled before/after report."""
    labels = [item.strip() for item in args.labels.split(",") if item.strip()]
    summaries: list[dict[str, Any]] = []
    for path in args.summaries:
        summary_path = Path(path).expanduser()
        if not summary_path.is_absolute():
            summary_path = Path.cwd() / summary_path
        summaries.append(json.loads(summary_path.read_text(encoding="utf-8")))
    if not summaries:
        raise VisualError("perf-merge needs at least one perf-summary.json.")
    if labels and len(labels) != len(summaries):
        raise VisualError(
            f"perf-merge got {len(labels)} labels for {len(summaries)} summaries."
        )
    results: list[dict[str, Any]] = []
    device_gpu: dict[str, str] = {}
    for index, summary in enumerate(summaries):
        label = labels[index] if labels else f"run{index + 1}"
        for result in summary.get("sweeps") or []:
            labelled = dict(result)
            labelled["case"] = f"{label}/{result.get('case', '?')}"
            results.append(labelled)
        gpu = summary.get("deviceGpu")
        if isinstance(gpu, dict):
            device_gpu.update(gpu)
    first = summaries[0]
    report = perf_report_markdown(
        results,
        device=f"{first.get('serial', '?')} ({first.get('avd', '?')})",
        generated=utc_now(),
        app_build="debug sweeps (per-row backend identity below)",
        archive_bytes=first.get("archiveBytes"),
        device_gpu=device_gpu or None,
    )
    report_path = Path(args.report).expanduser()
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report, encoding="utf-8")
    emit("perf_report", report=str(report_path), summaries=len(summaries), sweeps=len(results))


def perf_backend_label(result: dict[str, Any]) -> str:
    """One sweep's measured backend, from `RenderingEngine.getCurrentType()`.

    The renderer value is read at runtime by the app and is the authoritative
    bit: the single-backend MapLibre artifacts reject the other backend, so
    `vulkan` can only come from the Vulkan artifact and `opengl` only from the
    OpenGL one. The artifact's `BuildConfig` flavor is reported in the per-sweep
    JSON as extra context but is deliberately not used in this header: those
    constants are compile-time inlined, so an incrementally built APK can carry
    a stale value even when the runtime backend is correct.
    """
    backend = result.get("backend")
    if not isinstance(backend, dict):
        return "not reported"
    renderer = str(backend.get("renderer", "unknown"))
    build_type = backend.get("appBuildType")
    return f"{renderer} ({build_type})" if build_type else renderer


def perf_report_markdown(
    results: list[dict[str, Any]],
    *,
    device: str,
    generated: str,
    app_build: str,
    archive_bytes: int | None,
    device_gpu: dict[str, str] | None = None,
) -> str:
    """Human-readable evidence for the numeric benchmark, one table per view."""
    backends = sorted({perf_backend_label(result) for result in results})
    lines: list[str] = [
        "# Brisbane map performance report",
        "",
        f"- Generated: {generated}",
        f"- Device: {device}",
        f"- App build: {app_build}",
        (
            f"- Installed basemap: {archive_bytes} bytes"
            if archive_bytes
            else "- Installed basemap: not reported"
        ),
        (
            "- Rendering backend (measured in-process): " + "; ".join(backends)
            if backends
            else "- Rendering backend: not reported"
        ),
    ]
    if device_gpu:
        vulkan = device_gpu.get("vulkanDeviceName")
        gles = device_gpu.get("glesRenderer")
        if vulkan:
            lines.append(f"- Vulkan device (`cmd gpu vkjson`): {vulkan}")
        if gles:
            lines.append(f"- GLES renderer (`dumpsys SurfaceFlinger`): {gles}")
    lines += [
        f"- Sweeps: {len(results)}",
        "",
        "Frame intervals are wall-clock deltas between native MapLibre",
        "`OnDidFinishRenderingFrame` callbacks, i.e. the renderer's own frame",
        "cadence. `fps` is the primary cadence: `nativeFps.harmonic`, the",
        "elapsed average recovered from MapLibre's per-render-thread frame",
        "listener (first pre-sweep idle interval discarded). `elapsed fps` is",
        "the secondary finish-callback series `(frames-1)*1000/durationMs`;",
        "the in-app callback can be coalesced, so it never overrides the native",
        "series. `enc`/`render` are",
        "MapLibre `RenderingStats` times converted from seconds to ms; on",
        "Vulkan `enc` includes `Context::beginFrame` fence waits, so it is",
        "encode+GPU-wait, not pure CPU encode time. `draws` is the mean",
        "per-frame `numDrawCalls`; `upload KB/f` is the mean per-frame delta of",
        "the cumulative `bufferUpdateBytes` counter (never its running total).",
        "`gpu`/`total` are per-frame durations from the activity window's",
        "`Window.FrameMetrics` (`GPU_DURATION`/`TOTAL_DURATION`); a",
        "SurfaceView-backed MapView renders on its own GL surface, so those",
        "columns describe the window, not map presentation. Each sweep's",
        "`backend.renderer` is read at runtime via",
        "`RenderingEngine.getCurrentType()`, which the single-backend MapLibre",
        "artifacts make authoritative; the per-sweep JSON `maplibreFlavor` is",
        "informational (compile-time-inlined constants can go stale after an",
        "ABI-identical artifact swap).",
        "",
        "## Sweeps",
        "",
        "| case | mode | phase | fps | elapsed fps | p50 ms | p95 ms | p99 ms | >50 ms | >100 ms | enc mean | render mean | draws | upload KB/f | gpu p95 | total p95 | frames |",
        "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for result in results:
        all_stats = perf_segment(result)
        presentation = result.get("windowFrameMetrics") or {}
        gpu = presentation.get("gpu") if isinstance(presentation.get("gpu"), dict) else {}
        total = presentation.get("total") if isinstance(presentation.get("total"), dict) else {}
        counters = result.get("nativeRenderStats") or {}
        lines.append(
            "| {case} | {mode} | {phase} | {fps:.1f} | {elapsed:.1f} | {p50:.1f} | {p95:.1f} | {p99:.1f} | {s50} | {s100} | "
            "{enc:.1f} | {render:.1f} | {draws:.0f} | {upload:.0f} | {gpu:.1f} | {total:.1f} | {frames} |".format(
                case=result.get("case", "?"),
                mode=result.get("buildingMode", "?"),
                phase=result.get("phase", "?"),
                fps=perf_primary_fps(result),
                elapsed=perf_number(all_stats, "fps"),
                p50=perf_number(all_stats, "p50Ms"),
                p95=perf_number(all_stats, "p95Ms"),
                p99=perf_number(all_stats, "p99Ms"),
                s50=int(perf_number(all_stats, "stalls50")),
                s100=int(perf_number(all_stats, "stalls100")),
                enc=perf_number(all_stats, "encodingMeanMs"),
                render=perf_number(all_stats, "renderingMeanMs"),
                draws=perf_number(counters, "drawCallsMean"),
                upload=perf_number(counters, "bufferUpdateBytesPerFrameMean") / 1024.0,
                gpu=perf_number(gpu, "p95Ms"),
                total=perf_number(total, "p95Ms"),
                frames=int(perf_number(all_stats, "frames")),
            )
        )
    warm = [result for result in results if result.get("phase") == "warm"]
    if warm:
        lines += [
            "",
            "## Warmed motion (grouped by case and mode)",
            "",
            "| case | mode | mean fps | mean p95 ms | mean p99 ms | total >100 ms |",
            "| --- | --- | ---: | ---: | ---: | ---: |",
        ]
        groups: dict[tuple[str, str], list[dict[str, Any]]] = {}
        for result in warm:
            groups.setdefault((result.get("case", "?"), result.get("buildingMode", "?")), []).append(result)
        for (case, mode), group in sorted(groups.items()):
            count = len(group)
            fps = sum(perf_primary_fps(item) for item in group) / count
            p95 = sum(perf_number(perf_segment(item), "p95Ms") for item in group) / count
            p99 = sum(perf_number(perf_segment(item), "p99Ms") for item in group) / count
            stalls = sum(int(perf_number(perf_segment(item), "stalls100")) for item in group)
            lines.append(
                f"| {case} | {mode} | {fps:.1f} | {p95:.1f} | {p99:.1f} | {stalls} |"
            )
    # Explicit before/after comparison for merged runs labelled
    # `<before|after>-<variant>` (for example `before-opengl`/`after-opengl`).
    # Only valid warm sweeps count: a settle timeout or an interrupted leg is
    # never a measured improvement, and variants are never averaged together.
    grouped: dict[tuple[str, str, str], list[dict[str, Any]]] = {}
    for result in warm:
        if result.get("interrupted") or result.get("cancelled") or result.get("settleTimedOut"):
            continue
        case = str(result.get("case", "?"))
        label, _, suffix = case.partition("/")
        side, _, variant = label.partition("-")
        if side not in ("before", "after") or not variant:
            continue
        grouped.setdefault((variant, suffix, side), []).append(result)
    pairs = sorted(
        {
            (variant, suffix)
            for (variant, suffix, side) in grouped
            if (variant, suffix, "before") in grouped and (variant, suffix, "after") in grouped
        }
    )
    if pairs:
        lines += [
            "",
            "## Valid warm before/after (primary cadence)",
            "",
            "Mean `nativeFps.harmonic` over the valid warmed sweeps of each merged",
            "run, paired per backend variant; sweeps with a settle timeout,",
            "interruption or cancellation are excluded from both sides.",
            "",
            "| variant | case | before fps | after fps | factor | before p95 ms | after p95 ms |",
            "| --- | --- | ---: | ---: | ---: | ---: | ---: |",
        ]
        for variant, suffix in pairs:
            before = grouped[(variant, suffix, "before")]
            after = grouped[(variant, suffix, "after")]
            before_fps = sum(perf_primary_fps(item) for item in before) / len(before)
            after_fps = sum(perf_primary_fps(item) for item in after) / len(after)
            before_p95 = sum(perf_number(perf_segment(item), "p95Ms") for item in before) / len(before)
            after_p95 = sum(perf_number(perf_segment(item), "p95Ms") for item in after) / len(after)
            factor = after_fps / before_fps if before_fps > 0.0 else 0.0
            lines.append(
                f"| {variant} | {suffix} | {before_fps:.1f} | {after_fps:.1f} | {factor:.2f}x | "
                f"{before_p95:.1f} | {after_p95:.1f} |"
            )
    cold = [result for result in results if result.get("phase") == "cold"]
    if cold:
        lines += [
            "",
            "## Cold loading (fresh process, tiles still streaming)",
            "",
            "| case | mode | fps | p50 ms | p95 ms | >100 ms | cold frames | warm frames |",
            "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
        ]
        for result in cold:
            all_stats = perf_segment(result)
            segments = result.get("segments") or {}
            lines.append(
                "| {case} | {mode} | {fps:.1f} | {p50:.1f} | {p95:.1f} | {s100} | {cold} | {warm} |".format(
                    case=result.get("case", "?"),
                    mode=result.get("buildingMode", "?"),
                    fps=perf_number(all_stats, "fps"),
                    p50=perf_number(all_stats, "p50Ms"),
                    p95=perf_number(all_stats, "p95Ms"),
                    s100=int(perf_number(all_stats, "stalls100")),
                    cold=int(segments.get("coldFrames", 0) or 0),
                    warm=int(segments.get("warmFrames", 0) or 0),
                )
            )
    lines += [
        "",
        "## Acceptance",
        "",
        "Targets: ~60 fps, warm p95 <= 33 ms, no warm >100 ms stalls. A warm",
        "sweep whose legs were interrupted/cancelled or whose settle window",
        "timed out is INVALID, never folded into a pass. Emulator numbers below",
        "the target are reported as measured; the benchmark is never loosened to",
        "make a case pass.",
        "",
    ]
    for result in results:
        if result.get("phase") != "warm":
            continue
        all_stats = perf_segment(result)
        fps = perf_primary_fps(result)
        p95 = perf_number(all_stats, "p95Ms")
        stalls = int(perf_number(all_stats, "stalls100"))
        interrupted = bool(result.get("interrupted")) or bool(result.get("cancelled"))
        settle_timed_out = bool(result.get("settleTimedOut"))
        if interrupted or settle_timed_out:
            verdict = "INVALID"
        else:
            verdict = "PASS" if fps >= 55.0 and p95 <= 33.0 and stalls == 0 else "MISS"
        detail = f"{fps:.1f} fps, p95 {p95:.1f} ms, {stalls} stalls >100 ms"
        if interrupted:
            detail += "; interrupted leg"
        if settle_timed_out:
            detail += "; settle window timed out (not a verified warmed run)"
        lines.append(
            f"- {result.get('case', '?')} / {result.get('buildingMode', '?')}: "
            f"{verdict} ({detail})"
        )
    lines.append("")
    return "\n".join(lines)


def twistiest_stretch(points: list[list[float]], half_window_m: float = 600.0) -> tuple[int, int]:
    """(where the twistiest stretch begins, its twistiest point) as indices into [lat, lon] points.

    Mirrors the planner's curve shading closely enough to frame it: the router's
    corner bands (radius under 30/60/100/175 m) graded per segment, averaged
    over half_window_m either side. The beginning is the last point before the
    peak whose average is under the app's CURVE_SHADE_START (0.2): there the
    colour has only just begun to ease in.
    """
    cumulative, _ = cumulative_distance(points)
    lengths = [b - a for a, b in zip(cumulative, cumulative[1:])]

    def corner(index: int) -> float:
        a, b = lengths[index - 1], lengths[index]
        if a < 5.0 or b < 5.0:
            return 0.0
        (lat1, lon1), (lat3, lon3) = points[index - 1], points[index + 1]
        scale = math.cos(math.radians((lat1 + lat3) / 2.0))
        chord = math.hypot((lon3 - lon1) * scale, lat3 - lat1) * 111_195.0
        area = (a + b + chord) * (b + chord - a) * (chord + a - b) * (a + b - chord)
        radius = a * b * chord / math.sqrt(area) if chord > 0.0 and area > 1e-6 else math.inf
        for limit, weight in ((30.0, 1.0), (60.0, 0.8), (100.0, 0.65), (175.0, 0.5)):
            if radius < limit:
                return weight
        return 0.0

    corners = [0.0] + [corner(index) for index in range(1, len(points) - 1)] + [0.0]
    weights = [max(corners[index], corners[index + 1]) for index in range(len(lengths))]
    mids = [(cumulative[index] + cumulative[index + 1]) / 2.0 for index in range(len(lengths))]
    averaged = []
    low = high = 0
    curvy = window = 0.0
    for mid in mids:
        while high < len(mids) and mids[high] <= mid + half_window_m:
            curvy += lengths[high] * weights[high]
            window += lengths[high]
            high += 1
        while mids[low] < mid - half_window_m:
            curvy -= lengths[low] * weights[low]
            window -= lengths[low]
            low += 1
        averaged.append(curvy / window if window > 0.0 else 0.0)
    peak = max(range(len(averaged)), key=averaged.__getitem__)
    begins = peak
    while begins > 0 and averaged[begins] >= 0.2:
        begins -= 1
    return begins, peak + 1


def cumulative_distance(points: list[list[float]]) -> tuple[list[float], float]:
    if len(points) < 2:
        raise VisualError("The selected route has fewer than two geometry points.")
    cumulative = [0.0]
    for first, second in zip(points, points[1:]):
        lat1, lon1 = map(math.radians, first)
        lat2, lon2 = map(math.radians, second)
        dlat = lat2 - lat1
        dlon = lon2 - lon1
        hav = (
            math.sin(dlat / 2) ** 2
            + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
        )
        segment_m = 2 * 6_371_008.8 * math.asin(min(1.0, math.sqrt(hav)))
        cumulative.append(cumulative[-1] + segment_m)
    return cumulative, cumulative[-1]


def point_at_offset(
    points: list[list[float]],
    cumulative: list[float],
    offset_m: float,
) -> tuple[float, float]:
    target = max(0.0, min(cumulative[-1], offset_m))
    index = min(len(points) - 2, max(0, bisect.bisect_right(cumulative, target) - 1))
    segment_m = cumulative[index + 1] - cumulative[index]
    fraction = 0.0 if segment_m <= 0 else (target - cumulative[index]) / segment_m
    lat1, lon1 = points[index]
    lat2, lon2 = points[index + 1]
    longitude_delta = (lon2 - lon1 + 180.0) % 360.0 - 180.0
    return lat1 + (lat2 - lat1) * fraction, lon1 + longitude_delta * fraction


def point_at_percent(points: list[list[float]], percent: float) -> tuple[float, float]:
    cumulative, total = cumulative_distance(points)
    return point_at_offset(points, cumulative, total * percent / 100.0)


def required_distinct_stretch_m(distance_m: float) -> float:
    """Mirrors AlternativePolicy.requiredDistinctStretch (Kotlin)."""
    return min(
        STYLE_MAX_MEANINGFUL_STRETCH_M,
        max(STYLE_MIN_MEANINGFUL_STRETCH_M, distance_m * STYLE_MEANINGFUL_STRETCH_FRACTION),
    )


class _RouteGeometry:
    """One route polyline projected to metres, with a grid over its segments.

    Routes on one graph repeat the exact node coordinates of every road they
    share, so proximity on the sampled polyline tells whether a stretch is a
    different road. The grid keeps the pairwise check from comparing every
    candidate vertex against every reference segment.
    """

    CELL_M = 250.0

    def __init__(self, points: list[list[float]], scale_x: float, scale_y: float) -> None:
        self.scale_x = scale_x
        self.scale_y = scale_y
        self.vertices = [
            (math.radians(float(lon)) * scale_x, math.radians(float(lat)) * scale_y)
            for lat, lon in points
        ]
        self.segments = [
            (self.vertices[index], self.vertices[index + 1])
            for index in range(len(self.vertices) - 1)
        ]
        self.grid: dict[tuple[int, int], list[int]] = {}
        for index, ((x1, y1), (x2, y2)) in enumerate(self.segments):
            for cell in self._cells(x1, y1, x2, y2):
                self.grid.setdefault(cell, []).append(index)

    def length(self) -> float:
        return sum(
            math.hypot(x2 - x1, y2 - y1) for (x1, y1), (x2, y2) in self.segments
        )

    def near(self, x: float, y: float, tolerance_m: float) -> bool:
        cx, cy = math.floor(x / self.CELL_M), math.floor(y / self.CELL_M)
        for gx in (cx - 1, cx, cx + 1):
            for gy in (cy - 1, cy, cy + 1):
                for index in self.grid.get((gx, gy), ()):
                    if _point_segment_distance_m(x, y, *self.segments[index]) <= tolerance_m:
                        return True
        return False

    def _cells(self, x1: float, y1: float, x2: float, y2: float):
        min_x, max_x = sorted((x1, x2))
        min_y, max_y = sorted((y1, y2))
        for cx in range(math.floor(min_x / self.CELL_M), math.floor(max_x / self.CELL_M) + 1):
            for cy in range(math.floor(min_y / self.CELL_M), math.floor(max_y / self.CELL_M) + 1):
                yield cx, cy


def _point_segment_distance_m(
    x: float,
    y: float,
    first: tuple[float, float],
    second: tuple[float, float],
) -> float:
    (x1, y1), (x2, y2) = first, second
    dx, dy = x2 - x1, y2 - y1
    if dx == 0.0 and dy == 0.0:
        return math.hypot(x - x1, y - y1)
    t = ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return math.hypot(x - (x1 + t * dx), y - (y1 + t * dy))


def route_projection(
    first_points: list[list[float]],
    second_points: list[list[float]],
) -> tuple[float, float]:
    """A shared metres-per-degree pair for two routes, around their mean latitude."""
    latitudes = [float(point[0]) for point in first_points + second_points]
    scale_y = 6_371_008.8
    return scale_y * math.cos(math.radians(sum(latitudes) / len(latitudes))), scale_y


def route_geometry_diversity(
    candidate_points: list[list[float]],
    reference_points: list[list[float]],
    *,
    tolerance_m: float = STYLE_GEOMETRY_TOLERANCE_M,
) -> tuple[float, float]:
    """(shared fraction, longest unshared stretch in metres) of candidate vs reference.

    Mirrors DistinctRideStyles.sameRide's inputs: a candidate segment counts as
    shared when both its ends sit within [tolerance_m] of the reference, and a
    run of segments that does not is a stretch of the candidate's own road.
    """
    if len(candidate_points) < 2 or len(reference_points) < 2:
        return 1.0, 0.0
    scale_x, scale_y = route_projection(candidate_points, reference_points)
    candidate = _RouteGeometry(candidate_points, scale_x, scale_y)
    reference = _RouteGeometry(reference_points, scale_x, scale_y)
    shared_length = 0.0
    longest = 0.0
    current = 0.0
    for (x1, y1), (x2, y2) in candidate.segments:
        segment_m = math.hypot(x2 - x1, y2 - y1)
        if reference.near(x1, y1, tolerance_m) and reference.near(x2, y2, tolerance_m):
            shared_length += segment_m
            current = 0.0
        else:
            current += segment_m
            longest = max(longest, current)
    # sameRide divides shared roads by the longer ride, so a long detour with
    # an intact shared middle is never reported as 100% shared.
    longer = max(candidate.length(), reference.length())
    return (shared_length / longer if longer > 0.0 else 1.0), longest


def styles_are_same_ride(
    first_points: list[list[float]],
    second_points: list[list[float]],
    *,
    tolerance_m: float = STYLE_GEOMETRY_TOLERANCE_M,
) -> bool:
    """Mirrors DistinctRideStyles.sameRide on the sampled route geometry."""
    if len(first_points) < 2 or len(second_points) < 2:
        return True
    shared_first, stretch_first = route_geometry_diversity(
        first_points, second_points, tolerance_m=tolerance_m
    )
    shared_second, stretch_second = route_geometry_diversity(
        second_points, first_points, tolerance_m=tolerance_m
    )
    if max(shared_first, shared_second) >= STYLE_SAME_RIDE_SHARE:
        return True
    return (
        stretch_first < required_distinct_stretch_m(cumulative_distance(first_points)[1])
        and stretch_second < required_distinct_stretch_m(cumulative_distance(second_points)[1])
    )


def guidance_zoom_band(speed_mps: float) -> float:
    """Mirrors RouteScreen.guidanceZoomFor / CameraFollowPolicyTest bands."""
    if not math.isfinite(speed_mps) or speed_mps < 0.0:
        return GUIDANCE_ZOOM_BANDS[0][1]
    for limit, zoom in GUIDANCE_ZOOM_BANDS:
        if speed_mps < limit:
            return zoom
    return GUIDANCE_ZOOM_BANDS[-1][1]


def parse_speed_cases(value: str) -> list[float]:
    speeds: list[float] = []
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        try:
            speed = float(part)
        except ValueError as error:
            raise VisualError(f"Invalid speed case {part!r}: {error}") from error
        if not math.isfinite(speed) or speed < 0.0:
            raise VisualError(f"Speed cases must be finite and non-negative: {part!r}.")
        speeds.append(speed)
    if not speeds:
        raise VisualError("At least one speed case is required.")
    return speeds


def probe_number(probe: dict[str, Any], key: str) -> float:
    value = probe.get(key)
    return float(value) if isinstance(value, (int, float)) else float("nan")


def distance_metres(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    if not all(map(math.isfinite, (lat1, lon1, lat2, lon2))):
        return math.inf
    mean_lat = math.radians((lat1 + lat2) / 2.0)
    north = math.radians(lat2 - lat1) * 6_371_008.8
    east = math.radians(lon2 - lon1) * 6_371_008.8 * math.cos(mean_lat)
    return math.hypot(north, east)


def camera_near(probe: dict[str, Any], lat: float, lon: float, *, tolerance_m: float = 30.0) -> bool:
    """True when the settled camera target sits on the injected fix."""
    return distance_metres(
        probe_number(probe, "lat"), probe_number(probe, "lon"), lat, lon
    ) <= tolerance_m


def bearing_degrees(
    first: tuple[float, float],
    second: tuple[float, float],
) -> float:
    lat1, lon1 = map(math.radians, first)
    lat2, lon2 = map(math.radians, second)
    delta_lon = lon2 - lon1
    y = math.sin(delta_lon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(
        delta_lon
    )
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def bearing_delta_degrees(first: float, second: float) -> float:
    return (second - first + 540.0) % 360.0 - 180.0


def bearing_distance_degrees(first: float, second: float) -> float:
    if not (math.isfinite(first) and math.isfinite(second)):
        return math.inf
    return abs(bearing_delta_degrees(first, second))


def find_route_turn(
    points: list[list[float]],
    cumulative: list[float],
    *,
    threshold_deg: float,
    min_offset_m: float,
    max_offset_m: float,
) -> tuple[float, float, float] | None:
    """First pronounced corner: (offset_m, incoming bearing, outgoing bearing)."""
    if not math.isfinite(threshold_deg) or threshold_deg <= 0.0:
        raise VisualError("The turn threshold must be a positive number of degrees.")
    sample = max(0.0, min_offset_m)
    while sample <= max_offset_m:
        before = point_at_offset(points, cumulative, max(0.0, sample - 40.0))
        at = point_at_offset(points, cumulative, sample)
        after = point_at_offset(points, cumulative, min(cumulative[-1], sample + 40.0))
        incoming = bearing_degrees(before, at)
        outgoing = bearing_degrees(at, after)
        if abs(bearing_delta_degrees(incoming, outgoing)) >= threshold_deg:
            return sample, incoming, outgoing
        sample += 20.0
    return None


# --------------------------------------------------------------------------
# Screen-size suite (CommonScreenSizeTest)
# --------------------------------------------------------------------------

def load_ui_size_matrix(root: Path) -> dict[str, Any]:
    """Reads the suite matrix: the single source for windows and font scales."""
    path = root / UI_SIZE_MATRIX
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
        raise VisualError(f"Cannot read UI size matrix {path}: {error}") from error
    if not isinstance(payload, dict):
        raise VisualError(f"The UI size matrix must be an object: {path}")
    return payload


def ui_size_case_ids(matrix: dict[str, Any]) -> list[str]:
    return [str(entry["id"]) for entry in matrix.get("sizes", [])
            if isinstance(entry, dict) and entry.get("id")]


def ui_size_matrix_font_scales(matrix: dict[str, Any]) -> list[float]:
    scales: list[float] = []
    for value in matrix.get("fontScales", []):
        try:
            scales.append(float(value))
        except (TypeError, ValueError):
            continue
    return scales


def _ui_size_tokens(value: str) -> list[str]:
    return [token.strip() for token in value.split(",") if token.strip()]


def parse_ui_size_cases(value: str, matrix: dict[str, Any]) -> list[str]:
    """Validates a --cases filter against the matrix; an invalid filter fails."""
    tokens = _ui_size_tokens(value)
    if not tokens:
        raise VisualError("--cases was empty; omit it to run every window in the matrix.")
    duplicates = sorted({token for token in tokens if tokens.count(token) > 1})
    if duplicates:
        raise VisualError(f"--cases repeats {', '.join(duplicates)}.")
    known = ui_size_case_ids(matrix)
    if known:
        unknown = [token for token in tokens if token not in known]
        if unknown:
            raise VisualError(
                f"--cases has unknown window id(s): {', '.join(unknown)}. "
                f"Known ids: {', '.join(known)}."
            )
    return tokens


def parse_ui_size_font_scales(value: str, matrix: dict[str, Any]) -> list[str]:
    """Validates and normalizes a --font-scales filter (2.0 -> "2")."""
    tokens = _ui_size_tokens(value)
    if not tokens:
        raise VisualError("--font-scales was empty; omit it to run every scale in the matrix.")
    scales: list[float] = []
    for token in tokens:
        try:
            scale = float(token)
        except ValueError:
            raise VisualError(f"--font-scales has a non-numeric value: {token!r}.") from None
        if not math.isfinite(scale) or scale <= 0:
            raise VisualError(f"--font-scales must be positive finite numbers, got {token!r}.")
        scales.append(scale)
    known = ui_size_matrix_font_scales(matrix)
    if known:
        unknown = [f"{scale:g}" for scale in scales
                   if not any(abs(scale - member) < 1e-9 for member in known)]
        if unknown:
            raise VisualError(
                f"--font-scales has value(s) outside the matrix: {', '.join(unknown)}. "
                f"Matrix scales: {', '.join(f'{member:g}' for member in known)}."
            )
    return [f"{scale:g}" for scale in sorted(set(scales))]


def ui_size_expected_frames(cases: list[str] | None, font_scales: list[str] | None,
                            matrix: dict[str, Any]) -> int | None:
    """Frames the run should export: one per window x scale x surface.

    None when the matrix is unreadable and no filter supplies the counts, so a
    partial export is never mistaken for a complete one.
    """
    windows = len(cases) if cases else len(ui_size_case_ids(matrix))
    scales = len(font_scales) if font_scales else len(ui_size_matrix_font_scales(matrix))
    if not windows or not scales:
        return None
    return windows * scales * len(UI_SIZE_SURFACES)


def ui_size_device_relative(path: str, package: str = APP_ID) -> str:
    """Device absolute path -> run-as-relative path (cache/... for the app)."""
    prefix = f"/data/data/{package}/"
    if path.startswith(prefix):
        return path[len(prefix):]
    return path.lstrip("/") if path.startswith("/") else path


def ui_size_capture_records(payloads: Iterable[dict[str, Any]], listing: Iterable[str],
                            package: str = APP_ID) -> tuple[list[dict[str, Any]], list[str]]:
    """Joins per-PNG metadata with the PNGs the device actually exported.

    Returns (records, problems). A JSON whose PNG is absent, whose surface is
    unknown, or whose label disagrees with its surface is reported as a problem
    rather than silently dropped, so a partial export is always visible.
    """
    present = {ui_size_device_relative(entry, package) for entry in listing}
    by_name = {Path(entry).name: entry for entry in present}
    records: dict[str, dict[str, Any]] = {}
    problems: list[str] = []
    for payload in payloads:
        if not isinstance(payload, dict):
            problems.append(f"ignored non-object capture JSON: {payload!r}")
            continue
        label = payload.get("label")
        surface = payload.get("surface")
        device_png = payload.get("path")
        if not isinstance(label, str) or not label.startswith(UI_SIZE_LABEL_PREFIX):
            problems.append(f"ignored capture JSON with label {label!r}")
            continue
        if surface not in UI_SIZE_SURFACES:
            problems.append(f"{label}: unknown surface {surface!r}")
            continue
        if not label.endswith(f"-{surface}"):
            problems.append(f"{label}: label does not end with its surface {surface!r}")
            continue
        relative = ui_size_device_relative(str(device_png), package) if device_png else ""
        if relative not in present:
            relative = by_name.get(Path(relative).name, "")
        if not relative:
            problems.append(f"{label}: exported PNG missing for {device_png!r}")
            continue
        if label in records:
            problems.append(f"{label}: duplicate capture JSON (kept the last)")
        records[label] = {**payload, "label": label, "surface": surface, "devicePath": relative}
    return [records[key] for key in sorted(records)], problems


def ui_size_record_from_line(line: str) -> dict[str, Any] | None:
    """Parses one `I/CurveMaps.UiSizes(...): {json}` logcat line."""
    start = line.find("{")
    if start < 0:
        return None
    try:
        payload = json.loads(line[start:])
    except json.JSONDecodeError:
        return None
    if not isinstance(payload, dict) or not payload.get("label") or not payload.get("surface"):
        return None
    return payload


def ui_size_records_from_logs(lines: Iterable[str]) -> list[dict[str, Any]]:
    by_label: dict[str, dict[str, Any]] = {}
    for line in lines:
        payload = ui_size_record_from_line(line)
        if payload is not None:
            by_label[str(payload["label"])] = payload
    return [by_label[key] for key in sorted(by_label)]


UI_SIZE_OK = re.compile(r"^OK \(\d+ tests?\)\s*$", re.MULTILINE)


def ui_size_failure_summary(output: str) -> str | None:
    """Why the instrumentation run failed, or None when it reported OK.

    AndroidJUnitRunner prints JUnit's `OK (N tests)` only on success, so a zero
    shell exit code alone is not a pass: a failure line, a crashed process, or a
    runner that never started must all be reported.
    """
    for pattern in (
        r"INSTRUMENTATION_FAILED.*",
        r"INSTRUMENTATION_ABORTED.*",
        r"Process crashed.*",
        r"Unable to find instrumentation.*",
        r"FAILURES!!!.*",
        r"Tests run: \d+, +Failures: [1-9]\d*.*",
    ):
        match = re.search(pattern, output)
        if match:
            return match.group(0).strip()
    if not UI_SIZE_OK.search(output):
        return "instrumentation finished without reporting OK (N tests)"
    return None


def ui_size_instrumentation_component(listing: str, target: str = APP_ID) -> str | None:
    """The installed `<test-package>/<runner>` component for [target], if any."""
    for line in listing.splitlines():
        match = re.match(r"instrumentation:(\S+)\s+\(target=([^)]+)\)", line.strip())
        if match and match.group(2) == target:
            return match.group(1)
    return None


def add_selector(parser: argparse.ArgumentParser) -> None:
    selector = parser.add_mutually_exclusive_group(required=True)
    selector.add_argument("--text", help="Match visible UI text.")
    selector.add_argument("--description", help="Match a content description.")
    parser.add_argument("--contains", action="store_true", help="Match a substring.")
    parser.add_argument("--index", type=int, default=0, help="Choose one match when there are duplicates.")


def round_trip_amount(description: str) -> int | None:
    """Minutes or kilometres from "Round trip length, 1 h 30 min" / "…, 80 km"; None for a back-by time."""
    value = description.split(", ", 1)[-1].strip()
    km = re.fullmatch(r"(\d+) km", value)
    if km:
        return int(km.group(1))
    timed = re.fullmatch(r"(?:(\d+) h)?\s*(?:(\d+) min)?", value)
    if timed and (timed.group(1) or timed.group(2)):
        return int(timed.group(1) or 0) * 60 + int(timed.group(2) or 0)
    return None


def round_trip_on_grid(minutes: int | None, kilometres: int | None) -> str | None:
    """Why a wished length cannot be reached with the stepper, or None when it can."""
    if kilometres is not None:
        step = 5 if kilometres <= 50 else 10
        if not 10 <= kilometres <= 400 or kilometres % step:
            return "--distance must be 10–400 km, in 5 km steps up to 50 km and 10 km steps above"
    elif minutes is not None and (not 15 <= minutes <= 480 or minutes % 15):
        return "--minutes must be 15–480, in 15 minute steps"
    return None


def round_trip_time_label(minutes: int) -> str:
    """The app's RoundTripLength.Time label: "45 min", "1 h", "1 h 30 min"."""
    if minutes < 60:
        return f"{minutes} min"
    hours, rest = divmod(minutes, 60)
    return f"{hours} h" if rest == 0 else f"{hours} h {rest} min"


def parse_style(value: str) -> int:
    """Accepts a ride-style name (e.g. 'twisty') or its slider level."""
    normalized = value.strip().lower().replace(" ", "-").replace("_", "-")
    if normalized in STYLE_NAMES:
        return STYLE_NAMES.index(normalized)
    try:
        level = int(normalized)
    except ValueError:
        raise argparse.ArgumentTypeError(f"unknown ride style {value!r}; use {', '.join(STYLE_NAMES)}") from None
    if not 0 <= level < len(STYLE_NAMES):
        raise argparse.ArgumentTypeError(f"ride style level must be 0–{len(STYLE_NAMES) - 1}")
    return level


def add_route_options(parser: argparse.ArgumentParser, *, require_from: bool = True, default_style: int = 0) -> None:
    if require_from:
        parser.add_argument("--from", dest="from_text", required=True, help="Place name or lat,lon.")
    else:
        parser.set_defaults(from_text="")
    parser.add_argument("--to", dest="to_text", required=True, help="Place name or lat,lon.")
    parser.add_argument("--style", "--complexity", dest="style", type=parse_style, default=default_style,
                        help=f"Ride style: {', '.join(STYLE_NAMES)} or its level 0–{len(STYLE_NAMES) - 1} (default: {STYLE_NAMES[default_style]}).")
    parser.add_argument("--avoid-motorways", action="store_true", help="Avoid motorways and trunk roads for this route.")
    parser.add_argument("--block-unpaved", action="store_true", help="Block unpaved roads for this route.")
    parser.add_argument("--hills", action="store_true",
                        help="Tick Route settings > Hills and elevation (climbs count as fun roads; the card shows the climb).")
    parser.add_argument("--stops-towns", action="store_true",
                        help="Tick Route settings > Avoid stops and towns (the card counts the stops).")
    parser.add_argument("--route-index", type=int, default=1, help="Route to follow (always 1: the planner shows one route per ride style).")
    parser.add_argument("--timeout", type=float, default=300, help="Maximum route wait in seconds.")
    parser.add_argument("--progress-interval", type=float, default=2.0, help="Seconds between screenshots while routing.")
    parser.add_argument("--progress", "--progress-percent", dest="progress_percent", type=float, help="Enter ride mode and jump to this route percentage.")
    parser.add_argument("--step-delay", type=float, default=0.15, help="Delay between simulated GPS fixes in seconds.")
    parser.add_argument("--black-and-white", choices=("on", "off", "both"), help="Set ride-map style; 'both' captures every frame in both styles.")
    parser.add_argument("--plan-only", action="store_true", help="Stop after the route is ready; leave the planner visible.")
    parser.add_argument("--save-bubble", action="store_true", help="Also save the shown ride from its card (bookmark button, then long-press) and capture the save bubble.")
    parser.add_argument("--eta-layouts", action="store_true", help="Also capture the planner at 320 dp with 2x text; restore device settings afterwards.")
    parser.add_argument("--reuse-app", action="store_true", help="Use the running app without rebuilding or relaunching it.")
    parser.add_argument("--no-build", action="store_true", help="Use the existing debug APK when launching.")
    parser.add_argument("--skip-map", action="store_true", help="Launch without copying the PMTiles archive.")
    parser.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when a map is already installed.")


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Launch, route, inspect, and control CurveMaps on an ADB device.",
        epilog=(
            "Examples:\n"
            "  tools/test/visual.py launch\n"
            "  tools/test/visual.py route --from '-27.4698,153.0251' --to '-27.3353,152.7720'\n"
            "  tools/test/visual.py route --from 'Brisbane' --to 'Mount Glorious' --style twisty --avoid-motorways --block-unpaved\n"
            "  tools/test/visual.py route --from '-27.4698,153.0251' --to '-27.3353,152.7720' --hills --stops-towns --plan-only\n"
            "  tools/test/visual.py styles --from 'Brisbane' --to 'Mount Glorious'\n"
            "  tools/test/visual.py saved-routes --from '-27.4698,153.0251' --to '-27.3353,152.7720'\n"
            "  tools/test/visual.py current-location --to '-27.3353,152.7720' --plan-only\n"
            "  tools/test/visual.py lanes --from '-27.4698,153.0251' --to '-27.5598,153.0811'\n"
            "  tools/test/visual.py nav-camera --from 'Brisbane' --to 'Mount Glorious'\n"
            "  tools/test/visual.py nav-camera --from 'Brisbane' --to 'Mount Glorious' --black-and-white both\n"
            "  tools/test/visual.py nav-camera --from '-27.4700,153.0250' --to 'Mount Glorious' --black-and-white on\n"
            "  tools/test/visual.py route --from 'Brisbane' --to 'Mount Glorious' --black-and-white both\n"
            "  tools/test/visual.py map-mode --black-and-white on\n"
            "  tools/test/visual.py settings-menu\n"
            "  tools/test/visual.py first-run\n"
            "  tools/test/visual.py first-run --bundled --ask-location\n"
            "  tools/test/visual.py ui-sizes\n"
            "  tools/test/visual.py ui-sizes --cases phone,tablet --font-scales 1.0,2.0\n"
            "  tools/test/visual.py search-results --query Brisbane\n"
            "  tools/test/visual.py screenshot --label before-change\n"
            "  tools/test/visual.py tree\n"
            "  tools/test/visual.py tap --description 'Planning settings'\n"
            "  tools/test/visual.py type --description From --value 'Samford'\n"
            "  tools/test/visual.py swipe 500 700 800 900 --duration 500\n"
            "  tools/test/visual.py logs --lines 100"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--serial", help="ADB serial (defaults to ANDROID_SERIAL or the only online device).")
    parser.add_argument("--avd", help=f"AVD to boot when none is online (default: {DEFAULT_AVD}).")
    parser.add_argument("--output-dir", help="Artifact directory (default: build/visual-inspection).")
    parser.add_argument(
        "--assume-default-settings", action="store_true",
        help="App state is freshly reset: skip opening Route settings unless a flag changes a default.",
    )
    parser.add_argument(
        "--apk",
        help="Install this prebuilt APK instead of building (implies --no-build; "
        "it must match the scenario, e.g. a map-less APK for first-run without --bundled).",
    )
    commands = parser.add_subparsers(dest="command", required=True)

    launch = commands.add_parser("launch", help="Build/install, boot or reuse an emulator, and launch the app.")
    launch.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")
    launch.add_argument("--skip-map", action="store_true", help="Do not seed the PMTiles archive.")
    launch.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when a map is already installed.")

    settings_menu = commands.add_parser(
        "settings-menu",
        help="Capture the planner settings menu and its dismissed state.",
    )
    settings_menu.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")
    settings_menu.add_argument("--skip-map", action="store_true", help="Launch without copying the PMTiles archive.")
    settings_menu.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when a map is already installed.")

    search_results = commands.add_parser(
        "search-results", help="Capture the From POI action and search results with and without the keyboard.",
    )
    search_results.add_argument("--field", choices=("From", "To"), default="From")
    search_results.add_argument("--query", default="Brisbane")
    search_results.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")
    search_results.add_argument("--skip-map", action="store_true", help="Launch without copying the PMTiles archive.")
    search_results.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when installed.")

    maps_settings = commands.add_parser(
        "maps-settings",
        help=(
            "Capture the Maps screen: catalog load, grouped catalog and search, download/import "
            "intermediate states, installed-map delete confirmation, and Back."
        ),
    )
    maps_settings.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")
    maps_settings.add_argument("--skip-map", action="store_true", help="Launch without copying the PMTiles archive.")
    maps_settings.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when a map is already installed.")

    oss_licences = commands.add_parser(
        "oss-licences",
        help=(
            "Capture Open-source licences: the settings row, the list, the ODbL and "
            "Apache-2.0 texts, Back to the list, and Back to the planner."
        ),
    )
    oss_licences.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")
    oss_licences.add_argument("--skip-map", action="store_true", help="Launch without copying the PMTiles archive.")
    oss_licences.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when a map is already installed.")

    gpx = commands.add_parser(
        "gpx",
        help=(
            "Open a GPX track from \"another app\" and capture it followed road for road, Back, "
            "the preview, rotation and START again, a shared stop list, and a refused non-GPX file."
        ),
    )
    gpx.add_argument("--file", help="A GPX file of your own to open first (default: the Mount Glorious loop fixture).")
    gpx.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")
    gpx.add_argument("--skip-map", action="store_true", help="Launch without copying the PMTiles archive.")
    gpx.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when a map is already installed.")

    first_run = commands.add_parser(
        "first-run",
        help=(
            "Wipe app data and capture each welcome-carousel page, then the no-map "
            "card (map-less APK), the Maps screen it opens, and Back."
        ),
    )
    first_run.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")
    first_run.add_argument(
        "--bundled",
        action="store_true",
        help="Use the default bundled-data build instead of the map-less one (no Open Maps shortcut expected).",
    )
    first_run.add_argument(
        "--ask-location",
        action="store_true",
        help="Do not pre-grant location: capture the system dialog the location page raises, then allow it.",
    )

    media_controls = commands.add_parser(
        "media-controls",
        help="Capture the ride media control center across B&W player states.",
    )
    media_controls.add_argument("--from", dest="from_text", default="-27.4698,153.0251", help="Place name or lat,lon.")
    media_controls.add_argument("--to", dest="to_text", default="-27.3353,152.7720", help="Place name or lat,lon.")
    media_controls.add_argument("--cold-start", action="store_true", help="Enable the debug stopped-player fixture and capture Start → playing → paused, a refusing player started by one media-button press, then a refusing player's foreground start with the ride in its Picture-in-Picture window and its return.")
    media_controls.add_argument("--title", default="Debug Track", help="Synthetic track title.")
    media_controls.add_argument("--artist", default="Debug Artist", help="Synthetic artist.")
    media_controls.add_argument("--route-timeout", type=float, default=300, help="Maximum route wait in seconds.")
    media_controls.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")
    media_controls.add_argument("--skip-map", action="store_true", help="Launch without copying the PMTiles archive.")
    media_controls.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when a map is already installed.")
    media_controls.add_argument("--reuse-app", action="store_true", help="Reuse the running app and enter ride mode from the current screen.")
    media_controls.add_argument(
        "--inactivity-only",
        action="store_true",
        help="Capture only the real ride panel's inactivity timer and blank-panel touch reset.",
    )

    route = commands.add_parser("route", help="Load endpoints, apply options, route, and optionally enter ride mode.")
    add_route_options(route)
    saved_routes = commands.add_parser(
        "saved-routes",
        help=(
            "Capture Saved routes: the empty state, two saved rides, a comment, the delete "
            "confirmation (cancelled) and Open loading a ride into the planner."
        ),
    )
    add_route_options(saved_routes)
    saved_routes.add_argument("--second-style", type=parse_style, default=4,
                              help="Ride style of the second saved ride (default: twisty; must differ from --style).")
    saved_routes.add_argument("--comment", default="Great twisties, quiet road", help="Comment typed on the first card (printable ASCII).")

    current_location = commands.add_parser(
        "current-location",
        help="Select a real emulator GPS fix as From, then continue the route flow.",
    )
    add_route_options(current_location, require_from=False)
    current_location.add_argument(
        "--location",
        default="-27.4698,153.0251",
        help="Emulator GPS coordinate to supply to the app (default: Brisbane).",
    )
    current_location.set_defaults(from_current_location=True)

    buildings = commands.add_parser("buildings", help="Offline 3D houses, city navigation, silhouettes, style switches and landscape (explicit owned serial required).")
    buildings.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")

    perf = commands.add_parser(
        "perf",
        help="Native-frame FPS benchmark over deterministic camera sweeps on the real offline map (explicit owned serial required).",
        description=(
            "Queues camera sweeps through the debug receiver and reports native\n"
            "MapLibre frame intervals (p50/p95/p99, >50/>100 ms stalls) for cold\n"
            "loading and warmed motion, A/B-ing the building extrusion paint. Each\n"
            "sweep records the rendering backend MapLibre actually initialized and\n"
            "the per-frame renderer counters; the report names the device GPU.\n\n"
            "  tools/test/visual.py --serial emulator-5554 perf\n"
            "  tools/test/visual.py --serial emulator-5554 perf --targets cbd --zooms 18 \\\n"
            "      --building-modes current,opaque,hidden --repeat 2\n"
            "  tools/test/visual.py --serial emulator-5554 perf --targets cbd,houses \\\n"
            "      --zooms cbd=14,cbd=16,cbd=18,houses=18.5 --screenshots"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    perf.add_argument("--targets", default="cbd,houses", help="Comma-separated targets: cbd,houses (default both).")
    perf.add_argument(
        "--zooms",
        default="14,16,18",
        help=(
            "Comma-separated zooms. A bare zoom applies to every target; "
            "`target=zoom` applies only to that target and, when present for a "
            "target, replaces the bare zooms there (default 14,16,18). Example: "
            "--zooms cbd=14,cbd=16,cbd=18,houses=18.5"
        ),
    )
    perf.add_argument("--motion", default="combo", choices=("orbit", "pan", "zoom", "combo"), help="Camera motion kind (default combo).")
    perf.add_argument("--tilt", type=float, default=58.0, help="Camera pitch in degrees (default 58).")
    perf.add_argument("--duration", type=float, default=6.0, help="Sweep motion duration in seconds (default 6).")
    perf.add_argument("--repeat", type=int, default=2, help="Warmed repeats per case (default 2).")
    perf.add_argument("--building-modes", default="current", help="Comma-separated extrusion diagnostics: current,opaque,hidden (default current).")
    perf.add_argument("--no-cold", action="store_true", help="Skip the fresh-process cold-loading runs.")
    perf.add_argument("--no-warm", action="store_true", help="Skip the warmed motion runs.")
    perf.add_argument("--settle", type=float, default=1.0, help="Seconds to wait after a cold restart (default 1).")
    perf.add_argument("--screenshots", action="store_true", help="Capture a settled case view for building visibility evidence.")
    perf.add_argument("--screenshot-motion", action="store_true", help="Also capture one mid-motion frame per sweep (adds a known hitch to that sweep's frames).")
    perf.add_argument("--report", default=str(DEFAULT_PERF_REPORT), help=f"Markdown report path (default {DEFAULT_PERF_REPORT}).")
    perf.add_argument("--no-build", action="store_true", help="Use the existing debug APK.")
    perf.add_argument("--skip-map", action="store_true", help="Launch without copying the PMTiles archive.")
    perf.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when a map is already installed.")

    perf_merge_parser = commands.add_parser(
        "perf-merge",
        help="Merge several perf summaries into one before/after report.",
        description=(
            "Reads the perf-summary.json of one or more `perf` runs (each run\n"
            "covers one style/backend combination) and writes one report with\n"
            "every sweep labelled by its run, using the same markdown renderer\n"
            "as a single run.\n\n"
            "  tools/test/visual.py perf-merge --labels before-opengl,after-opengl \\\n"
            "      build/visual-inspection/*/perf-summary.json"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    perf_merge_parser.add_argument("summaries", nargs="+", help="perf-summary.json files, in report order.")
    perf_merge_parser.add_argument("--labels", default="", help="Comma-separated run labels, one per summary.")
    perf_merge_parser.add_argument("--report", default=str(DEFAULT_PERF_REPORT), help=f"Markdown report path (default {DEFAULT_PERF_REPORT}).")

    camera = commands.add_parser("camera", help="Set a deterministic debug inspection camera, not a production camera policy.")
    camera.add_argument("--lat", type=float, required=True)
    camera.add_argument("--lon", type=float, required=True)
    camera.add_argument("--zoom", type=float, required=True)
    camera.add_argument("--tilt", type=float, default=58.0)
    camera.add_argument("--bearing", type=float, default=0.0)

    styles = commands.add_parser(
        "styles",
        help="Capture all seven wavy-slider stops, the intermediate drag frames, and each stop's route card.",
    )
    add_route_options(styles)

    curve_shading = commands.add_parser(
        "curve-shading",
        help="Plan one ride style and capture its route line easing toward the curvy colour, from overview to close up.",
        description=(
            "Plans --from to --to at --style (default max-twisty), then captures the\n"
            "planner's overview, the start of the ride's twistiest stretch (the colour\n"
            "transition) and its twistiest bends, flat, through the debug camera.\n\n"
            "  tools/test/visual.py curve-shading --from -27.4698,153.0251 --to -27.3353,152.7720\n"
            "  tools/test/visual.py curve-shading --from Caloundra --to Kenilworth --style twisty"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    add_route_options(curve_shading, default_style=STYLE_NAMES.index("max-twisty"))

    round_trip = commands.add_parser(
        "round-trip",
        help="Toggle Round trip beside To, set its time, distance or back-by time, and capture all seven loops back to --from.",
        description=(
            "Types --from, switches Round trip on, taps the length to open the measure\n"
            "menu that grows out of the panel, picks Ride time / Distance / Back by (the\n"
            "menu melts back in), presses the stepper once and then steps to the amount;\n"
            "then START and every ride-style stop's loop. Fails when a loop does not end\n"
            "at its start or two stops are the same ride.\n\n"
            "  tools/test/visual.py round-trip --from -27.3727,152.8864 --minutes 60\n"
            "  tools/test/visual.py round-trip --from Samford --distance 80\n"
            "  tools/test/visual.py round-trip --from Samford --back-by"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    round_trip.add_argument("--from", dest="from_text", required=True, help="Place name or lat,lon of the start and finish.")
    round_trip_length = round_trip.add_mutually_exclusive_group()
    round_trip_length.add_argument("--minutes", type=int, default=60, help="Wished ride time in minutes (default 60).")
    round_trip_length.add_argument("--distance", type=int, help="Wished distance in kilometres instead of a time.")
    round_trip_length.add_argument("--back-by", action="store_true",
                                   help="Measure by a time to be back (one stepper press later than offered).")
    round_trip.add_argument("--timeout", type=float, default=300, help="Maximum wait for each stop's loop in seconds.")
    round_trip.add_argument("--progress-interval", type=float, default=2.0, help="Seconds between frames while the wave grows.")
    round_trip.add_argument("--reuse-app", action="store_true", help="Use the running app without rebuilding or relaunching it.")
    round_trip.add_argument("--no-build", action="store_true", help="Use the existing debug APK when launching.")
    round_trip.add_argument("--skip-map", action="store_true", help="Launch without copying the PMTiles archive.")
    round_trip.add_argument("--refresh-map", action="store_true", help="Recopy PMTiles even when a map is already installed.")

    lanes = commands.add_parser(
        "lanes",
        help="Ride a route and capture every maneuver whose guidance card shows recommended lanes.",
    )
    add_route_options(lanes)
    lanes.add_argument("--step", type=float, default=30.0, help="Metres between injected GPS fixes (default: 30).")
    lanes.add_argument("--speed", type=float, default=12.0, help="Speed in m/s for the injected fixes (default: 12).")
    lanes.add_argument("--max-captures", type=int, default=6, help="Stop after this many lane maneuvers (default: 6).")
    lanes.add_argument("--settle", type=float, default=0.7, help="Seconds to let the HUD repaint before a capture (default: 0.7).")

    suggestions = commands.add_parser("suggestions", help="Replay GPS until a real scenic offer; capture offer, fork, and decision.")
    add_route_options(suggestions, default_style=1)
    suggestions.add_argument("--decision", choices=("take", "skip", "ride", "continue"), default="ride")
    suggestions.add_argument("--settle", type=float, default=0.7, help="Seconds for a settled screenshot.")
    suggestions.add_argument("--speed", type=float, default=15.0, help="Replay speed in m/s (default 15).")
    suggestions.add_argument("--max-distance", type=float, default=35_000.0, help="Maximum baseline replay distance in metres.")

    gps_loss = commands.add_parser(
        "gps-loss",
        help="Lose GPS mid-ride: capture the along-route estimate, the eased recovery, and live GPS again.",
    )
    add_route_options(gps_loss)
    gps_loss.add_argument("--speed", type=float, default=15.0, help="Replay speed in m/s (default 15).")
    gps_loss.add_argument("--approach", type=float, default=600.0, help="Metres ridden before GPS is lost (default 600).")
    gps_loss.add_argument("--gap", type=float, default=12.0, help="Seconds without GPS before recovery (default 12, under the 20 s debug authority).")
    gps_loss.add_argument("--recover-offset", type=float, default=400.0,
                          help="Where GPS returns relative to the estimate, metres; negative = behind it (default 400).")
    gps_loss.add_argument("--settle", type=float, default=0.7, help="Seconds for a settled screenshot.")

    pace = commands.add_parser("pace", help="Replay local model pace, loss, stops, GPS gaps and recovery; verify and capture the HUD.")
    add_route_options(pace)

    ui_sizes = commands.add_parser(
        "ui-sizes",
        help="Run CommonScreenSizeTest's window/font matrix and export gallery-ready screenshots.",
        description=(
            "Builds the debug app and androidTest APKs with the JDK 17 wrapper,\n"
            "installs both, runs com.curvemaps.app.ui.CommonScreenSizeTest through\n"
            "`adb shell am instrument -w` with uiSizeCaptures=true, then pulls the\n"
            "suite's PNG+JSON exports from the app cache with run-as. Window sizes,\n"
            "font scales, and their official references live only in\n"
            "app/src/androidTest/assets/ui-screen-sizes.json; --cases/--font-scales\n"
            "validate against it. Instrumentation failures are reported honestly and\n"
            "any frames already exported are still imported. The suite renders\n"
            "production Compose surfaces (endpoints, planner, ride, settings, welcome) with\n"
            "fixture models; it does not cover the native MapLibre map or routing.\n\n"
            "  tools/test/visual.py ui-sizes\n"
            "  tools/test/visual.py --serial emulator-5554 ui-sizes --cases phone,tablet --font-scales 1.0,2.0\n"
            "  tools/test/visual.py ui-sizes --no-build --apk app/build/outputs/apk/debug/app-debug.apk"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ui_sizes.add_argument(
        "--no-build", action="store_true",
        help="Skip every Gradle invocation and use the existing app and androidTest APKs (fail if either is missing).",
    )
    ui_sizes.add_argument(
        "--test-apk",
        help=f"Instrumented APK to install (default: {UI_SIZE_TEST_APK}).",
    )
    ui_sizes.add_argument(
        "--cases",
        help="Comma-separated ui-screen-sizes window ids (default: every window in the matrix).",
    )
    ui_sizes.add_argument(
        "--font-scales",
        help="Comma-separated font scales from the matrix, for example 1.0,2.0 (default: every scale).",
    )
    ui_sizes.add_argument(
        "--timeout", type=float, default=UI_SIZE_TIMEOUT_S,
        help=f"Seconds to wait for the instrumentation run (default {UI_SIZE_TIMEOUT_S:g}).",
    )

    nav_camera = commands.add_parser(
        "nav-camera",
        help="Capture guidance camera lock/relock, speed, turn, and exit restore (both ride-map styles with --black-and-white both).",
    )
    add_route_options(nav_camera)
    nav_camera.add_argument(
        "--speed-cases",
        default=DEFAULT_SPEED_CASES,
        help=f"Comma-separated m/s cases to capture (default: {DEFAULT_SPEED_CASES}).",
    )
    nav_camera.add_argument(
        "--turn-threshold",
        type=float,
        default=40.0,
        help="Minimum turn angle in degrees to use for the heading-turn frames (default: 40).",
    )
    nav_camera.add_argument(
        "--turn-step",
        type=float,
        default=25.0,
        help="Metres between heading-turn GPS fixes (default: 25).",
    )
    nav_camera.add_argument(
        "--turn-speed",
        type=float,
        default=12.0,
        help="Speed in m/s used through the heading-turn frames (default: 12).",
    )
    nav_camera.add_argument(
        "--settle",
        type=float,
        default=0.7,
        help="Seconds to let the camera animation and repaint settle before a capture (default: 0.7).",
    )

    options = commands.add_parser("options", help="Set route options on the current screen.")
    options.add_argument("--style", "--complexity", dest="style", type=parse_style, help="Set the ride style (name or level).")
    options.add_argument("--avoid-motorways", choices=("on", "off"), help="Set whether motorways are avoided.")
    options.add_argument("--block-unpaved", choices=("on", "off"), help="Set whether unpaved roads are blocked.")
    options.add_argument("--hills", choices=("on", "off"), help="Set the Hills and elevation ride factor.")
    options.add_argument("--stops-towns", choices=("on", "off"), help="Set the Avoid stops and towns ride factor.")

    progress = commands.add_parser("progress", help="Move the active emulator ride to a route percentage.")
    progress.add_argument("--percent", type=float, required=True, help="Target progress from 0 to 100 percent.")
    progress.add_argument("--step-delay", type=float, default=0.15, help="Delay between simulated GPS fixes in seconds.")

    map_mode = commands.add_parser("map-mode", help="Set black-and-white ride-map style on an active ride.")
    map_mode.add_argument("--black-and-white", choices=("on", "off", "both"), required=True, help="Capture black-and-white mode on, off, or both.")

    commands.add_parser("icon-states", help="Capture moon and speaker states on an active ride, restoring preferences.")

    screenshot = commands.add_parser("screenshot", help="Save the current screen and update latest.png.")
    screenshot.add_argument("--label", default="manual", help="Artifact filename label.")
    screenshot.add_argument("--no-tree", action="store_true", help="Skip the accessibility dump.")

    commands.add_parser("tree", help="Print visible accessibility nodes as JSON.")
    commands.add_parser("status", help="Print app process, foreground activity, and visible nodes.")

    tap = commands.add_parser("tap", help="Tap a visible node by text or content description.")
    add_selector(tap)

    type_cmd = commands.add_parser("type", help="Focus a field and enter printable ASCII text.")
    add_selector(type_cmd)
    type_cmd.add_argument("--value", required=True, help="Text to type.")
    type_cmd.add_argument("--append", action="store_true", help="Append instead of selecting existing text first.")

    key = commands.add_parser("key", help="Send an Android key event, for example KEYCODE_BACK or KEYCODE_ENTER.")
    key.add_argument("code")

    swipe = commands.add_parser("swipe", help="Swipe between screen coordinates in physical pixels.")
    swipe.add_argument("x1", type=int)
    swipe.add_argument("y1", type=int)
    swipe.add_argument("x2", type=int)
    swipe.add_argument("y2", type=int)
    swipe.add_argument("--duration", type=int, default=450, help="Swipe duration in milliseconds.")

    watch = commands.add_parser("watch", help="Capture changing screens while another command or gesture runs.")
    watch.add_argument("--seconds", type=float, default=45, help="Capture duration.")
    watch.add_argument("--interval", type=float, default=1.5, help="Seconds between captures.")
    watch.add_argument("--max-captures", type=int, default=30, help="Maximum timeline screenshots.")

    logs = commands.add_parser("logs", help="Print recent app routing and geocoder logs.")
    logs.add_argument("--lines", type=int, default=120)

    commands.add_parser("stop", help="Force-stop the app without shutting down the emulator.")
    return parser


def main() -> int:
    parser = create_parser()
    args = parser.parse_args()
    harness = VisualHarness(args)
    try:
        if args.command == "launch":
            harness.launch_app(
                build=not args.no_build,
                skip_map=args.skip_map,
                refresh_map=args.refresh_map,
            )
        elif args.command == "route":
            harness.route(args)
        elif args.command == "saved-routes":
            harness.capture_saved_routes(args)
        elif args.command == "search-results":
            harness.capture_search_results(args)
        elif args.command == "current-location":
            harness.route(args)
        elif args.command == "nav-camera":
            harness.navigation_camera(args)
        elif args.command == "suggestions":
            harness.suggestions(args)
        elif args.command == "pace":
            harness.pace(args)
        elif args.command == "gps-loss":
            harness.gps_loss(args)
        elif args.command == "ui-sizes":
            harness.ui_sizes(args)
        elif args.command == "lanes":
            harness.lane_guidance(args)
        elif args.command == "round-trip":
            harness.capture_round_trip(args)
        elif args.command == "styles":
            harness.capture_styles(args)
        elif args.command == "curve-shading":
            harness.capture_curve_shading(args)
        elif args.command == "buildings":
            harness.buildings(args)
        elif args.command == "perf":
            harness.perf(args)
        elif args.command == "perf-merge":
            perf_merge(args)
        elif args.command == "camera":
            harness.ensure_device(start_if_missing=False)
            harness.inspect_camera(args.lat, args.lon, args.zoom, args.tilt, args.bearing)
            harness.capture("inspection-camera")
        elif args.command == "settings-menu":
            harness.capture_settings_menu(
                build=not args.no_build,
                skip_map=args.skip_map,
                refresh_map=args.refresh_map,
            )
        elif args.command == "maps-settings":
            harness.capture_maps_settings(args)
        elif args.command == "oss-licences":
            harness.capture_oss_licences(args)
        elif args.command == "gpx":
            harness.capture_gpx(args)
        elif args.command == "first-run":
            harness.capture_first_run(args)
        elif args.command == "media-controls":
            harness.capture_media_controls(args)
        elif args.command in {"options", "progress", "map-mode", "icon-states", "screenshot", "tree", "status", "tap", "type", "key", "swipe", "watch", "logs", "stop"}:
            harness.ensure_device(start_if_missing=False)
            if args.command == "options":
                if args.style is not None:
                    harness.set_style(args.style)
                block = None if args.block_unpaved is None else args.block_unpaved == "on"
                avoid = None if args.avoid_motorways is None else args.avoid_motorways == "on"
                hills = None if args.hills is None else args.hills == "on"
                stops = None if args.stops_towns is None else args.stops_towns == "on"
                harness.set_route_settings(avoid_motorways=avoid, block_unpaved=block,
                                           include_elevation=hills, avoid_stops_towns=stops)
                harness.capture("options-updated")
            elif args.command == "progress":
                harness.progress_to(args.percent, args.step_delay)
            elif args.command == "map-mode":
                modes = ("on", "off") if args.black_and_white == "both" else (args.black_and_white,)
                for mode in modes:
                    harness.set_black_and_white(mode)
            elif args.command == "icon-states":
                harness.capture_icon_states()
            elif args.command == "screenshot":
                harness.capture(args.label, include_tree=not args.no_tree)
            elif args.command == "tree":
                nodes, _ = harness.hierarchy()
                emit("tree", serial=harness.serial, nodes=[n for n in nodes if n["visible"]])
            elif args.command == "status":
                nodes, _ = harness.hierarchy()
                pid = harness.device_command("shell", "pidof", "-s", APP_ID, check=False).stdout.strip()
                activity = harness.device_command(
                    "shell", "dumpsys", "activity", "activities", check=False
                ).stdout
                resumed = re.search(r"mResumedActivity:.*?\s([^\s}]+/[^\s}]+)", activity)
                emit(
                    "status",
                    serial=harness.serial,
                    package_running=bool(pid),
                    pid=pid,
                    foreground_activity=resumed.group(1) if resumed else None,
                    nodes=[n for n in nodes if n["visible"]],
                    latest=str(harness.output_dir / "latest.png"),
                )
            elif args.command == "tap":
                node = harness.tap_node(
                    "text" if args.text is not None else "description",
                    args.text if args.text is not None else args.description,
                    contains=args.contains,
                    index=args.index,
                )
                emit("tap", node=node)
            elif args.command == "type":
                harness.type_into(
                    "text" if args.text is not None else "description",
                    args.text if args.text is not None else args.description,
                    args.value,
                    contains=args.contains,
                    replace=not args.append,
                )
                emit("typed", value=args.value)
            elif args.command == "key":
                harness.press_key(args.code)
                emit("key", code=args.code)
            elif args.command == "swipe":
                harness.swipe(args.x1, args.y1, args.x2, args.y2, args.duration)
                emit("swipe", coordinates=[args.x1, args.y1, args.x2, args.y2])
            elif args.command == "watch":
                deadline = time.monotonic() + args.seconds
                index = 0
                last_digest = ""
                while time.monotonic() < deadline and index < args.max_captures:
                    png = harness.raw_device_bytes("exec-out", "screencap", "-p", timeout=60)
                    digest = hashlib.sha256(png).hexdigest()
                    if digest != last_digest:
                        index += 1
                        harness.sequence += 1
                        label = f"watch-{index:02d}"
                        path = harness.run_dir / f"{harness.sequence:02d}-{label}.png"
                        harness.ensure_capture_dirs()
                        path.write_bytes(png)
                        latest_tmp = harness.output_dir / "latest.png.tmp"
                        latest_tmp.write_bytes(png)
                        latest_tmp.replace(harness.output_dir / "latest.png")
                        emit("screenshot", label=label, path=str(path), latest=str(harness.output_dir / "latest.png"))
                        last_digest = digest
                    time.sleep(max(0.1, args.interval))
            elif args.command == "logs":
                emit("logs", serial=harness.serial, text=harness.app_logs(lines=args.lines))
            elif args.command == "stop":
                harness.device_command("shell", "am", "force-stop", APP_ID)
                emit("stopped", package=APP_ID)
        emit("complete", run_dir=str(harness.run_dir), latest=str(harness.output_dir / "latest.png"))
        return 0
    except (VisualError, subprocess.TimeoutExpired, OSError) as error:
        print(json.dumps({"event": "error", "message": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
