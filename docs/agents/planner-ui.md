# Planner UI: panes, map toolbar and settings menu, camera, welcome carousel

Visual language and colours: `ui-theme.md`. The slider and route card's
behaviour: `ride-styles.md`.

## Two planner panes (`RoutePlanPanel`, `PlannerStage`)

- **Floating panel:** one M3 `Surface` (`surfaceContainerLow`, extra large
  28 dp corners, level-2 shadow) floating over the full-screen map
  (`ROUTE_PANEL_MARGIN` 12 dp each side, `ROUTE_PANEL_BOTTOM_MARGIN` 16 dp above
  the navigation bar); the ride data bar floats the same way in its place
  (`ride-guidance.md`). The
  map box above it holds the map controls; route fits pad by the covered
  height and MapLibre's logo lifts above it. The panel is bottom-centred and at
  most `ROUTE_PANEL_MAX_WIDTH` (560 dp, plus its margins) wide, so tablet and
  desktop windows keep a phone-sized island; it sits `ROUTE_PANEL_KEYBOARD_GAP`
  (8 dp) higher while the keyboard is up. The panel takes at most 92% of the
  height it is offered (`limitHeightFraction`): each pane scrolls its body and
  keeps its primary action (Start, Ride) pinned below, so a short landscape
  window or large text never pushes the action off screen.
- **Endpoints pane** (16 dp padding, 12 dp gaps): From over To as grouped
  filled fields (56 dp, `surfaceContainerHighest`; 16 dp outer corners, 4 dp
  where they meet, 2 dp apart; leading `trip_origin` / `sports_score` symbol,
  label above the value) beside two stacked toggle keys (`PlannerToggleKey`,
  an M3 `ToggleButton` 52 dp wide, at least 56 dp tall: Use current location
  `my_location`, Round trip `autorenew`). Each key shares a row with its field
  (`IntrinsicSize.Min`), so the keys' edges match the fields' at any text size;
  a picked place may take two lines above 1.3x font scale. Below the scrolling
  fields sits the pinned full-width 56 dp **Start** button (`route` symbol).
  A failed search adds its error line above Start. With a round trip on,
  the fields collapse to one "From and back" field with the two keys side by
  side, and the round-trip settings card sits above Start (`round-trips.md`).
  An imported GPX shows the same grouped rows read-only with **Edit** /
  **Preview** (sentence-case text actions; the whole row is the button, and its
  description is "Edit imported route" / "Preview GPX route, N milestones").
