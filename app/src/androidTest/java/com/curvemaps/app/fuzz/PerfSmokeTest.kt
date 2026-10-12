package com.curvemaps.app.fuzz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Process
import com.curvemaps.app.RideStyleOptions
import com.curvemaps.app.RouteParams
import com.curvemaps.app.geocoding.GeocodeSearchController
import com.curvemaps.app.geocoding.GeocoderIndex
import com.curvemaps.app.routing.GraphHopperRouter
import com.curvemaps.app.routing.ROUTE_CORPUS
import com.curvemaps.app.routing.RideStyle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class PerfSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Intra-run self-consistency budget: second repeat must stay within. */
    private val repeatFactor = 3.0

    /**
     * The warm route and geocoder query run twice. Their second pass must stay
     * within [repeatFactor] of the first. The curve-weighted route and the
     * seven-stop plan run once here and use the durable cross-run drift check
     * in tools/test/run-all.sh. AGP wipes the app package after each connected
     * run, so on-device history cannot survive.
     *
     * The warm routes are requests the router has not cached (the router
     * keeps every result it computed, so repeating the cold request measured
     * a cache hit): the reverse of the cold route, then the same with unpaved
     * roads blocked, which routes the same roads with the same work.
     */
    @Test(timeout = 900_000)
    fun coldWarmRouteAndGeocoderStayWithinCeilings() = runBlocking {
        val entry = ROUTE_CORPUS.first()
        val curveEntry = ROUTE_CORPUS[1]
        File(context.filesDir, "gh-cache").deleteRecursively()
        val router = GraphHopperRouter(context)
        var coldResult: com.curvemaps.app.routing.RouteResult
        val coldRouteMs = measureTimeMillis {
            coldResult = router.route(entry.from, entry.to, 0.0)
        }
        val warmRouteMs = measureTimeMillis {
            router.route(entry.to, entry.from, 0.0)
        }
        val warmRouteMsRepeat = measureTimeMillis {
            router.route(entry.to, entry.from, 0.0, blockUnpaved = true)
        }
        var curveResult: com.curvemaps.app.routing.RouteResult
        val curveRouteMs = measureTimeMillis {
            curveResult = router.route(curveEntry.from, curveEntry.to, com.curvemaps.app.routing.RideStyle.BALANCED.level.toDouble())
        }
        val suggestionEntry = ROUTE_CORPUS.first { it.name == "Samford to southern Brisbane scenic suggestion corridor" }
        val suggestionBaseline = com.curvemaps.app.routing.navigation.RouteTrackFactory.fromPath(
            router.route(suggestionEntry.from, suggestionEntry.to, com.curvemaps.app.routing.RideStyle.BALANCED.level.toDouble()).routes.first())
        val window = com.curvemaps.app.routing.suggestion.SuggestionWindow.choose(
            suggestionBaseline.totalDistanceM, 15_000.0, 15.0)!!
        val position = suggestionBaseline.positionAt(15_000.0)
        val request = com.curvemaps.app.routing.suggestion.DiversionSearchRequest(
            1, 1, suggestionBaseline, 15_000.0, position.first, position.second,
            window.first, window.second, 15.0, com.curvemaps.app.routing.RideStyle.BALANCED)
        val search = com.curvemaps.app.RideSuggestionSearch()
        val suggestionMs: Long
        val suggestionMsRepeat: Long
        try {
            suggestionMs = measureTimeMillis { org.junit.Assert.assertNotNull(search.search(router, request, suggestionEntry.to, false, false)) }
            suggestionMsRepeat = measureTimeMillis { org.junit.Assert.assertNotNull(search.search(router, request, suggestionEntry.to, false, false)) }
        } finally { search.close() }
        assertTrue("ride suggestion took ${suggestionMs}ms", suggestionMs <= 120_000L)
        assertSelfConsistent("ride suggestion", suggestionMs, suggestionMsRepeat)
        val (planMs, planCpuMs) = sevenStopPlan(router)
        assertTrue("seven-stop plan took ${planMs}ms", planMs <= 600_000L)
        val loadMs = measureTimeMillis { GeocoderIndex.load(context) }
        val controller = GeocodeSearchController(context)
        val queryMs = measureTimeMillis { controller.search("Brisbane") }
        val queryMsRepeat = measureTimeMillis { controller.search("Brisbane") }

        assertTrue("cold route took ${coldRouteMs}ms", coldRouteMs <= 240_000L)
        assertTrue("warm route took ${warmRouteMs}ms", warmRouteMs <= 30_000L)
        assertTrue("curve-weighted route took ${curveRouteMs}ms", curveRouteMs <= 120_000L)
        assertTrue("geocoder load took ${loadMs}ms", loadMs <= 60_000L)
        assertTrue("geocoder query took ${queryMs}ms", queryMs <= 3_000L)
        assertTrue(coldResult.routes.isNotEmpty())
        assertTrue(curveResult.routes.isNotEmpty())

        assertSelfConsistent("warm route", warmRouteMs, warmRouteMsRepeat)
        assertSelfConsistent("geocoder query", queryMs, queryMsRepeat)

        val perfJson = "{\"coldRouteMs\":$coldRouteMs,\"warmRouteMs\":$warmRouteMs," +
            "\"warmRouteMsRepeat\":$warmRouteMsRepeat,\"curveRouteMs\":$curveRouteMs," +
            "\"geocoderLoadMs\":$loadMs,\"queryMs\":$queryMs," +
            "\"queryMsRepeat\":$queryMsRepeat,\"suggestionMs\":$suggestionMs," +
            "\"suggestionMsRepeat\":$suggestionMsRepeat,\"planMs\":$planMs,\"planCpuMs\":$planCpuMs}"
        File(context.filesDir, "perf.json").writeText(perfJson + "\n")
        // AGP wipes this package after a per-class connected task. Its saved
        // per-test logcat remains durable even if the live file watcher misses it.
        android.util.Log.i("CurveMaps.Perf", "PERF_REPORT $perfJson")
        Unit
    }

    /**
     * All seven stops of one plan through the production [RideStyleOptions]
     * (two searches at a time), routed like RouteScreen routes them: wall time
     * until the last stop is ready, and the process's CPU time meanwhile (what
     * the plan costs the battery). Uses the corpus's Samford to Brisbane CBD.
     */
    private fun sevenStopPlan(router: GraphHopperRouter): Pair<Long, Long> {
        val entry = ROUTE_CORPUS.first { it.name == "Samford to Brisbane CBD" }
        val cpuStarted = Process.getElapsedCpuTime()
        val wallMs = measureTimeMillis {
            runBlocking {
                coroutineScope {
                    val options = RideStyleOptions(this) { params, style, search ->
                        router.route(
                            params.from,
                            params.to,
                            style.level.toDouble(),
                            viaPoints = listOfNotNull(search.via),
                            curveWeight = search.curveWeight,
                            penalizedEdges = search.penalizedEdges,
                            roadPenalty = search.roadPenalty,
                            passThrough = search.via != null,
                            twoWayVias = search.via != null,
                        )
                    }
                    try {
                        RideStyle.entries.forEach { style ->
                            val ride = options.route(RouteParams(entry.from, entry.to, style.level.toDouble(), blockUnpaved = false))
                            assertTrue(ride.routes.isNotEmpty())
                        }
                    } finally {
                        options.close()
                    }
                }
            }
        }
        return wallMs to Process.getElapsedCpuTime() - cpuStarted
    }

    private fun assertSelfConsistent(label: String, firstMs: Long, secondMs: Long) {
        val budget = max(firstMs * repeatFactor, firstMs + 250.0)
        assertTrue(
            "$label regressed between repeats: ${firstMs}ms -> ${secondMs}ms " +
                "(allowed up to ${"%.0f".format(budget)}ms)",
            secondMs <= budget,
        )
    }

    /**
     * Absolute ceilings alone are smoke bounds, not regression bounds.
     * Intra-run self-consistency (see [assertSelfConsistent]) covers mid-run
     * degradation; tools/test/run-all.sh additionally compares this run
     * against build/perf.history.jsonl for cross-run drift. run-all.sh owns
     * the cross-run drift factors (DRIFT_FACTORS there); the +250 ms floor
     * and intra-run factor here cover only this suite's own repeat check.
     */
}
