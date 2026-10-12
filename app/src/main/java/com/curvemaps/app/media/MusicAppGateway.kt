package com.curvemaps.app.media

/** An installed player, independent of whether it currently has an active session. */
data class MusicApp(val packageName: String, val name: String)

enum class MusicStartResult { REQUESTED, OPENED, UNAVAILABLE }

/** Cold-start playback. REQUESTED means a command was sent, never proof of audio. */
interface MusicAppGateway {
    fun apps(): List<MusicApp>
    fun start(packageName: String, onResult: (MusicStartResult) -> Unit)
    fun open(packageName: String): Boolean
    fun cancel()
}

/** Retain an explicit choice across observations, falling back after uninstall. */
internal fun selectMusicApp(
    apps: List<MusicApp>,
    chosenPackage: String?,
    activePackage: String?,
): MusicApp? = apps.firstOrNull { it.packageName == chosenPackage }
    ?: apps.firstOrNull { it.packageName == activePackage }
    ?: apps.firstOrNull()

/** Bounded wait for a foreground-started player to begin playing (below the panel timeout). */
internal const val ReturnToRideWindowMillis = 12_000L

/**
 * After a player's own screen was opened to start music, decides when to bring
 * the ride back to the front. It returns only once the rider has actually left
 * for the player and that player is observed playing; it gives up when the
 * rider comes back by themselves, when sessions cannot be observed (no
 * notification access — never guess from a timer), or after the window.
 */
internal class ReturnToRideWatch(
    private val startedAtMillis: Long,
    private val windowMillis: Long = ReturnToRideWindowMillis,
) {
    enum class Step { WAIT, RETURN, STOP }

    private var leftRide = false

    /** [playing] is null when the player's sessions cannot be observed. */
    fun step(nowMillis: Long, rideOnTop: Boolean, playing: Boolean?): Step {
        if (!rideOnTop) leftRide = true
        return when {
            playing == null -> Step.STOP
            leftRide && rideOnTop -> Step.STOP
            leftRide && playing -> Step.RETURN
            nowMillis - startedAtMillis >= windowMillis -> Step.STOP
            else -> Step.WAIT
        }
    }
}

/** Longest wait for a player's media browser to answer before Start tries the next entry point. */
internal const val MusicBrowserTimeoutMillis = 5_000L

/**
 * Bounded wait for a stopped player to start after one PLAY press on its media
 * button. With the browser wait it stays inside the return window, so a
 * fallback to the player's own screen still has time to bring the ride back.
 */
internal const val MediaButtonConfirmMillis = 3_000L

internal enum class MediaButtonStep { WAIT, PLAYING, FALL_BACK }

/**
 * After one PLAY press went to a stopped player's media-button receiver,
 * decides whether the press worked. Only observed playback counts. A player
 * that stays silent for [windowMillis], or sessions that become unobservable,
 * fall back to the player's own screen.
 */
internal fun mediaButtonStep(
    elapsedMillis: Long,
    playing: Boolean?,
    windowMillis: Long = MediaButtonConfirmMillis,
): MediaButtonStep = when {
    playing == true -> MediaButtonStep.PLAYING
    playing == null -> MediaButtonStep.FALL_BACK
    elapsedMillis >= windowMillis -> MediaButtonStep.FALL_BACK
    else -> MediaButtonStep.WAIT
}
