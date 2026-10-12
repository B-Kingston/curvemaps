package com.curvemaps.app.storage

import com.curvemaps.app.routing.navigation.GeoMath
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** How an imported GPX file becomes a ride. */
enum class GpxRideMode {
    /**
     * A detailed line (a recorded ride or a planner's exported track): the
     * file sets the roads, so the ride follows it and the style slider does
     * not apply.
     */
    FOLLOW_TRACK,

    /**
     * A short list of stops (a planner's route points or a waypoint list):
     * the router and the ride style choose the roads between them.
     */
    STOPS,
}

/**
 * What an imported GPX file asks for: [line] is the ride line (thinned, and
 * for [GpxRideMode.FOLLOW_TRACK] densified so no stretch goes unchecked),
 * [stops] the file's waypoints that lie on it, and [ignoredWaypoints] the
 * waypoints that do not (points of interest beside the ride, which must not
 * pull it off the track).
 */
data class GpxRidePlan(
    val mode: GpxRideMode,
    val line: List<GeoPoint>,
    val stops: List<GeoPoint>,
    val ignoredWaypoints: Int = 0,
)

/**
 * One shaping point on a followed line: where it sits on the line ([index]),
 * the point routed through, the line's direction there (sent as the request's
 * heading so it cannot snap onto the opposite carriageway), and whether it is
 * one of the file's own stops or a line end ([explicit]: never dropped).
 */
data class TrackShapingPoint(
    val index: Int,
    val point: GeoPoint,
    val headingDeg: Double,
    val explicit: Boolean,
)

/** One routing attempt along a followed line. */
data class FollowAttempt<T>(
    val value: T,
    /** The routed geometry. */
    val points: List<GeoPoint>,
    /** Where each requested shaping point snapped, in order; empty if unknown. */
    val snapped: List<GeoPoint>,
)

/**
 * How closely a route rides a line: [offTrackMeters] of the line the route
 * does not ride, and [extraMeters] of route away from the line (a spur or a
 * detour). Only stretches of at least [GpxTrackFollow.MIN_OFF_TRACK_RUN_METERS]
 * count: junction geometry and GPS noise are not a different road.
 */
data class TrackFollowMeasure(
    val offTrackMeters: Double,
    val extraMeters: Double,
    val offTrackRuns: List<IntRange> = emptyList(),
    val extraRuns: List<IntRange> = emptyList(),
) {
    val score: Double get() = offTrackMeters + extraMeters
    val followsTrack: Boolean get() = offTrackMeters == 0.0 && extraMeters == 0.0
}

/** The best attempt [GpxTrackFollow.follow] found, with its shaping points. */
data class TrackFollowResult<T>(
    val value: T,
    val measure: TrackFollowMeasure,
    val shaping: List<TrackShapingPoint>,
    val attempts: Int,
)

/**
 * Turns an imported GPX file into a ride that rides the file's roads.
 *
 * Shaping points every [SHAPING_SPACING_METERS] alone let the router pick its
 * own road between them, ride a spur to a point that snapped onto the wrong
 * carriageway, or swing through a point of interest beside the ride. So a
 * followed track is routed in rounds ([follow]): each round measures where the
 * route leaves the line ([measure]), adds shaping points from the line inside
 * each stretch it skipped, and drops shaping points that did not snap onto the
 * line (no road there); the round that rides the line
 * most closely wins, and what it still misses is reported, never hidden.
 */
object GpxTrackFollow {
    /** Above this mean point spacing a line is a list of stops, not a track. */
    const val DETAILED_MAX_MEAN_SPACING_METERS = 500.0

    /** A line needs this many points to describe roads at all. */
    const val DETAILED_MIN_POINTS = 10

    /** A waypoint this close to the line is one of its stops. */
    const val WAYPOINT_ON_TRACK_METERS = 100.0

    /** A route within this distance of a line point rides that point. */
    const val ON_TRACK_METERS = 35.0

    /** Shorter departures are junction geometry or GPS noise. */
    const val MIN_OFF_TRACK_RUN_METERS = 150.0

