package com.curvemaps.app.rater

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Local route-rating tool: the planner's seven ride styles on the real graph,
 * served to `tools/rater/index.html`, with good/bad ratings captured together
 * with each route's features. Opt-in (RATER=1) and blocks until stopped; see
 * `tools/rater/README.md`:
 *
 * ```
 * tools/rater/run.sh        # http://127.0.0.1:8765
 * ```
 */
class RouteRaterServer {
    @Test
    fun serve() {
        assumeTrue(System.getenv("RATER") != null)
        val root = File("..").canonicalFile
        check(File(root, "tools/rater/index.html").isFile) { "Run from the app module (tools/rater/run.sh)" }
        val port = System.getenv("RATER_PORT")?.toIntOrNull() ?: 8765
        val dataDir = System.getenv("RATER_DATA")?.let { File(it) } ?: File(root, "data/rater")
        RaterServer(root, dataDir.apply { mkdirs() }, port).start()
        println("RATER listening on http://127.0.0.1:$port (data in $dataDir)")
        CountDownLatch(1).await()
    }
}

private class HttpError(val status: Int, message: String) : Exception(message)

internal class RaterServer(private val root: File, private val dataDir: File, private val port: Int) {
    private val store = RaterStore(dataDir)
    private val regions = RaterRegions(root, dataDir, System.getenv("RATER_MAP_SERVER") ?: "https://maps.curvemaps.app")
    private val lock = ReentrantReadWriteLock()
    private var active: LoadedRegion? = null
    private val searchPool = Executors.newFixedThreadPool(4)

