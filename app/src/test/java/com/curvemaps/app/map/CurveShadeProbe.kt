package com.curvemaps.app.map

import com.curvemaps.app.routing.ROAD_CLASS_LINK_DETAIL
import com.curvemaps.app.routing.ROUNDABOUT_DETAIL
import com.curvemaps.app.routing.RideStyle
import com.curvemaps.app.routing.buildGhRequest
import com.curvemaps.app.routing.curvyDistanceFraction
import com.curvemaps.app.routing.loadMotoGraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.util.shapes.GHPoint
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in desktop probe: which roads the planner's curve shading colours.
 *
 * Loads the bundled graph with the production loader, routes a few Queensland
 * corridors at every ride style's own optimum, and prints one `CURVE_SHADE`
 * line per ride (curvy share, the share of the line tinted, half and full
 * colour; "tinted" counts a quarter of the way to the curvy colour or more)
 * followed by its visibly shaded stretches with the streets under them.
 * Skipped unless `CURVE_SHADE=1`:
 *
 * ```
 * CURVE_SHADE=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest \
 *   --tests '*CurveShadeProbe*' -i | grep CURVE_SHADE
 * ```
 *
 * It only reports (beyond proving the graph serves the roundabout and ramp
 * details). Use it before retuning `CurvyRouteShading.kt`.
 */
class CurveShadeProbe {

    @Test
    fun probe() {
        val graphDir = listOf(File("src/main/assets/graph-cache"), File("../data/graph-cache"))
            .firstOrNull { File(it, "properties").isFile }
        assumeTrue(System.getenv("CURVE_SHADE") != null && graphDir != null)
        val hopper = loadMotoGraphHopper(graphDir!!)
        try {
            CORRIDORS.forEach { (name, from, to) ->
                RideStyle.entries.forEach { style ->
                    val response = hopper.route(
                        buildGhRequest(
                            from, to, style, blockUnpaved = false, avoidMotorways = false, attempt = 0,
                            siblingEdgeIds = emptySet(), alternatives = false,
                        ),
                    )
                    check(!response.hasErrors()) { response.errors.toString() }
                    val path = response.best
                    check(path.pathDetails.keys.containsAll(listOf(ROUNDABOUT_DETAIL, ROAD_CLASS_LINK_DETAIL))) {
                        "the graph must serve ${path.pathDetails.keys}"
                    }
                    report(name, style, path)
                }
            }
        } finally {
            hopper.close()
        }
    }

    private fun report(name: String, style: RideStyle, path: ResponsePath) {
        val shade = CurveShade.of(path)
        val length = path.distance
        val samples = (0..1_000).map { shade.shadeAtMeters(length * it / 1_000.0) }
        println(
            "CURVE_SHADE %-22s %-12s %6.1f km curvy %4.1f%% tinted %4.1f%% half %4.1f%% full %4.1f%% stops %3d".format(
                name, style.label, length / 1_000.0, 100 * curvyDistanceFraction(path.points),
                100.0 * samples.count { it >= VISIBLE_SHADE } / samples.size,
                100.0 * samples.count { it >= 0.5 } / samples.size, 100.0 * samples.count { it >= 1.0 } / samples.size,
                shade.gradientStops().size,
            ),
        )
        // Each visibly shaded stretch, with the roads GraphHopper names under it.
        val streets = streetsAlong(path)
        var start = -1
        samples.forEachIndexed { index, value ->
            val open = value >= VISIBLE_SHADE
            if (open && start < 0) start = index
            if ((!open || index == samples.lastIndex) && start >= 0) {
                val fromM = length * start / 1_000.0
                val toM = length * index / 1_000.0
                val peak = samples.subList(start, index + 1).max()
                val names = streets.filter { (range, _) -> range.endInclusive >= fromM && range.start <= toM }
                    .map { it.second }.distinct().take(4)
                println("CURVE_SHADE     %6.1f-%6.1f km peak %.2f  %s".format(fromM / 1_000, toM / 1_000, peak, names.joinToString()))
                start = -1
            }
        }
    }

    /** Each instruction's street (with its road class) and the metres it covers. */
    private fun streetsAlong(path: ResponsePath): List<Pair<ClosedFloatingPointRange<Double>, String>> {
        val classes = path.pathDetails["road_class"].orEmpty()
        var cursor = 0
        var meters = 0.0
        return path.instructions.map { instruction ->
            val roadClass = classes.firstOrNull { cursor >= it.first && cursor < it.last }?.value ?: "?"
            val range = meters..(meters + instruction.distance)
            cursor += instruction.points.size()
            meters += instruction.distance
            range to "${instruction.name.ifBlank { "(unnamed)" }} ($roadClass)"
        }
    }

    private companion object {
        /** A quarter of the way to the curvy colour: fainter tints hardly read on the map. */
        const val VISIBLE_SHADE = 0.25

        val CORRIDORS = listOf(
            Triple("Brisbane-Kenilworth", GHPoint(-27.4689623, 153.0235009), GHPoint(-26.5953498, 152.7272623)),
            Triple("Caloundra-Kenilworth", GHPoint(-26.7981633, 153.1354434), GHPoint(-26.5953498, 152.7272623)),
            Triple("Brisbane-Samford", GHPoint(-27.4698, 153.0251), GHPoint(-27.3353, 152.7720)),
            Triple("Palmwoods-Brisbane", GHPoint(-26.6886, 152.9600), GHPoint(-27.4698, 153.0251)),
            Triple("Gore-Amiens", GHPoint(-28.2939682, 151.4897175), GHPoint(-28.593975, 151.8121877)),
        )
    }
}