    /** Spacing of the first round's shaping points along the line. */
    const val SHAPING_SPACING_METERS = 1_500.0

    /** Spacing of the shaping points added inside a skipped stretch. */
    const val REFINE_SPACING_METERS = 400.0

    /** A new shaping point keeps this far (along the line) from the others. */
    const val MIN_SHAPING_GAP_METERS = 60.0

    /** A shaping point that snapped farther than this has no road on the line. */
    const val MAX_SNAP_METERS = 30.0

    /** Line points are at most this far apart once densified. */
    const val DENSIFY_METERS = 25.0

    /** GraphHopper's practical bound on one request's points. */
    const val MAX_POINTS = 250

    /** Routing rounds per follow, the first included. */
    const val MAX_ROUNDS = 4

    /** Points closer than this are one point. */
    private const val DUPLICATE_POINT_METERS = 10.0

    /** Half-window over which a line point's heading is measured. */
    private const val HEADING_WINDOW_METERS = 30.0

    /** A change of direction at least this sharp is the rider turning round. */
    const val TURNAROUND_DEGREES = 150.0

    /** Shaping points this close (along the line) to a turnaround carry no heading. */
    private const val TURN_CLEARANCE_METERS = 60.0

    /** A leg longer than this many times the line between its points went the long way round. */
    const val DETOUR_FACTOR = 1.5

    /** ...plus this much, so a short leg's junction geometry is not a detour. */
    const val DETOUR_SLACK_METERS = 1_000.0

    /** Decides how [route] is ridden and which of its waypoints are stops. */
    fun plan(route: GpxParser.GpxRoute): GpxRidePlan {
        val raw = route.points
        require(raw.size >= 2) { "A ride needs at least two points" }
        val length = GpxGeometry.lengthMeters(raw)
        val detailed = raw.size >= DETAILED_MIN_POINTS &&
            length / (raw.size - 1) <= DETAILED_MAX_MEAN_SPACING_METERS
        if (route.source == GpxParser.Source.WAYPOINTS) {
            // A waypoint list is its own line: sparse, its stops; dense, a
            // track saved as waypoints.
            return if (detailed) {
                GpxRidePlan(GpxRideMode.FOLLOW_TRACK, densify(GpxGeometry.thin(raw), DENSIFY_METERS), emptyList())
            } else {
                dedupe(raw).let { stops -> GpxRidePlan(GpxRideMode.STOPS, stops, stops.drop(1).dropLast(1)) }
            }
        }
        val thinned = GpxGeometry.thin(raw)
        val line = if (detailed) densify(thinned, DENSIFY_METERS) else dedupe(raw)
        val index = PolylineProximity(line)
        val stops = route.waypoints.filter {
            index.distanceMeters(it, WAYPOINT_ON_TRACK_METERS) <= WAYPOINT_ON_TRACK_METERS
        }
        val mode = if (detailed) GpxRideMode.FOLLOW_TRACK else GpxRideMode.STOPS
        val planStops = if (mode == GpxRideMode.STOPS) mergeStops(line, stops) else stops
        return GpxRidePlan(mode, line, planStops, route.waypoints.size - stops.size)
    }

    /**
     * The routed points of a [GpxRideMode.STOPS] plan, ends included: every
     * stop in line order (the line's own points and the waypoints on it),
     * evenly sampled down to [MAX_POINTS].
     */
    fun stopPoints(plan: GpxRidePlan): List<GeoPoint> {
        val points = dedupe(listOf(plan.line.first()) + plan.stops + plan.line.last())
        return if (points.size <= MAX_POINTS) points else {
            List(MAX_POINTS) { slot -> points[(slot.toLong() * points.lastIndex / (MAX_POINTS - 1)).toInt()] }
        }
    }

