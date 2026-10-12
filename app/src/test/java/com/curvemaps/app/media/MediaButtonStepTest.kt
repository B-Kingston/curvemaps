package com.curvemaps.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaButtonStepTest {
    @Test fun observedPlaybackConfirmsThePressAtAnyTime() {
        assertEquals(MediaButtonStep.PLAYING, mediaButtonStep(0, playing = true))
        assertEquals(MediaButtonStep.PLAYING, mediaButtonStep(MediaButtonConfirmMillis + 1, playing = true))
    }
    @Test fun aSilentPlayerFallsBackExactlyAtTheWindow() {
        assertEquals(MediaButtonStep.WAIT, mediaButtonStep(MediaButtonConfirmMillis - 1, playing = false))
        assertEquals(MediaButtonStep.FALL_BACK, mediaButtonStep(MediaButtonConfirmMillis, playing = false))
    }
    @Test fun losingSessionAccessFallsBackInsteadOfWaitingBlind() {
        assertEquals(MediaButtonStep.FALL_BACK, mediaButtonStep(0, playing = null))
    }
    @Test fun browserAndButtonWaitsLeaveTheFallbackTimeToReturn() {
        // A Spotify-shaped start can spend both waits before it opens the
        // player's screen; the return watch counts from the Start tap.
        assertTrue(MusicBrowserTimeoutMillis + MediaButtonConfirmMillis < ReturnToRideWindowMillis)
    }
}
