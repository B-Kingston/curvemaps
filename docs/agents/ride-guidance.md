# Ride guidance: HUD and data bar, lane guidance, GPS gaps, ride suggestions

The ride map styles (B&W, curve shading) are in `map-rendering.md`; the media
panel in `media.md`. None of the features here needs a graph rebuild or CI
cache-seed bump, except lane data (`graph-pipeline.md`).

## Ride HUD, toolbar and data bar (`NavigationHud.kt`)

All ride chrome is Material 3 in the **dark scheme** (`CurveMapsDarkTheme` wraps
each composable itself, so callers need no theme): the guidance card, the
voice / B&W toolbar, the data bar and the suggestion card (`ui-theme.md`).

- **Guidance card** (`NavigationHud`): `primaryContainer` / `onPrimaryContainer`,
  extra-large (28 dp) radius and a **fixed width** (`guidanceCardWidthDp`: 212 dp,
  growing with the font scale to at most 300 dp) so the card and the chip under it
  hold still from frame to frame; a long street name ellipsises. A 52 dp manoeuvre
  glyph, the distance in `displaySmall` semibold (`—` until the first instruction)
  and the street in `titleMedium` (one line, ellipsis). `NavigationHudFormat.maneuverGlyph` (pure, JVM-tested) picks the
  glyph: Material Symbols `ms_straight`, `ms_turn_*`, `ms_turn_sharp_*`,
  `ms_turn_slight_*`, `ms_fork_*` (keep left/right), `ms_u_turn_*`,
  `ms_sports_score` (finish) and `ms_flag` (via); symbols are shifted left
  6 dp (`ManeuverSymbolInkInset`) so their ink, not their empty viewport edge,
  meets the text. Two stay **drawn**
  (`drawTurnArrow`) because no symbol is faithful: roundabouts (the circulation
  direction comes from GraphHopper's geometry, the exit number rides the glyph's
  corner as a badge) and a U-turn whose side is unknown (both ways). The slight
  turn's measured angle is no longer drawn (the symbol replaces it).
  Without a fix only the glyph fades (`GUIDANCE_STALE_ALPHA`); the distance holds
  full strength (it has under 5:1 to spare on the blue card).
- **B&W ride mode** (`monochrome`, `darkRideMapEnabled`): the card is
  `surfaceContainerHighest` / `onSurface`, END `inverseSurface`, the pace delta
  neutral, the suggestion card's tile and Take neutral. No blue or red accents;
  the `No GPS` chip stays `errorContainer` as designed.
- **Toolbar** (`RideMapToolbar`, rendered by `RouteActionsPill(rideMode = true)`
  in `SettingsMenu.kt`): a `VerticalFloatingToolbar` (`surfaceContainer`) of two
  `IconToggleButton`s, each a 64 dp glove target (toolbar 72 dp wide, 4 dp inset;
  `RideToolbarWidth`, `RideRailHitMargin` is 0). Voice: `ms_volume_up_fill` when
  voice is on and the offline voice is ready, `ms_volume_off` otherwise; selected
  = `secondaryContainer`; it **opens the voice settings** (it is a status toggle,
  not a mute). B&W map: `ms_dark_mode(_fill)`; selected = `inverseSurface`; it
  toggles `darkRideMapEnabled`. Descriptions unchanged (`Voice guidance settings,
  enabled, …`, `Dark ride map, on|off`). The compass (`MapCompassButton`) and
  zoom/follow controls belong to `RouteScreen` (`planner-ui.md`).
