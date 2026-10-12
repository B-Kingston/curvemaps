package com.curvemaps.app.media

import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService

/** Disabled-by-default cold player fixture. Never shipped in release builds. */
class VisualMusicBrowserService : MediaBrowserService() {
    private lateinit var session: MediaSession
    private val handler by lazy { android.os.Handler(mainLooper) }
    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "coldMusicFixture")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = publish(PlaybackState.STATE_PLAYING)
            override fun onPlayFromSearch(query: String?, extras: Bundle?) = publish(PlaybackState.STATE_PLAYING)
            override fun onPause() = publish(PlaybackState.STATE_PAUSED)
        })
        publish(PlaybackState.STATE_STOPPED)
        sessionToken = session.sessionToken
    }
    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FOREGROUND_PLAY -> {
                // `playDelayMs` holds playback back after the screen opens, so
                // the ride's Picture-in-Picture window stays up long enough to see.
                val prefs = getSharedPreferences("visual_music", MODE_PRIVATE)
                prefs.edit().putBoolean("foregroundPlay", true).apply()
                handler.postDelayed(
                    { publish(PlaybackState.STATE_PLAYING) }, prefs.getLong("playDelayMs", 0L),
                )
            }
            ACTION_BUTTON_PLAY -> {
                getSharedPreferences("visual_music", MODE_PRIVATE).edit().putBoolean("buttonPlay", true).apply()
                publish(PlaybackState.STATE_PLAYING)
            }
        }
        return START_NOT_STICKY
    }
    private fun publish(state: Int) {
        if (state == PlaybackState.STATE_PLAYING) {
            startService(android.content.Intent(this, VisualMusicBrowserService::class.java))
        }
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Cold-start music")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Debug player").build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_FROM_SEARCH)
            .setState(state, 0, 1f).build())
        // No discoverable active session until Start actually reaches the service.
        session.isActive = state != PlaybackState.STATE_STOPPED
        getSharedPreferences("visual_music", MODE_PRIVATE).edit().putBoolean("playing", state == PlaybackState.STATE_PLAYING).apply()
    }
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? =
        if (getSharedPreferences("visual_music", MODE_PRIVATE).getBoolean("reject", false)) null
        else BrowserRoot("root", null)
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        result.sendResult(mutableListOf())
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); session.release(); super.onDestroy() }
    companion object {
        const val ACTION_FOREGROUND_PLAY = "com.curvemaps.app.DEBUG_FOREGROUND_PLAY"
        const val ACTION_BUTTON_PLAY = "com.curvemaps.app.DEBUG_BUTTON_PLAY"
    }
}
