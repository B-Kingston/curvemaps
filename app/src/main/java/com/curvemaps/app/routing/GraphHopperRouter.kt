package com.curvemaps.app.routing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.util.PMap
import com.graphhopper.util.shapes.GHPoint
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlin.math.abs

private const val TAG = "CurveMaps.Router"


/** Serializes cache swaps with the destructive graph-copy instrumentation cases. */
internal val GRAPH_CACHE_COPY_LOCK = Any()
internal const val MAX_DISPLAYED_ROUTES = 3

/**
 * The primary route is first; later entries are accepted alternatives, which
 * only a request with `alternatives = true` collects (the planner shows one
 * route per slider stop).
 */
class RouteResult(routes: List<ResponsePath>) {
    val routes: List<ResponsePath> = Collections.unmodifiableList(routes.toList())

    init {
        require(this.routes.isNotEmpty() && this.routes.size <= MAX_DISPLAYED_ROUTES) {
            "A route result must contain between one and $MAX_DISPLAYED_ROUTES routes"
        }
    }
}

/**
 * GraphHopper-backed router.
 *
 * [graphDir] is the dataset's graph directory: the bundled fallback still
 * copies from APK assets on first use, while an installed regional package
 * reads its immutable directory directly. [close] waits for in-flight routing
 * to finish and then releases the graph's MMAP handles, which is required
 * before switching to another region's dataset.
 */
