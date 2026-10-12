package com.curvemaps.app.routing.suggestion

import com.graphhopper.ResponsePath
import com.curvemaps.app.routing.navigation.RouteTrack

/**
 * The pure steps of one ride-suggestion check, between the two routing calls
 * the Android layer makes:
 *
 *  1. route the window (P → R) one style twistier; [bestWindowCard] scores
 *     every card against the same stretch of the ride and keeps the best;
 *  2. [shapingPoints] pins that card's diverging stretch as via points;
 *  3. route rider → destination through them at the ride's own style;
 *     [proposal] re-measures that full replacement against the whole
 *     remaining ride, so the quoted extra time/distance are the real ones.
 */
object DiversionPlanner {
    data class WindowPick(
        val cardIndex: Int,
        val card: RouteTrack,
        val span: DiversionSpan,
        val measure: DiversionMeasure,
    )

    fun bestWindowCard(
        baseline: RouteTrack,
        windowStartM: Double,
        windowEndM: Double,
        cards: List<RouteTrack>,
        thresholds: DiversionThresholds = DiversionThresholds(),
    ): WindowPick? {
        val measured = cards.mapIndexedNotNull { index, card ->
            val span = DiversionGeometry.span(baseline, windowStartM, card) ?: return@mapIndexedNotNull null
            WindowPick(index, card, span, DiversionMeasure.of(baseline, windowStartM, windowEndM, card, span))
        }
        return DiversionScorer.best(measured.map { it to it.measure }, thresholds)?.first
    }

    /**
     * Up to [MAX_SHAPING_POINTS] vertices spread evenly through the interior
     * of the diverging stretch (at least two). Interior vertices lie on the
     * scenic road itself, so a router snapping them cannot pick a parallel road
     * at the fork.
     */
    fun shapingPoints(card: RouteTrack, span: DiversionSpan): List<Pair<Double, Double>> {
        val count = (span.distinctM / SHAPING_SPACING_M).toInt().coerceIn(MIN_SHAPING_POINTS, MAX_SHAPING_POINTS)
        val step = span.distinctM / (count + 1)
        return (1..count).map { k ->
            card.positionAt(span.divergeReplacementOffsetM + k * step)
        }
    }

    /**
     * The proposal for [replacement] (which starts at baseline offset
     * [riderOffsetM]), or null when the full ride no longer qualifies or the
     * fork is not a sensible distance ahead.
     */
    fun proposal(
        id: Int,
        baseline: RouteTrack,
        riderOffsetM: Double,
        replacement: RouteTrack,
        speedMps: Double,
        path: ResponsePath? = null,
        viaPoints: List<Pair<Double, Double>> = emptyList(),
        thresholds: DiversionThresholds = DiversionThresholds(),
    ): DiversionProposal? {
        val span = DiversionGeometry.span(baseline, riderOffsetM, replacement) ?: return null
        val measure = DiversionMeasure.of(baseline, riderOffsetM, baseline.totalDistanceM, replacement, span)
        if (!DiversionScorer.qualifies(measure, thresholds)) return null
        if (!SuggestionWindow.divergenceLeadOk(span.divergeReplacementOffsetM, speedMps)) return null
        return DiversionProposal(
            id = id,
            replacement = replacement,
            span = span,
            measure = measure,
            divergenceTurn = divergenceTurn(replacement, span),
            baselineOffsetAtProposalM = riderOffsetM,
            path = path,
            viaPoints = viaPoints,
        )
    }

    /** The replacement's maneuver at the fork, if one lies within [TURN_SEARCH_M] of it. */
    fun divergenceTurn(replacement: RouteTrack, span: DiversionSpan): RouteTrack.TurnNode? =
        replacement.turns
            .map { it to kotlin.math.abs(replacement.cumulativeDistanceM[it.vertexIndex] - span.divergeReplacementOffsetM) }
            .filter { it.second <= TURN_SEARCH_M }
            .minByOrNull { it.second }
            ?.first

    const val SHAPING_SPACING_M = 2_000.0
    const val MIN_SHAPING_POINTS = 2
    const val MAX_SHAPING_POINTS = 8
    const val TURN_SEARCH_M = 150.0
}
