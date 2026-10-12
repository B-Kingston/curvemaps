package com.curvemaps.app.rater

import com.curvemaps.app.routing.AlternativePolicy
import com.curvemaps.app.routing.METERS_PER_DEGREE
import com.curvemaps.app.routing.MIN_CURVE_SEGMENT_METERS
import com.curvemaps.app.routing.circumradiusMeters
import com.curvemaps.app.routing.combinedCurveExposure
import com.curvemaps.app.routing.curveRadiusWeight
import com.curvemaps.app.routing.curvyDistanceFraction
import com.curvemaps.app.routing.hillExposure
import com.curvemaps.app.routing.routeClimbMeters
import com.curvemaps.app.routing.routeStopCount
import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.routing.ev.DecimalEncodedValue
import com.graphhopper.routing.ev.EnumEncodedValue
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.RoadEnvironment
import com.graphhopper.routing.ev.Surface
import com.graphhopper.routing.ev.UrbanDensity
import com.graphhopper.util.FetchMode
import com.graphhopper.util.Instruction
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** What the rater knows about one stored edge, read once per region. */
internal class EdgeInfo(
    val roadClass: String,
    val surface: String,
    val urban: String,
    val environment: String,
    val curveExposure: Double,
    val hillExposure: Double,
    val speedKmh: Double,
)

/** Reads edge attributes straight from the loaded graph (no request weighting involved). */
internal class EdgeReader(hopper: GraphHopper) {
    private val graph = hopper.baseGraph
    private val em = hopper.encodingManager
    private val roadClass: EnumEncodedValue<RoadClass> = em.getEnumEncodedValue(RoadClass.KEY, RoadClass::class.java)
    private val surface: EnumEncodedValue<Surface> = em.getEnumEncodedValue(Surface.KEY, Surface::class.java)
    private val urban: EnumEncodedValue<UrbanDensity>? =
        if (em.hasEncodedValue(UrbanDensity.KEY)) em.getEnumEncodedValue(UrbanDensity.KEY, UrbanDensity::class.java) else null
    private val environment: EnumEncodedValue<RoadEnvironment>? =
        if (em.hasEncodedValue(RoadEnvironment.KEY)) em.getEnumEncodedValue(RoadEnvironment.KEY, RoadEnvironment::class.java) else null
    private val speed: DecimalEncodedValue? =
        if (em.hasEncodedValue("car_average_speed")) em.getDecimalEncodedValue("car_average_speed") else null
    private val cache = ConcurrentHashMap<Int, EdgeInfo>()

    /** Null for virtual (QueryGraph) edge ids, which have no stored attributes. */
    fun edge(id: Int): EdgeInfo? {
        if (id < 0 || id >= graph.edges) return null
        return cache.getOrPut(id) {
            val state = graph.getEdgeIteratorState(id, Int.MIN_VALUE)
            val geometry = state.fetchWayGeometry(FetchMode.ALL)
            EdgeInfo(
                roadClass = state.get(roadClass).toString(),
                surface = state.get(surface).toString(),
                urban = urban?.let { state.get(it).toString() } ?: "unknown",
                environment = environment?.let { state.get(it).toString() } ?: "unknown",
                curveExposure = combinedCurveExposure(geometry, state.distance),
                hillExposure = hillExposure(geometry, state.distance),
                speedKmh = speed?.let { max(state.get(it), state.getReverse(it)) } ?: Double.NaN,
            )
        }
    }
}

/** Sustained curvy stretches along a polyline, with the route card's corner definition. */
internal data class CurvyRuns(
    val curvyMeters: Double,
    val totalMeters: Double,
    val longestRunMeters: Double,
    val runsOver2Km: Int,
    val runsOver5Km: Int,
    /** Distinct bends: consecutive corner windows (radius < 175 m) count once. */
    val bends: Int,
    /** Distinct tight bends (radius < 60 m). */
    val tightBends: Int,
)

/**
 * A run is a sequence of curvy segments; straight gaps up to [maxGapMeters]
 * are bridged (a short straight between two bends is still the same twisty
 * road) and count toward the run, trailing gaps do not.
 */