    /**
     * The first round's shaping points for following [line], ends included:
     * one every [SHAPING_SPACING_METERS] along the line plus each of [stops]
     * at its nearest line position, at most [MAX_POINTS].
     */
    fun shapingPoints(line: List<GeoPoint>, stops: List<GeoPoint> = emptyList()): List<TrackShapingPoint> {
        require(line.size >= 2) { "A followed line needs at least two points" }
        val cumulative = cumulative(line)
        val total = cumulative.last()
        val closedLoop = GpxGeometry.haversineMeters(line.first(), line.last()) < GpxGeometry.LOOP_THRESHOLD_METERS
        // A closed loop needs a point on the far side, or it routes nowhere.
        // The rider turned round here (a lookout, a café, a missed turn). The
        // router may only turn round at a shaping point, so each turnaround
        // gets one, without a heading (it has two); a heading either side of
        // it would ask for the turn the long way round the network.
        val turns = turnarounds(line, cumulative)
        val reserved = (stops.size + turns.size).coerceAtMost(MAX_POINTS / 2)
        val samples = (ceil(total / SHAPING_SPACING_METERS).toInt() + 1)
            .coerceIn(if (closedLoop) 3 else 2, MAX_POINTS - reserved)
        val picked = ArrayList<TrackShapingPoint>()
        var source = 0
        for (sample in 0 until samples) {
            val target = total * sample / (samples - 1)
            while (source < line.lastIndex && cumulative[source] < target) source++
            val explicit = source == 0 || source == line.lastIndex
            picked += TrackShapingPoint(source, line[source], headingAt(line, cumulative, source), explicit)
        }
        for (stop in stops) {
            val at = nearestIndex(line, stop)
            picked += TrackShapingPoint(at, stop, headingAt(line, cumulative, at), explicit = true)
        }
        for (at in turns) picked += TrackShapingPoint(at, line[at], Double.NaN, explicit = true)
        val headed = picked.map { point ->
            if (turns.any { abs(cumulative[it] - cumulative[point.index]) < TURN_CLEARANCE_METERS }) {
                point.copy(headingDeg = Double.NaN)
            } else {
                point
            }
        }
        return normalise(line, headed)
    }

    /**
     * Where [line] turns back on itself: its direction over the
     * [HEADING_WINDOW_METERS] before a point and after it differ by at least
     * [TURNAROUND_DEGREES]. A tight hairpin may count too, which only adds a
     * harmless unheaded shaping point on the road.
     */
    internal fun turnarounds(line: List<GeoPoint>, cumulative: DoubleArray = cumulative(line)): List<Int> {
        val candidates = ArrayList<Pair<Int, Double>>()
        for (i in 1 until line.lastIndex) {
            var before = i
            while (before > 0 && cumulative[i] - cumulative[before] < HEADING_WINDOW_METERS) before--
            var after = i
            while (after < line.lastIndex && cumulative[after] - cumulative[i] < HEADING_WINDOW_METERS) after++
            // Too little line on either side to read a direction from.
            if (GpxGeometry.haversineMeters(line[before], line[i]) < HEADING_WINDOW_METERS / 2 ||
                GpxGeometry.haversineMeters(line[i], line[after]) < HEADING_WINDOW_METERS / 2
            ) {
                continue
            }
            val inBearing = GeoMath.bearingDeg(line[before].lat, line[before].lon, line[i].lat, line[i].lon)
            val outBearing = GeoMath.bearingDeg(line[i].lat, line[i].lon, line[after].lat, line[after].lon)
            val delta = abs(GeoMath.signedBearingDeltaDeg(inBearing, outBearing))
            if (delta >= TURNAROUND_DEGREES) candidates += i to delta
        }
        // One turnaround per bunch of neighbouring candidates: the sharpest.
        val found = ArrayList<Int>()
        var bunch = ArrayList<Pair<Int, Double>>()
        for (candidate in candidates) {
            val last = bunch.lastOrNull()
            if (last != null && cumulative[candidate.first] - cumulative[last.first] > TURN_CLEARANCE_METERS) {
                found += bunch.maxBy { it.second }.first
                bunch = ArrayList()
            }
            bunch += candidate
        }
        if (bunch.isNotEmpty()) found += bunch.maxBy { it.second }.first
        return found
    }

