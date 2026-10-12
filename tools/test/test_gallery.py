"""Unit tests for tools/test/gallery.py and the ui-sizes runner (no emulator needed; run by ci.sh)."""

from __future__ import annotations

import json
import re
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gallery  # noqa: E402
import visual  # noqa: E402

VISUAL_SOURCE = gallery.VISUAL.read_text(encoding="utf-8")
MATRIX_PATH = gallery.ROOT / "app" / "src" / "androidTest" / "assets" / "ui-screen-sizes.json"
# Every string literal in visual.py; f-strings become regexes ({...} -> .+).
LITERALS = set(re.findall(r'(?<![f\w])"([^"\n]+)"', VISUAL_SOURCE))
TEMPLATES = [re.compile(re.sub(r"\\\{[^}]*\\\}", ".+", re.escape(t)))
             for t in re.findall(r'\bf"([^"\n]+)"', VISUAL_SOURCE)]


def visual_can_emit(label: str) -> bool:
    return label in LITERALS or any(t.fullmatch(label) for t in TEMPLATES)


def tiny_png(path: Path) -> None:
    """Writes a valid 4x8 grey PNG without third-party libraries."""
    width, height = 4, 8
    raw = b"".join(b"\x00" + b"\x80\x80\x80" * width for _ in range(height))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def fake_run(root: Path, labels: list[str]) -> Path:
    run = root / "run"
    run.mkdir(parents=True)
    with (run / "events.jsonl").open("w", encoding="utf-8") as events:
        for index, label in enumerate(labels, start=1):
            png = run / f"{index:02d}-{label}.png"
            tiny_png(png)
            events.write(json.dumps({"event": "screenshot", "label": label, "path": str(png),
                                     "visible": [{"text": "RIDE", "description": ""},
                                                 {"text": "", "description": "Zoom in"}]}) + "\n")
            events.write(json.dumps({"event": "ride_map_mode"}) + "\n")
    return run


class RegistryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.registry = gallery.load_registry()

    def test_every_scenario_is_a_real_visual_command(self) -> None:
        commands = set(re.findall(r'add_parser\(\s*"([a-z-]+)"', VISUAL_SOURCE))
        for scenario in self.registry.scenarios:
            args = scenario.command_args(self.registry.defaults)
            self.assertIn(args[0], commands, scenario.id)

    def test_every_registered_label_is_captured_somewhere_in_visual(self) -> None:
        # A renamed capture must fail here, not as a silently missing frame.
        for scenario in self.registry.scenarios:
            for display in scenario.displays + self.registry.shared:
                if display.label is not None:
                    self.assertTrue(visual_can_emit(display.label),
                                    f"{scenario.id}: visual.py never captures {display.label!r}")
                else:
                    prefix = display.pattern.split("*", 1)[0]
                    sample = display.pattern.replace("*", "1")
                    self.assertTrue(prefix in VISUAL_SOURCE or visual_can_emit(sample),
                                    f"{scenario.id}: no visual.py label starts with {prefix!r}")

    def test_placeholders_resolve(self) -> None:
        for scenario in self.registry.scenarios:
            for arg in scenario.command_args(self.registry.defaults):
                self.assertNotIn("{", arg, scenario.id)

    def test_rejects_ambiguous_display(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "r.json"
            bad.write_text(json.dumps({"scenarios": [{"id": "x", "args": ["tree"], "displays": [
                {"label": "a", "pattern": "a*", "title": "A"}]}]}))
            with self.assertRaises(gallery.GalleryError):
                gallery.load_registry(bad)

    def test_rejects_duplicate_scenarios(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "r.json"
            scenario = {"id": "x", "args": ["tree"], "displays": [{"label": "a", "title": "A"}]}
            bad.write_text(json.dumps({"scenarios": [scenario, scenario]}))
            with self.assertRaises(gallery.GalleryError):
                gallery.load_registry(bad)

    def test_optional_scenarios_are_left_out_unless_asked(self) -> None:
        default = {s.id for s in gallery.select_scenarios(self.registry, None, None, False)}
        everything = {s.id for s in gallery.select_scenarios(self.registry, None, None, True)}
        self.assertNotIn("buildings", default)
        self.assertIn("buildings", everything)
        only = gallery.select_scenarios(self.registry, "styles,route", None, False)
        self.assertEqual(["styles", "route"], [s.id for s in only])


class ClassifyTest(unittest.TestCase):
    scenario = gallery.Scenario("s", "G", ("tree",), (
        gallery.Display("Exact", label="a"),
        gallery.Display("Many", pattern="speed-*", minimum=2),
        gallery.Display("Maybe", label="dialog", optional=True),
    ))
    shared = (gallery.Display("Launch", label="00-launched"),)

    def frames(self, *labels: str) -> list[gallery.Frame]:
        return [gallery.Frame(label=label, path=Path(label)) for label in labels]

    def test_complete_capture_has_nothing_missing(self) -> None:
        result = gallery.classify(self.scenario, self.frames("00-launched", "a", "speed-8", "speed-15"), self.shared)
        self.assertEqual([], result.missing)
        self.assertEqual([], result.unregistered)
        self.assertEqual(1, len(result.shared))

    def test_pattern_minimum_and_exact_label_are_enforced(self) -> None:
        result = gallery.classify(self.scenario, self.frames("speed-8"), self.shared)
        self.assertEqual({("a", 0), ("speed-*", 1)}, {(d.key, n) for d, n in result.missing})

    def test_unclaimed_frames_are_unregistered(self) -> None:
        result = gallery.classify(self.scenario, self.frames("a", "speed-1", "speed-2", "surprise"), self.shared)
        self.assertEqual(["surprise"], [f.label for f in result.unregistered])

    def test_first_matching_display_claims_a_frame_once(self) -> None:
        scenario = gallery.Scenario("s", "G", ("tree",), (
            gallery.Display("Specific", label="speed-8"), gallery.Display("Any", pattern="speed-*", minimum=0)))
        result = gallery.classify(scenario, self.frames("speed-8"), ())
        self.assertEqual([1, 0], [len(frames) for _, frames in result.by_display])


class OutputTest(unittest.TestCase):
    scenario = gallery.Scenario("demo", "Demo group", ("tree",), (
        gallery.Display("First <screen>", label="first"),
        gallery.Display("Never captured", label="ghost"),
    ))
    registry = gallery.Registry((scenario,), (), {})

    def test_reads_only_screenshot_events_with_visible_text(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            frames = gallery.read_run_frames(fake_run(Path(tmp), ["first", "extra"]))
            self.assertEqual(["first", "extra"], [f.label for f in frames])
            self.assertEqual(["RIDE", "Zoom in"], frames[0].visible)

    def test_gallery_page_shows_frames_missing_and_unregistered(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            run = fake_run(Path(tmp), ["first", "extra"])
            result = gallery.ScenarioResult(self.scenario, gallery.read_run_frames(run))
            out = Path(tmp) / "out"
            summary = gallery.write_gallery([result], self.registry, out, width=2, inline=False, title="T")
            page = (out / "index.html").read_text(encoding="utf-8")
            self.assertEqual(2, summary["frames"])
            self.assertEqual([{"scenario": "demo", "display": "ghost", "captured": 0, "needed": 1}], summary["missing"])
            self.assertEqual([{"scenario": "demo", "label": "extra"}], summary["unregistered"])
            self.assertIn("First &lt;screen&gt;", page)  # escaped
            self.assertIn("Not captured", page)
            self.assertIn("Unregistered frames", page)
            self.assertEqual(2, len(list((out / "img").iterdir())))
            self.assertEqual(json.loads((out / "gallery.json").read_text())["frames"], 2)
            self.assertEqual(1, gallery.finish(summary, out, allow_missing=False, strict=False))
            summary["missing"] = []
            self.assertEqual(0, gallery.finish(summary, out, allow_missing=False, strict=False))
            self.assertEqual(1, gallery.finish(summary, out, allow_missing=False, strict=True))

    def test_inline_gallery_is_one_self_contained_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            run = fake_run(Path(tmp), ["first"])
            result = gallery.ScenarioResult(self.scenario, gallery.read_run_frames(run))
            out = Path(tmp) / "out"
            gallery.write_gallery([result], self.registry, out, width=2, inline=True, title="T")
            page = (out / "index.html").read_text(encoding="utf-8")
            self.assertIn('src="data:image/', page)
            self.assertFalse((out / "img").exists())

    def test_failed_scenario_fails_the_run(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            result = gallery.ScenarioResult(self.scenario, [], "failed", "exit 1: boom")
            out = Path(tmp) / "out"
            summary = gallery.write_gallery([result], self.registry, out, width=2, inline=False, title="T")
            self.assertIn("exit 1: boom", (out / "index.html").read_text(encoding="utf-8"))
            self.assertEqual(1, gallery.finish(summary, out, allow_missing=True, strict=False))


class UiSizesRegistryTest(unittest.TestCase):
    """The registry's ui-sizes entry must agree with the matrix it drives."""

    def setUp(self) -> None:
        self.registry = gallery.load_registry()
        self.matrix = json.loads(MATRIX_PATH.read_text(encoding="utf-8"))

    def test_surface_minimums_come_from_the_matrix_not_a_second_copy(self) -> None:
        windows = len(self.matrix["sizes"])
        scales = len(self.matrix["fontScales"])
        self.assertGreater(windows * scales, 0)
        scenario = self.registry.scenario("ui-sizes")
        self.assertEqual(("ui-sizes",), scenario.args)
        self.assertEqual({f"ui-size-*-{surface}" for surface in visual.UI_SIZE_SURFACES},
                         {display.pattern for display in scenario.displays})
        for display in scenario.displays:
            self.assertEqual(windows * scales, display.minimum, display.pattern)

    def test_the_registered_command_pins_no_subset_filters(self) -> None:
        # A filter here would silently shrink the gallery instead of covering the matrix.
        scenario = self.registry.scenario("ui-sizes")
        self.assertEqual(["ui-sizes"], scenario.command_args(self.registry.defaults))


class UiSizesFilterTest(unittest.TestCase):
    """CLI filters validate against the matrix and fail loudly when wrong."""

    matrix = {"sizes": [{"id": "a", "widthDp": 1}, {"id": "b", "widthDp": 2}],
              "fontScales": [1.0, 2.0]}

    def test_cases_accepts_matrix_ids_and_an_unreadable_matrix(self) -> None:
        self.assertEqual(["a", "b"], visual.parse_ui_size_cases("a, b", self.matrix))
        self.assertEqual(["a"], visual.parse_ui_size_cases("a", {}))

    def test_cases_rejects_unknown_duplicate_and_empty_filters(self) -> None:
        with self.assertRaises(visual.VisualError) as unknown:
            visual.parse_ui_size_cases("a,phone", self.matrix)
        self.assertIn("phone", str(unknown.exception))
        with self.assertRaises(visual.VisualError):
            visual.parse_ui_size_cases("a,a", self.matrix)
        with self.assertRaises(visual.VisualError):
            visual.parse_ui_size_cases(" , ", self.matrix)

    def test_font_scales_normalize_and_reject_values_outside_the_matrix(self) -> None:
        self.assertEqual(["1", "2"], visual.parse_ui_size_font_scales("2.0, 1.0", self.matrix))
        for bad in ("1.5", "0", "-2", "big", " "):
            with self.assertRaises(visual.VisualError):
                visual.parse_ui_size_font_scales(bad, self.matrix)
        # An unreadable matrix still rejects nonsense; it only cannot compare ids.
        self.assertEqual(["1.5"], visual.parse_ui_size_font_scales("1.5", {}))

    def test_expected_frames_multiply_windows_scales_and_surfaces(self) -> None:
        surfaces = len(visual.UI_SIZE_SURFACES)
        self.assertEqual(2 * 2 * surfaces, visual.ui_size_expected_frames(None, None, self.matrix))
        self.assertEqual(1 * 1 * surfaces, visual.ui_size_expected_frames(["a"], ["2"], self.matrix))
        self.assertIsNone(visual.ui_size_expected_frames(None, None, {}))


class UiSizesOutputTest(unittest.TestCase):
    """Parsing and joining the suite's device output, including partial exports."""

    def test_log_line_parsing_skips_noise_and_dedupes_by_label(self) -> None:
        first = 'I/CurveMaps.UiSizes( 100): {"label": "ui-size-a-font-1-planner", "surface": "planner"}'
        second = 'I/CurveMaps.UiSizes( 100): {"label": "ui-size-a-font-1-ride", "surface": "ride"}'
        repeat = 'I/CurveMaps.UiSizes( 100): {"label": "ui-size-a-font-1-planner", "surface": "wrong"}'
        self.assertEqual("ui-size-a-font-1-planner",
                         visual.ui_size_record_from_line(first)["label"])
        self.assertIsNone(visual.ui_size_record_from_line("--------- beginning of main"))
        self.assertIsNone(visual.ui_size_record_from_line('I/CurveMaps.UiSizes( 1): {not json}'))
        records = visual.ui_size_records_from_logs([first, "noise", second, repeat])
        self.assertEqual(["ui-size-a-font-1-planner", "ui-size-a-font-1-ride"],
                         [record["label"] for record in records])
        self.assertEqual("wrong", records[0]["surface"])  # the last line for a label wins

    def test_instrumentation_failure_is_not_hidden_by_a_zero_exit(self) -> None:
        self.assertIsNone(visual.ui_size_failure_summary("OK (252 tests)\nINSTRUMENTATION_CODE: 0"))
        for output, expected in (
            ("FAILURES!!! Tests run: 252, Failures: 2", "FAILURES!!!"),
            ("INSTRUMENTATION_FAILED: com.curvemaps.app.test/...", "INSTRUMENTATION_FAILED:"),
            ("Process crashed.", "Process crashed."),
            ("Tests run: 252, Failures: 0\nINSTRUMENTATION_CODE: 0", "without reporting OK"),
        ):
            summary = visual.ui_size_failure_summary(output)
            self.assertIsNotNone(summary, output)
            self.assertIn(expected, summary)

    def test_instrumentation_component_matches_only_our_target(self) -> None:
        listing = (
            "instrumentation:com.other.test/androidx.test.runner.AndroidJUnitRunner (target=com.other)\n"
            "instrumentation:com.curvemaps.app.test/androidx.test.runner.AndroidJUnitRunner (target=com.curvemaps.app)"
        )
        self.assertEqual("com.curvemaps.app.test/androidx.test.runner.AndroidJUnitRunner",
                         visual.ui_size_instrumentation_component(listing))
        self.assertIsNone(visual.ui_size_instrumentation_component(
            "instrumentation:com.other.test/Runner (target=com.other)"))

    def test_capture_records_join_json_to_exported_pngs(self) -> None:
        listing = ["cache/ui-size-captures/a.png"]
        payloads = [{"label": "ui-size-a-font-1-planner", "surface": "planner",
                     "path": "/data/data/com.curvemaps.app/cache/ui-size-captures/a.png"}]
        records, problems = visual.ui_size_capture_records(payloads, listing)
        self.assertEqual([], problems)
        self.assertEqual("cache/ui-size-captures/a.png", records[0]["devicePath"])

    def test_capture_records_report_missing_mismatched_and_unknown_frames(self) -> None:
        listing = ["cache/ui-size-captures/a.png"]
        payloads = [
            {"label": "ui-size-ghost-font-1-planner", "surface": "planner",
             "path": "/data/data/com.curvemaps.app/cache/ui-size-captures/ghost.png"},
            {"label": "ui-size-a-font-1-ride", "surface": "planner",
             "path": "/data/data/com.curvemaps.app/cache/ui-size-captures/a.png"},
            {"label": "ui-size-a-font-1-mystery", "surface": "mystery",
             "path": "/data/data/com.curvemaps.app/cache/ui-size-captures/a.png"},
        ]
        records, problems = visual.ui_size_capture_records(payloads, listing)
        self.assertEqual([], records)
        self.assertEqual(3, len(problems))
        self.assertTrue(any("exported PNG missing" in problem for problem in problems))
        self.assertTrue(any("does not end with its surface" in problem for problem in problems))
        self.assertTrue(any("unknown surface" in problem for problem in problems))

    def test_capture_records_fall_back_to_the_basename_and_keep_the_last_duplicate(self) -> None:
        listing = ["cache/ui-size-captures/a.png"]
        payloads = [
            {"label": "ui-size-a-font-1-planner", "surface": "planner", "path": "a.png", "fontScale": 1},
            {"label": "ui-size-a-font-1-planner", "surface": "planner",
             "path": "/elsewhere/a.png", "fontScale": 2},
        ]
        records, problems = visual.ui_size_capture_records(payloads, listing)
        self.assertEqual(1, len(records))
        self.assertEqual("cache/ui-size-captures/a.png", records[0]["devicePath"])
        self.assertEqual(2, records[0]["fontScale"])
        self.assertTrue(any("duplicate" in problem for problem in problems))


class PrebuildApksTest(unittest.TestCase):
    """Built/frozen APK pairs: gallery must never assemble implicitly under --no-build."""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.app = self.root / "app-debug.apk"
        self.test = self.root / "app-debug-androidTest.apk"
        self.app.write_bytes(b"app")
        self.test.write_bytes(b"test")

    def prebuild(self, **kwargs):
        with mock.patch.object(gallery, "DEBUG_APK", self.app), \
                mock.patch.object(gallery, "DEBUG_TEST_APK", self.test):
            return gallery.prebuild_apks(**kwargs)

    def test_no_build_freezes_both_apks_unchanged(self) -> None:
        apks, test_apks = self.prebuild(needed={"bundled"}, apk_dir=self.root / "frozen",
                                        no_build=True, test_variants={"bundled"})
        self.assertEqual(self.root / "frozen" / "bundled.apk", apks["bundled"])
        self.assertEqual(self.root / "frozen" / "bundled-androidTest.apk", test_apks["bundled"])
        self.assertEqual(b"app", apks["bundled"].read_bytes())
        self.assertEqual(b"test", test_apks["bundled"].read_bytes())

    def test_no_build_fails_actionably_when_a_needed_apk_is_missing(self) -> None:
        self.test.unlink()
        with self.assertRaises(gallery.GalleryError) as error:
            self.prebuild(needed={"bundled"}, apk_dir=self.root / "frozen",
                          no_build=True, test_variants={"bundled"})
        self.assertIn("assembleDebugAndroidTest", str(error.exception))
        self.test.write_bytes(b"test")
        self.app.unlink()
        with self.assertRaises(gallery.GalleryError):
            self.prebuild(needed={"bundled"}, apk_dir=self.root / "frozen",
                          no_build=True, test_variants={"bundled"})

    def test_variants_without_on_device_tests_do_not_need_the_test_apk(self) -> None:
        self.test.unlink()
        apks, test_apks = self.prebuild(needed={"bundled"}, apk_dir=self.root / "frozen", no_build=True)
        self.assertIn("bundled", apks)
        self.assertEqual({}, test_apks)

    def test_no_build_skips_the_mapless_variant_instead_of_building(self) -> None:
        apks, test_apks = self.prebuild(needed={"bundled", "mapless"}, apk_dir=self.root / "frozen",
                                        no_build=True, test_variants={"bundled"})
        self.assertEqual({"bundled"}, set(apks))
        self.assertEqual({"bundled"}, set(test_apks))


class ShardingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.registry = gallery.load_registry()

    def test_after_pulls_in_its_predecessor_and_shares_a_chain(self) -> None:
        chains = gallery.build_chains([self.registry.scenario("icon-states")], self.registry)
        self.assertEqual([["route", "icon-states"]], [[s.id for s in chain] for chain in chains])

    def test_every_selected_scenario_lands_in_exactly_one_chain(self) -> None:
        selected = gallery.select_scenarios(self.registry, None, None, True)
        chains = gallery.build_chains(selected, self.registry)
        flat = [s.id for chain in chains for s in chain]
        self.assertEqual(sorted(s.id for s in selected), sorted(flat))
        self.assertEqual(len(flat), len(set(flat)))

    def test_registry_rejects_unknown_after_and_apk(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "r.json"
            for extra in ({"after": "nope"}, {"apk": "debug"}):
                scenario = {"id": "x", "args": ["tree"], "displays": [{"label": "a", "title": "A"}], **extra}
                bad.write_text(json.dumps({"scenarios": [scenario]}))
                with self.assertRaises(gallery.GalleryError):
                    gallery.load_registry(bad)

    def test_mismatched_devices_are_refused(self) -> None:
        same = {"size": "1280x2856", "density": "480", "fontScale": "1.0"}
        gallery.check_coherent({"a": dict(same), "b": dict(same)})
        with self.assertRaises(gallery.GalleryError) as error:
            gallery.check_coherent({"a": dict(same), "b": {**same, "fontScale": "2.0"}})
        self.assertIn("fontScale", str(error.exception))

    def test_shards_keep_chains_together_and_report_in_registry_order(self) -> None:
        selected = gallery.select_scenarios(self.registry, None, None, False)
        chains = gallery.build_chains(selected, self.registry)
        ran: list[tuple[str, str]] = []

        assumed: dict[str, bool] = {}

        def fake_run(scenario, registry, runs_dir, *, serial, timeout, apk=None,
                     test_apk=None, assume_defaults=False):
            ran.append((scenario.id, serial))
            assumed[scenario.id] = assume_defaults
            return gallery.ScenarioResult(scenario, [], "ok", serial=serial)

        original_run, original_reset = gallery.run_scenario, gallery.reset_app_state
        gallery.run_scenario, gallery.reset_app_state = fake_run, lambda serial: None
        try:
            apks = {"bundled": Path("b.apk"), "mapless": Path("m.apk")}
            results = gallery.run_sharded(chains, self.registry, ["emu-1", "emu-2", "emu-3"], Path("runs"),
                                          apks=apks, timeout=1, durations={"nav-camera": 900},
                                          on_result=lambda r: None)
        finally:
            gallery.run_scenario, gallery.reset_app_state = original_run, original_reset
        device = dict(ran)
        self.assertEqual(device["route"], device["icon-states"])
        self.assertLess([s for s, _ in ran].index("route"), [s for s, _ in ran].index("icon-states"))
        registry_order = [s.id for s in self.registry.scenarios if s.id in device]
        self.assertEqual(registry_order, [r.scenario.id for r in results])
        self.assertEqual("nav-camera", ran[0][0])  # longest known chain starts first
        # Only a chain's first scenario may assume freshly reset settings.
        self.assertTrue(assumed["route"])
        self.assertFalse(assumed["icon-states"])

    def test_missing_apk_variant_is_skipped_not_silently_passed(self) -> None:
        chains = gallery.build_chains([self.registry.scenario("first-run")], self.registry)
        original_reset = gallery.reset_app_state
        gallery.reset_app_state = lambda serial: None
        try:
            results = gallery.run_sharded(chains, self.registry, ["emu-1"], Path("runs"),
                                          apks={"bundled": Path("b.apk")}, timeout=1, durations={},
                                          on_result=lambda r: None)
        finally:
            gallery.reset_app_state = original_reset
        self.assertEqual("skipped", results[0].status)
        self.assertIn("mapless", results[0].detail)

    def test_frozen_apk_options_reach_ui_size_filter_validation(self) -> None:
        # Run the real CLI. Invalid cases fail before any emulator action.
        # Placing --test-apk before the subcommand instead fails in argparse.
        scenario = gallery.Scenario(
            "ui-sizes", "Responsive UI",
            ("ui-sizes", "--cases", "unknown-window"), (),
        )
        with tempfile.TemporaryDirectory() as tmp:
            result = gallery.run_scenario(
                scenario, self.registry, Path(tmp),
                serial=None, timeout=10,
                apk=Path(tmp) / "app.apk", test_apk=Path(tmp) / "test.apk",
            )
        self.assertEqual("failed", result.status)
        self.assertIn("unknown window id(s): unknown-window", result.detail)


class StyleGeometryTest(unittest.TestCase):
    """Ride-style distinctness geometry in visual.py (no emulator needed).

    The thresholds mirror AlternativePolicy/DistinctRideStyles, and the debug
    snapshot carries no edge ids, so the check is proximity on the sampled
    polyline. These cases pin that a swapped road is caught while the same
    sampled road is not.
    """

    LAT = -26.7
    LON_START = 152.70
    LON_END = 152.80  # ~9.9 km east-west
    SAMPLES = 100     # ~99 m per segment

    def _route(self) -> list[list[float]]:
        return [
            [self.LAT, self.LON_START + (self.LON_END - self.LON_START) * index / self.SAMPLES]
            for index in range(self.SAMPLES + 1)
        ]

    @staticmethod
    def _offset(route: list[list[float]], first: int, last: int, lat_delta: float) -> list[list[float]]:
        return [
            [lat + (lat_delta if first <= index <= last else 0.0), lon]
            for index, (lat, lon) in enumerate(route)
        ]

    def test_identical_geometry_is_the_same_ride(self) -> None:
        route = self._route()
        self.assertTrue(visual.styles_are_same_ride(route, list(route)))
        shared, stretch = visual.route_geometry_diversity(route, list(route))
        self.assertGreaterEqual(shared, 0.999)
        self.assertEqual(0.0, stretch)

    def test_a_long_parallel_swap_is_a_different_ride(self) -> None:
        route = self._route()
        swapped = self._offset(route, 30, 70, 0.0003)  # ~4 km, ~30 m north
        self.assertFalse(visual.styles_are_same_ride(route, swapped))
        self.assertFalse(visual.styles_are_same_ride(swapped, route))

    def test_a_short_parallel_swap_is_still_the_same_ride(self) -> None:
        route = self._route()
        swapped = self._offset(route, 50, 50, 0.0003)  # ~200 m, below the 750 m floor
        self.assertTrue(visual.styles_are_same_ride(route, swapped))

    def test_a_road_within_tolerance_is_treated_as_the_same_road(self) -> None:
        route = self._route()
        nudged = self._offset(route, 0, self.SAMPLES, 0.00005)  # ~5 m north
        shared, stretch = visual.route_geometry_diversity(nudged, route)
        self.assertGreaterEqual(shared, 0.999)
        self.assertEqual(0.0, stretch)
        self.assertTrue(visual.styles_are_same_ride(route, nudged))

    def test_a_whole_different_corridor_is_a_different_ride(self) -> None:
        route = self._route()
        elsewhere = self._offset(route, 0, self.SAMPLES, 0.0045)  # ~500 m north
        shared, _ = visual.route_geometry_diversity(elsewhere, route)
        self.assertLess(shared, visual.STYLE_SAME_RIDE_SHARE)
        self.assertFalse(visual.styles_are_same_ride(route, elsewhere))

    def test_required_stretch_clamps_five_percent_to_750m_and_3km(self) -> None:
        self.assertEqual(750.0, visual.required_distinct_stretch_m(1_000.0))
        self.assertEqual(750.0, visual.required_distinct_stretch_m(10_000.0))
        self.assertEqual(1_000.0, visual.required_distinct_stretch_m(20_000.0))
        self.assertEqual(3_000.0, visual.required_distinct_stretch_m(200_000.0))


if __name__ == "__main__":
    unittest.main()