class GraphHopperRouter(
    context: Context,
    private val graphDir: File = File(context.applicationContext.filesDir, "gh-cache"),
    private val copyGraphFromAssets: Boolean = true,
) {

    private val appContext = context.applicationContext
    private val lifecycleLock = ReentrantReadWriteLock()
    @Volatile
    private var closed = false

    // One result per (points, style, road toggles, ride factors,
    // alternatives). The key holds every input that changes the result, so a
    // style's route never depends on which other styles were visited first.
    private data class RouteKey(
        val points: List<Pair<Double, Double>>,
        val style: RideStyle,
        val blockUnpaved: Boolean,
        val avoidMotorways: Boolean,
        val alternatives: Boolean,
        val curveWeight: Double?,
        val penalizedEdges: Set<Int>,
        val roadPenalty: Double,
        val factors: RideFactors,
        val passThrough: Boolean,
        val viaHeadings: List<Double>,
        val startHeading: Double,
    )

    // Bounded LRU: every entry holds up to three full geometries, so an
    // unbounded map grew heap on long exploratory sessions.
    private val routeCache =
        object : LinkedHashMap<RouteKey, List<ResponsePath>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<RouteKey, List<ResponsePath>>,
            ): Boolean = size > MAX_CACHED_ROUTE_SETS
        }

    private val hopperDelegate = lazy {
        synchronized(GRAPH_CACHE_COPY_LOCK) {
            withGraphCacheFileLock {
                Log.i(TAG, "Initializing GraphHopper (graph dir: ${graphDir.absolutePath})")
                val started = SystemClock.elapsedRealtime()
                if (copyGraphFromAssets) {
                    copyGraphFromAssetsIfNeededUnlocked()
                }
                if (!graphDir.isDirectory || graphDir.listFiles().isNullOrEmpty()) {
                    throw IllegalStateException(
                        "Graph data not found in ${graphDir.absolutePath}. " +
                            "Install a region package or build the bundled graph " +
                            "with tools/gh/config.yml into app/src/main/assets/graph-cache"
                    )
                }
                val loaded = loadMotoGraphHopper(graphDir)
                loadedFeatures = loaded.graphFeatures(graphDir)
                Log.i(
                    TAG,
                    "GraphHopper ready in ${SystemClock.elapsedRealtime() - started} ms ($loadedFeatures)",
                )
                loaded
            }
        }
    }

    private val hopper: GraphHopper by hopperDelegate

    @Volatile
    private var loadedFeatures: GraphFeatures? = null

    /**
     * The ride-factor data of the loaded graph, or null before the first
     * route has loaded it (loading is lazy and never happens on the UI
     * thread). Route settings use it to say when a map lacks a factor's data.
     */
    val features: GraphFeatures?
        get() = loadedFeatures

    /**
     * Releases the graph. Waits for any in-flight [route] call, so the MMAP
     * files are never unmapped underneath a routing thread. Safe to call twice.
     */
    fun close() {
        lifecycleLock.writeLock().withLock { doClose() }
    }

    /**
     * Releases the graph without ever blocking the caller: when no route is in
     * flight the work happens inline, otherwise a short-lived daemon thread
     * waits for the route and then closes. Used by screen disposal.
     *
     * Returns the daemon thread that is draining an in-flight route, or null
     * when the close completed inline. Callers that must not delete the graph
     * directory under a live reader join the returned thread off the UI thread
     * before doing so.
     */
    fun closeAsync(): Thread? {
        if (lifecycleLock.writeLock().tryLock()) {
            try {
                doClose()
            } finally {
                lifecycleLock.writeLock().unlock()
            }
            return null
        }
        return Thread({ close() }, "gh-close").apply { isDaemon = true; start() }
    }

    private fun doClose() {
        if (closed) return
        closed = true
        if (hopperDelegate.isInitialized()) {
            runCatching { hopper.close() }
                .onFailure {
                    // ART has no sun.misc.Unsafe.invokeCleaner, so GraphHopper's
                    // explicit MMAP cleanup cannot run; the mapping is reclaimed
                    // by the garbage collector instead.
                    Log.i(TAG, "GraphHopper close deferred to the GC: ${it.message}")
                }
        }
        Log.i(TAG, "GraphHopper released (${graphDir.name})")
    }

    private inline fun <T> withGraphCacheFileLock(block: () -> T): T {
        val lockFile = File(appContext.filesDir, "gh-cache.lock")
        return FileChannel.open(
            lockFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use { block() }
        }
    }

    internal fun copyGraphFromAssetsIfNeeded() {
        synchronized(GRAPH_CACHE_COPY_LOCK) {
            withGraphCacheFileLock { copyGraphFromAssetsIfNeededUnlocked() }
        }
    }

    private fun copyGraphFromAssetsIfNeededUnlocked() {
        if (File(graphDir, COPY_COMPLETE_MARKER).isFile) {
            File(appContext.filesDir, "gh-cache.tmp").deleteRecursively()
            return
        }
        Log.i(TAG, "graph-cache missing or incomplete — copying from APK assets")
        val assets = appContext.assets
        val assetRoot = "graph-cache"
        val entries = assets.list(assetRoot)
            ?: throw IllegalStateException("No assets/$assetRoot found in the APK")
        // Copy into a sibling directory and swap only once complete: a process
        // death mid-copy then leaves an ignorable .tmp directory instead of a
        // half-written graph the loader would treat as complete forever.
        val stagingDir = File(appContext.filesDir, "gh-cache.tmp")
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        entries.forEach { copyAssetRecursive("$assetRoot/$it", stagingDir) }
        File(stagingDir, COPY_COMPLETE_MARKER).writeText("ok")
        graphDir.deleteRecursively()
        if (!stagingDir.renameTo(graphDir)) {
            stagingDir.deleteRecursively()
            throw IllegalStateException("Could not move the copied graph into place")
        }
        Log.i(TAG, "graph-cache copy complete")
    }

    private fun copyAssetRecursive(assetPath: String, targetDir: File) {
        val name = assetPath.substringAfterLast('/')
        val target = File(targetDir, name)
        val children = assetsOf(assetPath)
        if (children != null) {
            target.mkdirs()
            children.forEach { copyAssetRecursive("$assetPath/$it", target) }
        } else {
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun assetsOf(assetPath: String): Array<String>? = runCatching {
        appContext.assets.list(assetPath)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Routes [from] to [to] with the motorcycle profile.
     *
     * [complexity] is the ride-style dial level ([RideStyle.fromLevel]: 0 is
     * Fastest, higher stops prefer curvier roads; out-of-range values clamp).
     * The first returned route is the style's own optimum. Only with
     * [alternatives] (ride suggestions, saved-ride shape matching) up to two
     * more follow: alternatives that share at most
     * [AlternativePolicy.SIBLING_MAX_SHARE] of their roads with every other
     * route and stay within the detour budget of the primary. Fewer are
     * returned when the network has no sensible distinct alternative; routing
     * never fails for lack of one. Without [alternatives] the search runs the
     * plain algorithm and returns exactly one route.
     *
     * [blockUnpaved] hard-blocks known unpaved surfaces; [avoidMotorways]
     * strongly discourages (but never forbids) motorway and trunk roads.
     * [curveWeight] replaces the style's curve preference, so a stop that
     * repeats a lower stop's ride can search again a little curvier
     * ([DistinctRideStyles]); single-route requests only.
     * [penalizedEdges] with [roadPenalty] runs one plain request that
     * soft-penalises those roads by that fastest-time multiple instead: the
     * second way a repeated stop leaves a ride it must not repeat
     * ([RideStyleSearch]). Also single-route only; it may combine with
     * [curveWeight].
     * [factors] are the Route settings ride factors ([RideFactors]); a graph
     * without a factor's data routes as if it were off.
     * [passThrough] forbids turning around at [viaPoints] and moves each onto
     * a through road first ([ThroughRoadSnap.snap]; with [twoWayVias], a
     * look-around via, a two-way non-motorway one); with [viaSearchMetres]
     * (a round-trip corner, [RoundTrips]) it moves each onto the nearest road
     * a ride can pass straight through within that distance instead
     * ([ThroughRoadSnap.snapPassable]).
     * [startHeading] (degrees from north, NaN for none) makes the route leave
     * [from] in that direction: an off-route rebuild starts the way the
     * rider is going.
     * The result is deterministic for the same inputs.
     */
    fun route(
        from: GHPoint,
        to: GHPoint,
        complexity: Double = 0.0,
        blockUnpaved: Boolean = false,
        avoidMotorways: Boolean = false,
        viaPoints: List<GHPoint> = emptyList(),
        alternatives: Boolean = false,
        curveWeight: Double? = null,
        penalizedEdges: Set<Int> = emptySet(),
        roadPenalty: Double = 0.0,
        factors: RideFactors = RideFactors.NONE,
        passThrough: Boolean = false,
        viaSearchMetres: Double? = null,
        twoWayVias: Boolean = false,
        viaHeadings: List<Double> = emptyList(),
        startHeading: Double = Double.NaN,
    ): RouteResult {
        require(!alternatives || (curveWeight == null && penalizedEdges.isEmpty())) {
            "A curve weight or road penalty override applies to single-route requests"
        }
        lifecycleLock.readLock().withLock {
            check(!closed) { "The routing engine is no longer available" }
            return routeLocked(
                from, to, RideStyle.fromLevel(complexity), blockUnpaved, avoidMotorways, viaPoints, alternatives,
                curveWeight, penalizedEdges, roadPenalty, factors, passThrough, viaSearchMetres, twoWayVias, viaHeadings,
                startHeading,
            )
        }
    }

    /**
     * Diagnostics for the device corpus: the [JunctionJog]s along [path] on
     * this router's graph, judged by Fastest travel time.
     */
    internal fun junctionJogs(path: ResponsePath): List<JunctionJog> {
        lifecycleLock.readLock().withLock {
            check(!closed) { "The routing engine is no longer available" }
            val fastest = hopper.createWeighting(
                hopper.getProfile(MOTORCYCLE_PROFILE),
                PMap().putObject(MOTO_RIDE_STYLE, RideStyle.FASTEST.level),
            )
            return junctionJogs(hopper.baseGraph, fastest, interiorEdgeIds(path))
        }
    }

    private fun routeLocked(
        from: GHPoint,
        to: GHPoint,
        style: RideStyle,
        blockUnpaved: Boolean,
        avoidMotorways: Boolean,
        viaPoints: List<GHPoint>,
        alternatives: Boolean,
        curveWeight: Double?,
        penalizedEdges: Set<Int>,
        roadPenalty: Double,
        factors: RideFactors,
        passThrough: Boolean,
        viaSearchMetres: Double?,
        twoWayVias: Boolean,
        viaHeadings: List<Double>,
        startHeading: Double,
    ): RouteResult {
        if (viaPoints.isEmpty() && abs(from.lat - to.lat) < 1e-4 && abs(from.lon - to.lon) < 1e-4) {
            throw IllegalStateException("No route was found")
        }
        // A look-around via (pass-through) must sit on a through road, or the
        // ride goes into a cul-de-sac and straight back out. A round-trip
        // corner must also not lead into a dead end it could never leave.
        val legPoints = if (passThrough) {
            viaPoints.map { via ->
                val snapped = if (viaSearchMetres != null) {
                    ThroughRoadSnap.snapPassable(
                        hopper.baseGraph, hopper.locationIndex, hopper.encodingManager, via, viaSearchMetres,
                    )
                } else {
                    ThroughRoadSnap.snap(hopper.locationIndex, hopper.encodingManager, via, twoWay = twoWayVias)
                }
                snapped ?: throw IllegalStateException("No through road near the via point")
            }
        } else {
            viaPoints
        }
        // Intentionally no coordinates in the log: from/to are user-supplied.
        val requestPoints = listOf(from) + legPoints + to
        val key = RouteKey(
            requestPoints.map { it.lat to it.lon }, style, blockUnpaved, avoidMotorways, alternatives,
            curveWeight, penalizedEdges, roadPenalty, factors, passThrough, viaHeadings, startHeading,
        )
        synchronized(routeCache) {
            routeCache[key]?.let { cached ->
                Log.i(TAG, "Using cached routes for ${style.label}")
                return RouteResult(cached)
            }
        }

        val started = SystemClock.elapsedRealtime()
        // Multi-leg GPX requests use the standard algorithm, which returns
        // one path, so they never retry; neither does a single-route request.
        val searchAlternatives = alternatives && viaPoints.isEmpty()
        fun search(attempt: Int, siblingEdgeIds: Set<Int>, siblingEdgePenalty: Double?): List<ResponsePath> {
            val request = buildGhRequest(
                from = from,
                to = to,
                style = style,
                blockUnpaved = blockUnpaved,
                avoidMotorways = avoidMotorways,
                attempt = attempt,
                siblingEdgeIds = siblingEdgeIds,
                viaPoints = legPoints,
                alternatives = searchAlternatives,
                curveWeight = curveWeight,
                siblingEdgePenalty = siblingEdgePenalty,
                factors = factors,
                passThrough = passThrough,
                viaHeadings = viaHeadings,
                startHeading = startHeading,
            )
            Log.i(TAG, "Requesting ${style.label} routes (attempt $attempt${curveWeight?.let { ", curve weight $it" } ?: ""})")
            val response = hopper.route(request)
            if (response.hasErrors()) {
                val details = response.errors.joinToString("; ") {
                    it.message ?: it.javaClass.simpleName
                }
                throw IllegalStateException("Routing failed: $details")
            }
            return response.all
        }

        // A diversified re-search soft-penalises the repeated roads of a
        // stop's own optimum: one plain request, never a carousel.
        val routes: List<ResponsePath>
        var rejectedAsDetour = 0
        if (penalizedEdges.isNotEmpty()) {
            routes = search(0, penalizedEdges, roadPenalty)
        } else {
            val carousel = AlternativePolicy.selectCarousel(
                style = style,
                maxCards = if (alternatives) MAX_DISPLAYED_ROUTES else 1,
                attempts = if (searchAlternatives) 0..2 else 0..0,
            ) { attempt, siblingEdgeIds -> search(attempt, siblingEdgeIds, null) }
            routes = carousel.routes
            rejectedAsDetour = carousel.rejectedAsDetour
        }
        if (routes.isEmpty()) throw IllegalStateException("No route was found")

        synchronized(routeCache) { routeCache[key] = routes }
        Log.i(
            TAG,
            "${style.label}: ${routes.size} route(s) in ${SystemClock.elapsedRealtime() - started} ms " +
                "(detours rejected=$rejectedAsDetour, primary ${routes.first().distance}m)",
        )
        return RouteResult(routes)
    }


    private companion object {
        const val COPY_COMPLETE_MARKER = ".copy-complete"
        const val MAX_CACHED_ROUTE_SETS = 24
    }
}
