package com.curvemaps.app.rater

import com.curvemaps.app.routing.AlternativePolicy
import com.curvemaps.app.routing.DistinctRideStyles
import com.curvemaps.app.routing.curvyDistanceFraction
import com.curvemaps.app.routing.MOTO_STOPS_DETAIL
import com.curvemaps.app.routing.RideFactors
import com.curvemaps.app.routing.RideStyle
import com.curvemaps.app.routing.RideStyleSearch
import com.curvemaps.app.routing.ThroughRoadSnap
import com.curvemaps.app.routing.buildGhRequest
import com.graphhopper.ResponsePath
import com.graphhopper.util.shapes.GHPoint
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.max

/** Everything that changes a plan except the code and the graph. */
internal data class PlanRequest(
    val from: GHPoint,
    val to: GHPoint,
    val fromName: String,
    val toName: String,
    val factors: RideFactors,
    val blockUnpaved: Boolean,
    val avoidMotorways: Boolean,
) {
    /** Same ride question across code versions: replay matches ratings by this. */
    fun key(regionId: String): String = listOf(
        regionId, "%.5f,%.5f".format(from.lat, from.lon), "%.5f,%.5f".format(to.lat, to.lon),
        "hills=${factors.elevation}", "stops=${factors.stopsAndTowns}", "unpaved=$blockUnpaved", "motorways=$avoidMotorways",
    ).joinToString("|")

    fun toJson(): JSONObject = JSONObject()
        .put("from", JSONObject().put("lat", from.lat).put("lon", from.lon).put("name", fromName))
        .put("to", JSONObject().put("lat", to.lat).put("lon", to.lon).put("name", toName))
        .put("elevation", factors.elevation)
        .put("stopsAndTowns", factors.stopsAndTowns)
        .put("blockUnpaved", blockUnpaved)
        .put("avoidMotorways", avoidMotorways)

    companion object {
        fun fromJson(json: JSONObject): PlanRequest {
            val from = json.getJSONObject("from")
            val to = json.getJSONObject("to")
            return PlanRequest(
                GHPoint(from.getDouble("lat"), from.getDouble("lon")),
                GHPoint(to.getDouble("lat"), to.getDouble("lon")),
                from.optString("name", ""),
                to.optString("name", ""),
                RideFactors(json.optBoolean("elevation"), json.optBoolean("stopsAndTowns")),
                json.optBoolean("blockUnpaved"),
                json.optBoolean("avoidMotorways"),
            )
        }
    }
}

/**
 * Runs the planner's seven-stop resolution ([DistinctRideStyles.resolve]) the
 * way the app does (`RouteScreen` → `GraphHopperRouter.route` →
 * `RideStyleOptions`), recording every search it makes so a rating can say
 * whether a better ride was found and discarded (selection) or never found
 * (generation).
 */
internal class RaterPlanner(private val region: LoadedRegion, private val pool: ExecutorService) {
    private class Attempt(
        val style: RideStyle,
        val stage: String,
        val search: RideStyleSearch,
        val snappedVia: GHPoint?,
        val path: ResponsePath?,
        val error: String?,
    )

    private val attempts = mutableListOf<Attempt>()

    /** One search, as `GraphHopperRouter.route(alternatives = false)` makes it; null when nothing routes. */
    private fun search(request: PlanRequest, style: RideStyle, search: RideStyleSearch, stage: String): ResponsePath? {
        val hopper = region.hopper
        val via = search.via?.let { ThroughRoadSnap.snap(hopper.locationIndex, hopper.encodingManager, it, twoWay = true) }
        if (search.via != null && via == null) {
            record(Attempt(style, stage, search, null, null, "No through road near the via point"))
            return null
        }
        val gh = buildGhRequest(
            request.from, request.to, style, request.blockUnpaved, request.avoidMotorways, 0, search.penalizedEdges,
            viaPoints = listOfNotNull(via), alternatives = false, curveWeight = search.curveWeight,
            siblingEdgePenalty = if (search.penalizedEdges.isNotEmpty()) search.roadPenalty else null,
            factors = request.factors, passThrough = via != null,
        )
        // Path details never change the route; the rater always counts stops.
        if (MOTO_STOPS_DETAIL !in gh.pathDetails) gh.setPathDetails(gh.pathDetails + MOTO_STOPS_DETAIL)
        val response = hopper.route(gh)
        if (response.hasErrors()) {
            record(Attempt(style, stage, search, via, null, response.errors.joinToString("; ") { it.message ?: it.javaClass.simpleName }))
            return null
        }
        return response.best.also { record(Attempt(style, stage, search, via, it, null)) }
    }

