package com.curvemaps.app.routing

import com.graphhopper.ResponsePath
import com.graphhopper.util.DistanceCalcEarth
import com.graphhopper.util.shapes.GHPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Request-local preferences. They never depend on which stop the rider visits.
 * [via] routes the ride through one extra point with GraphHopper's
 * pass-through rule (no U-turn at it): the "look around" search of
 * [DistinctRideStyles] for a different road the penalties cannot reach.
 * [loop] routes a round trip through the corners of one loop shape
 * ([RoundTrips]), with the same rule.
 */
internal data class RideStyleSearch(
    val curveWeight: Double? = null,
    val penalizedEdges: Set<Int> = emptySet(),
    val roadPenalty: Double = 0.0,
    val via: GHPoint? = null,
    val loop: RoundTrips.Shape? = null,
)

/**
 * Resolves the seven stops from Fastest up. Each accepted ride must differ
 * meaningfully from every lower stop, so every stop is a ride of its own.
 *
 * Three searches run in turn, each only if the one before found no ride
 * within the stop's time and distance budget ([Budget.STYLE]):
 *
 * 1. Curve-weight escalation: the stop's own preference, a little curvier
 *    each round, within [RideStyle.MAX_CURVE_WEIGHT].
 * 2. Road penalties: the stop's own weight with every road of the rides
 *    already shown made [ROAD_PENALTIES] times slower. Cheap and right on
 *    long corridors, where a different valley or range road exists.
 * 3. Looking around: the stop's own weight routed through one via point on
 *    rings around the corridor ([detourRings]), best ride of a ring first.
 *    A uniform penalty cannot find these: on Skyline Circuit to Palmwoods
 *    every one of the 41 different rides reuses Woombye Palmwoods Road and
 *    Margaret Street, so under any penalty the fully penalised direct road
 *    stayed cheaper than Jubilee Drive or Chevallum Road. It is skipped when
 *    the first two found a ride at the floor within the relaxed budget: in
 *    two 100-corridor reviews (1,800 stops) 63–87% of look-around stops were
 *    nonsense, against 47–62% for a relaxed ride of the first searches.
 *
 * A ride within budget settles the stop only when it is no straighter than
 * the stop below (the card's curvy share, within [CURVINESS_TOLERANCE]), and
 * a look-around ride only when it also adds curves to the road it replaces
 * ([curveGainPerMinute]); the searches go on for one. Without the floor a
 * 100-corridor review found 30% of Max twisty stops more than 2 points
 * straighter than a lower stop: the penalty stage took the first different
 * ride within budget, curvy or not, and the look-around took farm squares
 * that replaced a highway with straighter roads (71% of look-around stops
 * were nonsense).
 *
 * A stop that finds no such ride keeps the best one it found: the gentlest
 * at the floor within [RELAXED_BUDGET_FACTOR] times the budget's allowance,
 * then the curviest within budget, then the gentlest relaxed one, then any
 * meaningfully different ride ([Option.budget]). The relaxed ride at the
 * floor comes first because the curviest ride within budget can be timid
 * (Cowra to Yass Curvy fell from a 12% curvy ride beyond budget to a 2% one,
 * and 61–80% of such stops were nonsense). A stop whose own searches found
 * nothing else takes a ride a lower stop found but did not show ([Found]),
 * then a lasso. A ride that goes round a smaller loop is no ride for any
 * stop ([loopKind]). Only a graph with no other road at all
 * (every search returned a ride already shown) settles the stop on the stop
 * below it, marked with [Option.repeats]. The resolver never invents a ride
 * and never fails a plan whose Fastest ride exists.
 */
internal object DistinctRideStyles {
    const val ESCALATION_ROUNDS = 6
    const val TOP_GAP = 0.75
    const val SAME_RIDE_SHARE = 0.99

