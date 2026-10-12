package com.curvemaps.app.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * Debug-build-only synthetic media session for the ADB visual runner.
 *
 * It lives in the `debug` source set, so it is never part of a release APK. It
 * exists only so `tools/test/visual.py media-controls` can screenshot the
 * playing/paused/no-player states on an emulator that has no real media app.
 * Production builds create no synthetic session.
 */
class VisualMediaSessionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val state = intent.getStringExtra("state") ?: return
        currentTitle = intent.getStringExtra("title") ?: currentTitle
        currentArtist = intent.getStringExtra("artist") ?: currentArtist
        when (state) {
            "cold-button" -> {
                // A stopped player that refuses the background browser but
                // starts from one PLAY press on its media button: the ride
                // stays in front.
                val prefs = context.getSharedPreferences("visual_music", Context.MODE_PRIVATE)
                context.stopService(Intent(context, VisualMusicBrowserService::class.java))
                setButtonReceiver(context, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                prefs.edit().putBoolean("reject", true).putBoolean("buttonPlay", false).commit()
            }
            "cold-refuse" -> {
                // The Spotify shape: the background browser refuses this app
                // and the media button does nothing, so Start opens the
                // player's own play screen and the ride comes back once
                // playback is observed.
                val prefs = context.getSharedPreferences("visual_music", Context.MODE_PRIVATE)
                val screen = android.content.ComponentName(context, VisualMusicPlayerActivity::class.java)
                val pm = context.packageManager
                context.stopService(Intent(context, VisualMusicBrowserService::class.java))
                if (!prefs.contains("player_enabled_before")) {
                    prefs.edit().putInt("player_enabled_before", pm.getComponentEnabledSetting(screen)).commit()
                }
                pm.setComponentEnabledSetting(screen,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP)
                setButtonReceiver(context, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                // Playback starts `playDelayMs` (default 5 s) after the screen
                // opens, so the ride's Picture-in-Picture window can settle
                // and be seen before the ride returns.
                prefs.edit().putBoolean("reject", true).putBoolean("foregroundPlay", false)
                    .putLong("playDelayMs", intent.getLongExtra("playDelayMs", 5_000L)).commit()
            }
            "cold-enable", "cold-disable" -> {
                val component = android.content.ComponentName(context, VisualMusicBrowserService::class.java)
                val pm = context.packageManager
                val prefs = context.getSharedPreferences("visual_music", Context.MODE_PRIVATE)
                if (state == "cold-enable") {
                    if (!prefs.contains("enabled_before")) {
                        prefs.edit().putInt("enabled_before", pm.getComponentEnabledSetting(component)).commit()
                    }
                    pm.setComponentEnabledSetting(component,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP)
                } else {
                    context.stopService(Intent().setComponent(component))
                    pm.setComponentEnabledSetting(
                        android.content.ComponentName(context, VisualMusicPlayerActivity::class.java),
                        prefs.getInt("player_enabled_before", android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
                        android.content.pm.PackageManager.DONT_KILL_APP)
                    pm.setComponentEnabledSetting(
                        android.content.ComponentName(context, VisualMusicButtonReceiver::class.java),
                        prefs.getInt("button_enabled_before", android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
                        android.content.pm.PackageManager.DONT_KILL_APP)
                    pm.setComponentEnabledSetting(component,
                        prefs.getInt("enabled_before", android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
                        android.content.pm.PackageManager.DONT_KILL_APP)
                    prefs.edit().clear().commit()
                }
            }
            "playing" -> ensureSession(context).also { publish(it, PlaybackState.STATE_PLAYING) }
            "paused" -> ensureSession(context).also { publish(it, PlaybackState.STATE_PAUSED) }
            "stop" -> stopSession()
        }
    }

    /** Sets the fixture's media-button receiver, recording its first state for `cold-disable`. */
    private fun setButtonReceiver(context: Context, state: Int) {
        val receiver = android.content.ComponentName(context, VisualMusicButtonReceiver::class.java)
        val pm = context.packageManager
        val prefs = context.getSharedPreferences("visual_music", Context.MODE_PRIVATE)
        if (!prefs.contains("button_enabled_before")) {
            prefs.edit().putInt("button_enabled_before", pm.getComponentEnabledSetting(receiver)).commit()
        }
        pm.setComponentEnabledSetting(receiver, state, android.content.pm.PackageManager.DONT_KILL_APP)
    }

    private fun ensureSession(context: Context): MediaSession {
        session?.let { return it }
        val created = MediaSession(context.applicationContext, "CurveMapsDebugMedia")
        created.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                publish(created, PlaybackState.STATE_PLAYING)
            }

            override fun onPause() {
                publish(created, PlaybackState.STATE_PAUSED)
            }
        })
        session = created
        return created
    }

    private fun stopSession() {
        session?.let { active ->
            active.isActive = false
            active.release()
        }
        session = null
    }

    companion object {
        const val ACTION = "com.curvemaps.app.DEBUG_VISUAL_MEDIA_SESSION"

        private var session: MediaSession? = null
        private var currentTitle: String = "Debug Track"
        private var currentArtist: String = "Debug Artist"

        private fun publish(session: MediaSession, state: Int) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "Debug")
                    .build(),
            )
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                    )
                    .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build(),
            )
            session.isActive = true
        }
    }
}
