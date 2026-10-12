package com.curvemaps.app.routing

import com.carrotsearch.hppc.IntHashSet
import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.EnumEncodedValue
import com.graphhopper.routing.ev.RoadAccess
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.Surface
import com.graphhopper.routing.ev.UrbanDensity
import com.graphhopper.routing.querygraph.VirtualEdgeIteratorState
import com.graphhopper.routing.weighting.TurnCostProvider
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.storage.Graph
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.EdgeIteratorState.REVERSE_STATE
import com.graphhopper.util.FetchMode
import com.graphhopper.util.GHUtility
import com.graphhopper.util.PointList
import java.util.LinkedHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Request-hint key carrying the [RideStyle.level] of a user route. */
internal const val MOTO_RIDE_STYLE = "moto_ride_style"

/**
 * Request hint overriding the ride style's [RideStyle.curveWeight]: a slider
 * stop that repeats a lower stop's ride searches again a little curvier
 * ([DistinctRideStyles]). Absent on every ordinary request.
 */
internal const val MOTO_CURVE_WEIGHT = "moto_curve_weight"

/** Edge IDs of sibling routes already accepted for this carousel. */
internal const val MOTO_SIBLING_EDGES = "moto_sibling_edges"

/** Added fastest-time multiples for edges in [MOTO_SIBLING_EDGES]. */
internal const val MOTO_SIBLING_EDGE_PENALTY = "moto_sibling_edge_penalty"

/** Request hint that hard-blocks roads with a known unpaved surface. */
internal const val BLOCK_UNPAVED = "block_unpaved"

/** Request hint that strongly discourages motorway and trunk roads. */
internal const val AVOID_MOTORWAYS = "avoid_motorways"

/** Request hint ([RideFactors.elevation]): hilly roads count as fun roads on curvy stops. */
internal const val MOTO_HILLS = "moto_hills"

/** Request hint ([RideFactors.stopsAndTowns]): stops cost time and towns weigh more. */
internal const val MOTO_STOPS_AND_TOWNS = "moto_stops_and_towns"

/**
 * Weighting for one [RideStyle] stop plus the Route settings road toggles.
 *
 * Every term is a non-negative multiple of the edge's fastest travel time
 * `wFastest` (seconds), so the units never mix:
 *
 * ```
 * weight = wFastest
 *        + avoidMotorways             ? 9 * wFastest on motorway/trunk
 *        + curvy style                ? 9 * wFastest on destination-only roads
 *        + 1.5 * curveWeight * (1 - funExposure)^2 * wFastest
 *        + stopsAndTowns              ? 0.5 * wFastest residential, 1.5 * wFastest city
 *        + siblingPenalty * wFastest on roads already used by an accepted card (off small loops)
 *        + stopsAndTowns              ? 20 s per stop on the edge (absolute)
 * ```
 *
 * `funExposure` is the curve exposure, or with [hills] the hill-adjusted
 * [hillyFunExposure]: a steep straight road keeps a sixteenth of the
 * straight-road penalty, like a properly curvy one. Town streets
 * ([TOWN_STREET_CLASSES]) earn none of it and known unpaved surfaces
 * [UNPAVED_CURVE_CREDIT] of it ([funCredit]). Junction pieces earn none
 * either: links, roundabouts and every edge on a small loop
 * ([SmallLoopEdges]). The curve score is per edge, so a 46 m slip lane or a
 * slightly kinked 18 m connector scores as curvy as a mountain road; with
 * credit there, a curvy stop left the road for any junction structure
 * shorter than the multiple it saved on the straight road beside it. Without
 * it, every way round a small loop costs the same multiple of its travel
 * time and the faster one wins. The stop term is the
 * one absolute addition: a stop costs the same wait however fast the road
 * is. It stays non-negative, so the Fastest lower bound is still admissible.
 *
 * The old dial blended in `w_custom - w_fastest`, which carried the custom
 * model's 90 s/km distance influence and its motorway priority. Scaled by the
 * dial, the distance term capped the curve preference (a fully curvy road could
 * never be more than 1.6x the length of a fast straight one, however far the
 * dial turned) and the motorway priority made the first click a hidden
 * "avoid motorways" switch. Neither is scaled any more: motorway avoidance is
 * the explicit [avoidMotorways] toggle, and the curve preference is the only
 * thing the dial changes. The 9x factors mirror the custom model's 0.1
 * priorities.
 *
 * The straightness term is squared so a moderately curvy road counts as
 * mostly curvy: a range road with exposure 0.5 keeps a quarter of the
 * straight-road penalty instead of half. With the linear term, the D'Aguilar
 * loop from Palmwoods to Brisbane (150 min, 23% curvy) could never beat the
 * 91 min coastal route at Max twisty, because it kept as many straight
 * minutes and only added part-curvy ones; the measured cost ratio needed a
 * ~50x straight-road penalty. Squaring lets a curvy road win on the roads
 * riders call twisty without a penalty so large that it weakens the search.
 *
 * Hard constraints apply at every stop: `car_access == false` (including
 * reverse reads), the custom model's priority-0 roads (private, high track
 * grades), and known unpaved surfaces when [blockUnpaved] is set all return
 * [Double.POSITIVE_INFINITY]. They are enforced HERE, not by a graph filter:
 * the flexible (non-CH) solver's edge filter only checks `!inSubnetwork` and
 * that the weight is finite. Reading EVs on virtual edges is safe because the
 * QueryGraph forwards EV reads to the original edge.
 *
 * [calcMinWeightPerDistance] uses Fastest's lower bound, which stays
 * admissible because every added term is non-negative. [calcEdgeMillis]
 * always reports the physical fastest travel time, so ETA never depends on
 * the subjective preference.
 */
