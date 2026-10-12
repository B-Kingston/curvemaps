package com.curvemaps.app.routing

import com.graphhopper.ResponsePath
import com.graphhopper.routing.querygraph.VirtualEdgeIteratorState
import com.graphhopper.storage.Graph
import com.graphhopper.util.DistanceCalcEarth
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.PointList
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Edge KV key written by `tools/gh/MotoGraphImport.java`: the number of HALF
 * stops (traffic signals, stop and give-way signs, mini roundabouts) a rider
 * meets on the edge in each travel direction, as a decimal string; absent when
 * zero. Controls on an edge's end node count half, so a ride through a
 * signalled junction (two edges meeting there) meets exactly one stop.
 */
const val MOTO_STOPS_DETAIL = "moto_stops"

/** Parses a [MOTO_STOPS_DETAIL] value; anything malformed counts as no stop. */
internal fun parseHalfStops(value: Any?): Int =
    (value as? String)?.trim()?.toIntOrNull()?.coerceIn(0, MAX_HALF_STOPS_PER_EDGE) ?: 0

/**
 * The share (0..1) of its original edge that [edge] covers. QueryGraph splits
 * an edge at a snapped start, end or via point and copies the whole edge's KV
 * values onto each piece; the import stores stop counts, not positions, so a
 * piece gets its length share of them instead of all of them (a mid-edge via
 * point would otherwise count the edge's stops twice). 1 for real edges.
 */
internal fun virtualEdgeShare(edge: EdgeIteratorState, baseGraph: Graph): Double {
    if (edge !is VirtualEdgeIteratorState) return 1.0
    val whole = baseGraph.getEdgeIteratorStateForKey(edge.originalEdgeKey).distance
    return if (whole > 0.0) (edge.distance / whole).coerceIn(0.0, 1.0) else 1.0
}

/**
 * Total elevation change (metres, up plus down) along a 3D point list, or 0
 * for a 2D one. The import's Ramer smoothing already removed sub-5 m wiggles
 * inside each edge, so consecutive differences are the road's real relief.
 */
internal fun reliefMeters(points: PointList): Double {
    if (!points.is3D || points.size() < 2) return 0.0
    var relief = 0.0
    var previous = points.getEle(0)
    for (index in 1 until points.size()) {
        val elevation = points.getEle(index)
        if (elevation.isFinite() && previous.isFinite()) relief += abs(elevation - previous)
        previous = elevation
    }
    return relief
}

/**
 * How hilly an edge is, 0..1: its relief per kilometre mapped linearly from
 * [HILL_FLAT_METERS_PER_KM] (flat, 0) to [HILL_FULL_METERS_PER_KM] (a sustained
 * 7% grade or steeper, 1). Short edges are measured over at least
 * [HILL_MIN_DISTANCE_METERS], so a few metres of SRTM noise between two close
 * junctions cannot read as a mountain pass. 2D graphs return 0.
 *
 * SRTM is a surface model (trees and roofs included): measured on the real
 * Queensland graph, a flat coastal road near Caboolture still shows 10-20 m/km
 * on a third of its length, while the Mt Glorious climb has a third above
 * 45 m/km. The flat threshold sits above that noise.
 */
internal fun hillExposure(points: PointList, edgeDistanceMeters: Double): Double {
    if (!points.is3D) return 0.0
    val kilometres = max(edgeDistanceMeters, HILL_MIN_DISTANCE_METERS) / 1_000.0
    val perKm = reliefMeters(points) / kilometres
    return ((perKm - HILL_FLAT_METERS_PER_KM) / (HILL_FULL_METERS_PER_KM - HILL_FLAT_METERS_PER_KM))
        .coerceIn(0.0, 1.0)
}

/**
 * Curve exposure with hills folded in: `1 - (1 - curve) * (1 - share * hill)`.
 * A fully hilly straight road counts like a road of exposure [HILL_FUN_SHARE]
 * (0.75: a properly curvy road); a road that is already curvy gains little,
 * and a flat road is unchanged. Stays in 0..1 and never below [curveExposure].
 *
 * Calibrated with `RideFactorsProbe` on the real Queensland graph: at 0.5 the
 * factor moved almost no ride (South East Queensland's curvy roads are its
 * hilly roads, so curves already won them); at 0.75 Max twisty Brisbane to
 * Toowoomba trades a quarter of its roads for 2,100 m of climbing instead of
 * 1,700 m, while corridors without straight hilly roads keep their rides.
 */
internal fun hillyFunExposure(curveExposure: Double, hillExposure: Double): Double {
    val curve = curveExposure.coerceIn(0.0, 1.0)
    val hill = hillExposure.coerceIn(0.0, 1.0)
    return 1.0 - (1.0 - curve) * (1.0 - HILL_FUN_SHARE * hill)
}

/**
 * Total climb (metres) along a route's 3D points, counting a rise only once
 * it exceeds [threshold] from the last low point, so SRTM's metre-scale noise
 * and roof/tree bumps are not summed into hundreds of phantom metres. (The
 * raw sum GraphHopper reports as `ascend` read 141 m on a flat 18 km road
 * near Caboolture; this reads 70 m there and keeps real range climbs.) 0 for
 * 2D points.
 */
