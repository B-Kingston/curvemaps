package com.curvemaps.app.media

/**
 * Resumes a player that paused for a spoken guidance prompt and did not start
 * again by itself.
 *
 * Guidance asks for duckable focus, but some players pause on any transient
 * focus loss and a few never resume. This watch is strictly scoped to our own
 * duck: it remembers which sessions were playing the moment guidance asked for
 * focus, and [RESUME_GRACE_MS] after guidance released it, sends one PLAY to
 * each of those that is now paused. It never touches a player that was not
 * playing before the prompt, and it gives up entirely when the duck ended for
 * another reason (a call or another app taking real focus — [cancel]) or the
 * rider used a transport control meanwhile. A pause observed later than
 * [FOCUS_PAUSE_WINDOW_MS] after the prompt asked for focus is the rider's own
 * (a headset or handlebar button, notification controls) and drops that
 * player too. Without notification access no session is observable, so
 * nothing is ever resumed blindly.
 *
 * Pure and clock-injected so the JVM suite can pin every case.
 */
class GuidanceResumeWatch(private val graceMillis: Long = RESUME_GRACE_MS) {
    private var playingAtDuck: Set<String> = emptySet()
    private var ducking = false
    private var deadline: Long? = null
    private var duckStartedAt = 0L

    /** Players observed pausing in answer to our focus request. */
    private val pausedByDuck = mutableSetOf<String>()

    /** Players observed playing again after [pausedByDuck]; a later pause is the rider's. */
    private val restarted = mutableSetOf<String>()

    /** True from a duck's start until it is resumed or cancelled. */
    val isWatching: Boolean get() = ducking || deadline != null

    /** Guidance is about to take focus at [nowMs]; [sessions] is read before the duck. */
    fun onDuckStarted(sessions: List<MediaSessionInfo>, nowMs: Long) {
        val playing = sessions.filter { it.isPlaying && it.playbackKnown }.map { it.key }.toSet()
        // A new prompt during the grace window continues the same duck: keep
        // the sessions that were playing before the first one, too.
        playingAtDuck = if (ducking || deadline != null) playingAtDuck + playing else playing
        ducking = true
        deadline = null
        duckStartedAt = nowMs
    }

    /**
     * A session snapshot observed at [nowMs] while watching. A player that
     * pauses right after the focus request paused for us; one that pauses
     * later (or pauses again after restarting) paused for the rider and is
     * never resumed for this duck. Unobserved pauses keep the old behaviour.
     */
    fun onSessionsObserved(sessions: List<MediaSessionInfo>, nowMs: Long) {
        if (!isWatching) return
        sessions.filter { it.key in playingAtDuck && it.playbackKnown }.forEach { session ->
            when {
                session.isPlaying -> if (pausedByDuck.remove(session.key)) restarted += session.key
                session.key in pausedByDuck -> Unit
                session.key !in restarted && ducking && nowMs - duckStartedAt <= FOCUS_PAUSE_WINDOW_MS -> pausedByDuck += session.key
                else -> playingAtDuck = playingAtDuck - session.key
            }
        }
    }

    /** Guidance released focus normally at [nowMs]. */
    fun onDuckEnded(nowMs: Long) {
        if (!ducking) return
        ducking = false
        deadline = if (playingAtDuck.isEmpty()) null else nowMs + graceMillis
    }

    /** The pause is not ours to undo (real focus loss, or the rider took over). */
    fun cancel() {
        playingAtDuck = emptySet()
        pausedByDuck.clear()
        restarted.clear()
        ducking = false
        deadline = null
    }

    fun isDue(nowMs: Long): Boolean = deadline?.let { nowMs >= it } == true

    /**
     * Once due, returns the keys to send exactly one PLAY to and forgets the
     * duck, so a player is never resumed twice for one prompt.
     */
    fun takeResumable(sessions: List<MediaSessionInfo>, nowMs: Long): List<String> {
        if (!isDue(nowMs)) return emptyList()
        val keys = sessions
            .filter { it.key in playingAtDuck && it.playbackKnown && !it.isPlaying && it.supports(MediaCommand.PLAY) }
            .map { it.key }
        cancel()
        return keys
    }

    companion object {
        /** Players that resume on focus gain do so well within this. */
        const val RESUME_GRACE_MS = 2_500L

        /** Players that pause on a transient focus loss do so well within this. */
        const val FOCUS_PAUSE_WINDOW_MS = 1_500L
    }
}