- **Data bar** (`NavigationDataBar`): `surfaceContainer`, floating over the map
  as an **island** like the planner panel it replaces (`DataBarMargin` =
  `ROUTE_PANEL_MARGIN` 12 dp each side, `DataBarBottomMargin` =
  `ROUTE_PANEL_BOTTOM_MARGIN` 16 dp above the navigation bar; corners
  `DataBarCorner` = `ROUTE_PANEL_CORNER_RADIUS` 28 dp). The top corners square
  off while the media panel is open (`MediaPanelJoin`, which also draws a 1 dp
  `outlineVariant` hairline on the open bar's top edge). Its content cluster is
  capped at `DataBarMaxContentWidth` (640 dp) and centred on wide windows. The media button (a 64 dp `IconToggleButton`, `ms_music_note`;
  selected = `secondaryContainer` while the panel is open, `stateDescription`
  open/closed) and END (below) flank **five readout cells**, each a `titleLarge`
  semibold tabular value over a `labelMedium` caption: speed (`km/h`, or `No GPS`
  in the error role while there is no fix), time left (`1h 19m` / `45m`, caption
  `left`), distance left (`9.9 km`, caption `left`), the arrival clock (`9:46`,
  phone 12/24 h setting, caption `arrive`) and the pace delta (caption `gained` /
  `lost` / `on plan` / `pace`). Arrival is its own cell, not a caption under the
  time, so nothing reads as a contradiction. END is a `Button` (`errorContainer`;
  `inverseSurface` in B&W), ≥64 dp tall and `EndPillMinWidth` (72 dp) wide, text
  `End` (sentence case). The row has `DataBarRowGap` (8 dp) above and below it
  inside the island.
- Each value is its own text node; the bar's description adds "arriving …" and
  the pace in words.
- `NavigationHudFormat.planDataBar` (pure, JVM-tested; the bar feeds it the real
  text widths from `rememberTextMeasurer`, tests the `estimated*` widths) picks
  the layout **and one value size for every cell**: `SINGLE_ROW` (media, five
  equal cells, END) when the size stays ≥ 15 sp, which is roughly ≥ 465 dp at 1x;
  else `GRID` (media, speed beside a 2 × 2 of time/distance over arrival/pace,
  END: three equal columns) at any size down to 13 sp; else `STACKED` (the same
  grid on the full width above a media/END row; a 320 dp phone at 1.3x+). The size
  steps down in 0.5 sp together, capped at `readoutSp` (30 dp rendered), and is
  computed against worst-case value/caption templates as well as the live ones so
  nothing jumps as numbers grow. The chrome constants mirror the Compose layout
  (`dataBarLayoutChromeMirrorsTheComposeLayout`).
- **Pace delta:** actual minus model time in whole seconds, like a lap timer:
  `−8 s` ahead (true minus, `CurveMapsTheme.colors.success`), `+12 s` behind
  (`colorScheme.error`), `onSurface` `0 s` under half a second, a muted dash
  until measured. Both clear 4.5:1 on `surfaceContainer` (`ColorThemeTest`).
- **Layout:** `RouteScreen` measures the real data bar and directions HUD
  (`onGloballyPositioned`) and positions the media panel and map controls from
  them, never from hard-coded ride offsets. The HUD's measured height includes
  the `No GPS` chip while it shows.
- `NavigationPipHud` is the guidance card in its compact form (manoeuvre
  glyph and distance in `headlineSmall`; no fixed width, street name or lanes)
  for the ride's Picture-in-Picture window over a music player (`media.md`).
  Nothing else of the ride chrome is drawn in that window.
- Tests: `DataBarResponsiveTest` (320 dp / 2x grid, glove targets, hues in
  pixels, END/media/bar container roles), `RideLandscapeLayoutTest`,
  `NavigationHudTest`, `NavigationIconRenderingTest` (drawn glyphs; every
  manoeuvre draws a glyph on the card), `RideSettingsPillTest` (toolbar),
  `AccessibilityTest`; JVM `NavigationHudDeltaFormatTest`,
  `RideChromeContrastTest` (every ride role pair, and the faded readouts).

## Lane guidance

When the road being left has two or more lanes in the travel direction, the
guidance card shows a lane strip under the street name with the lane(s) for the
next maneuver highlighted. Data: `moto_lanes` edge KV (`graph-pipeline.md`).

- **App:** `MotoPathDetailsBuilderFactory` adds `KVStringDetails("moto_lanes")`
  (travel direction); `buildGhRequest` requests it;
  `RouteTrackFactory.segmentLanes` expands it per segment.
  `LaneGuidance.forTurn` reads the segment entering the maneuver (looking back
  ≤150 m through unknown segments, never past the previous maneuver, stopping
  at a known single lane).
- `LaneGuidance.recommend`: marked lanes carrying the turn arrow; for
  keep/slight, through lanes when none carries it; else an unmarked outer lane;
  contradicting marked data shows nothing. Count-only roads suggest the
  outermost lane on the turn side (U-turns: the oncoming side for the country).
  Continue, roundabouts and arrival show no lanes.
- **UI:** `LaneGuidanceRow` in `NavigationHud.kt`: a darker strip under the
  card's text (the card colour mixed 28 % toward black), one cell per lane
  (`laneCellWidthDp`: 56 dp down to 16 dp, sharing the fixed card's real width so
  any lane count fits) divided by dashed markings. The recommended lanes are
  filled with the card's content colour with strip-coloured arrows; the others
  draw their arrows at 45 % (colour-neutral, so B&W-safe); a lane with no marking
  data draws a plain dim ahead arrow so every cell reads alike. Not a touch target;
  `contentDescription = "Lane guidance: use … of N[, suggested]"`,
  `RecommendedLanesKey` (`"1,2/3"`, `"0/3 suggested"`).
- Tests: JVM `LaneGuidanceTest`, `RouteRequestsTest`,
  `GraphAndGeocoderAssetIntegrityTest` (graph carries `moto_lanes`);
  instrumented `LaneGuidanceHudTest`,
  `RouteCorpusTest.cbdRouteCarriesLaneGuidanceFromTheGraph`.
  `visual.py lanes --from '-27.4698,153.0251' --to '-27.5598,153.0811'`.

## Off-route rebuild

- **Detection** (`NavigationSession.onMissed`): a fix farther than
  `matchingThresholdM` (50 m, plus GPS accuracy up to 30 m) from the route is
  a miss. Each miss adds `FAST_MISS_WEIGHT` (4) while moving, or
  `SLOW_MISS_WEIGHT` (1) below 3 km/h; a matched fix resets the counter. The
  rebuild fires past `ON_ROUTE_MISSED_COUNT` (10): the third moving miss
  (about 1 s when fixes arrive every 500 ms), or the eleventh slow one, so GPS
  drift at a stop does not reroute. A pending ride suggestion holds the rebuild.
- **Origin:** `RebuildRequest` carries the rider's confirming fix, never the
  last on-route point. That point is behind the rider, and a route from it
  leads back onto the road the rider has left, so the next fixes miss again
  and a second rebuild follows.
- **Direction:** `RebuildRequest.headingDeg` (`DirectionAccumulator`, the
  last ~70 m of movement) becomes `RouteParams.startHeading`, a GraphHopper
  start heading (`buildGhRequest(startHeading)`, part of `RouteKey` and
  `RidePlanKey`). The route then snaps to the rider's carriageway and leaves
  in the rider's direction; GraphHopper's heading penalty still allows a
  U-turn where no forward way exists.
- **Routing:** riding disables the seven-style search, so a rebuild is one
  single-route request through `RouteSearchCoordinator`. On the desktop JVM a
  100 km rebuild takes 30–100 ms and 1,700 km about 300 ms at every style.
- **While it runs:** the matcher stays frozen, but the marker keeps following
  the rider's raw fixes and any off-route `RecoveryGlide` keeps easing.
- Tests: JVM `NavigationSessionTest` (third-fix boundary),
  `GpsGapSessionTest` (origin, heading, glide during a rebuild),
  `RouteRequestsTest` (one heading per point); instrumented
  `NavigationSessionOnDeviceTest`.

## GPS request rate

- `GpsFixSource` passes ride mode to `AdaptiveLocationPolicy` for each stream.
  `RouteScreen` replaces the stream when `guidanceRequested` changes.
- GPS requests use a 500 ms interval while moving or acquiring satellites.
  Ride mode keeps that interval when stopped, with unknown speed, or during a
  GPS outage. Android may deliver fixes less often than requested.
- Outside ride mode, healthy GPS slows to 15 seconds after 60 seconds without
  a fix at or above 1 m/s. After a GPS outage, the planner keeps the existing
  15-second GPS probe. Network and passive fallback rates do not change.
- Tests: JVM `AdaptiveLocationPolicyTest` covers stopped rides, unknown speed,
  outages and recovery. Instrumented `GpsFixSourceOnDeviceTest` checks the
  registered interval beyond the stationary cutoff and stream cleanup.

## GPS gaps

When fixes stop mid-ride, guidance keeps going along the route, then eases back
onto GPS.

- **Estimate** (`routing/navigation/GpsGapEstimation.kt`,
  `RouteProgressEstimator`, pure): anchored on every *matched* fix. 3 s after
  the last fix, if the smoothed speed was ≥ 2 m/s, the position advances along
  the route at the rider's pace ratio (smoothed speed ÷ model speed over ±100 m,
  clamped 0.5–1.6) integrated through the model times. It stops 50 m before the
  destination (only a real fix finishes a ride) and after 3 minutes
  (`PositionSource.LOST`, published once). A stopped rider is never estimated;
  an off-route miss clears the anchor.
