package com.curvemaps.app.routing

import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.util.shapes.GHPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Opt-in desktop cost probe for the planner's seven-stop search.
 *
 * Runs [DistinctRideStyles.resolve] the way the app does (`RideStyleOptions`:
 * every stop's optimum two at a time, then escalation, road penalties and
 * the look-around rings) on the bundled graph, and prints per corridor how
 * many GraphHopper searches each stage made, their CPU time and visited
 * nodes, the plan's wall time, and a fingerprint of the seven chosen rides.
 * A routing-speed change must leave every fingerprint unchanged; compare a
 * run before and after it:
 *
 * ```
 * PLAN_COST=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest \
 *   --tests '*PlanCostProbe*' -i | grep PLAN_COST
 * ```
 *
 * `PLAN_COST_REPEAT` (default 2) plans each corridor that many times on the
 * one loaded graph; the first pass is cold (page cache, JIT, graph-lifetime
 * caches), later passes warm. `PLAN_COST_CORRIDORS` (comma-separated names)
 * picks corridors. It only reports.
 */
class PlanCostProbe {

    private class Corridor(
        val name: String,
        val from: GHPoint,
        val to: GHPoint,
        val avoidMotorways: Boolean = false,
        val factors: RideFactors = RideFactors.NONE,
    )

    private class Tally {
        var searches = 0
        var failures = 0
        var cpuNanos = 0L
        var visitedNodes = 0L
    }

    @Test
    fun probe() {
        val graphDir = listOf(File("src/main/assets/graph-cache"), File("../data/graph-cache"))
            .firstOrNull { File(it, "properties").isFile }
        assumeTrue(System.getenv("PLAN_COST") != null && graphDir != null)
        val names = System.getenv("PLAN_COST_CORRIDORS")?.split(',')?.map { it.trim() }?.toSet()
        val repeat = System.getenv("PLAN_COST_REPEAT")?.toInt() ?: 2
        val loadStarted = System.nanoTime()
        val hopper = loadMotoGraphHopper(graphDir!!)
        println("PLAN_COST graph loaded in %d ms (%d nodes, %d edges)".format(
            (System.nanoTime() - loadStarted) / 1_000_000, hopper.baseGraph.nodes, hopper.baseGraph.edges,
        ))
        val pool = Executors.newFixedThreadPool(PARALLELISM)
        try {
            for (pass in 1..repeat) {
                var wallTotal = 0L
                var cpuTotal = 0L
                var searchTotal = 0
                CORRIDORS.filter { names == null || it.name in names }.forEach { corridor ->
                    val (wall, tallies, fingerprint) = plan(hopper, pool, corridor)
                    val cpu = tallies.values.sumOf { it.cpuNanos } / 1_000_000
                    val searches = tallies.values.sumOf { it.searches }
                    wallTotal += wall
                    cpuTotal += cpu
                    searchTotal += searches
                    println(
                        "PLAN_COST pass=%d %-22s wall=%6d ms cpu=%6d ms searches=%3d %s visited=%dk rides=%s".format(
                            pass, corridor.name, wall, cpu, searches,
                            STAGES.joinToString(" ") { stage ->
                                val t = tallies.getValue(stage)
                                "$stage=${t.searches}/${t.cpuNanos / 1_000_000}ms" + if (t.failures > 0) "(${t.failures} failed)" else ""
                            },
                            tallies.values.sumOf { it.visitedNodes } / 1_000, fingerprint,
                        ),
                    )
                }
                println("PLAN_COST pass=%d TOTAL wall=%d ms cpu=%d ms searches=%d".format(pass, wallTotal, cpuTotal, searchTotal))
            }
        } finally {
            pool.shutdownNow()
            hopper.close()
        }
    }

