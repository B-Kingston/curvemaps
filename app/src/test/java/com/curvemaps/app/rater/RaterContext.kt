package com.curvemaps.app.rater

import com.curvemaps.app.geocoding.GeocoderIndex
import com.curvemaps.app.routing.AlternativePolicy
import com.curvemaps.app.routing.curvyDistanceFraction
import com.curvemaps.app.routing.routeClimbMeters
import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.util.FetchMode
import com.graphhopper.util.Instruction
import com.graphhopper.util.shapes.GHPoint
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Context a reviewer (human or agent) needs to judge a ride, beyond its
// whole-route numbers: where each road starts and what it is, odd geometry the
// code can find, the towns passed, and the curvy roads the graph knows nearby.

internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val s = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
    return 2 * 6_371_000.0 * asin(sqrt(min(1.0, s)))
}

/** Cumulative distance per vertex, in metres. */
internal fun cumulativeMeters(lats: DoubleArray, lons: DoubleArray): DoubleArray {
    val out = DoubleArray(lats.size)
    for (i in 1 until lats.size) out[i] = out[i - 1] + haversineMeters(lats[i - 1], lons[i - 1], lats[i], lons[i])
    return out
}

/** One odd-looking spot of a route; km are along the route from A. */
internal data class Oddity(val kind: String, val fromKm: Double, val toKm: Double, val lat: Double, val lon: Double, val detail: String) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("fromKm", num(fromKm, 2)).put("toKm", num(toKm, 2))
        .put("lat", num(lat, 5)).put("lon", num(lon, 5)).put("detail", detail)
}

/** The route resampled every [step] metres: (lat, lon, km). */
private fun resample(lats: DoubleArray, lons: DoubleArray, step: Double): Triple<DoubleArray, DoubleArray, DoubleArray> {
    val cum = cumulativeMeters(lats, lons)
    val total = cum.lastOrNull() ?: 0.0
    val n = (total / step).toInt() + 1
    val la = DoubleArray(n)
    val lo = DoubleArray(n)
    val km = DoubleArray(n)
    var j = 1
    for (k in 0 until n) {
        val d = k * step
        while (j < cum.size - 1 && cum[j] < d) j++
        val span = cum[j] - cum[j - 1]
        val t = if (span > 0) ((d - cum[j - 1]) / span).coerceIn(0.0, 1.0) else 0.0
        la[k] = lats[j - 1] + t * (lats[j] - lats[j - 1])
        lo[k] = lons[j - 1] + t * (lons[j] - lons[j - 1])
        km[k] = d / 1000.0
    }
    return Triple(la, lo, km)
}

/**
 * Geometry a rider would question, found without any map knowledge:
 * - "returns to an earlier point": the ride comes back within [returnMeters] of
 *   where it was at least [minLoopMeters] earlier (loops, out-and-back spurs,
 *   a road driven twice);
 * - "doubles back": over a [windowMeters] stretch the ride ends up less than
 *   [maxChordShare] of that distance from where it started (a U-shaped detour
 *   even when the way back is a parallel road).
 * Overlapping finds merge; the result is ordered along the route.
 */
