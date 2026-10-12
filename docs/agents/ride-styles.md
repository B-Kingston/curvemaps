# Ride styles: seven different rides, the slider, ghosts

Product rule: all seven stops show seven different rides, with no errors and
no repeats unless the graph truly has no other road. The weighting itself is in
`routing.md`. Use the rater (`rater.md`) replay and the probes as evidence when
retuning anything here.

## One route per stop (no carousel)

The planner shows exactly one route, the selected stop's, on a full-width route
card (`RoutePlanPanel.RouteCard`, `tertiaryContainer`: "<style> · N% curvy",
"time · distance", a curvy-share meter and a `bookmark_add` button; the
bookmark saves in one tap and long-press opens the save bubble, `saved-rides.md`;
tests find it as `ROUTE_CARD` in `ui/RouteCards.kt`, content description
`"Route 1: <style>, …"`). The slider is the only "show me something different"
axis. While the selected stop is searched the card is the "Finding this ride…"
placeholder (`PendingRouteCard`: M3 Expressive `LoadingIndicator`, "All seven
styles are searched together"; content description `"Finding the <style> ride"`).

- `GraphHopperRouter.route(…, alternatives = false)` (default) returns one path.
  `alternatives = true` keeps the old machinery for two users: ride suggestions
  (`ride-guidance.md`) and saved-ride loads (`preferredGeometry`, matched with
  `RouteSimilarity`, only the match shown).
- With alternatives, up to 3 paths: the style's optimum first, then paths
  sharing at most `AlternativePolicy.SIBLING_MAX_SHARE` (60%) of their roads
  with every other path, within the style's time/distance budget of its primary
  (table in `routing.md`), without local divergence/rejoin bubbles over 1.5 km
  extra and 2.25x the primary section, and with one continuous distinct
  stretch of at least `clamp(5% of distance, 750 m, 3 km)`; retries softly
  penalise accepted paths' roads. Do NOT judge alternatives against Fastest: a
  curvy ride is legitimately slow.

## The stop-by-stop ladder (`DistinctRideStyles.resolve`)

`RideStyleOptions` starts each stop's optimum search, two at a time.
`DistinctRideStyles.resolve` searches the stops from Fastest up, then the
joint choice (below) picks all seven from every ride found.

- **Different:** each accepted ride differs from every lower stop. `sameRide`
  rejects ≥99% shared roads and tiny road swaps; at least one route in each
  pair needs a continuous unshared stretch of `clamp(5% of distance, 750 m, 3 km)`.
- **Three searches**, each only when the one before found no ride within the
  stop's budget:
  1. `MOTO_CURVE_WEIGHT` escalation, up to six rounds.
  2. Bounded penalties (0.25x–16x) on lower stops' roads at the stop's own
     curve weight. Edges on a small loop (`SmallLoopEdges`, `routing.md`) take
     no penalty, so a penalised search cannot jog round a junction.
  3. **Looking around**, skipped when stages 1–2 found a `RELAXED` ride at the
     floor (reviewed: 63–87% of look-around stops were nonsense against 47–62%
     for relaxed rides). The stop's own weight routed through one via on rings
     round the corridor midpoint (`detourRings`: 0.35/0.75/1.5/2.5 × beeline,
     clamped 0.7–25 km, 12 bearings). Among a ring's rides within budget, at the
     floor, and adding curves over the stop's optimum (`curveGainPerMinute` > 0;
     without it the gentlest detour was a straight farmland square), the lowest
     route weight at the stop's own preference wins (ranking by curves per
     minute picked a 97-minute dirt loop). The next ring only when a ring holds
     none. Plans with imported shaping points get no rings. Stage 3 exists
     because a uniform penalty cannot find a loop that reuses shared approach
     streets (Skyline Circuit → Palmwoods: under any penalty the direct road
     stayed cheapest).
- **Vias** snap to the nearest two-way through road
  (`ThroughRoadSnap.snap(twoWay = true)`, applied for
  `route(twoWayVias = true)`): trunk–tertiary and unclassified with car access
  both ways; never motorway, residential/service, or one carriageway of a
  divided road (passing such a via drove out and back on the other
  carriageway). Routed with `pass_through`.
