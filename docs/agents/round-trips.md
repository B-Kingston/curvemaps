# Round trips

No graph rebuild or CI cache-seed bump is needed for anything here.

## Controls

- The **Round trip** toggle (`RoundTripToggle`, an M3 `ToggleButton` 52 x 56 dp,
  `Role.Switch`, `ROUND_TRIP_TOGGLE_DESCRIPTION` = "Round trip") sits under Use
  current location beside To. Off it is a quiet `surfaceContainerHighest` key
  with an `autorenew` symbol; on it takes M3's selected colours (`primary` /
  `onPrimary`, filled symbol). On/off is planner state (`rememberSaveable`) like
  the fields; toggling or changing the setting invalidates the plan.
- On, To gives way to the **settings card** (`RoundTripSettings`,
  `surfaceContainer`, 20 dp corners) under a single "From and back" field, with
  the two toggle keys side by side beside the field (Use current location left,
  Round trip right, `PLANNER_KEYS_START_SHAPE`/`PLANNER_KEYS_END_SHAPE`). The
  card is always open (there is no dialog and no menu):
  - a `SingleChoiceSegmentedButtonRow` (`RoundTripMeasureSelector`, description
    `ROUND_TRIP_MENU_TITLE` = "Measure the ride by"): **Ride time** /
    **Distance** / **Back by** (`Role.RadioButton`, `Selected`, 48 dp), the
    same options and the same `RoundTripWish.withMeasure` as the old measure
    menu;
  - the connected stepper (`RoundTripStepper`, 64 dp): a tonal **−** button
    (`ROUND_TRIP_SHORTER_DESCRIPTION`), the value card and a tonal **+**
    (`ROUND_TRIP_LONGER_DESCRIPTION`), muted at the end of the range. The value
    card ("1 h 30 min" over "≈ 80 km, back to From", the caption dropped above
    1.3x font scale) is a status, not a control: its description is
    `"Round trip length, <value>"` (or `"…, back by 5:30 pm"`) with a "Measured
    by …" state;
  - preset chips (`FilterChip`, `roundTripPresets`): the length model's
    `TIME_PRESETS` (30 min, 1 h, 2 h, 3 h, 4 h) on Ride time and
    `DISTANCE_PRESETS` (25, 50, 100, 150, 200 km) on Distance, the one equal to
    the value selected; none on Back by (a clock time). A chip sets only its own
    measure (`withPreset`).
- Panel height changes with the toggle; START stays put at the bottom. The card
  scrolls with the panel when the window is short.

## `RoundTripWish` (pure, `routing/`)

- One value per measure (ride time 15 min–8 h, distance 10–400 km, back-by as a
  wall-clock epoch minute) plus which is in use. Steppers move only that one
  (`stepped`: 15 min; 5 km to 50 km, then 10 km; back-by quarter hours between
  now+15 min and now+8 h); switching measure brings back its own value.
- A back-by time is held while ahead and falls back to now + ride time once
  passed. `length(now)` turns it into the ride time left, floored to 15 min so
  the plan key changes only each quarter hour (`rememberMinuteClock` ticks the
  shown values).
- Persisted in `route_preferences/round_trip_length` (`storageKey`, e.g.
  `measure:back_by,time:90,distance:80,back_by:29856000`; the old `time:90`
  form still parses).

## Planning

- START snapshots the wish (not its length), resolves From only and submits
  `RouteParams(from, to = from, roundTrip = wish.length(now))`; `RidePlanKey`
  includes `roundTrip` and `passThrough`. `RideStyleOptions` uses
  `RoundTrips.resolve` instead of `DistinctRideStyles`, each search a
  `RideStyleSearch(loop = shape)`; the screen routes `from → shape.vias → from`
  with `passThrough = true` and `viaSearchMetres = shape.searchMeters`.
- **Loop shape (`RoundTrips`):** a triangle, From plus two corners
  `APEX_DEGREES` (60°) apart at one radius, ridden straight through
  (`pass_through`). A tier is 8 bearings routed at once; tiers 2–4 rotate by
  22.5°/11.25°/33.75°. The first radius is `estimatedRoadMeters / (3.0 ×
  ROAD_FACTOR 1.6)` (time wishes at 55 km/h); each tier rescales the next by
  its median size ratio (outside ±15%, ≤2× per step, 0.25–3× the estimate).
- **Score:** `Loop.score` = route weight per metre (the A-to-B trade: Fastest
  likes quick roads, curvy stops the bends) × (1 + |ln size ratio|) × (1 + 3 ×
  retraced share).
- **Budgets:** `STYLE` = 0.8–1.25 of the wish, ≤12% ridden twice, a lasso ≤
  max(150 m, 0.5%); `RELAXED` = 0.6–1.6, ≤30%, lasso ≤ max(500 m, 2%); else
  `NONE`. U-turns are no loop.
