# Ride media control center

While riding, the data bar's far-left key opens a Compose media panel above
the bar (`media/MediaControlCenter.kt`). Fully offline and unprivileged: no
Spotify SDK, no network, no `MEDIA_CONTENT_CONTROL`, no accessibility service.
The data bar itself: `ride-guidance.md`.

## Panel layout and lifetime

The panel is Material 3 on the **dark** scheme (`CurveMapsDarkTheme` wraps it),
matching `RideMedia.dc.html`: a `surfaceContainer` slab whose top corners are
`MediaPanelJoin.panelTopCorner` (the bar's), 16 dp content inset, 16 dp between
sections, built from stock components. The open bar draws a 1 dp `outlineVariant`
hairline along its top edge (`MediaPanelJoin.SEAM_DP`) so the two same-coloured
surfaces do not merge into one slab; it lies inside the bar, so the flush join is
unchanged. From the top:

- Header: 56 dp album tile (`secondaryContainer`, `ms_album_fill`), then
  `labelMedium` primary "`<app> · Playing|Paused`" (the bare app name while the
  state is unknown; "Media keys" / "No player" without a session), the title
  (`titleLarge`, medium weight, ellipsised) and artist (`bodyMedium`,
  `onSurfaceVariant`), then Close (64 dp `IconButton`) inside the timeout ring.
- Transport: one connected group, `MediaPanelLayout.transportHeight` = 80 dp,
  4 dp seams. Previous/Next are `secondaryContainer` buttons (pill outer end,
  16 dp inner end), play-or-pause is the `primary` button between them, 1.5x
  their width, 24 dp corners; symbols `ms_skip_previous_fill`, `ms_pause_fill` /
  `ms_play_arrow_fill`, `ms_skip_next_fill`. Unknown state: Pause | Play as a
  two-key group (Play `primary`, Pause `secondaryContainer`: nothing is known to be
  playing, so Play is the one to reach for), Previous | Next as a second group below.
- Volume: caption line (player, readback "5 / 15", "Fixed volume" or
  "Adjustable"), then 64 dp round `surfaceContainerHigh` step buttons around an
  M3 slim-handle `Slider`. The slider is **read-only** (`enabled = false`, its
  disabled colours set to the live ones, dimmed only when the level is
  unreadable; steps = stream steps): the volume contract is one command per tap
  with observed readback, never a drag. Its accessible node is
  "`<player> volume level`" with the observed value; the buttons keep
  "Volume up/down, `<player>`".
- Players: a row of 64 dp `FilterChip`s (selected = `secondaryContainer` + check,
  and the `selected` semantic).
- **Music apps**: an outlined full-width pill row (`library_music`,
  `expand_more`/`expand_less`) at least 64 dp tall. Opened, the app list is M3
  `ListItem`s on `surfaceContainerHigh` (Change row, then the choices with a
  check on the selected one); Start (filled) and Open (tonal) are pill buttons.
  The panel's content scrolls inside its bound with a 40 dp fade into
  `surfaceContainer` at whichever edge has more to scroll to (`scrollFades`), so a
  list the bound cuts shows that it scrolls; at the end 16 dp of space remains
  above the bar.
- Status: fallback copy ("Using system media keys (limited control)",
  "Playback state unknown — Play and Pause are explicit") and the music-app
  message are `StatusNote`s: `surfaceContainerHigh` rows with an `info` glyph.
  Playing/Paused is no longer a footer line; it is in the header.
- Every glyph is a Material Symbol (`R.drawable.ms_*`); the data bar's opener
  uses `ms_music_note` (outlined closed, `ms_music_note_fill` while the panel
  is open).

- Bounded to the space between the directions HUD and the bar (measured, not
  hard-coded) and scrolls inside it, so landscape never covers the next turn
  or END.
- System Back closes it (`BackHandler`); the settings menu keeps priority (the
  media handler is disabled while the menu is open), so one Back peels exactly
  the top surface.
- A 15-second inactivity timer starts on open; panel touches, scrolling and
  control activations restart it; playback, volume and player observations do
  not. A 48 dp ring (`primary` on `surfaceContainerHighest`, round cap) around
  the close glyph shows the progress; it is its own node,
  "Media controls inactivity timer", with a `ProgressBarRangeInfo`.

## The panel/bar join (`media/MediaPanelJoin.kt`)

The open panel and the bar are one continuous silhouette (the same trick as the
settings cog's tab). A geometry contract, every value derived from the bar's
own geometry:

- The panel is inset exactly like the floating bar (`leadingInset`/
  `trailingInset` = `DataBarMargin`, plus any side navigation bar).
- The bar has square top corners while open (rounded again when closed; its
  bottom corners always round), so only the panel supplies the top rounding.
- The panel reaches `fuseDepth` (= `DataBarVerticalPadding`, 6 dp) down into the
  bar, and `RouteScreen` draws the panel before the bar, so the bar paints over
  the overlap and no seam shows.
- The bar's `shadowElevation` is 0 while open (its top shadow would redraw the
  line the join removes).
- Never give the panel an inset other than the bar's, a second corner radius,
  or re-add the bar's shadow while open.
- Tests: JVM `media/MediaPanelJoinTest`; instrumented `ui/MediaPanelJoinTest`.
  `visual.py media-controls` captures the portrait frame; `visual.py route …`
  plus RIDE and a **Media controls** tap shows landscape.

## Sessions (`media/AndroidMediaSessionGateway.kt`)

- `MediaSessionManager.getActiveSessions` + `MediaController` callbacks →
  `StateFlow`. A session's key comes from its `MediaSession.Token` (stable
  across snapshots), so two sessions of one package or a reordered snapshot
  cannot swap identities; per-controller callbacks are guarded against stale
  sessions. `onAudioInfoChanged` is in the callback set.
