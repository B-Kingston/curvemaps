package com.curvemaps.app

import android.util.Log
import com.curvemaps.app.routing.GraphHopperRouter
import com.curvemaps.app.routing.RideStyle
import com.curvemaps.app.routing.RouteResult
import com.curvemaps.app.storage.FollowAttempt
import com.curvemaps.app.storage.GeoPoint
import com.curvemaps.app.storage.GpxRidePlan
import com.curvemaps.app.storage.GpxTrackFollow
import com.graphhopper.util.PointList
import com.graphhopper.util.shapes.GHPoint
import kotlin.math.roundToInt

private const val TAG = "CurveMaps.RouteScreen"

/**
 * Routes [plan]'s detailed track road for road ([GpxTrackFollow.follow]) with
 * [params]' road settings. Each round is one plain Fastest request through
 * the shaping points with the track's headings: the file chose the roads, so
 * no ride style reshapes them. [checkActive] runs before every round so a
 * cancelled search stops between requests.
 */
internal fun followGpxTrack(
    router: GraphHopperRouter,
    params: RouteParams,
    plan: GpxRidePlan,
    checkActive: () -> Unit,
): RouteResult {
    val initial = GpxTrackFollow.shapingPoints(plan.line, plan.stops)
    val result = GpxTrackFollow.follow(plan.line, initial) { shaping ->
        checkActive()
        val vias = shaping.subList(1, shaping.size - 1)
        val path = router.route(
            GHPoint(shaping.first().point.lat, shaping.first().point.lon),
            GHPoint(shaping.last().point.lat, shaping.last().point.lon),
            complexity = RideStyle.FASTEST.level.toDouble(),
            blockUnpaved = params.blockUnpaved,
            avoidMotorways = params.avoidMotorways,
            viaPoints = vias.map { GHPoint(it.point.lat, it.point.lon) },
            factors = params.factors,
            viaHeadings = vias.map { it.headingDeg },
        ).routes.first()
        FollowAttempt(path, path.points.geoPoints(), path.waypoints?.geoPoints().orEmpty())
    }
    Log.i(
        TAG,
        "GPX track followed in ${result.attempts} round(s) with ${result.shaping.size} points: " +
            "${result.measure.offTrackMeters.roundToInt()} m of the track not ridden, " +
            "${result.measure.extraMeters.roundToInt()} m off it",
    )
    return RouteResult(listOf(result.value))
}

private fun PointList.geoPoints(): List<GeoPoint> = List(size()) { GeoPoint(getLat(it), getLon(it)) }
