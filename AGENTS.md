# AGENTS.md

Offline-first motorcycle routing app: Kotlin + Compose + MapLibre (map) +
GraphHopper (routing). Source-available under the PolyForm Shield License
1.0.0 (`LICENSE`; name and logo in `TRADEMARKS.md`, contributions need
`CLA.md`, third-party notices in `THIRD_PARTY.md`). Only the owner publishes
the app, under the owner's terms (Google Play is planned), never as a free
public APK. Never commit secrets.

Owner-only operations (the hosted map service, test builds) live in the
private `B-Kingston/curvemaps-server` repository; its `AGENTS.md` is their
runbook.

## Topic docs: read before touching the area

This file holds what every task needs. Details live in `docs/agents/`:

| Doc | Read before changing |
|---|---|
| [testing.md](docs/agents/testing.md) | test suites, fuzzing, perf history, corpus golds, probes, `visual.py`/gallery |
| [dev-environment.md](docs/agents/dev-environment.md) | emulator/adb/Orca use, `tools/android.sh`, log tags, iOS simulator |
| [graph-pipeline.md](docs/agents/graph-pipeline.md) | graph import, GraphHopper version/profile, `MotoGraphImport`, Janino-free weighting |
| [map-assets.md](docs/agents/map-assets.md) | tiles, glyphs/sprites, geocoder index, `.motomap` packing, CI data cache, release assets |
| [routing.md](docs/agents/routing.md) | `ComplexityWeighting`, ride-style stops, road toggles, ride factors |
| [ride-styles.md](docs/agents/ride-styles.md) | seven-ride search (`DistinctRideStyles`, `RideLadder`), alternatives, slider, ghosts |
| [round-trips.md](docs/agents/round-trips.md) | round-trip controls, `RoundTripWish`, `RoundTrips` loops |
| [gpx-import.md](docs/agents/gpx-import.md) | GPX parsing, track following, Open-with |
| [planner-ui.md](docs/agents/planner-ui.md) | planner panes, map toolbar/settings menu, planner camera, welcome carousel |
| [saved-rides.md](docs/agents/saved-rides.md) | saved-route SQLite store, schema, save/load |
| [ride-guidance.md](docs/agents/ride-guidance.md) | ride HUD/data bar, lane guidance, GPS gaps, ride suggestions |
| [media.md](docs/agents/media.md) | ride media panel, sessions, volume, music start, guidance ducking |
| [map-rendering.md](docs/agents/map-rendering.md) | route-line curve shading, B&W ride style, MapLibre backend, POI batching, frame benchmark |
| [regions.md](docs/agents/regions.md) | region installs/updates, Maps screen, map service API, downloads, Load map file |
| [licences.md](docs/agents/licences.md) | Open-source licences screen and its build-time data |
| [ui-theme.md](docs/agents/ui-theme.md) | colour schemes, M3 components and icons, type/shape/motion, settings controls |
| [rater.md](docs/agents/rater.md) | the desktop route rater and agent reviews |

When a change alters what a topic doc says, update that doc in the same
commit. Keep this file to rules that apply to every task.

## Build

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

- `JAVA_HOME` is mandatory and always `./gradlew` (Gradle 9.5.0, AGP 9.3.1,
  Kotlin 2.2.10). Plain `gradle` runs Gradle 9.7 on JDK 26 and breaks the build.
- Output: `app/build/outputs/apk/debug/app-debug.apk`.
- **Bundled vs map-less:** `-Pmoto.bundleData=true|false` (default debug
  `true`, release `false`) decides whether the APK carries `graph-cache` and
  `geocoder` (`BuildConfig.BUNDLED_DATA`, `RegionDatasets.bundledAvailable`;
  stripped in `app/build.gradle.kts`). Every documented test command uses the
  bundled debug build. Release artifacts: `map-assets.md`.