- Access is the user-granted notification listener
  (`MediaNotificationListenerService`, only
  `BIND_NOTIFICATION_LISTENER_SERVICE`; it overrides no notification callback,
  so notification content is never read). `MediaPermission` re-checks on
  resume. Access is offered once, on the welcome carousel's media page (Enable
  opens `ACTION_NOTIFICATION_LISTENER_SETTINGS`, **Not now** skips); the ride
  panel has no permission prompt.
- `setOnSessionsChanged` reports whether the platform accepted the registration;
  the controller retries a refused one on refresh and re-asserts it on resume
  and listener reconnect (idempotent), so a later grant starts live updates
  without reopening the panel.

## Pure core (`MediaModels.kt`, `MediaCenterState.kt`, `MediaCenterController.kt`)

- Deterministic selection: a user-pinned session is retained; otherwise the
  playing session, ties broken by recency/package/key; a paused selected
  session is retained for Resume.
- Capabilities come from the `PlaybackState` action mask; unsupported commands
  are disabled. One command per tap with an honest accepted/rejected result:
  never retried, duplicated, or reported as a false success.
- `MediaVolumeControl` mirrors the `VolumeProvider` ABI (`FIXED = 0`,
  `RELATIVE = 1`, `ABSOLUTE = 2`), pinned on device.
- `transportStateKnown` is true only when the selected session reported a
  `PlaybackState`. Otherwise the panel shows explicit **Pause** and **Play**
  (never a toggle whose label would lie) and the footer says the state is
  unknown. Without session discovery it falls back to a labelled
  `AudioManager.dispatchMediaKeyEvent` with explicit `KEYCODE_MEDIA_PLAY` /
  `KEYCODE_MEDIA_PAUSE`, always one paired DOWN/UP.

## Cold music start (`media/AndroidMusicAppGateway.kt`)

- Candidates: installed, enabled, launchable Spotify, TIDAL, Deezer, Apple
  Music, YouTube Music, Qobuz (explicit package visibility) and declared
  `CATEGORY_APP_MUSIC` apps; not generic media-browser/video handlers. Debug
  builds also see the enabled debug music-browser fixture.
- The panel hides controls behind Music apps (omitted when none exist), shows
  Start/Open for one app, and Change music app on demand. Targets are 64 dp;
  every interaction resets the timer.
