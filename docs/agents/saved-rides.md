# Saved rides (route storage)

Device SQLite `saved_routes.db`, schema v4: `saved_routes` +
`saved_route_comments` (`ON DELETE CASCADE`). Raw `SQLiteOpenHelper` behind the
narrow `SavedRouteStore` interface, not Room: zero new toolchain deps (no
KSP/kapt) for two tables, and every implementation satisfies one contract
(`SavedRouteStore.kt` KDoc) that both the JVM in-memory fake and the
instrumented SQL suite assert. Revisit Room if the schema grows.

## Saving and the storage sheet

- Saving starts on the route card two ways. The card's `bookmark_add` button
  ("Save to saved rides") opens the save bubble already saving (`autoSave`: one
  tap). Long-pressing the card opens the bubble at its prompt. The bubble
  (`SaveRouteBubble.kt`, an M3 surface above the card: "Save this route" and
  the metrics, a 48 dp tonal "Save route" button with a spinner while saving)
  reflects the real outcome: "Saved" in the success colours then dismisses
  itself after `CONFIRM_LINGER_MS`, or "Couldn't save this route" in `error`
  with the button live for a retry. The save stores the exact path (encoded polyline, precision 1e5),
  geocoded From/To names, distance, duration, and the live routing controls:
  ride-style level (`complexity` column), block-unpaved, avoid-motorways, and
  the ride factors the shown route was planned with.
- The map toolbar's bookmark ("Saved routes") opens
  `SavedRoutesSheet`: despite the name a
  full-screen M3 `Dialog` page (`Scaffold` + `TopAppBar` "Saved routes" with
  the `rideCountLabel`, Back = "Close saved routes", `LoadingIndicator` while
  the first query runs). Each ride is a `Card` (surfaceContainerLow, extra
  large shape; tapping it loads the ride, as does **Open**): the mini-map
  banner (`RouteMiniMap.kt`, drawn from the decoded polyline: the map's own
  orange route line on a white casing, start and end dots in scheme roles;
  a stored ride has no per-stretch curviness, so no magenta; a soft green-tinted
  scheme backdrop with pale streets, a 140 dp banner and 12 dp fit padding so
  the route fills it; dimensions and colours pinned in `MiniMapStyle`, no
  tiles or network) with the ride-style
  badge, "From → To", "time · distance", one outlined chip per stored planning
  choice (`savedRouteFactors`: avoid motorways, block unpaved, hills,
  avoid stops and towns; none for a plain ride), the comments (loaded as the
  card scrolls into view when the ride has any, "No comments yet." otherwise),
  **Add comment** (opens the composer: text field + check button, described
  "Add comment"; the toggle itself is described "Comments for saved route: A
  to B") and **Open**. The 48 dp delete icon button asks in an `AlertDialog` (delete icon, error-filled
  **Delete** like the Maps delete dialog). Comment times read "just now" under a
  minute (`relativeCommentTime`). Empty state: bookmark badge, "No saved routes
  yet" and "Tap the bookmark on a route card to save it here."
- Key files: `app/src/main/java/com/curvemaps/app/storage/` (models,
  `PolylineCodec`, `SqlSavedRouteStore`, `SavedRouteRepository`,
  `RouteSimilarity`) and the root-package UI files above.

## Loading

Loading restores the editable planning state (fields, points, ride style (old
unbounded levels clamp to Max twisty), block-unpaved, avoid-motorways, ride
factors) and re-routes; `submitRoute(preferredGeometry=…)` re-selects the fresh
candidate closest to the stored shape via `RouteSimilarity` (mean haversine to
strided candidate vertices). It does NOT rewrite the persisted
block-unpaved/avoid-motorways/ride-factor preferences; those change only
through Route settings Apply. Round-trip loads: `round-trips.md`.

## Schema history

- v2 added `avoid_motorways` (`ALTER TABLE … DEFAULT 0`); the v1
  `max_road_share_percent` column is kept but unread
  (`SqlSavedRouteStoreTest.upgradesVersionOneDatabaseInPlace`).
- v3 moved `complexity` from the four-stop slider (0..3) to seven stops (0..6):
  old levels are rounded/clamped as the old slider did and doubled
  (`upgradesVersionTwoRideStylesOntoTheSevenStopSlider`).
- v4 added `include_elevation` and `avoid_stops_and_towns` (`DEFAULT 0`, so
  older rides reload with both off; `upgradesVersionThreeRidesWithRideFactorsOff`).

A schema change needs both store-contract suites and the repository
truthfulness tests. Suites: `SqlSavedRouteStoreTest`, `SaveBubbleTest`,
`SavedRoutesSheetTest`, `SavedRouteFlowTest`; JVM `MiniMapLayoutTest`,
`SavedRoutesScreenLogicTest`, `SavedRouteReloadTest`.

## Visual capture

`tools/test/visual.py saved-routes --from … --to …` (gallery scenario
`saved-routes`, group "Saved rides") deletes `databases/saved_routes.db*` with
the app stopped, captures the empty page, plans a route, saves it with the
card's bookmark button, switches to `--second-style` (default twisty) and saves
that, then captures the list (two cards, "2 rides"), the comment composer and
the added comment on the first card, the delete confirmation and its Cancel,
and Open (`saved-routes-*` labels). The bubble's "Saved" text lingers under a
second, so the harness waits the bubble out and the ride count proves each save.
