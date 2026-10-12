# Testing

The tiers, commands and rules are in the root `AGENTS.md`. This file holds the
suite inventory and the details of the fuzz, perf, corpus and visual tooling.

## Tiers

- **Light tier** `tools/test/ci.sh`: `tools/test/preflight.sh`, every JVM suite,
  the Go modules (when Go is present) and `assembleDebug`. No emulator. GitHub
  Actions (`.github/workflows/ci.yml`) runs it after regenerating the gitignored
  assets from the pinned pipeline (see `map-assets.md` for cache keys); the
  `githooks` pre-push hook runs it locally.
- **Full tier** `tools/test/run-all.sh` (needs a booted AVD; set
  `ANDROID_SERIAL` with several devices): the light tier, then pre-warms graph,
  tiles and geocoder, proves routing and map rendering in airplane mode, seeds
  the Queensland fixtures, and runs every instrumented class in its own
  connected task with seeded fuzzing, keeping each class's XML and logcat.
- **Airplane-mode proof by hand:** `adb shell cmd connectivity airplane-mode enable`,
  run `OfflineMapSmokeTest` and `RouteCorpusTest`, then
  `adb shell cmd connectivity airplane-mode disable`.
  `run-all.sh` restores airplane mode on failure.
- `tools/test/process-death.sh` is a manual pre-release check: kills and
  relaunches the app, prints the restored fields and dial level.

## Instrumented suites

- **Core:** `RouteCorpusTest`, `GraphCopyTest`, `OfflineMapSmokeTest`,
  `CarouselMapConsistencyTest` (one route card ↔ one map line),
  `SqlSavedRouteStoreTest`, `GeocoderOnDeviceTest`, `DarkRideStyleTest`.
- **Compose UI:** `PlannerPanelTest`, `RideStylePlannerTest`,
  `RideStyleSliderTest`, `SaveBubbleTest`, `SavedRoutesSheetTest`,
  `SearchDebounceTest`, `LifecycleRecreationTest`, `AccessibilityTest`,
  `FontScaleTest`, `CommonScreenSizeTest`, `SavedRouteFlowTest`,
  `RouteSettingsApplyTest`, `OpenSourceLicencesTest`. Run one Compose-rule class
  per invocation (several collide on the single UiAutomation service).
- **Fuzz/perf:** `FuzzCampaignTest`, `FuzzReplayTest`, `FuzzMinimizerTest`,
  `MemoryStressTest`, `PerfSmokeTest`.
- Feature suites are listed in each topic doc.
- Wrap Compose content in `CurveMapsTheme`, never a bare `MaterialTheme`.
- Slider gestures go through the shared `KnobRobot` (`ui/KnobRobot.kt`,
  historical name kept for fuzz compatibility). Never synthesize slider drags
  inline; retune gesture physics there only. Instrumented suites reach the
  slider through `ui/PlannerFlow.kt` (`searchRideStyles`, `awaitRideStyles`,
  `backToEndpoints`) and the route card through `ui/RouteCards.kt`.
- `RouteSearchCoordinator`, `GeocodeController` and the keys in
  `UiSemantics.kt` are deliberate test seams: keep their state transitions and
  observable values stable.

### Fixtures

- Map/camera tests use `map/OfflineBasemapRule.kt` as the outermost `RuleChain`
  rule: it installs and validates the small Monaco fixture before Activity
  launch (enough for camera behaviour, not Queensland rendering/POIs). Planner
  suites that drive `RouteScreen` (`PlannerPanelTest`, `FontScaleTest`,
  `RideStylePlannerTest`, `LifecycleRecreationTest`, `SavedRouteFlowTest`,
  `CarouselMapConsistencyTest`, `FuzzReplayTest`) use it too: without a basemap
  the first-run "map needed" dialog is a second window (a second Compose root,
  which also takes Back), so `onRoot()` is ambiguous and Back closes the dialog
  instead of the screen.
- `BuildingRenderingTest` and `SymbolPriorityOnDeviceTest` need real Queensland
  POIs/buildings: seed `files/fixtures/queensland.motomap` with
  `ANDROID_SERIAL=<serial> tools/test/seed-map-fixture.sh` before a direct run
  (the full runner does this per class after APK removal).
- `QueenslandPackageOnDeviceTest` needs `tools/test/seed-package-fixture.sh`
  (`run-all.sh` runs it).
- The fuzz campaign's setup requires installed-map and loaded-style semantics
  before riding; the Monaco fixture is enough for its camera/control assertions.

## Fuzzing

- Run with `-Pandroid.testInstrumentationRunnerArguments.fuzzSeeds=42,1337
  -Pandroid.testInstrumentationRunnerArguments.fuzzSteps=120` (the full tier's
  settings). Replay with `...fuzzSeedFile=media-ride.json`; checked-in seeds
  include `initial.json`, `media-ride.json`, `maps-settings.json`,
  `oss-licences.json`.
- **One action vocabulary:** `fuzz/FuzzActions.kt`. Every `FuzzAction`
  declares its `wireName`, `target` and `args()`; `fuzz/FuzzActionCodec.kt` is
  the only JSON mapping, used by `FuzzSeed`, minimisation and
  `FailureRecorder`. `FuzzActionCodecTest` proves the round trip for every
  action, the checked-in seeds, and everything `FuzzRandom` generates. Add an
  action to `FuzzActions` plus its weight in `FuzzWeights`, never by editing a
  private encoder.
