package com.curvemaps.app.media

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/**
 * Disabled-by-default foreground "play any music" screen for the cold player
 * fixture, like Spotify's play-from-search entry point. It runs in its own
 * process and task so the ride really leaves the front. Never shipped in release.
 */
class VisualMusicPlayerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Debug music player" })
        startService(Intent(this, VisualMusicBrowserService::class.java)
            .setAction(VisualMusicBrowserService.ACTION_FOREGROUND_PLAY))
    }
}
