package com.curvemaps.app.routing.suggestion

import com.curvemaps.app.routing.curvyDistanceMeters
import com.curvemaps.app.routing.navigation.RouteTrack
import kotlin.math.max

/**
 * What one diversion costs and buys, compared with the same stretch of the
 * ride the rider is on. Every figure is computed from the two tracks, so the
 * numbers the rider hears are the numbers the planner measured.
 */
data class DiversionMeasure(
    /** Replacement time minus the baseline time it replaces; may be negative. */
    val extraTimeS: Double,
    /** Replacement distance minus the baseline distance it replaces. */
    val extraDistanceM: Double,
    /** Model time of the baseline stretch the diversion replaces. */
    val baselineSectionTimeS: Double,
    /** Length of the baseline stretch the diversion replaces. */
    val baselineSectionDistanceM: Double,
    /** Length of the diverging stretch itself. */
    val sectionDistanceM: Double,
    /** Curvy metres on the diverging stretch minus those on the replaced one. */
    val curvyGainM: Double,
    /** Curvy share of the diverging stretch minus that of the replaced one (0..1 scale). */
    val curvyShareGain: Double,
) {
    companion object {
        /**
         * Measures [replacement] (which starts at baseline offset
         * [baselineFromOffsetM] and ends at [baselineToOffsetM]) against the
         * baseline, using [span] for the diverging stretch.
         */
        fun of(
            baseline: RouteTrack,
            baselineFromOffsetM: Double,
            baselineToOffsetM: Double,
            replacement: RouteTrack,
            span: DiversionSpan,
        ): DiversionMeasure {
            val baselineTime = timeAt(baseline, baselineToOffsetM) - timeAt(baseline, baselineFromOffsetM)
            val baselineDistance = baselineToOffsetM - baselineFromOffsetM
            val baselineFirst = baseline.locate(span.divergeBaselineOffsetM).first
            val baselineLast = (baseline.locate(span.rejoinBaselineOffsetM).first + 1)
                .coerceAtMost(baseline.vertexCount - 1)
            val baselineCurvy = curvyDistanceMeters(
                baseline.latitudes, baseline.longitudes, baselineFirst, baselineLast,
            )
            val sectionCurvy = curvyDistanceMeters(
                replacement.latitudes, replacement.longitudes, span.divergeVertex, span.rejoinVertex,
            )
            val sectionDistance = span.distinctM
            val replacedDistance = span.replacedBaselineM
            return DiversionMeasure(
                extraTimeS = replacement.totalTimeS - baselineTime,
                extraDistanceM = replacement.totalDistanceM - baselineDistance,
                baselineSectionTimeS = timeAt(baseline, span.rejoinBaselineOffsetM) -
                    timeAt(baseline, span.divergeBaselineOffsetM),
                baselineSectionDistanceM = replacedDistance,
                sectionDistanceM = sectionDistance,
                curvyGainM = sectionCurvy - baselineCurvy,
                curvyShareGain = share(sectionCurvy, sectionDistance) - share(baselineCurvy, replacedDistance),
            )
        }

        private fun timeAt(track: RouteTrack, offsetM: Double): Double =
            track.totalTimeS - track.remainingTimeS(offsetM)

        private fun share(curvy: Double, length: Double): Double =
            if (length <= 0.0) 0.0 else (curvy / length).coerceIn(0.0, 1.0)
    }
}

/**
 * The "middle ground" between discovery and nagging. One place, so the voice
 * prompt, the HUD, the tests and AGENTS.md all describe the same rules.
 */
data class DiversionThresholds(
    /** A diversion must add at least this many curvy metres. */
    val minCurvyGainM: Double = 3_000.0,
    /** ...and raise the curvy share of the stretch by this much. */
    val minCurvyShareGain: Double = 0.10,
    /** Absolute cap on the time it may add. */
    val maxExtraTimeS: Double = 12 * 60.0,
    /**
     * Cap on the time it may add, as a share of the replaced stretch's time.
     * Curvy roads are slower by nature (a 60 km/h ridge road against a
     * 100 km/h highway is already +67% on equal length), so a tighter cap
     * would reject nearly every real twisty road; the absolute cap and the
     * value-for-time rate keep it from going over the top.
     */
    val maxExtraTimeFraction: Double = 0.5,
    /** The diverging stretch may be at most this much longer than the replaced one. */
    val maxSectionDistanceRatio: Double = 1.4,
    /** Value for time: curvy metres gained per extra minute (extra time floors at one minute). */
    val minCurvyMetresPerExtraMinute: Double = 400.0,
    /** The diverging stretch must be one continuous run at least this long. */
    val minDistinctM: Double = 3_000.0,
)

/** Pure decision: does a measured diversion earn a spoken suggestion? */
object DiversionScorer {
    /** Why a diversion was rejected, or null when it qualifies. */
    fun rejection(measure: DiversionMeasure, thresholds: DiversionThresholds = DiversionThresholds()): String? {
        val timeBudget = minOf(
            thresholds.maxExtraTimeS,
            thresholds.maxExtraTimeFraction * measure.baselineSectionTimeS,
        )
        return when {
            measure.sectionDistanceM < thresholds.minDistinctM -> "distinct stretch too short"
            measure.curvyGainM < thresholds.minCurvyGainM -> "too few extra curves"
            measure.curvyShareGain < thresholds.minCurvyShareGain -> "curvy share barely changes"
            measure.extraTimeS > timeBudget -> "adds too much time"
            measure.baselineSectionDistanceM <= 0.0 ||
                measure.sectionDistanceM > thresholds.maxSectionDistanceRatio * measure.baselineSectionDistanceM ->
                "detour too long"
            score(measure) < thresholds.minCurvyMetresPerExtraMinute -> "too slow for the curves gained"
            else -> null
        }
    }

    fun qualifies(measure: DiversionMeasure, thresholds: DiversionThresholds = DiversionThresholds()): Boolean =
        rejection(measure, thresholds) == null

    /** Curvy metres gained per extra minute; never rewards adding time. */
    fun score(measure: DiversionMeasure): Double =
        measure.curvyGainM / max(1.0, measure.extraTimeS / 60.0)

    /** The best qualifying candidate, or null. Ties keep the first (deterministic). */
    fun <T> best(
        candidates: List<Pair<T, DiversionMeasure>>,
        thresholds: DiversionThresholds = DiversionThresholds(),
    ): Pair<T, DiversionMeasure>? {
        var best: Pair<T, DiversionMeasure>? = null
        for (candidate in candidates) {
            if (!qualifies(candidate.second, thresholds)) continue
            val current = best
            if (current == null || score(candidate.second) > score(current.second)) best = candidate
        }
        return best
    }
}