- `local.properties` (gitignored) points `sdk.dir` at `~/Library/Android/sdk`.
- `minSdk = 26` is a hard floor (GraphHopper's jar fails dexing below it).
- A fresh clone has no generated data (graph, tiles, geocoder, glyphs,
  sprites) until the pipeline runs: `map-assets.md`, `graph-pipeline.md`.
- Routine build/install/launch/logs: `tools/android.sh run|debug|logs|stop`.

## Test

```sh
tools/test/ci.sh        # light tier: preflight, all JVM suites, Go modules, assembleDebug; no emulator
tools/test/run-all.sh   # full tier: needs a booted AVD (ANDROID_SERIAL with several devices)

JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest :geocoder-tool:test
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=<fully.qualified.Class>
```

- The light tier runs in GitHub Actions and, after `git config core.hooksPath
  githooks`, as a pre-push hook. There is no lint task.
- Run one Compose-rule class per instrumented invocation (several collide on
  the single UiAutomation service).
- Instrumented test method names are plain camelCase: backtick-spaced names
  fail D8 at minSdk 26.
- Fuzz flags, fixtures, perf history and gold recalibration: `testing.md`.

## Every change

- **Tests are permanent infrastructure.** Every feature, UI element, routing,
  geocoder, storage or data-pipeline change adds or updates tests at the same
  rigor:
  - pure logic: JVM invariant/property tests in the matching package;
  - Android-bound behaviour: instrumented tests;
  - route-visible changes: a corpus entry and recalibration;
  - new composables/controls: content descriptions, 48 dp targets, planner,
    accessibility and fuzz-oracle coverage;
  - weighting/custom-model changes: `MotorcycleProfileTest` and
    `ComplexityWeightingTest` together;
  - storage schema changes: both store-contract suites and the repository
    truthfulness tests.
- **`README.md` must stay true.** Any change to what the app does, how a feature
  behaves, or how it is built, tested, captured (`visual.py` labels) or
  distributed updates `README.md` in the same change, along with the relevant
  topic doc. Correct or remove what the change makes stale; don't just append.
  Before finishing, search `README.md` for the feature's name and controls.
- **Visual coverage.** A feature that can be judged on the emulator gets
  repeatable setup and screenshots in `tools/test/visual.py` (accessible
  text/content descriptions, never coordinates; intermediate states too), and
  every new capture label goes in `tools/test/gallery-displays.json`.
- **Generated-data freshness.** Changing the OSM extract or any pipeline input
  (GraphHopper/Planetiler version, `tools/gh/config.yml`,
  `tools/gh/motorcycle.json`, `tools/gh/MotoGraphImport.java`,
  `tools/tiles/build-tiles.sh`, geocoder source/format) bumps `CACHE_SEED` (and
  `OSM_EXTRACT_DATE` for a new extract) in `.github/workflows/ci.yml` in the
  same commit. A pipeline input change also rebuilds every hosted package, so
  don't change inputs casually (`regions.md`); a new CI extract alone does not.

## Commit as you go

Commit each coherent unit of work (a feature, fix, refactor, docs or tooling
change) as soon as it is done and its tests pass, instead of leaving a pile of
uncommitted changes. One feature or fix per commit, with its tests and doc
updates. Stage by file or hunk (`git add -p` or a filtered patch) so unrelated
work stays out. Do not push, and do not commit half-finished or failing work,
unless asked. If the tree already holds unrelated changes, leave them alone.

## Bounded emulator-test debugging

- Prove test prerequisites before calling a failure a production bug. Map/camera
  tests install and validate their fixture before Activity launch
  (`map/OfflineBasemapRule.kt` as the outermost `RuleChain` rule), then assert
  installed-map and loaded-style semantics before checking guidance or
  gestures. Do not seed files after launch and recreate the Activity to
  compensate for missing setup.
- Never relax production lifecycle, access or readiness guards to make a broken
  fixture pass, and never skip a required test because its fixture is missing.
- After two failures at the same stage, stop rerunning. Report the hypothesis,
  current-run evidence, and one discriminating next check. Change one
  diagnostic variable per run; a longer timeout is not a fix without evidence
  of slow progress.
- After each focused run, report what changed, the exact failure stage (or
  pass), and whether the failure moved. Delegated workers report these
  checkpoints too.
- Tie native/logcat evidence to the current test's start time and app PID
  (prefer the per-test logcat under `app/build/outputs/androidTest-results/`).
- Only one instrumentation or visual driver may own an emulator serial at a
  time, across agents too. Finish or stop the existing run first; compile-only
  checks need no emulator.
- Emulator taps are flaky: after ~3 failed attempts, re-dump the UI tree or
  verify another way (`dev-environment.md`).

## Hard constraints

Each has burned time before. The linked doc has the why.

- **Offline:** routing, search, rendering, saved rides and media are fully
  offline. The only network code is `app/src/main/java/com/curvemaps/app/region/net/`
  (region downloads), enforced by `OfflineGuaranteeTest` (`regions.md`).
- **Graph:** the app never imports a PBF on the device. `graphhopper-core` in
  `gradle/libs.versions.toml` equals the import jar in `tools/gh/`. The profile
  is `motorcycle` everywhere and hashes to `motorcycle|198752012`. Import with
  `tools/gh/MotoGraphImport.java`, never the stock `-jar … import`
  (`graph-pipeline.md`).
- **Janino does not run on ART:** a custom-model change regenerates
  `MotorcycleWeightingHelper.java` and changes five files together, then
  re-imports the graph (`graph-pipeline.md`).
- **No LGPL/GPL in the APK:** the Osmosis PBF jar stays excluded
  (`checkNoOsmosisInApp`, `OsmosisExcludedFromAppTest`); never weaken the guard
  (`graph-pipeline.md`, `licences.md`).
- **Two copies stay in sync:** geocoder `Text.kt` (tool ↔ app) and
  `poi_keywords.tsv` ↔ `PoiKeywords.kt`; `--check-sync` verifies (`map-assets.md`).
- **Routing:** the straightness penalty stays squared; never reintroduce
  `w_custom - w_fastest`; every term stays non-negative so the Fastest bound is
  admissible; plans are deterministic (no visit-history penalties). Retune with
  probes and rater replay, never by loosening budgets to manufacture a pass
  (`routing.md`, `ride-styles.md`).
- **Colours and controls:** raw `Color(0x…)` only in `ui/theme/Palette.kt`;
  screens read `MaterialTheme.colorScheme` / `CurveMapsTheme.colors` and use
  stock Material 3 components with their ripples and state layers (never
  disable the ripple); icons are `R.drawable.ms_*` Material Symbols
  (`ui-theme.md`).
- **MapLibre:** GeoJSON is `org.maplibre.geojson.*`; glyphs/sprites load via
  `asset://`, never `file://`; never add a per-feature `symbol-sort-key`
  (`map-rendering.md`, `map-assets.md`).
- **Never DRM `.motomap` files** (ODbL); paid value stays on the server
  (`regions.md`).
- `slf4j-android` stays 1.7.36 (2.x has no Android binding).
- Router init is lazy (first START): the graph loads on `Dispatchers.IO`, never
  the UI thread.
- `RouteSearchCoordinator`, `GeocodeController` and the `UiSemantics.kt` keys
  are deliberate test seams: keep their state transitions and observable
  values stable.
