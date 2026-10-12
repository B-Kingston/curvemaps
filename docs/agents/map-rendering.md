# Map rendering: route line, B&W ride map, backend, frame benchmark

None of this needs a graph or tile rebuild or a CI cache-seed bump. Style asset
pipeline: `map-assets.md`.

## MapLibre facts

- MapLibre 13.5.0: GeoJSON classes are `org.maplibre.geojson.*` (NOT
  `com.mapbox.*`; `android-sdk-geojson` arrives transitively). Route drawing
  uses `MapLibreMap.getStyle {}` (style-race-safe), not `MapView.style`.
- Map-layer colours (route line series, suggestion line, styles) live in
  `map/`, not `ui/theme/`.

## Curve shading on the route line (colour map only)

- `map/CurvyRouteShading.kt` gives the focused route a MapLibre
  `line-gradient` (route source created with `lineMetrics`) easing from the
  route colour toward `CURVY_ROUTE_COLOR` (`#D6006F`) on the most curvy
  stretches, in the planner and on the colour ride map. The B&W map keeps a
  solid white route (`drawRoutes` passes no gradient in `darkGuidanceMode`).
- **Measure:** the card's corner definition graded by tightness
  (`segmentCorners` in `routing/RouteCurviness.kt`, shared with
  `curvyDistanceFraction`, which must keep counting exactly the segments with a
  weight above 0), averaged over ±`CURVE_SHADE_HALF_WINDOW_M` (600 m,
  triangular) and smoothstep-eased from `CURVE_SHADE_START` 0.2 to
  `CURVE_SHADE_FULL` 0.4.
- **Bends that are not riding curves weigh nothing:** vertices where
  GraphHopper announces a manoeuvre (`junctionVertices`: every sign but
  continue, via, finish) and segments on roundabouts, ramps
  (`road_class_link`), motorways and `TOWN_STREET_CLASSES` (`quietSegments`).
  Without them town grids, roundabouts and motorway ramps lit up. Every request
  therefore asks for the `roundabout` and `road_class_link` path details
  (`ROUTE_DETAILS` in `RouteRequests.kt`; GraphHopper 11 defaults present in
  every graph and `.motomap`).
- **Texels:** MapLibre spreads a 256-texel ramp over the line's Mercator length
  (`CURVE_SHADE_SAMPLES`; `line-progress` is Mercator, so stops use
  Mercator-scaled lengths). Each texel takes the strongest shade along its span
  (a 2 km twisty road on a 400 km ride still reaches full colour), then
  `softened` widens each stretch by `CURVE_SHADE_SOFTEN_TEXELS` (2) and blurs it
  as far again, so the fade looks equally gradual on a 10 km hop and a 300 km
  ride.
- `RouteGeometryCache.curveShadeFor` measures each route once. Thresholds come
  from 300 rater plans; `CurveShadeProbe` (`testing.md`) measures Max twisty
  Brisbane → Kenilworth 47% tinted / 14% full and Fastest 3% / 0%.