    /**
     * How closely [route] rides [line]. Both directions are checked: line
     * stretches the route does not ride and route stretches away from the line.
     */
    fun measure(line: List<GeoPoint>, route: List<GeoPoint>): TrackFollowMeasure {
        if (line.size < 2 || route.size < 2) return TrackFollowMeasure(0.0, 0.0)
        val denseRoute = densify(route, DENSIFY_METERS)
        val routeIndex = PolylineProximity(denseRoute)
        val lineIndex = PolylineProximity(line)
        val (offTrack, offRuns) = offRuns(line, routeIndex)
        val (extra, extraRuns) = offRuns(denseRoute, lineIndex)
        return TrackFollowMeasure(offTrack, extra, offRuns, extraRuns)
    }

    /**
     * Routes [line] in rounds starting from [initial] shaping points (ends
     * included) and returns the round that rides the line most closely. A
     * round after the first that fails to route ends the search with the best
     * round so far; a failing first round throws.
     */
    fun <T> follow(
        line: List<GeoPoint>,
        initial: List<TrackShapingPoint>,
        maxRounds: Int = MAX_ROUNDS,
        route: (List<TrackShapingPoint>) -> FollowAttempt<T>,
    ): TrackFollowResult<T> {
        require(initial.size >= 2) { "A followed line needs both its ends" }
        val cumulative = cumulative(line)
        var shaping = initial
        val rejected = ArrayList<Int>()
        // Line indices of shaping points that already ended a detour leg once.
        val suspects = HashSet<Int>()
        var best: TrackFollowResult<T>? = null
        var rounds = 0
        while (rounds < maxRounds) {
            val attempt = try {
                route(shaping)
            } catch (e: Exception) {
                if (best == null) throw e
                break
            }
            rounds++
            val measure = measure(line, attempt.points)
            if (best == null || measure.score < best.measure.score) {
                best = TrackFollowResult(attempt.value, measure, shaping, rounds)
            }
            if (measure.followsTrack) break

            // A shaping point that snapped off the line has no road there
            // (a car park, a ferry, a blocked track): forcing it rides a spur.
            val dropped = shaping.indices.filter { i ->
                val point = shaping[i]
                val snapped = attempt.snapped.getOrNull(i)
                !point.explicit && snapped != null &&
                    GpxGeometry.haversineMeters(snapped, point.point) > MAX_SNAP_METERS
            }.toMutableSet()

            // A leg far longer than the line between its points went the long
            // way round (a U-turn the track made between shaping points, a
            // heading the road could not keep): its ends lose their headings
            // and its middle gets a point of its own. An end that already
            // ended a detour leg once snapped onto the wrong road (the other
            // carriageway of a motorway whose two sides run metres apart):
            // it goes, and the stretch is pinned again from the line.
            val legs = legMeters(attempt.points, attempt.snapped)
            val unheaded = HashSet<Int>()
            val detourMiddles = ArrayList<Int>()
            if (legs != null && legs.size == shaping.size - 1) {
                for (k in legs.indices) {
                    val lineLeg = cumulative[shaping[k + 1].index] - cumulative[shaping[k].index]
                    if (legs[k] > lineLeg * DETOUR_FACTOR + DETOUR_SLACK_METERS) {
                        for (end in listOf(k, k + 1)) {
                            val index = shaping[end].index
                            if (index in suspects && !shaping[end].explicit) dropped += end
                            suspects += index
                            unheaded += end
                        }
                        val middle = cumulative[shaping[k].index] + lineLeg / 2
                        var at = shaping[k].index
                        while (at < shaping[k + 1].index && cumulative[at] < middle) at++
                        detourMiddles += at
                    }
                }
            }
            dropped.forEach { rejected += shaping[it].index }
            val kept = shaping.mapIndexedNotNull { i, point ->
                when {
                    i in dropped -> null
                    i in unheaded -> point.copy(headingDeg = Double.NaN)
                    else -> point
                }
            }

            // Pin every skipped stretch with points from the line itself.
            val added = ArrayList<TrackShapingPoint>()
            for (at in detourMiddles) {
                if (kept.none { abs(cumulative[it.index] - cumulative[at]) < MIN_SHAPING_GAP_METERS } &&
                    rejected.none { abs(cumulative[it] - cumulative[at]) < MIN_SHAPING_GAP_METERS }
                ) {
                    added += TrackShapingPoint(at, line[at], Double.NaN, explicit = false)
                }
            }
            for (run in measure.offTrackRuns) {
                for (at in refineIndices(cumulative, run)) {
                    val tooClose = (kept + added).any { abs(cumulative[it.index] - cumulative[at]) < MIN_SHAPING_GAP_METERS } ||
                        rejected.any { abs(cumulative[it] - cumulative[at]) < MIN_SHAPING_GAP_METERS }
                    if (!tooClose) added += TrackShapingPoint(at, line[at], headingAt(line, cumulative, at), explicit = false)
                }
            }
            val next = normalise(line, kept + added)
            if (next == shaping) break
            shaping = next
        }
        return best!!.copy(attempts = rounds)
    }