- **Use current location is a toggle:** while From holds a resolved current
  location (`currentLocationInFrom`) the key is checked (`primary` / `onPrimary`,
  filled pin; stateDescription `CURRENT_LOCATION_IN_USE_STATE`, "Current location
  in use, tap to clear") and a second tap clears From. Tapping into From holds the
  location (`heldCurrentLocation` in `RouteScreen`); leaving the field without
  writing another place puts it back (`keepsCurrentLocationOnLeave`, via
  `onFromFocusLost`, which also fires when a map tap or the keyboard closing
  ends editing). Tests: JVM `CurrentLocationSelectionTest`.
- Rows grow instead of clipping at large font scales; the round-trip value
  drops its caption above 1.3x (`SELECTED_PLACE_SUBTITLE_MAX_FONT_SCALE`) and the
  ETA steps from headline to title size
  (`CommonScreenSizeTest.plannerPickedPlacesFitTheirRowsBesideTheKeys`/
  `plannerTypedEndpointsFitTheirRowsBesideTheKeys`; `visual.py ui-sizes` captures
  `ui-size-*-endpoints`).
- **Ride-styles pane:** START slides over to it, replacing the fields: the route
  card (`tertiaryContainer`; its bookmark stays at the card's top when the card
  grows, and a number never splits from its unit; or the "Finding this ride…" placeholder with
  M3 Expressive's `LoadingIndicator` on `surfaceContainerHighest`, both at least
  88 dp so the pane never jumps), then the "Ride style" label and the style name
  (`primary`) over a 48 dp tonal back button (`FilledTonalIconButton`,
  `BACK_TO_ENDPOINTS_DESCRIPTION` = "Back to From and To") left of the wavy
  slider, "Fastest" / "Max twisty" captions under it (the label, the style's name
  and the captions line up with the slider's first and last stops,
  `RIDE_STYLE_SLIDER_END_INSET`; the name drops under the label rather than
  overlap it, and each caption gets half the width), then the ETA
  (`RouteStatusSlot`: duration in `headlineMedium`, distance in `bodyLarge`)
  beside the filled 56 dp **Ride** button (`navigation`, level-1 elevation;
  `RIDE_BUTTON_HEIGHT`). The card and slider scroll in a short window; the ETA
  and Ride are pinned (`RideFooter`: when the ETA would have to wrap beside the
  button, Ride drops under it at full width). While a style is searched the ETA gives way to a
  `LinearWavyProgressIndicator` ("Searching styles") and Ride is disabled. The
  slider can move while stops are still searching.
- Start and Ride are M3 `Button`s whose visible words are "Start" / "Ride"; their
  accessibility text stays "START" / "RIDE" (`semantics { text }`), the names the
  tests, fuzz and `visual.py` use.
- The route card: title "<style> · N% curvy" (`titleMedium`), "time · distance
  [· ride factors]" (`bodyMedium`), a `bookmark_add` icon button (description
  `ROUTE_CARD_SAVE_DESCRIPTION` = "Save to saved rides", deliberately not "Save
  route", which names the bubble's button) and a 4 dp meter of the curvy share.
  Tap selects, long-press opens the save bubble, the bookmark button opens it
  already saving (`saved-rides.md`).
- **Back** (button, or system Back, which yields to the settings menu and Maps
  screen) slides back to the fields with the route kept on the map; START then
  answers from the finished plan. A failed search (including point resolution)
  slides back to show its error; Idle (END, dataset switch, recreation) returns
  to the fields. Saved/GPX loads open on the styles pane.
- Pane changes use `AnimatedContent` with a shared spring slide + fade + scale
  and a spring `SizeTransform`; card text and the style name slide in from the
  side the slider moved toward.

## Search fields

Each field is a filled-text-field look over a `BasicTextField` (the whole row is
the text field, so a tap anywhere focuses it; the label sits above the value).
Tapping From or To selects the whole entry so typing replaces it
(`selectAllForReplacement`, applied after the focusing tap places its caret and
only if nothing was typed meanwhile; `visual.py type --append` moves the caret
to the end first). A picked place shows its name under the label with a pencil;
tapping it edits (`"<label> selected: <name>"`). The geocoder behind the fields:
`map-assets.md`.

The results are the top of the panel itself, not a popup window
(`PlannerSearchLayout`, `RoutePlanSearchSheet`): the fields and Start are laid
out first, then the sheet gets what is left of the panel's height (at most
`MAX_DROPDOWN_HEIGHT`), full width above them (`ROUTE_ENDPOINT_GROUP_TEST_TAG`
tags the fields-and-Start block, whose top is the sheet's bottom). It can
therefore never overlap or cut the fields, and it composes nothing when not even
one whole result row fits. Contents: a chips row ("Searching From|To" and, for
From, a **Use current location** assist chip; `SEARCH_POPUP_HEADER_HEIGHT`,
shown only when a whole result row still fits under it, `searchPopupHeight`), up
to 2.5 two-line rows (leading symbol per `GeocodeResultType`, the typed words in
semibold, `searchMatchRanges`; the rows fade into the panel at the cut while more
scroll) and `SEARCH_OFFLINE_NOTE`. The panel grows upward, so the map box above it
shrinks and the Centre on me / zoom controls make room (hidden when they would
not fit below the toolbar). Closing the keyboard clears the field's focus
(`RouteScreen`), which closes the sheet. The 300 ms debounce
(`SEARCH_DEBOUNCE_MS`) is unchanged; `RoutePlanSearchResultsPopup` (a `Popup`
window above a field, `AboveAnchorPopupPositionProvider`,
`maxSearchDropdownHeightPx`) remains for a field used outside the planner panel.
Tests: JVM `SearchPopupPlacementTest` (popup height, match ranges, symbols),
`SearchFieldSelectionTest`; instrumented `SearchDebounceTest`, `PlannerPanelTest`
(`firstSearchPopupStaysAboveBothPlannerRows`, `mapControlsStayClear...`),
`CommonScreenSizeTest`, `AccessibilityTest`.

## Map toolbar and settings menu (`SettingsMenu.kt`)

- **Toolbar:** a `VerticalFloatingToolbar` (56 dp wide, `surfaceContainer`,
  upper right: `MapControlsLayout.PLANNER_END` 16 dp from the edge, 16 dp below
  the status bar) holds two 48 dp `IconButton`s: the settings cog (content
  description "Planning settings") and the saved-routes bookmark ("Saved
  routes", `ms_bookmarks`). Both stay reachable while the menu is open (fuzz
  oracles and `SettingsMenuTest` pin this) and keep their bounds. The toolbar
  is hidden while the full-screen Maps and Open-source licences pages are open
  (the mockups show none; it would cover their headers and banners); Back
  returns to the planner and the toolbar. The fuzz oracle skips it there.
- **Cog state:** while the menu is open the cog is the selected button
  (`secondaryContainer`, filled `ms_settings_fill`); stateDescription
  `SETTINGS_COG_OPEN`/`SETTINGS_COG_CLOSED` ("Settings card open"/"closed",
  strings unchanged as test seams).
- **Menu:** an M3 menu surface (`surfaceContainer`, large shape, 264 dp max)
  grows leftwards from the toolbar, built from `DropdownMenuItem`s in the
  planner's own window, not a `DropdownMenu` popup, so it shares the compose
  root with the scrim and the tests (`onRoot()` stays unambiguous). Groups,
  separated by dividers: Route settings, Sound settings | Maps, Load map file,
  Import GPX route | Open-source licences, then the map credits text. Rows
  carry `ms_*` leading icons. While open the menu is height-bounded to the
  window (navigation-bar inset plus a 16 dp margin below) and scrolls inside,
  and keeps a 16 dp margin from the left edge (it shrinks below 264 dp on a
  320 dp phone). Sound settings announces the offline-voice state
  (stateDescription). The credits text keeps "CC BY 4.0" together (no-break
  spaces).
- It dismisses on system Back (`BackHandler`), on a cog tap and on any tap
  outside it: `RouteScreen` draws a full-screen scrim (8 % black) above map and
  planner, so the planner cannot be edited behind it.
- The screens it opens each have a Back button (`contentDescription = "Back"`,
  48 dp). `RouteSettingsDialog`, `VoiceGuidanceSettingsDialog` (full-screen
  dialog windows from `SettingsPageDialog`, with a Cancel / Apply footer) and
  Open-source licences share `SettingsScreenHeader` (an M3 top app bar:
  arrow_back navigation icon, title in `titleLarge`). Maps draws its own header
  (`HeaderBlock`: Back, then a `headlineMedium` title block, see `regions.md`).
  Load map file and Import GPX route open the system document picker.
- **Ride mode** (`RouteActionsPill(rideMode = true)`): the voice-guidance and
  B&W-map toggles in a 56 dp dark-scheme pill; each 48 dp button sits in a 64 dp
  glove-sized hit box (`RIDE_TOGGLE_TARGET`; the box carries the semantics and
  shares the button's interaction source). B&W on = `inverseSurface`; with the B&W
  map the voice toggle (when on) is `inverseSurface` too, with the filled
  glyph, so selected stays visibly selected. The pill shares the zoom group's
  vertical axis (`MapControlsLayout.rideToggleEndPadding`) and, in a ride,
  starts below the compass button (`COMPASS_SIZE` + `COMPASS_GAP`).
- **No map installed:** `MissingMapDialog` is an M3 `AlertDialog` over a scrim
  ("Map package needed" / "Offline map file needed", Choose map file `Button`,
  Open Maps `TextButton` only when the build ships no data). It steps aside while
  Maps or the licences screen is open; Back or an outside tap dismisses it
  (`missingMapDialogDismissed`) so the toolbar menu (Maps, Load map file) stays
  reachable. The dialog is wide (`usePlatformDefaultWidth = false`, 32 dp side
  margins, M3's 560 dp cap: 348 dp on a 412 dp phone). It is its own window, so
  instrumented tests that press Back or use `onRoot()` need a basemap installed
  (`tools/test/run-all.sh` seeds one; `OfflineBasemapRule` for others). Tests:
  `MissingMapPromptTest`; `visual.py first-run`.
- **System bars:** `SystemBarIcons(darkIcons)` (in `MainActivity.kt`) sets the
  activity window's status- and navigation-bar icon colour: dark over the planner,
  the light pages and the colour ride map, light over the welcome carousel and
  the B&W ride map. A soft wash (`StatusBarScrim`, white or black over the
  map) keeps map labels from colliding with the clock and icons. Dialog windows
  set their own icons.

## Planner camera and map controls

- The map opens on the newest last-known position at planner zoom
  (`GpsFixSource.lastKnownCameraSeed`, any age, camera only, never routing or
  guidance), else the Queensland overview; the first live fix still recentres.
- A shown route owns the camera (`PlannerCameraPolicy`). Framing a new route
  releases the Centre-on-me lock and spends the one-shot first-fix centring (as
  does any map touch), so a cold-GPS fix or a slider reload never flies the
  camera back to the rider; only Centre on me does.
- The search-results sheet is the top of the planner panel: the map box above
  the panel shrinks while it is open, so Centre on me / the zoom group sit above
  it (`MapControlsLayout`; hidden only when they would not fit). `RoutePlanPanel`
  still reports the sheet's window top (`onSearchSheetTopChange`). On a short window (phone landscape)
  `MapControlsLayout.slot` moves a control that no longer fits the right-hand
  column to the column left of the toolbar, top-aligned with it (Centre on me
  first), and hides it only when no room is left; nothing overlaps the toolbar
  or leaves the screen. Route framing (`fitBounds`) keeps the route's ends clear
  of the control column and the status bar, and the MapLibre logo/attribution
  lift 8 dp above the panel.
- **Map controls** (`MapControlsLayout` pins the geometry): the zoom +/- group is
  a second 56 dp `VerticalFloatingToolbar` of two 48 dp cells; below it, 16 dp
  apart, the Centre on me / Follow rider control is an M3 `FloatingActionButton`
  (56 dp, `surfaceContainerHigh`, 16 dp from the edge; 12 dp in a ride). Locked
  it shows the filled `my_location` glyph in `primary` (`onSurface` over the B&W
  map); released, the outline in `onSurfaceVariant`; the content description
  keeps the state in words ("Rider lock on; tap to release" ...). In a ride both
  wrap in `CurveMapsDarkTheme`. The **compass** is our own 48 dp
  `MapCompassButton` (disc in `surfaceContainer`, `navigation` arrow in `error`,
  rotated against the map bearing; "Compass, north up" / "Compass, map rotated N
  degrees; tap for north up"); MapLibre's native compass is disabled. In a ride
  it is always shown at the top of the toolbar column (toolbar 12 dp below); in
  the planner it appears under the toolbar while the map is rotated. Tapping it
  turns the map north-up. The startup map credits are a compact one-line chip
  ("MapLibre · © OpenStreetMap", `MapCreditsChip`, right of MapLibre's logo) for
  five seconds, with the full credits line as its accessibility text; the full
  credits live in the settings menu.
- Tests: JVM `PlannerCameraPolicyTest`, `MapControlsLayoutTest`,
  `CameraSeedTest`, `SearchFieldSelectionTest`; instrumented
  `PlannerFollowCameraOnDeviceTest.shownRouteKeepsTheCameraUntilCentreOnMe`,
  `PlannerPanelTest.mapControlsLiftAboveTheSearchResultsSheet`,
  `PlannerPanelTest.tappingAFilledFieldSelectsItsWholeEntryForReplacement`;
  `visual.py search-results` captures both keyboard states.

## First-run welcome carousel (`onboarding/`)

- A fresh install (or an upgrade that never saw it) opens on `WelcomeCarousel`
  **instead of** the planner, so startup never stacks dialogs. Pages: Welcome →
  Ride safely → Location → Music while you ride → Offline maps.
- The safety page must be acknowledged with **I understand**: Skip stays hidden
  and swiping cannot pass it until then (`WelcomeModel.showSkip`/`swipeEnabled`).
- Each permission is asked on its page: arriving on Location fires the system
  dialog once if not granted (`WelcomeModel.shouldAutoRequestLocation`; the
  button re-asks); notification access for media is a button only (it leaves
  the app). The app posts no notifications, so no `POST_NOTIFICATIONS`.
  `RouteScreen` does not request location at startup; only START and Current
  location ask afterwards.
- The map page names the ready region (routing data AND drawn tiles) or
  explains `.motomap` files and hands the planner
  `WelcomeExit.CHOOSE_MAP_FILE` / `OPEN_MAPS` (`RouteScreen(startupAction=…)`).
- Skip (`"Skip welcome"`), Later or finish persists `welcome/completed`.
  Planner suites skip it through `dismissMediaStartupPrompt()`.
- Look (M3 Expressive, matching `Welcome1–5` in the design): the carousel is
  wrapped in `CurveMapsDarkTheme` on a `surface` background. Each page has a
  hero drawn with `MaterialShapes` (`.toShape()`), specified purely by
  `WelcomeHero.spec(page)`: Welcome = Cookie9Sided in `primaryContainer` with the
  twisty-road art, Ride safely = Clover4Leaf in `tertiaryContainer` (the mockup's
  fill) with `two_wheeler`, Location = Cookie6Sided in `secondaryContainer` with a
  `near_me` disc and halo rings, Music = SoftBurst in `primaryContainer` with
  `headphones` and a decorative mini player, Offline maps = Cookie9Sided (200 dp)
  in `tertiaryContainer` with `map`. The shape turns up to 45 degrees with the
  swipe (`WelcomeHero.rotationDegrees`) and stays still when
  `ANIMATOR_DURATION_SCALE` is 0. Below the hero: headline, body, then a status
  chip (location and media only: a 32 dp outlined small-shape chip, success
  check when granted) and a card per page (feature list, outlined warning note,
  media notes, map-part chips plus how-to when no map is ready).
- Controls: the page indicator is a 24 x 8 dp `primary` pill among 8 dp
  `outlineVariant` dots (`stateDescription` "Page n of 5"); the main action is a
  filled `Button` (56 dp, glyph before or after the label); Skip (content
  description `"Skip welcome"`, only after the safety page), Not now and Later are
  `TextButton`s; Download in Maps is a `FilledTonalButton`.
- Fit (`WelcomeLayout`, pure): each page scrolls inside the pager (a soft fade
  marks where it continues) while the page dots and buttons stay pinned. The
  hero scales with the page's height (36 % of it, less at large font scales,
  clamped to 96 dp .. its designed size) and its glyphs scale with it. Content,
  card and buttons are capped at 560 dp on wide windows. On short wide windows
  (>= 520 dp wide, < 520 dp tall: phone landscape) the hero sits beside the text
  and Skip, secondary actions and the primary button share one row with the dots.
  Secondary actions (Not now, Download in Maps, Later) live in one reserved slot
  under the primary button, so the dots and button do not jump between pages; the
  primary button is keyed per page so a press ripple never carries over.
- Tests: JVM `WelcomeModelTest` (copy, decisions, hero specs, rotation) and
  `WelcomeLayoutTest` (hero sizing, compact rule); instrumented `WelcomeCarouselTest`;
  `visual.py first-run [--bundled] [--ask-location]`.
