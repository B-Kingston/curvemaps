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
 * Set ROUND_TRIP=1 to run the production round-trip search ([RoundTrips]) on
 * the real Queensland graph from several starts and lengths. Every stop must
 * be a loop of its own that comes back to the start, and the Fastest loop of
 * every start must land within the wished length's budget.
 *
 * ```
 * ROUND_TRIP=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest \
 *   --tests '*RoundTripProbeTest*' -i | grep ROUND_TRIP
 * ```
 */
class RoundTripProbeTest {
    @Test
    fun everyStopIsALoopOfItsOwnNearTheWishedLength() = runBlocking {
        val graphDir = listOf(File("src/main/assets/graph-cache"), File("../data/graph-cache"))
            .firstOrNull { File(it, "properties").isFile }
        assumeTrue(System.getenv("ROUND_TRIP") != null && graphDir != null)
        val hopper = loadMotoGraphHopper(graphDir!!)
        try {
            val lengths = System.getenv("ROUND_TRIP_LENGTHS")?.split(',')?.map { RoundTripLength.parse(it) } ?: LENGTHS
            val starts = System.getenv("ROUND_TRIP_STARTS")?.split(',')?.associateWith { STARTS.getValue(it) } ?: STARTS
            for ((name, start) in starts) for (length in lengths) {
                var searches = 0
                fun route(style: RideStyle, shape: RoundTrips.Shape): ResponsePath? {
                    searches++
                    val snapped = shape.vias.map {
                        ThroughRoadSnap.snapPassable(hopper.baseGraph, hopper.locationIndex, hopper.encodingManager, it, shape.searchMeters) ?: return null
                    }
                    val response = hopper.route(
                        buildGhRequest(
                            start, start, style, false, false, 0, emptySet(),
                            viaPoints = snapped, alternatives = false, passThrough = true,
                        ),
                    )
                    return if (response.hasErrors()) null else response.best
                }
                val started = System.nanoTime()
                val curvy = mutableListOf<Double>()
                val options = RoundTrips.resolve(start, length, loop = { style, shape -> route(style, shape) }) { style, option ->
                    val path = option.path
                    curvy += curvyDistanceFraction(path.points)
                    println(
                        "ROUND_TRIP %s %s %s: %.0f min %.1f km curvy %.0f%% w/s %.2f size %.2f retraced %.0f%% lasso %.0f m bearing %s budget %s repeats %s".format(
                            name, length.label, style.label, path.time / 60_000.0, path.distance / 1000, 100 * curvyDistanceFraction(path.points),
                            path.routeWeight / (path.time / 1000.0),
                            length.ratio(path), 100 * DistinctRideStyles.retracedMeters(AlternativePolicy.cachedRoute(path)) / path.distance,
                            RoundTrips.retrace(AlternativePolicy.cachedRoute(path)).second,
                            option.loop?.bearing, option.budget, option.repeats?.label,
                        ),
                    )
                }
                var inversions = 0
                for (i in curvy.indices) for (j in i + 1 until curvy.size) if (curvy[j] < curvy[i] - 0.02) inversions++
                println(
                    "ROUND_TRIP %s %s resolved in %d ms with %d searches; curvy Fastest %.0f%% Max twisty %.0f%% inversions %d".format(
                        name, length.label, (System.nanoTime() - started) / 1_000_000, searches, 100 * curvy.first(), 100 * curvy.last(), inversions,
                    ),
                )
                assertEquals(RideStyle.entries.size, options.size)
                val shown = options.mapValues { AlternativePolicy.cachedRoute(it.value.path) }
                options.forEach { (style, option) ->
                    assertNull("$name ${length.label} ${style.label} repeats a stop", option.repeats)
                    assertFalse("$name ${style.label} turns around", DistinctRideStyles.turnsAround(option.path))
                    val points = option.path.points
                    val back = com.graphhopper.util.DistanceCalcEarth.DIST_EARTH.calcDist(
                        points.getLat(0), points.getLon(0), points.getLat(points.size() - 1), points.getLon(points.size() - 1),
                    )
                    assertTrue("$name ${style.label} ends ${back.toInt()} m from its start", back < 1.0)
                }
                options.forEach { (style, option) ->
                    // A road ridden again mid-loop (a lasso) is never within budget.
                    if (option.budget == DistinctRideStyles.Budget.STYLE) {
                        assertTrue(
                            "$name ${length.label} ${style.label} rides a road again mid-loop",
                            RoundTrips.retrace(AlternativePolicy.cachedRoute(option.path)).second <=
                                RoundTrips.lassoAllowance(option.path.distance),
                        )
                    }
                }
                assertEquals(
                    "$name ${length.label} Fastest within budget",
                    DistinctRideStyles.Budget.STYLE,
                    options.getValue(RideStyle.FASTEST).budget,
                )
                // A rebuild mid-ride routes through the loop's own corners
                // (its waypoints) with the plain through-road snap: same loop.
                options.forEach { (style, option) ->
                    val corners = RoundTrips.viaPointsOf(option.path)
                    assertEquals("$name ${style.label} corners", 2, corners.size)
                    val again = hopper.route(
                        buildGhRequest(
                            start, start, style, false, false, 0, emptySet(),
                            viaPoints = corners.map { ThroughRoadSnap.snap(hopper.locationIndex, hopper.encodingManager, it)!! },
                            alternatives = false, passThrough = true,
                        ),
                    ).best
                    assertTrue(
                        "$name ${length.label} ${style.label} rebuilds onto another loop",
                        DistinctRideStyles.sameRide(AlternativePolicy.cachedRoute(again), shown.getValue(style)),
                    )
                }
                RideStyle.entries.forEachIndexed { index, higher ->
                    RideStyle.entries.take(index).forEach { lower ->
                        assertFalse(
                            "$name ${length.label} ${higher.label} repeats ${lower.label}",
                            DistinctRideStyles.sameRide(shown.getValue(higher), shown.getValue(lower)),
                        )
                    }
                }
            }
        } finally {
            hopper.close()
        }
    }

    private companion object {
        val STARTS = mapOf(
            "Samford" to GHPoint(-27.3727, 152.8864),
            "Palmwoods" to GHPoint(-26.6859705, 152.959253),
            "Brisbane CBD" to GHPoint(-27.4698, 153.0251),
            "Toowoomba" to GHPoint(-27.5598, 151.9507),
            "Noosa" to GHPoint(-26.3980, 153.0600),
            // Its 50 km Max twisty loop used to lasso a corner south of the Mooloolah road.
            "Skyline Circuit" to GHPoint(-26.6787181, 152.9698004),
        )
        val LENGTHS = listOf(
            RoundTripLength.Time(60),
            RoundTripLength.Time(180),
            RoundTripLength.Distance(40),
            RoundTripLength.Distance(150),
            RoundTripLength.Distance(50),
        )
    }
}
