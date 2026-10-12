package com.curvemaps.app.media

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media panel's Material 3 layout keeps the glove contract: every target at
 * least [GloveTarget] (play-or-pause at least [PlayPauseTarget]), the connected
 * transport group 80 dp tall, play-or-pause the widest key, and the widest
 * rows still fitting a 320 dp phone without shrinking a key below the floor.
 */
class MediaPanelKeysTest {

    @Test
    fun `the glove floors are 64 and 80 dp`() {
        assertTrue("the glove floor is 64 dp", GloveTarget >= 64.dp)
        assertTrue("play/pause floor is 80 dp", PlayPauseTarget >= 80.dp)
        assertTrue("the glove gap is 12 dp", GloveGap >= 12.dp)
    }

    @Test
    fun `transport group and ride targets keep their sizes`() {
        assertEquals(80.dp, MediaPanelLayout.transportHeight)
        assertTrue(MediaPanelLayout.transportHeight >= PlayPauseTarget)
        assertTrue("volume steps below the glove floor", MediaPanelLayout.volumeButton >= GloveTarget)
        assertTrue("close below the glove floor", MediaPanelLayout.closeTarget >= GloveTarget)
        assertTrue("music-app rows below the glove floor", MediaPanelLayout.appRow >= GloveTarget)
        assertTrue(
            "the timeout ring must sit inside the close target",
            MediaPanelLayout.timerRing <= MediaPanelLayout.closeTarget,
        )
    }

    @Test
    fun `play-or-pause is the widest key of the connected group`() {
        assertTrue(MediaPanelLayout.PlayWeight > MediaPanelLayout.SkipWeight)
        assertEquals(4.dp, MediaPanelLayout.transportGap)
    }

    @Test
    fun `the widest transport rows fit a 320 dp panel at the glove floor`() {
        val panel = 320.dp
        val skip = MediaPanelLayout.skipKeyWidth(panel)
        val play = MediaPanelLayout.playKeyWidth(panel)
        val pair = MediaPanelLayout.pairKeyWidth(panel)
        assertTrue("Previous/Next ($skip) below the glove floor", skip >= GloveTarget)
        assertTrue("play/pause ($play) below its 80 dp floor", play >= PlayPauseTarget)
        assertTrue("explicit Pause/Play ($pair) below its 80 dp floor", pair >= PlayPauseTarget)
        // The three keys and two seams use exactly the content width.
        val total = skip * 2 + play + MediaPanelLayout.transportGap * 2
        val content = panel - MediaPanelLayout.horizontalPadding * 2
        assertEquals(content.value, total.value, 0.01f)
    }

    @Test
    fun `the volume row leaves the slider room at 320 dp`() {
        val content = 320.dp - MediaPanelLayout.horizontalPadding * 2
        val slider = content - MediaPanelLayout.volumeButton * 2 - MediaPanelLayout.volumeGap * 2
        assertTrue("slider only $slider wide at 320 dp", slider >= 96.dp)
    }
}
