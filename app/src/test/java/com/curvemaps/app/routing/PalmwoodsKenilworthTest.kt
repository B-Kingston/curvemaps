package com.curvemaps.app.routing

import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.util.shapes.GHPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Set STYLE_CORRIDOR=1 to check the production policy against the real Queensland graph. */
class PalmwoodsKenilworthTest {
    @Test
    fun sevenMeaningfulRides() = withGraph { hopper ->
        val from = GHPoint(-26.6886, 152.9600)
        val to = GHPoint(-26.5963, 152.7281)
        val (options, search) = plan(hopper, from, to, "KENILWORTH")
        val offered = options.values.map { AlternativePolicy.cachedRoute(it.path) }
        val fastest = AlternativePolicy.cachedRoute(options.getValue(RideStyle.FASTEST).path)
        assertEquals(7, options.size)
        options.forEach { (style, option) ->
            val best = AlternativePolicy.cachedRoute(search(style)!!)
            val candidate = AlternativePolicy.cachedRoute(option.path)
            assertEquals(style.label, null, option.repeats)
            assertEquals(style.label, DistinctRideStyles.LoopKind.NONE, DistinctRideStyles.loopKind(candidate, fastest))
            // The 74 km ride that fitted Twisty's budget began with 1.2 km round Palmwoods' streets and a
            // block ([DistinctRideStyles.loopKind]); without it Very twisty rides within twice its allowance.
            val allowance = if (style == RideStyle.VERY_TWISTY) DistinctRideStyles.RELAXED_BUDGET_FACTOR else 1.0
            assertTrue(style.label, option.budget == DistinctRideStyles.Budget.STYLE || (allowance > 1.0 && option.budget == DistinctRideStyles.Budget.RELAXED))
            assertTrue(style.label, AlternativePolicy.withinGlobalDetourBudget(option.path, best.path, style, allowance))
            assertFalse(style.label, AlternativePolicy.hasExcessiveLocalDetour(candidate.edgeSections, best.edgeSections))
        }
        assertPairwiseDifferent(offered, "KENILWORTH")
    }

    /**
     * Toowoomba's place point snaps onto one carriageway of Ruthven Street,
     * so every ride that leaves the other way starts with a U-turn 20 m in.
     * Before such end U-turns were exempt, every candidate of the first
     * searches was rejected and Twisty to Max twisty repeated Curvy, though
     * Murphys Creek and Flagstone Creek roads climb the range.
     */
    @Test
    fun aStartOnTheWrongCarriagewayStillGivesSevenRides() = withGraph { hopper ->
        val (options, _) = plan(hopper, GHPoint(-27.5610193, 151.953351), GHPoint(-27.5615314, 152.2791353), "TOOWOOMBA")
        assertEquals(7, options.size)
        options.forEach { (style, option) -> assertEquals(style.label, null, option.repeats) }
        assertPairwiseDifferent(options.values.map { AlternativePolicy.cachedRoute(it.path) }, "TOOWOOMBA")
    }

    /**
     * Curvy stops may take gravel where it is far curvier (it keeps half its
     * curve credit) and the card says so; Block unpaved roads must remove it
     * from every stop. Gore to Amiens crosses Granite Belt country where the
     * curvy roads are gravel (Rabbit Fence Road, Carbean Road).
     */
    @Test
    fun blockUnpavedLeavesNoGravelOnAnyStop() = withGraph { hopper ->
        val from = GHPoint(-28.2939682, 151.4897175)
        val to = GHPoint(-28.593975, 151.8121877)
        val allowed = plan(hopper, from, to, "GRAVEL allowed").first
        val blocked = plan(hopper, from, to, "GRAVEL blocked", blockUnpaved = true).first
        val gravelAllowed = allowed.mapValues { routeGravelMeters(it.value.path)!! }
        val gravelBlocked = blocked.mapValues { routeGravelMeters(it.value.path)!! }
        println("GRAVEL allowed $gravelAllowed blocked $gravelBlocked")
        assertTrue("some stop rides gravel when it is allowed: $gravelAllowed", gravelAllowed.values.any { it >= MIN_GRAVEL_NOTE_METERS })
        gravelBlocked.forEach { (style, meters) -> assertEquals(style.label, 0.0, meters, 0.0) }
    }

    private fun withGraph(body: suspend (GraphHopper) -> Unit) = runBlocking {
        val graphDir = listOf(File("src/main/assets/graph-cache"), File("../data/graph-cache"))
            .firstOrNull { File(it, "properties").isFile }
        assumeTrue(System.getenv("STYLE_CORRIDOR") != null && graphDir != null)
        val hopper = loadMotoGraphHopper(graphDir!!)
        try {
            body(hopper)
        } finally {
            hopper.close()
        }
    }

    /** The production seven-stop plan from [from] to [to], and the single search it used. */
    private suspend fun plan(
        hopper: GraphHopper,
        from: GHPoint,
        to: GHPoint,
        label: String,
        blockUnpaved: Boolean = false,
    ): Pair<Map<RideStyle, DistinctRideStyles.Option>, (RideStyle) -> ResponsePath?> {
        fun search(style: RideStyle, weight: Double? = null, edges: Set<Int> = emptySet(), penalty: Double = 0.0, via: GHPoint? = null): ResponsePath? {
            val snapped = via?.let { ThroughRoadSnap.snap(hopper.locationIndex, hopper.encodingManager, it, twoWay = true) ?: return null }
            val response = hopper.route(buildGhRequest(from, to, style, blockUnpaved, false, 0, edges,
                viaPoints = listOfNotNull(snapped), alternatives = false, curveWeight = weight, passThrough = via != null).apply {
                if (edges.isNotEmpty()) putHint(MOTO_SIBLING_EDGE_PENALTY, penalty)
            })
            if (response.hasErrors()) {
                check(via != null) { response.errors.toString() }
                return null
            }
            return response.best
        }
        val options = DistinctRideStyles.resolve(
            optimum = { search(it)!! },
            reweighted = { style, weight -> search(style, weight) },
            diversified = { style, request -> search(style, request.curveWeight, request.penalizedEdges, request.roadPenalty, request.via) },
            rings = DistinctRideStyles.detourRings(from, to),
            onResolved = { style, option ->
                println("$label ${style.label}: ${option.path.distance / 1000} km, ${option.path.time / 60000.0} min, weight=${option.curveWeight}, round=${option.round}, roadPenalty=${option.roadPenalty}, budget=${option.budget}, curvy=${curvyDistanceFraction(option.path.points)}")
            },
        )
        return options to { style -> search(style) }
    }

    private fun assertPairwiseDifferent(offered: List<AlternativePolicy.CachedRoute>, label: String) {
        for (i in offered.indices) for (j in 0 until i) {
            println("$label pair=$i/$j shared=${AlternativePolicy.sharedRoadFraction(offered[i].edgeDistances, offered[j].edgeDistances)} stretch=${AlternativePolicy.longestDistinctStretch(offered[i].edgeSections, offered[j].edgeDistances.keys)}")
            assertFalse("Stops $i/$j repeat", DistinctRideStyles.sameRide(offered[i], offered[j]))
        }
    }
}