internal fun routeClimbMeters(points: PointList, threshold: Double = CLIMB_HYSTERESIS_METERS): Double {
    if (!points.is3D || points.size() < 2) return 0.0
    var climb = 0.0
    var low = Double.NaN
    var high = Double.NaN
    var rising = true
    for (index in 0 until points.size()) {
        val elevation = points.getEle(index)
        if (!elevation.isFinite()) continue
        if (low.isNaN()) {
            low = elevation
            high = elevation
            continue
        }
        if (rising) {
            if (elevation > high) {
                high = elevation
            } else if (high - elevation >= threshold) {
                // A confirmed crest: bank the rise from the last low.
                if (high - low >= threshold) climb += high - low
                rising = false
                low = elevation
            }
        } else {
            if (elevation < low) {
                low = elevation
            } else if (elevation - low >= threshold) {
                rising = true
                high = elevation
            }
        }
    }
    if (rising && high - low >= threshold) climb += high - low
    return climb
}

/**
 * Whole stops along a route, from the per-edge [MOTO_STOPS_DETAIL] path detail
 * (one detail per edge, travel direction), or null when the route was not
 * requested with that detail.
 */
internal fun routeStopCount(path: ResponsePath): Int? {
    val details = path.pathDetails[MOTO_STOPS_DETAIL] ?: return null
    val halves = details.sumOf { (it.value as? Number)?.toDouble() ?: 0.0 }
    return (halves / 2.0).roundToInt()
}

/** Path detail naming each edge's surface (GraphHopper's `surface` encoded value), for [routeGravelMeters]. */
internal const val SURFACE_DETAIL = "surface"

/** Less known gravel than this is a driveway or a car park, not worth a note on the card. */
internal const val MIN_GRAVEL_NOTE_METERS = 100.0

private val UNPAVED_SURFACE_NAMES = ComplexityWeighting.UNPAVED_SURFACES.map { it.toString() }.toSet()

/** True for a [SURFACE_DETAIL] value Block unpaved roads removes ([ComplexityWeighting.UNPAVED_SURFACES]). */
internal fun isUnpavedSurface(value: Any?): Boolean = value?.toString() in UNPAVED_SURFACE_NAMES

/**
 * Metres of known unpaved road along [path] (the surfaces Block unpaved roads
 * removes, [ComplexityWeighting.UNPAVED_SURFACES]), from its [SURFACE_DETAIL]
 * path detail; null when the route was not requested with that detail.
 */
internal fun routeGravelMeters(path: ResponsePath): Double? {
    val details = path.pathDetails[SURFACE_DETAIL] ?: return null
    val points = path.points
    var meters = 0.0
    for (detail in details) {
        if (!isUnpavedSurface(detail.value)) continue
        for (index in detail.first until minOf(detail.last, points.size() - 1)) {
            meters += DistanceCalcEarth.DIST_EARTH.calcDist(
                points.getLat(index), points.getLon(index), points.getLat(index + 1), points.getLon(index + 1),
            )
        }
    }
    return meters
}

/**
 * The card's gravel note, "400 m gravel", "4.7 km gravel" or "23 km gravel"
 * (100 m steps, one decimal up to 10 km, whole kilometres above); null under
 * [MIN_GRAVEL_NOTE_METERS].
 */
internal fun gravelText(meters: Double): String? = when {
    meters < MIN_GRAVEL_NOTE_METERS -> null
    meters < 950.0 -> "${(meters / 100.0).roundToInt() * 100} m gravel"
    meters < 9_950.0 -> "%.1f km gravel".format(Locale.US, meters / 1000.0)
    else -> "${(meters / 1000.0).roundToInt()} km gravel"
}

/**
 * The route card's measured extras: the gravel the ride includes ("4.7 km
 * gravel", whatever the settings: curvy styles may take gravel where it is far
 * curvier, and Block unpaved roads removes it), then what each enabled
 * [factors] measured: "640 m climb", "6 stops"; joined by " · ". Null when
 * there is nothing to say: no gravel worth a note, no factor on, or a loaded
 * graph without data for the enabled ones (no climb on a 2D graph, no stops
 * before `moto_stops`).
 */
internal fun rideFactorSummary(
    path: ResponsePath,
    factors: RideFactors,
    hasElevation: Boolean,
    hasStops: Boolean,
): String? {
    val parts = buildList {
        routeGravelMeters(path)?.let(::gravelText)?.let(::add)
        if (factors.elevation && hasElevation) add("${routeClimbMeters(path.points).roundToInt()} m climb")
        // A graph imported without moto_stops reads 0 on every edge; say nothing rather than "0 stops".
        if (factors.stopsAndTowns && hasStops) routeStopCount(path)?.let { add(if (it == 1) "1 stop" else "$it stops") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Relief per km at and below which a road counts as flat (above SRTM's surface noise). */
internal const val HILL_FLAT_METERS_PER_KM = 20.0

/** Relief per km at and above which a road counts as fully hilly. */
internal const val HILL_FULL_METERS_PER_KM = 70.0

/** A rise or fall smaller than this is noise to [routeClimbMeters]. */
internal const val CLIMB_HYSTERESIS_METERS = 10.0

/** Shortest distance a hill rate is measured over. */
internal const val HILL_MIN_DISTANCE_METERS = 250.0

/** Fun exposure a fully hilly straight road earns (see [hillyFunExposure]). */
internal const val HILL_FUN_SHARE = 0.75

/** Upper bound on one edge's half stops: a corrupt value cannot explode a weight. */
internal const val MAX_HALF_STOPS_PER_EDGE = 64