    private fun plan(hopper: GraphHopper, pool: java.util.concurrent.ExecutorService, corridor: Corridor): Triple<Long, Map<String, Tally>, String> {
        val tallies = STAGES.associateWith { Tally() }
        fun search(style: RideStyle, search: RideStyleSearch, stage: String): ResponsePath? {
            val cpuStarted = threadCpuNanos()
            try {
                val via = search.via?.let {
                    ThroughRoadSnap.snap(hopper.locationIndex, hopper.encodingManager, it, twoWay = true) ?: return null
                }
                val request = buildGhRequest(
                    corridor.from, corridor.to, style, false, corridor.avoidMotorways, 0, search.penalizedEdges,
                    viaPoints = listOfNotNull(via), alternatives = false, curveWeight = search.curveWeight,
                    siblingEdgePenalty = if (search.penalizedEdges.isNotEmpty()) search.roadPenalty else null,
                    factors = corridor.factors, passThrough = via != null,
                )
                val response = hopper.route(request)
                synchronized(tallies) {
                    tallies.getValue(stage).visitedNodes += response.hints.getLong("visited_nodes.sum", 0)
                }
                return if (response.hasErrors()) null else response.best
            } finally {
                val cpu = threadCpuNanos() - cpuStarted
                synchronized(tallies) {
                    val tally = tallies.getValue(stage)
                    tally.searches++
                    tally.cpuNanos += cpu
                }
            }
        }
        val started = System.nanoTime()
        val optima: Map<RideStyle, Future<ResponsePath?>> = RideStyle.entries.associateWith { style ->
            pool.submit<ResponsePath?> { search(style, RideStyleSearch(), "optimum") }
        }
        val options = runBlocking {
            DistinctRideStyles.resolve(
                optimum = { style -> optima.getValue(style).get() ?: error("No ${style.label} route on ${corridor.name}") },
                reweighted = { style, weight -> search(style, RideStyleSearch(curveWeight = weight), "escalate") },
                diversified = { style, s -> search(style, s, if (s.via != null) "lookAround" else "penalty") },
                rings = DistinctRideStyles.detourRings(corridor.from, corridor.to),
            )
        }
        val wall = (System.nanoTime() - started) / 1_000_000
        return Triple(wall, tallies, fingerprint(options))
    }

    /**
     * The calling thread's CPU time. The unit-test compile classpath is
     * android.jar, which has no java.lang.management, so it is reached
     * reflectively on the desktop JVM.
     */
    private fun threadCpuNanos(): Long = THREAD_CPU.invoke(THREAD_MX) as Long

    /** One short hash of every stop's ride: its points, distance and time. */
    private fun fingerprint(options: Map<RideStyle, DistinctRideStyles.Option>): String {
        val digest = MessageDigest.getInstance("SHA-1")
        RideStyle.entries.forEach { style ->
            val path = options.getValue(style).path
            digest.update("${style.level}:${path.time}:${"%.3f".format(path.distance)}:${"%.6f".format(path.routeWeight)}".toByteArray())
            val points = path.points
            for (i in 0 until points.size()) digest.update("%.6f,%.6f;".format(points.getLat(i), points.getLon(i)).toByteArray())
        }
        return digest.digest().take(6).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** `RideStyleOptions.DEFAULT_PARALLELISM`. */
        const val PARALLELISM = 2

        val THREAD_MX: Any = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val THREAD_CPU: java.lang.reflect.Method =
            Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime")

        val STAGES = listOf("optimum", "escalate", "penalty", "lookAround")

        val CORRIDORS = listOf(
            Corridor("skyline-palmwoods", GHPoint(-26.6787181, 152.9698004), GHPoint(-26.6859705, 152.959253)),
            Corridor("palmwoods-nambour", GHPoint(-26.6886, 152.9600), GHPoint(-26.6263, 152.9592)),
            Corridor("samford-brisbane", GHPoint(-27.3727, 152.8864), GHPoint(-27.4698, 153.0251)),
            Corridor("nerang-canungra", GHPoint(-27.9896566, 153.3365942), GHPoint(-28.0171019, 153.165254), avoidMotorways = true, factors = RideFactors(elevation = true, stopsAndTowns = true)),
            Corridor("caboolture-kilcoy", GHPoint(-27.0839336, 152.9510863), GHPoint(-26.9431268, 152.5654162)),
            Corridor("palmwoods-brisbane", GHPoint(-26.6859705, 152.959253), GHPoint(-27.4689623, 153.0235009), avoidMotorways = true, factors = RideFactors(elevation = true)),
            Corridor("brisbane-toowoomba", GHPoint(-27.4689623, 153.0235009), GHPoint(-27.5610193, 151.953351), factors = RideFactors(elevation = true, stopsAndTowns = true)),
            Corridor("toowoomba-warwick", GHPoint(-27.5598, 151.9507), GHPoint(-28.2167, 152.0333)),
            Corridor("brisbane-rockhampton", GHPoint(-27.4698, 153.0251), GHPoint(-23.3791, 150.5100)),
        )
    }
}