- **What an estimate may change:** position, bearing, turn distances, remaining
  distance/time, voice turn prompts. Never the monotonic completion bar, the
  pace delta, off-route misses, or the matcher, so `currentRouteCoveredM()`
  (suggestions, vias, rebuild banking) stays on the last real fix. Suggestions
  never anchor on an estimated or easing position.
- **Recovery:** the first fix after a gap is searched for from the last real fix
  to past the estimate (`PolylineMatcher.relocate`; the whole route if expired),
  scoring perpendicular distance + 0.05 × along-route disagreement, so a
  hairpin's other leg cannot capture it. Fixes coarser than 50 m do not end an
  estimate. If the recovery fix misses the route, the rebuild starts from the
  rider.
- **Reconciliation** (`RecoveryGlide`): distances, turns and voice switch to the
  live fix at once; only the marker/camera eases (smoothstep, 1.2–3 s, +1 s per
  250 m of correction, in along-route metres; lat/lon blend if off route).
  `PositionSource.RECONCILING` meanwhile; the live offset is extrapolated
  ≤1.5 s between fixes so the marker never steps backwards.
- **Wiring:** `NavigationSession.onTick(nowMs)` from a 500 ms ticker in
  `NavigationController` (started by `start`, cancelled by `stop`). Fixes and
  ticks share the controller's elapsed-realtime `clock`, independent of a debug
  replay clock. `setEstimationHandlers(allowed, onEstimated)`: RouteScreen
  disables estimation while queued debug fixes own guidance (unless a fix sets
  `allowEstimate`) and feeds estimated snapshots to voice guidance.
