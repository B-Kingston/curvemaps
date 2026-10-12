package com.curvemaps.app.routing

import com.graphhopper.GHRequest
import com.graphhopper.util.Parameters
import com.graphhopper.util.shapes.GHPoint

/**
 * Builds the request used for one route-search attempt.
 *
 * Keep the profile, path details, and hint order aligned with the production
 * router. GraphHopper reads these hints when it selects the weighting and
 * alternative algorithm.
 *
 * A two-point request with [alternatives] uses GraphHopper's alternative-route
 * algorithm. Attempt 0 is the plain search; later attempts softly penalise
 * [siblingEdgeIds] (roads of already accepted routes) and widen the candidate
 * pool. Without [alternatives] the request runs the default algorithm and
 * GraphHopper returns the style's single optimum. [curveWeight] replaces the
 * style's own curve preference for a stop searched again a little curvier.
 * [siblingEdgePenalty] replaces the attempt's own sibling penalty, so a
 * diversified re-search can penalise its repeated roads by an exact multiple.
 * [factors] adds the ride-factor hints only when a factor is on, so ordinary
 * requests stay byte-for-byte what they were; with [RideFactors.stopsAndTowns]
 * the request also asks for the per-edge [MOTO_STOPS_DETAIL] the route card
 * counts. [passThrough] forbids a U-turn at each via point (GraphHopper's
 * `pass_through`): the look-around search of [DistinctRideStyles] wants a
 * loop through its via point, never an out-and-back spur; imported GPX
 * shaping points keep GraphHopper's default. [viaHeadings], one per via
 * point (NaN for none), are the directions a followed GPX track rides
 * through them, so a point beside a divided road snaps onto the carriageway
 * the track used instead of the opposite one; the destination carries none.
 * [startHeading] (degrees from north, NaN for none) is the direction an
 * off-route rebuild starts in: the rider's own, so the new route continues
 * forward on the rider's carriageway instead of turning the rider around.
 */
internal fun buildGhRequest(
    from: GHPoint,
    to: GHPoint,
    style: RideStyle,
    blockUnpaved: Boolean,
    avoidMotorways: Boolean,
    attempt: Int,
    siblingEdgeIds: Set<Int>,
    viaPoints: List<GHPoint> = emptyList(),
    alternatives: Boolean = true,
    curveWeight: Double? = null,
    siblingEdgePenalty: Double? = null,
    factors: RideFactors = RideFactors.NONE,
    passThrough: Boolean = false,
    viaHeadings: List<Double> = emptyList(),
    startHeading: Double = Double.NaN,
): GHRequest {
    require(viaHeadings.isEmpty() || viaHeadings.size == viaPoints.size) {
        "One heading per via point (NaN for none)"
    }
    val details = ROUTE_DETAILS + if (factors.stopsAndTowns) listOf(MOTO_STOPS_DETAIL) else emptyList()
    val request = GHRequest(listOf(from) + viaPoints + to)
        .setProfile(MOTORCYCLE_PROFILE)
        .setPathDetails(details)
    request.putHint(Parameters.CH.DISABLE, true)
    request.putHint(MOTO_RIDE_STYLE, style.level)
    if (curveWeight != null) request.putHint(MOTO_CURVE_WEIGHT, curveWeight)
    request.putHint(BLOCK_UNPAVED, blockUnpaved)
    request.putHint(AVOID_MOTORWAYS, avoidMotorways)
    if (factors.elevation) {
        request.putHint(MOTO_HILLS, true)
        // Response simplification otherwise uses horizontal distance only
        // (RouterConfig's elevation tolerance is MAX_VALUE) and drops crests
        // and dips on near-straight roads, so the card's climb comes up short.
        request.putHint(ELEVATION_WAY_POINT_MAX_DISTANCE, CLIMB_SIMPLIFY_TOLERANCE_METERS)
    }
    if (factors.stopsAndTowns) request.putHint(MOTO_STOPS_AND_TOWNS, true)
    if (passThrough) request.putHint(Parameters.Routing.PASS_THROUGH, true)
    if (!startHeading.isNaN() || viaHeadings.any { !it.isNaN() }) {
        request.setHeadings(listOf(startHeading) + viaHeadings.ifEmpty { List(viaPoints.size) { Double.NaN } } + Double.NaN)
    }
    if (siblingEdgeIds.isNotEmpty()) {
        request.putHint(MOTO_SIBLING_EDGES, siblingEdgeIds)
        request.putHint(
            MOTO_SIBLING_EDGE_PENALTY,
            siblingEdgePenalty ?: AlternativePolicy.siblingRoadPenalty(attempt),
        )
    }
    // GraphHopper's alternative-route algorithm only supports two-point
    // requests. Imported GPX rides use the normal flexible algorithm so
    // every via point becomes a routed leg with turn instructions.
    if (viaPoints.isNotEmpty() || !alternatives) return request
    request.setAlgorithm(Parameters.Algorithms.ALT_ROUTE)
    request.putHint(
        Parameters.Algorithms.AltRoute.MAX_PATHS,
        (10 + attempt * 8).coerceAtMost(40),
    )
    request.putHint(
        Parameters.Algorithms.AltRoute.MAX_SHARE,
        minOf(
            AlternativePolicy.MAX_CANDIDATE_SHARE,
            AlternativePolicy.SIBLING_MAX_SHARE + attempt * AlternativePolicy.CANDIDATE_SHARE_STEP,
        ),
    )
    request.putHint(Parameters.Algorithms.AltRoute.MAX_WEIGHT, AlternativePolicy.maxAlternativeWeight(attempt))
    // Constant on purpose: widening exploration on retries cost 30-60 s per
    // style on 500-900 km corridors. Retries diversify through the sibling
    // penalty and the wider share/weight caps instead.
    request.putHint("alternative_route.max_exploration_factor", AlternativePolicy.MAX_EXPLORATION_FACTOR)
    request.putHint("alternative_route.min_plateau_factor", AlternativePolicy.MIN_PLATEAU_FACTOR)
    return request
}

/** GraphHopper's built-in `roundabout` flag, per edge. */
internal const val ROUNDABOUT_DETAIL = "roundabout"

/** GraphHopper's built-in `road_class_link` flag (motorway, trunk and other ramps), per edge. */
internal const val ROAD_CLASS_LINK_DETAIL = "road_class_link"

/**
 * The path details every route asks for: edges (alternatives, loops), road
 * class (B&W ride map, curve shading), surface (gravel), lanes (guidance),
 * and the roundabout and ramp flags the planner's curve shading leaves out.
 */
internal val ROUTE_DETAILS =
    listOf("edge_id", "road_class", SURFACE_DETAIL, MOTO_LANES_DETAIL, ROUNDABOUT_DETAIL, ROAD_CLASS_LINK_DETAIL)

/** GraphHopper's per-request vertical tolerance for response simplification. */
internal const val ELEVATION_WAY_POINT_MAX_DISTANCE = "elevation_way_point_max_distance"

/** Vertical simplification tolerance: well under [CLIMB_HYSTERESIS_METERS]. */
internal const val CLIMB_SIMPLIFY_TOLERANCE_METERS = 2.0