internal fun curvyRuns(lats: DoubleArray, lons: DoubleArray, maxGapMeters: Double = 300.0): CurvyRuns {
    val count = lats.size
    if (count < 2) return CurvyRuns(0.0, 0.0, 0.0, 0, 0, 0, 0)
    fun distance(a: Int, b: Int): Double {
        val scale = cos(Math.toRadians((lats[a] + lats[b]) / 2.0))
        return hypot((lons[b] - lons[a]) * scale, lats[b] - lats[a]) * METERS_PER_DEGREE
    }
    val lengths = DoubleArray(count - 1) { distance(it, it + 1) }
    val curvy = BooleanArray(count - 1)
    var bends = 0
    var tightBends = 0
    var inBend = false
    var inTight = false
    for (start in 0 until count - 2) {
        val a = lengths[start]
        val b = lengths[start + 1]
        var corner = false
        var tight = false
        if (a >= MIN_CURVE_SEGMENT_METERS && b >= MIN_CURVE_SEGMENT_METERS) {
            val radius = circumradiusMeters(a, b, distance(start, start + 2))
            if (curveRadiusWeight(radius) > 0.0) {
                corner = true
                tight = radius < 60.0
                curvy[start] = true
                curvy[start + 1] = true
            }
        }
        if (corner && !inBend) bends++
        if (tight && !inTight) tightBends++
        inBend = corner
        inTight = tight
    }
    var curved = 0.0
    var total = 0.0
    var longest = 0.0
    var over2 = 0
    var over5 = 0
    var run = 0.0
    var gap = 0.0
    var inRun = false
    fun close() {
        if (run >= 2_000.0) over2++
        if (run >= 5_000.0) over5++
    }
    lengths.forEachIndexed { index, length ->
        total += length
        if (curvy[index]) {
            curved += length
            run = if (inRun) run + gap + length else length
            inRun = true
            gap = 0.0
            longest = max(longest, run)
        } else if (inRun) {
            gap += length
            if (gap > maxGapMeters) {
                close()
                inRun = false
                gap = 0.0
            }
        }
    }
    if (inRun) close()
    return CurvyRuns(curved, total, longest, over2, over5, bends, tightBends)
}

/** Stable id of a ride on one graph: its edge sequence, a via split counted once. */
internal fun routeKey(route: AlternativePolicy.CachedRoute): String {
    val ids = StringBuilder()
    var previous = Int.MIN_VALUE
    route.edgeSections.forEach {
        if (it.edgeId != previous) ids.append(it.edgeId).append(',')
        previous = it.edgeId
    }
    return sha256(ids.toString()).take(12)
}

internal fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** Non-finite numbers become JSON null; finite ones are rounded to [digits]. */
internal fun num(value: Double, digits: Int = 4): Any {
    if (!value.isFinite()) return JSONObject.NULL
    val scale = Math.pow(10.0, digits.toDouble())
    return Math.round(value * scale) / scale
}

private val NOT_A_TURN = setOf(Instruction.CONTINUE_ON_STREET, Instruction.FINISH, Instruction.REACHED_VIA)

private val MOTORWAYS = setOf("motorway", "trunk")
private val MINOR = setOf("residential", "service", "living_street", "track")
private val UNPAVED = setOf("unpaved", "compacted", "fine_gravel", "gravel", "ground", "dirt", "grass", "sand")

/**
 * Whole-route features of [path]: everything a whole-route scorer could
 * weigh. Stop-independent; per-stop ratios are added by the planner.
 */
internal fun routeFeatures(path: ResponsePath, route: AlternativePolicy.CachedRoute, edges: EdgeReader, fastest: ResponsePath): JSONObject {
    val points = path.points
    val lats = DoubleArray(points.size()) { points.getLat(it) }
    val lons = DoubleArray(points.size()) { points.getLon(it) }
    val runs = curvyRuns(lats, lons)
    val km = path.distance / 1000.0

    val roadClassKm = sortedMapOf<String, Double>()
    val surfaceKm = sortedMapOf<String, Double>()
    val urbanKm = sortedMapOf<String, Double>()
    val environmentKm = sortedMapOf<String, Double>()
    var known = 0.0
    var exposure = 0.0
    var hill = 0.0
    var straight = 0.0
    var veryCurvy = 0.0
    route.edgeSections.forEach { section ->
        val d = section.distance / 1000.0
        val edge = edges.edge(section.edgeId)
        if (edge == null) {
            roadClassKm.merge("virtual", d, Double::plus)
            return@forEach
        }
        known += d
        roadClassKm.merge(edge.roadClass, d, Double::plus)
        surfaceKm.merge(edge.surface, d, Double::plus)
        urbanKm.merge(edge.urban, d, Double::plus)
        environmentKm.merge(edge.environment, d, Double::plus)
        exposure += edge.curveExposure * d
        hill += edge.hillExposure * d
        if (edge.curveExposure < 0.1) straight += d
        if (edge.curveExposure >= 0.5) veryCurvy += d
    }
    fun share(map: Map<String, Double>, keys: Set<String>) =
        if (known > 0) map.filterKeys { it in keys }.values.sum() / known else Double.NaN
    val instructions = try {
        path.instructions.toList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }
    val turns = instructions.count { it.sign !in NOT_A_TURN }
    val stops = routeStopCount(path)
    fun kmMap(map: Map<String, Double>) = JSONObject().apply { map.forEach { (k, v) -> put(k, num(v, 3)) } }

    return JSONObject()
        .put("timeMin", num(path.time / 60_000.0, 2))
        .put("distanceKm", num(km, 3))
        .put("avgKmh", num(if (path.time > 0) km / (path.time / 3_600_000.0) else Double.NaN, 1))
        .put("curvyShare", num(curvyDistanceFraction(points)))
        .put("curvyKm", num(runs.curvyMeters / 1000.0, 3))
        .put("longestCurvyRunKm", num(runs.longestRunMeters / 1000.0, 3))
        .put("curvyRunsOver2Km", runs.runsOver2Km)
        .put("curvyRunsOver5Km", runs.runsOver5Km)
        .put("bendsPerKm", num(if (km > 0) runs.bends / km else Double.NaN, 3))
        .put("tightBendsPerKm", num(if (km > 0) runs.tightBends / km else Double.NaN, 3))
        .put("meanEdgeCurveExposure", num(if (known > 0) exposure / known else Double.NaN))
        .put("meanEdgeHillExposure", num(if (known > 0) hill / known else Double.NaN))
        .put("straightEdgeShare", num(if (known > 0) straight / known else Double.NaN))
        .put("veryCurvyEdgeShare", num(if (known > 0) veryCurvy / known else Double.NaN))
        .put("climbM", num(routeClimbMeters(points), 0))
        .put("rawAscendM", num(path.ascend, 0))
        .put("stops", stops ?: JSONObject.NULL)
        .put("stopsPer10Km", if (stops != null && km > 0) num(stops / km * 10, 2) else JSONObject.NULL)
        .put("turns", turns)
        .put("turnsPer10Km", num(if (km > 0) turns / km * 10 else Double.NaN, 2))
        .put("motorwayTrunkShare", num(share(roadClassKm, MOTORWAYS)))
        .put("minorRoadShare", num(share(roadClassKm, MINOR)))
        .put("unknownSurfaceShare", num(share(surfaceKm, setOf("missing", "other"))))
        .put("unpavedShare", num(share(surfaceKm, UNPAVED)))
        .put("urbanShare", num(share(urbanKm, setOf("residential", "city"))))
        .put("timeVsFastest", num(path.time.toDouble() / fastest.time))
        .put("distanceVsFastest", num(path.distance / fastest.distance))
        .put("roadClassKm", kmMap(roadClassKm))
        .put("surfaceKm", kmMap(surfaceKm))
        .put("urbanKm", kmMap(urbanKm))
        .put("environmentKm", kmMap(environmentKm))
        .put("edgeCount", route.edgeDistances.size)
}

