package com.curvemaps.app.routing

import com.graphhopper.util.PMap
import com.graphhopper.util.shapes.GHPoint
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in desktop probe: junction jogs on the real Queensland graph.
 *
 * Routes each corridor at every stop and counts [junctionJogs]: detours a
 * faster one- or two-edge shortcut closes. Fails on any jog within the small
 * loop perimeter on a plain ride (no ride factors), which [SmallLoopEdges]
 * rules out; also prints the jogs up to a 400 m detour whatever their loop,
 * with and without the ride factors, for review. Skipped unless
 * `JOG_PROBE=1`; `JOG_CORRIDORS` may name a TSV of
 * `fromLat fromLon toLat toLon fromName toName` rows instead of the built-in
 * corridors:
 *
 * ```
 * JOG_PROBE=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest \
 *   --tests '*JunctionJogProbe*' -i | grep JOG_PROBE
 * ```
 *
 * Before the rule, Parkpine Drive → Dulong left Woombye Palmwoods Road for a
 * 64 m triangle at Taintons Road at every stop from Mostly fast up; on 151
 * rater corridors a quarter of Curvy and 42% of Max twisty rides had a jog.
 */
class JunctionJogProbe {

    @Test
    fun noRideJogsThroughJunctionStructure() {
        val graphDir = listOf(File("src/main/assets/graph-cache"), File("../data/graph-cache"))
            .firstOrNull { File(it, "properties").isFile }
        assumeTrue(System.getenv("JOG_PROBE") != null && graphDir != null)
        val corridors = System.getenv("JOG_CORRIDORS")?.let { path ->
            File(path).readLines().filter { it.isNotBlank() }.map { line ->
                val field = line.split("\t")
                Triple("${field[4]} -> ${field[5]}", GHPoint(field[0].toDouble(), field[1].toDouble()), GHPoint(field[2].toDouble(), field[3].toDouble()))
            }
        } ?: CORRIDORS
        val hopper = loadMotoGraphHopper(graphDir!!)
        try {
            val fastest = hopper.createWeighting(hopper.getProfile(MOTORCYCLE_PROFILE)!!, PMap().putObject(MOTO_RIDE_STYLE, RideStyle.FASTEST.level))
            val failures = mutableListOf<String>()
            listOf(RideFactors.NONE, RideFactors(elevation = true, stopsAndTowns = true)).forEach { factors ->
                val totals = IntArray(RideStyle.entries.size)
                corridors.forEach { (name, from, to) ->
                    RideStyle.entries.forEach { style ->
                        val response = hopper.route(
                            buildGhRequest(from, to, style, false, false, 0, emptySet(), alternatives = false, factors = factors),
                        )
                        if (response.hasErrors()) return@forEach
                        val edges = interiorEdgeIds(response.best)
                        val small = junctionJogs(hopper.baseGraph, fastest, edges)
                        val wide = junctionJogs(hopper.baseGraph, fastest, edges, maxLoopMeters = WIDE_LOOP_METERS)
                            .filter { it.routeMeters <= WIDE_DETOUR_METERS }
                        totals[style.level] += wide.size
                        wide.forEach { jog ->
                            val at = hopper.baseGraph.nodeAccess.let { "%.6f,%.6f".format(it.getLat(jog.fromNode), it.getLon(jog.fromNode)) }
                            println(
                                "JOG_PROBE jog %-34s %-12s %-14s at %s detour %.0f m %.1f s shortcut %.0f m %.1f s loop %.0f m".format(
                                    name, style.label, if (factors.any) "factors" else "plain", at,
                                    jog.routeMeters, jog.routeSeconds, jog.shortcutMeters, jog.shortcutSeconds, jog.loopMeters,
                                ),
                            )
                        }
                        if (!factors.any && small.isNotEmpty()) failures += "$name ${style.label}: $small"
                    }
                }
                println("JOG_PROBE ${if (factors.any) "factors" else "plain"} jogs per stop (${corridors.size} corridors): " +
                    RideStyle.entries.joinToString { "${it.label} ${totals[it.level]}" })
            }
            assertTrue("jogs within the small-loop perimeter: $failures", failures.isEmpty())
        } finally {
            hopper.close()
        }
    }

