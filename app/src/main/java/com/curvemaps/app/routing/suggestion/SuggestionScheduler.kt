package com.curvemaps.app.routing.suggestion

import com.curvemaps.app.routing.RideStyle
import com.curvemaps.app.routing.navigation.NavigationState

/** Battery facts the scheduler needs, read by the Android layer. */
data class BatteryState(
    val powerSaveMode: Boolean = false,
    /** 0..100, or -1 when unknown (treated as healthy). */
    val levelPercent: Int = -1,
    val charging: Boolean = false,
)

/**
 * Decides WHEN a ride-suggestion check may run. Pure and clock-injected.
 *
 * The cadence is the battery/discovery middle ground: one bounded routing
 * check every ten minutes and ten kilometres of real riding (about six an
 * hour at highway speed), never in town traffic,
 * never near the end of a ride, paused in battery saver or on a low battery,
 * and quiet for a while after the rider turns a suggestion down.
 */
class SuggestionScheduler(private val config: Config = Config()) {
    data class Config(
        /** Checks only run while moving at least this fast (25 km/h). */
        val minSpeedMps: Double = 25.0 / 3.6,
        /** Moving time only accrues above this speed. */
        val movingSpeedMps: Double = 2.0,
        /** No checks once less than this much route remains. */
        val minRemainingM: Double = 15_000.0,
        /** First check after this much riding. */
        val firstCheckAfterM: Double = 5_000.0,
        /** Later checks need this much riding since the last check... */
        val intervalM: Double = 10_000.0,
        /** ...and this much moving time since the last check. */
        val intervalMovingS: Double = 10 * 60.0,
        /** After a declined suggestion, wait this much wall time... */
        val cooldownS: Double = 20 * 60.0,
        /** ...and this much riding. */
        val cooldownM: Double = 15_000.0,
        /** At most this many proposals in any rolling hour. */
        val maxProposalsPerHour: Int = 3,
        /** Below this battery level (and not charging) checks pause. */
        val lowBatteryPercent: Int = 20,
    )

    private var riddenM = 0.0
    private var movingS = 0.0
    private var lastFixMs = Long.MIN_VALUE
    private var lastCheckRiddenM = Double.NaN
    private var lastCheckMovingS = 0.0
    private var declinedAtMs = Long.MIN_VALUE
    private var declinedAtRiddenM = 0.0
    private val proposalTimesMs = ArrayDeque<Long>()

    /** Ride distance accounted so far, metres. */
    val riddenMeters: Double get() = riddenM

    /** Starts a new ride: everything resets, including the cooldown. */
    fun reset() {
        riddenM = 0.0
        movingS = 0.0
        lastFixMs = Long.MIN_VALUE
        lastCheckRiddenM = Double.NaN
        lastCheckMovingS = 0.0
        declinedAtMs = Long.MIN_VALUE
        declinedAtRiddenM = 0.0
        proposalTimesMs.clear()
    }

    /** Accounts one fix: [movedM] is the distance since the previous fix. */
    fun onFix(nowMs: Long, speedMps: Double, movedM: Double) {
        if (movedM.isFinite() && movedM > 0.0 && movedM < MAX_STEP_M) riddenM += movedM
        val previous = lastFixMs
        lastFixMs = nowMs
        if (previous == Long.MIN_VALUE) return
        val dtS = (nowMs - previous) / 1000.0
        if (dtS > 0.0 && dtS <= MAX_GAP_S && speedMps >= config.movingSpeedMps) movingS += dtS
    }

    /** True when a check is due now. Reasons for "no" are deliberately not surfaced. */
    fun shouldEvaluate(
        nowMs: Long,
        enabled: Boolean,
        state: NavigationState,
        style: RideStyle,
        speedMps: Double,
        remainingM: Double,
        battery: BatteryState,
        busy: Boolean,
    ): Boolean {
        if (!enabled || busy) return false
        if (state != NavigationState.OnRoute) return false
        if (style == RideStyle.FASTEST) return false
        if (!speedMps.isFinite() || speedMps < config.minSpeedMps) return false
        if (remainingM < config.minRemainingM) return false
        if (!batteryAllows(battery)) return false
        if (inCooldown(nowMs)) return false
        if (hourlyCapReached(nowMs)) return false
        val lastRidden = lastCheckRiddenM
        return if (lastRidden.isNaN()) {
            riddenM >= config.firstCheckAfterM
        } else {
            riddenM - lastRidden >= config.intervalM &&
                movingS - lastCheckMovingS >= config.intervalMovingS
        }
    }

