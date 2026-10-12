package com.curvemaps.app.routing

import com.graphhopper.ResponsePath
import com.graphhopper.util.DistanceCalcEarth
import kotlin.math.min

/** Pure GraphHopper route-diversity policy used by the Android router. */
internal object AlternativePolicy {
    data class EdgeSection(
        val edgeId: Int,
        val forward: Boolean,
        val distance: Double,
    )

    data class EdgeTraversal(
        val edgeId: Int,
        val forward: Boolean,
    )

    data class CachedRoute(
        val path: ResponsePath,
        val edgeDistances: Map<Int, Double>,
        val edgeSections: List<EdgeSection>,
    )

    data class RouteDiversity(
        val maxOverlap: Double,
        val longestDistinctStretch: Double,
    )

    /**
     * Maximum shared-road fraction between any two carousel cards. Fixed:
     * diversity is a property of the carousel, not a user setting.
     */
    const val SIBLING_MAX_SHARE = 0.60

    internal const val MAX_CANDIDATE_SHARE = 0.98
    internal const val CANDIDATE_SHARE_STEP = 0.20
    internal const val MAX_ALTERNATIVE_WEIGHT = 4.00
    internal const val BASE_ALTERNATIVE_WEIGHT = 2.00
    internal const val CANDIDATE_WEIGHT_STEP = 0.75
    internal const val MIN_PLATEAU_FACTOR = 0.10
    internal const val MAX_EXPLORATION_FACTOR = 1.5

    private const val BASE_SIBLING_ROAD_PENALTY = 2.0
    private const val SIBLING_ROAD_PENALTY_STEP = 6.0

    private const val MIN_MEANINGFUL_STRETCH = 750.0
    private const val MAX_MEANINGFUL_STRETCH = 3_000.0
    private const val MEANINGFUL_STRETCH_FRACTION = 0.05

    private const val MAX_LOCAL_DETOUR_RATIO = 2.25
    private const val MIN_LOCAL_EXTRA_DISTANCE = 1_500.0
    private const val MIN_LOCAL_BASELINE_DISTANCE = 250.0
    private const val MAX_LOCAL_BASELINE_DISTANCE = 15_000.0
    internal const val LOCAL_DETOUR_IS_THE_RIDE_SHARE = 0.5

    /**
     * The detour budget's percentages apply to at least this much ride. On a
     * short hop a pure ratio leaves no room for any other road: Skyline
     * Circuit (Palmwoods) to Palmwoods is 2.1 km / 3.1 min, so Mostly fast's
     * 50% allowed 93 s and the first different ride (5.7 min, 45% curvy) was
     * rejected, which failed every stop above Fastest. With a six-minute /
     * five-kilometre reference the same stop may add 3 min and 3.25 km, and
     * Max twisty 6 min and 5.5 km; rides longer than the reference are
     * judged exactly as before.
     */
    internal const val BUDGET_REFERENCE_TIME_MS = 6L * 60_000L
    internal const val BUDGET_REFERENCE_DISTANCE_M = 5_000.0

    fun routeDiversity(
        candidate: CachedRoute,
        previous: List<CachedRoute>,
    ): RouteDiversity {
        if (previous.isEmpty()) return RouteDiversity(0.0, candidate.path.distance)
        val closest = previous.maxByOrNull {
            sharedRoadFraction(candidate.edgeDistances, it.edgeDistances)
        } ?: return RouteDiversity(0.0, candidate.path.distance)
        return RouteDiversity(
            sharedRoadFraction(candidate.edgeDistances, closest.edgeDistances),
            longestDistinctStretch(candidate.edgeSections, closest.edgeDistances.keys),
        )
    }

    fun isMeaningfullyDifferent(
        candidate: CachedRoute,
        diversity: RouteDiversity,
        previous: List<CachedRoute>,
    ): Boolean {
        if (previous.isEmpty()) return true
        return diversity.longestDistinctStretch >= requiredDistinctStretch(candidate.path.distance)
    }

    fun requiredDistinctStretch(distance: Double): Double =
        (distance * MEANINGFUL_STRETCH_FRACTION).coerceIn(MIN_MEANINGFUL_STRETCH, MAX_MEANINGFUL_STRETCH)

    fun longestDistinctStretch(
        candidate: List<EdgeSection>,
        comparisonEdgeIds: Set<Int>,
    ): Double {
        var longest = 0.0
        var current = 0.0
        candidate.forEach { section ->
            if (section.edgeId !in comparisonEdgeIds) {
                current += section.distance
                longest = maxOf(longest, current)
            } else {
                current = 0.0
            }
        }
        return longest
    }

