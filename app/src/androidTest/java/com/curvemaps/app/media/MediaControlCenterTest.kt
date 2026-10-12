package com.curvemaps.app.media
import com.curvemaps.app.ui.theme.CurveMapsTheme
import android.os.SystemClock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose contract of the media control center: explicit state-driven
 * transport, honest disabled commands, the permission/fallback states, the
 * unknown-state explicit Play/Pause pair, the 80 dp connected transport group,
 * glove-sized volume buttons and chips with visible separation, and the
 * narrow/landscape/scroll behaviour.
 */
@RunWith(AndroidJUnit4::class)
class MediaControlCenterTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val commands = mutableListOf<MediaCommand>()
    private val volumes = mutableListOf<VolumeDirection>()
    private val startedApps = mutableListOf<String>()
    private val openedApps = mutableListOf<String>()
    private val selected = mutableListOf<String>()
    @Volatile private var closed = false
    @Volatile private var closedAtMillis = 0L
    private var updateState: ((MediaCenterState) -> Unit)? = null
    private var setPanelVisible: ((Boolean) -> Unit)? = null
    private fun show(
        state: MediaCenterState,
        fontScale: Float = 1f,
        width: Dp? = null,
        maxHeight: Dp? = null,
        inactivityTimeoutMillis: Long = MediaPanelTimeoutMillis,
    ) {
        val density = composeRule.density
        composeRule.setContent {
            var displayedState by remember { mutableStateOf(state) }
            var panelVisible by remember { mutableStateOf(true) }
            SideEffect {
                updateState = { displayedState = it }
                setPanelVisible = { panelVisible = it }
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                CurveMapsTheme {
                    Box(
                        Modifier.then(if (width != null) Modifier.width(width) else Modifier),
                    ) {
                        if (panelVisible) {
                            MediaControlCenter(
                                state = displayedState,
                                onStartMusicApp = { startedApps += it },
                                onOpenMusicApp = { openedApps += it },
                                onCommand = { commands += it },
                                onVolume = { volumes += it },
                                onSelectPlayer = { selected += it },
                                onClose = {
                                    closed = true
                                    closedAtMillis = SystemClock.elapsedRealtime()
                                },
                                maxHeight = maxHeight,
                                inactivityTimeoutMillis = inactivityTimeoutMillis,
                                modifier = Modifier,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun session(
        key: String,
        playing: Boolean,
        actions: Long,
        volumeControl: Int = MediaVolumeControl.ABSOLUTE,
        volume: Int = 7,
        maxVolume: Int = 15,
        playbackKnown: Boolean = true,
    ) = MediaSessionInfo(
        key = key,
        packageName = key,
        playerName = key,
        title = "Song",
        artist = "Artist",
        album = null,
        isPlaying = playing,
        actions = actions,
        lastActiveTime = 0,
        volumeControl = volumeControl,
        volume = volume,
        maxVolume = maxVolume,
        playbackKnown = playbackKnown,
    )

    private fun readyState(
        sessions: List<MediaSessionInfo>,
        selectedKey: String = sessions.first().key,
    ) = MediaCenterState(
        access = MediaAccessState.GRANTED,
        sessions = sessions,
        selectedKey = selectedKey,
        resolvedVolume = MediaCapabilities.volumeStateFor(sessions.firstOrNull { it.key == selectedKey }),
    )

    private fun bounds(description: String): androidx.compose.ui.geometry.Rect =
        composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot

    private fun px(dp: Float): Float = dp * composeRule.density.density

    @Test
    fun installedSpotifyCanStartWithoutSessionOrNotificationAccess() {
        show(MediaCenterState(access = MediaAccessState.DENIED,
            musicApps = listOf(MusicApp("com.spotify.music", "Spotify"))))
        composeRule.onNodeWithContentDescription("Show music apps").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Start music in Spotify").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Open music app Spotify").performScrollTo().performClick()
        assertEquals(listOf("com.spotify.music"), startedApps)
        assertEquals(listOf("com.spotify.music"), openedApps)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun installedPlayerTargetsFitLargeFontsAndRemainGloveSized() {
        show(MediaCenterState(access = MediaAccessState.GRANTED,
            musicApps = listOf(MusicApp("com.spotify.music", "Spotify"))),
            width = 320.dp, fontScale = 2f, maxHeight = 240.dp)
        composeRule.onNodeWithContentDescription("Show music apps").performScrollTo().performClick()
        listOf("Hide music apps", "Start music in Spotify", "Open music app Spotify").forEach { description ->
            val node = composeRule.onNodeWithContentDescription(description).performScrollTo()
            node.assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val floor = 64f * composeRule.density.density
            assertTrue(bounds.width >= floor && bounds.height >= floor)
        }
    }

    @Test
    fun startingPlayerDisablesDuplicateStartAndOpen() {
        show(MediaCenterState(musicApps = listOf(MusicApp("spotify", "Spotify")), startingMusicApp = "spotify"))
        composeRule.onNodeWithContentDescription("Show music apps").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Start music in Spotify").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Open music app Spotify").assertIsNotEnabled()
    }

    @Test
    fun appControlsStayHiddenAndOnlyChosenAppHasActions() {
        val apps = listOf(MusicApp("com.spotify.music", "Spotify"), MusicApp("com.aspiro.tidal", "TIDAL"))
        show(MediaCenterState(musicApps = apps), width = 320.dp, fontScale = 2f, maxHeight = 240.dp)
        composeRule.onNodeWithContentDescription("Start music in Spotify").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Start music in TIDAL").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Show music apps").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Start music in Spotify").assertExists()
        composeRule.onNodeWithContentDescription("Choose music app TIDAL").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Start music in TIDAL").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Change music app").performScrollTo().performClick()
        listOf("Choose music app Spotify", "Choose music app TIDAL").forEach { description ->
            val node = composeRule.onNodeWithContentDescription(description).performScrollTo()
            node.assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= px(64f) && bounds.height >= px(64f))
        }
        composeRule.onNodeWithContentDescription("Choose music app TIDAL").performClick()
        composeRule.onNodeWithContentDescription("Choose music app Spotify").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Start music in Spotify").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Start music in TIDAL").performScrollTo().performClick()
        assertEquals(listOf("com.aspiro.tidal"), startedApps)
        composeRule.onNodeWithContentDescription("Hide music apps").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Start music in TIDAL").assertDoesNotExist()
    }

    @Test
    fun noInstalledAppsMeansNoPickerAndRemovalHidesIt() {
        show(MediaCenterState())
        composeRule.onNodeWithContentDescription("Show music apps").assertDoesNotExist()
        composeRule.runOnIdle { updateState?.invoke(MediaCenterState(musicApps = listOf(MusicApp("spotify", "Spotify")))) }
        composeRule.onNodeWithContentDescription("Show music apps").performScrollTo().performClick()
        composeRule.runOnIdle { updateState?.invoke(MediaCenterState()) }
        composeRule.onNodeWithContentDescription("Hide music apps").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Start music in Spotify").assertDoesNotExist()
    }

    @Test
    fun untouchedPanelClosesAfterItsInactivityWindow() {
        show(
            MediaCenterState(access = MediaAccessState.DENIED),
            inactivityTimeoutMillis = 350L,
        )
        composeRule.onNodeWithContentDescription("Media controls inactivity timer").assertIsDisplayed()
        composeRule.waitUntil(3_000) { closed }
    }

    @Test
    fun reopeningAfterCloseStartsAFullInactivityWindow() {
        show(
            MediaCenterState(access = MediaAccessState.DENIED),
            inactivityTimeoutMillis = 600L,
        )
        Thread.sleep(400)
        composeRule.onNodeWithContentDescription("Close media controls").performClick()
        assertTrue("Close must dismiss immediately", closed)
        composeRule.runOnIdle { setPanelVisible?.invoke(false) }
        Thread.sleep(700)

        var reopenedAt = 0L
        composeRule.runOnIdle {
            closed = false
            closedAtMillis = 0L
            setPanelVisible?.invoke(true)
            reopenedAt = SystemClock.elapsedRealtime()
        }
        Thread.sleep(350)
        assertFalse("reopened panel must receive a fresh timer", closed)
        composeRule.waitUntil(1_500) { closed }
        assertTrue(closedAtMillis - reopenedAt >= 550L)
    }

    @Test
    fun semanticTransportAndVolumeActivationsRestartTheInactivityWindow() {
        show(
            readyState(
                listOf(
                    session(
                        "Spotify",
                        playing = true,
                        actions = MediaActionBits.PLAY or MediaActionBits.PAUSE,
                    ),
                ),
            ),
            inactivityTimeoutMillis = 600L,
        )
        Thread.sleep(400)
        composeRule.onNodeWithContentDescription("Pause").performClick()
        Thread.sleep(400)
        assertFalse("semantic Pause activation must restart the timer", closed)
        composeRule.onNodeWithContentDescription("Volume up, Spotify").performClick()
        val interactedAt = SystemClock.elapsedRealtime()
        Thread.sleep(300)
        assertFalse("semantic volume activation must restart the timer", closed)
        composeRule.waitUntil(1_500) { closed }
        assertTrue(closedAtMillis - interactedAt >= 550L)
        assertEquals(listOf(MediaCommand.PAUSE), commands)
        assertEquals(listOf(VolumeDirection.UP), volumes)
    }

    @Test
    fun panelTouchRestartsTheInactivityWindow() {
        show(
            readyState(listOf(session("Spotify", playing = false, actions = MediaActionBits.PLAY))),
            inactivityTimeoutMillis = 600L,
        )
        Thread.sleep(400)
        composeRule.onNodeWithContentDescription("Media control center").performTouchInput {
            val tap = Offset(visibleSize.width / 2f, 2f)
            down(tap)
            up()
        }
        val interactedAt = SystemClock.elapsedRealtime()
        Thread.sleep(300)
        assertFalse("panel touch must restart the timer", closed)
        composeRule.waitUntil(1_500) { closed }
        assertTrue(closedAtMillis - interactedAt >= 550L)
    }

    @Test
    fun holdingAPanelTouchPastTheTimeoutKeepsItOpenUntilRelease() {
        show(
            MediaCenterState(access = MediaAccessState.DENIED),
            inactivityTimeoutMillis = 400L,
        )
        Thread.sleep(200)
        val panel = composeRule.onNodeWithContentDescription("Media control center")
        val touch = Offset(
            panel.fetchSemanticsNode().boundsInRoot.width / 2f,
            2f,
        )
        panel.performTouchInput { down(touch) }
        Thread.sleep(500)
        assertFalse("an active touch must not close the panel", closed)
        panel.performTouchInput { up() }
        composeRule.waitUntil(1_000) { closed }
    }


    @Test
    fun scrollingRestartsTheInactivityWindow() {
        show(
            readyState(listOf(session("Spotify", playing = true, actions = MediaActionBits.PAUSE))),
            width = 320.dp,
            maxHeight = 150.dp,
            inactivityTimeoutMillis = 600L,
        )
        Thread.sleep(400)
        val interactedAt = SystemClock.elapsedRealtime()
        composeRule.onNodeWithContentDescription("Volume down, Spotify").performScrollTo()
        Thread.sleep(300)
        assertFalse("scrolling must restart the timer", closed)
        composeRule.waitUntil(1_500) { closed }
        assertTrue(closedAtMillis - interactedAt >= 550L)
    }

    @Test
    fun playbackAndVolumeObservationsDoNotRestartTheInactivityWindow() {
        val first = session(
            "Spotify",
            playing = true,
            actions = MediaActionBits.PAUSE,
        )
        show(readyState(listOf(first)), inactivityTimeoutMillis = 900L)
        Thread.sleep(600)
        val observed = session(
            "Spotify",
            playing = false,
            actions = MediaActionBits.PLAY,
            volume = 10,
        )
        composeRule.runOnIdle { updateState?.invoke(readyState(listOf(observed))) }
        composeRule.waitUntil(700) { closed }
    }
    @Test
    fun playingSessionShowsPauseAndRequestsPause() {
        val playing = session(
            "Spotify",
            playing = true,
            actions = MediaActionBits.PLAY or MediaActionBits.PAUSE or
                MediaActionBits.SKIP_TO_NEXT or MediaActionBits.SKIP_TO_PREVIOUS,
        )
        show(readyState(listOf(playing)))
        composeRule.onNodeWithContentDescription("Media control center").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Spotify · Playing").assertIsDisplayed()
        assertEquals(listOf(MediaCommand.PAUSE), commands)
    }

    @Test
    fun pausedSessionShowsPlay() {
        val paused = session(
            "Spotify",
            playing = false,
            actions = MediaActionBits.PLAY or MediaActionBits.PAUSE,
        )
        show(readyState(listOf(paused)))
        composeRule.onNodeWithContentDescription("Play").assertIsDisplayed().performClick()
        assertEquals(listOf(MediaCommand.PLAY), commands)
    }

    @Test
    fun unsupportedTransportIsDisabled() {
        // Only PLAY advertised: Next must be honestly disabled.
        val paused = session("Spotify", playing = false, actions = MediaActionBits.PLAY)
        show(readyState(listOf(paused)))
        composeRule.onNodeWithContentDescription("Next track").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Previous track").assertIsNotEnabled()
    }

    @Test
    fun permissionNeededKeepsVolumeUsableWithoutSetupButton() {
        val state = MediaCenterState(
            access = MediaAccessState.DENIED,
            resolvedVolume = VolumeState.phone(current = 5, min = 0, max = 15),
            localVolume = VolumeState.phone(current = 5, min = 0, max = 15),
        )
        show(state)
        composeRule.onNodeWithText("Notification access").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Enable notification access").assertDoesNotExist()
        composeRule.onNodeWithText("Using system media keys (limited control)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Volume up, Phone").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Volume down, Phone").assertIsDisplayed()
        assertEquals(listOf(VolumeDirection.UP), volumes)
    }

    @Test
    fun unknownPlaybackStateShowsExplicitPauseAndPlay() {
        // Denied access: the state is unknown, so the panel must offer both
        // directions instead of a toggle whose label would have to lie.
        val state = MediaCenterState(
            access = MediaAccessState.DENIED,
            resolvedVolume = VolumeState.phone(current = 5, min = 0, max = 15),
        )
        show(state)
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Play").assertIsDisplayed().performClick()
        assertEquals(listOf(MediaCommand.PAUSE, MediaCommand.PLAY), commands)
        composeRule.onNodeWithText("Playback state unknown — Play and Pause are explicit")
            .assertIsDisplayed()
    }

    @Test
    fun sessionWithoutPlaybackStateAlsoUsesTheExplicitPair() {
        val unknown = session(
            "Spotify",
            playing = false,
            actions = MediaActionBits.PLAY or MediaActionBits.PAUSE,
            playbackKnown = false,
        )
        show(readyState(listOf(unknown)))
        // Both directions are honest and enabled by the advertised actions.
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Play").assertIsDisplayed().performClick()
        assertEquals(listOf(MediaCommand.PAUSE, MediaCommand.PLAY), commands)
    }

    @Test
    fun closeButtonDismisses() {
        show(readyState(listOf(session("Spotify", playing = true, actions = MediaActionBits.PAUSE))))
        composeRule.onNodeWithContentDescription("Close media controls").performClick()
        assertTrue(closed)
    }

    @Test
    fun controlsMeetGloveMinimumTargets() {
        val playing = session(
            "Spotify",
            playing = true,
            actions = MediaActionBits.PLAY or MediaActionBits.PAUSE or
                MediaActionBits.SKIP_TO_NEXT or MediaActionBits.SKIP_TO_PREVIOUS,
        )
        show(readyState(listOf(playing)))
        val glove = px(64f)
        listOf(
            "Close media controls",
            "Previous track",
            "Next track",
            "Volume down, Spotify",
            "Volume up, Spotify",
        ).forEach { description ->
            val bounds = bounds(description)
            assertTrue(
                "$description too small for gloves: ${bounds.width}x${bounds.height}",
                bounds.width >= glove && bounds.height >= glove,
            )
        }
        // The primary play/pause control is roomier still.
        val playPause = bounds("Pause")
        val preferred = px(80f)
        assertTrue(
            "play/pause too small: ${playPause.width}x${playPause.height}",
            playPause.width >= preferred && playPause.height >= preferred,
        )
    }

    @Test
    fun adjacentTransportTargetsAreVisiblySeparated() {
        val playing = session(
            "Spotify",
            playing = true,
            actions = MediaActionBits.PLAY or MediaActionBits.PAUSE or
                MediaActionBits.SKIP_TO_NEXT or MediaActionBits.SKIP_TO_PREVIOUS,
        )
        show(readyState(listOf(playing)))
        val previous = bounds("Previous track")
        val playPause = bounds("Pause")
        val next = bounds("Next track")
        // The connected group's seam: visible, but only the group's 4 dp (every key
        // is 80 dp tall and at least 80 dp wide, so the targets stay glove-sized).
        val gap = px(4f) - 1f
        assertTrue(
            "previous/pause overlap: ${previous.right} vs ${playPause.left}",
            playPause.left - previous.right >= gap,
        )
        assertTrue(
            "pause/next overlap: ${playPause.right} vs ${next.left}",
            next.left - playPause.right >= gap,
        )
    }

    @Test
    fun transportIsAnEightyDpConnectedGroupAndPlayPauseIsTheWidest() {
        val playing = session(
            "Spotify",
            playing = true,
            actions = MediaActionBits.PLAY or MediaActionBits.PAUSE or
                MediaActionBits.SKIP_TO_NEXT or MediaActionBits.SKIP_TO_PREVIOUS,
        )
        show(readyState(listOf(playing)), width = 320.dp)
        val slack = px(1f)
        // Close stays a round glove key.
        val close = bounds("Close media controls")
        assertEquals("close is not round: ${close.width}x${close.height}", close.width, close.height, slack)
        assertTrue("close below 64 dp: ${close.width}", close.width >= px(64f))
        // The three transport keys are 80 dp tall and at least 64 dp wide.
        listOf("Previous track", "Pause", "Next track").forEach { description ->
            val key = bounds(description)
            assertEquals("$description is not 80 dp tall: ${key.height}", px(80f), key.height, slack)
            assertTrue("$description below 64 dp wide: ${key.width}", key.width >= px(64f))
        }
        val playPause = bounds("Pause")
        assertTrue("play/pause below 80 dp: ${playPause.width}", playPause.width >= px(80f))
        assertTrue(
            "play/pause must be the widest transport key",
            playPause.width > bounds("Previous track").width && playPause.width > bounds("Next track").width,
        )
        // Previous and Next sit level with the play/pause key's centre.
        listOf("Previous track", "Next track").forEach { description ->
            assertEquals(
                "$description is not centred on play/pause",
                playPause.center.y,
                bounds(description).center.y,
                slack,
            )
        }
    }

    @Test
    fun unknownStateOffersTwoExplicitPlayPauseKeys() {
        show(
            MediaCenterState(
                access = MediaAccessState.DENIED,
                resolvedVolume = VolumeState.phone(current = 5, min = 0, max = 15),
            ),
            width = 320.dp,
        )
        val pause = bounds("Pause")
        val play = bounds("Play")
        listOf("Pause" to pause, "Play" to play).forEach { (name, key) ->
            assertTrue("$name below 80 dp tall: ${key.height}", key.height >= px(80f))
            assertTrue("$name below 80 dp wide: ${key.width}", key.width >= px(80f))
        }
        assertEquals("Pause and Play not level", pause.top, play.top, px(1f))
        assertTrue("Pause and Play overlap: ${pause.right} vs ${play.left}", play.left - pause.right >= px(4f) - 1f)
        // The skips keep their own group below, never squeezed beside the pair.
        val previous = bounds("Previous track")
        val next = bounds("Next track")
        assertTrue("skips overlap the pair", previous.top >= pause.bottom + px(4f) - 1f)
        assertTrue("Previous/Next overlap", next.left - previous.right >= px(4f) - 1f)
    }

    @Test
    fun volumeStepsAreSeparateRoundButtonsAroundTheLevelSlider() {
        val playing = session("Spotify", playing = true, actions = MediaActionBits.PAUSE)
        show(readyState(listOf(playing)), width = 320.dp)
        val down = bounds("Volume down, Spotify")
        val up = bounds("Volume up, Spotify")
        val level = bounds("Spotify volume level")
        listOf("down" to down, "up" to up).forEach { (name, button) ->
            assertTrue("volume $name below 64 dp: ${button.width}x${button.height}",
                button.width >= px(64f) - 1f && button.height >= px(64f) - 1f)
            assertEquals("volume $name is not round", button.width, button.height, px(1f))
        }
        // The slider sits between the two buttons with a visible gap each side.
        assertTrue("slider touches Volume down", level.left - down.right >= px(8f) - 1f)
        assertTrue("slider touches Volume up", up.left - level.right >= px(8f) - 1f)
        assertEquals("volume buttons not level", down.top, up.top, px(1f))
    }

    @Test
    fun chosenPlayerChipIsMarkedSelectedAndStaysGloveSized() {
        val first = session("Spotify", playing = false, actions = MediaActionBits.PLAY)
        val second = session("Radio", playing = true, actions = MediaActionBits.PLAY or MediaActionBits.PAUSE)
        show(readyState(listOf(first, second), selectedKey = second.key))
        composeRule.onNodeWithContentDescription("Select player Radio").assertIsSelected()
        composeRule.onNodeWithContentDescription("Select player Spotify").assertIsNotSelected()
        listOf("Select player Radio", "Select player Spotify").forEach { description ->
            // The chips sit low in a scrolling panel: measure the laid-out
            // chip, not the slice the panel's viewport happens to show.
            val node = composeRule.onNodeWithContentDescription(description)
            node.performScrollTo()
            val chip = node.fetchSemanticsNode().size
            assertTrue("$description below 64 dp: ${chip.height}", chip.height >= px(64f) - 1f)
            assertTrue("$description is not a pill: ${chip.width}x${chip.height}", chip.width > chip.height)
        }
        composeRule.onNodeWithContentDescription("Select player Radio").performScrollTo()
        val radio = bounds("Select player Radio")
        val spotify = bounds("Select player Spotify")
        val (left, right) = if (radio.left < spotify.left) radio to spotify else spotify to radio
        assertTrue("player chips touch", right.left - left.right >= px(12f) - 1f)
    }

    @Test
    fun narrowLargeFontKeepsEveryControlReachable() {
        val playing = session(
            "Spotify",
            playing = true,
            actions = MediaActionBits.PLAY or MediaActionBits.PAUSE or
                MediaActionBits.SKIP_TO_NEXT or MediaActionBits.SKIP_TO_PREVIOUS,
        )
        show(readyState(listOf(playing)), fontScale = 2f, width = 320.dp)
        val glove = px(64f)
        listOf(
            "Close media controls",
            "Previous track",
            "Pause",
            "Next track",
            "Volume down, Spotify",
            "Volume up, Spotify",
        ).forEach { description ->
            val target = bounds(description)
            assertTrue(
                "$description clipped at 320 dp / 2x: ${target.width}x${target.height}",
                target.width >= glove && target.height >= glove,
            )
            // Nothing may extend past the 320 dp panel.
            assertTrue(
                "$description leaves the screen: ${target.left}..${target.right}",
                target.left >= -1f && target.right <= px(320f) + 1f,
            )
        }
    }

    @Test
    fun boundedLandscapePanelScrollsToEveryControl() {
        val playing = session(
            "Spotify",
            playing = true,
            actions = MediaActionBits.PLAY or MediaActionBits.PAUSE or
                MediaActionBits.SKIP_TO_NEXT or MediaActionBits.SKIP_TO_PREVIOUS,
        )
        // A short landscape card: the content must scroll, and the transport
        // and close controls must still be reachable.
        show(readyState(listOf(playing)), maxHeight = 180.dp, width = 320.dp)
        composeRule.onNodeWithContentDescription("Media control center").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close media controls")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun multipleSessionsExposeLargePlayerSelection() {
        val first = session("Spotify", playing = false, actions = MediaActionBits.PLAY)
        val second = session("Radio", playing = true, actions = MediaActionBits.PLAY or MediaActionBits.PAUSE)
        show(
            readyState(listOf(first, second), selectedKey = second.key),
            inactivityTimeoutMillis = 600L,
        )
        Thread.sleep(400)
        composeRule.onNodeWithContentDescription("Select player Spotify").assertIsDisplayed().performClick()
        val interactedAt = SystemClock.elapsedRealtime()
        Thread.sleep(300)
        assertFalse("player selection must restart the inactivity timer", closed)
        composeRule.waitUntil(1_500) { closed }
        assertTrue(closedAtMillis - interactedAt >= 550L)
        val chip = bounds("Select player Spotify")
        assertTrue(
            "player chip too small for gloves: ${chip.width}x${chip.height}",
            chip.height >= px(64f),
        )
    }

    @Test
    fun largeFontScaleKeepsTransportOnScreen() {
        val playing = session(
            "Spotify",
            playing = true,
            actions = MediaActionBits.PAUSE or MediaActionBits.SKIP_TO_NEXT or MediaActionBits.SKIP_TO_PREVIOUS,
        )
        show(readyState(listOf(playing)), fontScale = 2f)
        composeRule.onNodeWithContentDescription("Media control center").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close media controls").assertIsDisplayed()
    }

    @Test
    fun boundedPanelStaysWithinTheRequestedHeight() {
        val playing = session(
            "Spotify",
            playing = true,
            actions = MediaActionBits.PLAY or MediaActionBits.PAUSE or
                MediaActionBits.SKIP_TO_NEXT or MediaActionBits.SKIP_TO_PREVIOUS,
        )
        show(readyState(listOf(playing)), maxHeight = 200.dp, width = 320.dp, fontScale = 2f)
        val panel = composeRule.onNodeWithContentDescription("Media control center")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "panel ${panel.height} must respect the landscape bound",
            panel.height <= 200f * composeRule.density.density + 1f,
        )
    }

    @Test
    fun transportIconsDoNotOverlapTheHeader() {
        val playing = session(
            "Spotify",
            playing = true,
            actions = MediaActionBits.PLAY or MediaActionBits.PAUSE or
                MediaActionBits.SKIP_TO_NEXT or MediaActionBits.SKIP_TO_PREVIOUS,
        )
        show(readyState(listOf(playing)), fontScale = 2f, width = 320.dp)
        val close = bounds("Close media controls")
        val previous = bounds("Previous track")
        assertTrue(
            "close overlaps the transport row: ${close.bottom} vs ${previous.top}",
            previous.top >= close.bottom - 1f || close.left >= previous.right - 1f,
        )
        // Also assert the header's close button is a full glove target.
        assertTrue(close.width >= px(64f) && close.height >= px(64f))
    }

    @Test
    fun heightConstraintDoesNotClipTheCard() {
        val playing = session(
            "Spotify",
            playing = true,
            actions = MediaActionBits.PLAY or MediaActionBits.PAUSE,
        )
        show(readyState(listOf(playing)), maxHeight = 400.dp, width = 320.dp)
        val panel = composeRule.onNodeWithContentDescription("Media control center")
            .fetchSemanticsNode().boundsInRoot
        val playPause = bounds("Pause")
        assertTrue(playPause.top >= panel.top - 1f && playPause.bottom <= panel.bottom + 1f)
    }
}
