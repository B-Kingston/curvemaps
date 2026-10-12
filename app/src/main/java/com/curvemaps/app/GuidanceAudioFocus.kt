package com.curvemaps.app

import android.media.AudioManager

/**
 * How spoken guidance shares the speaker with the rider's music. Guidance asks
 * for transient may-duck focus, so the platform lowers the player rather than
 * pausing it. This policy makes that duck sound deliberate:
 *
 * - **Lead-in**: a phrase that newly takes focus waits [DUCK_LEAD_IN_MS] so the
 *   music has faded down before the first word, instead of talking over it.
 * - **Tail**: focus is held [RELEASE_TAIL_MS] after the last phrase finishes, so
 *   music swells back after the voice has decayed, and prompts that follow each
 *   other closely share one duck instead of pumping the music up and down.
 * - **Other apps**: a brief duckable sound from another app (a notification)
 *   never cuts a prompt off; only a real loss of focus (a call, another
 *   navigator, a player taking full focus) stops speech.
 */
internal object GuidanceAudioFocus {
    const val DUCK_LEAD_IN_MS = 300L
    const val RELEASE_TAIL_MS = 700L

    enum class Reaction {
        /** Keep speaking (or keep holding focus); nothing to do. */
        KEEP,

        /** Stop the prompt and let the next fix announce the maneuver again. */
        DROP_PROMPT,

        /** Focus is gone while nothing is speaking: forget it without re-announcing. */
        FORGET_FOCUS,
    }

    fun reactionTo(focusChange: Int, speaking: Boolean): Reaction = when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
        -> Reaction.KEEP
        else -> if (speaking) Reaction.DROP_PROMPT else Reaction.FORGET_FOCUS
    }

    /** Delay before a phrase is spoken, given whether guidance already held focus. */
    fun leadInMillis(focusAlreadyHeld: Boolean): Long = if (focusAlreadyHeld) 0L else DUCK_LEAD_IN_MS
}