- **Stem vs lasso** (`RoundTrips.retrace`): road ridden twice with passes at
  least half the loop apart is the **stem** (the way out and home, also from a
  loop street like Skyline Circuit); closer is a **lasso** (a corner on a side
  road pulling the ride off a road and back). A lassoed loop is routed again
  with that corner moved to where the twice-ridden road was first entered
  (`unlassoed`); both stay candidates. Size calibration uses only unrepaired
  loops.
- **Settling:** Fastest first; each stop takes its best different
  (`DistinctRideStyles.sameRide` vs every lower stop) `STYLE` loop that is no
  straighter than the stop below (curvy share − 0.02), searching up to
  `FLOOR_TIERS` (2) tiers for that floor and more tiers only while no `STYLE`
  loop differs; then the curviest `STYLE`, then `RELAXED`, then any. Only a
  start with no different loop repeats (`Option.repeats`; `Option.loop` names
  the shape). No loop for Fastest fails with `RoundTrips.NO_ROUND_TRIP_MESSAGE`.
- **Do not** hand loops out top-down or rotate tiers per stop (both measured
  worse), and do not drop pass-through (every failing corner retried without
  it U-turned at a dead end).
- **Corners** (`ThroughRoadSnap.snapPassable`, round trips only): the nearest
  through road by the plain rule (motorway–unclassified with car access either
  way; NOT the look-around's two-way rule, which shrank Samford's 80 km loop to
  59 km, and a mid-ride rebuild re-snaps corners with plain `snap`, which must
  find the same roads) whose edge is not a dead-end pocket (`pocketBehind`: a
  side that runs out within 2,000 junctions is a pocket and all its edges are
  skipped), else rings every 1.5 km out to `Shape.searchMeters` (30% of the
  radius, 1.5–9 km).
- **Measured** (`RoundTripProbeTest`, 6 SEQ starts × 1 h, 3 h, 40, 50, 150 km):
  seven different closed loops on all 30, 205 of 210 stops within budget, no
  over-allowance lasso within budget; 61–216 searches, 0.1–9 s on the desktop.
  Emulator: Samford 1 h, all seven in 4.6 s. Retune with the probe and
  `visual.py round-trip`, never by loosening budgets.

## Riding, saving, loading

- The loop's corners are its `ResponsePath.waypoints`
  (`RoundTrips.viaPointsOf`). An off-route rebuild routes rider → remaining
  corners → From with pass-through (`RouteParams.passThrough`). Ride
  suggestions treat the corners as shaping points ahead; an accepted suggestion
  clears `roundTrip`.
- A saved round trip's To name is `ROUND_TRIP_TO_NAME` ("Round trip"). Loading
  any ride that ends where it starts switches the planner to round trip with
  the measure set from the ride (`RoundTripWish.withRide`: `RoundTripLength.near`,
  or now + the ride's time for back-by; in memory only) and
  routes the saved shape. GPX imports switch round trip off.

## Tests

JVM `RoundTripLengthTest`, `RoundTripWishTest`, `RoundTripsTest` (geometry,
calibration, seven loops, floor, rescaled tier, repeat, failure message,
retrace/U-turn, stem vs lasso, lasso repair and allowance, budgets, corners),
`ThroughRoadSnapTest` (pockets, ring search), `RideStyleOptionsTest`; opt-in
`RoundTripProbeTest` (`ROUND_TRIP=1`, `ROUND_TRIP_STARTS`,
`ROUND_TRIP_LENGTHS=time:60,distance:150`); instrumented
`RouteCorpusTest.roundTripsAreSevenDifferentLoopsBackToTheStart` (incl. a
rebuild through a loop's own corners riding the same loop), `PlannerPanelTest`,
`AccessibilityTest`, `FontScaleTest`, `LifecycleRecreationTest`,
`CommonScreenSizeTest.plannerRoundTripSettingsAndKeysFitInsideThePanel`/
`plannerRoundTripMeasureSelectorFitsInsideThePanel`, `PlannerPanelTest.roundTripPresetChipsSetTheMeasureInUseOnly`;
JVM `RoundTripControlsTest` (texts, presets, key dimensions); fuzz `ToggleRoundTrip`/
`SetRoundTripLength(measure, steps)` (`UiTarget.ROUND_TRIP`/`ROUND_TRIP_LENGTH`)
with the `RoundTrip*` oracles (incl. `RoundTripSelectorOrphan`/
`RoundTripSelectorTargetSize`). `visual.py round-trip --from … [--minutes N |
--distance N | --back-by]` captures `round-trip-*` (gallery scenario
`round-trip`).
