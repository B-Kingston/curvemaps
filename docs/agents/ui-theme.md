# Theme, colours, components and icons (`ui/theme/`)

The UI is Material 3 with the Expressive components, matching the design
mockups. Screens use stock M3 components and read roles from the theme; there
is no custom control language any more.

## Theme and schemes (`CurveMapsTheme.kt`)

- `CurveMapsTheme { }` is the app root (`MainActivity`): a
  `MaterialExpressiveTheme` with the **light** scheme `CurveMapsLightColors`,
  `MotionScheme.expressive()`, the M3 shape scale (`CurveMapsShapes`: extra
  small 4, small 8, medium 12, large 16, extra large 28 dp; full is
  `CircleShape`) and stock `Typography()` on the system Roboto (no bundled or
  downloadable font; the app is offline). Use `MaterialTheme.typography.*`; bold
  ride numerals are `titleLarge` + `FontWeight.SemiBold` with tabular figures.
- `CurveMapsDarkTheme { }` wraps a **subtree** in the dark scheme
  `CurveMapsDarkColors` (same shapes, type and motion). It is for the ride
  chrome (guidance card, map controls, data bar, suggestion card, media panel)
  and the welcome carousel. The planner, its sheets and dialogs and the
  settings screens stay light.
- Both schemes are M3 *Fidelity* from seed `#0B70C2`, every `ColorScheme` role
  set explicitly (surface dim/bright, all five surface-container levels, fixed
  roles, inversePrimary, surfaceTint, scrim). The design overrides
  `onSecondaryContainer` for contrast: light `#2F4868`, dark `#D3E4FF`.
- Extra roles M3 lacks: `CurveMapsTheme.colors.success / onSuccess /
  successContainer / onSuccessContainer` and the same four for `warning`,
  resolving to the light or dark set of the current subtree.
- `ColorScheme.disabledContent` / `disabledContainer` are M3's `onSurface` at
  38 % / 12 %.
- **Ripples and state layers are on.** Hover, focus, press and selected states
  come from the M3 components. Never provide `LocalRippleConfiguration` as
  `null` and never paint a pressed-state fill by hand.
- Map data colours (route `#FF5500`, curvy `#D6006F`, ghosts, gravel) are not
  theme roles and stay in `map/`.

## Colours

- `ui/theme/Palette.kt` is the **only** file allowed raw `Color(0x…)` values
  (`Palette.FidelityLight`, `Palette.FidelityDark`, `Palette.StatusLight`,
  `Palette.StatusDark` hold the generated tones). Screens read
  `MaterialTheme.colorScheme.*` and `CurveMapsTheme.colors.*`.
  `Color.White/Black/Transparent` constants are fine where the design uses
  literal white (the rider puck), but prefer roles.
- `ColorThemeTest` (JVM) fails on a colour literal outside `ui/theme/`, on any
  `LocalRippleConfiguration provides null`, on drift of either scheme or the
  status roles from the generated values, on a role left at M3's baseline
  (purple), on any text pair below WCAG AA 4.5:1 in either scheme (each `on*` role on
  its container; `onSurface`, `onSurfaceVariant`, `primary`, `error`,
  `success`, `warning` on every surface level) and on `outline`/`primary` below
  3:1.

## Icons: Material Symbols Rounded

- Weight 400, grade 0, optical size 24, as vector drawables:
  `R.drawable.ms_<name>` (outlined) and `R.drawable.ms_<name>_fill`
  (filled), e.g. `Icon(painterResource(R.drawable.ms_settings),
  contentDescription = null)`. They are black and tinted by `Icon`.
- `tools/icons/fetch-symbols.sh <name> …` fetches a symbol from
  `google/material-design-icons`, writes both drawables and adds the name to the
  sorted manifest `tools/icons/symbols.txt`; `--all` refetches the manifest. It
  fails on a 404, an SVG with more than one path or any transform. Commit the
  new drawables with the change that needs them. Some Material Icons names have
  no Symbols file (`place` is `location_on`, `gps_off` is `location_disabled`,
  `sd_storage` is `sd_card`; `loop` and `terrain` are gone, see `autorenew`,
  `repeat`, `landscape`).
- Licence: Apache-2.0, listed on the Open-source licences screen as a data entry
  and in `THIRD_PARTY.md` (`licences.md`).
- There are no hand-drawn icon composables left in the UI chrome: every glyph
  is a symbol. The route line, map POI sprites, the rider puck and map-layer
  glyphs are drawn by the map and are not symbols.

## Which M3 component for which control

| Control | Component |
|---|---|
| Primary action | `Button` |
| Secondary action | `FilledTonalButton` or `OutlinedButton` |
| Dialog actions | `TextButton` |
| Destructive action | `Button` with the `error` / `onError` colours |
| Map controls | `VerticalFloatingToolbar` + `FloatingActionButton` |
| Toggles on the map and ride chrome | `IconToggleButton` family; selected = `secondaryContainer` (the B&W-map toggle is `inverseSurface` as designed) |
| Planner toggle keys (current location, round trip) | `ToggleButton`; checked = `primary` / `onPrimary` |
| Settings rows | `ListItem` + `Switch` / `Checkbox` |
| Keys that sit together | connected shapes set by hand (4 dp seam between members, a larger outer corner) on `Button` / `ToggleButton`; the `ButtonGroup` API is not used |
| Choice between modes | `SingleChoiceSegmentedButtonRow` + `SegmentedButton` |
| Choices and filters | `FilterChip` / `AssistChip` |
| Ride style | the slim-handle `Slider` |
| Saved rides, sheets | `Card`, `ModalBottomSheet`, `AlertDialog` (full-screen `Dialog` pages for the settings screens) |
| Progress | `LoadingIndicator`, wavy `Linear`/`CircularWavyProgressIndicator` |

Every control keeps a 48 dp target (ride chrome keeps its larger glove-sized
targets) and its content description. Expressive APIs need
`@OptIn(ExperimentalMaterial3ExpressiveApi::class)`; material3 stays pinned at
`1.5.0-alpha01`.
