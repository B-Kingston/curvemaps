package com.curvemaps.app.routing

import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.routing.ev.UrbanDensity
import com.graphhopper.util.shapes.GHPoint
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * Opt-in desktop probe: how each ride factor changes real rides.
 *
 * Loads the bundled graph with the production loader and routes a few
 * Queensland corridors at four styles with every factor combination, printing
 * one `FACTOR_PROBE` line per ride: time, distance, curvy share, total climb,
 * stops, share of the distance in towns, and how much of the factors-off
 * ride's road it still uses. Skipped unless `FACTOR_PROBE=1`:
 *
 * ```
 * FACTOR_PROBE=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest \
 *   --tests '*RideFactorsProbe*' -i | grep FACTOR_PROBE
 * ```
 *
 * It only reports. Use it before retuning the hill or stop/town constants in
 * [ComplexityWeighting] and `EdgeRideFeatures.kt`.
 */
class RideFactorsProbe {

    @Test
    fun probe() {
        val graphDir = listOf(File("src/main/assets/graph-cache"), File("../data/graph-cache"))
            .firstOrNull { File(it, "properties").isFile }
        assumeTrue(System.getenv("FACTOR_PROBE") != null && graphDir != null)
        val hopper = loadMotoGraphHopper(graphDir!!)
        try {
            println("FACTOR_PROBE graph ${hopper.graphFeatures(graphDir)}")
            CORRIDORS.forEach { (name, from, to) ->
                STYLES.forEach { style ->
                    var baseline: ResponsePath? = null
                    FACTORS.forEach { (label, factors) ->
                        val started = System.nanoTime()
                        val response = hopper.route(
                            buildGhRequest(
                                from, to, style, blockUnpaved = false, avoidMotorways = false, attempt = 0,
                                siblingEdgeIds = emptySet(), alternatives = false,
                                // Always measure stops, also for the rides that do not avoid them.
                                factors = factors,
                            ).apply {
                                if (!factors.stopsAndTowns) setPathDetails(pathDetails + MOTO_STOPS_DETAIL)
                            },
                        )
                        check(!response.hasErrors()) { response.errors.toString() }
                        val path = response.best
                        val millis = (System.nanoTime() - started) / 1_000_000
                        val shared = baseline?.let { sharedRoadFraction(hopper, it, path) } ?: 1.0
                        if (baseline == null) baseline = path
                        println(
                            "FACTOR_PROBE %-22s %-12s %-9s %5dms %6.1f min %6.1f km curvy %4.1f%% climb %5d m stops %3d towns %4.1f%% same-roads %3d%%".format(
                                name, style.label, label, millis, path.time / 60_000.0, path.distance / 1_000.0,
                                100 * curvyDistanceFraction(path.points), routeClimbMeters(path.points).roundToInt(),
                                routeStopCount(path) ?: -1, 100 * townFraction(hopper, path),
                                (100 * shared).roundToInt(),
                            ),
                        )
                    }
                }
            }
        } finally {
            hopper.close()
        }
    }

    private fun edgeIds(path: ResponsePath): List<Int> =
        path.pathDetails["edge_id"].orEmpty().map { (it.value as Number).toInt() }

    private fun sharedRoadFraction(hopper: GraphHopper, reference: ResponsePath, path: ResponsePath): Double {
        val referenceEdges = edgeIds(reference).toSet()
        val graph = hopper.baseGraph
        var total = 0.0
        var shared = 0.0
        edgeIds(path).forEach { id ->
            val distance = graph.getEdgeIteratorState(id, Int.MIN_VALUE).distance
            total += distance
            if (id in referenceEdges) shared += distance
        }
        return if (total > 0.0) shared / total else 1.0
    }

    private fun townFraction(hopper: GraphHopper, path: ResponsePath): Double {
        if (!hopper.encodingManager.hasEncodedValue(UrbanDensity.KEY)) return 0.0
        val enc = hopper.encodingManager.getEnumEncodedValue(UrbanDensity.KEY, UrbanDensity::class.java)
        val graph = hopper.baseGraph
        var total = 0.0
        var town = 0.0
        edgeIds(path).forEach { id ->
            val edge = graph.getEdgeIteratorState(id, Int.MIN_VALUE)
            total += edge.distance
            if (edge.get(enc) != UrbanDensity.RURAL) town += edge.distance
        }
        return if (total > 0.0) town / total else 0.0
    }

    private companion object {
        val STYLES = listOf(RideStyle.FASTEST, RideStyle.BALANCED, RideStyle.TWISTY, RideStyle.MAX_TWISTY)
        val FACTORS = listOf(
            "off" to RideFactors.NONE,
            "hills" to RideFactors(elevation = true),
            "stops" to RideFactors(stopsAndTowns = true),
            "both" to RideFactors(elevation = true, stopsAndTowns = true),
        )
        val CORRIDORS = listOf(
            Triple("Brisbane-Samford", GHPoint(-27.4698, 153.0251), GHPoint(-27.3353, 152.7720)),
            Triple("Brisbane-Springwood", GHPoint(-27.4698, 153.0251), GHPoint(-27.6119, 153.1290)),
            Triple("Palmwoods-Brisbane", GHPoint(-26.6886, 152.9600), GHPoint(-27.4698, 153.0251)),
            Triple("Brisbane-Toowoomba", GHPoint(-27.4698, 153.0251), GHPoint(-27.5598, 151.9507)),
        )
    }
}
