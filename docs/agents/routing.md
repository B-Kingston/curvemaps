# Routing: ride styles, weighting, road toggles, ride factors

How one route is weighted. How the seven stops become seven different rides is
in `ride-styles.md`; round trips and GPX in their own docs. Graph and
custom-model build: `graph-pipeline.md`.

## Ride style stops

`GraphHopperRouter.route(from, to, complexity, blockUnpaved, avoidMotorways)`
takes the slider level as `complexity`; `RideStyle.fromLevel` clamps it onto
seven stops (`RideStyle.kt`), sent as the `moto_ride_style` hint (constants in
`ComplexityWeighting.kt`).

| stop | `curveWeight` | fully curvy road wins vs equally fast straight road while shorter than | vs 100 km/h straight at 60 km/h | alternative budget (time / distance vs the style's primary) |
|---|---|---|---|---|
| Fastest (0, default) | 0.0 | 1.00x | 0.60x | 1.50x / 1.65x |
| Mostly fast (1) | 0.3 | 1.45x | 0.87x | 1.50x / 1.65x |
| Balanced (2) | 0.6 | 1.90x | 1.14x | 1.50x / 1.65x |
| Curvy (3) | 1.05 | 2.58x | 1.55x | 1.625x / 1.75x |
| Twisty (4) | 1.5 | 3.25x | 1.95x | 1.75x / 1.85x |
| Very twisty (5) | 2.25 | 4.38x | 2.63x | 1.875x / 1.975x |
| Max twisty (6) | 3.0 | 5.50x | 3.30x | 2.00x / 2.10x |

The even stops are the four calibrated styles; each odd stop sits exactly
halfway between its neighbours (`RideStyleTest.midStopsSitHalfwayBetweenTheirCalibratedNeighbours`).

Every user route sets `ch.disable=true`. Styles, toggles and factors need no
graph re-import or profile/helper change. Empty hints during graph load return
pure CustomWeighting (preserving the stored profile and CH);
`GraphHopperRouter` always supplies `moto_ride_style`.

## `ComplexityWeighting`

`wFastest * (1 + 1.5 * curveWeight * (1 - curveExposure)^2 + toggles + siblingPenalty)`.
Every term is a non-negative multiple of Fastest travel time.

- **The straightness term is squared**, so a moderately curvy range road
  (exposure ~0.5) keeps a quarter of the straight penalty. With a linear term,
  Palmwoods → Brisbane at Max twisty could never pick the D'Aguilar loop (it
  keeps as many straight minutes as the coast and only adds part-curvy ones).
  Do not go back to linear, and do not chase it with a huge `curveWeight` (it
  weakens the Fastest-bound search heuristic).
- **Do NOT reintroduce `w_custom - w_fastest`.** It carried the model's 90 s/km
  `distance_influence` and 0.1 motorway priority, which capped the curve
  preference at 1.6x and made the first click a hidden "avoid motorways". The
  custom model's priority is used only as a hard block (priority 0: private,
  high track grades).
- **Curve exposure:** consecutive point triples give circumcircle radii
  (`circumradiusMeters`/`curveRadiusWeight`); each segment takes the tighter
  adjacent radius and a length-weighted score over the 30/60/100/175 m bands;
  the weighting uses the larger of that and the accumulated-heading exposure.
  `RouteCurviness.curvyDistanceFraction` reuses the same corner definition for
  the card's "N% curvy". Fastest never reads edge geometry.
