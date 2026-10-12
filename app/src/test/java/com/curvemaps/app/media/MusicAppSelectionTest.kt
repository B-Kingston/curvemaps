package com.curvemaps.app.media

import org.junit.Assert.*
import org.junit.Test

class MusicAppSelectionTest {
    private val spotify = MusicApp("com.spotify.music", "Spotify")
    private val tidal = MusicApp("com.aspiro.tidal", "TIDAL")
    private val apps = listOf(spotify, tidal)

    @Test fun explicitChoiceSurvivesSessionChangesAndListReordering() {
        assertEquals(tidal, selectMusicApp(apps, tidal.packageName, spotify.packageName))
        assertEquals(tidal, selectMusicApp(apps.reversed(), tidal.packageName, null))
    }

    @Test fun initialChoiceUsesActiveInstalledPlayerThenFirstAvailable() {
        assertEquals(tidal, selectMusicApp(apps, null, tidal.packageName))
        assertEquals(spotify, selectMusicApp(apps, null, "unrelated.video"))
    }

    @Test fun removedChoiceFallsBackWithoutAdvertisingMissingApps() {
        assertEquals(spotify, selectMusicApp(listOf(spotify), tidal.packageName, tidal.packageName))
        assertNull(selectMusicApp(emptyList(), spotify.packageName, spotify.packageName))
    }
}