- **Start** tries three entry points in order, and the ride stays in front
  for the first two:
  1. Connect to the player's `MediaBrowserService` (wait at most
     `MusicBrowserTimeoutMillis`, 5 s) and send exactly one supported play
     command.
  2. A refused, failed or timed-out connection (Spotify refuses unknown
     callers) or a session without play actions sends nothing. If the player
     declares an exported `ACTION_MEDIA_BUTTON` receiver and sessions are
     observable, send that receiver one paired DOWN/UP `KEYCODE_MEDIA_PLAY`
     (the signal a headset's play key gives). `mediaButtonStep` (pure) then
     waits at most `MediaButtonConfirmMillis` (3 s) for that player to be
     observed Playing. The press is an explicit PLAY, never a toggle, so a late
     start plus the fallback's own play request cannot stop the music.
  3. A silent player, no receiver, or no notification access falls back to
     the player's `MEDIA_PLAY_FROM_SEARCH` activity (empty query), else opens
     the player.
- Whether a stopped Spotify starts from a press that another app sends is not
  proven on a real phone; step 3 covers it when it stays silent.
- Keep the browser binding after Play until Playing is observed (or 5 s), so a
  cold service is not destroyed before its session callback runs. Closing the
  panel or detach cancels the browser and ignores late callbacks.
- **Picture-in-Picture (`RidePictureInPicture.kt`):** just before a player's
  own screen opens (Start's fallback or **Open**), the gateway's
  `beforeLeavingRide` hook shrinks the ride to a 3:4 PiP window, a small
  window that Android keeps on top of other apps. In the window `RouteScreen`
  draws only the map and `NavigationPipHud` (turn arrow and distance); the
  data bar, rail, map controls, suggestion banner and media panel wait for the
  full ride. The media panel stays open behind the window, so the Start watch
  survives. The rider taps the window, then its full-screen button, to get
  back. The ride stays visible, so location and voice guidance keep their full
  rate. `MainActivity` declares `supportsPictureInPicture` and handles
  `screenSize|smallestScreenSize|screenLayout|orientation` itself, so neither
  the PiP resize nor rotation recreates the activity, the map or the ride.
  `enter` returns false (the player opens over the ride as before) without
  PiP support, with PiP turned off for the app, or when the ride is not in
  front. Dismissing the window closes the ride task, like swiping it away in
  Recents [inference from AOSP PiP behaviour, not observed].
- **Return to the ride:** after a foreground play request, `ReturnToRideWatch`
  (pure) polls every 250 ms for at most `ReturnToRideWindowMillis` (12 s from
  the Start tap, below the panel timeout). It asks to bring the ride task back
  (`REORDER_TO_FRONT`, never a second planner) only once the rider has left
  (the ride is not resumed, or is in its PiP window) AND that player is
  observed Playing. It stops if the rider returns first, if sessions are
  unobservable (no blind timer), or on `cancel()`. Plain **Open** never
  auto-returns. Android blocks the request over a real player:
  on the API 37 emulator it logs `BAL_BLOCK` both without PiP
  (`callingUidHasVisibleActivity: false`) and from the PiP window
  (`callingUidHasVisibleActivity: true`,
  `callingUidHasVisibleNotPinnedActivity: false`). Only the debug fixture
  player (same UID, visible) and instrumentation get through.
- Tests: `MusicAppControllerTest`, `MusicAppOnDeviceTest` (including the PiP
  window under the player), `ReturnToRideWatchTest`, `MediaButtonStepTest`,
  `MediaControlCenterTest`. `visual.py media-controls --cold-start` enables
  the debug-only `VisualMusicBrowserService` (stopped → playing → paused). The
  `cold-button` fixture state makes it refuse and enables the debug-only
  `VisualMusicButtonReceiver` to capture `media-cold-player-button` (the ride
  never leaves). The `cold-refuse` state disables that receiver and enables
  the debug-only `VisualMusicPlayerActivity` (`:player` process, `noHistory`),
  which plays `playDelayMs` (default 5 s) after it opens. The runner checks
  the pinned task in `dumpsys activity activities` and captures
  `media-cold-player-pip`, then `media-cold-player-returned`. Component states
  are restored in `finally`.
  App-launch controls are not fuzz actions; the oracle checks their targets.

## Volume (`media/AndroidVolumeController.kt`)

- Local `STREAM_MUSIC` via `adjustStreamVolume` with real min/max/current
  readback and fixed-volume handling (`isVolumeFixed` plus a collapsed range).
  Remote sessions use `MediaController.adjustVolume` per
  `PlaybackInfo.volumeControl`, shown only from the observed
  `onAudioInfoChanged` value.
- Hardware presses are invisible to the app, so while the panel is open the
  controller observes the stream setting and polls readback; the shown level
  and disabled limits follow observation, never an optimistic guess.
- Works without notification access. Controls take no audio focus.

## Guidance ducking and resume

- `GuidanceAudioFocus.kt` (used by `OfflineVoiceGuidance`): prompts take
  `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`. A freshly ducked phrase waits
  `DUCK_LEAD_IN_MS` (300 ms); focus is held `RELEASE_TAIL_MS` (700 ms) after the
  last phrase so close prompts share one duck. Another app's
  `LOSS_TRANSIENT_CAN_DUCK` never cuts a prompt; a real loss mid-phrase drops it
  (re-announced on the next fix); a loss during the tail is forgotten.
  `GuidanceAudioFocusTest`.
- `media/GuidanceResumeWatch.kt` (`OfflineVoiceGuidance.duckListener` →
  `MediaCenterController`): some players pause instead of ducking. Sessions
  playing before guidance's focus request that are still paused
  `RESUME_GRACE_MS` (2.5 s) after a normal release get exactly one PLAY. Never
  for a player that was not playing, a pause observed over
  `FOCUS_PAUSE_WINDOW_MS` (1.5 s) after the request or after the player
  restarted (the rider's own pause), after a real loss
  (`GuidanceDuck.INTERRUPTED`), after a rider transport command or music start,
  twice per duck, or without notification access. `GuidanceResumeWatchTest`.

## Glove ergonomics

- Close, volume ±, the player chips and the Music apps controls are >=64 dp;
  the three transport keys are 80 dp tall and, at 320 dp, >=80 dp wide
  (play/pause >=120 dp). The connected group's seam is 4 dp (the design's
  group); the keys are large enough that adjacent targets cannot be confused.
  Volume buttons and the slider have an 8 dp gap, chips a 12 dp gap.
  `MediaPanelLayout` holds the values and `MediaPanelKeysTest` pins sizes and
  the 320 dp fit.
- Rows add rows or scroll instead of shrinking at 320 dp / 2x; no precision
  gestures or hold-to-repeat (the volume slider is a display, not a control).

## Tests

JVM `media/MediaSessionSelectionTest`, `MediaCapabilitiesTest`,
`MediaCenterControllerTest`; instrumented `MediaControlCenterTest`,
`MediaPlatformAbiOnDeviceTest`, `MediaVolumeOnDeviceTest`,
`MediaSessionTransportOnDeviceTest`, `MediaAccessRecoveryOnDeviceTest`
(attach-before-grant → grant → live updates), `MediaSessionVolumeOnDeviceTest`,
`MediaVolumePanelOnDeviceTest`, `DataBarResponsiveTest`,
`RideLandscapeLayoutTest`, `MediaPanelJoinTest`, `NavigationHudTest`,
`FontScaleTest`. `AccessibilityTest` owns the real-screen glove targets; do not
add per-suite copies. Fuzz `ToggleMediaPanel`/`StartRide` (oracle floors 64 dp
and 80 dp play/pause; the campaign reports `mediaPanelStates`).

`visual.py media-controls` captures the B&W playing/paused/no-player/
permission-needed states and the Back-dismissed panel from a debug-only
synthetic session (`VisualMediaSessionReceiver`; release creates none).
On-device helpers edit only this app's entry in
`enabled_notification_listeners` and restore the exact starting access in
`@After` (the visual runner in a `finally`).