- **UI:** the data bar shows "—" over **No GPS** while estimating or lost
  ("GPS signal lost, position estimated" / "GPS signal lost"). A **No GPS
  chip** pops up under the guidance card (`errorContainer` chip,
  `ms_location_disabled`, "No GPS" over "position estimated" / "position
  held"; the card's width), driven by `NavigationController.gpsSignalLost`
  (`GpsSignal.isLost`: no fix from the location stream for 3 s). The position
  source does not control the chip. Fresh fallback fixes hide it even when
  guidance keeps an estimate because the fixes are too coarse for route matching.
  The chip also covers stopped riders, off-route rides and rides before the
  first fix. It stays hidden outside a ride and while debug fixes hold
  estimation off. The HUD
  reserves `trailingClearance` (`RideToolbarWidth` + 8 dp) on the right, so a
  long street name ellipsizes. Description "No GPS
  fix[, position estimated along the route | , position held]".
- **Faded readouts:** while `gpsSignalLost`, GPS-derived values (speed,
  time/distance left, arrival, pace delta) fade to
  `NavigationHudFormat.STALE_READOUT_ALPHA` (0.55, 400 ms ease) and the pace
  delta drops its hue. The guidance card's manoeuvre glyph fades to
  `GUIDANCE_STALE_ALPHA` (0.8, the lowest that keeps 3:1 as a graphic).
  "No GPS", the turn distance, street name and lanes never fade. Both
  surfaces set `stateDescription` = "Not current, no GPS fix".
  `RideChromeContrastTest` pins the faded pairs.
- Test seams: `PositionSourceKey` ("gps"/"estimated"/"lost"/"reconciling"),
  `GpsSignalLostKey`; debug builds publish `cache/visual-guidance.json` and the
  fix ack carries `positionSource`.
- Tests: JVM `GpsGapEstimationTest`, `GpsGapSessionTest`,
  `NavigationHudGpsFormatTest`; instrumented `GpsGapControllerOnDeviceTest`
  (real ticker, injected clock), `GpsLossHudTest`. `visual.py gps-loss --from …
  --to … [--recover-offset M]` captures `gps-loss-00-riding` …
  `gps-loss-06-stopped-no-fix` (the mid-glide frame is optional).
  `gps-loss-02-fallback-fixes` captures an ongoing estimate without the chip
  while fresh coarse locations arrive.

## Ride suggestions

Mid-ride scenic suggestions, on by default (`route_preferences/ride_suggestions`,
a switch row under "While riding" in Route settings; suggestions-only Apply persists without
re-routing). Fastest rides never check. Remaining GPX/shaping points suppress
checks until passed; off-route rebuilds keep them.

- Pure logic in `routing/suggestion/` (geometry, scorer, scheduler, planner,
  acceptance, engine). `NavigationController` owns the Android binding;
  `RideSuggestionSearch` makes the two routing calls on one
  background-priority thread. Battery facts are cached ≤1 minute.
- **Cadence:** first check after 5 km; later checks need 10 km and 10 minutes
  moving. Checks need OnRoute, ≥25 km/h and ≥15 km remaining. Battery saver or
  an unplugged battery below 20% pauses checks. At most three proposals per
  rolling hour; Skip/expiry waits 20 minutes and 15 km.
- **Search:** a 25 km window (up to 35 km near the destination) starting 1–2 km
  ahead, against the alternatives of the stop two higher (capped at Max
  twisty). The chosen road becomes 2–8 shaping points; the full replacement is
  re-routed at the rider's style and measured again; every quoted cost comes
  from it.
- **Thresholds:** 3 km continuous distinct road, 3 km more curves, +10 points
  curvy share; added time ≤12 minutes and ≤50% of the replaced stretch,
  distance ≤1.4×, ≥400 curvy metres per added minute; the fork 800 m or 45 s
  ahead (whichever is farther) and within 10 km. Do not loosen these to
  manufacture a fixture. `DiversionScorer` keeps prompt, HUD and tests on the
  same rules.
- **Offer:** spoken once with cost, curves, fork and choice; a reminder within
  150 m or 8 s of the fork. Take, or riding the diversion, installs the measured
  replacement and confirms by voice; Skip or continuing clears it. A pending
  proposal holds off-route rebuilding. The pending line is teal (grey in B&W).
- **Card** (`RideSuggestionBanner`): `surfaceContainerHigh`, extra-large radius,
  at most 340 dp. A 48 dp `tertiaryContainer` tile with `ms_alt_route`, the title
  "Twistier road ahead" (`titleLarge`) over the summary (`bodyMedium`,
  `onSurfaceVariant`), and under them an `OutlinedButton` **Skip** (left) and a
  tertiary `Button` **Take** (right, with `ms_check`), each ≥64 dp tall. Offers
  last until the rider passes the fork, so the optional countdown ring
  (`remainingFraction`, `RideSuggestionFormat.remainingFraction`: 1 when offered,
  0 at the fork) is drawn only when the caller supplies it. B&W: neutral tile
  and an `inverseSurface` Take. Descriptions unchanged
  (`Scenic diversion: …`, `Take scenic diversion`, `Skip scenic diversion`).
- Test seams `RideSuggestionKey`, `RideSuggestionsEnabledKey`. JVM suites cover
  every pure component and the speech/banner units; instrumented
  `RideSuggestionHudTest`, `RideSuggestionControllerTest`,
  `RouteSettingsApplyTest`, accessibility, font scale, corpus, perf; fuzz
  `ToggleRideSuggestions`, `TakeSuggestion`, `SkipSuggestion` (keep glove-size
  and overlap oracles in sync).
- `visual.py suggestions` uses the real search and GPS replay, exports proposal
  geometry to debug-only `cache/visual-suggestion.json`, and captures pre-check,
  offer, near-fork and resolution frames (`--decision take|skip|ride|continue`,
  `--black-and-white both`). Debug fixes may carry a monotonic replay clock;
  production uses elapsed realtime.
