package com.curvemaps.app.routing

import com.graphhopper.ResponsePath
import com.graphhopper.util.shapes.GHPoint
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in desktop calibration probe for the ride styles on the real graph.
 *
 * Loads the bundled graph (`app/src/main/assets/graph-cache`, or
 * `data/graph-cache`) with the production profile and weighting factory, then
 * replays the production carousel ([AlternativePolicy.selectCarousel], retries
 * included) and prints one `CURVE_PROBE` line per card for every style: time,
 * distance and curvy share, plus how many candidates the style's detour
 * budget rejected. Skipped unless `CURVE_PROBE=1`:
 *
 * ```
 * CURVE_PROBE=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest \
 *   --tests '*CurvyCorridorProbe*' -i | grep CURVE_PROBE
 * ```
 *
 * It only reports. Use it before retuning [RideStyle] or [ComplexityWeighting].
 */
class CurvyCorridorProbe {

    @Test
    fun probe() {
        val graphDir = listOf(File("src/main/assets/graph-cache"), File("../data/graph-cache"))
            .firstOrNull { File(it, "properties").isFile }
        assumeTrue(System.getenv("CURVE_PROBE") != null && graphDir != null)
        val hopper = loadMotoGraphHopper(graphDir!!)
        try {
            CORRIDORS.forEach { (name, from, to) ->
                RideStyle.entries.forEach { style ->
                    val started = System.nanoTime()
                    val carousel = AlternativePolicy.selectCarousel(style, MAX_DISPLAYED_ROUTES, 0..2) { attempt, siblings ->
                        val response = hopper.route(buildGhRequest(from, to, style, false, false, attempt, siblings))
                        check(!response.hasErrors()) { response.errors.toString() }
                        response.all
                    }
                    val millis = (System.nanoTime() - started) / 1_000_000
                    carousel.routes.forEachIndexed { index, path ->
                        println(line(name, style, index, millis, path))
                    }
                    println("CURVE_PROBE %-20s %-10s rejected-as-detour=%d".format(name, style.label, carousel.rejectedAsDetour))
                }
            }
        } finally {
            hopper.close()
        }
    }

    private fun line(name: String, style: RideStyle, index: Int, millis: Long, path: ResponsePath) =
        "CURVE_PROBE %-20s %-10s card%d %5dms %6.1f min %6.1f km curvy %4.1f%% (distance_m=%.4f duration_ms=%d)".format(
            name, style.label, index + 1, millis, path.time / 60_000.0, path.distance / 1_000.0,
            100 * curvyDistanceFraction(path.points), path.distance, path.time,
        )

    private companion object {
        val CORRIDORS = listOf(
            Triple("Palmwoods-Brisbane", GHPoint(-26.6886, 152.9600), GHPoint(-27.4698, 153.0251)),
            Triple("Brisbane-Toowoomba", GHPoint(-27.4698, 153.0251), GHPoint(-27.5598, 151.9507)),
            Triple("Brisbane-Cairns", GHPoint(-27.4679, 153.0281), GHPoint(-16.9203, 145.7710)),
        )
    }
}
