package com.curvemaps.app.routing.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteTrackPositionTest {
    /** Due east along the equator, 20 vertices 100 m apart. */
    private val track = RouteTrack.fromPath(
        DoubleArray(20) { 0.0 },
        DoubleArray(20) { it * 0.0009 },
        listOf(0, 4),
        listOf("", ""),
        listOf(19, 1),
        listOf(190.0, 0.0),
    )

    @Test
    fun positionAtInterpolatesAndClamps() {
        val (lat, lon) = track.positionAt(track.cumulativeDistanceM[3] + 0.5 * (track.cumulativeDistanceM[4] - track.cumulativeDistanceM[3]))
        assertEquals(0.0, lat, 1e-12)
        assertEquals(3.5 * 0.0009, lon, 1e-9)
        assertEquals(0.0, track.positionAt(-50.0).second, 1e-12)
        assertEquals(19 * 0.0009, track.positionAt(1e9).second, 1e-12)
    }

    @Test
    fun nearestVertexOffsetOnlyLooksAhead() {
        val behind = 2 * 0.0009
        assertEquals(track.cumulativeDistanceM[2], track.nearestVertexOffsetM(0.0, behind, 0.0, 50.0)!!, 1e-9)
        assertNull("behind the rider", track.nearestVertexOffsetM(0.0, behind, track.cumulativeDistanceM[5], 50.0))
        assertNull("too far off the route", track.nearestVertexOffsetM(0.01, 10 * 0.0009, 0.0, 50.0))
    }

    @Test
    fun matcherSeekStartsTheForwardWindowThere() {
        val matcher = PolylineMatcher(track)
        // Without a seek the 3-segment window from the start cannot reach vertex 15.
        val far = GpsFix(0.0, 15.2 * 0.0009, speedMps = 10.0, timestampMs = 0)
        assertNull(matcher.project(far, windowSegments = 3, maxSnapM = 20.0))
        matcher.seek(track.cumulativeDistanceM[14])
        assertEquals(14, matcher.currentSegmentIndex)
        val offset = matcher.project(far, windowSegments = 3, maxSnapM = 20.0)!!
        assertEquals(track.cumulativeDistanceM[15] + 0.2 * 100.0, offset, 1.0)
    }
}