internal fun geometryOddities(
    lats: DoubleArray,
    lons: DoubleArray,
    returnMeters: Double = 60.0,
    minLoopMeters: Double = 500.0,
    windowMeters: Double = 2_000.0,
    maxChordShare: Double = 0.25,
): List<Oddity> {
    if (lats.size < 2) return emptyList()
    val step = 25.0
    val (la, lo, km) = resample(lats, lons, step)
    val n = la.size
    val spans = mutableListOf<Pair<Int, Int>>()

    // Returns: spatial hash of earlier samples, cell ≈ returnMeters.
    val cellLat = returnMeters / 111_195.0
    val cellLon = cellLat / cos(Math.toRadians(la[0])).coerceAtLeast(0.1)
    val grid = HashMap<Long, MutableList<Int>>()
    fun cy(i: Int) = Math.floor(la[i] / cellLat).toLong()
    fun cx(i: Int) = Math.floor(lo[i] / cellLon).toLong()
    val gap = (minLoopMeters / step).toInt()
    for (j in 0 until n) {
        if (j - gap >= 0) {
            val i = j - gap
            grid.getOrPut(cy(i) * 1_000_003L + cx(i)) { mutableListOf() }.add(i)
        }
        val y = cy(j)
        val x = cx(j)
        var earliest = -1
        for (dy in -1L..1L) for (dx in -1L..1L) {
            grid[(y + dy) * 1_000_003L + (x + dx)]?.forEach { i ->
                if ((earliest < 0 || i < earliest) && haversineMeters(la[i], lo[i], la[j], lo[j]) <= returnMeters) earliest = i
            }
        }
        if (earliest >= 0) spans += earliest to j
    }
    val returns = merge(spans).map { (a, b) ->
        val mid = (a + b) / 2
        Oddity("returns to an earlier point", km[a], km[b], la[mid], lo[mid],
            "comes back within ${returnMeters.toInt()} m of km %.1f at km %.1f (%.1f km round)".format(km[a], km[b], km[b] - km[a]))
    }

    // Doubling back over a window.
    val w = (windowMeters / step).toInt()
    val doubles = mutableListOf<Pair<Int, Int>>()
    if (n > w) for (i in 0 until n - w) {
        val chord = haversineMeters(la[i], lo[i], la[i + w], lo[i + w])
        if (chord < maxChordShare * windowMeters) doubles += i to i + w
    }
    val doubled = merge(doubles).mapNotNull { (a, b) ->
        // Skip stretches that overlap a return (the same spot, already reported).
        if (returns.any { it.fromKm <= km[b] && it.toKm >= km[a] }) return@mapNotNull null
        var far = a
        for (k in a..b) if (haversineMeters(la[a], lo[a], la[k], lo[k]) > haversineMeters(la[a], lo[a], la[far], lo[far])) far = k
        Oddity("doubles back", km[a], km[b], la[far], lo[far],
            "between km %.1f and %.1f the ride heads out and comes back towards where it was (turning point near km %.1f)".format(km[a], km[b], km[far]))
    }
    return (returns + doubled).sortedBy { it.fromKm }
}

private fun merge(spans: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
    val out = mutableListOf<Pair<Int, Int>>()
    spans.sortedBy { it.first }.forEach { (a, b) ->
        val last = out.lastOrNull()
        if (last != null && a <= last.second) out[out.size - 1] = last.first to max(last.second, b) else out += a to b
    }
    return out
}

private val UNPAVED_SURFACES = setOf("unpaved", "compacted", "fine_gravel", "gravel", "ground", "dirt", "grass", "sand")

/**
 * The route road by road: consecutive instructions on one name merge, and each
 * row carries its km range, manoeuvre, dominant road class and surface,
 * unpaved metres, curvy share (the route card's measure), climb and urban share.
 */