    private fun record(attempt: Attempt) = synchronized(attempts) { attempts += attempt }

    fun plan(request: PlanRequest): JSONObject {
        require(abs(request.from.lat - request.to.lat) >= 1e-4 || abs(request.from.lon - request.to.lon) >= 1e-4) {
            "From and To are the same place"
        }
        val started = System.nanoTime()
        val optima: Map<RideStyle, Future<ResponsePath?>> = RideStyle.entries.associateWith { style ->
            pool.submit<ResponsePath?> { search(request, style, RideStyleSearch(), "optimum") }
        }
        var selection: DistinctRideStyles.Selection? = null
        val options = runBlocking {
            DistinctRideStyles.resolve(
                optimum = { style -> optima.getValue(style).get() ?: error("No route was found (${style.label})") },
                reweighted = { style, weight -> search(request, style, RideStyleSearch(curveWeight = weight), "escalate") },
                diversified = { style, s -> search(request, style, s, if (s.via != null) "look-around" else "penalty") },
                rings = DistinctRideStyles.detourRings(request.from, request.to),
                onSelected = { selection = it },
            )
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        return describe(request, options, millis, selection)
    }

    private fun stage(option: DistinctRideStyles.Option): String = when {
        option.repeats != null -> "repeat"
        option.via != null -> "look-around"
        option.roadPenalty > 0.0 -> "penalty"
        option.round > 0 -> "escalate"
        else -> "optimum"
    }

    private fun describe(
        request: PlanRequest,
        options: Map<RideStyle, DistinctRideStyles.Option>,
        millis: Long,
        selection: DistinctRideStyles.Selection?,
    ): JSONObject {
        val fastest = options.getValue(RideStyle.FASTEST).path
        val cache = java.util.IdentityHashMap<ResponsePath, AlternativePolicy.CachedRoute>()
        fun cached(path: ResponsePath) = cache.getOrPut(path) { AlternativePolicy.cachedRoute(path) }
        val routes = JSONObject()
        fun register(path: ResponsePath, spacing: Double): String {
            val route = cached(path)
            val key = routeKey(route)
            if (!routes.has(key)) {
                routes.put(
                    key,
                    JSONObject()
                        .put("features", routeFeatures(path, route, region.edges, fastest))
                        .put("roads", roadSequence(path))
                        .put("geometry", geometry(path, spacing)),
                )
            } else if (spacing == 0.0) {
                // A shown ride keeps its full geometry even if a candidate registered it first.
                routes.getJSONObject(key).put("geometry", geometry(path))
            }
            return key
        }

        val shown = options.mapValues { (_, option) -> cached(option.path) }
        val optimumOf = synchronized(attempts) {
            attempts.filter { it.stage == "optimum" && it.path != null }.associate { it.style to it.path!! }
        }
        // Reviewer context for the shown rides (candidates keep features only).
        val shownPaths = RideStyle.entries.map { options.getValue(it).path }
        val places = placesAlong(shownPaths, region.reverse) { km -> (km / 15).coerceIn(1.0, 8.0) }
        RideStyle.entries.forEachIndexed { i, style ->
            val path = options.getValue(style).path
            val route = routes.getJSONObject(register(path, 0.0))
            if (route.has("segments")) return@forEachIndexed
            val segments = roadSegments(path, cached(path), region.edges)
            route.put("segments", segments).put("oddities", routeOddities(path, segments)).put("places", places[i])
        }
        val longest = shownPaths.maxOf { it.distance }
        val beeline = haversineMeters(request.from.lat, request.from.lon, request.to.lat, request.to.lon)
        val corridor = corridorRoads(
            region.hopper, region.edges, request.from, request.to,
            maxSumMeters = max(longest, 1.5 * beeline + 5_000.0),
            stops = RideStyle.entries.map { shown.getValue(it) },
        )

        val stops = JSONArray()
        RideStyle.entries.forEach { style ->
            val option = options.getValue(style)
            val key = register(option.path, 0.0)
            val optimum = optimumOf[style] ?: option.path
            val overlap = JSONObject()
            RideStyle.entries.filter { it != style }.forEach { other ->
                overlap.put(other.level.toString(), num(AlternativePolicy.sharedRoadFraction(shown.getValue(style).edgeDistances, shown.getValue(other).edgeDistances), 3))
            }
            stops.put(
                JSONObject()
                    .put("level", style.level)
                    .put("style", style.label)
                    .put("routeKey", key)
                    .put("stage", stage(option))
                    .put("round", option.round)
                    .put("curveWeight", num(option.curveWeight, 3))
                    .put("roadPenalty", num(option.roadPenalty, 3))
                    .put("via", option.via?.let { JSONArray().put(num(it.lat, 5)).put(num(it.lon, 5)) } ?: JSONObject.NULL)
                    .put("budget", option.budget.name)
                    .put("repeats", option.repeats?.label ?: JSONObject.NULL)
                    .put("foundBy", option.foundBy?.label ?: JSONObject.NULL)
                    .put("timeVsStyleOptimum", num(option.path.time.toDouble() / optimum.time))
                    .put("distanceVsStyleOptimum", num(option.path.distance / optimum.distance))
                    .put("overlapWith", overlap)
                    // The same stop without any forced road: where a via or penalty moved the ride from.
                    .put("optimumRouteKey", register(optimum, 25.0))
                    .put("viaDetour", option.via?.let { viaDetour(shown.getValue(style), cached(optimum), it, region.edges) } ?: JSONObject.NULL),
            )
        }

        // Every search each stop made, deduplicated per stop, judged the way
        // DistinctRideStyles.classify judged it (against the stops below).
        val candidates = JSONArray()
        val list = synchronized(attempts) { attempts.toList() }
        RideStyle.entries.forEach { style ->
            val best = optimumOf[style]?.let { cached(it) } ?: return@forEach
            val offered = RideStyle.entries.take(style.ordinal).map { shown.getValue(it) }
            // The curvy floor the resolver held this stop to: the last stop below with a ride of its own.
            val curvyFloor = RideStyle.entries.take(style.ordinal).lastOrNull { options.getValue(it).repeats == null }
                ?.let { DistinctRideStyles.curvinessFloor(options.getValue(it).path) } ?: Double.NEGATIVE_INFINITY
            val chosenKey = routeKey(shown.getValue(style))
            val seen = HashMap<String, JSONObject>()
            list.filter { it.style == style }.forEachIndexed { index, attempt ->
                val meta = JSONObject()
                    .put("level", style.level)
                    .put("stage", attempt.stage)
                    .put("order", index)
                    .put("curveWeight", num(attempt.search.curveWeight ?: style.curveWeight, 3))
                    .put("roadPenalty", num(attempt.search.roadPenalty, 3))
                    .put("via", attempt.snappedVia?.let { JSONArray().put(num(it.lat, 5)).put(num(it.lon, 5)) } ?: JSONObject.NULL)
                if (attempt.path == null) {
                    candidates.put(meta.put("routeKey", JSONObject.NULL).put("verdict", "no route").put("error", attempt.error))
                    return@forEachIndexed
                }
                val route = cached(attempt.path)
                val key = register(attempt.path, 25.0)
                seen[key]?.let { it.put("foundTimes", it.getInt("foundTimes") + 1); return@forEachIndexed }
                val verdict = when {
                    DistinctRideStyles.drivesARoadTwice(route) -> "drives a road twice"
                    DistinctRideStyles.turnsAround(attempt.path, excusedBy = best.path) -> "turns around"
                    offered.any { DistinctRideStyles.sameRide(route, it) } -> "same ride as a lower stop"
                    AlternativePolicy.hasExcessiveLocalDetour(route.edgeSections, best.edgeSections) -> "NONE (local detour bubble)"
                    AlternativePolicy.withinGlobalDetourBudget(attempt.path, best.path, style) -> when {
                        // Within budget, but it only stands in while the resolver looks for a better ride.
                        curvyDistanceFraction(attempt.path.points) < curvyFloor -> "STYLE (straighter than the stop below)"
                        attempt.stage == "look-around" && DistinctRideStyles.curveGainPerMinute(attempt.path, best.path) <= 0.0 -> "STYLE (adds no curves)"
                        else -> "STYLE"
                    }
                    AlternativePolicy.withinGlobalDetourBudget(attempt.path, best.path, style, DistinctRideStyles.RELAXED_BUDGET_FACTOR) -> "RELAXED"
                    else -> "NONE (beyond budget)"
                }
                meta.put("routeKey", key)
                    .put("verdict", verdict)
                    .put("chosen", key == chosenKey)
                    .put("foundTimes", 1)
                    .put("routeWeight", num(attempt.path.routeWeight, 1))
                    .put("timeVsStyleOptimum", num(attempt.path.time.toDouble() / best.path.time))
                    .put("distanceVsStyleOptimum", num(attempt.path.distance / best.path.distance))
                seen[key] = meta
                candidates.put(meta)
            }
        }

        // The ladder settled stop by stop, which the joint choice started from.
        val selectionJson = selection?.let { chosen ->
            val settledRides = RideStyle.entries.map { cached(chosen.settled.getValue(it).path) }
            JSONObject()
                .put("settledCost", num(chosen.settledCost, 4))
                .put("chosenCost", num(chosen.chosenCost, 4))
                .put("poolSize", chosen.poolSize)
                .put("changed", RideStyle.entries.count { chosen.settled.getValue(it).path !== options.getValue(it).path })
                .put("problem", chosen.problem?.let { problem ->
                    // The joint choice's input, for re-scoring the ladder offline: candidates in order (route keys,
                    // the stop whose search found each), per-stop budgets and time over the stop's optimum, and pairs.
                    val n = problem.candidates.size
                    JSONObject()
                        .put("keys", JSONArray(chosen.paths.map { register(it, 25.0) }))
                        .put("foundBy", JSONArray(chosen.foundBy.map { it.ordinal }))
                        .put("curviness", JSONArray(problem.candidates.map { num(it.curviness, 4) }))
                        .put("gravel", JSONArray(problem.candidates.map { num(it.gravelShare, 4) }))
                        .put("lookAround", JSONArray(problem.candidates.map { it.lookAround }))
                        .put("lasso", JSONArray(problem.candidates.map { it.lasso }))
                        .put("ownOptimum", JSONArray(problem.ownOptimum.toList()))
                        .put("budgets", JSONArray(problem.budgets.map { row -> JSONArray(row.map { it?.name ?: JSONObject.NULL }) }))
                        .put("timeOverOptimum", JSONArray(problem.timeOverOptimum.map { row -> JSONArray(row.map { num(it, 4) }) }))
                        .put("same", JSONArray((0 until n).flatMap { a -> (a + 1 until n).filter { problem.sameRide[a][it] }.map { b -> JSONArray().put(a).put(b) } }))
                        .put("overlap", JSONArray((0 until n).map { a -> JSONArray((0 until n).map { b -> num(problem.overlap[a][b], 3) }) }))
                } ?: JSONObject.NULL)
                .put("settled", JSONArray().apply {
                    RideStyle.entries.forEachIndexed { i, style ->
                        val option = chosen.settled.getValue(style)
                        val optimum = optimumOf[style] ?: option.path
                        val overlap = JSONObject()
                        RideStyle.entries.indices.filter { it != i }.forEach { j ->
                            overlap.put(j.toString(), num(AlternativePolicy.sharedRoadFraction(settledRides[i].edgeDistances, settledRides[j].edgeDistances), 3))
                        }
                        put(JSONObject()
                            .put("routeKey", register(option.path, 25.0))
                            .put("stage", stage(option))
                            .put("budget", option.budget.name)
                            .put("repeats", option.repeats?.label ?: JSONObject.NULL)
                            .put("timeVsStyleOptimum", num(option.path.time.toDouble() / optimum.time))
                            .put("overlapWith", overlap))
                    }
                })
        }

        return JSONObject()
            .put("createdAt", Instant.now().toString())
            .put("selection", selectionJson ?: JSONObject.NULL)
            .put("request", request.toJson())
            .put("requestKey", request.key(region.id))
            .put("region", region.summary())
            .put("searches", list.size)
            .put("millis", millis)
            .put("stops", stops)
            .put("candidates", candidates)
            .put("routes", routes)
            .put("corridorRoads", corridor)
    }
}
