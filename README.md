# CurveMaps

Offline-first motorcycle routing for Android.
Kotlin + Jetpack Compose + MapLibre Android SDK (map) + GraphHopper core (routing).

Android application ID: `com.curvemaps.app`. This replaces `com.organicmoto.maps`;
Android installs CurveMaps as a separate app, so saved rides, settings, map imports,
and notification access from the previous app are not transferred automatically.

The app makes **zero runtime network calls** for routing, search and map
rendering. The distributed APK carries only the style, glyphs and sprites; the
map itself is **one `.motomap` file** (basemap tiles + routing graph + place
search) that you load once with **Load map file**. Releases ship `curvemaps.apk`
and `queensland.motomap`. (Debug builds still bundle Queensland's graph and
geocoder by default so the test tiers work; `-Pmoto.bundleData=false` builds
the map-less APK.)

## Features

- **Offline basemap** — OpenMapTiles vector tiles, delivered inside the
  `.motomap` package (or, legacy, as a bare PMTiles archive) and rendered with
  the APK's local style.
- **Offline place search** — From/To fields use a prebuilt on-device geocoder
  index. The accessible current-location action in From uses a fresh device fix
  and requests location permission when needed; raw `lat,lon` entry still works.
  The key is a toggle: while From holds the current location it shows selected
  (M3's primary colours, a filled pin), and a second tap clears From. Tapping
  into From to edit keeps the current location unless another place is typed or
  picked. Results open as the top of the floating planner panel (the panel grows
  upward and the map controls make room, so the sheet never covers or cuts the
  fields): a "Searching From" chip and, for From, a **Use current location**
  chip, two-line rows with a leading symbol (the typed part in bold), fading out
  where more rows scroll, and a note that the search runs on the phone. On a
  window of 600 dp or more the panel is a 560 dp island, centred at the bottom.
  GPS and network location are requested together until GPS is ready. GPS
  requests use a 500 ms interval while moving or acquiring satellites. Healthy
  GPS then runs alone. Ride mode keeps the 500 ms request when stopped or when
  GPS fixes stop. Outside ride mode, healthy GPS slows to 15 seconds after
  60 seconds without movement. Android may deliver fixes less often than
  requested. GPS acquisition also works without network location.
  During a ride, the No GPS banner appears after three seconds without a fresh
  location fix. Route estimation alone does not show it. Fresh fallback fixes
  hide the banner even when guidance keeps its more precise route estimate.
- **Offline motorcycle routing** — GraphHopper with a motorcycle custom model,
  shipped as a pre-compiled weighting helper because Janino bytecode cannot load
  on ART.
- **Ride style slider** — an M3 wavy line (primary) with seven humps that runs from
  the Fastest dot to the Max twisty dot (the captions under it start and end
  there), a slim pill handle, a flat track for styles still being searched, and seven
  haptic stops: Fastest, Mostly fast, Balanced, Curvy, Twisty, Very
  twisty, Max twisty. Drag toward a stop to build the haptic pulse, then feel it
  click into place. Each stop is one calibrated curve preference that keeps
  growing (it never saturates). Curves count on roads built for riding: the
  bends of town streets (residential, service) earn no curve preference, so a
  curvy style does not wander through estates, and gravel earns half, so a
  good sealed road wins over an equally curvy gravel one. Junction structure
  earns none either: slip lanes, roundabouts and every edge of a loop shorter
  than 500 m (junction triangles, carriageways split at an intersection,
  interchange pieces, town blocks), so a curvy style never leaves a road for a
  slip lane and comes straight back onto it. The slider appears only after a search: START
  slides the From/To fields away for the ride styles, and the wave grows out
  as the styles are ready (Fastest at once, the other six together when the
  search is done), with a back button beside it to return to the fields. All
  seven searches start up front. If a style repeats
  a lower stop, the search increases the curve weight, then applies bounded
  penalties to roads already used by lower stops (never to junction
  structure, which would make it jog round the penalised piece), then looks around the
  corridor through via points on nearby two-way through roads (never a
  motorway or one carriageway of a divided road), taking the lightest detour
  at the style's own curve preference that adds curves. No style's ride goes
  round a loop: it never passes a point of its road twice and never leaves a
  road to come back onto it near where it left (round a block); a loop of at
  least 3 km and a tenth of the ride is shown only when the style would
  otherwise repeat a lower stop. Each accepted ride must
  have a continuous different stretch of at least 5% of its distance, bounded
  to 750 m–3 km, against every lower stop, never goes into a side road and
  straight back out (a U-turn where the start or finish snapped onto the wrong
  carriageway, or one the style's own best ride makes too, does not count),
  and is no straighter than the stop below.
  Time, distance, and local-detour limits apply, budgeted as
  for at least a six-minute ride so a short hop still has room; when a stop's
  budget holds no such ride, a slightly longer ride that is no straighter is
  shown, then the curviest one within the budget, then the gentlest ride
  beyond it, then a ride a lower style found but did not show, because every
  stop is a ride of its own. Those searches gather the candidates; the seven
  rides are then chosen together from everything they found, so the curviest
  road goes to the style that wants it most instead of the first style that
  meets it, Mostly fast and Balanced stay close to their quickest ride, and
  each style climbs in curviness above the one below where the roads allow
  (the choice is scored by how an agent reviewer judged thousands of
  earlier rides). The planner never offers a
  duplicate or a tiny road swap as a new ride; only a road with nothing else
  around it (a single road in and out) repeats the stop below. Each route card
  (tertiary container, with a meter of the curvy share) shows its curvy
  distance share, time, and distance, and says how much
  gravel the ride includes ("4.7 km gravel"). Its bookmark button saves the
  ride in one tap (a small bubble confirms "Saved" or offers a retry); a
  long-press opens the same bubble for a deliberate save. While a style is
  searched the card is a "Finding this ride…" placeholder with M3 Expressive's
  loading indicator, the Ride button waits, and a wavy progress bar runs. When the
  window is short (landscape, large text) the card and slider scroll while the
  time and Ride stay pinned below them; on a narrow phone at large text Ride drops
  under the time and takes the full width. Road types are separate
  settings: **Avoid motorways** and **Block unpaved roads** (which keeps every
  style off gravel, so the note disappears).
  On the colour map the route line itself shows where the curves are: it
  eases from the route's orange toward magenta on the ride's most curvy
  stretches (the bends the card's "N% curvy" counts, graded by how tight they
  are and averaged over about a kilometre), full magenta on the twistiest
  roads and back to orange over a short fade either side. Turns at junctions,
  roundabouts, ramps, motorways and town streets never colour it, so a Fastest
  ride through town stays orange while a Max twisty ride lights up its range
  roads. A gravel stretch stays grey and fades in from the line's colour where
  it starts. The black-and-white ride map keeps its single white route.
- **Round trip** — the loop toggle beside To plans a ride that starts at From
  and comes back to it. To gives way to a settings card: a segmented button
  chooses **Ride time**, **Distance** or **Back by**, −/+ step the amount
  (15 min; 5 km up to 50 km, then 10 km; 15 min–8 h or 10–400 km) beside the
  value and its "≈ 60 km, back to From" line, and preset chips (30 min–4 h, or
  25–200 km) set it in one tap; the last length is remembered. The seven ride styles still apply, each a
  different loop: a loop is From plus two corners 60° apart, ridden straight
  through (never a turn-around), and each style tries eight directions and
  keeps the loop it likes best that is close to the wished length, rides
  little road twice, and is no straighter than the style below. Only the
  street home may be ridden twice; a corner that pulls the ride off a road and
  back onto it (a lasso) is moved onto that road. Corners move
  off dead-end roads and out of the bay or the forest onto the nearest road a
  ride can pass through. Saved round trips reopen as round trips, and
  guidance keeps riding the loop's corners after a missed turn.
- **GPX files** — open a `.gpx` with **Open with** or **Share** from Files,
  mail or a chat app (or the settings menu's **Import GPX route**) and it is
  planned at once. A detailed track (a recorded ride, or a planner's exported
  track) is ridden road for road: shaping points along it carry the track's
  direction so a divided road cannot snap onto the other carriageway, a
  U-turn in the track gets a point of its own, and the route is checked
  against the file and pinned back wherever it left it. The ride-style
  slider gives way to a note saying the route rides your track, or how much
  of it is missing (a road the map lacks, or one Route settings blocks).
  A short list of stops (a planner's route points or a waypoint list) is
  routed between the stops in your ride style, slider and all. Waypoints
  that lie beside the ride (a café 2 km away, a lookout) are left out
  instead of pulling the ride to them. The import survives Back → START,
  rotation and the app being closed in the background; **Edit** gives the
  fields back. Points with broken coordinates are skipped; a file that
  is not a GPX, or is cut short, gets a plain message. The **Preview route**
  bottom sheet shows how the file is ridden (a green "Following your track"
  card, or a neutral one when it is routed between stops) and a timeline of
  Route start, Waypoints and Route finish whose centred row drives the map;
  **Edit** gives the fields back and **Ride** starts guidance once a route
  is shown.
- **Saved rides** — tap the bookmark on a route card to save it (long-press opens the
  save bubble), then open the saved
  rides page from the map toolbar's bookmark. It is a Material 3 page (top app bar with the
  ride count) of cards: a tile-free mini-map with the ride style, From → To,
  time and distance, a chip for each planning choice the ride used (avoid
  motorways, block unpaved roads, hills and elevation, avoid stops and
  towns), its comments with **Add comment**, **Open** to reload it as an
  editable plan and a delete button that asks first (an error-coloured Delete,
  like the Maps delete dialog).
- **Off-route recalculation** — when the rider leaves the route, guidance
  says "Recalculating the route" after three moving GPS fixes beyond about
  50 m from the line (about 1 s if the phone delivers fixes every 500 ms;
  a stopped rider needs eleven). The new route starts at the rider's own
  position and leaves in the rider's direction of travel, so it continues ahead
  instead of sending the rider back to the road just left. Remaining shaping points and round-trip
  corners carry over.
- **Ride media control center** — while riding, the data bar's far-left button
  opens a compact Material 3 player panel above the bar (dark scheme): an
  album tile with "app · state", track and artist, a connected 80 dp transport
  group (previous, explicit state-driven play/pause, next), phone-or-player
  volume (round step buttons around a read-only slim-handle level slider),
  optional player selection, a **Music apps** row, and a close button ringed by
  the inactivity countdown. It uses the platform `MediaSessionManager`
  (no Spotify SDK, no privileged access, no network); the user grants
   notification access from an optional startup prompt (only when not already
   granted), and a clearly labelled limited
  system-media-key fallback covers session discovery being unavailable. When
  the playback state is genuinely unknown (no player, or a player that reports
  no state) the panel shows explicit Pause and Play controls instead of a
  toggle whose label would have to lie. System Back closes the panel without
  leaving the ride. The panel closes 15 seconds after the last panel
  interaction; touch, scroll, and control actions restart its inactivity timer,
  while playback and volume observations alone do not. The close button's ring
  shows remaining time. The data bar floats over the map as a rounded island like
  the planner panel, above the system navigation bar, and the panel grows
  seamlessly out of its top edge. Every control is a glove-sized
  target (64 dp; 80 dp for the transport keys) with visible separation; the data bar lays
  out its five readouts (speed, time left, distance left, arrival clock, pace) as
  one row of equal cells, a speed-plus-2×2 grid or a full-width grid, always at
  one value size, instead of shrinking them to an ellipsis on narrow screens or
  large font scales. Volume always works through the local music
  stream, and the panel follows the real readback when hardware keys change it
  while it is open. The panel lists installed music apps, with Spotify first.
  **Start** asks the player to play, even without an active session; **Open**
  lets you sign in or choose music. A player that refuses third-party playback
  connections (Spotify does) first gets one play press on its media button,
  the same signal a headset sends, so music can start without leaving the
  ride. Only if it stays silent for 3 seconds does its own play screen open.
  Whenever the panel opens a player's screen (that fallback, or **Open**), the
  ride shrinks to a Picture-in-Picture window over the player: the map and the
  next turn stay in view, and a tap on the window, then its full-screen
  button, brings the full ride back. Recent Android versions do not let the
  ride bring itself back over another app, so that tap is the way back.
  Requests are shown separately from observed Playing state; the player
  handles its own login and connectivity.
- **Black-and-white ride map** — the dark-mode toggle in the ride toolbar switches the
  ride surface to a monochrome palette for night riding: solid grayscale
  streets (round caps/joins, one layer per drivable class including
  service/track roads, widths that keep growing from z14 to z18 where the
  separately distributed basemap stops) with no pedestrian-path or sidewalk
  clutter, readable street-name labels that stay upright in the 58° guidance
  tilt, and the white route line on its dark casing.
- **Startup location** — requests foreground precise or approximate location
  on first open, starts the offline location stream after approval, and centers
  the map on the first fix. Android retains a normal grant until it is revoked.

## Building

Requires JDK 17 and an Android SDK.

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

`./gradlew assembleRelease` builds the unsigned release APK, shrunk and
optimised with R8 (`app/proguard-rules.pro` keeps GraphHopper and Jackson,
which load a graph's stored profile and encoded values reflectively). Measured
on the emulator against the debug build (2026-10-11, same data, OpenGL map):
cold start to the map 3.7 s → 1.3 s, memory at idle 138 MB → 77 MB PSS, dex
45 MB → 7 MB, and a seven-stop Brisbane → Toowoomba plan 101 s → 37 s (the
release build is not debuggable, so ART compiles it fully). The tester and
release-asset APKs are still debug builds.

APKs carry MapLibre's native renderer for arm64-v8a, armeabi-v7a and x86_64
only (`ndk.abiFilters`); 32-bit x86 was 11 MB of every APK.

The repository includes the Gradle wrapper and its wrapper JAR.

The UI is Material 3 with the Expressive components: a Fidelity colour scheme
around the brand blue (light for the planner and settings, dark for the ride
chrome and welcome carousel), stock M3 type, shapes, ripples and state layers,
and Material Symbols Rounded icons as vector drawables (`R.drawable.ms_<name>`).
`tools/icons/fetch-symbols.sh <name> …` adds an icon (names are recorded in
`tools/icons/symbols.txt`); see [docs/agents/ui-theme.md](docs/agents/ui-theme.md).

## Running and debugging from the CLI

`tools/android.sh` builds, installs, launches, and logs the app.

```sh
tools/android.sh run
```

If no Android device is online, `run` starts the `Pixel_10_Pro` emulator.
Set `ANDROID_AVD` to choose another installed emulator. Set `ANDROID_SERIAL`
to choose a connected device.

To build and launch the app, then stream its logs:

```sh
tools/android.sh debug
```

To stream logs from an app that is already running:

```sh
tools/android.sh logs
```

Press Ctrl-C to stop the log stream. This CLI shows app-process logs. It does
not attach a source-level debugger for breakpoints. The script reads the SDK
path from `ANDROID_SDK_ROOT`, `ANDROID_HOME`, or `local.properties`. It uses
Homebrew JDK 17 when `JAVA_HOME` is unset and that JDK is installed.

### Screenshot gallery (every registered display)

`tools/test/gallery.py` runs every scenario registered in
`tools/test/gallery-displays.json` (each one a `visual.py` command plus the
frame labels it must capture) on one emulator, then writes
`build/gallery/<run-id>/index.html`: every frame grouped by screen, with a
filter box, a lightbox (arrow keys to step), and each frame's on-screen text.
The top of the page, and the exit status, flag registered displays that were
not captured, frames no display claims, and failed scenarios.

```sh
tools/test/gallery.py list                                # what is registered
tools/test/gallery.py run                                 # everything but optional scenarios
tools/test/gallery.py run --only media-controls,styles --no-build
tools/test/gallery.py run --include-optional --serial emulator-5554 --inline
tools/test/gallery.py build build/visual-inspection/<run-id> ...   # from earlier runs
```

`--inline` embeds the images so `index.html` is one shareable file. When
`visual.py` gains a capture, register it in `gallery-displays.json`;
`tools/test/test_gallery.py` (run by `ci.sh`) fails if a registered label is
one `visual.py` can no longer produce.

A scenario whose command accepts `--test-apk` (currently `ui-sizes`, which runs
JUnit on device) also receives a frozen instrumented APK: `gallery.py` builds
`:app:assembleDebug` and `:app:assembleDebugAndroidTest` once per needed
variant, freezes both files, and passes the pair to every shard, so all devices
install the identical build. `--no-build` never assembles anything; it fails
with the missing artifact's path instead.

### Agent-driven visual routing

`tools/test/visual.py` boots or reuses the ADB emulator, installs the debug app,
and provides repeatable UI controls that leave the app open for inspection.
To route the default Brisbane CBD to Mount Glorious corridor and enter ride
mode:

```sh
tools/test/visual.py route \
  --from '-27.4698,153.0251' \
  --to '-27.3353,152.7720'
tools/test/visual.py current-location --to '-27.3353,152.7720' --plan-only
```

The `current-location` scenario captures the request and selected location,
then reports elapsed time from emulator fix injection to selection.

Use place names or coordinates, and set the route controls on the same command:

```sh
tools/test/visual.py route \
  --from 'Brisbane' \
  --to 'Mount Glorious' \
  --style twisty \
  --avoid-motorways \
  --block-unpaved
```

Compare every ride style on one plan. The command captures each route card,
exports its geometry, and checks all 21 pairs for meaningful differences:

```sh
# Captures all seven stops and the intermediate drag frames.
tools/test/visual.py styles --from 'Palmwoods' --to 'Kenilworth'
```

Look at the route line's curve shading on one ride style (default Max twisty):
the planner's overview, where the twistiest stretch begins and the colour
eases in, and the twistiest bends close up (`curve-shading-*`):

```sh
tools/test/visual.py curve-shading --from '-27.4698,153.0251' --to '-27.3353,152.7720'
tools/test/visual.py curve-shading --from 'Caloundra' --to 'Kenilworth' --style twisty
```

The real-graph probe prints, for every style on five Queensland corridors,
how much of the line is tinted and fully coloured and which roads those are:

```sh
CURVE_SHADE=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew \
  :app:testDebugUnitTest --tests '*CurveShadeProbe*' -i | grep CURVE_SHADE
```

Measure what a seven-stop plan costs on the real graph: searches, CPU time and
visited nodes per stage, wall time, and a fingerprint of the chosen rides per
corridor. A change meant only to make routing faster must leave every
fingerprint unchanged:

```sh
PLAN_COST=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew \
  :app:testDebugUnitTest --tests '*PlanCostProbe*' --rerun -i | grep PLAN_COST
```

Open a GPX file the way another app hands it over and capture the followed
track, the GPX rows after Back, the preview, a rotation, START again, a
shared stop list (slider kept) and a refused non-GPX file:

```sh
tools/test/visual.py gpx
tools/test/visual.py gpx --file ~/Downloads/sunday-ride.gpx
```

Capture the Saved routes page: the empty state, two rides saved from the route
card (`--style` and `--second-style`, default twisty), the list, a comment
added through the composer, the delete confirmation (cancelled, nothing is
deleted) and Open loading a ride into the planner. It deletes the app's
`saved_routes.db` first, so it starts empty (`saved-routes-empty`,
`saved-routes-list`, `saved-routes-comment-composer`, `saved-routes-commented`,
`saved-routes-delete-confirmation`, `saved-routes-delete-cancelled`,
`saved-routes-opened`):

```sh
tools/test/visual.py saved-routes --from '-27.4698,153.0251' --to '-27.3353,152.7720'
```

Plan a round trip from one start and capture all seven loops; the run
fails if a loop does not end where it started or two stops are the same ride:

```sh
tools/test/visual.py round-trip --from '-27.3727,152.8864' --minutes 60
tools/test/visual.py round-trip --from 'Samford' --distance 80
tools/test/visual.py round-trip --from 'Samford' --back-by
```

It also captures the measure selector in the round trip settings card
(`round-trip-03-measure-selector`), the picked measure, a stepper press and the
stepped amount. The real-graph probe resolves round
trips from five SEQ starts at four lengths and checks the same promises:

```sh
ROUND_TRIP=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew \
  :app:testDebugUnitTest --tests '*RoundTripProbeTest*' -i | grep ROUND_TRIP
```

The current route changes as the slider reaches each level during a drag.
The map keeps the previous two routes as grey ghost lines below it.
The most recent ghost has 55% opacity. The older ghost has 34% opacity.
Ghosts do not select routes or change the camera bounds.
The `style-drag-N` frames capture the route before the finger releases.
Cancelling a drag restores the starting level and route.

Each `style_ready` event names a `style-N-NAME.json` route snapshot beside
the screenshots. The visual check uses a 12 m road-proximity tolerance.
The graph-based regression compares road IDs directly:

```sh
STYLE_CORRIDOR=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew \
  :app:testDebugUnitTest --tests '*PalmwoodsKenilworthTest*'
```

The route command captures the launch, entered endpoints, routing progress,
ready route, and active ride. Screenshots and an event log go under
`build/visual-inspection/<run-id>/`; `build/visual-inspection/latest.png` is
updated after every capture. Add `--plan-only` to leave the route ready at the
planner. A subsequent run can reuse the built APK with `--no-build`. Add
`--save-bubble` to save the shown ride from its card (the bookmark button, then
a long-press) and capture the bubble (`save-bubble-saved`, `save-bubble-prompt`);
it adds the ride to the app's saved routes.

The planner keeps the full ETA visible beside the Ride button. The distance
moves below it when the row is too narrow. The row grows for wrapped text and large font scales.
Capture the planner at 320 dp with 2x text:

```sh
tools/test/visual.py route --from Brisbane --to 'Mount Glorious' --plan-only --eta-layouts
```

The runner saves `planner-eta-narrow-large-text` and restores the device size
and font scale. `PlannerEtaResponsiveTest` checks full text at 320 dp / 2x
and 200 dp / 2x, including a duration of more than 100 hours.

### Screen-size matrix (`ui-sizes`)

`tools/test/visual.py ui-sizes` runs the on-device Compose suite
`com.curvemaps.app.ui.CommonScreenSizeTest` over every window and font scale in
the matrix, and turns its exports into gallery-ready frames under
`build/visual-inspection/<run-id>/` (`ui-sizes.json` manifest,
`instrumentation.log`, and `latest.png`). It builds and installs the debug app
and the instrumented APK with the JDK 17 wrapper, then runs the real
instrumentation class through `adb shell am instrument -w` with
`uiSizeCaptures=true`; it never taps through the UI and never changes the
device's own size or font scale.

```sh
# Full matrix: 21 windows x 3 font scales x 4 surfaces = 252 frames.
tools/test/visual.py ui-sizes
# Focused subsets (ids and scales must exist in the matrix).
tools/test/visual.py ui-sizes --cases phone,tablet,width-840 --font-scales 1.0,2.0
# Reuse an existing pair of APKs instead of building (fails if either is missing).
tools/test/visual.py ui-sizes --no-build
tools/test/visual.py ui-sizes --no-build --apk app/build/outputs/apk/debug/app-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Through the gallery: the app and androidTest APKs are built and frozen once,
# then every shard installs the same pair.
tools/test/gallery.py run --only ui-sizes
```

Frames are labelled `ui-size-{caseId}-font-{scale}-{surface}` with surface one
of `planner`, `ride`, `settings`, `welcome`; `gallery-displays.json` claims each
surface. The suite writes one PNG plus one JSON record (label, widthDp,
heightDp, fontScale, surface, device path) per frame and logs the same JSON
under the `CurveMaps.UiSizes` tag. The runner pulls the PNG+JSON exports from
the app cache with `run-as` before anything can uninstall the app, exports
before assertions, and still imports the frames a failing configuration already
produced; an instrumentation failure is reported as a failure, so nothing is
misreported as fully captured.

The window list and font scales live in exactly one place,
`app/src/androidTest/assets/ui-screen-sizes.json`; the CLI filters validate
against it and the gallery registry's minimums are derived from it in
`tools/test/test_gallery.py`, so no second copy can drift.

| Window id | Size (dp) | Why it is in the matrix |
| --- | --- | --- |
| `small-phone` | 320x568 | Representative small phone |
| `phone` | 360x640 | Representative baseline phone |
| `tall-phone` | 393x873 | Representative tall phone |
| `large-phone` | 412x915 | Representative large phone |
| `phone-landscape` | 640x360 | Landscape phone (compact height) |
| `large-phone-landscape` | 915x412 | Landscape large phone (expanded width, compact height) |
| `foldable` | 673x841 | Representative foldable inner screen |
| `small-tablet` | 600x960 | Representative small tablet (medium width) |
| `tablet` | 800x1280 | Representative tablet (medium width) |
| `tablet-landscape` | 1280x800 | Representative tablet landscape (large width) |
| `desktop` | 1600x900 | Extra-large width upper boundary, expanded height |
| `width-599` | 599x900 | One dp below the 600 dp compact/medium break |
| `width-600` | 600x900 | Compact/medium width break |
| `width-839` | 839x900 | One dp below the 840 dp medium/expanded break |
| `width-840` | 840x900 | Medium/expanded width break |
| `width-1199` | 1199x900 | One dp below the 1200 dp expanded/large break |
| `width-1200` | 1200x900 | Expanded/large width break |
| `width-1599` | 1599x900 | One dp below the 1600 dp large/extra-large break |
| `height-479` | 840x479 | One dp below the 480 dp compact/medium height break |
| `height-480` | 840x480 | Compact/medium height break |
| `height-899` | 840x899 | One dp below the 900 dp medium/expanded height break |

Android defines the window size class boundaries at widths of 600, 840,
1200, and 1600 dp, and heights of 480 and 900 dp. Android also recommends
testing at the maximum font scale of 2.0. This suite uses 1.0 for normal text
and 1.3 as an intermediate stress case. The named phone, tablet, foldable,
and landscape windows are representative sizes, not official device standards.
The probes test both sides of each class boundary.

Compose's `DeviceConfigurationOverride.ForcedSize` fits each virtual window
on the emulator. The suite measures the effective density and verifies the
requested window dimensions. It removes the host's system-bar insets because
these dimensions describe the available app window, not the physical screen.

Sources (also recorded in the matrix JSON):
[window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes),
[Android 14 non-linear font scaling](https://developer.android.com/about/versions/14/features#non-linear-font-scaling),
and [`DeviceConfigurationOverride.ForcedSize`](https://developer.android.com/reference/kotlin/androidx/compose/ui/test/DeviceConfigurationOverride.Companion).

The suite tests the planner, ride HUD and data bar, settings menu, and welcome
safety page. It uses production Compose surfaces with fixed fixture models.
It does not test the native MapLibre map, routing, platform dialogs, system
bars, or keyboard resizing. The existing on-device suites cover those paths.

The settings menu is height-bounded to the window and scrolls inside when it
cannot fit in a short window. The toolbar's cog and saved-routes buttons stay
fixed. The size suite scrolls to every menu
row and checks its visible bounds and touch target.

Inspect the From selector's POI icon and search results with the keyboard open
and dismissed using `tools/test/visual.py search-results --query Brisbane`.
Use `--field To` for destination search. Results sit in a sheet as wide as the
planner panel and are part of it (the fields and Start stay whole below them);
longer lists scroll within the room the panel has left above the keyboard. The runner
temporarily enables the on-screen keyboard even when a hardware keyboard is
connected, then restores the emulator setting. It waits for the known query
to finish and captures the query entry, complete results, and keyboard dismissal.

Set initial progress on the same command, or move an active ride later:

```sh
tools/test/visual.py route --from Brisbane --to 'Mount Glorious' --progress 50
tools/test/visual.py progress --percent 75
tools/test/visual.py route --from Brisbane --to 'Mount Glorious' --black-and-white both
tools/test/visual.py map-mode --black-and-white on
tools/test/visual.py icon-states
tools/test/visual.py pace --from Brisbane --to 'Mount Glorious'
tools/test/visual.py nav-camera --from Brisbane --to 'Mount Glorious'
# Includes compact ride sound/B&W controls in both toggle states.
tools/test/visual.py nav-camera --from Brisbane --to 'Mount Glorious' --black-and-white both
tools/test/visual.py nav-camera --from '-27.4700,153.0250' --to 'Mount Glorious' --black-and-white on
```

Progress follows the selected route geometry through the live navigation
session. The runner captures intermediate route positions and the final frame,
and updates `latest.png` after each capture. Backward jumps restart
guidance at the route start and advance to the requested point. This requires a
debug build and an Android emulator; route geometry and the ADB control bridge
exist only in debug builds.

`pace` follows the first 600 m of a fresh route using its local planned times
and a debug navigation clock, so host delays do not change the result. It
checks and captures the unmeasured HUD, riding on plan, losing time, a five-minute
stop, a GPS-gap resync, and recovering time. Each `pace_checkpoint` reports
expected and observed seconds. The route must be at least 650 m long. Pace
compares sampled moving time with the planned time between matched positions;
stops are excluded and the displayed delta is smoothed. This verifies the
calculation against the routing model, not the model's real-world accuracy.

`gps-loss` checks the No GPS banner on the Material 3 ride screen. It captures
missing fixes, route estimation, fresh coarse fallback fixes with no banner,
precise-fix recovery, and a stopped rider without fixes. The
`gps-loss-02-fallback-fixes` frame proves that estimation alone does not show
the banner. Run it with `--from` and `--to`, like `pace`.

`nav-camera` starts a ride and captures the guidance camera as a repeatable
sequence. It captures navigation start, street-close framing, rider-lock
release while injected fixes advance, and relock at the latest rider position.
It also pans the locked map, captures the 1.5-second pause, and proves that
follow returns automatically in both map styles.
It then captures one frame per speed band (`--speed-cases`, default
`0,8,15,23,32` m/s, whose guidance zooms run z18 down to z14), an off-rider
inspection at a changed tilt with another lock cycle, a real heading turn
(`--turn-threshold`, `--turn-step`, `--turn-speed`), and the planning-camera
restore after END. Each step reads the debug camera probe and fails if the
settled camera misses the injected fix or does not match the expected zoom,
forward tilt, heading, or restore. The screenshots include assertions as well
as pixels. Queued debug fixes own the guidance session while they flow, so the
emulator's live provider cannot pull the camera away between injections. The
runner uses accessible text controls (RIDE/End and the lock button) and the
existing debug fix bridge. It needs an emulator and a debug build.

During rides, the right-side dark floating toolbar contains the voice-guidance
button (opens the voice settings) and the B&W toggle (selected = inverse
surface). Each 48 dp button has a 64 dp touch target. A compass button sits above it
and the zoom group and the Follow rider FAB below it, all on the same axis. The directions card (blue;
grey in B&W) shows a Material Symbol for the next manoeuvre, and the bottom
ride bar is an island like the planner panel, with matching space above and
below its content.

The ride avatar uses the supplied rounded arrow with a rear notch. Its shaded
faces, centre ridge, side walls, and contact shadow suggest depth (2.5D).
The colour map uses blue. The B&W map uses grayscale with a dark outline to
separate the avatar from the white routed road. The avatar follows the road
heading but keeps its drawn depth as the camera tilts. Normal location tracking
keeps its existing disc marker.
`nav-camera --black-and-white both` captures both avatars over the road at the
street-close stop and during the speed and heading changes.

During guidance, the rider-lock button reports the current camera state. Tap it
to release or restore camera follow. A pan or two-finger tilt pauses follow until 1.5 seconds after the gesture
finishes, then restores the normal camera angle. The button stays locked
during that pause; tap it to release follow indefinitely. Route
tracking and the rider marker continue while the camera is unlocked. Relocking
uses the current rider position and guidance camera framing.

`--black-and-white on|off` sets the ride map style during a route run. Use
`both` to save screenshots of both styles for comparison. On `nav-camera`,
`both` re-captures **every** guidance frame in colour and B&W — the
street-close stop, each speed band's z14–z18 zoom, and the turn — writing
`-color` / `-bw` suffixed files (for example
`nav-camera-02-speed-8-color` and `nav-camera-02-speed-8-bw`), so the B&W
ride map's full-width white routed road, subtly dimmed surrounding streets,
and labels can be compared against the
colour map at identical camera states. `map-mode` changes
the style on an already active ride and saves a screenshot for each requested
state. `icon-states` captures the dark-mode outline/fill, voice settings dialog, and
volume on/off states during an active ride, then restores both preferences.

Capture the planner's settings menu, a screen opened from it, and their
dismissed states with:

```sh
tools/test/visual.py settings-menu
```

This verifies the map credits in settings and saves `settings-menu-open`, `settings-screen-open`,
`settings-screen-back-to-planner`, and `settings-menu-dismissed` screenshots
under the normal visual-inspection run directory. Use `--no-build` to reuse the
existing debug APK, or `--skip-map` when no PMTiles archive is available.

Capture the Open-source licences screen (settings cog → Open-source licences)
with:

```sh
tools/test/visual.py oss-licences
tools/test/visual.py oss-licences --no-build
```

This saves `oss-licences-settings-row`, `oss-licences-list` (filter chips, then map data, fonts
and icons first), `oss-licences-text-odbl` (OpenStreetMap's ODbL in full),
`oss-licences-libraries`, `oss-licences-text-apache` (GraphHopper's
Apache-2.0 text), `oss-licences-back-to-list` (system Back closes the text)
and `oss-licences-back-to-planner`. Everything shown is bundled in the APK.

Capture the regional package screen (server address, catalog, download/import
intermediate states, installed-map list with delete confirmation, and the
offline import control) with:

```sh
tools/test/visual.py maps-settings
```

This saves `maps-settings-empty` and `maps-settings-back-to-planner`, the
22-region catalog preview grouped by continent (`maps-preview-catalog-world`,
`maps-preview-catalog-expanded`, `maps-preview-catalog-search`), plus
deterministic intermediate states through the debug Maps-preview bridge:
`maps-preview-service-connected`, `maps-preview-server-error` (dismissible,
actionable connection error), `maps-preview-catalog-ready` (a terminal build
job still shows Download), `maps-preview-build-failed` (Retry stays visible),
`maps-preview-download-progress`, `maps-preview-download-retrying` (automatic
resume countdown after a dropped connection), `maps-preview-download-verifying`
(SHA-256 check before the file is saved), `maps-preview-download-interrupted`,
`maps-preview-package-saved`, `maps-preview-recovered-package`,
`maps-preview-installed`, `maps-delete-confirmation`, `maps-delete-cancelled`,
the map-update states (`maps-preview-update-check`: one Check for updates
button before the catalog loads; `maps-preview-update-available`: Update on an
installed map whose package fingerprint differs from the service's;
`maps-preview-updated`: the one remaining copy, Up to date),
and `maps-preview-removal-pending` (the active map switching to bundled data
while the routing graph drains), `maps-preview-removal-retry` (a safe deletion
timeout), and `maps-preview-import-deferred` (installation succeeds while a ride
defers activation). It never taps Browse maps, so no map server, network
access, or SAF picker is required. The real download/import/delete lifecycle is exercised on device by
`MapsDownloadFlowOnDeviceTest` and `MapsSettingsScreenTest`.

Capture the ride media control center across its black-and-white player states
(playing, paused, no player, and notification-access needed) with:

```sh
tools/test/visual.py media-controls
# Stopped-player discovery and real MediaBrowser Start → playing → paused
tools/test/visual.py media-controls --cold-start
```

This routes the default corridor, enters ride mode, and saves
`media-panel-color-playing`, `media-panel-bw-playing`,
`media-panel-bw-paused`, `media-panel-bw-no-player`,
`media-panel-bw-permission-needed`, `media-panel-dismissed-with-back`,
`media-panel-inactivity-full`, `media-panel-inactivity-half`, and
`media-panel-inactivity-closed` screenshots (the blank-panel touch restarts the
timer before the half frame; the final frame proves it closes after inactivity).
A fresh install also captures `00-welcome-carousel` before skipping the
first-run welcome carousel (see `visual.py first-run` for every page). The
carousel is drawn in the dark scheme: each of its five pages has an M3 Expressive
hero shape (nine-sided cookie, four-leaf clover, six-sided cookie, soft burst)
that turns slightly as you swipe (still when animations are off), outlined status
chips for permissions, a pill page indicator, a filled button for the main action
and text buttons for Skip, Not now and Later. Pages scroll (dots and buttons stay
pinned), the hero shrinks on short windows, content is capped at 560 dp on wide
ones, and phone landscape puts the hero beside the text.
Installed music apps stay behind **Music apps**; **Change music app** reveals
other installed choices and only the chosen app gets Start/Open controls. Spotify,
TIDAL, Deezer, Apple Music, YouTube Music and Qobuz are explicitly checked;
other apps must declare Android's music-app entry point. Missing or disabled apps
are omitted, and with none installed the button is hidden.

`--cold-start` also captures the collapsed picker, its revealed controls, and hiding
it again (`media-music-apps-collapsed`, `media-music-apps-hidden-again`; plus
`media-music-app-choice` when multiple apps exist). It enables the disabled debug
music-browser fixture and captures
`media-cold-player-before-start`, `media-cold-player-playing`, and
`media-cold-player-paused`. The fixture begins without an active session, so
Start must connect to its service and deliver a playback command. It then makes
the fixture refuse that connection and captures `media-cold-player-button`
(one media-button press starts it; the ride stays in front),
`media-cold-player-pip` (its own play screen opens and the ride shrinks to its
Picture-in-Picture window) and `media-cold-player-returned` (the ride comes
back; only the same-app fixture player lets it). Its original component state
is restored afterward.

It drives the debug-only synthetic MediaSession broadcast
(`DEBUG_VISUAL_MEDIA_SESSION`) so a machine with no real media app can exercise
every state; release builds create no synthetic session. The runner records
the device's notification-listener access before it changes anything and
restores exactly that value in a `finally` — it never force-grants access.
Pass `--reuse-app` to reuse a running ride, or `--from`/`--to` to choose
another corridor.

For only the 15-second inactivity behavior, without synthetic player or map
style transitions, run the focused repeatable capture:

```sh
tools/test/visual.py media-controls --inactivity-only
```

It saves the same full, reset-halfway, and closed screenshots.

For step-by-step exploration, use `launch`, then call `tree`, `screenshot`,
`tap`, `type`, `key`, `swipe`, or `options` as needed. `watch` records changing
screens while another gesture or command runs. These commands use accessible
text and content descriptions, so the planning settings cog, route settings,
saved routes, voice options, GPX import, and other visible app controls can be
inspected and operated without fixed screen coordinates. Set `ANDROID_SERIAL` when more than one
device is online; set `ANDROID_AVD` to select another emulator.

### Transparent offline 3D buildings

The normal map keeps flat building footprints at zoom 13 and adds translucent
3D boxes at zoom 14 and above (including overzoom beyond the archive's z14).
Ordinary houses without an explicit height use 5 m; bases/heights are bounded
to prevent malformed features creating giant or inverted boxes. `hide_3d=true`
outlines stay flat. Planning remains flat, and riding keeps its existing tilt,
bearing and speed-dependent zoom. The minimal black-and-white ride map
deliberately has **no buildings**. No dataset rebuild or network access is needed.

Use an **exclusively owned AVD**, with distinct emulator/debugger ports. Never
run these examples against another agent's device. The `buildings` scenario
requires an explicit serial and does not auto-select or boot a device:

```sh
ANDROID_SERIAL=emulator-5580 tools/test/visual.py --serial emulator-5580 \
  --output-dir build/visual-buildings buildings
# Inspect another deterministic pitched view in a debug APK:
ANDROID_SERIAL=emulator-5580 tools/test/visual.py --serial emulator-5580 camera \
  --lat=-27.4616 --lon=153.0466 --zoom=18.5 --tilt=58
```

The scenario captures flat overview, both sides of the z14 threshold, CBD
towers, default-height residential houses near James/Hawthorne Streets,
a real CBD route at all five navigation zoom bands, opposing tower-silhouette
views, moving progress, B&W/normal switches, landscape navigation with End,
and installed-map reuse after relaunch. It runs in airplane mode and restores
airplane mode, rotation settings, initial location grants, parked GPS position
and ride appearance. Queued fixes also update the owned emulator's GPS provider
so a long UI inspection cannot fall back to its old parked location.
Style-switch captures wait for a debug-only, request-driven native probe to
find basemap/rider render features and the selected-route source/layers before saving pixels;
they do not assume that an accessibility toggle means tiles finished repainting.
This base resets navigation on rotation, so the bounded landscape check starts
a fresh ride rather than claiming session persistence. PNGs, accessibility
states and camera probes are emitted under the chosen output directory.
The `camera` command is a debug-only inspection seam, not a production control.

`BuildingStyleTest` evaluates the shipped height/base/filter expressions.
`BuildingRenderingTest` requires a seeded PMTiles map and compares native
snapshot pixels with extrusions shown/hidden for real towers and fallback-height
houses; it also checks route/rider ink at z14/16/18 and normal → B&W → normal
style reloads. Run `visual.py launch` on the explicit owned serial first, build
`:app:assembleDebugAndroidTest`, install its APK with `adb -s <serial>`, then:

```sh
~/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell am instrument -w -r \
  -e class com.curvemaps.app.map.BuildingRenderingTest \
  com.curvemaps.app.test/androidx.test.runner.AndroidJUnitRunner
```

Rendered screenshots remain necessary: feature queries alone do not prove
visibility, and layer order alone does not guarantee 3D occlusion safety.
The CBD visual corridor also has a committed routing-corpus gold baseline and
an on-device full guidance replay (upcoming turns through arrival).
See [validation results and screenshot inventory](tools/test/buildings-validation.md)
for the isolated-AVD evidence and remaining limitations.

### Map performance benchmark (native frames)

`visual.py perf` measures the real offline map while the camera moves
continuously: it queues deterministic `easeCamera` sweeps through the debug
receiver, records each native `OnDidFinishRenderingFrame` timestamp, the
per-frame renderer counters, and the declared rendering backend, then writes
`build/brisbane-performance-report.md` plus one JSON per sweep under the
inspection run. It needs an explicitly owned serial and a debug build.

```sh
# Acceptance matrix: dense CBD at z14/z16/z18 and the houses area at z18.5,
# each cold (fresh process) and twice warmed, with settled building screenshots.
tools/test/visual.py --serial emulator-5554 perf \
  --targets cbd,houses --zooms cbd=14,cbd=16,cbd=18,houses=18.5 --repeat 2 --screenshots

# Extrusion diagnostics on one case, without touching shipped rendering.
tools/test/visual.py --serial emulator-5554 perf --targets cbd --zooms 18 \
  --building-modes current,opaque,hidden --repeat 2
```

Read the numbers with the semantics in the report: `fps` is the elapsed
frame-timestamp average, `nativeFps.harmonic` is the listener series after
discarding its first (pre-sweep idle) interval, `enc` is MapLibre's
`encodingTime` converted from seconds and on Vulkan includes
`Context::beginFrame` fence waits (encode+GPU-wait, not pure CPU), and
`upload KB/f` is the per-frame delta of the cumulative buffer-upload counter.
`windowFrameMetrics` describes the activity window, not the map SurfaceView.
A warm sweep with an interrupted leg or a settle timeout is reported INVALID,
never counted as a pass (a sweep cancelled mid-plan reports fewer legs than it
planned and is marked interrupted, so a canceled native camera leg can never be
counted as a completed benchmark).

The dense-CBD fix came from the drawable count, not from hiding content: the
POI layers' data-driven `symbol-sort-key` made MapLibre 13.5 emit roughly one
drawable per POI feature (~880 of 929 draw calls, ~1.2 s render-thread encode
per frame at CBD z18). The style now uses a bounded rank band, so priority
order survives while equal keys batch; `SymbolBatchingStyleTest` (JVM) enforces
that contract and `SymbolPriorityOnDeviceTest` proves the important labels
still win placement against the real archive. Measured before/after on the same
matrix — CBD z18: 0.8 → 59.9 fps (p95 1587 → 18.9 ms, zero warm stalls) on
debug OpenGL, and 1.0 → 46.3 fps on the Vulkan artifact; houses z18.5:
3.8 → 60.1 fps. CBD z14 remains the wide-view case at ~32 fps / p95 ~48 ms
(zero stalls over 100 ms): ~350 far-tile draw calls and their label uploads,
which is the whole frame at the pitched wide view. MapLibre's native camera
tile LOD (default pitch threshold 60°, inactive at this app's 58° camera) was
tried there and is deliberately **not** shipped — its zoom shift hid the wide
extrusions and the no-shift variant did not reproduce; the debug sweep runner
retains the override for experiments only. The release APK keeps the published
Vulkan backend; this emulator renders Vulkan in software (llvmpipe), so its
release-backend numbers are environment-bound, while debug builds use the
OpenGL artifact purely as emulator tooling. Merge runs with
`tools/test/visual.py perf-merge --labels before-opengl,after-opengl ...`.

The rendering backend comes from the MapLibre artifact, and it is real, not a
runtime setting: `android-sdk` is Vulkan-only and `android-sdk-opengl` is
OpenGL-ES-only (a runtime switch throws `UnsupportedOperationException`). Debug
builds default to OpenGL because the emulator rasterizes Vulkan in software
(`cmd gpu vkjson` reports llvmpipe) while its OpenGL ES path uses the host GPU;
release builds keep the published Vulkan default. Force either side with
`-PmaplibreBackend=vulkan|opengl`, for example to reproduce the baseline:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug -PmaplibreBackend=vulkan
```

Every sweep report names `backend.renderer` (the value
`RenderingEngine.getCurrentType()` actually initialized) and the loaded
MapLibre flavor/revision, so a number can never be attributed to the wrong
backend. The JVM `MapPerfSweepTest` and the on-device
`MapPerformanceOnDeviceTest` pin the report contract, the building-diagnostic
restore (verified during and after a sweep), cancellation/interruption
cleanup, and the backend declaration.

## Data pipelines (required before first build)

The generated assets are gitignored; a fresh clone has none of them until the
pipelines are re-run. All take an OSM `.osm.pbf` extract placed at
`data/queensland.osm.pbf`:

1. **Routing graph** — delete any old `data/graph-cache`, then import with
   `java -Xmx12g -cp tools/gh/graphhopper-web-11.0.jar tools/gh/MotoGraphImport.java import tools/gh/config.yml`
   (GraphHopper's import plus the lane-guidance parser) and copy
   `data/graph-cache` into `app/src/main/assets/graph-cache`.
2. **Basemap tiles** — `tools/tiles/build-tiles.sh` (Planetiler, needs JDK 21)
   writes `data/tiles/queensland.pmtiles`. That is an input, not the
   deliverable: `tools/package/pack-queensland.sh` combines it with the graph
   and geocoder into `data/package/queensland.motomap` (via `motomap pack`),
   and `tools/package/build-release-assets.sh` produces the release pair in
   `build/release/` (map-less `curvemaps.apk` + `queensland.motomap`).
3. **Style glyphs & sprites** — `tools/style/fetch-style-assets.sh`.
4. **Geocoder index** — `./gradlew :geocoder-tool:run` writes
   `app/src/main/assets/geocoder/geocoder.dat`.

Exact commands, version pins, and hard constraints live in [AGENTS.md](AGENTS.md)
and the topic docs it indexes under [docs/agents/](docs/agents/).

## Regional packages and the hosted map service

The app can install **complete regional data packages** (`.motomap`): vector
tiles, the routing graph, and the offline place-search index in one verified
archive. Packages come from the CurveMaps map service (`maps.curvemaps.app`), which
generates them with the same pinned tools as this repository's CI pipeline, or
from a file installed offline. The package format and its pack/verify tool:
[tools/motomap/README.md](tools/motomap/README.md).

- **Maps settings** (settings cog → Maps): tap **Browse maps** to see the
  regions the CurveMaps map service offers. Nothing is sent before that tap.
  The download button on a catalog row (**Download & install**) downloads,
  verifies and switches to a map in one step, with a wavy progress card and
  **Cancel download** while it runs; **Save a copy to Files** also keeps the
  `.motomap` file. **Find a region** (a search field with a **Near me**
  button) builds any other state-sized region on request,
  and it installs itself when it's ready. A failed or cancelled download keeps
  its resumable partial and always offers a retry. The app always uses the
  CurveMaps map service over HTTPS; there is no server setting. A long catalog
  is grouped by continent and country (collapsed until opened) with a
  **Filter regions** field.
- **Installed maps** lists the app-managed copy of each region with its source
  date and actual installed size, plus the immutable bundled Queensland data.
  The device keeps one copy per region: installing a package for a region that
  is already installed (download, Load map file or import) replaces the older
  copy, which is deleted once the map, router and search have released it.
  "Newest" is the order the copies were installed in on the device, never the
  phone's clock. Copies kept side by side before this app version stay until
  that region is installed again. If an older copy cannot be deleted, it is
  listed again so you can delete it yourself. With no usable map at all, a
  newly installed map always switches on.
- **Map updates**: after **Check for updates** (or Browse maps), each installed
  map compares its package fingerprint with the map service's current package.
  A match reads **Up to date**; a different package whose map data is not older
  offers **Update**, which downloads and verifies it, takes the old copy's place
  (active if the old copy was active, switching when a ride ends — even if the
  app is closed mid-ride, it switches on the next start) and deletes the old
  copy. Each entry
  has a rubbish bin: deleting an inactive map removes only that app-managed
  directory (your downloaded `.motomap` in Files is untouched), and deleting the
  active map first switches back to the bundled dataset, waits for the routing
  graph to be released, and then removes it. Deletion is refused during a ride.
- **Switching regions** rebuilds the router, geocoder, and map style from the
  new package; a ride must be stopped first. Saved rides are never modified —
  a ride outside the active region fails with an honest "outside the active
  region" message.
- **Offline guarantee**: routing, place search, and map rendering never touch
  the network. The only network code is the downloader/API client under
  `app/src/main/java/com/curvemaps/app/region/net/`, and
  `OfflineGuaranteeTest` pins that boundary.
- **Load map file** is one action for both formats, chosen by the file's own
  bytes: a `.motomap` is installed and activated in one step; a bare PMTiles
  archive keeps the legacy behaviour (basemap only, bundled dataset) and is
  refused by a map-less build, which has no routing data to pair it with.
  With nothing installed, the map-less build shows a "Map package needed"
  dialog (Choose map file / Open Maps); Back dismisses it and the settings
  menu stays reachable.

Pack and verify a package from locally generated components:

```sh
tools/package/pack-queensland.sh            # writes data/package/queensland.motomap
(cd tools/motomap && go run ./cmd/motomap verify --package ../../data/package/queensland.motomap)
```

## Attribution & licenses

- CurveMaps is source-available under the
  [PolyForm Shield License 1.0.0](LICENSE). You may read, build, change and
  use it for any purpose except providing a product that competes with
  CurveMaps, free or paid. The official app is published by the project owner
  on Google Play. The name, logo and icon are not licensed
  ([TRADEMARKS.md](TRADEMARKS.md)). Contributions need the CLA
  ([CONTRIBUTING.md](CONTRIBUTING.md)).
  Third-party components and their licences: [THIRD_PARTY.md](THIRD_PARTY.md).
  In the app, settings cog → **Open-source licences** lists every bundled
  library (generated from the build's dependency graph) and every map data,
  font and icon source, each with its full licence text and notices, offline.
- Map data and style: © OpenMapTiles.org © OpenStreetMap contributors
  (shown in-app; required by their licenses).
- Noto fonts: SIL OFL 1.1. OSM Bright sprites: CC BY 4.0.
- GraphHopper core: Apache-2.0, embedded for offline routing only — no hosted
  GraphHopper service is contacted.

## Ride suggestions

With a Balanced, Twisty or Max twisty ride, the app can offer a worthwhile twistier
road ahead. The banner and spoken offer explain added time, distance, extra curves
and the turn to take. Tap Take or ride the suggested turn to accept; tap Skip or
continue along your route to decline. Take and Skip have 64 dp glove-sized targets.
The pending road appears in teal (grey in black-and-white mode).

Ride suggestions is on by default in Route settings. Switching it changes only
suggestions; applying road-avoidance changes still recalculates the route. Checks
start after 5 km, then require both 10 km and 10 minutes riding, with at most three
offers an hour. Battery saver and a low unplugged battery pause checks. The app
waits until any remaining GPX shaping points have been passed. A diversion must
add substantial curves and stay within both 12 extra minutes and 50% of the
replaced stretch's time. All routing works offline.

Replay a real proposal on an explicitly selected emulator, capture both map styles,
and choose how to resolve it:

```bash
python3 tools/test/visual.py --serial emulator-5554 suggestions \
  --from '-27.3690,152.8864' --to '-27.5598,153.0811' --style balanced \
  --decision ride --black-and-white both
# Other choices: --decision take, --decision skip, --decision continue
```

The command drives the production scheduler and graph search; it fails with a
no-offer screenshot when no diversion qualifies. Inspect `CurveMaps.Suggest`
logs for checks. Use `--max-distance` for longer corridors. JVM coverage runs in
`tools/test/ci.sh`; HUD, settings, accessibility, large fonts, corpus and performance
coverage run on-device, with the full offline/fuzz checks in `tools/test/run-all.sh`.

Run the full tier against the same emulator (one instrumented class per task,
with class logs and XML retained under `build/test-report/classes`):

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_SERIAL=emulator-5554 tools/test/run-all.sh
```

Queensland building and POI rendering suites need their region fixture before
Activity launch. The full runner seeds it for each class. For a direct run:

```bash
ANDROID_SERIAL=emulator-5554 tools/test/seed-map-fixture.sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.curvemaps.app.map.BuildingRenderingTest
```
