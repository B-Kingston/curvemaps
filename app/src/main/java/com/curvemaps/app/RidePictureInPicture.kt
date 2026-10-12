package com.curvemaps.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer

/**
 * The ride shrinks to a Picture-in-Picture (PiP) window when CurveMaps itself
 * opens a music player. A PiP window is a small window that Android keeps on
 * top of other apps. The rider keeps the next turn in view and goes back to
 * the full ride from the window itself (tap it, then its full-screen button),
 * not through the system navigation. The ride stays visible, so the location
 * stream and voice guidance keep running at full rate.
 *
 * PiP does not let the ride bring itself back: Android 17 blocks an activity
 * start from an app whose only visible activity is pinned
 * (`callingUidHasVisibleNotPinnedActivity: false` → `BAL_BLOCK`).
 */
internal object RidePictureInPicture {
    /** Portrait window: the turn card above a strip of map. */
    private val aspectRatio = Rational(3, 4)

    /**
     * Shrinks [activity] to the PiP window. False when the device has no PiP,
     * the rider turned it off for CurveMaps, or the activity is not in front;
     * the player then opens over the ride as before.
     */
    fun enter(activity: Activity): Boolean {
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        if (activity.isInPictureInPictureMode) return true
        return runCatching {
            activity.enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build(),
            )
        }.getOrDefault(false)
    }
}

/** Whether [activity] is in the PiP window, updated on every mode change. */
@Composable
internal fun rememberInPictureInPicture(activity: Activity?): Boolean {
    var inPictureInPicture by remember(activity) { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val provider = activity as? ComponentActivity
        val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
            inPictureInPicture = info.isInPictureInPictureMode
        }
        provider?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { provider?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPictureInPicture
}