    fun cachedRoute(path: ResponsePath): CachedRoute {
        val sections = edgeSections(path)
        val distances = mutableMapOf<Int, Double>()
        sections.forEach { section ->
            distances[section.edgeId] = distances.getOrDefault(section.edgeId, 0.0) + section.distance
        }
        return CachedRoute(path, distances, sections)
    }

    fun edgeSections(path: ResponsePath): List<EdgeSection> {
        val points = path.points
        if (points.size() == 0) return emptyList()
        return path.pathDetails["edge_id"].orEmpty().mapNotNull { detail ->
            val edgeId = (detail.value as? Number)?.toInt() ?: return@mapNotNull null
            val first = detail.first.coerceIn(0, points.size() - 1)
            val last = min(detail.last, points.size() - 1)
            var distance = 0.0
            for (index in first until last) {
                distance += DistanceCalcEarth.DIST_EARTH.calcDist(
                    points.getLat(index),
                    points.getLon(index),
                    points.getLat(index + 1),
                    points.getLon(index + 1),
                )
            }
            val fromLat = points.getLat(first)
            val toLat = points.getLat(last)
            val forward = fromLat < toLat ||
                (fromLat == toLat && points.getLon(first) <= points.getLon(last))
            EdgeSection(edgeId, forward, distance)
        }
    }

    data class Carousel(
        val routes: List<ResponsePath>,
        val rejectedAsDetour: Int,
    )

    /**
     * Builds the carousel for one ride style. [search] runs one GraphHopper
     * attempt with the roads of already accepted cards as soft-penalised
     * siblings and returns its paths in GraphHopper's order.
     *
     * Attempt 0 is the plain alternative search. Retries only run while the
     * carousel is short: they softly penalise roads of accepted cards and
     * widen GraphHopper's internal pool. The first path of the first attempt
     * is the style's honest optimum; every other card is judged against it
     * ([withinGlobalDetourBudget], [hasExcessiveLocalDetour]) and against all
     * accepted cards ([SIBLING_MAX_SHARE], [isMeaningfullyDifferent]).
     */
    fun selectCarousel(
        style: RideStyle,
        maxCards: Int,
        attempts: IntRange,
        search: (attempt: Int, siblingEdgeIds: Set<Int>) -> List<ResponsePath>,
    ): Carousel {
        var reference: CachedRoute? = null
        val accepted = mutableListOf<CachedRoute>()
        var rejectedAsDetour = 0
        for (attempt in attempts) {
            if (accepted.size >= maxCards) break
            val paths = search(attempt, accepted.flatMapTo(mutableSetOf()) { it.edgeDistances.keys })
            for (path in paths) {
                if (accepted.size >= maxCards) break
                val candidate = cachedRoute(path)
                val primary = reference
                if (primary == null) {
                    reference = candidate
                    accepted += candidate
                    continue
                }
                if (!withinGlobalDetourBudget(candidate.path, primary.path, style) ||
                    hasExcessiveLocalDetour(candidate.edgeSections, primary.edgeSections)
                ) {
                    rejectedAsDetour++
                    continue
                }
                val diversity = routeDiversity(candidate, accepted)
                if (diversity.maxOverlap > SIBLING_MAX_SHARE ||
                    !isMeaningfullyDifferent(candidate, diversity, accepted)
                ) continue
                accepted += candidate
            }
        }
        return Carousel(accepted.map { it.path }, rejectedAsDetour)
    }

    /**
     * Checks a card against the primary route of the same ride style. The
     * primary is the style's honest optimum, so a slow curvy primary is never
     * judged against Fastest. The budget is the style's own
     * ([RideStyle.maxAlternativeTimeRatio], [RideStyle.maxAlternativeDistanceRatio]),
     * taken of the primary or of the reference ride
     * ([BUDGET_REFERENCE_TIME_MS], [BUDGET_REFERENCE_DISTANCE_M]), whichever
     * is longer, so a short hop still has room for a different road.
     * [allowance] scales what the budget adds (2.0 doubles the extra time and
     * distance allowed) for a search widened beyond the style's own budget.
     */
    fun withinGlobalDetourBudget(
        candidate: ResponsePath,
        reference: ResponsePath,
        style: RideStyle,
        allowance: Double = 1.0,
    ): Boolean =
        withinBudget(
            candidate.time.toDouble(), reference.time.toDouble(), BUDGET_REFERENCE_TIME_MS.toDouble(),
            1.0 + (style.maxAlternativeTimeRatio - 1.0) * allowance,
        ) && withinBudget(
            candidate.distance, reference.distance, BUDGET_REFERENCE_DISTANCE_M,
            1.0 + (style.maxAlternativeDistanceRatio - 1.0) * allowance,
        )

