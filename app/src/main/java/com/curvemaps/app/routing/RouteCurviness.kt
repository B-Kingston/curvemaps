package com.curvemaps.app.routing

import com.graphhopper.util.PointList
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Share of a route's distance that runs through curves, in 0..1.
 *
 * Uses the router's own corner definition ([circumradiusMeters],
 * [curveRadiusWeight]): a segment counts as curvy when either adjacent
 * three-point window has a circumradius under [CURVE_RADIUS_LIMIT_METERS],
 * and segments shorter than [MIN_CURVE_SEGMENT_METERS] never define a curve.
 * Each segment uses its own latitude for the longitude scale so long routes
 * stay accurate. This is the figure the route cards show, so a rider can see
 * what a slower ride style actually bought.
 */
internal fun curvyDistanceFraction(points: PointList): Double {
    val count = points.size()
    if (count < 2) return 0.0
    val latitudes = DoubleArray(count) { points.getLat(it) }
    val longitudes = DoubleArray(count) { points.getLon(it) }
    val (curved, total) = curvyAndTotalMeters(latitudes, longitudes, 0, count - 1)
    if (total <= 0.0) return 0.0
    return (curved / total).coerceIn(0.0, 1.0)
}

/**
 * Curvy metres between vertex [fromIndex] and [toIndex] (inclusive) of a
 * polyline, using the same corner definition as [curvyDistanceFraction].
 * Ride suggestions compare these figures for the same stretch of two routes,
 * so the "km of curves" a rider hears matches the cards' "N% curvy".
 */
internal fun curvyDistanceMeters(
    latitudes: DoubleArray,
    longitudes: DoubleArray,
    fromIndex: Int,
    toIndex: Int,
): Double = curvyAndTotalMeters(latitudes, longitudes, fromIndex, toIndex).first

private fun curvyAndTotalMeters(
    latitudes: DoubleArray,
    longitudes: DoubleArray,
    fromIndex: Int,
    toIndex: Int,
): Pair<Double, Double> {
    val first = fromIndex.coerceIn(0, latitudes.size - 1)
    val last = toIndex.coerceIn(0, latitudes.size - 1)
    val corners = cornersBetween(latitudes, longitudes, first, last) { false }
    var curved = 0.0
    var total = 0.0
    corners.lengths.forEachIndexed { index, length ->
        total += length
        if (corners.weights[index] > 0.0) curved += length
    }
    return curved to total
}

/**
 * Per-segment cornering of a polyline: [lengths] in metres and [weights] in
 * 0..1 for segment v (vertex v to v + 1).
 */
internal class SegmentCorners(val lengths: DoubleArray, val weights: DoubleArray)

/**
 * The corners [curvyDistanceFraction] counts, graded: each segment weighs the
 * tighter of the corners at its two ends, as [curveRadiusWeight] over its
 * maximum (0 straight, 1 a bend under 30 m). A vertex in [isJunction] is no
 * corner (a turn from one road onto another is not a bend in either).
 */
internal fun segmentCorners(
    latitudes: DoubleArray,
    longitudes: DoubleArray,
    isJunction: (Int) -> Boolean = { false },
): SegmentCorners = cornersBetween(latitudes, longitudes, 0, latitudes.size - 1, isJunction)

private fun cornersBetween(
    latitudes: DoubleArray,
    longitudes: DoubleArray,
    first: Int,
    last: Int,
    isJunction: (Int) -> Boolean,
): SegmentCorners {
    val count = last - first + 1
    if (count < 2) return SegmentCorners(DoubleArray(0), DoubleArray(0))

    fun distance(a: Int, b: Int): Double {
        val scale = cos(Math.toRadians((latitudes[a] + latitudes[b]) / 2.0))
        var deltaLongitude = longitudes[b] - longitudes[a]
        if (deltaLongitude > 180.0) deltaLongitude -= 360.0
        if (deltaLongitude < -180.0) deltaLongitude += 360.0
        return hypot(deltaLongitude * scale, latitudes[b] - latitudes[a]) * METERS_PER_DEGREE
    }

    val lengths = DoubleArray(count - 1) { distance(first + it, first + it + 1) }
    val weights = DoubleArray(count - 1)
    for (start in 0 until count - 2) {
        val a = lengths[start]
        val b = lengths[start + 1]
        if (a < MIN_CURVE_SEGMENT_METERS || b < MIN_CURVE_SEGMENT_METERS) continue
        if (isJunction(first + start + 1)) continue
        val radius = circumradiusMeters(a, b, distance(first + start, first + start + 2))
        val weight = curveRadiusWeight(radius) / MAX_CORNER_WEIGHT
        weights[start] = maxOf(weights[start], weight)
        weights[start + 1] = maxOf(weights[start + 1], weight)
    }
    return SegmentCorners(lengths, weights)
}

/** [curveRadiusWeight] of the tightest band. */
private const val MAX_CORNER_WEIGHT = 2.0