    /** Added fastest-time multiples. Start gently to avoid leaving the corridor. */
    private val ROAD_PENALTIES = doubleArrayOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0, 4.0, 8.0, 16.0)

    /** The widened budget's allowance, as a multiple of the stop's own. */
    const val RELAXED_BUDGET_FACTOR = 2.0

    /** How much straighter (card curvy share) a stop may be than the stop below it; round trips use the same. */
    const val CURVINESS_TOLERANCE = RoundTrips.CURVINESS_TOLERANCE

    /**
     * Ring radii around the corridor's midpoint as a fraction of the
     * straight-line distance, clamped to [MIN_RING_RADIUS_M]..[MAX_RING_RADIUS_M]:
     * a 2 km hop looks 0.7, 1, 2 and 3.3 km around; a 40 km ride 14 to 25 km.
     */
    val RING_FRACTIONS = doubleArrayOf(0.35, 0.75, 1.5, 2.5)
    const val MIN_RING_RADIUS_M = 700.0
    const val MAX_RING_RADIUS_M = 25_000.0
    const val RING_BEARINGS = 12

    /** How far beyond the stop's detour budget a ride was accepted. */
    enum class Budget {
        /** Within the stop's own time and distance budget, no local detour bubble. */
        STYLE,
        /** Within [RELAXED_BUDGET_FACTOR] times the budget's allowance, no local detour bubble. */
        RELAXED,
        /** Any meaningfully different ride: the budget held none. */
        NONE,
    }

    data class Option(
        val path: ResponsePath,
        val curveWeight: Double,
        /** 0 for the optimum; otherwise the search round that found this ride. */
        val round: Int,
        val roadPenalty: Double = 0.0,
        /** The via point of a ride found by looking around the corridor. */
        val via: GHPoint? = null,
        val budget: Budget = Budget.STYLE,
        /**
         * The lowest stop whose ride this stop shows because the graph has no
         * other road (every search returned a ride already shown); null for a
         * ride of its own. [path], [curveWeight], [round], [roadPenalty],
         * [via] and [budget] are that stop's.
         */
        val repeats: RideStyle? = null,
        /** The loop shape of a round-trip stop ([RoundTrips]); null on an A-to-B plan. */
        val loop: RoundTrips.Shape? = null,
        /**
         * The stop whose search found this ride; null for the stop itself. The
         * joint choice ([RideLadder]) can give a stop a ride another stop's
         * search found; [curveWeight], [round], [roadPenalty] and [via] are
         * that search's.
         */
        val foundBy: RideStyle? = null,
    )

    fun escalationWeights(style: RideStyle, floor: Double): List<Double> =
        generateSequence(nextEscalationWeight(style, floor)) { nextEscalationWeight(style, it) }
            .takeWhile { it <= RideStyle.MAX_CURVE_WEIGHT }
            .take(ESCALATION_ROUNDS)
            .toList()

    fun nextEscalationWeight(style: RideStyle, curveWeight: Double): Double {
        val next = RideStyle.entries.getOrNull(style.ordinal + 1)
        val step = (next?.let { it.curveWeight - style.curveWeight } ?: TOP_GAP) / 3
        var weight = curveWeight + step
        while (RideStyle.entries.any { abs(it.curveWeight - weight) < 1e-9 }) weight += step
        return weight
    }

    /**
     * Via points for the look-around search: [RING_BEARINGS] points on each
     * ring around the midpoint of [from] and [to], nearest ring first. Rings
     * that clamp to the same radius are searched once.
     */
    fun detourRings(from: GHPoint, to: GHPoint): List<List<GHPoint>> {
        val midLat = (from.lat + to.lat) / 2
        val midLon = (from.lon + to.lon) / 2
        val beeline = DistanceCalcEarth.DIST_EARTH.calcDist(from.lat, from.lon, to.lat, to.lon)
        val metresPerDegreeLat = DistanceCalcEarth.METERS_PER_DEGREE
        val metresPerDegreeLon = metresPerDegreeLat * cos(Math.toRadians(midLat))
        return RING_FRACTIONS
            .map { (beeline * it).coerceIn(MIN_RING_RADIUS_M, MAX_RING_RADIUS_M) }
            .distinct()
            .map { radius ->
                List(RING_BEARINGS) { index ->
                    val bearing = Math.toRadians(index * 360.0 / RING_BEARINGS)
                    GHPoint(midLat + radius * cos(bearing) / metresPerDegreeLat, midLon + radius * sin(bearing) / metresPerDegreeLon)
                }
            }
    }

    /**
     * [rings] are the look-around via points ([detourRings]); empty for a
     * plan with its own shaping points, which the penalties alone serve.
     */
    suspend fun resolve(
        optimum: suspend (RideStyle) -> ResponsePath,
        reweighted: suspend (style: RideStyle, curveWeight: Double) -> ResponsePath?,
        diversified: suspend (RideStyle, RideStyleSearch) -> ResponsePath? = { _, _ -> null },
        rings: List<List<GHPoint>> = emptyList(),
        onResolved: (RideStyle, Option) -> Unit = { _, _ -> },
        onSearched: (RideStyle) -> Unit = {},
        select: Boolean = true,
        onSelected: (Selection) -> Unit = {},
    ): Map<RideStyle, Option> {
        val offered = LinkedHashMap<RideStyle, AlternativePolicy.CachedRoute>()
        val options = LinkedHashMap<RideStyle, Option>()
        val bests = LinkedHashMap<RideStyle, AlternativePolicy.CachedRoute>()
        val found = Found()
        val pool = Pool()
        var floor = 0.0
        var curvyFloor = Double.NEGATIVE_INFINITY
        var previous: Option? = null
        for (style in RideStyle.entries) {
            val best = AlternativePolicy.cachedRoute(optimum(style))
            bests[style] = best
            val fastest = bests.getValue(RideStyle.FASTEST)
            val own = Option(best.path, style.curveWeight, 0)
            pool.record(own, style, best)
            val kept = Kept(curvyFloor, found)
            // A stop's own optimum that rides a loop is no ride, or only a lasso.
            val ownLoop = loopKind(best, fastest)
            val distinct = offered.values.none { sameRide(best, it) }
            if (distinct && ownLoop == LoopKind.LASSO) kept.offerLasso(own)
            val option = (if (distinct && ownLoop == LoopKind.NONE) kept.offer(own) else null)
                ?: escalate(style, best, fastest, max(floor, style.curveWeight), offered.values, reweighted, kept, pool)
                ?: diversify(style, best, fastest, offered.values, diversified, kept, pool)
                ?: (if (kept.holdsARelaxedRideAtTheFloor) null else lookAround(style, best, fastest, offered.values, diversified, rings, kept, pool))
                ?: kept.settle(lasso = false)
                ?: found.spare(style, best, fastest, offered.values, kept)
                ?: repeat(style, previous)
            options[style] = option
            offered[style] = when {
                option.path === best.path -> best
                option.repeats != null -> offered.getValue(option.repeats)
                else -> AlternativePolicy.cachedRoute(option.path)
            }
            floor = option.curveWeight
            if (option.repeats == null) curvyFloor = curvinessFloor(option.path)
            previous = option
            onSearched(style)
            // Fastest is its own optimum either way, so it is final at once.
            if (!select || style == RideStyle.FASTEST) onResolved(style, option)
        }
        if (!select) return options
        val chosen = pool.choose(bests, options, onSelected)
        RideStyle.entries.drop(1).forEach { onResolved(it, chosen.getValue(it)) }
        return chosen
    }

    /**
     * The joint choice of one plan ([RideLadder]): the ladder settled stop by
     * stop ([settled]), the one chosen ([chosen]), both scores (expected
     * nonsense stops, repeats and lassos counted as in [RideLadder.cost]), and
     * how many different rides the searches found ([poolSize]). [problem]
     * is what [RideLadder] chose from, its candidates being [paths] in order,
     * each first found by the search of [foundBy] (for offline re-scoring).
     */
    data class Selection(
        val settled: Map<RideStyle, Option>,
        val chosen: Map<RideStyle, Option>,
        val settledCost: Double,
        val chosenCost: Double,
        val poolSize: Int,
        val problem: RideLadder.Problem? = null,
        val paths: List<ResponsePath> = emptyList(),
        val foundBy: List<RideStyle> = emptyList(),
    )

    /**
     * A plan whose searches found more different rides than this keeps the
     * ladder settled stop by stop (a plan's searches find a median of 18,
     * at most 94 in a 482-plan sweep).
     */
    const val MAX_POOL = 160

    /**
     * Every ride the plan's searches found, once each (the same roads in the
     * same order are one ride), with the search that found it first.
     */
    private class Pool {
        class Entry(val option: Option, val foundBy: RideStyle, val route: AlternativePolicy.CachedRoute)

        val entries = ArrayList<Entry>()
        private val byRoads = HashMap<List<Int>, Int>()
        private val byPath = java.util.IdentityHashMap<ResponsePath, Int>()

        fun record(option: Option, foundBy: RideStyle, route: AlternativePolicy.CachedRoute = AlternativePolicy.cachedRoute(option.path)) {
            if (byPath.containsKey(option.path)) return
            val roads = route.edgeSections.map { it.edgeId }
            val known = if (roads.isEmpty()) null else byRoads[roads]
            if (known != null) {
                byPath[option.path] = known
                return
            }
            byPath[option.path] = entries.size
            if (roads.isNotEmpty()) byRoads[roads] = entries.size
            entries += Entry(option, foundBy, route)
        }

        fun indexOf(path: ResponsePath): Int? = byPath[path]

        /** The ladder [RideLadder] chooses from these rides, as options per stop. */
        fun choose(
            bests: Map<RideStyle, AlternativePolicy.CachedRoute>,
            settled: Map<RideStyle, Option>,
            onSelected: (Selection) -> Unit,
        ): Map<RideStyle, Option> {
            // Beyond this the pairwise comparison would cost more than it can win back.
            if (entries.size > MAX_POOL) return settled
            val styles = RideStyle.entries
            // A ride moves up the slider, never down: a stop may show a ride
            // its own search or a lower stop's found, not a higher stop's.
            val fastest = bests.getValue(RideStyle.FASTEST)
            val loops = entries.map { loopKind(it.route, fastest) }
            val budgets = styles.map { style ->
                val best = bests.getValue(style)
                entries.mapIndexed { index, entry ->
                    when {
                        entry.foundBy > style -> null
                        // A small loop is no ride for any stop; Fastest's own ride never loops.
                        loops[index] == LoopKind.SMALL -> null
                        else -> budgetFor(entry.route, best, style)
                    }
                }
            }
            val ownOptimum = IntArray(styles.size) { checkNotNull(indexOf(bests.getValue(styles[it]).path)) }
            val size = entries.size
            val same = Array(size) { BooleanArray(size) }
            val overlap = Array(size) { DoubleArray(size) }
            for (a in 0 until size) {
                same[a][a] = true
                overlap[a][a] = 1.0
                for (b in a + 1 until size) {
                    val x = entries[a].route
                    val y = entries[b].route
                    same[a][b] = sameRide(x, y)
                    same[b][a] = same[a][b]
                    overlap[a][b] = AlternativePolicy.sharedRoadFraction(x.edgeDistances, y.edgeDistances)
                    overlap[b][a] = overlap[a][b]
                }
            }
            val candidates = entries.mapIndexed { index, entry ->
                val path = entry.option.path
                RideLadder.Candidate(
                    curviness = curvyDistanceFraction(path.points),
                    gravelShare = if (path.distance > 0.0) (routeGravelMeters(path) ?: 0.0) / path.distance else 0.0,
                    lookAround = entry.option.via != null,
                    lasso = (entry.option.via != null && isLasso(path)) || loops[index] == LoopKind.LASSO,
                )
            }
            val timeOverOptimum = styles.map { style ->
                val optimumTime = bests.getValue(style).path.time.toDouble().coerceAtLeast(1.0)
                DoubleArray(size) { entries[it].option.path.time / optimumTime - 1.0 }
            }
            val problem = RideLadder.Problem(candidates, budgets, ownOptimum, same, overlap, timeOverOptimum)
            val seed = IntArray(styles.size) { stop ->
                val option = settled.getValue(styles[stop])
                if (option.repeats != null) RideLadder.REPEAT else checkNotNull(indexOf(option.path))
            }
            val ladder = RideLadder.choose(problem, seed)
            val chosen = LinkedHashMap<RideStyle, Option>()
            styles.forEachIndexed { stop, style ->
                chosen[style] = when {
                    ladder.contentEquals(seed) -> settled.getValue(style)
                    ladder[stop] == RideLadder.REPEAT -> {
                        val below = chosen.getValue(styles[stop - 1])
                        below.copy(repeats = below.repeats ?: styles[stop - 1])
                    }
                    // The stop's own optimum reads as its own, whichever search met it first.
                    ladder[stop] == ownOptimum[stop] -> Option(bests.getValue(style).path, style.curveWeight, 0)
                    else -> {
                        val entry = entries[ladder[stop]]
                        entry.option.copy(
                            budget = checkNotNull(budgets[stop][ladder[stop]]),
                            repeats = null,
                            foundBy = entry.foundBy.takeIf { it != style },
                        )
                    }
                }
            }
            onSelected(
                Selection(
                    settled, chosen, RideLadder.cost(problem, seed), RideLadder.cost(problem, ladder), size,
                    problem, entries.map { it.option.path }, entries.map { it.foundBy },
                ),
            )
            return chosen
        }
    }

    /**
     * The budget class of [candidate] for [style] against that stop's own
     * optimum [best], or null when it is no ride for that stop (it drives a
     * road twice or turns around; [Pool.choose] leaves out small loops
     * first). The optimum itself is always the stop's.
     */
    private fun budgetFor(candidate: AlternativePolicy.CachedRoute, best: AlternativePolicy.CachedRoute, style: RideStyle): Budget? {
        if (candidate.path === best.path) return Budget.STYLE
        if (drivesARoadTwice(candidate) || turnsAround(candidate.path, excusedBy = best.path)) return null
        return when {
            AlternativePolicy.hasExcessiveLocalDetour(candidate.edgeSections, best.edgeSections) -> Budget.NONE
            AlternativePolicy.withinGlobalDetourBudget(candidate.path, best.path, style) -> Budget.STYLE
            AlternativePolicy.withinGlobalDetourBudget(candidate.path, best.path, style, RELAXED_BUDGET_FACTOR) -> Budget.RELAXED
            else -> Budget.NONE
        }
    }

    /** The least curvy share the stop above a stop showing [path] may settle on. */
    fun curvinessFloor(path: ResponsePath): Double = curvyDistanceFraction(path.points) - CURVINESS_TOLERANCE

    /**
     * Curvy metres [candidate] adds to [best] per minute it adds (at least
     * one minute counted, so a quicker ride is judged by its curves alone).
     * Positive only for a ride with more curves than the stop's own optimum,
     * which on a look-around stop is a lower stop's ride: the detour then
     * replaces part of it with curvier road.
     */
    fun curveGainPerMinute(candidate: ResponsePath, best: ResponsePath): Double {
        val added = curvyMeters(candidate) - curvyMeters(best)
        val minutes = max(1.0, (candidate.time - best.time) / 60_000.0)
        return added / minutes
    }

    private fun curvyMeters(path: ResponsePath): Double {
        val points = path.points
        val count = points.size()
        if (count < 2) return 0.0
        return curvyDistanceMeters(DoubleArray(count) { points.getLat(it) }, DoubleArray(count) { points.getLon(it) }, 0, count - 1)
    }

    /**
     * The stop below [style], with no other road in the graph: its ride is
     * the curviest one that exists here, so [style] shows it too rather than
     * an error. Fastest is never repeated (nothing is below it).
     */
    private fun repeat(style: RideStyle, previous: Option?): Option {
        checkNotNull(previous) { "${style.label} has no stop below it to repeat" }
        return previous.copy(repeats = previous.repeats ?: RideStyle.entries[style.ordinal - 1])
    }

    /**
     * The rides a stop found that do not settle it at once, kept while the
     * search goes on: the curviest within budget but straighter than
     * [curvyFloor] (or a look-around ride that adds no curves), and the
     * gentlest (least time) meaningfully different ride beyond the budget in
     * each class, one at the floor first. [settle] prefers a relaxed ride at
     * the floor to a straighter one within budget.
     */
    private class Kept(val curvyFloor: Double, private val found: Found) {
        private var straighter: Option? = null
        private var lasso: Option? = null
        private var straighterCurviness = Double.NEGATIVE_INFINITY
        private var relaxedAtFloor: Option? = null
        private var relaxed: Option? = null
        private var unbounded: Option? = null

        /** [option] when it settles the stop (within budget, at the floor and [addsCurves]); otherwise kept, and null. */
        fun offer(option: Option, addsCurves: Boolean = true): Option? {
            found += option
            val curviness = curvyDistanceFraction(option.path.points)
            val atFloor = curviness >= curvyFloor
            when (option.budget) {
                Budget.STYLE -> if (atFloor && addsCurves) {
                    return option
                } else if (curviness > straighterCurviness) {
                    straighter = option
                    straighterCurviness = curviness
                }
                Budget.RELAXED -> {
                    if (atFloor) relaxedAtFloor = gentler(relaxedAtFloor, option)
                    relaxed = gentler(relaxed, option)
                }
                Budget.NONE -> unbounded = gentler(unbounded, option)
            }
            return null
        }

        /** Whether [option] (within budget) would settle the stop. */
        fun settles(option: Option): Boolean = curvyDistanceFraction(option.path.points) >= curvyFloor

        /** Whether a ride at the floor within the relaxed budget was found: the look-around is then not tried. */
        val holdsARelaxedRideAtTheFloor: Boolean get() = relaxedAtFloor != null

        /** A lasso ([isLassoRide]): shown only when the stop would otherwise repeat the one below. */
        fun offerLasso(option: Option) {
            found += option
            lasso = gentler(lasso, option)
        }

        fun settle(lasso: Boolean = true): Option? =
            relaxedAtFloor ?: straighter ?: relaxed ?: unbounded ?: this.lasso.takeIf { lasso }

        private fun gentler(kept: Option?, option: Option): Option =
            if (kept == null || option.path.time < kept.path.time) option else kept
    }

    /**
     * Every ride the searches of lower stops found ([Kept.offer]), for a stop
     * whose own searches found no ride of its own: a ride a lower stop found
     * but did not show is still a different road (Kilkivan to Goomeri: Curvy
     * passed over a 25.8 km ride for a curvier one and Max twisty, whose
     * searches never met that road, repeated Very twisty).
     */
    private class Found {
        private val options = ArrayList<Option>()
        private val ids = HashSet<ResponsePath>()

        operator fun plusAssign(option: Option) {
            if (ids.add(option.path)) options += option
        }

        fun spare(
            style: RideStyle,
            best: AlternativePolicy.CachedRoute,
            fastest: AlternativePolicy.CachedRoute,
            offered: Collection<AlternativePolicy.CachedRoute>,
            kept: Kept,
        ): Option? {
            for (option in options.toList()) {
                val route = AlternativePolicy.cachedRoute(option.path)
                val budget = classify(route, best, fastest, style, offered) ?: continue
                val spare = option.copy(budget = budget)
                if (isLassoRide(route, fastest, option.via)) kept.offerLasso(spare) else kept.offer(spare)?.let { return it }
            }
            return kept.settle()
        }
    }

    /**
     * How [candidate] stands against [best] for [style]: null when it is not
     * a ride of its own (it repeats a ride already offered, drives a road
     * twice, turns around or rides a small loop, [loopKind]), otherwise the
     * budget class it falls in. A lasso is classified like any ride; the
     * searches keep it apart ([isLassoRide]).
     */
    private fun classify(
        candidate: AlternativePolicy.CachedRoute,
        best: AlternativePolicy.CachedRoute,
        fastest: AlternativePolicy.CachedRoute,
        style: RideStyle,
        offered: Collection<AlternativePolicy.CachedRoute>,
    ): Budget? {
        if (drivesARoadTwice(candidate) || turnsAround(candidate.path, excusedBy = best.path) || offered.any { sameRide(candidate, it) }) return null
        if (loopKind(candidate, fastest) == LoopKind.SMALL) return null
        val bubble = AlternativePolicy.hasExcessiveLocalDetour(candidate.edgeSections, best.edgeSections)
        return when {
            bubble -> Budget.NONE
            AlternativePolicy.withinGlobalDetourBudget(candidate.path, best.path, style) -> Budget.STYLE
            AlternativePolicy.withinGlobalDetourBudget(candidate.path, best.path, style, RELAXED_BUDGET_FACTOR) -> Budget.RELAXED
            else -> Budget.NONE
        }
    }

    /**
     * Metres of its own road a ride may drive again: a via point splits its
     * edge, and a ride may cross a road it rode for a few metres. More is an
     * out-and-back spur. A loop that comes back along the road it left by
     * ([loopKind]) is judged as a loop however few metres it drives again.
     */
    const val MAX_RETRACED_METRES = 150.0

    /** The shortest loop [loopMetres] counts. */
    const val LOOP_MIN_METRES = 300.0

    /**
     * How far along Fastest's road a ride that left it may come back onto it
     * and still have gone round a loop ([loopMetres]), and how many times
     * that distance the loop must be.
     */
    const val LOOP_REJOIN_METRES = 500.0
    const val LOOP_RATIO = 3.0

    /** How much two rides' first (or last) stretches of one road may differ and still be the same way along it ([rejoinLoops]). */
    const val END_SECTION_TOLERANCE_METRES = 50.0

    /** How a ride loops ([loopKind]). */
    enum class LoopKind {
        /** No loop. */
        NONE,
        /** Only loops at least a lasso long ([lassoMinMetres]): it stands in only for a repeat. */
        LASSO,
        /** A loop shorter than a lasso: no ride for any stop. */
        SMALL,
    }

    /**
     * How [candidate] loops ([loopMetres]). A loop shorter than a lasso
     * ([lassoMinMetres]) is no ride for any stop: on Skyline Circuit to
     * Palmwoods (rider settings hills and stops on) the joint choice showed
     * Max twisty leaving Woombye Palmwoods Road for a block north of
     * Palmwoods and coming back along the 70 m it left by, 1.1 km for no
     * progress, and Balanced passing 26 m from Palmwoods and going round a
     * block to arrive from the other side. Reviewed, every shown ride that
     * passed one of its own points twice was called a pointless loop. A
     * longer loop stands in only for a repeat, like a look-around lasso
     * ([isLasso]): on a 2–6 km hop through the Blackall Range the old ladders
     * showed 20–55 km lassos for every stop, and without them every stop
     * repeated Fastest.
     */
    fun loopKind(candidate: AlternativePolicy.CachedRoute, fastest: AlternativePolicy.CachedRoute): LoopKind {
        val loops = loopMetres(candidate, fastest)
        if (loops.isEmpty()) return LoopKind.NONE
        val lasso = lassoMinMetres(candidate.path.distance)
        return if (loops.any { it < lasso }) LoopKind.SMALL else LoopKind.LASSO
    }

    /** Whether [route] (found through [via], if any) is a lasso: shown only instead of a repeat. */
    private fun isLassoRide(route: AlternativePolicy.CachedRoute, fastest: AlternativePolicy.CachedRoute, via: GHPoint?): Boolean =
        (via != null && isLasso(route.path)) || loopKind(route, fastest) == LoopKind.LASSO

    /**
     * The length of every loop [candidate] rides, in metres. Two shapes count:
     * - passing a point of its own road again at least [LOOP_MIN_METRES]
     *   later, unless both passes are within [END_U_TURN_METRES] of the same
     *   end (where the start or finish snapped) or [fastest] passes a point
     *   twice within [SHARED_U_TURN_METRES] of there too (the road network
     *   forces it); the loop is the distance between the passes;
     * - leaving [fastest]'s roads and coming back onto them at most
     *   [LOOP_REJOIN_METRES] further along [fastest], after riding at least
     *   [LOOP_RATIO] times that and [LOOP_MIN_METRES] off them (Ruatoria to
     *   Hicks Bay Max twisty: 730 m round a block for 121 m of road); the loop
     *   is the distance off them.
     * Mountain switchbacks and interchange loops pass no point twice and
     * leave no road they come back to, so neither shape sees them.
     */
    fun loopMetres(candidate: AlternativePolicy.CachedRoute, fastest: AlternativePolicy.CachedRoute): List<Double> {
        if (candidate.path === fastest.path) return emptyList()
        return revisitLoops(candidate.path, fastest.path) + rejoinLoops(candidate.edgeSections, fastest.edgeSections)
    }

    /** Distance from the start to each point of [path]. */
    private fun alongMetres(path: ResponsePath): DoubleArray {
        val points = path.points
        val along = DoubleArray(points.size())
        for (index in 1 until points.size()) {
            along[index] = along[index - 1] + DistanceCalcEarth.DIST_EARTH.calcDist(
                points.getLat(index - 1), points.getLon(index - 1), points.getLat(index), points.getLon(index),
            )
        }
        return along
    }

    /** Where (point index pairs) [path] passes one of its own points again at least [LOOP_MIN_METRES] later. */
    private fun repeatedPoints(path: ResponsePath, along: DoubleArray): List<Pair<Int, Int>> {
        val points = path.points
        val first = HashMap<Pair<Double, Double>, Int>()
        val repeats = ArrayList<Pair<Int, Int>>()
        for (index in 0 until points.size()) {
            val key = points.getLat(index) to points.getLon(index)
            val earlier = first.putIfAbsent(key, index) ?: continue
            if (along[index] - along[earlier] >= LOOP_MIN_METRES) repeats += earlier to index
        }
        return repeats
    }

    /** The loops (metres between the passes) of [path] passing its own points twice ([loopMetres]). */
    private fun revisitLoops(path: ResponsePath, fastest: ResponsePath): List<Double> {
        val along = alongMetres(path)
        val total = along.lastOrNull() ?: return emptyList()
        val repeats = repeatedPoints(path, along).filterNot { (earlier, later) ->
            along[later] <= END_U_TURN_METRES || total - along[earlier] <= END_U_TURN_METRES
        }
        if (repeats.isEmpty()) return emptyList()
        val forced = repeatedPoints(fastest, alongMetres(fastest)).map { (earlier, _) -> fastest.points.getLat(earlier) to fastest.points.getLon(earlier) }
        val points = path.points
        return repeats.filter { (earlier, _) ->
            forced.none { (lat, lon) -> DistanceCalcEarth.DIST_EARTH.calcDist(lat, lon, points.getLat(earlier), points.getLon(earlier)) <= SHARED_U_TURN_METRES }
        }.map { (earlier, later) -> along[later] - along[earlier] }
    }

    /**
     * The loops (metres off [reference]'s roads) of [candidate] leaving
     * [reference]'s roads and coming back onto them at most
     * [LOOP_REJOIN_METRES] further along [reference], after riding at least
     * [LOOP_RATIO] times that and [LOOP_MIN_METRES] off them. Both rides
     * start and finish at the same points, so the start and the finish count
     * as their roads too: Samford Valley to Brisbane Max twisty began with
     * 1.1 km round a block back onto the 350 m link Fastest takes.
     */
    fun rejoinLoops(candidate: List<AlternativePolicy.EdgeSection>, reference: List<AlternativePolicy.EdgeSection>): List<Double> {
        if (candidate.isEmpty() || reference.isEmpty()) return emptyList()
        val positions = HashMap<Int, MutableList<Int>>()
        reference.forEachIndexed { index, section -> positions.getOrPut(section.edgeId) { ArrayList() } += index }
        val along = DoubleArray(reference.size + 1)
        reference.forEachIndexed { index, section -> along[index + 1] = along[index] + section.distance }
        val loops = ArrayList<Double>()
        fun rejoin(off: Double, skipped: Double) {
            if (off > 0.0 && skipped <= LOOP_REJOIN_METRES && off >= max(LOOP_MIN_METRES, LOOP_RATIO * skipped)) loops += off
        }
        // The start and the finish lie on one road each, which a ride may
        // leave either way: the same road ridden another way round (Samford
        // Valley starts on a loop street, 73 m to its corner one way, 1.1 km
        // the other) is not the reference's road.
        fun sameEnd(a: AlternativePolicy.EdgeSection, b: AlternativePolicy.EdgeSection) =
            a.forward == b.forward && abs(a.distance - b.distance) <= END_SECTION_TOLERANCE_METRES
        val otherStart = !sameEnd(candidate.first(), reference.first())
        val otherFinish = !sameEnd(candidate.last(), reference.last())
        // Where the candidate last rode the reference's road: -1 is the start.
        var lastMatched = -1
        var off = 0.0
        for ((index, section) in candidate.withIndex()) {
            val matched = positions[section.edgeId]?.firstOrNull { it > lastMatched }
                ?.takeUnless { (it == 0 && index == 0 && otherStart) || (it == reference.lastIndex && index == candidate.lastIndex && otherFinish) }
            if (matched == null) {
                off += section.distance
                continue
            }
            rejoin(off, along[matched] - along[lastMatched + 1])
            lastMatched = matched
            off = 0.0
        }
        // A ride that never meets the reference's road is another way, not a loop off it.
        if (lastMatched >= 0) rejoin(off, along[reference.size] - along[lastMatched + 1])
        return loops
    }

    /**
     * An out-and-back spur (a via point on a road that dead-ends further
     * on) is not a ride. A via point splits its edge into two consecutive
     * sections with the same id; that is one pass, not two.
     */
    fun drivesARoadTwice(candidate: AlternativePolicy.CachedRoute): Boolean =
        retracedMeters(candidate) > MAX_RETRACED_METRES

    /** Metres of road [candidate] drives again after it already drove them once. */
    fun retracedMeters(candidate: AlternativePolicy.CachedRoute): Double {
        val seen = HashSet<Int>()
        var previous = -1
        var retraced = 0.0
        for (section in candidate.edgeSections) {
            if (section.edgeId != previous && !seen.add(section.edgeId)) retraced += section.distance
            previous = section.edgeId
        }
        return retraced
    }

    /** GraphHopper's U-turn instruction signs: a ride that turns around is a spur. */
    private val U_TURN_SIGNS = setOf(
        com.graphhopper.util.Instruction.U_TURN_UNKNOWN,
        com.graphhopper.util.Instruction.U_TURN_LEFT,
        com.graphhopper.util.Instruction.U_TURN_RIGHT,
    )

    /**
     * A U-turn this close to the start or the finish is where the point
     * snapped, not a spur: a place on one carriageway of a divided road or
     * the wrong side of a median makes every ride that leaves the other way
     * begin "20 m, U-turn". Rejecting those threw away 71% of the 3,428
     * U-turn rejections in a 100-corridor review, and on Toowoomba to Gatton
     * every candidate of the first two searches, so three stops repeated.
     */
    const val END_U_TURN_METRES = 300.0

    /**
     * How close (metres) a U-turn must be to one the stop's own optimum makes
     * for [turnsAround] to excuse it.
     */
    const val SHARED_U_TURN_METRES = 50.0

    /**
     * Whether [path] turns around more than [END_U_TURN_METRES] from both of
     * its ends, at a place where [excusedBy] (the stop's own optimum) does
     * not turn around too. A U-turn the optimum makes is forced by where a
     * point snapped, however far from the end the next turning place is: on
     * Sunshine Coast to Yarrabilba every ride turns onto Pioneer Drive 2.8 km
     * before the finish, so every candidate was rejected and Curvy and
     * Twisty repeated Balanced.
     */
    fun turnsAround(path: ResponsePath, excusedBy: ResponsePath? = null): Boolean {
        // The router always builds instructions; a path without them (a
        // desktop fixture) cannot be shown to turn around.
        val instructions = instructionsOf(path) ?: return false
        val excused = excusedBy?.let(::uTurnPoints).orEmpty()
        val total = instructions.sumOf { it.distance }
        var along = 0.0
        for (instruction in instructions) {
            if (instruction.sign in U_TURN_SIGNS && along > END_U_TURN_METRES && along < total - END_U_TURN_METRES) {
                val at = instruction.points.takeIf { it.size() > 0 }
                val shared = at != null && excused.any { DistanceCalcEarth.DIST_EARTH.calcDist(it.lat, it.lon, at.getLat(0), at.getLon(0)) <= SHARED_U_TURN_METRES }
                if (!shared) return true
            }
            along += instruction.distance
        }
        return false
    }

    private fun instructionsOf(path: ResponsePath) = try {
        path.instructions
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Where [path] makes a U-turn. */
    private fun uTurnPoints(path: ResponsePath): List<GHPoint> =
        instructionsOf(path).orEmpty().filter { it.sign in U_TURN_SIGNS && it.points.size() > 0 }
            .map { GHPoint(it.points.getLat(0), it.points.getLon(0)) }

    private suspend fun escalate(
        style: RideStyle,
        best: AlternativePolicy.CachedRoute,
        fastest: AlternativePolicy.CachedRoute,
        floor: Double,
        offered: Collection<AlternativePolicy.CachedRoute>,
        reweighted: suspend (RideStyle, Double) -> ResponsePath?,
        kept: Kept,
        pool: Pool,
    ): Option? {
        for ((index, weight) in escalationWeights(style, floor).withIndex()) {
            val path = reweighted(style, weight) ?: continue
            pool.record(Option(path, weight, index + 1), style)
            // A later weighting can find a shorter corridor. A rejected search
            // is not proof that all later searches also exceed the budget.
            val route = AlternativePolicy.cachedRoute(path)
            val budget = classify(route, best, fastest, style, offered) ?: continue
            val option = Option(path, weight, index + 1, budget = budget)
            if (isLassoRide(route, fastest, via = null)) kept.offerLasso(option) else kept.offer(option)?.let { return it }
        }
        return null
    }

    private suspend fun diversify(
        style: RideStyle,
        best: AlternativePolicy.CachedRoute,
        fastest: AlternativePolicy.CachedRoute,
        offered: Collection<AlternativePolicy.CachedRoute>,
        search: suspend (RideStyle, RideStyleSearch) -> ResponsePath?,
        kept: Kept,
        pool: Pool,
    ): Option? {
        val roads = offered.flatMapTo(HashSet()) { it.edgeDistances.keys }
        for ((index, penalty) in ROAD_PENALTIES.withIndex()) {
            val path = search(style, RideStyleSearch(style.curveWeight, roads, penalty)) ?: continue
            pool.record(Option(path, style.curveWeight, ESCALATION_ROUNDS + index + 1, penalty), style)
            val route = AlternativePolicy.cachedRoute(path)
            val budget = classify(route, best, fastest, style, offered) ?: continue
            val option = Option(path, style.curveWeight, ESCALATION_ROUNDS + index + 1, penalty, budget = budget)
            if (isLassoRide(route, fastest, via = null)) kept.offerLasso(option) else kept.offer(option)?.let { return it }
        }
        return null
    }

    /**
     * Routes through every via point of a ring and takes the ride within
     * budget, at the floor and adding curves ([curveGainPerMinute] > 0), with
     * the lowest route weight at the stop's own preference; the next ring
     * only when a ring holds none. The style decides how much detour its
     * curves are worth, and gravel and town streets count as the weighting
     * counts them: ranking by curves per added minute handed Blackheath to
     * Singleton Mostly fast the Wollombi dirt loop (97 extra minutes, 50 km
     * of gravel). A ride that adds no curves only stands in ([Kept]): through
     * a ring point in farmland the gentlest detour was a square on straight
     * roads replacing a highway. A lasso ([isLassoRide]) stands in only
     * when the stop would otherwise repeat the stop below.
     */
    private suspend fun lookAround(
        style: RideStyle,
        best: AlternativePolicy.CachedRoute,
        fastest: AlternativePolicy.CachedRoute,
        offered: Collection<AlternativePolicy.CachedRoute>,
        search: suspend (RideStyle, RideStyleSearch) -> ResponsePath?,
        rings: List<List<GHPoint>>,
        kept: Kept,
        pool: Pool,
    ): Option? {
        var round = ESCALATION_ROUNDS + ROAD_PENALTIES.size
        for (ring in rings) {
            var liked: Option? = null
            for (via in ring) {
                round++
                val path = search(style, RideStyleSearch(style.curveWeight, via = via)) ?: continue
                pool.record(Option(path, style.curveWeight, round, via = via), style)
                val route = AlternativePolicy.cachedRoute(path)
                val budget = classify(route, best, fastest, style, offered) ?: continue
                val option = Option(path, style.curveWeight, round, via = via, budget = budget)
                if (isLassoRide(route, fastest, via)) {
                    kept.offerLasso(option)
                    continue
                }
                val gain = curveGainPerMinute(path, best.path)
                if (budget == Budget.STYLE && gain > 0.0 && kept.settles(option)) {
                    if (liked == null || path.routeWeight < liked.path.routeWeight) liked = option
                } else {
                    kept.offer(option, addsCurves = gain > 0.0)
                }
            }
            if (liked != null) return liked
        }
        return null
    }

    /** How close (metres) a ride must come back to a point it passed for [lassoMeters] to count it. */
    const val LASSO_RETURN_METRES = 60.0

    /**
     * The shortest lasso: max(this, [LASSO_MIN_SHARE] of the ride)
     * ([lassoMinMetres]). [lassoMeters] counts no smaller loop (interchange
     * ramps and a turnaround where the start snapped onto the wrong
     * carriageway close smaller loops near the road they leave), and a
     * smaller loop [loopKind] finds is no ride at all.
     */
    const val LASSO_MIN_METRES = 3_000.0
    const val LASSO_MIN_SHARE = 0.1

    /** The shortest lasso on a ride of [distanceMetres]. */
    fun lassoMinMetres(distanceMetres: Double): Double = max(LASSO_MIN_METRES, LASSO_MIN_SHARE * distanceMetres)

    /** Whether [path] is a lasso: it comes back to where it was after a loop of at least [lassoMinMetres]. */
    fun isLasso(path: ResponsePath): Boolean =
        lassoMeters(path, lassoMinMetres(path.distance)) > 0.0

    /**
     * Length of the longest loop after which [path] comes back within
     * [LASSO_RETURN_METRES] of a point it passed at least [minLoopMeters]
     * earlier; 0 when it never does. A look-around via point beside a town
     * pulled rides out of the town and back to collect a curvy road (Bell to
     * Kumbia Balanced: a 25 km unpaved loop out of Bell and back), on roads
     * the edge-based [drivesARoadTwice] never sees twice.
     */
    fun lassoMeters(path: ResponsePath, minLoopMeters: Double): Double {
        val points = path.points
        if (points.size() < 2) return 0.0
        // Resample every SAMPLE_METRES so a long straight edge has points to meet.
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        val along = ArrayList<Double>()
        lats += points.getLat(0); lons += points.getLon(0); along += 0.0
        var travelled = 0.0
        for (i in 1 until points.size()) {
            val lat0 = points.getLat(i - 1); val lon0 = points.getLon(i - 1)
            val lat1 = points.getLat(i); val lon1 = points.getLon(i)
            val length = DistanceCalcEarth.DIST_EARTH.calcDist(lat0, lon0, lat1, lon1)
            val steps = max(1, Math.ceil(length / SAMPLE_METRES).toInt())
            for (step in 1..steps) {
                val t = step.toDouble() / steps
                lats += lat0 + t * (lat1 - lat0); lons += lon0 + t * (lon1 - lon0); along += travelled + t * length
            }
            travelled += length
        }
        val cellLat = LASSO_RETURN_METRES / DistanceCalcEarth.METERS_PER_DEGREE
        val cellLon = cellLat / max(0.1, cos(Math.toRadians(lats[0])))
        fun row(i: Int): Long = floor(lats[i] / cellLat).toLong()
        fun column(i: Int): Long = floor(lons[i] / cellLon).toLong()
        fun key(i: Int): Long = row(i) * 1_000_003L + column(i)
        val grid = HashMap<Long, MutableList<Int>>()
        var longest = 0.0
        var queued = 0
        for (j in lats.indices) {
            // Only points at least minLoopMeters behind are candidates.
            while (queued < j && along[j] - along[queued] >= minLoopMeters) {
                grid.getOrPut(key(queued)) { ArrayList() } += queued
                queued++
            }
            val row = row(j)
            val column = column(j)
            for (dy in -1L..1L) for (dx in -1L..1L) {
                grid[(row + dy) * 1_000_003L + column + dx]?.forEach { i ->
                    val loop = along[j] - along[i]
                    if (loop > longest && DistanceCalcEarth.DIST_EARTH.calcDist(lats[i], lons[i], lats[j], lons[j]) <= LASSO_RETURN_METRES) longest = loop
                }
            }
        }
        return longest
    }

    private const val SAMPLE_METRES = 25.0

    /**
     * A tiny road swap is still the same ride. Require a continuous different
     * stretch of clamp(5% of the ride, 750 m, 3 km) in at least one direction.
     * Compare both directions so a shorter route can differ by omitting a
     * meaningful stretch. Missing edge data cannot prove a different ride.
     */
    fun sameRide(a: AlternativePolicy.CachedRoute, b: AlternativePolicy.CachedRoute): Boolean {
        val totalA = a.edgeDistances.values.sum()
        val totalB = b.edgeDistances.values.sum()
        if (totalA <= 0.0 || totalB <= 0.0) return true
        val shared = a.edgeDistances.entries.sumOf { (edgeId, distance) ->
            minOf(distance, b.edgeDistances[edgeId] ?: 0.0)
        }
        if (shared / max(totalA, totalB) >= SAME_RIDE_SHARE) return true
        fun hasStretch(candidate: AlternativePolicy.CachedRoute, other: AlternativePolicy.CachedRoute): Boolean {
            return AlternativePolicy.longestDistinctStretch(candidate.edgeSections, other.edgeDistances.keys) >=
                AlternativePolicy.requiredDistinctStretch(candidate.path.distance)
        }
        return !hasStretch(a, b) && !hasStretch(b, a)
    }
}