internal fun roadSegments(path: ResponsePath, route: AlternativePolicy.CachedRoute, edges: EdgeReader): JSONArray {
    val instructions = try {
        path.instructions.toList()
    } catch (_: IllegalArgumentException) {
        return JSONArray()
    }
    val points = path.points
    val lats = DoubleArray(points.size()) { points.getLat(it) }
    val lons = DoubleArray(points.size()) { points.getLon(it) }
    val cum = cumulativeMeters(lats, lons)
    val scale = if ((cum.lastOrNull() ?: 0.0) > 0) path.distance / cum.last() else 1.0

    // Edge sections along the route, by start metre.
    val sectionStart = DoubleArray(route.edgeSections.size)
    var acc = 0.0
    route.edgeSections.forEachIndexed { i, s -> sectionStart[i] = acc; acc += s.distance }

    class Row(val name: String, val turn: Int, val p0: Int, var p1: Int)
    val rows = mutableListOf<Row>()
    var at = 0
    instructions.forEach { instruction ->
        val start = at
        at += instruction.length
        if (instruction.sign == Instruction.FINISH) return@forEach
        val label = instruction.name.ifBlank { instruction.extraInfoJSON["street_ref"]?.toString().orEmpty() }.ifBlank { "(unnamed)" }
        val uTurn = instruction.sign in setOf(Instruction.U_TURN_UNKNOWN, Instruction.U_TURN_LEFT, Instruction.U_TURN_RIGHT)
        val last = rows.lastOrNull()
        if (last != null && last.name == label && !uTurn) last.p1 = min(at, points.size() - 1)
        else rows += Row(label, instruction.sign, start, min(at, points.size() - 1))
    }
    val out = JSONArray()
    rows.forEach { row ->
        val fromM = cum[row.p0] * scale
        val toM = cum[row.p1] * scale
        val classKm = HashMap<String, Double>()
        val surfaceKm = HashMap<String, Double>()
        var unpaved = 0.0
        var urban = 0.0
        var known = 0.0
        route.edgeSections.forEachIndexed { i, s ->
            val mid = sectionStart[i] + s.distance / 2
            if (mid < fromM || mid >= toM) return@forEachIndexed
            val e = edges.edge(s.edgeId) ?: return@forEachIndexed
            known += s.distance
            classKm.merge(e.roadClass, s.distance, Double::plus)
            surfaceKm.merge(e.surface, s.distance, Double::plus)
            if (e.surface in UNPAVED_SURFACES) unpaved += s.distance
            if (e.urban == "residential" || e.urban == "city") urban += s.distance
        }
        val sub = if (row.p1 > row.p0) points.copy(row.p0, row.p1 + 1) else null
        out.put(
            JSONObject()
                .put("fromKm", num(fromM / 1000, 2)).put("toKm", num(toM / 1000, 2))
                .put("name", row.name).put("turn", turnWord(row.turn))
                .put("roadClass", classKm.maxByOrNull { it.value }?.key ?: "?")
                .put("surface", surfaceKm.maxByOrNull { it.value }?.key ?: "?")
                .put("unpavedM", num(unpaved, 0))
                .put("curvyShare", sub?.let { num(curvyDistanceFraction(it), 2) } ?: 0)
                .put("climbM", sub?.let { num(routeClimbMeters(it), 0) } ?: 0)
                .put("urbanShare", num(if (known > 0) urban / known else 0.0, 2))
                .put("lat", num(lats[(row.p0 + row.p1) / 2], 5)).put("lon", num(lons[(row.p0 + row.p1) / 2], 5))
                .put("startLat", num(lats[row.p0], 5)).put("startLon", num(lons[row.p0], 5)),
        )
    }
    return out
}

/**
 * "leaves and rejoins a road": a named road the ride leaves for other roads
 * and comes back onto within [maxAwayKm] (a block jog, an exit-and-rejoin, an
 * Eagle Tce → Skew St → Eagle Tce zig-zag), too short for [geometryOddities].
 * It counts only when the stretch off the road really wanders: at least
 * [minDetourRatio] × and [minExtraMeters] longer than the straight line from
 * where it leaves to where it rejoins, so a road renamed through a town
 * (D'Aguilar Hwy → Archer St → D'Aguilar Hwy) is not a jog. Rows of
 * [segments] (see [roadSegments]) that are only a via split are ignored.
 */