- Every action stays inside the app. Paths that leave it (notification-access
  settings, document pickers, Open-with, music-app launches) are not fuzz
  actions; `MediaAccessRecoveryOnDeviceTest` owns the notification-access path.
- **No-op accounting:** `FuzzExecutor.execute` returns `FuzzOutcome`
  (`PERFORMED` / `UNAVAILABLE` / `NO_SUCH_TARGET`), and `CoverageTracker`
  credits a target only when the step reached the UI. The `FUZZ_REPORT` line
  prints `effective=N/M no-op`.
- **Coverage gates:** `fuzzMinEffectiveSteps` (10), `fuzzMinStates` (5),
  `fuzzMinMediaPanelStates` (0, off), `fuzzRequireTargets` (comma-separated
  `UiTarget` names). Raise them when adding actions. A soft warning firing on
  more than `fuzzWarnFraction` (0.25) of a seed's steps fails the campaign.
- **Waiting:** `fuzz/FuzzWait.kt` holds the gentle semantics polling shared by
  the campaign and the minimiser. Never reintroduce `rule.waitUntil` in a fuzz
  suite: it forces compose-idle sync, which can run measure/layout inside a
  live map draw pass and crash with a framework IAE on the Pixel_10_Pro AVD. A
  poll timeout is still a hard failure.

## Performance history

`PerfSmokeTest` keeps absolute ceilings and runs each warm measurement twice;
the repeat must stay within 3× of the first (a package wipe after every
connected task makes on-device history impossible). Warm routes are requests
the router has not cached (the reverse of the cold route, then the same with
unpaved roads blocked): repeating the cold request measured a 1 ms cache hit.
`planMs`/`planCpuMs` are one seven-stop plan (Samford to Brisbane CBD) through
`RideStyleOptions`: wall time until the last stop is ready and the process's
CPU time meanwhile, the plan's battery cost. The cross-run comparison
is in `run-all.sh`: it appends `perf.json` metrics to
`build/perf.history.jsonl` and fails when a metric exceeds its factor over the
previous entry (cold 2.5×; warm and curve-weighted routing and the plan
1.75×; geocoder load/query and ride-suggestion search 2.0×). Desktop plan cost
per stage: `PlanCostProbe` below. Delete the history file after a
deliberate slowdown (e.g. a graph rebuild) to re-baseline. The map frame
benchmark is in `map-rendering.md`.

## Route corpus gold baselines

`RouteCorpusEntry.goldDistanceMeters`/`goldDurationMillis` pin measured
Fastest values and alert at ±15%
(`RouteCorpusTest.fastestRoutesMatchCommittedGoldBaselines`). After a
deliberate OSM-data or weighting change, run the `@Ignore calibrationProbe` and
fill the golds from its `GOLD_PROBE` lines.

## Opt-in desktop probes

JVM tests that load the real graph, skipped unless their env var is set. Grep
their output for the tag.

| Probe | Env | Measures |
|---|---|---|
| `CurvyCorridorProbe` | `CURVE_PROBE=1` | each style's alternatives, curvy share, budget verdict |
| `RideFactorsProbe` | `FACTOR_PROBE=1` | hills / stops & towns effects |
| `JunctionJogProbe` | `JOG_PROBE=1` (`JOG_CORRIDORS`) | junction jogs per stop (plain optima fail on any within the small-loop perimeter; planned rides and ride factors are reported) |
| `CurveShadeProbe` | `CURVE_SHADE=1` | route-line curve shading per corridor |
| `PalmwoodsKenilworthTest`, `SkylinePalmwoodsTest` | `STYLE_CORRIDOR=1` | seven-stop ladders on real corridors |
| `RoundTripProbeTest` | `ROUND_TRIP=1` (`ROUND_TRIP_STARTS`, `ROUND_TRIP_LENGTHS`) | round-trip loops |
| `GpxFollowProbeTest` | `GPX_FOLLOW=1` (`GPX_FIXTURE_DIR`) | followed GPX tracks |
| `PlanCostProbe` | `PLAN_COST=1` (`PLAN_COST_CORRIDORS`, `PLAN_COST_REPEAT`) | seven-stop plan cost per corridor: searches, CPU and visited nodes per stage, wall time, a fingerprint of the chosen rides (a speed-only change must keep every fingerprint) |
| `RouteRaterServer` | `RATER=1` | the desktop rater (`rater.md`) |

Example: `CURVE_PROBE=1 ./gradlew :app:testDebugUnitTest --tests
'*CurvyCorridorProbe*' -i | grep CURVE_PROBE`.

## Visual captures (`tools/test/visual.py`)

- Every emulator-judgeable feature gets repeatable setup/control and screenshot
  coverage here, driven by accessible text/content descriptions (never
  hard-coded coordinates), with intermediate states captured.
- Register each capture label in `tools/test/gallery-displays.json`;
  `tools/test/gallery.py run` renders all displays into one HTML contact sheet
  and fails on missing or failed displays. `tools/test/test_gallery.py` checks
  the registry against `visual.py`.
- Keep the CLI and README examples current. Subcommands are listed in each
  topic doc.
