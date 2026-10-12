# GPX import

No graph rebuild or CI cache-seed bump is needed for anything here.

## Entry points

- Settings card → **Import GPX route** (SAF picker), or another app:
  `OpenGpxActivity` (exported, no UI, `Theme.Translucent.NoTitleBar`,
  `taskAffinity=""`, `noHistory`) takes VIEW (`application/gpx+xml`,
  `application/gpx`, `application/x-gpx[+xml]`, or `application/octet-stream`
  with a `.gpx` path) and SEND (those plus `text/xml`/`application/xml`).
- It forwards the URI to `MainActivity` (`ACTION_OPEN_GPX`, `NEW_TASK |
  CLEAR_TOP | SINGLE_TOP | GRANT_READ_URI_PERMISSION`), so a running planner
  gets it through `onNewIntent` and never starts twice.
  `MainActivity.incomingGpx` (saved in instance state) waits for the welcome
  carousel; `RouteScreen(incomingGpx)` waits out a ride, then runs the same
  `importGpx` as the picker.
- The picker and Open-with leave the app, so they are not fuzz actions.

## Parsing (`storage/GpxParser.kt`)

GPX 1.0/1.1, namespaces ignored. Source: the track (≥2 points), else the first
`<rte>`, else the `<wpt>` list (`GpxRoute.source`); `waypoints` are always the
top-level `<wpt>`s. A point with a missing/impossible/NaN lat/lon is skipped
(`skippedPoints`), never fatal. Failures carry rider-facing messages
(`NOT_GPX_MESSAGE` before a `<gpx>` root, `DAMAGED_MESSAGE` after,
`NO_RIDE_MESSAGE`, `UNSUPPORTED_VERSION_MESSAGE`, `CANNOT_OPEN_MESSAGE` for I/O
and revoked grants); the parser's own words go only to the cause/log.

## Two ways to ride a file (`storage/GpxTrackFollow.kt`, pure)

`plan` calls a line **detailed** at ≥10 points and ≤500 m mean spacing:
detailed → `FOLLOW_TRACK` (thinned at 20 m, densified to ≤25 m); otherwise →
`STOPS` (every point a stop, `stopPoints` ≤250). Waypoints count only within
100 m of the line (`ignoredWaypoints`: a café beside the ride must not pull it
there); a waypoint-only file is its own line.

## Following a track (`followGpxTrack` in `GpxFollowRouting.kt`, `GpxTrackFollow.follow`)

- Shaping points every 1.5 km, plus the near stops, plus an unheaded explicit
  point at each **turnaround** (direction over ±30 m reverses by ≥150°; the
  router may only U-turn at a shaping point; without it a recorded U-turn cost
  a 139 km loop); ≤250.
- Each round is one plain Fastest request with `viaHeadings` (the track's
  direction per point, NaN near a turnaround; sent as GraphHopper `headings`,
  which needs `ch.disable`).
- `measure` compares route and line both ways with `PolylineProximity` (50 m
  segment grid): line stretches ≥150 m farther than 35 m from the route are
  **not ridden**; route stretches ≥150 m from the line are **off it**.
- Repairs per round: points from the line every 400 m inside each unridden
  stretch; a non-explicit point that snapped >30 m from its line point is
  dropped; a leg longer than 1.5× + 1 km its line distance loses its end
  headings and gains an unheaded middle point; an end that ends a detour twice
  is dropped (it snapped onto the other carriageway of a motorway).
- Up to `MAX_ROUNDS` (4); least unridden + off-track metres wins; a later round
  that throws keeps the best, a first that throws is the error.
- No ride style applies: `RouteParams.followTrack` routes through
  `followGpxTrack`; the styles pane swaps the slider for `FollowedGpxNote`
  (`FOLLOWED_GPX_NOTE_PREFIX` "GPX track: …": "Rides your track road for road"
  or "Leaves your track for N km", measured off the main thread); the card is
  labelled with the file's name.
- Measured (`GpxFollowProbeTest`, six noisy Queensland tracks, 4–132 km): the
  old import left up to 4.7 km unridden and drove up to 19 km off the track;
  followed tracks miss nothing and stray at most 0.3%, in ≤3 rounds, ≤100 ms.
- **Riding:** a rebuild (`shapingPointsOf`) uses the shown path's own waypoints
  with headings read back along it (`GpxTrackFollow.headingsAlong`), at Fastest
  (`plan.followTrack != null || plan.viaHeadings.isNotEmpty()`);
  `NavigationController.remainingViaIndices` keeps headings beside their
  points. Ride suggestions wait while shaping points remain.

## Planner state

`ImportedGpxStore` (`files/imported-gpx/current.json`, org.json + encoded
polylines, temp-file rename) holds the open import; saved state keeps only
`importedGpxOpen`, and a restore effect reloads it after rotation or process
death. START with an import open re-submits it (`submitImportedGpx`; Back →
START used to route From → To directly). Editing From/To, **EDIT**, current
location, a dataset switch or a new import clears it. The preview sheet
(`GpxRoutePreviewSheet.kt`, an M3 `ModalBottomSheet`: drag handle, extra-large
top corners, opens fully, transparent scrim so the map and its focused-milestone
marker stay visible) says how the file is ridden in a card under its title
(`gpxPreviewNote` is the detail line; a success-container "Following your
track" for `FOLLOW_TRACK`, a neutral "Routing between your stops" for `STOPS`,
chosen by the `following` parameter) and lists the milestones as a timeline
(Route start, Waypoint n numbered like its badge, Route finish; the row at the
curved selection slot snaps, ticks and drives the map camera, padded by
`GPX_PREVIEW_MAP_PADDING_FRACTION` of the screen). **Edit** (`OutlinedButton`,
the planner's EDIT: drops the import) and **Ride** (`Button`, starts guidance;
only offered while a route is shown and guidance is off) appear when
`RouteScreen` passes `onEdit`/`onRide`. Close is "Close route preview". A saved GPX ride reloads like any saved ride.

## Tests

JVM `GpxParserTest`, `GpxTrackFollowTest` (classification, waypoints, densify,
shaping, turnarounds, measure, follow rounds against fake routers,
`headingsAlong`, proximity vs brute force), `ImportedGpxStoreTest`,
`RouteRequestsTest` (headings), opt-in `GpxFollowProbeTest` (`GPX_FOLLOW=1`,
`GPX_FIXTURE_DIR` writes the tracks); instrumented `GpxImportFlowTest`,
`RouteCorpusTest.importedGpxTrackIsFollowedRoadForRoad`,
`FontScaleTest.fontScaleTwoKeepsTheFollowedGpxNoteReadableAt320Dp`,
`GpxRoutePreviewSheetTest`. Fixtures in `app/src/androidTest/assets/gpx/`:
`mount-glorious-loop.gpx` (recorded loop with GPS noise, a U-turn, one waypoint
on and one 7 km off the ride) and `nambour-stops.gpx` (a 5-stop `<rte>`).
`visual.py gpx [--file PATH]` captures `gpx-*`.