    /**
     * The rides the planner shows: each stop resolved as the app does, with
     * re-weighted and penalised searches and look-around via points. Reports
     * only: road penalties and via legs may differ between two ways round.
     */
    @Test
    fun plannedRidesReport() = kotlinx.coroutines.runBlocking {
        val graphDir = listOf(File("src/main/assets/graph-cache"), File("../data/graph-cache"))
            .firstOrNull { File(it, "properties").isFile }
        assumeTrue(System.getenv("JOG_PROBE") != null && graphDir != null)
        val hopper = loadMotoGraphHopper(graphDir!!)
        try {
            val fastest = hopper.createWeighting(hopper.getProfile(MOTORCYCLE_PROFILE)!!, PMap().putObject(MOTO_RIDE_STYLE, RideStyle.FASTEST.level))
            listOf(RideFactors.NONE, RideFactors(elevation = true, stopsAndTowns = true)).forEach { factors ->
                val totals = IntArray(RideStyle.entries.size)
                CORRIDORS.forEach { (name, from, to) ->
                    fun search(style: RideStyle, search: RideStyleSearch = RideStyleSearch()): com.graphhopper.ResponsePath? {
                        val via = search.via?.let { ThroughRoadSnap.snap(hopper.locationIndex, hopper.encodingManager, it, twoWay = true) ?: return null }
                        val request = buildGhRequest(
                            from, to, style, false, false, 0, search.penalizedEdges,
                            viaPoints = listOfNotNull(via), alternatives = false, curveWeight = search.curveWeight,
                            factors = factors, passThrough = via != null,
                        ).apply { if (search.penalizedEdges.isNotEmpty()) putHint(MOTO_SIBLING_EDGE_PENALTY, search.roadPenalty) }
                        val response = hopper.route(request)
                        return if (response.hasErrors()) null else response.best
                    }
                    val options = DistinctRideStyles.resolve(
                        optimum = { search(it)!! },
                        reweighted = { style, weight -> search(style, RideStyleSearch(curveWeight = weight)) },
                        diversified = { style, request -> search(style, request) },
                        rings = DistinctRideStyles.detourRings(from, to),
                    )
                    options.forEach { (style, option) ->
                        val jogs = junctionJogs(hopper.baseGraph, fastest, interiorEdgeIds(option.path))
                        totals[style.level] += jogs.size
                        jogs.forEach { jog ->
                            val at = hopper.baseGraph.nodeAccess.let { "%.6f,%.6f".format(it.getLat(jog.fromNode), it.getLon(jog.fromNode)) }
                            println(
                                "JOG_PROBE planned jog %-34s %-12s %-7s round=%d penalty=%.2f via=%s at %s detour %.0f m %.1f s shortcut %.0f m %.1f s".format(
                                    name, style.label, if (factors.any) "factors" else "plain", option.round, option.roadPenalty, option.via != null, at,
                                    jog.routeMeters, jog.routeSeconds, jog.shortcutMeters, jog.shortcutSeconds,
                                ),
                            )
                        }
                    }
                }
                println("JOG_PROBE planned ${if (factors.any) "factors" else "plain"} jogs per stop (${CORRIDORS.size} corridors): " +
                    RideStyle.entries.joinToString { "${it.label} ${totals[it.level]}" })
            }
        } finally {
            hopper.close()
        }
    }

    private companion object {
        /** The survey's wider net: any detour up to 400 m, whatever loop it closes. */
        const val WIDE_DETOUR_METERS = 400.0
        const val WIDE_LOOP_METERS = 2_000.0

        /** The device corpus's seven-stop corridors and others whose rides jogged before the rule. */
        val CORRIDORS = listOf(
            Triple("Parkpine Drive -> Dulong", GHPoint(-26.6798, 152.9697), GHPoint(-26.6333, 152.9000)),
            Triple("Skyline Circuit -> Palmwoods", GHPoint(-26.6787181, 152.9698004), GHPoint(-26.6859705, 152.959253)),
            Triple("Palmwoods -> Kenilworth", GHPoint(-26.6886, 152.9600), GHPoint(-26.5963, 152.7281)),
            Triple("Brisbane CBD -> Mount Glorious", GHPoint(-27.4698, 153.0251), GHPoint(-27.3353, 152.7720)),
            Triple("Samford -> Brisbane CBD", GHPoint(-27.3727, 152.8864), GHPoint(-27.4698, 153.0251)),
            Triple("Toowoomba -> Warwick", GHPoint(-27.5598, 151.9507), GHPoint(-28.2167, 152.0333)),
            Triple("Kulangoor -> Image Flat", GHPoint(-26.5885458, 152.9680239), GHPoint(-26.59861, 152.93444)),
            Triple("Samford Valley -> Brisbane", GHPoint(-27.3691038, 152.8403564), GHPoint(-27.4689623, 153.0235009)),
            Triple("Brisbane -> Balmoral", GHPoint(-27.4689623, 153.0235009), GHPoint(-27.4559074, 153.0664859)),
            Triple("Balmoral -> Bowen Hills", GHPoint(-27.4559074, 153.0664859), GHPoint(-27.4466752, 153.0402224)),
            Triple("Murarrie -> Tarragindi", GHPoint(-27.4609442, 153.105018), GHPoint(-27.5266462, 153.0434187)),
            Triple("Highland Park -> Carrara", GHPoint(-28.011487, 153.3249552), GHPoint(-28.02077, 153.3683962)),
            Triple("Gold Coast -> Jacobs Well", GHPoint(-28.0023731, 153.4145987), GHPoint(-27.781009, 153.3638622)),
            Triple("Amiens -> Cotton Vale", GHPoint(-28.593975, 151.8121877), GHPoint(-28.5183741, 151.9483745)),
        )
    }
}