- **Not a ride:** driving more than 150 m of its own road again
  (`drivesARoadTwice`, a via split counts once), or a U-turn instruction more
  than `END_U_TURN_METRES` (300 m) from both ends (`turnsAround`), unless the
  stop's own optimum turns within `SHARED_U_TURN_METRES` (50 m) of the same
  place. U-turns nearer an end are snapping artefacts (rejecting them threw
  away 71% of U-turn rejections). Round trips share the check.
- **Loops** (`loopKind`/`loopMetres`, every search and the joint choice): a
  ride that passes a point of its own road again at least 300 m later (unless
  Fastest passes a point twice within 50 m of there: the roads force it), or
  that leaves Fastest's roads and comes back onto them at most 500 m further
  along after at least 3 times that and 300 m off them. A loop shorter than
  max(3 km, 10% of the ride) is no ride for any stop, its own optimum
  included (Skyline Circuit → Palmwoods with hills and stops on: Max twisty
  circled a block north of Palmwoods and came back along the 70 m it left by;
  Balanced passed 26 m from Palmwoods and circled a block to arrive from the
  other side). Longer loops are lassos. Switchbacks and interchange loops pass
  no point twice and leave no road, so neither shape sees them. Comparing
  against Fastest alone caught 47 of the 49 loops the stop's own optimum also
  exposed on 80 Sunshine Coast hops. Reviewed (Claude Haiku 5.5, both
  versions of each changed corridor in the same window): 92 Queensland
  1.5–12 km hops nonsense stops 159 → 134 (−0.27 ± 0.11 per corridor), 25
  Welsh hops 34 → 23, Max twisty most (changed stops 35 → 18); 45 long
  Queensland corridors 49 → 58 (+0.20 ± 0.14: the joint choice moves other
  stops' rides, Balanced and Twisty worse, "not curvier than a lower stop"
  up). It changes 30% of short hops and 2–5% of long corridors, adds about 2
  repeats per 100 short hops (where every other ride was a small loop), and
  re-searching a loop's ride with its roads avoided reviewed worse.
- **Lasso** (`isLasso`/`lassoMeters` for look-around rides: back within 60 m
  of a point passed at least max(3 km, 10% of the ride) earlier; or a loop
  that long, above): stands in only when the stop would otherwise repeat. On
  2–6 km Blackall Range hops every ride but Fastest is one.
- **Budget classes** (`Option.budget`): `STYLE` (within the stop's budget, no
  local-detour bubble), `RELAXED` (within twice the allowance), `NONE`. The
  detour budget's percentages apply to at least a 6 min / 5 km ride
  (`AlternativePolicy.BUDGET_REFERENCE_*`), and a bubble at least half the
  candidate is the ride itself.
- **Settling:** a `STYLE` candidate settles the stop at once only when it is
  **no straighter than the stop below** (card curvy share ≥ the last own ride
  below − `CURVINESS_TOLERANCE` 0.02, `curvinessFloor`) and, on the
  look-around, adds curves. Otherwise the stop keeps (`Kept`), in order: the
  gentlest `RELAXED` at the floor; the curviest `STYLE`; the gentlest
  `RELAXED`; the gentlest `NONE`; a ride a lower stop found but did not show
  (`Found`); a lasso. Only a graph with no other road at all
  repeats the stop below (`Option.repeats`, logged "repeats <style> (the graph
  has no other road)"). The plan never fails on exhaustion, only when a search
  throws; a failed search does not prove later parameters fail.

## The joint choice (`RideLadder`)

Settling stop by stop chooses without knowing what higher stops need; on a
corridor with few curvy roads that only moves a defect up the slider. So
`resolve` records every ride its searches route (`Pool`: optima, escalations,
penalties, look-arounds; deduplicated by roads), classifies each for every stop
against that stop's own optimum (`budgetFor`; a ride that drives a road twice
or turns around where the optimum does not is no ride for it), and
`RideLadder.choose` picks one ride per stop by beam search (512 partial
ladders):

