package com.curvemaps.app.media

/** Installed music players to probe even without a declared music entry point. */
internal object KnownMusicApps {
    const val SPOTIFY = "com.spotify.music"
    val packages = listOf(
        SPOTIFY,
        "com.aspiro.tidal",
        "deezer.android.app",
        "com.apple.android.music",
        "com.google.android.apps.youtube.music",
        "com.qobuz.music",
    )
}