    /**
     * [candidate] is within [ratio] of [reference]; a reference shorter than
     * [floor] may add what a [floor]-long ride could. Both branches agree at
     * the floor, and above it the check is the plain ratio it always was.
     */
    private fun withinBudget(candidate: Double, reference: Double, floor: Double, ratio: Double): Boolean =
        if (reference >= floor) candidate / reference <= ratio else candidate - reference <= (ratio - 1.0) * floor

    /**
     * Rejects a bounded local divergence and rejoin bubble: a long wiggle
     * replacing a short link of an otherwise identical ride. A bubble that
     * is at least [LOCAL_DETOUR_IS_THE_RIDE_SHARE] of the candidate is the
     * ride itself (a loop around a 2 km hop), judged by the global budget.
     */
    fun hasExcessiveLocalDetour(
        candidate: List<EdgeSection>,
        reference: List<EdgeSection>,
    ): Boolean {
        if (candidate.isEmpty() || reference.isEmpty()) return false
        val candidateTotal = candidate.sumOf { it.distance }
        val referencePositions = reference.indices.groupBy {
            EdgeTraversal(reference[it].edgeId, reference[it].forward)
        }
        data class Match(val candidateIndex: Int, val referenceIndex: Int)

        val matches = mutableListOf<Match>()
        var lastReferenceIndex = -1
        candidate.forEachIndexed { candidateIndex, section ->
            val traversal = EdgeTraversal(section.edgeId, section.forward)
            val referenceIndex = referencePositions[traversal]
                ?.firstOrNull { it > lastReferenceIndex }
                ?: return@forEachIndexed
            matches += Match(candidateIndex, referenceIndex)
            lastReferenceIndex = referenceIndex
        }
        if (matches.size < 2) return false

        for (index in 1 until matches.size) {
            val before = matches[index - 1]
            val after = matches[index]
            if (after.candidateIndex == before.candidateIndex + 1 &&
                after.referenceIndex == before.referenceIndex + 1
            ) continue

            val candidateDistance = candidate
                .subList(before.candidateIndex + 1, after.candidateIndex)
                .sumOf { it.distance }
            val referenceDistance = reference
                .subList(before.referenceIndex + 1, after.referenceIndex)
                .sumOf { it.distance }
            if (referenceDistance > MAX_LOCAL_BASELINE_DISTANCE) continue
            if (candidateDistance >= LOCAL_DETOUR_IS_THE_RIDE_SHARE * candidateTotal) continue
            val extraDistance = candidateDistance - referenceDistance
            val ratio = candidateDistance / referenceDistance.coerceAtLeast(MIN_LOCAL_BASELINE_DISTANCE)
            if (extraDistance >= MIN_LOCAL_EXTRA_DISTANCE && ratio > MAX_LOCAL_DETOUR_RATIO) return true
        }
        return false
    }

    fun sharedRoadFraction(a: Map<Int, Double>, b: Map<Int, Double>): Double {
        val denominator = min(a.values.sum(), b.values.sum())
        if (denominator <= 0.0) return 1.0
        val shared = a.entries.sumOf { (edgeId, distance) ->
            min(distance, b[edgeId] ?: 0.0)
        }
        return shared / denominator
    }

    /** GraphHopper's alternative-route weight cap for one search attempt. */
    fun maxAlternativeWeight(attempt: Int): Double =
        min(MAX_ALTERNATIVE_WEIGHT, BASE_ALTERNATIVE_WEIGHT + attempt * CANDIDATE_WEIGHT_STEP)

    /**
     * Soft fastest-time multiple added to roads of already accepted cards on
     * retry [attempt] (attempt 0 has no siblings yet). Never a hard block.
     */
    fun siblingRoadPenalty(attempt: Int): Double =
        (1.0 - SIBLING_MAX_SHARE) * (BASE_SIBLING_ROAD_PENALTY + attempt * SIBLING_ROAD_PENALTY_STEP)
}