internal fun reentryOddities(
    segments: JSONArray,
    maxAwayKm: Double = 1.0,
    minDetourRatio: Double = 1.4,
    minExtraMeters: Double = 100.0,
): List<Oddity> {
    val rows = (0 until segments.length()).map { segments.getJSONObject(it) }
        .filter { it.getString("turn") != "via" || it.getDouble("toKm") - it.getDouble("fromKm") >= 0.03 }
    val found = mutableListOf<Oddity>()
    var i = 0
    while (i < rows.size) {
        val name = rows[i].getString("name")
        val left = rows[i].getDouble("toKm")
        var hit: Oddity? = null
        var end = i
        if (!name.startsWith("(unnamed")) for (j in i + 2 until rows.size) {
            val back = rows[j].getDouble("fromKm")
            if (back - left > maxAwayKm) break
            if (rows[j].getString("name") != name) continue
            val away = (back - left) * 1000
            val chord = haversineMeters(rows[i + 1].getDouble("startLat"), rows[i + 1].getDouble("startLon"), rows[j].getDouble("startLat"), rows[j].getDouble("startLon"))
            if (away >= minDetourRatio * chord && away - chord >= minExtraMeters) {
                val middle = rows.subList(i + 1, j)
                val mid = middle[middle.size / 2]
                hit = Oddity("leaves and rejoins a road", left, back, mid.getDouble("lat"), mid.getDouble("lon"),
                    "leaves $name at km %.2f and is back on it at km %.2f, %.0f m on %s for %.0f m of progress".format(
                        left, back, away, middle.joinToString(" → ") { it.getString("name") }, chord))
                end = j
            }
            break
        }
        if (hit != null) found += hit
        i = if (hit != null) end else i + 1
    }
    return found
}

/**
 * "street tangle": a [windowMeters] stretch that ends less than [maxChordShare]
 * of that distance from where it started while touching at least [minRoads]
 * roads (km ranges from [segments]): turning back and forth through streets.
 * A hairpin on one mountain road touches one road and is not a tangle, and
 * stretches starting within [endMeters] of A or ending within it of B are
 * skipped: turning round to leave a pin dropped mid-street is not a tangle.
 */
internal fun tangleOddities(
    lats: DoubleArray,
    lons: DoubleArray,
    segments: JSONArray,
    windowMeters: Double = 500.0,
    maxChordShare: Double = 0.45,
    minRoads: Int = 3,
    endMeters: Double = 250.0,
): List<Oddity> {
    if (lats.size < 2) return emptyList()
    val step = 25.0
    val (la, lo, km) = resample(lats, lons, step)
    val rows = (0 until segments.length()).map { segments.getJSONObject(it) }
        .filter { it.getString("turn") != "via" && it.getDouble("toKm") > it.getDouble("fromKm") }
    val w = (windowMeters / step).toInt()
    val spans = mutableListOf<Pair<Int, Int>>()
    val totalKm = km.last()
    for (i in 0 until la.size - w) {
        if (km[i] < endMeters / 1000 || km[i + w] > totalKm - endMeters / 1000) continue
        if (haversineMeters(la[i], lo[i], la[i + w], lo[i + w]) >= maxChordShare * windowMeters) continue
        val roads = rows.count { it.getDouble("fromKm") < km[i + w] && it.getDouble("toKm") > km[i] }
        if (roads >= minRoads) spans += i to i + w
    }
    return merge(spans).map { (a, b) ->
        val names = rows.filter { it.getDouble("fromKm") < km[b] && it.getDouble("toKm") > km[a] }.map { it.getString("name") }
        val mid = (a + b) / 2
        Oddity("street tangle", km[a], km[b], la[mid], lo[mid],
            "km %.2f–%.2f drives %.0f m to end %.0f m from where it began, through %s".format(
                km[a], km[b], (km[b] - km[a]) * 1000, haversineMeters(la[a], lo[a], la[b], lo[b]), names.joinToString(" → ")))
    }
}

