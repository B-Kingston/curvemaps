package com.curvemaps.app

import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

/** Short, increasing pulses followed by a firm detent click. Honors system haptic settings. */
internal class RideStyleHaptics(private val view: View) {
    @Suppress("DEPRECATION")
    private val vibrator = view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private var pulsing = false
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()

    fun approach(strength: Int) {
        if (!enabled() || strength !in 1..3) return
        if (vibrator?.hasAmplitudeControl() == true) {
            val amplitude = intArrayOf(35, 80, 145)[strength - 1]
            vibrator.vibrate(VibrationEffect.createOneShot(6L + strength * 2L, amplitude), attributes)
            pulsing = true
        } else {
            // Devices without amplitude control still feel a stronger final click.
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun click() {
        stop()
        if (!enabled()) return
        if (vibrator?.hasAmplitudeControl() == true) {
            // Peak above every approach pulse. This brief click completes naturally;
            // ending the gesture only cancels an outstanding approach pulse.
            vibrator.vibrate(VibrationEffect.createOneShot(14L, 255), attributes)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    fun stop() {
        if (pulsing) vibrator?.cancel()
        pulsing = false
    }

    @Suppress("DEPRECATION")
    private fun enabled(): Boolean = view.isHapticFeedbackEnabled &&
        Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
}