    /**
     * At most two plans load or compute at once. Each holds several copies of a ~1 MB plan while it is served, and
     * parallel agent reviews asking for theirs together ran a New South Wales rater out of heap; the rest wait.
     */
    private val planSlots = Semaphore(2)
    private val algo = algoInfo()
    private val activeFile = File(dataDir, "active.txt")
    private val planCache = object : LinkedHashMap<String, JSONObject>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?) = size > 8
    }

    fun start() {
        val wanted = activeFile.takeIf { it.isFile }?.readText()?.trim()
        // The last map used; else an installed .motomap (what riders route on); else the bundled graph.
        val entry = regions.installed().let { list ->
            list.firstOrNull { it.id == wanted } ?: list.lastOrNull { it.source == "package" } ?: list.firstOrNull()
        }
        if (entry != null) runCatching { activate(entry.id) }.onFailure { println("RATER could not load ${entry.id}: ${it.message}") }
        MiniHttpServer(port, ::handle).start()
    }

    private fun handle(request: MiniRequest): MiniResponse {
        return try {
            val path = request.path
            val query = parseQuery(request.rawQuery)
            val post = request.method == "POST"
            val body = if (post) JSONObject(request.body.decodeToString().ifBlank { "{}" }) else JSONObject()
            when {
                path == "/" || path == "/index.html" ->
                    MiniResponse(200, File(root, "tools/rater/index.html").readBytes(), "text/html; charset=utf-8")
                path == "/api/info" -> json(info())
                path == "/api/catalog" -> json(JSONObject().put("regions", regions.catalog()).put("downloads", downloads()))
                path == "/api/downloads" -> json(JSONObject().put("downloads", downloads()).put("installed", installedJson()))
                path == "/api/download" && post -> {
                    val state = regions.startDownload(body.getString("regionId")) { }
                    json(state.toJson())
                }
                path == "/api/load-file" && post -> {
                    val file = File(body.getString("path")).let { if (it.isAbsolute) it else File(root, it.path) }
                    if (!file.isFile) throw HttpError(400, "No such file: $file")
                    json(regions.install(file).toJson())
                }
                path == "/api/activate" && post -> {
                    activate(body.getString("id"))
                    json(info())
                }
                path == "/api/geocode" -> json(geocode(query))
                path == "/api/reverse" -> json(reverse(query))
                path == "/api/places" -> json(places(query))
                path == "/api/plan" && post -> json(plan(body))
                path == "/api/plan" -> json(savedPlan(query["id"] ?: throw HttpError(400, "id required")))
                path == "/api/plans" -> json(
                    JSONObject().put("plans", if (query["all"] == "1") store.listPlans(limit = 1000, includeTagged = true) else store.listPlans()),
                )
                path == "/api/rate" && post -> json(rate(body))
                path == "/api/replay" -> json(replay())
                path == "/api/export.json" -> {
                    val out = JSONObject().put("exportedAt", Instant.now().toString()).put("algoNow", algo)
                        .put("ratings", JSONArray(store.latestRatings()))
                    MiniResponse(200, out.toString(1).toByteArray(), "application/json", mapOf("Content-Disposition" to "attachment; filename=route-ratings.json"))
                }
                path == "/api/export.csv" -> {
                    MiniResponse(200, exportCsv().toByteArray(), "text/csv; charset=utf-8", mapOf("Content-Disposition" to "attachment; filename=route-ratings.csv"))
                }
                else -> throw HttpError(404, "Not found: $path")
            }
        } catch (e: HttpError) {
            json(JSONObject().put("error", e.message), e.status)
        } catch (e: Exception) {
            e.printStackTrace()
            json(JSONObject().put("error", e.message ?: e.javaClass.simpleName), 500)
        }
    }

    private fun region(): LoadedRegion = active ?: throw HttpError(409, "No map loaded: download or load one first")

    private fun activate(id: String) = lock.write {
        val entry = regions.installed().firstOrNull { it.id == id } ?: throw HttpError(404, "No installed map $id")
        if (active?.id == id) return@write
        active?.close()
        active = null
        active = regions.load(entry)
        activeFile.writeText(id)
        println("RATER routing on ${entry.name}")
    }

    private fun installedJson() = JSONArray(regions.installed().map { it.toJson() })

    private fun downloads() = JSONArray(regions.downloads.values.map { it.toJson() })

    private fun info(): JSONObject = lock.read {
        JSONObject()
            .put("region", active?.info() ?: JSONObject.NULL)
            .put("installed", installedJson())
            .put("algo", algo)
            // The server runs the code it was started with; true once a source it digests has changed on disk.
            .put("codeChanged", sourceDigests() != (algo.getString("hash") to algo.getString("context")))
            .put("dataDir", dataDir.absolutePath)
            .put("mapServer", regions.mapServer)
    }

    private fun geocode(query: Map<String, String>): JSONObject = lock.read {
        val engine = region().search ?: throw HttpError(409, "This map has no geocoder; type lat,lon or drop pins")
        val results = runBlocking {
            engine.search(
                query["q"].orEmpty(),
                query["lat"]?.toDoubleOrNull() ?: -27.47,
                query["lon"]?.toDoubleOrNull() ?: 153.02,
                limit = 8,
            )
        }
        JSONObject().put(
            "results",
            JSONArray(results.map {
                JSONObject().put("name", it.name).put("subtitle", it.subtitle).put("type", it.type.name).put("lat", it.lat).put("lon", it.lon)
            }),
        )
    }

    private fun reverse(query: Map<String, String>): JSONObject = lock.read {
        val lat = query["lat"]?.toDoubleOrNull() ?: throw HttpError(400, "lat required")
        val lon = query["lon"]?.toDoubleOrNull() ?: throw HttpError(400, "lon required")
        val hit = region().reverse?.reverseGeocode(listOf(lat to lon))?.firstOrNull()
        JSONObject().put("name", hit?.let { listOf(it.name, it.detail).filter(String::isNotBlank).joinToString(", ") } ?: "%.5f, %.5f".format(lat, lon))
    }

    /** Places a trip could start or end at inside south/west/north/east (agent sweeps pick their endpoints here). */
    private fun places(query: Map<String, String>): JSONObject = lock.read {
        val geocoder = region().reverse ?: throw HttpError(409, "This map has no geocoder")
        fun edge(name: String) = query[name]?.toDoubleOrNull() ?: throw HttpError(400, "$name required")
        JSONObject().put("places", placesIn(geocoder, edge("south"), edge("west"), edge("north"), edge("east")))
    }

    private fun planId(region: LoadedRegion, request: PlanRequest) =
        sha256("${request.key(region.id)}|${algo.getString("hash")}|${region.graphId}").take(12)

    /**
     * A saved plan for the same inputs, code and graph unless [fresh]; [JSONObject] "cached" says which. The plan id
     * covers the routing code only, so a saved plan built by other rater code (`algo.context`) is planned again.
     * A [tag] (agent sweeps) keeps a new plan out of History; an untagged request lists it there.
     */
    private fun computePlan(region: LoadedRegion, request: PlanRequest, fresh: Boolean, tag: String? = null): JSONObject {
        val id = planId(region, request)
        if (!fresh) {
            val saved = synchronized(planCache) { planCache[id] } ?: store.loadPlan(id)
            saved?.takeIf { it.getJSONObject("algo").optString("context") == algo.getString("context") }?.let {
                synchronized(planCache) { planCache[id] = it }
                store.indexPlan(id, it, tag)
                return JSONObject(it.toString()).put("cached", true)
            }
        }
        val plan = RaterPlanner(region, searchPool).plan(request).put("planId", id).put("algo", algo)
        store.savePlan(id, plan, tag)
        synchronized(planCache) { planCache[id] = plan }
        println("RATER plan $id ${request.fromName} → ${request.toName}: ${plan.getInt("searches")} searches in ${plan.getLong("millis")} ms")
        return plan
    }

    private fun withRatings(plan: JSONObject): JSONObject {
        val copy = JSONObject(plan.toString())
        val id = plan.getString("planId")
        return copy.put("ratings", store.ratingsFor(id)).put("prior", store.priorFor(plan.getString("requestKey"), id))
            .put("stale", plan.getJSONObject("algo").getString("hash") != algo.getString("hash"))
    }

    private fun plan(body: JSONObject): JSONObject {
        planSlots.acquire()
        try {
            return lock.read {
                val region = region()
                withRatings(computePlan(region, PlanRequest.fromJson(body), body.optBoolean("fresh"), body.optString("tag").ifBlank { null }))
            }
        } finally {
            planSlots.release()
        }
    }

    private fun savedPlan(id: String): JSONObject {
        val plan = synchronized(planCache) { planCache[id] } ?: store.loadPlan(id) ?: throw HttpError(404, "No plan $id")
        return withRatings(plan)
    }

    private fun rate(body: JSONObject): JSONObject {
        val planId = body.getString("planId")
        val level = body.getInt("level")
        val routeKey = body.getString("routeKey")
        val plan = synchronized(planCache) { planCache[planId] } ?: store.loadPlan(planId) ?: throw HttpError(404, "No plan $planId")
        val stop = plan.getJSONArray("stops").getJSONObject(level)
        val routes = plan.getJSONObject("routes")
        val route = routes.optJSONObject(routeKey) ?: throw HttpError(400, "Route $routeKey is not in plan $planId")
        val candidates = plan.getJSONArray("candidates")
        val candidate = (0 until candidates.length()).map { candidates.getJSONObject(it) }
            .firstOrNull { it.getInt("level") == level && it.optString("routeKey") == routeKey }
        val rating = body.opt("rating").takeIf { it == "good" || it == "bad" } ?: JSONObject.NULL
        val record = JSONObject()
            .put("ts", Instant.now().toString())
            .put("planId", planId)
            .put("requestKey", plan.getString("requestKey"))
            .put("level", level)
            .put("style", stop.getString("style"))
            .put("routeKey", routeKey)
            .put("role", if (stop.getString("routeKey") == routeKey) "chosen" else "candidate")
            .put("rating", rating)
            .put("reasons", body.optJSONArray("reasons") ?: JSONArray())
            .put("note", body.optString("note"))
            .put("request", plan.getJSONObject("request"))
            .put("region", plan.getJSONObject("region"))
            .put("algo", plan.getJSONObject("algo"))
            .put("stop", JSONObject(stop.toString()).apply { remove("overlapWith") })
            .put("candidate", candidate ?: JSONObject.NULL)
            .put("features", route.getJSONObject("features"))
            .put("chosenFeatures", routes.getJSONObject(stop.getString("routeKey")).getJSONObject("features"))
        store.rate(record)
        return JSONObject().put("ok", true).put("ratings", store.ratingsFor(planId))
    }

    /**
     * Re-plans every rated request on the loaded map with the current code and
     * reports, per stop, what the stop now shows: a ride rated good or bad
     * before (any code version), or one nobody has rated.
     */
    private fun replay(): JSONObject = lock.read {
        val region = region()
        val byRequest = store.latestRatings().filter { it.getString("requestKey").startsWith("${region.id}|") }
            .groupBy { it.getString("requestKey") }
        val totals = mutableMapOf("chosenGood" to 0, "chosenBad" to 0, "chosenUnrated" to 0, "goodNoLongerChosen" to 0, "badNoLongerChosen" to 0)
        val requests = JSONArray()
        byRequest.forEach { (requestKey, ratings) ->
            val request = PlanRequest.fromJson(ratings.last().getJSONObject("request"))
            val plan = try {
                computePlan(region, request, false)
            } catch (e: Exception) {
                requests.put(JSONObject().put("requestKey", requestKey).put("error", e.message))
                return@forEach
            }
            val prior = store.priorFor(requestKey, null)
            val stops = JSONArray()
            val chosen = HashSet<String>()
            val planStops = plan.getJSONArray("stops")
            for (i in 0 until planStops.length()) {
                val stop = planStops.getJSONObject(i)
                val key = "${stop.getInt("level")}#${stop.getString("routeKey")}"
                chosen += key
                val rated = prior.optJSONObject(key)?.getString("rating")
                totals.merge(when (rated) { "good" -> "chosenGood"; "bad" -> "chosenBad"; else -> "chosenUnrated" }, 1, Int::plus)
                stops.put(JSONObject().put("level", stop.getInt("level")).put("style", stop.getString("style")).put("routeKey", stop.getString("routeKey")).put("rated", rated ?: JSONObject.NULL))
            }
            ratings.filter { "${it.getInt("level")}#${it.getString("routeKey")}" !in chosen }.forEach {
                totals.merge(if (it.getString("rating") == "good") "goodNoLongerChosen" else "badNoLongerChosen", 1, Int::plus)
            }
            requests.put(
                JSONObject().put("requestKey", requestKey).put("planId", plan.getString("planId"))
                    .put("from", request.fromName).put("to", request.toName).put("stops", stops),
            )
        }
        JSONObject().put("algo", algo).put("region", region.id).put("totals", JSONObject(totals as Map<*, *>)).put("requests", requests)
    }

    private fun exportCsv(): String {
        val rows = store.latestRatings().map { r ->
            val row = linkedMapOf<String, Any?>(
                "ts" to r.getString("ts"), "planId" to r.getString("planId"), "region" to r.getJSONObject("region").getString("id"),
                "algo" to r.getJSONObject("algo").getString("hash"),
                "from" to r.getJSONObject("request").getJSONObject("from").optString("name"),
                "to" to r.getJSONObject("request").getJSONObject("to").optString("name"),
                "level" to r.getInt("level"), "style" to r.getString("style"), "role" to r.getString("role"),
                "stage" to (r.optJSONObject("candidate")?.optString("stage") ?: r.getJSONObject("stop").optString("stage")),
                "verdict" to r.optJSONObject("candidate")?.optString("verdict"),
                "budget" to r.getJSONObject("stop").optString("budget"),
                "rating" to r.getString("rating"),
                "reasons" to (r.optJSONArray("reasons")?.join(";")?.replace("\"", "") ?: ""),
                "note" to r.optString("note"),
            )
            flatten("", r.getJSONObject("features"), row)
            row
        }
        val columns = rows.flatMap { it.keys }.distinct()
        fun cell(value: Any?): String {
            val text = when (value) { null, JSONObject.NULL -> ""; else -> value.toString() }
            return if (text.any { it == ',' || it == '"' || it == '\n' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
        }
        return (listOf(columns.joinToString(",")) + rows.map { row -> columns.joinToString(",") { cell(row[it]) } }).joinToString("\n") + "\n"
    }

    private fun flatten(prefix: String, json: JSONObject, out: MutableMap<String, Any?>) {
        json.keys().asSequence().sorted().forEach { key ->
            val value = json.get(key)
            if (value is JSONObject) flatten("$prefix$key.", value, out) else out["$prefix$key"] = value
        }
    }

    /**
     * The routing code a plan came from: git HEAD plus a digest of the routing sources (the tree is often dirty).
     * `context` digests the rater code that shapes a plan's features and reviewer context; it is not part of the
     * plan id (ratings survive it), but the agent review refetches cached plans when it changes.
     */
    private fun algoInfo(): JSONObject {
        val (digest, context) = sourceDigests()
        fun git(vararg args: String) = runCatching {
            ProcessBuilder("git", *args).directory(root).redirectErrorStream(true).start().inputStream.readBytes().decodeToString().trim()
        }.getOrDefault("")
        return JSONObject().put("hash", digest).put("context", context).put("git", git("rev-parse", "--short", "HEAD"))
            .put("dirty", git("status", "--porcelain", "--", "app/src/main/java/com/curvemaps/app/routing").isNotBlank())
    }

    /** Digests of the routing sources and of the rater's plan-shaping sources, as they are on disk now. */
    private fun sourceDigests(): Pair<String, String> {
        val routing = listOf(
            "DistinctRideStyles.kt", "RideLadder.kt", "ComplexityWeighting.kt", "AlternativePolicy.kt", "RideStyle.kt", "RouteRequests.kt",
            "ThroughRoadSnap.kt", "EdgeRideFeatures.kt", "RouteCurviness.kt", "MotorcycleWeightingFactory.kt",
        ).map { File(root, "app/src/main/java/com/curvemaps/app/routing/$it") }
        val rater = listOf("RaterPlanner.kt", "RaterContext.kt", "RaterFeatures.kt")
            .map { File(root, "app/src/test/java/com/curvemaps/app/rater/$it") }
        fun digest(files: List<File>) = sha256(files.joinToString("\n") { if (it.isFile) it.readText() else "" }).take(10)
        return digest(routing) to digest(rater)
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { it.isNotBlank() }.associate {
            val (k, v) = (it.split('=', limit = 2) + "").take(2)
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        }

    private fun json(body: JSONObject, status: Int = 200) =
        MiniResponse(status, body.toString().toByteArray(), "application/json")
}