/** [[lat, lon], ...] at 5 decimals; [minSpacingMeters] > 0 drops vertices closer than that. */
internal fun geometry(path: ResponsePath, minSpacingMeters: Double = 0.0): JSONArray {
    val points = path.points
    val out = JSONArray()
    var lastLat = Double.NaN
    var lastLon = Double.NaN
    for (i in 0 until points.size()) {
        val lat = points.getLat(i)
        val lon = points.getLon(i)
        if (minSpacingMeters > 0 && i > 0 && i < points.size() - 1 && lastLat.isFinite()) {
            val d = hypot((lon - lastLon) * cos(Math.toRadians(lat)), lat - lastLat) * METERS_PER_DEGREE
            if (d < minSpacingMeters) continue
        }
        out.put(JSONArray().put(num(lat, 5)).put(num(lon, 5)))
        lastLat = lat
        lastLon = lon
    }
    return out
}

/**
 * The named roads a route drives, in order, as [[name, km, turnsInto], ...]:
 * consecutive instructions on the same name merge, unnamed stretches read
 * "(unnamed <class>)" from the instruction's street ref when GraphHopper has none.
 */
internal fun roadSequence(path: ResponsePath): JSONArray {
    val instructions = try {
        path.instructions.toList()
    } catch (_: IllegalArgumentException) {
        return JSONArray()
    }
    val out = JSONArray()
    var name: String? = null
    var meters = 0.0
    var sign = 0
    fun flush() {
        name?.let { out.put(JSONArray().put(it).put(num(meters / 1000.0, 2)).put(turnWord(sign))) }
    }
    instructions.forEach { instruction ->
        if (instruction.sign == Instruction.FINISH) return@forEach
        val label = instruction.name.ifBlank { instruction.extraInfoJSON["street_ref"]?.toString().orEmpty() }.ifBlank { "(unnamed)" }
        if (label == name && instruction.sign != Instruction.U_TURN_UNKNOWN && instruction.sign != Instruction.U_TURN_LEFT && instruction.sign != Instruction.U_TURN_RIGHT) {
            meters += instruction.distance
        } else {
            flush()
            name = label
            meters = instruction.distance
            sign = instruction.sign
        }
    }
    flush()
    return out
}

internal fun turnWord(sign: Int): String = when (sign) {
    Instruction.CONTINUE_ON_STREET -> "continue"
    Instruction.TURN_SLIGHT_LEFT -> "slight left"
    Instruction.TURN_LEFT -> "left"
    Instruction.TURN_SHARP_LEFT -> "sharp left"
    Instruction.TURN_SLIGHT_RIGHT -> "slight right"
    Instruction.TURN_RIGHT -> "right"
    Instruction.TURN_SHARP_RIGHT -> "sharp right"
    Instruction.KEEP_LEFT -> "keep left"
    Instruction.KEEP_RIGHT -> "keep right"
    Instruction.USE_ROUNDABOUT -> "roundabout"
    Instruction.LEAVE_ROUNDABOUT -> "leave roundabout"
    Instruction.U_TURN_UNKNOWN, Instruction.U_TURN_LEFT, Instruction.U_TURN_RIGHT -> "U-TURN"
    Instruction.REACHED_VIA -> "via"
    else -> "sign $sign"
}