    /** Records that a check ran (whether or not it found anything). */
    fun onEvaluated() {
        lastCheckRiddenM = riddenM
        lastCheckMovingS = movingS
    }

    fun onProposed(nowMs: Long) {
        proposalTimesMs.addLast(nowMs)
    }

    /** A skipped or expired proposal starts the cooldown. */
    fun onDeclined(nowMs: Long) {
        declinedAtMs = nowMs
        declinedAtRiddenM = riddenM
    }

    fun batteryAllows(battery: BatteryState): Boolean {
        if (battery.powerSaveMode) return false
        if (battery.charging) return true
        return battery.levelPercent < 0 || battery.levelPercent >= config.lowBatteryPercent
    }

    private fun inCooldown(nowMs: Long): Boolean {
        if (declinedAtMs == Long.MIN_VALUE) return false
        val waitedS = (nowMs - declinedAtMs) / 1000.0
        return waitedS < config.cooldownS || riddenM - declinedAtRiddenM < config.cooldownM
    }

    private fun hourlyCapReached(nowMs: Long): Boolean {
        while (proposalTimesMs.isNotEmpty() && nowMs - proposalTimesMs.first() >= HOUR_MS) {
            proposalTimesMs.removeFirst()
        }
        return proposalTimesMs.size >= config.maxProposalsPerHour
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        /** Fix gaps longer than this do not count as moving time. */
        const val MAX_GAP_S = 30.0
        /** A single fix-to-fix jump longer than this is a GPS glitch. */
        const val MAX_STEP_M = 2_000.0
    }
}

/** Picks the stretch of the remaining ride a check compares against. */
object SuggestionWindow {
    const val MIN_LEAD_M = 1_000.0
    const val MAX_LEAD_M = 2_000.0
    const val LEAD_SECONDS = 60.0
    const val TYPICAL_LENGTH_M = 25_000.0
    const val MAX_LENGTH_M = 35_000.0
    const val MIN_LENGTH_M = 5_000.0

    /**
     * Start (P) and end (R) offsets on the current track, or null when too
     * little route is left. P sits a minute ahead (1–2 km); R is the
     * destination when it is within [MAX_LENGTH_M] of P, otherwise 25 km on.
     */
    fun choose(totalDistanceM: Double, offsetM: Double, speedMps: Double): Pair<Double, Double>? {
        val lead = (speedMps.takeIf { it.isFinite() && it > 0.0 } ?: 0.0) * LEAD_SECONDS
        val start = offsetM + lead.coerceIn(MIN_LEAD_M, MAX_LEAD_M)
        val left = totalDistanceM - start
        if (left < MIN_LENGTH_M) return null
        val end = if (left <= MAX_LENGTH_M) totalDistanceM else start + TYPICAL_LENGTH_M
        return start to end
    }

    /**
     * Whether a divergence [distanceAheadM] from the rider leaves time to
     * decide (at least 800 m or 45 s) without being so far ahead that the
     * prompt is meaningless (the next check will catch it instead).
     */
    fun divergenceLeadOk(distanceAheadM: Double, speedMps: Double): Boolean {
        val speed = speedMps.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val minimum = maxOf(MIN_DECISION_M, speed * MIN_DECISION_S)
        return distanceAheadM in minimum..MAX_DIVERGENCE_AHEAD_M
    }

    const val MIN_DECISION_M = 800.0
    const val MIN_DECISION_S = 45.0
    /** Matches the check interval so consecutive checks cover the road ahead. */
    const val MAX_DIVERGENCE_AHEAD_M = 10_000.0
}