class ComplexityWeighting(
    private val fastest: Weighting,
    private val priority: (EdgeIteratorState, Boolean) -> Double,
    private val style: RideStyle,
    private val accessEnc: BooleanEncodedValue,
    private val surfaceEnc: EnumEncodedValue<Surface>,
    private val roadClassEnc: EnumEncodedValue<RoadClass>,
    private val roadAccessEnc: EnumEncodedValue<RoadAccess>,
    private val blockUnpaved: Boolean,
    private val avoidMotorways: Boolean,
    private val siblingEdgeIds: Set<Int>,
    private val siblingEdgePenalty: Double,
    /** The straight-road penalty scale; the style's own unless [MOTO_CURVE_WEIGHT] overrides it. */
    private val curveWeight: Double = style.curveWeight,
    /** [RideFactors.elevation]; a 2D graph has no relief, so this then changes nothing. */
    private val hills: Boolean = false,
    /** [RideFactors.stopsAndTowns]: [MOTO_STOPS_DETAIL] stops and `urban_density` towns. */
    private val stopsAndTowns: Boolean = false,
    /** Null on graphs imported without urban density; towns then add nothing. */
    private val urbanDensityEnc: EnumEncodedValue<UrbanDensity>? = null,
    /** Base graph for [virtualEdgeShare]; null charges QueryGraph pieces their whole edge's stops. */
    private val baseGraph: Graph? = null,
    /** GraphHopper's `road_class_link` (slip lanes and ramps); null gives links ordinary credit. */
    private val roadClassLinkEnc: BooleanEncodedValue? = null,
    /** GraphHopper's `roundabout`; null gives roundabouts ordinary credit. */
    private val roundaboutEnc: BooleanEncodedValue? = null,
    /** Junction loops on the base graph; null gives their edges ordinary credit. */
    private val smallLoops: SmallLoopEdges? = null,
    /**
     * The loaded graph's per-edge curve factors and stop counts, shared by
     * every request ([loadMotoGraphHopper]); null keeps them per request.
     */
    private val edgeFactors: EdgeFactors? = null,
) : Weighting {

    // Read for every edge a penalised search relaxes: no boxing per lookup.
    private val siblingEdges = IntHashSet(siblingEdgeIds.size).apply { siblingEdgeIds.forEach { add(it) } }

    /**
     * Factors of edges [edgeFactors] does not hold: QueryGraph's virtual
     * edges (pieces of an edge split at a snapped point), or every edge
     * without it. Edge IDs are unique inside a request's graph and each
     * request gets its own weighting. Directional reads share an edge ID and
     * curve exposure is symmetric, so one value serves both directions. Keep
     * only recent factors to cap per-request memory.
     */
    private val curveFactorCache = object : LinkedHashMap<Int, Double>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Double>?): Boolean =
            size > MAX_CACHED_CURVE_FACTORS
    }

    /**
     * Half stops per edge and STORAGE-relative travel direction, keyed
     * `edge * 2 + againstStorage`, already scaled by [virtualEdgeShare].
     * Bidirectional searches read one edge through states of either
     * orientation; REVERSE_STATE (relative to storage, also for QueryGraph's
     * virtual edges) XOR [reverse] makes the key independent of that.
     */
    private val halfStopsCache = object : LinkedHashMap<Int, Double>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Double>?): Boolean =
            size > MAX_CACHED_STOP_COUNTS
    }

    override fun calcMinWeightPerDistance(): Double = fastest.calcMinWeightPerDistance()

    override fun calcEdgeWeight(edgeState: EdgeIteratorState, reverse: Boolean): Double {
        val accessible = if (reverse) edgeState.getReverse(accessEnc) else edgeState.get(accessEnc)
        if (!accessible) return Double.POSITIVE_INFINITY
        // GraphHopper marks the virtual edge a `pass_through` via point was
        // reached by: the next leg must not turn back onto it. Only the
        // look-around search of DistinctRideStyles sets pass-through, and it
        // wants a loop through its via point, never an out-and-back spur, so
        // a via on a dead end fails to route instead of producing one.
        if (edgeState.get(EdgeIteratorState.UNFAVORED_EDGE)) return Double.POSITIVE_INFINITY
        if (blockUnpaved) {
            val surface = if (reverse) edgeState.getReverse(surfaceEnc) else edgeState.get(surfaceEnc)
            if (surface in UNPAVED_SURFACES) return Double.POSITIVE_INFINITY
        }
        val wFastest = fastest.calcEdgeWeight(edgeState, reverse)
        if (!wFastest.isFinite()) return Double.POSITIVE_INFINITY
        // The custom model's priority-0 edges (private roads, high track
        // grades) are hard blocks at every stop. Its soft priorities are NOT
        // blended in; see the class KDoc.
        if (!(priority(edgeState, reverse) > 0.0)) return Double.POSITIVE_INFINITY

        var multiple = 1.0
        if (avoidMotorways) {
            val roadClass = if (reverse) edgeState.getReverse(roadClassEnc) else edgeState.get(roadClassEnc)
            if (roadClass == RoadClass.MOTORWAY || roadClass == RoadClass.TRUNK) multiple += AVOIDED_ROAD_PENALTY
        }
        if (curveWeight > 0.0) {
            // Curvy stops explore side roads; keep destination-only roads
            // (gated estates, access lanes) as discouraged as the model asks.
            val roadAccess = if (reverse) edgeState.getReverse(roadAccessEnc) else edgeState.get(roadAccessEnc)
            if (roadAccess == RoadAccess.DESTINATION) multiple += AVOIDED_ROAD_PENALTY
            multiple += RideStyle.STRAIGHT_ROAD_PENALTY * curveWeight * curvePenaltyFactor(edgeState)
        }
        var stopSeconds = 0.0
        if (stopsAndTowns) {
            multiple += townPenalty(edgeState, reverse)
            stopSeconds = STOP_PENALTY_SECONDS / 2.0 * halfStops(edgeState, reverse)
        }
        // A road penalty makes a used road dearer; junction structure is not
        // road, and penalising one way round a small loop but not the other
        // made penalised searches jog round it.
        if (siblingEdges.contains(edgeState.edge) && smallLoops?.isOnSmallLoop(edgeState.edge) != true) {
            multiple += siblingEdgePenalty
        }
        return wFastest * multiple + stopSeconds
    }

    private fun townPenalty(edgeState: EdgeIteratorState, reverse: Boolean): Double {
        val enc = urbanDensityEnc ?: return 0.0
        return when (if (reverse) edgeState.getReverse(enc) else edgeState.get(enc)) {
            UrbanDensity.CITY -> CITY_PENALTY
            UrbanDensity.RESIDENTIAL -> RESIDENTIAL_PENALTY
            else -> 0.0
        }
    }

    /**
     * Half stops met travelling the edge. A forward read uses `getValue`,
     * which decodes just this key in the state's direction. A [reverse] read
     * takes the stored per-direction value from the KV map instead: never
     * `detach(true)`, which QueryGraph's VirtualEdgeIterator rejects ("Not yet
     * supported") on every route that starts or ends mid-edge. REVERSE_STATE
     * is relative to storage for real and virtual edges alike.
     */
    private fun halfStops(edgeState: EdgeIteratorState, reverse: Boolean): Double {
        val againstStorage = edgeState.get(REVERSE_STATE) != reverse
        val edge = edgeState.edge
        // A base-graph edge is whole (share 1): its count is the graph's.
        if (edgeFactors != null && baseGraph != null && edgeFactors.covers(edge)) {
            val known = edgeFactors.halfStops(edge, againstStorage)
            if (known >= 0) return known.toDouble()
            val count = parseHalfStops(storedHalfStops(edgeState, reverse, againstStorage))
            edgeFactors.putHalfStops(edge, againstStorage, count)
            return count.toDouble()
        }
        val key = edge * 2 + if (againstStorage) 1 else 0
        synchronized(halfStopsCache) { halfStopsCache[key] }?.let { return it }
        // detach(false) unwraps a VirtualEdgeIterator to its current
        // VirtualEdgeIteratorState, so a split edge's piece is recognised.
        val share = baseGraph?.let { virtualEdgeShare(edgeState.detach(false), it) } ?: 1.0
        val computed = parseHalfStops(storedHalfStops(edgeState, reverse, againstStorage)) * share
        synchronized(halfStopsCache) { return halfStopsCache.getOrPut(key) { computed } }
    }

    private fun storedHalfStops(edgeState: EdgeIteratorState, reverse: Boolean, againstStorage: Boolean): Any? =
        if (!reverse) {
            edgeState.getValue(MOTO_STOPS_DETAIL)
        } else {
            edgeState.keyValues[MOTO_STOPS_DETAIL]?.let { if (againstStorage) it.bwd else it.fwd }
        }

    override fun calcEdgeMillis(edgeState: EdgeIteratorState, reverse: Boolean): Long =
        fastest.calcEdgeMillis(edgeState, reverse)

    override fun calcTurnWeight(inEdge: Int, viaNode: Int, outEdge: Int): Double =
        fastest.calcTurnWeight(inEdge, viaNode, outEdge)

    override fun calcTurnMillis(inEdge: Int, viaNode: Int, outEdge: Int): Long =
        fastest.calcTurnMillis(inEdge, viaNode, outEdge)

    override fun hasTurnCosts(): Boolean = fastest.hasTurnCosts()

    /**
     * Squared straightness `(1 - exposure)^2` in 0..1. The radius score adds
     * corner tightness without discarding the existing sustained-bend signal
     * that keeps broad, curved roads competitive. A town street or a junction
     * piece (a link, a roundabout) skips the geometry: it pays the whole
     * straight-road penalty however it bends. So does an edge on a small loop
     * ([SmallLoopEdges]), checked only when its geometry would earn credit.
     *
     * A base-graph edge's factor is the graph's ([edgeFactors]), measured
     * along its storage direction: summed the other way round its curves can
     * differ in the last bit, and the factor must not depend on which way an
     * earlier search first met the edge.
     */
    private fun curvePenaltyFactor(edgeState: EdgeIteratorState): Double {
        val edgeId = edgeState.edge
        if (edgeFactors != null && baseGraph != null && edgeFactors.covers(edgeId)) {
            val known = edgeFactors.curveFactor(edgeId, hills)
            if (!known.isNaN()) return known
            return computeCurvePenaltyFactor(edgeState, alongStorage = true)
                .also { edgeFactors.putCurveFactor(edgeId, hills, it) }
        }
        synchronized(curveFactorCache) {
            curveFactorCache[edgeId]?.let { return it }
        }
        val computed = computeCurvePenaltyFactor(edgeState, alongStorage = false)
        synchronized(curveFactorCache) {
            return curveFactorCache[edgeId] ?: computed.also { curveFactorCache[edgeId] = it }
        }
    }

    private fun computeCurvePenaltyFactor(edgeState: EdgeIteratorState, alongStorage: Boolean): Double {
        if (edgeState.get(roadClassEnc) in TOWN_STREET_CLASSES || isJunctionPiece(edgeState)) return 1.0
        val geometry = edgeState.fetchWayGeometry(FetchMode.ALL)
        if (alongStorage && edgeState.get(REVERSE_STATE)) geometry.reverse()
        val curve = combinedCurveExposure(geometry, edgeState.distance)
        val exposure = if (hills) hillyFunExposure(curve, hillExposure(geometry, edgeState.distance)) else curve
        val straightness = 1.0 - funCredit(exposure, edgeState.get(surfaceEnc))
        return if (straightness < 1.0 && smallLoops?.isOnSmallLoop(baseEdge(edgeState)) == true) 1.0 else straightness * straightness
    }

    private fun isJunctionPiece(edgeState: EdgeIteratorState): Boolean =
        roadClassLinkEnc?.let { edgeState.get(it) } == true || roundaboutEnc?.let { edgeState.get(it) } == true

    /** The base-graph edge behind [edgeState]: itself, or the edge a QueryGraph piece was split from. */
    private fun baseEdge(edgeState: EdgeIteratorState): Int {
        val graph = baseGraph ?: return edgeState.edge
        if (edgeState.edge < graph.edges) return edgeState.edge
        val piece = edgeState.detach(false)
        return if (piece is VirtualEdgeIteratorState) GHUtility.getEdgeFromEdgeKey(piece.originalEdgeKey) else edgeState.edge
    }

    // Must match Weighting.isValidName's regex [|_a-z]+.
    override fun getName(): String = "moto_ride_style"

    internal companion object {
        /** Added multiple for avoided road types; mirrors the model's 0.1 priority. */
        const val AVOIDED_ROAD_PENALTY = 9.0

        /** Bounded LRU cap for request-local edge curve factors. */
        private const val MAX_CACHED_CURVE_FACTORS = 4_096

        /** Bounded LRU cap for request-local per-edge stop counts. */
        private const val MAX_CACHED_STOP_COUNTS = 4_096

        /**
         * Added seconds per stop with [RideFactors.stopsAndTowns]: an average
         * signal wait plus slowing and pulling away again. 20 s makes a
         * suburban route through ten sets of lights cost about three extra
         * minutes, enough to prefer a free-flowing road of similar length.
         */
        const val STOP_PENALTY_SECONDS = 20.0

        /**
         * Road classes whose bends earn no curve credit on curvy stops: an
         * estate crescent or a cul-de-sac loop is not a twisty road. With the
         * credit, a 100-corridor review found curvy stops weaving through
         * estates and street grids (Max twisty rode 3.3 km of them per ride
         * against Fastest's 0.9, and picked the curvy ones): at Max twisty a
         * crescent scoring 0.8 cost 1.2x its time while a straight main road
         * cost 5.5x, so a 775 m wiggle beat 280 m of the main road. They still
         * route; they just pay the straight-road penalty like any town road.
         */
        val TOWN_STREET_CLASSES: Set<RoadClass> = setOf(RoadClass.RESIDENTIAL, RoadClass.LIVING_STREET, RoadClass.SERVICE)

        /**
         * Share of its fun exposure a known unpaved road earns ([UNPAVED_SURFACES]).
         * Gravel follows the terrain: the review measured unpaved roads 23%
         * curvy against 8% for sealed ones, and 31% of Max twisty's curvy km on
         * gravel. With half the credit, at exposure 0.6 an equally curvy sealed
         * road pays a third of the gravel road's straight-road penalty (before
         * the model's 30 km/h gravel speed), so a good sealed road wins and gravel still
         * carries a ride where it is far curvier or the only way. Block unpaved
         * roads removes it altogether.
         */
        const val UNPAVED_CURVE_CREDIT = 0.5

        /** The curve credit an edge's [exposure] earns on [surface]. */
        fun funCredit(exposure: Double, surface: Surface): Double =
            if (surface in UNPAVED_SURFACES) UNPAVED_CURVE_CREDIT * exposure else exposure

        /** Added fastest-time multiple in residential areas (urban_density). */
        const val RESIDENTIAL_PENALTY = 0.5

        /** Added fastest-time multiple in city areas (urban_density). */
        const val CITY_PENALTY = 1.5

        /**
         * Explicitly unpaved GraphHopper surfaces. MISSING and OTHER stay
         * routable because they do not prove a road is unpaved; cobblestone,
         * paving stones, and wood are surfaced roads rather than unpaved ones.
         */
        val UNPAVED_SURFACES = setOf(
            Surface.UNPAVED,
            Surface.COMPACTED,
            Surface.FINE_GRAVEL,
            Surface.GRAVEL,
            Surface.GROUND,
            Surface.DIRT,
            Surface.GRASS,
            Surface.SAND,
        )
    }
}

