package com.curvemaps.app.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent

/**
 * Disabled-by-default media-button receiver for the cold player fixture, like
 * the receiver a stopped Spotify declares. A PLAY press starts the fixture's
 * service and records `buttonPlay`; the `ignoreButton` preference makes it a
 * player that stays silent. `buttonPresses` counts every PLAY press it gets.
 * Never shipped in release builds.
 */
class VisualMusicButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
        if (event?.action != KeyEvent.ACTION_DOWN || event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY) return
        val prefs = context.getSharedPreferences("visual_music", Context.MODE_PRIVATE)
        prefs.edit().putInt("buttonPresses", prefs.getInt("buttonPresses", 0) + 1).commit()
        if (prefs.getBoolean("ignoreButton", false)) return
        context.startService(
            Intent(context, VisualMusicBrowserService::class.java)
                .setAction(VisualMusicBrowserService.ACTION_BUTTON_PLAY),
        )
    }
}