/** Every oddity of a shown ride: geometry, road re-entries, street tangles, U-turn instructions and unpaved stretches (≥200 m). */
internal fun routeOddities(path: ResponsePath, segments: JSONArray): JSONArray {
    val points = path.points
    val lats = DoubleArray(points.size()) { points.getLat(it) }
    val lons = DoubleArray(points.size()) { points.getLon(it) }
    val geometric = geometryOddities(lats, lons)
    // A re-entry inside a loop or detour the geometry already found is the same spot.
    val reentries = reentryOddities(segments).filter { r -> geometric.none { it.fromKm <= r.toKm && it.toKm >= r.fromKm } }
    val tangles = tangleOddities(lats, lons, segments).filter { t -> (geometric + reentries).none { it.fromKm <= t.toKm && it.toKm >= t.fromKm } }
    val found = (geometric + reentries + tangles).toMutableList()
    for (i in 0 until segments.length()) {
        val s = segments.getJSONObject(i)
        if (s.getString("turn") == "U-TURN") {
            found += Oddity("U-turn instruction", s.getDouble("fromKm"), s.getDouble("fromKm"), s.getDouble("lat"), s.getDouble("lon"), "U-turn onto ${s.getString("name")}")
        }
        if (s.getDouble("unpavedM") >= 200) {
            found += Oddity("unpaved", s.getDouble("fromKm"), s.getDouble("toKm"), s.getDouble("lat"), s.getDouble("lon"),
                "${s.getInt("unpavedM")} m unpaved (${s.getString("surface")}) on ${s.getString("name")}")
        }
    }
    return JSONArray(found.sortedBy { it.fromKm }.map { it.toJson() })
}

/** The geocoder's locality kinds by `subType` (`GeocoderIndexBuilder.LOCALITY_SUBTYPE`); 4 is a state. */
private val LOCALITY_KINDS = mapOf(0 to "city", 1 to "town", 2 to "village", 3 to "suburb", 5 to "other")

/**
 * Named places inside a box that a trip could start or end at: every locality except states, with its kind and
 * rank (city 235, town 220, village 195, suburb 185, hamlet 165, plus up to 15 for population). One index scan.
 */
internal fun placesIn(geocoder: GeocoderIndex, south: Double, west: Double, north: Double, east: Double): JSONArray {
    val out = JSONArray()
    for (id in 0 until geocoder.docCount) {
        if (geocoder.docType(id) != GeocoderIndex.TYPE_LOCALITY) continue
        val doc = geocoder.doc(id)
        val kind = LOCALITY_KINDS[doc.subType] ?: continue
        if (doc.lat !in south..north || doc.lon !in west..east || doc.name.isBlank()) continue
        out.put(JSONObject().put("name", doc.name).put("kind", kind).put("rank", doc.rank).put("lat", doc.lat).put("lon", doc.lon))
    }
    return out
}

/** [[km, town], ...] every ~[everyKm] along each path, consecutive repeats dropped. One geocoder scan for all paths. */
internal fun placesAlong(paths: List<ResponsePath>, geocoder: GeocoderIndex?, everyKm: (Double) -> Double): List<JSONArray> {
    if (geocoder == null) return paths.map { JSONArray() }
    val samples = paths.map { path ->
        val points = path.points
        val lats = DoubleArray(points.size()) { points.getLat(it) }
        val lons = DoubleArray(points.size()) { points.getLon(it) }
        val cum = cumulativeMeters(lats, lons)
        val step = everyKm(path.distance / 1000) * 1000
        val out = mutableListOf<Triple<Double, Double, Double>>()
        var next = 0.0
        for (i in cum.indices) if (cum[i] >= next || i == cum.lastIndex) {
            out += Triple(cum[i] / 1000, lats[i], lons[i])
            next = cum[i] + step
        }
        out
    }
    val hits = geocoder.reverseGeocode(samples.flatten().map { it.second to it.third })
    var k = 0
    return samples.map { list ->
        val out = JSONArray()
        var last = ""
        list.forEach { (km, _, _) ->
            val hit = hits[k++]
            val town = hit?.let { it.detail.ifBlank { it.name } } ?: return@forEach
            if (town != last) out.put(JSONArray().put(num(km, 1)).put(town))
            last = town
        }
        out
    }
}

// Motorways are never a riding road, and their named ramps read as curvy km.
private val NOT_RIDING_ROADS = setOf("motorway", "residential", "service", "track", "living_street", "footway", "path", "cycleway", "pedestrian", "steps", "bridleway", "construction", "other")

/**
 * Named non-motorway, non-residential roads inside the corridor (an ellipse
 * with foci A and B: every point a ride of [maxSumMeters] could reach) ranked by curvy km (edge curve exposure ×
 * length, the planner's own measure), with how much of each road every stop
 * rides. Gives a reviewer local road knowledge from the graph itself.
 */
