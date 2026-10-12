package com.curvemaps.app.routing.suggestion

import com.curvemaps.app.routing.navigation.GeoMath
import com.curvemaps.app.routing.navigation.RouteTrack
import kotlin.math.floor

/**
 * Where a [RouteTrack] leaves another one and where it comes back.
 *
 * Vertex indices and offsets are on the replacement; the baseline offsets are
 * the projections of the two replacement vertices onto the baseline.
 */
data class DiversionSpan(
    /** Last replacement vertex still on the baseline before the diversion. */
    val divergeVertex: Int,
    /** First replacement vertex back on the baseline after the diversion. */
    val rejoinVertex: Int,
    val divergeReplacementOffsetM: Double,
    val rejoinReplacementOffsetM: Double,
    val divergeBaselineOffsetM: Double,
    val rejoinBaselineOffsetM: Double,
) {
    /** Length of the diverging stretch on the replacement, metres. */
    val distinctM: Double get() = rejoinReplacementOffsetM - divergeReplacementOffsetM

    /** Length of the stretch of baseline it replaces, metres. */
    val replacedBaselineM: Double get() = rejoinBaselineOffsetM - divergeBaselineOffsetM
}

/**
 * Pure geometric comparison of two tracks. It needs no graph edge ids, so the
 * same code compares a window card against the ride and the full replacement
 * against the ride, and it runs in JVM tests on synthetic polylines.
 */
object DiversionGeometry {
    /** A replacement vertex this close to the baseline counts as "on" it. */
    const val ON_ROUTE_TOLERANCE_M = 30.0

    /** Shorter off-baseline runs are snapping noise, not a diversion. */
    const val MIN_OFF_RUN_M = 200.0

    /**
     * The first real diversion of [replacement] from [baseline], searching the
     * baseline only at or after [baselineFromOffsetM]. Returns null when the
     * replacement never leaves the baseline, or leaves and never rejoins it.
     */
    fun span(
        baseline: RouteTrack,
        baselineFromOffsetM: Double,
        replacement: RouteTrack,
    ): DiversionSpan? {
        val index = SegmentGrid(baseline, baseline.locate(baselineFromOffsetM).first)
        var cursorSegment = baseline.locate(baselineFromOffsetM).first
        var runStart = -1
        var lastOnVertex = -1
        var lastOnBaselineOffset = Double.NaN
        var divergeVertex = -1
        var divergeBaselineOffset = Double.NaN
        for (v in 0 until replacement.vertexCount) {
            val near = index.nearest(
                replacement.latitudes[v], replacement.longitudes[v],
                minSegment = cursorSegment,
            )
            val on = near != null && near.distanceM <= ON_ROUTE_TOLERANCE_M
            if (on) {
                near!!
                if (runStart >= 0) {
                    val runLength = replacement.cumulativeDistanceM[v] -
                        replacement.cumulativeDistanceM[runStart]
                    if (runLength >= MIN_OFF_RUN_M && divergeVertex >= 0) {
                        if (near.offsetM <= divergeBaselineOffset) return null
                        return DiversionSpan(
                            divergeVertex = divergeVertex,
                            rejoinVertex = v,
                            divergeReplacementOffsetM = replacement.cumulativeDistanceM[divergeVertex],
                            rejoinReplacementOffsetM = replacement.cumulativeDistanceM[v],
                            divergeBaselineOffsetM = divergeBaselineOffset,
                            rejoinBaselineOffsetM = near.offsetM,
                        )
                    }
                    runStart = -1
                    divergeVertex = -1
                }
                cursorSegment = maxOf(cursorSegment, near.segment)
                lastOnVertex = v
                lastOnBaselineOffset = near.offsetM
            } else if (runStart < 0) {
                runStart = v
                divergeVertex = lastOnVertex
                divergeBaselineOffset = lastOnBaselineOffset
            }
        }
        return null
    }

    private data class Nearest(val segment: Int, val offsetM: Double, val distanceM: Double)

    /**
     * Bucket grid over the baseline's segments so each replacement vertex
     * checks only nearby segments instead of the whole remaining route.
     */
    private class SegmentGrid(private val track: RouteTrack, firstSegment: Int) {
        private val cells = HashMap<Long, MutableList<Int>>()

        init {
            for (s in firstSegment until track.vertexCount - 1) {
                val latA = cell(track.latitudes[s]); val latB = cell(track.latitudes[s + 1])
                val lonA = cell(track.longitudes[s]); val lonB = cell(track.longitudes[s + 1])
                for (la in minOf(latA, latB)..maxOf(latA, latB)) {
                    for (lo in minOf(lonA, lonB)..maxOf(lonA, lonB)) {
                        cells.getOrPut(key(la, lo)) { mutableListOf() } += s
                    }
                }
            }
        }

        fun nearest(lat: Double, lon: Double, minSegment: Int): Nearest? {
            val la = cell(lat)
            val lo = cell(lon)
            var best: Nearest? = null
            for (dLa in -1..1) for (dLo in -1..1) {
                val segments = cells[key(la + dLa, lo + dLo)] ?: continue
                for (s in segments) {
                    if (s < minSegment) continue
                    val (distance, t) = GeoMath.segmentDistanceM(
                        lat, lon,
                        track.latitudes[s], track.longitudes[s],
                        track.latitudes[s + 1], track.longitudes[s + 1],
                    )
                    val current = best
                    // Ties keep the earliest segment so a road ridden twice
                    // matches its first pass.
                    if (current == null || distance < current.distanceM - 1e-6) {
                        val start = track.cumulativeDistanceM[s]
                        val length = track.cumulativeDistanceM[s + 1] - start
                        best = Nearest(s, start + t * length, distance)
                    }
                }
            }
            return best
        }

        private fun cell(degrees: Double): Int = floor(degrees / CELL_DEGREES).toInt()

        private fun key(la: Int, lo: Int): Long = (la.toLong() shl 32) or (lo.toLong() and 0xffffffffL)

        private companion object {
            /** ~220 m: much wider than the on-route tolerance, so 3x3 cells suffice. */
            const val CELL_DEGREES = 0.002
        }
    }
}
