package com.curvemaps.app.routing.navigation

/**
 * Immutable guidance-ready view of one GraphHopper route: the geometry plus
 * pre-computed cumulative distances, cumulative model travel times, and turn
 * nodes derived from GraphHopper's instruction list.
 *
 * Everything here is pure JVM with no Android imports so it can be built and
 * exercised in unit tests from a [com.graphhopper.ResponsePath].
 *
 * Distances are metres, times are seconds. Model times come from GraphHopper
 * per-instruction durations, so remaining-time matches the ETA the user was
 * quoted at planning time rather than a wall-clock guess.
 */
class RouteTrack(
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    /** Cumulative distance in metres at each vertex; cumulative[0] == 0. */
    val cumulativeDistanceM: DoubleArray,
    /** Cumulative travel time in seconds at each vertex; cumulative[0] == 0. */
    val cumulativeTimeS: DoubleArray,
    /** Total driven distance in metres. */
    val totalDistanceM: Double,
    /** Total model travel time in seconds. */
    val totalTimeS: Double,
    /** Turns along the route, ordered by vertex index ascending. */
    val turns: List<TurnNode>,
) {
    init {
        require(latitudes.size == longitudes.size) { "coordinate arrays must align" }
        require(latitudes.size >= 2) { "a route track needs at least two vertices" }
        require(cumulativeDistanceM.size == latitudes.size) { "distance cache must cover every vertex" }
        require(cumulativeTimeS.size == latitudes.size) { "time cache must cover every vertex" }
    }

    /** One navigation turn: the maneuver at vertex [vertexIndex]. */
    data class TurnNode(
        val vertexIndex: Int,
        /** GraphHopper instruction sign; see [com.graphhopper.util.Instruction]. */
        val sign: Int,
        /** Human road/street name for the maneuver, may be empty. */
        val streetName: String,
        /** Signed route deflection at the maneuver, in degrees; right is positive. */
        val turnAngleDeg: Double? = null,
        /** Chosen exit number for a GraphHopper roundabout instruction, when known. */
        val roundaboutExitNumber: Int? = null,
        /** True for clockwise circulation, false for counterclockwise, null when ambiguous. */
        val roundaboutClockwise: Boolean? = null,
        /** Recommended lane(s) on the road being left, when it has two or more lanes. */
        val lanes: LaneGuidance? = null,
    )

    val vertexCount: Int get() = latitudes.size

    /** Remaining model time in seconds from fractional offset [offsetM] (metres along the track). */
    fun remainingTimeS(offsetM: Double): Double {
        val (index, fraction) = locate(offsetM)
        val base = cumulativeTimeS[index]
        val next = cumulativeTimeS[(index + 1).coerceAtMost(lastIndex)]
        return totalTimeS - (base + fraction * (next - base))
    }

    /** Remaining distance in metres from fractional offset [offsetM]. */
    fun remainingDistanceM(offsetM: Double): Double = (totalDistanceM - offsetM).coerceAtLeast(0.0)

    /** Model travel time in seconds from the start to fractional offset [offsetM]. */
    fun timeAtOffsetS(offsetM: Double): Double = totalTimeS - remainingTimeS(offsetM)

    /**
     * Inverse of [timeAtOffsetS]: the offset the model reaches after [timeS]
     * seconds from the start, clamped to the track. Zero-time stretches are
     * passed at the end of their run so the result never moves backwards.
     */
    fun offsetAtTimeS(timeS: Double): Double {
        if (timeS.isNaN() || timeS <= 0.0) return 0.0
        if (timeS >= totalTimeS) return totalDistanceM
        var lo = 0
        var hi = lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulativeTimeS[mid] <= timeS) lo = mid else hi = mid - 1
        }
        val next = (lo + 1).coerceAtMost(lastIndex)
        val segTime = cumulativeTimeS[next] - cumulativeTimeS[lo]
        val fraction = if (segTime <= 0.0) 1.0 else ((timeS - cumulativeTimeS[lo]) / segTime).coerceIn(0.0, 1.0)
        return cumulativeDistanceM[lo] + fraction * (cumulativeDistanceM[next] - cumulativeDistanceM[lo])
    }

    /**
     * Mean model speed (m/s) over [windowM] either side of [offsetM], or NaN
     * when the track carries no usable times there.
     */
    fun modelSpeedMpsAround(offsetM: Double, windowM: Double): Double {
        val from = (offsetM - windowM).coerceIn(0.0, totalDistanceM)
        val to = (offsetM + windowM).coerceIn(0.0, totalDistanceM)
        val seconds = timeAtOffsetS(to) - timeAtOffsetS(from)
        if (to - from < 1.0 || !(seconds > 0.0)) return Double.NaN
        return (to - from) / seconds
    }

    /**
     * Route bearing in degrees at [offsetM]: the bearing of the segment that
     * contains it, skipping zero-length segments. NaN on a degenerate track.
     */
    fun bearingAtDeg(offsetM: Double): Double {
        var (index, _) = locate(offsetM)
        if (index >= lastIndex) index = lastIndex - 1
        var end = index + 1
        while (end < lastIndex && cumulativeDistanceM[end] - cumulativeDistanceM[index] <= 0.0) end++
        if (cumulativeDistanceM[end] - cumulativeDistanceM[index] <= 0.0) return Double.NaN
        return GeoMath.bearingDeg(latitudes[index], longitudes[index], latitudes[end], longitudes[end])
    }

    /**
     * Locates [offsetM] along the track: the segment start index and the
     * fraction (0..1) travelled into that segment.
     */
    fun locate(offsetM: Double): Pair<Int, Double> {
        val clamped = offsetM.coerceIn(0.0, totalDistanceM)
        var lo = 0
        var hi = lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulativeDistanceM[mid] <= clamped) lo = mid else hi = mid - 1
        }
        val segLen = cumulativeDistanceM[(lo + 1).coerceAtMost(lastIndex)] - cumulativeDistanceM[lo]
        return if (segLen <= 0.0) lo to 0.0 else lo to ((clamped - cumulativeDistanceM[lo]) / segLen).coerceIn(0.0, 1.0)
    }

    /** Interpolated position (lat to lon) at fractional offset [offsetM]. */
    fun positionAt(offsetM: Double): Pair<Double, Double> {
        val (index, fraction) = locate(offsetM)
        val next = (index + 1).coerceAtMost(lastIndex)
        return (latitudes[index] + fraction * (latitudes[next] - latitudes[index])) to
            (longitudes[index] + fraction * (longitudes[next] - longitudes[index]))
    }

    /**
     * Offset of the vertex nearest to ([lat], [lon]) at or after
     * [fromOffsetM], or null when none lies within [maxDistanceM]. Used to
     * tell whether a shaping point is still ahead of the rider.
     */
    fun nearestVertexOffsetM(lat: Double, lon: Double, fromOffsetM: Double, maxDistanceM: Double): Double? {
        val (start, _) = locate(fromOffsetM)
        var best = Double.POSITIVE_INFINITY
        var bestIndex = -1
        for (v in start..lastIndex) {
            val d = GeoMath.haversineM(lat, lon, latitudes[v], longitudes[v])
            if (d < best) {
                best = d
                bestIndex = v
            }
        }
        return if (bestIndex >= 0 && best <= maxDistanceM) cumulativeDistanceM[bestIndex] else null
    }

    /** Upcoming turns in route order, with each distance measured from [offsetM]. */
    fun upcomingTurns(offsetM: Double, limit: Int): List<Pair<TurnNode, Double>> {
        if (limit <= 0) return emptyList()
        return turns.asSequence()
            .mapNotNull { turn ->
                val turnOffset = cumulativeDistanceM[turn.vertexIndex.coerceIn(0, lastIndex)]
                if (turnOffset >= offsetM - TURN_SNAP_TOLERANCE_M) {
                    turn to (turnOffset - offsetM).coerceAtLeast(0.0)
                } else {
                    null
                }
            }
            .take(limit)
            .toList()
    }

    private val lastIndex: Int get() = latitudes.size - 1

    companion object {
        /** A turn within this distance of the current offset already counts as reached. */
        const val TURN_SNAP_TOLERANCE_M = 15.0

        /**
         * Builds a track from GraphHopper path geometry and instructions.
         * Instruction vertex offsets come from cumulative instruction point
         * counts; each instruction's points are a contiguous slice of the
         * path geometry.
         */
        fun fromPath(
            latitudes: DoubleArray,
            longitudes: DoubleArray,
            instructionSigns: List<Int>,
            instructionNames: List<String>,
            instructionPointCounts: List<Int>,
            instructionTimesS: List<Double>,
            instructionRoundaboutExitNumbers: List<Int?> = List(instructionSigns.size) { null },
            instructionRoundaboutClockwise: List<Boolean?> = List(instructionSigns.size) { null },
            /** Raw `moto_lanes` value of segment v -> v+1 at index v; empty when unknown. */
            segmentLanes: List<String?> = emptyList(),
        ): RouteTrack {
            val n = latitudes.size
            require(n >= 2) { "a route track needs at least two vertices" }
            require(longitudes.size == n) { "coordinate arrays must align" }
            require(instructionNames.size == instructionSigns.size &&
                instructionPointCounts.size == instructionSigns.size &&
                instructionTimesS.size == instructionSigns.size &&
                instructionRoundaboutExitNumbers.size == instructionSigns.size &&
                instructionRoundaboutClockwise.size == instructionSigns.size
            ) { "instruction fields must align" }
            // Exact cumulative geometric distances: the distance cache must
            // reflect real geometry so remaining-distance math is honest.
            val cumulativeDistance = DoubleArray(n)
            for (v in 1 until n) {
                cumulativeDistance[v] = cumulativeDistance[v - 1] + GeoMath.haversineM(
                    latitudes[v - 1], longitudes[v - 1], latitudes[v], longitudes[v],
                )
            }
            val totalDistance = cumulativeDistance[n - 1]
            val totalTime = instructionTimesS.sum()
            // Distribute per-instruction time across the segments the
            // instruction covers, proportionally to each segment's geometric
            // length. Instruction points are contiguous path-vertex slices.
            val cumulativeTime = DoubleArray(n)
            val segmentTime = DoubleArray(n) // time attributable to segment v (v -> v+1)
            val turns = mutableListOf<TurnNode>()
            var cursor = 0
            for (i in instructionSigns.indices) {
                val points = instructionPointCounts[i]
                require(points >= 0) { "instruction point counts must be non-negative" }
                val end = (cursor + points).coerceAtMost(n)
                val time = instructionTimesS[i]
                val first = cursor
                // GraphHopper omits an instruction's final adjacent point;
                // that vertex is the first point of the next instruction.
                // Consequently P instruction points cover P route segments.
                val segmentEnd = end.coerceAtMost(n - 1)
                val segmentCount = (segmentEnd - first).coerceAtLeast(0)
                val segLenSum = if (segmentCount > 0) {
                    cumulativeDistance[segmentEnd] - cumulativeDistance[first]
                } else 0.0
                for (v in first until segmentEnd) {
                    val share = if (segLenSum > 0.0) {
                        (cumulativeDistance[v + 1] - cumulativeDistance[v]) / segLenSum
                    } else {
                        1.0 / segmentCount
                    }
                    segmentTime[v] += time * share
                }
                // A maneuver sign happens at the START of instruction i, i.e.
                // at the vertex where the previous instruction ended. Skip the
                // synthetic first instruction (depart / continue at origin).
                if (i > 0 && isRealTurn(instructionSigns[i])) {
                    val vertex = cursor.coerceAtMost(n - 1)
                    val lanes = LaneGuidance.forTurn(
                        segmentLanes, cumulativeDistance, vertex,
                        previousTurnVertex = turns.lastOrNull()?.vertexIndex ?: 0,
                        sign = instructionSigns[i],
                    )
                    turns += TurnNode(
                        vertexIndex = vertex,
                        sign = instructionSigns[i],
                        streetName = instructionNames[i],
                        turnAngleDeg = turnAngleAt(latitudes, longitudes, vertex),
                        roundaboutExitNumber = instructionRoundaboutExitNumbers[i],
                        roundaboutClockwise = instructionRoundaboutClockwise[i],
                        lanes = lanes,
                    )
                }
                cursor = end
            }
            for (v in 1 until n) {
                cumulativeTime[v] = cumulativeTime[v - 1] + segmentTime[v - 1]
            }
            // Scale so the last vertex lands exactly on totalTime.
            if (cumulativeTime[n - 1] > 0.0) {
                val drift = totalTime / cumulativeTime[n - 1]
                for (v in 0 until n) cumulativeTime[v] *= drift
            }
            return RouteTrack(
                latitudes, longitudes, cumulativeDistance, cumulativeTime,
                totalDistance, totalTime, turns,
            )
        }

        /** GraphHopper signs that represent an actual maneuver worth announcing. */
        fun isRealTurn(sign: Int): Boolean = when (sign) {
            com.graphhopper.util.Instruction.CONTINUE_ON_STREET,
            com.graphhopper.util.Instruction.FINISH,
            com.graphhopper.util.Instruction.REACHED_VIA,
            com.graphhopper.util.Instruction.IGNORE,
            -> false
            else -> true
        }

        /**
         * Measures the route's direction change from the closest distinct
         * geometry vertices either side of a maneuver. GraphHopper signs
         * classify the instruction; this angle preserves how gently the road
         * actually bends for the HUD arrow.
         */
        private fun turnAngleAt(
            latitudes: DoubleArray,
            longitudes: DoubleArray,
            vertex: Int,
        ): Double? {
            var before = vertex - 1
            while (before >= 0 && GeoMath.haversineM(
                    latitudes[before], longitudes[before], latitudes[vertex], longitudes[vertex],
                ) < MIN_TURN_BEARING_SAMPLE_M
            ) {
                before--
            }
            var after = vertex + 1
            while (after < latitudes.size && GeoMath.haversineM(
                    latitudes[vertex], longitudes[vertex], latitudes[after], longitudes[after],
                ) < MIN_TURN_BEARING_SAMPLE_M
            ) {
                after++
            }
            if (before < 0 || after >= latitudes.size) return null
            val incoming = GeoMath.bearingDeg(
                latitudes[before], longitudes[before], latitudes[vertex], longitudes[vertex],
            )
            val outgoing = GeoMath.bearingDeg(
                latitudes[vertex], longitudes[vertex], latitudes[after], longitudes[after],
            )
            return GeoMath.signedBearingDeltaDeg(incoming, outgoing)
        }

        private const val MIN_TURN_BEARING_SAMPLE_M = 0.5
    }
}
