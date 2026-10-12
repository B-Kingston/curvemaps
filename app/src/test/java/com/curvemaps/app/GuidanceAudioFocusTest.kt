package com.curvemaps.app

import android.media.AudioManager
import com.curvemaps.app.GuidanceAudioFocus.Reaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract for how spoken guidance ducks and restores the rider's music. */
class GuidanceAudioFocusTest {

    @Test
    fun `a fresh duck waits for the music to fade before the first word`() {
        assertTrue(GuidanceAudioFocus.leadInMillis(focusAlreadyHeld = false) in 200L..500L)
    }

    @Test
    fun `a phrase inside an existing duck starts at once`() {
        assertEquals(0L, GuidanceAudioFocus.leadInMillis(focusAlreadyHeld = true))
    }

    @Test
    fun `music returns only after the voice has decayed`() {
        // Long enough to bridge back-to-back prompts, short enough that the
        // music does not sit ducked through silence.
        assertTrue(GuidanceAudioFocus.RELEASE_TAIL_MS in 400L..1_500L)
    }

    @Test
    fun `a brief duckable sound from another app never cuts a prompt off`() {
        assertEquals(Reaction.KEEP, GuidanceAudioFocus.reactionTo(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, speaking = true))
        assertEquals(Reaction.KEEP, GuidanceAudioFocus.reactionTo(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, speaking = false))
        assertEquals(Reaction.KEEP, GuidanceAudioFocus.reactionTo(AudioManager.AUDIOFOCUS_GAIN, speaking = true))
    }

    @Test
    fun `a real loss mid phrase drops the prompt so it is announced again`() {
        for (loss in listOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
            assertEquals(Reaction.DROP_PROMPT, GuidanceAudioFocus.reactionTo(loss, speaking = true))
        }
    }

    @Test
    fun `a loss during the release tail never repeats the finished prompt`() {
        for (loss in listOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
            assertEquals(Reaction.FORGET_FOCUS, GuidanceAudioFocus.reactionTo(loss, speaking = false))
        }
    }
}
