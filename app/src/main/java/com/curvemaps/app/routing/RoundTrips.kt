package com.curvemaps.app.routing

import com.graphhopper.ResponsePath
import com.graphhopper.util.DistanceCalcEarth
import com.graphhopper.util.shapes.GHPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Round trips: a ride that leaves the start and comes back to it, about as
 * long as the rider asked for ([RoundTripLength]), on roads the selected
 * ride style likes.
 *
 * A loop is a triangle: the start and two via points [APEX_DEGREES] apart at
 * the same distance from it ([Shape]), ridden start → via → via → start with
 * GraphHopper's pass-through rule, so the ride never turns around at a via.
 * The style's weighting picks the roads between the corners; the corners
 * pick the country. A stop routes [BEARINGS] directions at once (a tier) and
 * likes best the loop with the lowest [Loop.score]: route weight per metre
 * (the trade the A-to-B router makes: Fastest prefers quick roads, a curvy
 * stop the bends), closest to the wished length, least road ridden twice.
 *
 * Every stop must be a ride of its own ([DistinctRideStyles.sameRide] against
 * every lower stop), and sliding right should not make the loop straighter.
 * Stops settle Fastest first, as on an A-to-B plan, each taking its
 * favourite different loop within its budget ([DistinctRideStyles.Budget.STYLE]:
 * [MIN_SIZE_RATIO]..[MAX_SIZE_RATIO] of the wished length, at most
 * [MAX_RETRACED_SHARE] ridden twice) that is no straighter than the stop
 * below (the card's curvy share, within [CURVINESS_TOLERANCE]). A stop whose
 * tier holds none searches further tiers, rotated to directions not yet
 * tried ([TIER_BEARING_OFFSETS]); for the curviness floor alone it searches
 * at most [FLOOR_TIERS], then takes the curviest loop within budget. Only
 * then does it settle for a looser loop ([DistinctRideStyles.Budget.RELAXED],
 * then any), and only a start with no different loop at all repeats the stop
 * below ([DistinctRideStyles.Option.repeats]).
 *
 * Measured on 20 plans (5 SEQ starts, 1 h, 3 h, 40 km, 150 km;
 * `RoundTripProbeTest`): without the floor 51 stop pairs came out more than
 * 2 points straighter than a lower stop; with it, 42 at two tiers, 43 at
 * three, and 34 with no limit (but up to twice the searches on 3 h loops).
 * Handing the loops out from Max twisty down did worse (54): each stop only sees the
 * corners its own tiers routed, so Max twisty's favourite was often not the
 * curviest loop another stop had found. Max twisty is curvier than Fastest
 * on all 20.
 *
 * Loop size is calibrated as it goes: the first tier is sized from the wish
 * with [ROAD_FACTOR] (road metres per straight-line metre of the triangle,
 * measured around SEQ), and every tier rescales the next one by the median
 * size ratio it measured, so a coast, a range or a slow curvy style all
 * converge on the wished length.
 *
 * Measured on the Queensland graph: a via snapped onto a dead-end road
 * cannot be passed through ("Connection between locations not found"; the
 * same loop without pass-through U-turns at the via), and a via in a forest
 * or the sea has no through road near it; the router moves corners off both
 * ([ThroughRoadSnap.snapPassable]) and a direction it cannot fix is dropped.
 */
internal object RoundTrips {
    /** Angle at the start between the ride out and the ride back. */
    const val APEX_DEGREES = 60.0

    /** Directions per tier. */
    const val BEARINGS = 8

    /** Each tier's rotation, so a later tier looks between the earlier ones. */
    val TIER_BEARING_OFFSETS = doubleArrayOf(0.0, 22.5, 11.25, 33.75)

    /** Road metres per straight-line metre of a loop's triangle (median measured ratio 1.0). */
    const val ROAD_FACTOR = 1.6

    /** A loop within this share of the wished length is the length asked for. */
    const val MIN_SIZE_RATIO = 0.8
    const val MAX_SIZE_RATIO = 1.25

    /** The looser size band a stop settles for when its budget holds no loop. */
    const val RELAXED_MIN_SIZE_RATIO = 0.6
    const val RELAXED_MAX_SIZE_RATIO = 1.6

    /** Share of a loop that may be road it already rode (the street home, a short spur). */
    const val MAX_RETRACED_SHARE = 0.12
    const val RELAXED_RETRACED_SHARE = 0.30

    /**
     * Road a loop may ride again away from its start: a lasso, where the
     * loop leaves a road for a corner and comes back to ride the same road
     * again. A block's worth is fine, as on an A-to-B ride
     * ([DistinctRideStyles.MAX_RETRACED_METRES]), and so is a junction's
     * worth of a long loop ([MAX_LASSO_SHARE]; Noosa 150 km rode 295 m
     * twice). Skyline Circuit (Palmwoods), 50 km, Max twisty: a corner on a
     * side loop south of the Mooloolah road had the ride go 2.9 km west,
     * round the corner and the same 2.9 km west again, only 5% of the loop,
     * so the retraced share alone let it through.
     */
    const val MAX_LASSO_METRES = DistinctRideStyles.MAX_RETRACED_METRES
    const val MAX_LASSO_SHARE = 0.005
    const val RELAXED_LASSO_METRES = 500.0
    const val RELAXED_LASSO_SHARE = 0.02

    /** The lasso a loop of [distanceMetres] may ride within budget (or the relaxed one). */
    fun lassoAllowance(distanceMetres: Double, relaxed: Boolean = false): Double =
        if (relaxed) max(RELAXED_LASSO_METRES, RELAXED_LASSO_SHARE * distanceMetres)
        else max(MAX_LASSO_METRES, MAX_LASSO_SHARE * distanceMetres)

    /** Score multipliers per |ln(size ratio)| and per retraced share. */
    const val SIZE_WEIGHT = 1.0
    const val RETRACE_WEIGHT = 3.0

    /** Tiers a stop searches for a loop no straighter than the stop below before it settles. */
    const val FLOOR_TIERS = 2

    /** How much straighter than the stop below a loop may be and still count as no straighter. */
    const val CURVINESS_TOLERANCE = 0.02

    /** A tier whose median size ratio is within this of 1 keeps its radius. */
    const val CALIBRATION_TOLERANCE = 0.15

    /** Bounds of the calibrated radius as multiples of the first estimate. */
    const val MIN_RADIUS_FACTOR = 0.25
    const val MAX_RADIUS_FACTOR = 3.0

    /** Below this a triangle's corners crowd the start's own streets. */
    const val MIN_RADIUS_METERS = 1_000.0

    const val NO_ROUND_TRIP_MESSAGE = "No round trip found from here. Try another start or length."

    /** Straight-line perimeter of the triangle per metre of radius. */
    val PERIMETER_FACTOR: Double = 2.0 + 2.0 * sin(Math.toRadians(APEX_DEGREES / 2.0))

    /** Share of the radius a corner may move to reach a passable road ([ThroughRoadSnap.snapPassable]). */
    const val CORNER_SEARCH_SHARE = 0.3
    const val MAX_CORNER_SEARCH_METERS = 9_000.0

    /**
     * One loop's corners: [vias] at [radiusMeters] from the start, either side
     * of [bearing]. The router moves each onto the nearest passable through
     * road within [searchMeters].
     */
    data class Shape(val bearing: Double, val radiusMeters: Double, val vias: List<GHPoint>) {
        val searchMeters: Double
            get() = (radiusMeters * CORNER_SEARCH_SHARE).coerceIn(ThroughRoadSnap.RING_STEP_METRES, MAX_CORNER_SEARCH_METERS)
    }

    /** A routed loop and what the stops judge it by. */
    class Loop(
        val shape: Shape,
        val path: ResponsePath,
        val route: AlternativePolicy.CachedRoute,
        /** Measured size over the wished size ([RoundTripLength.ratio]). */
        val sizeRatio: Double,
        /** Share of the distance that is road the loop already rode, its stem included. */
        val retracedShare: Double,
        /** Metres ridden again away from the start ([lassoAllowance]). */
        val lassoMetres: Double = 0.0,
    ) {
        /**
         * Route weight per metre: the trade the A-to-B router makes, travel
         * time scaled by how much the style dislikes the road. Fastest
         * prefers the quick roads; a curvy stop the bends.
         */
        val weightPerMetre: Double = path.routeWeight / max(1.0, path.distance)

        /** Lower is better: the style's own preference, then fit, then retracing. */
        val score: Double =
            weightPerMetre * (1.0 + SIZE_WEIGHT * abs(ln(sizeRatio))) * (1.0 + RETRACE_WEIGHT * retracedShare)

        /** Share of the distance in curves, as the route card reports it. */
        val curviness: Double by lazy { curvyDistanceFraction(path.points) }

        val budget: DistinctRideStyles.Budget = when {
            sizeRatio in MIN_SIZE_RATIO..MAX_SIZE_RATIO && retracedShare <= MAX_RETRACED_SHARE &&
                lassoMetres <= lassoAllowance(path.distance) -> DistinctRideStyles.Budget.STYLE
            sizeRatio in RELAXED_MIN_SIZE_RATIO..RELAXED_MAX_SIZE_RATIO && retracedShare <= RELAXED_RETRACED_SHARE &&
                lassoMetres <= lassoAllowance(path.distance, relaxed = true) -> DistinctRideStyles.Budget.RELAXED
            else -> DistinctRideStyles.Budget.NONE
        }
    }

    /** Radius of the first tier for [length]. */
    fun baseRadius(length: RoundTripLength): Double =
        max(MIN_RADIUS_METERS, length.estimatedRoadMeters / (PERIMETER_FACTOR * ROAD_FACTOR))

    /** The loop heading out along [bearing] with its corners [radiusMeters] from [start]. */
    fun shape(start: GHPoint, bearing: Double, radiusMeters: Double): Shape {
        val half = APEX_DEGREES / 2.0
        return Shape(
            bearing,
            radiusMeters,
            listOf(bearing - half, bearing + half).map { offset(start, it, radiusMeters) },
        )
    }

    /** [BEARINGS] loops around [start], rotated by tier [index]. */
    fun tier(start: GHPoint, index: Int, radiusMeters: Double): List<Shape> =
        List(BEARINGS) { shape(start, TIER_BEARING_OFFSETS[index] + it * 360.0 / BEARINGS, radiusMeters) }

    /**
     * The radius for the next tier: [radiusMeters] scaled by the median size
     * ratio [loops] measured, unless that is already close to 1, kept within
     * [MIN_RADIUS_FACTOR]..[MAX_RADIUS_FACTOR] of [baseRadius].
     */
    fun recalibrated(radiusMeters: Double, baseRadius: Double, loops: List<Loop>): Double {
        if (loops.isEmpty()) return radiusMeters
        val ratios = loops.map { it.sizeRatio }.sorted()
        val median = if (ratios.size % 2 == 1) {
            ratios[ratios.size / 2]
        } else {
            (ratios[ratios.size / 2 - 1] + ratios[ratios.size / 2]) / 2.0
        }
        if (abs(ln(median)) <= ln(1.0 + CALIBRATION_TOLERANCE)) return radiusMeters
        return (radiusMeters / median.coerceIn(0.5, 2.0))
            .coerceIn(baseRadius * MIN_RADIUS_FACTOR, baseRadius * MAX_RADIUS_FACTOR)
            .coerceAtLeast(MIN_RADIUS_METERS)
    }

    /** [path] judged as a loop for [length]; null when it is no loop (missing, empty, or it turns around). */
    fun measure(shape: Shape, length: RoundTripLength, path: ResponsePath?): Loop? {
        if (path == null || path.distance <= 0.0 || DistinctRideStyles.turnsAround(path)) return null
        val route = AlternativePolicy.cachedRoute(path)
        // Both from the same geometry, so simplification cannot skew the share.
        val ridden = route.edgeSections.sumOf { it.distance }
        val (stem, lasso) = retrace(route)
        return Loop(
            shape,
            path,
            route,
            length.ratio(path),
            if (ridden > 0.0) ((stem + lasso) / ridden).coerceIn(0.0, 1.0) else 0.0,
            lasso,
        )
    }

    /**
     * Two passes over a road at least this share of the loop apart are its
     * stem (the way out and the way home); closer ones are a lasso round a
     * corner. A start on a loop street (Skyline Circuit, Palmwoods) leaves
     * by one side of it and comes home by the other, so the street both
     * passes share is neither the loop's first nor its last road.
     */
    const val STEM_SEPARATION = 0.5

    /**
     * Metres of road a loop rides a second time: its stem ([STEM_SEPARATION])
     * and its lassos. A via splits its edge into two sections of one pass,
     * and the start splits its edge too, so a loop that leaves across one
     * half and comes home along the other the same way has ridden it once.
     */
    fun retrace(route: AlternativePolicy.CachedRoute): Pair<Double, Double> =
        retraceOf(route.edgeSections).let { it.stem to it.lasso }

    /** [stem] and [lasso] metres; [lassoPasses] maps each lasso section to its first pass. */
    private class Retrace(val stem: Double, val lasso: Double, val lassoPasses: Map<Int, Int>)

    private fun retraceOf(sections: List<AlternativePolicy.EdgeSection>): Retrace {
        val offsets = DoubleArray(sections.size)
        var total = 0.0
        sections.forEachIndexed { index, section ->
            offsets[index] = total
            total += section.distance
        }
        val firstPass = HashMap<Int, Int>()
        val lassoPasses = LinkedHashMap<Int, Int>()
        var previous = -1
        var stem = 0.0
        var lasso = 0.0
        sections.forEachIndexed { index, section ->
            val seenAt = firstPass[section.edgeId]
            val startEdgeHalf = index == sections.lastIndex && index > 0 &&
                section.edgeId == sections[0].edgeId && section.forward == sections[0].forward
            if (section.edgeId != previous && seenAt != null && !startEdgeHalf) {
                if (offsets[index] - offsets[seenAt] >= STEM_SEPARATION * total) {
                    stem += section.distance
                } else {
                    lasso += section.distance
                    lassoPasses[index] = seenAt
                }
            }
            if (seenAt == null) firstPass[section.edgeId] = index
            previous = section.edgeId
        }
        return Retrace(stem, lasso, lassoPasses)
    }

    /**
     * [loop] with the corner that made it ride a road twice moved to where
     * that road begins, or null when no corner did. A corner on a side road
     * pulls the ride off a road and back onto it (a lasso); on the road
     * itself the ride just carries on. The twice-ridden stretch is the first
     * run of lasso sections; it begins at the earliest of their first passes
     * (for a stretch ridden back the other way, the far end of the run), and
     * the corner to move is the one between the two passes.
     */
    fun unlassoed(loop: Loop): Shape? {
        val details = loop.path.pathDetails["edge_id"] ?: return null
        val sections = loop.route.edgeSections
        if (details.size != sections.size || sections.isEmpty()) return null
        val passes = retraceOf(sections).lassoPasses
        val runStart = passes.keys.minOrNull() ?: return null
        var runEnd = runStart
        var base = passes.getValue(runStart)
        // The run continues over consecutive sections, a via's split included.
        while (true) {
            val next = runEnd + 1
            val first = passes[next] ?: if (next < sections.size && sections[next].edgeId == sections[runEnd].edgeId) base else null
            if (first == null) break
            base = minOf(base, first)
            runEnd = next
        }
        val points = loop.path.points
        val from = details[base].first
        val to = details[runEnd].last
        val corners = viaPointsOf(loop.path)
        if (corners.size != loop.shape.vias.size) return null
        val moved = corners.indexOfFirst { corner ->
            val at = (0 until points.size()).minByOrNull {
                DistanceCalcEarth.DIST_EARTH.calcDist(corner.lat, corner.lon, points.getLat(it), points.getLon(it))
            } ?: -1
            at in from..to
        }
        if (moved < 0) return null
        val vias = loop.shape.vias.toMutableList()
        vias[moved] = GHPoint(points.getLat(from), points.getLon(from))
        return loop.shape.copy(vias = vias)
    }

    /**
     * The best-scoring loop of [loops] within [budget] (or tighter), at least
     * [minCurviness] curvy, that differs from every ride in [offered]; ties
     * keep the earlier loop.
     */
    fun best(
        loops: List<Loop>,
        offered: Collection<AlternativePolicy.CachedRoute>,
        budget: DistinctRideStyles.Budget,
        minCurviness: Double = Double.NEGATIVE_INFINITY,
    ): Loop? = different(loops, offered, budget)
        .filter { it.curviness >= minCurviness }
        .minByOrNull { it.score }

    /** The curviest loop of [loops] within [budget] (or tighter) that differs from every ride in [offered]. */
    fun curviest(
        loops: List<Loop>,
        offered: Collection<AlternativePolicy.CachedRoute>,
        budget: DistinctRideStyles.Budget,
    ): Loop? = different(loops, offered, budget).maxByOrNull { it.curviness }

    private fun different(
        loops: List<Loop>,
        offered: Collection<AlternativePolicy.CachedRoute>,
        budget: DistinctRideStyles.Budget,
    ): List<Loop> = loops.filter {
        it.budget.ordinal <= budget.ordinal && offered.none { shown -> DistinctRideStyles.sameRide(it.route, shown) }
    }

    /**
     * The loop a stop shows once its tiers are searched: within budget and
     * at least [minCurviness] first; then the curviest loop within budget,
     * so the slider stays as curvy as the area allows; then the same two in
     * the relaxed budget; then any different loop.
     */
    fun settle(
        loops: List<Loop>,
        offered: Collection<AlternativePolicy.CachedRoute>,
        minCurviness: Double,
    ): Loop? = best(loops, offered, DistinctRideStyles.Budget.STYLE, minCurviness)
        ?: curviest(loops, offered, DistinctRideStyles.Budget.STYLE)
        ?: best(loops, offered, DistinctRideStyles.Budget.RELAXED, minCurviness)
        ?: curviest(loops, offered, DistinctRideStyles.Budget.RELAXED)
        ?: best(loops, offered, DistinctRideStyles.Budget.NONE)

    /**
     * Resolves the seven stops of a round trip from [start], Fastest first.
     * [loop] routes one loop through its via points for a stop, or returns
     * null when it does not route; the loops of a tier are asked for at once.
     * Throws [NO_ROUND_TRIP_MESSAGE] when not even Fastest finds a loop.
     */
    suspend fun resolve(
        start: GHPoint,
        length: RoundTripLength,
        loop: suspend (style: RideStyle, shape: Shape) -> ResponsePath?,
        onResolved: (RideStyle, DistinctRideStyles.Option) -> Unit = { _, _ -> },
    ): Map<RideStyle, DistinctRideStyles.Option> {
        val base = baseRadius(length)
        var radius = base
        val offered = LinkedHashMap<RideStyle, AlternativePolicy.CachedRoute>()
        val options = LinkedHashMap<RideStyle, DistinctRideStyles.Option>()
        var previous: DistinctRideStyles.Option? = null
        var floor = Double.NEGATIVE_INFINITY
        for (style in RideStyle.entries) {
            val candidates = ArrayList<Loop>()
            var picked: Loop? = null
            for (index in TIER_BEARING_OFFSETS.indices) {
                val shapes = tier(start, index, radius)
                val loops = coroutineScope {
                    shapes.map { shape -> async { routed(style, shape, length, loop) } }.awaitAll()
                }.flatten()
                candidates += loops
                // A repaired loop's corner moved, so its size says nothing about the radius.
                radius = recalibrated(radius, base, loops.filter { it.shape in shapes })
                picked = best(candidates, offered.values, DistinctRideStyles.Budget.STYLE, floor)
                if (picked != null) break
                // The floor is worth a few more tiers, not the whole compass:
                // long loops cost seconds per tier on a phone.
                if (index + 1 >= FLOOR_TIERS && best(candidates, offered.values, DistinctRideStyles.Budget.STYLE) != null) break
            }
            val chosen = picked ?: settle(candidates, offered.values, floor)
            val option = when {
                chosen != null -> DistinctRideStyles.Option(
                    chosen.path,
                    style.curveWeight,
                    round = candidates.indexOf(chosen) + 1,
                    budget = chosen.budget,
                    loop = chosen.shape,
                )
                // Nothing different exists around this start: the stop below
                // already shows the most different loop there is.
                previous != null -> previous.copy(repeats = previous.repeats ?: RideStyle.entries[style.ordinal - 1])
                else -> throw IllegalStateException(NO_ROUND_TRIP_MESSAGE)
            }
            options[style] = option
            offered[style] = chosen?.route ?: offered.getValue(checkNotNull(option.repeats))
            chosen?.let { floor = it.curviness - CURVINESS_TOLERANCE }
            previous = option
            onResolved(style, option)
        }
        return options
    }

    /**
     * [shape] routed for [style] and measured. A loop that rides a road again
     * mid-loop is routed once more with its lasso's corner moved onto that
     * road ([unlassoed]); both stay candidates, the repair first, because the
     * repair can turn out to be a ride a lower stop already shows.
     */
    private suspend fun routed(
        style: RideStyle,
        shape: Shape,
        length: RoundTripLength,
        loop: suspend (RideStyle, Shape) -> ResponsePath?,
    ): List<Loop> {
        val first = measure(shape, length, loop(style, shape)) ?: return emptyList()
        if (first.lassoMetres <= lassoAllowance(first.path.distance)) return listOf(first)
        val moved = unlassoed(first) ?: return listOf(first)
        val second = measure(moved, length, loop(style, moved)) ?: return listOf(first)
        return if (second.lassoMetres < first.lassoMetres) listOf(second, first) else listOf(first)
    }

    /** The via points [path] rode through (its waypoints without the start and the end). */
    fun viaPointsOf(path: ResponsePath): List<GHPoint> {
        val waypoints = path.waypoints ?: return emptyList()
        if (waypoints.size() <= 2) return emptyList()
        return (1 until waypoints.size() - 1).map { GHPoint(waypoints.getLat(it), waypoints.getLon(it)) }
    }

    private fun offset(start: GHPoint, bearingDegrees: Double, distanceMeters: Double): GHPoint {
        val bearing = Math.toRadians(bearingDegrees)
        val metresPerDegreeLat = DistanceCalcEarth.METERS_PER_DEGREE
        val metresPerDegreeLon = metresPerDegreeLat * cos(Math.toRadians(start.lat))
        return GHPoint(
            start.lat + distanceMeters * cos(bearing) / metresPerDegreeLat,
            start.lon + distanceMeters * sin(bearing) / metresPerDegreeLon,
        )
    }
}