- **Gravel blends** (`GravelRouteLine.kt`) fade from the line's colour where
  they start on sealed road (`shadeAtMeters` reads the shade as MapLibre draws
  it), snapped to `GRAVEL_BLEND_LEVELS` (11) levels with one gradient layer per
  band and level in use (a gradient's colour is fixed per layer). Never the
  shade of the gravel road they hide (that tinted the grey magenta), never
  per-step pieces (a starburst on twisting road).
- Tests: JVM `CurvyRouteShadingTest`,
  `RouteCurvinessTest.segmentCornersGradeExactlyTheCardsCurvySegments`,
  `GravelRouteLineTest`, `RouteRequestsTest`; instrumented
  `CurvyRouteShadingOnDeviceTest` (rendered pixels). `visual.py curve-shading
  --from … --to … [--style NAME]` captures `curve-shading-overview`,
  `-transition` and `-bends`.

## Black-and-white ride map

During a ride with the B&W toggle on (`darkRideMapEnabled`; the `dark_mode`
button in the ride toolbar, `inverseSurface` when selected), `RouteScreen`
loads `app/src/main/assets/ride-dark-style.json` instead of `style.json` (same
`{tiles_path}` replacement and load path). The planner and the post-END
restore always use colour, so B&W screenshots must be taken mid-ride.

The ride chrome goes neutral with the map (`monochrome`): the guidance card is
`surfaceContainerHighest`, END `inverseSurface`, the pace delta and suggestion
card neutral, with no blue or red accents (`ride-guidance.md`).

- Roads are one solid layer per drivable class over `transportation`:
  `ride-service-roads` (service, track), `ride-local-roads` (tertiary, minor),
  `ride-major-roads` (motorway, trunk, primary, secondary). Pedestrian `path`
  features are deliberately not drawn (they made the frame read as a sidewalk
  drawing). Every road layer filters `["==", ["geometry-type"], "LineString"]`:
  the z14 archive ships `path`/`pier`/`bridge` polygons in that layer, and a
  line layer draws a polygon's border. No casings, no
  `line-pattern`/`line-dasharray`/`line-gap-width`, no paint opacity.
- `line-cap`/`line-join` go in `layout` (MapLibre ignores them in `paint`).
  Widths are legacy `{base, stops}` functions continuing past the source's
  `maxzoom: 14` (local 9 dp at z16, 18 dp at z18; major 13/26 dp) because
  guidance runs z14–z18 over overzoomed tiles.
- Colours strictly grayscale, dimmest to brightest in class order; labels
  `text-pitch-alignment: viewport` at 14–16 sp so the 58° tilt cannot flatten
  them.
- The route overlay is runtime-added solid white without a casing, below the
  road labels. `road_class` path details split it into service/local/major
  segments; `map/DarkRouteRoads.kt` mirrors the basemap's width stops with a
  1 dp shoulder. Keep its stops and the style in sync (the JVM test checks).
- Tests: the dark-ride cases in `StyleAndSpritesIntegrityTest` (JVM);
  instrumented `DarkRideStyleTest` (real MapView, synthetic GeoJSON, no PMTiles:
  widths, round layout, polygon rejection in pixels and
  `queryRenderedFeatures`). `visual.py nav-camera --black-and-white both`
  captures every guidance frame in both styles.

## Rendering backend

MapLibre 13.5.0 publishes one backend per artifact: `org.maplibre.gl:android-sdk`
is Vulkan-only (`setCurrentType(OPENGL)` throws), `android-sdk-opengl` is the
OpenGL ES build. The first `MapLibre.getInstance()` fixes the type, so no
runtime switch exists. `app/build.gradle.kts`: debug uses
`android-sdk-opengl`, release the Vulkan default; `-PmaplibreBackend=vulkan|opengl`
forces either for A/B evidence. Why: the emulator's Vulkan is software
(`cmd gpu vkjson` → Goldfish GFXStream/llvmpipe), its OpenGL ES translates onto
the host GPU (`dumpsys SurfaceFlinger` → Apple M4 Pro). Real-hardware Vulkan
has not been measured and must not be promised the OpenGL debug numbers.

## POI symbol batching

MapLibre 13.5 turns every distinct `symbol-sort-key` range into its own
drawable. The OpenMapTiles key `["get","rank"]` cost the Brisbane CBD ~880 of
929 draw calls. The shipped style uses a bounded rank band on
`poi-icons`/`poi-labels`:
`["min", ["floor", ["/", ["coalesce", ["get","rank"], 30], 10]], 3]`.

- Never reintroduce a per-feature sort key, and beware data-driven
  `icon-size`/`icon-image` `match` expressions on `rank` (measured 15 vs 60 fps
  at CBD z18).
- `SymbolBatchingStyleTest` (JVM) rejects unbounded keys and pins the band;
  `SymbolPriorityOnDeviceTest` (real Queensland tiles) fails if committed
  winners (General Post Office, Queen Street Stop 57, HSBC, Woolworths at z18;
  HSBC, Woolworths, Post Office Square, Anzac Square at z16; Teneriffe Park at
  the houses camera) stop winning placement or POIs stop rendering.
- Measured, debug OpenGL: CBD z14 31.9 fps, z16 55.5, z18 59.9 (p95 18.9 ms),
  houses z18.5 60.1; Vulkan artifact z18 46.3 fps. Before the fix 0.8–5 fps.
- **Wide view (z14)** is the remaining gap (~32 fps; far tiles' geometry and
  labels). MapLibre's tile LOD (`setTileLod*`) is **not** enabled: results did
  not reproduce across rebuilds and a `zoomShift` variant hid `building-3d`
  extrusions. The sweep runner keeps `MapPerfSweepRequest.tileLod` for
  experiments only. Revisiting it needs reproducible before/after draws plus
  extrusion/label proof.

## Frame benchmark (`visual.py perf`)

Queues deterministic `easeCamera` sweeps through the debug receiver
(`MapPerfSweepQueue`/`MapPerfSweepRunner`), records native
`OnDidFinishRenderingFrame` timestamps plus `RenderingStats`, and writes
`build/brisbane-performance-report.md` (`visual.py perf-merge --labels a,b …`
pairs runs). Numbers count only with the report's semantics:

- `fps` = `(frames-1)*1000/durationMs`; `nativeFps.harmonic` recovers it from
  `1e9/delta` after dropping the first idle interval. Never average listener
  rates arithmetically.
- `encodingTime`/`renderingTime` are seconds; on Vulkan `encodingTime` includes
  fence waits.
- `numDrawCalls` is per frame; buffer/texture counters are cumulative, so
  per-frame uploads are deltas between samples.
- `Window.FrameMetrics` describes the activity window, not the map SurfaceView.
- A warm sweep with an interrupted/cancelled leg or a timed-out settle is
  INVALID; a cancelled leg is never recorded as finished.
- Each report names the backend that initialized
  (`RenderingEngine.getCurrentType()` + the artifact's `BuildConfig`) and the
  host GPU; a number without both is not evidence.

The probe (`map/MapPerformanceProbe.kt`) attaches only from the debug-only
sweep effect in `RouteScreen`, stays dormant outside `begin`/`end`, suppresses
the debug camera-snapshot writer (`MapPerfProbeGate`) during a sweep, and the
runner restores camera, layer visibility and building paint in `finally`.
Tests: JVM `MapPerfSweepTest`; instrumented `MapPerformanceOnDeviceTest`.
