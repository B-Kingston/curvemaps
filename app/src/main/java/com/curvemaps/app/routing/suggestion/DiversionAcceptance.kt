package com.curvemaps.app.routing.suggestion

import com.graphhopper.ResponsePath
import com.curvemaps.app.routing.navigation.GpsFix
import com.curvemaps.app.routing.navigation.PolylineMatcher
import com.curvemaps.app.routing.navigation.RouteTrack

/**
 * A pending scenic diversion: the precomputed full replacement ride (rider
 * position → diversion → destination) and what it costs and buys.
 */
data class DiversionProposal(
    val id: Int,
    val replacement: RouteTrack,
    val span: DiversionSpan,
    val measure: DiversionMeasure,
    /** The maneuver that leaves the current route, when the replacement has one there. */
    val divergenceTurn: RouteTrack.TurnNode?,
    /** Baseline offset where the replacement starts (the rider's position when the check ran). */
    val baselineOffsetAtProposalM: Double,
    /** The routed replacement, installed as-is when the rider accepts. */
    val path: ResponsePath? = null,
    /** Shaping points that pin the diversion; kept on the plan so a rebuild keeps it. */
    val viaPoints: List<Pair<Double, Double>> = emptyList(),
)

/**
 * "Ride it or tap": follows the rider on BOTH the current route and the
 * proposed replacement while a proposal is pending.
 *
 *  - Clearly on the replacement past the divergence → [Outcome.ACCEPTED].
 *  - Clearly on the current route past the divergence → [Outcome.EXPIRED].
 *  - Lost from both for a few fixes → [Outcome.EXPIRED], and normal off-route
 *    recalculation takes over.
 *
 * "Clearly" means matched and at least [DISCRIMINATION_M] closer to one
 * track than the other for [CONFIRM_FIXES] consecutive fixes, so GPS noise at
 * the fork cannot flip the decision.
 */
class DiversionAcceptance(
    baseline: RouteTrack,
    private val proposal: DiversionProposal,
    /** Baseline offset of the rider now; the replacement matcher starts at the same road position. */
    riderBaselineOffsetM: Double = proposal.baselineOffsetAtProposalM,
) {
    enum class Outcome { PENDING, ACCEPTED, EXPIRED }

    private val baselineMatcher = PolylineMatcher(baseline).apply { seek(riderBaselineOffsetM) }

    // Before the fork both tracks share the road, so the rider's distance
    // along the baseline since the check ran is their offset on the replacement.
    private val replacementMatcher = PolylineMatcher(proposal.replacement).apply {
        seek((riderBaselineOffsetM - proposal.baselineOffsetAtProposalM).coerceAtLeast(0.0))
    }
    private var replacementStreak = 0
    private var baselineStreak = 0
    private var lostStreak = 0

    /** Latest matched offset on the replacement; where guidance resumes on acceptance. */
    val replacementOffsetM: Double get() = replacementMatcher.currentOffsetM

    fun onFix(fix: GpsFix): Outcome {
        val accuracy = if (fix.hasAccuracy) fix.accuracyM.coerceIn(0.0, MAX_ACCURACY_INFLATION_M) else 0.0
        val tolerance = MATCH_TOLERANCE_M + accuracy
        val onReplacement = replacementMatcher.project(fix, WINDOW_SEGMENTS, tolerance)
        val replacementMiss = replacementMatcher.lastMissM
        val onBaseline = baselineMatcher.project(fix, WINDOW_SEGMENTS, tolerance)
        val baselineMiss = baselineMatcher.lastMissM

        if (onReplacement == null && onBaseline == null) {
            replacementStreak = 0
            baselineStreak = 0
            return if (++lostStreak >= LOST_FIXES) Outcome.EXPIRED else Outcome.PENDING
        }
        lostStreak = 0

        val pastReplacementFork = onReplacement != null &&
            onReplacement > proposal.span.divergeReplacementOffsetM + PAST_FORK_M
        val pastBaselineFork = onBaseline != null &&
            onBaseline > proposal.span.divergeBaselineOffsetM + PAST_FORK_M
        replacementStreak = if (pastReplacementFork && replacementMiss + DISCRIMINATION_M < baselineMiss) {
            replacementStreak + 1
        } else {
            0
        }
        baselineStreak = if (pastBaselineFork && baselineMiss + DISCRIMINATION_M < replacementMiss) {
            baselineStreak + 1
        } else {
            0
        }
        return when {
            replacementStreak >= CONFIRM_FIXES -> Outcome.ACCEPTED
            baselineStreak >= CONFIRM_FIXES -> Outcome.EXPIRED
            else -> Outcome.PENDING
        }
    }

    companion object {
        const val MATCH_TOLERANCE_M = 50.0
        const val MAX_ACCURACY_INFLATION_M = 30.0
        const val WINDOW_SEGMENTS = 40
        const val PAST_FORK_M = 60.0
        const val DISCRIMINATION_M = 15.0
        const val CONFIRM_FIXES = 2
        const val LOST_FIXES = 4
    }
}