internal fun corridorRoads(
    hopper: GraphHopper,
    edges: EdgeReader,
    from: GHPoint,
    to: GHPoint,
    maxSumMeters: Double,
    stops: List<AlternativePolicy.CachedRoute>,
    limit: Int = 25,
    geometryRoads: Int = 15,
): JSONArray {
    val graph = hopper.baseGraph
    val nodes = graph.nodeAccess
    class Road(val name: String) {
        var km = 0.0
        var curvyKm = 0.0
        var unpavedKm = 0.0
        val classKm = HashMap<String, Double>()
        val edgeIds = mutableListOf<Int>()
        var bestEdge = -1
        var bestScore = 0.0
    }
    val roads = HashMap<String, Road>()
    val iterator = graph.allEdges
    while (iterator.next()) {
        val a = iterator.baseNode
        val b = iterator.adjNode
        val lat = (nodes.getLat(a) + nodes.getLat(b)) / 2
        val lon = (nodes.getLon(a) + nodes.getLon(b)) / 2
        val sum = haversineMeters(from.lat, from.lon, lat, lon) + haversineMeters(lat, lon, to.lat, to.lon)
        if (sum > maxSumMeters) continue
        val name = iterator.name
        if (name.isNullOrBlank()) continue
        val edge = edges.edge(iterator.edge) ?: continue
        if (edge.roadClass in NOT_RIDING_ROADS) continue
        val road = roads.getOrPut(name) { Road(name) }
        val km = iterator.distance / 1000
        road.km += km
        road.curvyKm += km * edge.curveExposure
        if (edge.surface in UNPAVED_SURFACES) road.unpavedKm += km
        road.classKm.merge(edge.roadClass, km, Double::plus)
        road.edgeIds += iterator.edge
        if (km * edge.curveExposure > road.bestScore) {
            road.bestScore = km * edge.curveExposure
            road.bestEdge = iterator.edge
        }
    }
    val ranked = roads.values.filter { it.curvyKm >= 0.5 }.sortedByDescending { it.curvyKm }.take(limit)
    val out = JSONArray()
    ranked.forEachIndexed { rank, road ->
        val used = JSONObject()
        stops.forEachIndexed { level, stop ->
            val m = road.edgeIds.sumOf { stop.edgeDistances[it] ?: 0.0 }
            if (m >= 200) used.put((level + 1).toString(), num(m / 1000, 1))
        }
        val best = graph.getEdgeIteratorState(road.bestEdge, Int.MIN_VALUE).fetchWayGeometry(FetchMode.ALL)
        val json = JSONObject()
            .put("name", road.name)
            .put("roadClass", road.classKm.maxByOrNull { it.value }?.key ?: "?")
            .put("km", num(road.km, 1))
            .put("curvyKm", num(road.curvyKm, 1))
            .put("unpavedKm", num(road.unpavedKm, 1))
            .put("curviestAt", JSONArray().put(num(best.getLat(best.size() / 2), 5)).put(num(best.getLon(best.size() / 2), 5)))
            .put("usedKmByStop", used)
        if (rank < geometryRoads) {
            // Each edge as a thin polyline for map overlays (vertices ≥40 m apart).
            val lines = JSONArray()
            road.edgeIds.forEach { id ->
                val g = graph.getEdgeIteratorState(id, Int.MIN_VALUE).fetchWayGeometry(FetchMode.ALL)
                val line = JSONArray()
                var lastLat = Double.NaN
                var lastLon = Double.NaN
                for (i in 0 until g.size()) {
                    val la = g.getLat(i)
                    val lo = g.getLon(i)
                    if (i in 1 until g.size() - 1 && haversineMeters(lastLat, lastLon, la, lo) < 40) continue
                    line.put(JSONArray().put(num(la, 5)).put(num(lo, 5)))
                    lastLat = la
                    lastLon = lo
                }
                lines.put(line)
            }
            json.put("lines", lines)
        }
        out.put(json)
    }
    return out
}