/**
 * Returns a length-weighted curve exposure in the range 0..1.
 *
 * Each three-point window defines a circumcircle. Its radius classifies the
 * two adjacent segments. A segment shared by two windows uses the tighter
 * radius, as a tight turn commonly meets a longer straight segment. Curves
 * below 30 m radius get full exposure. Curves from 30 m through 175 m get
 * progressively smaller fixed weights. Straighter geometry gets zero.
 *
 * Very short adjacent segments do not define a curve. This removes isolated
 * OSM digitizing jiggles before they can outweigh sustained road geometry.
 */
internal fun radiusCurveExposure(points: PointList): Double =
    curveExposure(points, Double.NaN)

internal fun combinedCurveExposure(points: PointList, edgeDistanceMeters: Double): Double =
    curveExposure(points, edgeDistanceMeters)

private fun curveExposure(points: PointList, edgeDistanceMeters: Double): Double {
    if (points.size() < 3) return 0.0

    val longitudeScale = cos(
        Math.toRadians((points.getLat(0) + points.getLat(points.size() - 1)) / 2.0),
    )

    fun projectedX(first: Int, second: Int): Double {
        var deltaLongitude = points.getLon(second) - points.getLon(first)
        if (deltaLongitude > 180.0) deltaLongitude -= 360.0
        if (deltaLongitude < -180.0) deltaLongitude += 360.0
        return deltaLongitude * longitudeScale
    }

    fun projectedY(first: Int, second: Int): Double =
        points.getLat(second) - points.getLat(first)

    fun distance(first: Int, second: Int): Double =
        hypot(projectedX(first, second), projectedY(first, second)) * METERS_PER_DEGREE

    fun triangleWeight(start: Int, first: Double, second: Double): Double {
        if (first < MIN_CURVE_SEGMENT_METERS || second < MIN_CURVE_SEGMENT_METERS) return 0.0
        return curveRadiusWeight(circumradiusMeters(first, second, distance(start, start + 2)))
    }

    val includeHeading = edgeDistanceMeters.isFinite()
    var previousHeading = Double.NaN
    var totalTurn = 0.0
    fun addHeading(segmentStart: Int) {
        if (!includeHeading) return
        val x = projectedX(segmentStart, segmentStart + 1)
        val y = projectedY(segmentStart, segmentStart + 1)
        if (abs(x) + abs(y) < 1e-12) return
        val heading = atan2(y, x)
        if (previousHeading.isFinite()) {
            var turn = abs(heading - previousHeading)
            if (turn > PI) turn = 2.0 * PI - turn
            totalTurn += turn
        }
        previousHeading = heading
    }

    val firstLength = distance(0, 1)
    var secondLength = distance(1, 2)
    var previousWeight = triangleWeight(0, firstLength, secondLength)
    var totalLength = firstLength + secondLength
    var weightedCurvature = firstLength * previousWeight
    addHeading(0)
    addHeading(1)

    for (triangleStart in 1 until points.size() - 2) {
        val nextLength = distance(triangleStart + 1, triangleStart + 2)
        val nextWeight = triangleWeight(triangleStart, secondLength, nextLength)
        weightedCurvature += secondLength * maxOf(previousWeight, nextWeight)
        totalLength += nextLength
        secondLength = nextLength
        previousWeight = nextWeight
        addHeading(triangleStart + 1)
    }
    weightedCurvature += secondLength * previousWeight

    val radiusExposure = if (totalLength <= 0.0) {
        0.0
    } else {
        (weightedCurvature / (totalLength * MAX_CURVE_WEIGHT)).coerceIn(0.0, 1.0)
    }
    if (!includeHeading) return radiusExposure
    val distanceKm = max(edgeDistanceMeters / 1_000.0, 0.05)
    val headingExposure = (totalTurn / distanceKm / PI).coerceIn(0.0, 1.0)
    return maxOf(radiusExposure, headingExposure)
}