    /**
     * The direction [line] rides through each of [points], in order (NaN
     * where a point is not on it): a rebuild of a followed track sends these
     * with the shaping points still ahead. Each point is looked for from where
     * the one before it was found, so a loop's shared start and end, or an
     * out-and-back, read the right pass.
     */
    fun headingsAlong(line: List<GeoPoint>, points: List<GeoPoint>): List<Double> {
        if (line.size < 2) return points.map { Double.NaN }
        val cumulative = cumulative(line)
        var cursor = 0
        return points.map { point ->
            var found = -1
            var nearest = Double.POSITIVE_INFINITY
            for (i in cursor until line.size) {
                val d = GpxGeometry.haversineMeters(point, line[i])
                if (d < nearest) {
                    nearest = d
                    found = i
                }
                // The routed geometry carries each snapped point as a vertex.
                if (d < 1.0) break
            }
            if (found < 0 || nearest > MAX_SNAP_METERS) Double.NaN else {
                cursor = found
                headingAt(line, cumulative, found)
            }
        }
    }

    /** The line's direction at [index], degrees clockwise from north. */
    fun headingAt(line: List<GeoPoint>, index: Int): Double = headingAt(line, cumulative(line), index)

    /** Inserts points so no two neighbours are more than [maxSpacingMeters] apart. */
    fun densify(points: List<GeoPoint>, maxSpacingMeters: Double): List<GeoPoint> {
        if (points.size < 2) return points
        val out = ArrayList<GeoPoint>(points.size)
        out += points.first()
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val steps = ceil(GpxGeometry.haversineMeters(a, b) / maxSpacingMeters).toInt()
            for (step in 1 until steps) {
                val f = step.toDouble() / steps
                out += GeoPoint(a.lat + f * (b.lat - a.lat), a.lon + f * (b.lon - a.lon))
            }
            out += b
        }
        return out
    }

    /**
     * Route metres between consecutive [snapped] points along [route], or null
     * when they cannot be placed on it. The routed geometry carries each
     * snapped point as a vertex, found in order from the one before.
     */
    private fun legMeters(route: List<GeoPoint>, snapped: List<GeoPoint>): List<Double>? {
        if (snapped.size < 2 || route.size < 2) return null
        val along = cumulative(route)
        var cursor = 0
        val positions = snapped.map { point ->
            var at = -1
            for (i in cursor until route.size) {
                if (GpxGeometry.haversineMeters(route[i], point) < 1.0) {
                    at = i
                    break
                }
            }
            if (at < 0) return null
            cursor = at
            along[at]
        }
        return positions.zipWithNext { a, b -> b - a }
    }

    private fun refineIndices(cumulative: DoubleArray, run: IntRange): List<Int> {
        val start = cumulative[run.first]
        val length = cumulative[run.last] - start
        val count = max(1, floor(length / REFINE_SPACING_METERS).toInt())
        return List(count) { slot ->
            val target = start + length * (slot + 0.5) / count
            var at = run.first
            while (at < run.last && cumulative[at] < target) at++
            at
        }.distinct()
    }

    /** Runs of [points] farther than [ON_TRACK_METERS] from [other], with their total length. */
    private fun offRuns(points: List<GeoPoint>, other: PolylineProximity): Pair<Double, List<IntRange>> {
        val cumulative = cumulative(points)
        val runs = ArrayList<IntRange>()
        var total = 0.0
        var runStart = -1
        fun close(end: Int) {
            // From the last ridden point before the run to the first after it.
            val from = cumulative[max(runStart - 1, 0)]
            val to = cumulative[min(end + 1, points.lastIndex)]
            if (to - from >= MIN_OFF_TRACK_RUN_METERS) {
                runs += runStart..end
                total += to - from
            }
            runStart = -1
        }
        for (i in points.indices) {
            val off = other.distanceMeters(points[i], ON_TRACK_METERS) > ON_TRACK_METERS
            if (off && runStart < 0) runStart = i
            if (!off && runStart >= 0) close(i - 1)
        }
        if (runStart >= 0) close(points.lastIndex)
        return total to runs
    }

    /** Sorts by line position, merges near-duplicates (a stop wins), keeps both ends, caps the count. */
    private fun normalise(line: List<GeoPoint>, points: List<TrackShapingPoint>): List<TrackShapingPoint> {
        val sorted = points.sortedWith(compareBy<TrackShapingPoint> { it.index }.thenBy { !it.explicit })
        val merged = ArrayList<TrackShapingPoint>(sorted.size)
        for (candidate in sorted) {
            val last = merged.lastOrNull()
            if (last != null && GpxGeometry.haversineMeters(last.point, candidate.point) < DUPLICATE_POINT_METERS) {
                if (candidate.explicit && !last.explicit) merged[merged.lastIndex] = candidate
            } else {
                merged += candidate
            }
        }
        val first = TrackShapingPoint(0, line.first(), merged.first().headingDeg, explicit = true)
        val last = TrackShapingPoint(line.lastIndex, line.last(), merged.last().headingDeg, explicit = true)
        if (merged.first().index != 0 || merged.first().point != line.first()) {
            if (GpxGeometry.haversineMeters(merged.first().point, line.first()) < DUPLICATE_POINT_METERS) merged[0] = first
            else merged.add(0, first)
        }
        if (merged.last().index != line.lastIndex || merged.last().point != line.last()) {
            if (merged.size > 1 && GpxGeometry.haversineMeters(merged.last().point, line.last()) < DUPLICATE_POINT_METERS) {
                merged[merged.lastIndex] = last
            } else {
                merged += last
            }
        }
        if (merged.size <= MAX_POINTS) return merged
        // Keep the explicit points (evenly, ends included, if even they are
        // too many), then fill evenly from the rest.
        val allExplicit = merged.filter { it.explicit }
        val explicit = if (allExplicit.size <= MAX_POINTS) allExplicit else {
            List(MAX_POINTS) { slot -> allExplicit[(slot.toLong() * allExplicit.lastIndex / (MAX_POINTS - 1)).toInt()] }
        }
        val room = MAX_POINTS - explicit.size
        val others = merged.filter { !it.explicit }
        val sampled = if (room <= 0 || others.isEmpty()) emptyList() else {
            List(min(room, others.size)) { slot ->
                others[(slot.toLong() * others.lastIndex / max(1, min(room, others.size) - 1)).toInt()]
            }.distinct()
        }
        return (explicit + sampled).sortedBy { it.index }
    }

    private fun mergeStops(line: List<GeoPoint>, stops: List<GeoPoint>): List<GeoPoint> {
        val ordered = (line.drop(1).dropLast(1).mapIndexed { i, p -> (i + 1) to p } +
            stops.map { nearestIndex(line, it) to it }).sortedBy { it.first }.map { it.second }
        return dedupe(ordered)
    }

    private fun dedupe(points: List<GeoPoint>): List<GeoPoint> {
        val out = ArrayList<GeoPoint>(points.size)
        for (p in points) {
            if (out.isEmpty() || GpxGeometry.haversineMeters(out.last(), p) >= DUPLICATE_POINT_METERS) out += p
        }
        if (out.size == 1 && points.size >= 2) out += points.last()
        return out
    }

    private fun nearestIndex(line: List<GeoPoint>, point: GeoPoint): Int {
        var best = 0
        var bestDistance = Double.POSITIVE_INFINITY
        for (i in line.indices) {
            val d = GpxGeometry.haversineMeters(point, line[i])
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    private fun cumulative(points: List<GeoPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) out[i] = out[i - 1] + GpxGeometry.haversineMeters(points[i - 1], points[i])
        return out
    }

    private fun headingAt(line: List<GeoPoint>, cumulative: DoubleArray, index: Int): Double {
        var before = index
        while (before > 0 && cumulative[index] - cumulative[before] < HEADING_WINDOW_METERS) before--
        var after = index
        while (after < line.lastIndex && cumulative[after] - cumulative[index] < HEADING_WINDOW_METERS) after++
        if (before == after) return Double.NaN
        val a = line[before]
        val b = line[after]
        if (GpxGeometry.haversineMeters(a, b) < 1.0) return Double.NaN
        return GeoMath.bearingDeg(a.lat, a.lon, b.lat, b.lon)
    }

    private fun abs(value: Double) = kotlin.math.abs(value)
}