- Fastest is its own optimum; every pair differs (`sameRide`, hard).
- **A ride only moves up the slider:** a stop may show a ride its own search or
  a lower stop's found (`Pool.Entry.foundBy`), never a higher stop's (otherwise
  low stops took the top stops' forced detours).
- Winner: the ladder with the highest chance that a reviewer calls none of
  stops 2–7 nonsense, cost Σ −ln(1 − p). Summing p instead let one stop absorb
  every compromise.
- **p** is a logistic model fitted on 3,039 reviewed stops on 220 corridors
  (conditional logit per plan, calibrated per level). Features: curvy-share gain
  over the stop below (−1.59 per 10 points, capped at 10) and loss (+1.12);
  straighter than the curviest lower stop (+1.53 per 10 points); largest road
  share with a lower stop (+0.67, +1.35 more at ≥95%); Mostly fast/Balanced time
  over their own optimum (+1.89 per 100%); `RELAXED` +0.39; `NONE` +1.25;
  look-around +0.60; gravel share (+0.64 per 10%); the stop's own optimum
  (−1.12).
- **Guardrails (product rules, not fits):** the fit's reward for longer Curvy–Max
  twisty rides is not used (it chose a 160-minute ride on a 51-minute corridor);
  a Mostly fast/Balanced `RELAXED` ride costs one more nonsense stop
  (`FAST_STOP_RELAXED_COST`); a ride beyond every budget costs 40
  (`BEYOND_BUDGET_COST`, more than any within-budget ladder, ~7.9 max); a
  lasso 50; a repeat 500 + 0.5 × stop. So a stop repeats only when
  no ride is left, and then the low stops repeat first.
- The stop-by-stop ladder is the seed, kept when nothing scores better, and
  kept outright when the pool exceeds `MAX_POOL` (160; a 482-plan sweep found a
  median of 18, max 94). The choice adds no searches. A chosen ride found by
  another stop carries `Option.foundBy`. `resolve(select = false)` returns the
  stop-by-stop ladder (its mechanics tests use it).
- `RideStyleOptions` completes Fastest at once and the six stops above together
  when the choice is made (`onSearched` cancels a stop's speculative searches,
  `onResolved` reports final rides).
- Measured on 200 reviewed corridors: nonsense stops per corridor 3.08 → 2.77;
  Max twisty nonsense 133 → 93; look-around stops 26% → 17%; costs: Twisty
  110 → 119 and "same as another stop" 1.35 → 1.59 per corridor. Plan time
  unchanged (1.1–1.3 s on the desktop). The rater records both ladders
  (`selection`: `settled` stops, `settledCost`, `chosenCost`, `poolSize`,
  `changed`; stops carry `foundBy`).

## Plan lifecycle and determinism

- A request joins its plan while it runs. New points or toggles cancel the old
  plan; cancelling a request leaves its plan running; a failed plan is
  forgotten. Guidance computes only the requested optimum.
- Saved loads bypass the seven-stop search. A GPX stop list is an ordinary plan
  through its stops (no rings); a followed GPX track bypasses the plan
  (`gpx-import.md`). Round trips use `RoundTrips.resolve` (`round-trips.md`).
- The router caches by `(points, style, blockUnpaved, avoidMotorways,
  alternatives, curveWeight, penalizedEdges, roadPenalty)`. Penalties belong to
  one deterministic plan search, never to the order the rider visits stops:
  never restore visit-history penalties
  (`RouteCorpusTest.styleResultsDoNotDependOnVisitingOrder`).

## Slider UI (`RideStyleSlider.kt`)

- A seven-stop M3 wavy slider: seven crests (heights 0.3, 0.45, 0.6, 0.7, 0.8,
  0.9, 1.0) in `primary`, a slim vertical pill handle (4 dp, 6 dp while dragging,
  `primary`), a 64 dp touch region, each detent on its crest. The wave starts on
  the Fastest crest and ends on the Max twisty crest: the track, the level dots
  and the handle's two ends are the same two points, `RIDE_STYLE_SLIDER_END_INSET`
  (8 dp) in from the box's edges, and the pane's label and captions start and end
  there too. `RideStyleSliderGeometry` shares the level/track mapping
  (`fractionAtLevel` = level / 6, `positionAtFraction` into the wave) and the
  envelope (Fastest shortest). Level dots are `outlineVariant`, the selected
  one `primary`.
- `RideStyleDetents` ramps approach pulses through three strength bands, clicks
  within 0.07 of a stop, with 0.18-level hysteresis. `RideStyleHaptics` uses
  amplitude-controlled pulses when available, else platform haptics, and
  respects system preferences. Release spring-snaps to the nearest level;
  cancelled drags restore the initial level and route.
- Semantics: `contentDescription = "Ride style level N"`, style
  `stateDescription`, stepped range/set-progress, keyboard arrows/Home/End.
- While dragging the wave drifts (one wavelength per 2.4 s at Fastest to 0.8 s
  at Max twisty) and springs back to a whole wavelength on release. The drift
  runs through the coroutine's `InfiniteAnimationPolicy` (`infiniteFrameNanos`),
  which the Compose test clock pauses: a plain `withFrameNanos` loop would keep
  Compose busy for as long as a finger is down and every test that drags then
  fails with `ComposeNotIdleException`.
- **Growing wave:** `readyStops` (from `RideStyleOptions.progress`) grows one
  hump per finished stop (`growthHeight`/`growthFront`): unfinished stops are a flat
  6 dp `secondaryContainer` line and faint level dots, and a finished plan is one
  wave end to end whichever stop the handle is on; each crest pops up with ~7% overshoot; a finished plan grows
  out in one sweep. The card shows the Finding placeholder (RIDE disabled, a
  `LinearWavyProgressIndicator` "Searching styles" where the ETA goes) until the
  selected stop is ready.
- **Live routes and ghosts:** `RouteScreen.requestRideStyle` selects each
  rounded level during a drag; `RideStyleOptions.readyOption` returns a settled
  ride immediately, an unfinished stop uses the normal request, and release
  does not repeat it. `map/RouteGhosts.kt` keeps the previous two displayed
  routes under the current one as grey 4.5 dp lines (newest 55%, older 34%
  opacity), excluding the current geometry. A new plan, endpoint edit, dataset
  switch, idle, error or guidance clears them; a same-plan loading state keeps
  them. Ghosts are not selectable and change neither the route count nor
  camera bounds.

## Tests

JVM `DistinctRideStylesTest` (stages, budget classes, spur guards, end U-turns,
rings, the floor, curve gain, look-around skip and weight ranking, lassos,
loops (block loops, rejoins, forced loops, switchbacks, no stop shows one),
passed-over rides, the joint choice), `RideLadderTest` (curviest ride to the
top, near copy vs straighter top, distinct rides, repeats only when nothing
fits and low first, lasso only for a repeat, preferences, Fastest fixed, never
worse than the seed, deterministic on random plans), `AlternativePolicyTest`,
`ThroughRoadSnapTest`, `RouteRequestsTest` (`pass_through` only with a
look-around via), `RideStyleOptionsTest`, `RouteGhostsTest`; opt-in
`PalmwoodsKenilworthTest` (incl. Toowoomba → Gatton, a start on the wrong
carriageway) and `SkylinePalmwoodsTest` (`STYLE_CORRIDOR=1`). Instrumented
`RouteCorpusTest.everyStopBecomesItsOwnRideOnTheRealGraph` (corpus entry
"Skyline Circuit to Palmwoods"), `CarouselMapConsistencyTest`,
`RideStylePlannerTest` (incl. the held-drag ghost case), `RideStyleSliderTest`,
`RouteGhostsOnDeviceTest`; fuzz `RotateKnob` (−5..5, a no-op until a search
opens the slider). `visual.py styles --from … --to …` exports each stop's
geometry, captures all seven cards (and drag frames with ghosts), and checks
every pair with a 12 m road-proximity tolerance; `visual.py route` captures
`styles-growing-NN` frames while a higher stop is still searching.
