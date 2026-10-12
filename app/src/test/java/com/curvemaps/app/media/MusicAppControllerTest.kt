package com.curvemaps.app.media

import org.junit.Assert.*
import org.junit.Test

class MusicAppControllerTest {
    private class Apps : MusicAppGateway {
        val calls = mutableListOf<String>()
        var result: ((MusicStartResult) -> Unit)? = null
        var cancelled = false
        override fun apps() = listOf(MusicApp("spotify", "Spotify"))
        override fun start(packageName: String, onResult: (MusicStartResult) -> Unit) {
            calls += packageName
            result = onResult
        }
        override fun open(packageName: String) = false
        override fun cancel() { cancelled = true }
    }
    private val apps = Apps()
    private val sessions = FakeMediaSessionGateway(access = false)
    private val keys = FakeMediaKeys()
    private val controller = MediaCenterController(sessions, FakeLocalVolume(), keys, apps)

    @Test fun coldStartIsIndependentOfNotificationAccessAndMediaKeys() {
        controller.refresh()
        controller.startMusicApp("spotify")
        controller.startMusicApp("spotify")
        controller.startMusicApp("unknown")
        assertEquals(listOf("spotify"), apps.calls)
        assertEquals("spotify", controller.state.value.startingMusicApp)
        apps.result!!(MusicStartResult.REQUESTED)
        assertNull(controller.state.value.startingMusicApp)
        assertFalse(controller.state.value.isPlaying)
        assertTrue(controller.state.value.musicAppMessage!!.startsWith("Playback requested"))
        assertTrue(keys.pressed.isEmpty())
        assertTrue(sessions.transportCalls.isEmpty())
    }
    @Test fun openIsReportedAsChoosingMusicInsteadOfPlaying() {
        controller.refresh()
        controller.startMusicApp("spotify")
        apps.result!!(MusicStartResult.OPENED)
        assertFalse(controller.state.value.isPlaying)
        assertTrue(controller.state.value.musicAppMessage!!.startsWith("Choose music"))
    }
    @Test fun rejectedRequestsAndAppRemovalAreHonest() {
        controller.refresh()
        controller.startMusicApp("spotify")
        apps.result!!(MusicStartResult.UNAVAILABLE)
        assertTrue(controller.state.value.musicAppMessage!!.startsWith("Couldn’t start"))
        assertFalse(controller.openMusicApp("spotify"))
        assertTrue(controller.state.value.musicAppMessage!!.startsWith("Couldn’t open"))
        assertFalse(controller.openMusicApp("uninstalled"))
    }
    @Test fun existingPausedPlayerUsesItsSessionInsteadOfColdStart() {
        sessions.access = true
        sessions.sessions = listOf(session("spotify-session", packageName = "spotify"))
        controller.refresh()
        controller.startMusicApp("spotify")
        assertEquals(listOf("spotify-session" to MediaCommand.PLAY), sessions.transportCalls)
        assertEquals("spotify-session", controller.state.value.pinnedKey)
        assertTrue(apps.calls.isEmpty())
    }
    @Test fun alreadyPlayingPlayerDoesNotRestartTheTrack() {
        sessions.access = true
        sessions.sessions = listOf(session("spotify-session", packageName = "spotify", isPlaying = true))
        controller.refresh()
        controller.startMusicApp("spotify")
        assertTrue(sessions.transportCalls.isEmpty())
        assertTrue(apps.calls.isEmpty())
        assertEquals("Spotify is already playing.", controller.state.value.musicAppMessage)
    }
    @Test fun patreonSessionRemainsControllableWithoutColdStartSupport() {
        assertFalse("com.patreon.android" in KnownMusicApps.packages)
        sessions.access = true
        sessions.sessions = listOf(session("patreon-session", packageName = "com.patreon.android", isPlaying = true))
        controller.refresh()
        assertEquals("patreon-session", controller.state.value.selectedKey)
        assertTrue(controller.dispatch(MediaCommand.PAUSE))
        sessions.sessions = listOf(session("patreon-session", packageName = "com.patreon.android"))
        controller.refresh()
        assertTrue(controller.dispatch(MediaCommand.PLAY))
        controller.startMusicApp("com.patreon.android")
        assertEquals(listOf("patreon-session" to MediaCommand.PAUSE,
            "patreon-session" to MediaCommand.PLAY), sessions.transportCalls)
        assertTrue(apps.calls.isEmpty())
        assertTrue(keys.pressed.isEmpty())
    }
    @Test fun systemPlayPauseRemainAvailableWithoutAnAdvertisedMusicApp() {
        controller.refresh()
        assertTrue(controller.dispatch(MediaCommand.PAUSE))
        assertTrue(controller.dispatch(MediaCommand.PLAY))
        assertEquals(listOf(MediaCommand.PAUSE, MediaCommand.PLAY), keys.pressed)
        assertTrue(apps.calls.isEmpty())
    }
    @Test fun closingPanelCancelsPendingLaunch() {
        controller.setPanelOpen(true)
        controller.startMusicApp("spotify")
        controller.setPanelOpen(false)
        apps.result!!(MusicStartResult.OPENED)
        assertTrue(apps.cancelled)
        assertNull(controller.state.value.startingMusicApp)
        assertNull(controller.state.value.musicAppMessage)
    }
    @Test fun detachCancelsStartAndIgnoresLateCallback() {
        controller.refresh()
        controller.startMusicApp("spotify")
        controller.detach()
        val snapshot = controller.state.value
        apps.result!!(MusicStartResult.REQUESTED)
        assertTrue(apps.cancelled)
        assertEquals(snapshot, controller.state.value)
    }
}