/**
 * Circumradius of the triangle with side lengths [first], [second] and
 * [chord] (metres), or infinity for collinear or degenerate triples.
 */
internal fun circumradiusMeters(first: Double, second: Double, chord: Double): Double {
    if (chord <= 0.0) return Double.POSITIVE_INFINITY
    val denominatorSquared =
        (first + second + chord) *
            (second + chord - first) *
            (chord + first - second) *
            (first + second - chord)
    if (!denominatorSquared.isFinite() || denominatorSquared <= MIN_AREA_TERM) return Double.POSITIVE_INFINITY
    return first * second * chord / sqrt(denominatorSquared)
}

/** Radius bands: tighter corners weigh more; 175 m and wider is straight. */
internal fun curveRadiusWeight(radius: Double): Double = when {
    radius < 30.0 -> 2.0
    radius < 60.0 -> 1.6
    radius < 100.0 -> 1.3
    radius < CURVE_RADIUS_LIMIT_METERS -> 1.0
    else -> 0.0
}

internal const val CURVE_RADIUS_LIMIT_METERS = 175.0
internal const val MIN_CURVE_SEGMENT_METERS = 5.0
private const val MIN_AREA_TERM = 1e-6
private const val MAX_CURVE_WEIGHT = 2.0
internal const val METERS_PER_DEGREE = 111_195.0

