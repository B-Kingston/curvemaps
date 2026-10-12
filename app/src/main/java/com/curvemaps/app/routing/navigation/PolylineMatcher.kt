package com.curvemaps.app.routing.navigation

/**
 * Forward-window route matcher over a [RouteTrack], ported in spirit from
 * Organic Maps' FollowedPolyline: projection always searches FORWARD from the
 * current position on the route, never backward, so a parallel road already
 * passed cannot recapture the rider.
 *
 * The matcher tracks a fractional offset (metres along the track) and the
 * vertex index of the matched segment. It reports both the projected offset
 * and the perpendicular miss distance so the session can decide whether the
 * rider is still on the route.
 */
class PolylineMatcher(private val track: RouteTrack) {

    /** Vertex index of the current segment start. */
    var currentSegmentIndex: Int = 0
        private set

    /** Current offset along the track in metres. */
    var currentOffsetM: Double = 0.0
        private set

    /** Perpendicular distance in metres from the latest fix to the route. */
    var lastMissM: Double = Double.POSITIVE_INFINITY
        private set

    /**
     * Projects [fix] onto the track, searching at most [windowSegments]
     * segments forward from the current position plus one segment back
     * (to absorb intra-segment back-and-forth within the same street).
     *
     * @return the projected offset in metres, or null when no segment in the
     * window yields a projection within [maxSnapM] of the fix.
     */
    fun project(fix: GpsFix, windowSegments: Int, maxSnapM: Double): Double? {
        val n = track.vertexCount
        val from = currentSegmentIndex.coerceAtLeast(0)
        // One segment back handles the common case of stopping right at a
        // vertex boundary where the forward-only search would jump a street.
        val back = if (from > 0) from - 1 else 0
        val to = (from + windowSegments).coerceAtMost(n - 1)
        var bestIndex = -1
        var bestT = 0.0
        var bestDist = Double.POSITIVE_INFINITY
        for (i in back until to) {
            val (dist, t) = GeoMath.segmentDistanceM(
                fix.lat, fix.lon,
                track.latitudes[i], track.longitudes[i],
                track.latitudes[i + 1], track.longitudes[i + 1],
            )
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = i
                bestT = t
            }
        }
        lastMissM = bestDist
        if (bestIndex < 0 || bestDist > maxSnapM) return null
        currentSegmentIndex = bestIndex
        val segStart = track.cumulativeDistanceM[bestIndex]
        val segLen = track.cumulativeDistanceM[bestIndex + 1] - segStart
        currentOffsetM = segStart + bestT * segLen
        return currentOffsetM
    }

    /**
     * Re-finds the rider after a GPS gap, when the ordinary forward window
     * may be far too short for the distance covered blind. Searches from one
     * segment behind the current position up to [toOffsetM] and, among the
     * segments within [maxSnapM], prefers the one closest to [expectedOffsetM]
     * along the route: each metre of along-route disagreement costs
     * [ALONG_ROUTE_WEIGHT] metres of perpendicular distance, so the road the
     * rider is actually on still wins locally while a hairpin's other leg
     * (close to the fix, but far from where the rider should be) does not.
     *
     * @return the projected offset, or null when nothing lies within [maxSnapM].
     */
    fun relocate(fix: GpsFix, toOffsetM: Double, expectedOffsetM: Double, maxSnapM: Double): Double? {
        val n = track.vertexCount
        val from = currentSegmentIndex.coerceAtLeast(0)
        val back = if (from > 0) from - 1 else 0
        val (endIndex, _) = track.locate(toOffsetM)
        val to = (endIndex + 1).coerceIn(back + 1, n - 1)
        var bestIndex = -1
        var bestT = 0.0
        var bestScore = Double.POSITIVE_INFINITY
        var nearest = Double.POSITIVE_INFINITY
        for (i in back until to) {
            val (dist, t) = GeoMath.segmentDistanceM(
                fix.lat, fix.lon,
                track.latitudes[i], track.longitudes[i],
                track.latitudes[i + 1], track.longitudes[i + 1],
            )
            if (dist < nearest) nearest = dist
            if (dist > maxSnapM) continue
            val offset = track.cumulativeDistanceM[i] +
                t * (track.cumulativeDistanceM[i + 1] - track.cumulativeDistanceM[i])
            val score = dist + ALONG_ROUTE_WEIGHT * kotlin.math.abs(offset - expectedOffsetM)
            if (score < bestScore) {
                bestScore = score
                bestIndex = i
                bestT = t
            }
        }
        lastMissM = nearest
        if (bestIndex < 0) return null
        currentSegmentIndex = bestIndex
        val segStart = track.cumulativeDistanceM[bestIndex]
        val segLen = track.cumulativeDistanceM[bestIndex + 1] - segStart
        currentOffsetM = segStart + bestT * segLen
        return currentOffsetM
    }

    /**
     * Bearing of the matched segment in degrees clockwise from north.
     */
    fun matchedSegmentBearingDeg(): Double = GeoMath.bearingDeg(
        track.latitudes[currentSegmentIndex], track.longitudes[currentSegmentIndex],
        track.latitudes[currentSegmentIndex + 1], track.longitudes[currentSegmentIndex + 1],
    )

    /**
     * Position of the current projection. Returns lat, lon.
     */
    fun projectedPosition(): Pair<Double, Double> {
        val i = currentSegmentIndex
        val segStart = track.cumulativeDistanceM[i]
        val segLen = track.cumulativeDistanceM[i + 1] - segStart
        val t = if (segLen <= 0.0) 0.0 else ((currentOffsetM - segStart) / segLen).coerceIn(0.0, 1.0)
        return (track.latitudes[i] + t * (track.latitudes[i + 1] - track.latitudes[i])) to
            (track.longitudes[i] + t * (track.longitudes[i + 1] - track.longitudes[i]))
    }

    /** Moves the matcher to [offsetM] so the forward window starts there. */
    fun seek(offsetM: Double) {
        val (index, _) = track.locate(offsetM)
        currentSegmentIndex = index.coerceAtMost(track.vertexCount - 2)
        currentOffsetM = offsetM.coerceIn(0.0, track.totalDistanceM)
    }

    fun reset() {
        currentSegmentIndex = 0
        currentOffsetM = 0.0
        lastMissM = Double.POSITIVE_INFINITY
    }

    companion object {
        /** Perpendicular metres one along-route metre of disagreement costs in [relocate]. */
        const val ALONG_ROUTE_WEIGHT = 0.05
    }
}