/**
 * Where a look-around via point changed a stop's ride: the continuous stretch
 * around the via (shared gaps under 150 m bridged) that the stop's unforced
 * optimum does not drive, and the optimum stretch it replaces (between the
 * last shared road before and the first after). Virtual edges (query-graph
 * splits at A, B and the via) take the state of the road before them.
 */
internal fun viaDetour(ride: AlternativePolicy.CachedRoute, optimum: AlternativePolicy.CachedRoute, via: GHPoint, edges: EdgeReader): JSONObject? {
    val sections = ride.edgeSections
    if (sections.isEmpty()) return null
    val start = DoubleArray(sections.size)
    var acc = 0.0
    sections.forEachIndexed { i, s -> start[i] = acc; acc += s.distance }
    val shared = BooleanArray(sections.size)
    var last = true
    sections.forEachIndexed { i, s ->
        last = if (edges.edge(s.edgeId) == null) last else s.edgeId in optimum.edgeDistances
        shared[i] = last
    }
    // Bridge short shared gaps.
    var i = 0
    while (i < sections.size) {
        if (shared[i]) {
            var j = i
            var gap = 0.0
            while (j < sections.size && shared[j]) { gap += sections[j].distance; j++ }
            if (i > 0 && j < sections.size && gap < 150.0) for (k in i until j) shared[k] = false
            i = j
        } else i++
    }
    // Via position along the ride.
    val points = ride.path.points
    val lats = DoubleArray(points.size()) { points.getLat(it) }
    val lons = DoubleArray(points.size()) { points.getLon(it) }
    val cum = cumulativeMeters(lats, lons)
    val scale = if ((cum.lastOrNull() ?: 0.0) > 0) ride.path.distance / cum.last() else 1.0
    val nearest = lats.indices.minByOrNull { haversineMeters(lats[it], lons[it], via.lat, via.lon) } ?: return null
    val viaM = cum[nearest] * scale
    // The unshared run containing the via (or the nearest one).
    val runs = mutableListOf<IntRange>()
    i = 0
    while (i < sections.size) {
        if (!shared[i]) {
            var j = i
            while (j + 1 < sections.size && !shared[j + 1]) j++
            runs += i..j
            i = j + 1
        } else i++
    }
    if (runs.isEmpty()) return null
    fun distanceTo(r: IntRange): Double {
        val a = start[r.first]
        val b = start[r.last] + sections[r.last].distance
        return if (viaM in a..b) 0.0 else min(kotlin.math.abs(viaM - a), kotlin.math.abs(viaM - b))
    }
    val run = runs.minByOrNull(::distanceTo)!!
    val fromM = start[run.first]
    val toM = start[run.last] + sections[run.last].distance
    // The optimum stretch between the roads where the ride leaves and rejoins it.
    val optStart = HashMap<Int, Pair<Double, Double>>()
    acc = 0.0
    optimum.edgeSections.forEach { s -> optStart.putIfAbsent(s.edgeId, acc to acc + s.distance); acc += s.distance }
    val leave = (run.first - 1 downTo 0).firstOrNull { edges.edge(sections[it].edgeId) != null }?.let { optStart[sections[it].edgeId]?.second } ?: 0.0
    val rejoin = (run.last + 1 until sections.size).firstOrNull { edges.edge(sections[it].edgeId) != null }?.let { optStart[sections[it].edgeId]?.first } ?: acc
    return JSONObject()
        .put("fromKm", num(fromM / 1000, 2)).put("toKm", num(toM / 1000, 2))
        .put("viaKm", num(viaM / 1000, 2))
        .put("detourKm", num((toM - fromM) / 1000, 2))
        .put("replacesKm", num(max(0.0, rejoin - leave) / 1000, 2))
        .put("optimumFromKm", num(leave / 1000, 2)).put("optimumToKm", num(rejoin / 1000, 2))
}