/**
 * Travel-time weighting: the Fastest stop and the base term of every ride style.
 *
 * [speedForEdge] returns the motorcycle custom model's effective speed in
 * kilometres per hour. The model's speed limits remain part of ETA (including
 * its 0.9× average-speed adjustment and rough-surface cap), while motorcycle
 * road preferences live only in [ComplexityWeighting]. Consequently Fastest
 * means "minimise expected riding time", not "interpret km/h as m/s".
 */
internal class FastestWeighting(
    private val speedForEdge: (EdgeIteratorState, Boolean) -> Double,
    private val maxSpeedKmh: Double,
    private val turnCostProvider: TurnCostProvider,
    private val accessEnc: BooleanEncodedValue,
) : Weighting {

    override fun calcMinWeightPerDistance(): Double = KMH_TO_SECONDS_PER_METRE / maxSpeedKmh

    override fun calcEdgeWeight(edgeState: EdgeIteratorState, reverse: Boolean): Double {
        val accessible = if (reverse) edgeState.getReverse(accessEnc) else edgeState.get(accessEnc)
        if (!accessible) return Double.POSITIVE_INFINITY
        val speedKmh = speedForEdge(edgeState, reverse)
        if (speedKmh <= 0.0) return Double.POSITIVE_INFINITY
        return edgeState.distance * KMH_TO_SECONDS_PER_METRE / speedKmh
    }

    override fun calcEdgeMillis(edgeState: EdgeIteratorState, reverse: Boolean): Long {
        val seconds = calcEdgeWeight(edgeState, reverse)
        return if (seconds.isFinite()) (seconds * 1_000.0).roundToLong() else Long.MAX_VALUE
    }

    override fun calcTurnWeight(inEdge: Int, viaNode: Int, outEdge: Int): Double =
        turnCostProvider.calcTurnWeight(inEdge, viaNode, outEdge)

    override fun calcTurnMillis(inEdge: Int, viaNode: Int, outEdge: Int): Long =
        turnCostProvider.calcTurnMillis(inEdge, viaNode, outEdge)

    override fun hasTurnCosts(): Boolean = turnCostProvider !== TurnCostProvider.NO_TURN_COST_PROVIDER

    override fun getName(): String = "fastest"

    private companion object {
        /** Converts distance(m) / speed(km/h) to seconds. */
        const val KMH_TO_SECONDS_PER_METRE = 3.6
    }
}
