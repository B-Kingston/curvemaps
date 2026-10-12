package com.curvemaps.app.routing

import com.graphhopper.ResponsePath
import com.graphhopper.util.shapes.GHPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Set STYLE_CORRIDOR=1 to check the production policy on the real Queensland
 * graph for a 2 km hop: Skyline Circuit Drainage Reserve to Palmwoods.
 *
 * Before the look-around search this corridor failed at Mostly fast ("No
 * meaningfully different ride"), which failed every higher stop and threw the
 * planner back to the From/To fields when the slider moved. The road
 * penalties could not help: all 41 different rides around Palmwoods reuse
 * Woombye Palmwoods Road and Margaret Street, so the fully penalised direct
 * road always stayed cheapest. Every stop must now be a ride of its own.
 *
 * ```
 * STYLE_CORRIDOR=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest \
 *   --tests '*SkylinePalmwoodsTest*' -i | grep SKYLINE
 * ```
 */
class SkylinePalmwoodsTest {
    @Test
    fun everyStopIsARideOfItsOwnOnATwoKilometreHop() = runBlocking {
        val graphDir = listOf(File("src/main/assets/graph-cache"), File("../data/graph-cache"))
            .firstOrNull { File(it, "properties").isFile }
        assumeTrue(System.getenv("STYLE_CORRIDOR") != null && graphDir != null)
        val hopper = loadMotoGraphHopper(graphDir!!)
        try {
            fun search(style: RideStyle, search: RideStyleSearch = RideStyleSearch()): ResponsePath? {
                val via = search.via?.let { ThroughRoadSnap.snap(hopper.locationIndex, hopper.encodingManager, it, twoWay = true) ?: return null }
                val request = buildGhRequest(
                    FROM, TO, style, false, false, 0, search.penalizedEdges,
                    viaPoints = listOfNotNull(via), alternatives = false, curveWeight = search.curveWeight,
                    passThrough = via != null,
                ).apply { if (search.penalizedEdges.isNotEmpty()) putHint(MOTO_SIBLING_EDGE_PENALTY, search.roadPenalty) }
                val response = hopper.route(request)
                return if (response.hasErrors()) null else response.best
            }
            val started = System.nanoTime()
            val options = DistinctRideStyles.resolve(
                optimum = { search(it)!! },
                reweighted = { style, weight -> search(style, RideStyleSearch(curveWeight = weight)) },
                diversified = { style, request -> search(style, request) },
                rings = DistinctRideStyles.detourRings(FROM, TO),
                onResolved = { style, option ->
                    println(
                        "SKYLINE ${style.label}: %.1f min %.2f km curvy %.0f%% round=%d penalty=%.2f via=%s budget=%s repeats=%s".format(
                            option.path.time / 60_000.0, option.path.distance / 1000.0, 100 * curvyDistanceFraction(option.path.points),
                            option.round, option.roadPenalty, option.via, option.budget, option.repeats?.label,
                        ),
                    )
                },
            )
            println("SKYLINE plan resolved in ${(System.nanoTime() - started) / 1_000_000} ms")
            assertEquals(RideStyle.entries.size, options.size)
            val shown = options.mapValues { AlternativePolicy.cachedRoute(it.value.path) }
            options.forEach { (style, option) ->
                assertNull("${style.label} repeats a stop", option.repeats)
                assertFalse("${style.label} drives a road twice", DistinctRideStyles.drivesARoadTwice(shown.getValue(style)))
                assertFalse("${style.label} turns around", DistinctRideStyles.turnsAround(option.path))
                // Before the loop rule Balanced and Curvy went round a block by Palmwoods.
                assertEquals(
                    "${style.label} goes round a loop",
                    DistinctRideStyles.LoopKind.NONE,
                    DistinctRideStyles.loopKind(shown.getValue(style), shown.getValue(RideStyle.FASTEST)),
                )
                if (option.budget == DistinctRideStyles.Budget.STYLE) {
                    assertTrue(
                        "${style.label} within budget",
                        AlternativePolicy.withinGlobalDetourBudget(option.path, search(style)!!, style),
                    )
                }
            }
            RideStyle.entries.forEachIndexed { index, higher ->
                RideStyle.entries.take(index).forEach { lower ->
                    assertFalse(
                        "${higher.label} repeats ${lower.label}",
                        DistinctRideStyles.sameRide(shown.getValue(higher), shown.getValue(lower)),
                    )
                }
            }
            // The gold the device corpus pins for Fastest.
            val fastest = options.getValue(RideStyle.FASTEST).path
            println("SKYLINE GOLD distance_m=%.4f duration_ms=%d".format(fastest.distance, fastest.time))
        } finally {
            hopper.close()
        }
    }

    private companion object {
        /** Geocoder results for "Skyline Circuit Drainage Reserve" and the Palmwoods locality. */
        val FROM = GHPoint(-26.6787181, 152.9698004)
        val TO = GHPoint(-26.6859705, 152.959253)
    }
}