- **Curve credit depends on the road** (`ComplexityWeighting.funCredit`,
  applied before the edge's factor is cached): `TOWN_STREET_CLASSES`
  (residential, living_street, service) earn none and their geometry is never
  read; known `UNPAVED_SURFACES` earn `UNPAVED_CURVE_CREDIT` (0.5) of their
  exposure (hills included); missing/other surfaces keep full credit. From a
  100-corridor agent review: Max twisty rode 3.3 km of town streets per ride
  (the curvy crescents), and 31% of its curvy km were gravel. Half credit lets
  a good sealed road beat an equally curvy gravel one without hiding gravel
  where it is far curvier or the only road. Block unpaved still removes it.
  Tests: `ComplexityWeightingTest.townStreetsEarnNoCurveCreditOnCurvyStops`/
  `unpavedRoadsEarnHalfTheirCurveCredit`.
  `PalmwoodsKenilworthTest.blockUnpavedLeavesNoGravelOnAnyStop` (opt-in) proves
  that.
- **Junction structure earns no curve credit** (`ComplexityWeighting`,
  `SmallLoopEdges`): links (`road_class_link`), roundabouts and every edge on
  a small loop of drivable road pay the whole straight-road penalty. A small
  loop: some other path drivable in either direction joins the edge's ends
  within `SMALL_LOOP_PERIMETER_METERS` (500 m) minus its length (junction
  triangles, carriageways split at intersections, interchange pieces, town
  blocks). Why: the curve score is per edge, so a 46 m slip lane or a kinked
  18 m connector scores as curvy as a mountain road, and a curvy stop left the
  road for any junction structure shorter than the multiple it saved on the
  straight road beside it (Woombye Palmwoods Road at Taintons Road: a 64 m
  triangle beat 39 m of straight road from Mostly fast up). On 151 rater
  corridors a quarter of Curvy and 42% of Max twisty optima jogged; with the
  rule none do within the perimeter, because every way round a small loop
  costs the same multiple of its travel time and the faster wins (proved by
  `SmallLoopEdgesTest.curvyStopsTakeNoJogWithinThePerimeterOnRandomNetworks`).
  The loop check runs only for an edge whose geometry would earn credit, once
  per edge per loaded graph (one `SmallLoopEdges` per `loadMotoGraphHopper`;
  GraphHopper builds a weighting factory per request, and one before the
  graph's edges load, so its state is sized on first use). It costs 1.4% of
  the curve credit on rural roads and 25–38% in residential-density areas.
  Road penalties (`moto_sibling_edges`) skip small-loop edges for the same
  reason: a penalised main-road piece beside an unpenalised slip lane made
  penalised searches jog round it. With Avoid stops and towns, the per-edge
  stop count still lets a slip lane bypass a signal (even on Fastest).
- **Per-edge factors are kept per graph** (`EdgeFactors`, one per
  `loadMotoGraphHopper`, like `SmallLoopEdges`): each base edge's straight-road
  factor (once without and once with hills) and its half stops per storage
  direction are computed on first use and shared by every later request. A
  seven-stop plan routes a median of 86 searches over the same roads; per
  request, re-measuring curves and re-reading stops was 38% of planning CPU
  (`PlanCostProbe`). The curve factor is measured along the edge's storage
  direction, so it never depends on which way an earlier search met the edge;
  QueryGraph's virtual edges (split at snapped points) stay per request.
  Blocks of 4,096 edges are allocated on first touch (8 bytes per edge per
  variant, 2 for stops). Measured on nine Queensland corridors: planning CPU
  −40%, every chosen ride identical (`laterRequestsReuseTheGraphsSharedEdgeFactors`,
  `sharedCurveFactorsDoNotDependOnWhichWayAnEdgeWasFirstMet`).
- **`FastestWeighting` is app-owned** (not GraphHopper's `SpeedWeighting`):
  model speeds are km/h, so edge seconds are `distanceMetres * 3.6 / speedKmh`.
  ETA (`calcEdgeMillis`) is always the physical Fastest time; turn costs are
  Fastest's. Access (`car_access`, including reverse reads) is a hard
  directional constraint at every stop. `calcMinWeightPerDistance()` uses
  Fastest's bound, admissible because every added term is non-negative.
- `pass_through` is honoured as a hard block on the unfavoured virtual edge
  (look-around vias, round-trip corners).
- Calibrate with `CurvyCorridorProbe` (`testing.md`). Measured (2026-10-11,
  junction structure without credit): Max twisty Palmwoods → Brisbane leads
  with 92 min / 9% curvy and offers the 152 min / 22% curvy range road (98 min
  / 13% and 155 min / 23% while slip lanes and roundabouts still earned credit,
  which the card's curvy share also counts); Fastest is unchanged, so the
  corpus golds stand.

## Road toggles (Route settings)

M3 `ListItem` rows with a `Switch` (check thumb icon) under "Road types" and
"While riding"; each row is one Switch-role toggle whose `contentDescription`
is its title. Persisted in `route_preferences` (`avoid_motorways`,
`block_unpaved_roads`), saved with rides, re-routing on Apply. The page is a
full-screen dialog (`SettingsPageDialog`: top app bar with Back, scrolling
rows, footer Cancel / Apply); the draft is separate from the applied values,
so Cancel and Back discard it.

- **Avoid motorways:** +9x travel time on motorway/trunk at every stop (soft;
  never strands a ride).
- **Block unpaved roads:** hard-blocks known unpaved surfaces.
- Curvy stops also add 9x on destination-only roads (mirroring the model's 0.1
  priority); Fastest does not.

## Ride factors (Route settings → "Include in route calculations")

Two checkbox rows (`RideFactors`; whole row is the Checkbox-role toggle,
described by its title, at least 48 dp), persisted in `route_preferences`
(`include_elevation`, `avoid_stops_and_towns`), part of
`RouteParams`/`RidePlanKey`/the router cache key, re-routing on Apply. Saves
store the factors the shown route was planned with and a load restores them
(schema v4, `saved-rides.md`). A map without a factor's data routes as if
it were off, and the dialog says so once the graph is loaded
(`GraphHopperRouter.features`).

- **Hills and elevation** (`moto_hills`), curvy stops only:
  `fun = 1 - (1 - curve) * (1 - 0.75 * hill)` (`hillyFunExposure`); `hill` maps
  an edge's relief per km from 20 m/km (flat: SRTM is a surface model, flat
  roads measure 10–20 m/km of tree/roof noise) to 70 m/km, over at least 250 m.
- **Avoid stops and towns** (`moto_stops_and_towns`), every stop: +20 s per
  stop (the one absolute term, still non-negative) and +0.5x / +1.5x travel
  time on `urban_density` residential / city edges. Stops are the `moto_stops`
  edge KV (`graph-pipeline.md`), read in storage direction (`REVERSE_STATE` xor
  `reverse`, also right for virtual edges).
- **Route card extras:** the card leads with the ride's gravel whatever the
  settings ("4.7 km gravel": `routeGravelMeters` sums the `surface` path detail
  over `UNPAVED_SURFACES`; `gravelText` stays silent under 100 m), then what an
  enabled factor measured: "N m climb" (`routeClimbMeters`, 10 m hysteresis;
  GraphHopper's raw `ascend` doubles flat-road climbs) and "N stops"
  (`HalfStopDetails`, one entry per edge, never merged).
- Measured (`RideFactorsProbe`): stops/towns takes Fastest Brisbane → Samford
  from 90 stops to 25 (+7 min); hills mostly agrees with curves in SEQ and
  matters where a straight hilly road competes (Max twisty Brisbane →
  Toowoomba climbs 2,100 m instead of 1,700 m).

## Tests

JVM `ComplexityWeightingTest` (hard blocks, no soft-priority leakage, per-style
straight penalties, non-saturation, toggles, town-street/unpaved/junction-piece credit,
hill/stop/town terms, direction, no data, non-negativity, Fastest reads no
geometry, pass-through block), `SmallLoopEdgesTest` (loop flags match a brute-force search, the Taintons
triangle, split pieces, no jog within the perimeter on random networks with a
control that jogs without the rule), opt-in `JunctionJogProbe` (`JOG_PROBE=1`,
real graph, plain optima and planned rides), `RideStyleTest` (level clamping incl. old
saved levels, mid stops, curvy-fraction bounds), `EdgeRideFeaturesTest`
(incl. the gravel note), `RouteRequestsTest` (single-route requests skip
`alternative_route`), `AlternativePolicyTest`, `MotoGraphLoaderTest`,
`MotoGraphImport --self-test`; instrumented `RouteCorpusTest` (every stop valid
with one route, alternatives' sibling share, order independence, styles /
avoid-motorways / ride factors measurably change the ride: `STYLE_PROBE`,
`MOTORWAY_PROBE` lines; `rideFactorsMeasurablyChangeTheRideOnTheRealGraph`),
`RouteSettingsApplyTest`, `AccessibilityTest`; fuzz
`ToggleAvoidMotorways`, `ToggleIncludeElevation`, `ToggleAvoidStopsAndTowns`
with the `RideFactorAccessible`/`RideFactorTargetSize` oracles.
`visual.py route|options` take `--style NAME|LEVEL` (alias `--complexity`),
`--avoid-motorways`, `--block-unpaved`; `route … --hills --stops-towns` and
`options --hills on|off --stops-towns on|off` capture `route-settings-factors`.