/**
 * Nearest-distance lookups against a polyline through a grid of its
 * segments, so measuring a long ride against another stays linear.
 */
internal class PolylineProximity(private val points: List<GeoPoint>, private val cellMeters: Double = 50.0) {
    private val referenceLat = points.firstOrNull()?.lat ?: 0.0
    private val metersPerDegLat = 111_132.0
    private val metersPerDegLon = max(1.0, cos(Math.toRadians(referenceLat)) * 111_320.0)
    private val cells = HashMap<Long, MutableList<Int>>()

    init {
        if (points.size == 1) add(cellOf(points[0]), 0)
        for (segment in 0 until points.size - 1) {
            val a = points[segment]
            val b = points[segment + 1]
            val steps = max(1, ceil(GpxGeometry.haversineMeters(a, b) / (cellMeters / 2)).toInt())
            var previous = Long.MIN_VALUE
            for (step in 0..steps) {
                val f = step.toDouble() / steps
                val cell = cellOf(GeoPoint(a.lat + f * (b.lat - a.lat), a.lon + f * (b.lon - a.lon)))
                if (cell != previous) add(cell, segment)
                previous = cell
            }
        }
    }

    /** Distance from [point] to the polyline, or +∞ when farther than [within]. */
    fun distanceMeters(point: GeoPoint, within: Double): Double {
        if (points.isEmpty()) return Double.POSITIVE_INFINITY
        val reach = ceil(within * 1.2 / cellMeters).toInt() + 1
        val cx = floor(point.lon * metersPerDegLon / cellMeters).toLong()
        val cy = floor(point.lat * metersPerDegLat / cellMeters).toLong()
        var best = Double.POSITIVE_INFINITY
        for (dx in -reach..reach) for (dy in -reach..reach) {
            val segments = cells[key(cx + dx, cy + dy)] ?: continue
            for (segment in segments) {
                val a = points[segment]
                val b = points.getOrNull(segment + 1) ?: a
                val d = GeoMath.segmentDistanceM(point.lat, point.lon, a.lat, a.lon, b.lat, b.lon).first
                if (d < best) best = d
            }
        }
        return if (best <= within) best else Double.POSITIVE_INFINITY
    }

    private fun add(cell: Long, segment: Int) {
        val list = cells.getOrPut(cell) { ArrayList(2) }
        if (list.lastOrNull() != segment) list += segment
    }

    private fun cellOf(p: GeoPoint): Long = key(
        floor(p.lon * metersPerDegLon / cellMeters).toLong(),
        floor(p.lat * metersPerDegLat / cellMeters).toLong(),
    )

    private fun key(x: Long, y: Long): Long = (x shl 32) xor (y and 0xffffffffL)
}
