package com.curvemaps.app.routing.suggestion

import com.curvemaps.app.routing.suggestion.SyntheticRoutes.METRES_PER_DEGREE
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.join
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.straight
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.track
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.twisty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiversionPlannerTest {
    private val baseline = track(straight(0.0, 60_000.0, 25.0))

    private fun card(fromX: Double, toX: Double, twistyFrom: Double, twistyTo: Double, offsetY: Double = 200.0) = track(
        join(straight(fromX, twistyFrom, 25.0), twisty(twistyFrom, twistyTo, 20.0, offsetY), straight(twistyTo, toX, 25.0)),
    )

    @Test
    fun windowPicksTheQualifyingCardWithTheBestValue() {
        val same = track(straight(7_000.0, 32_000.0, 25.0))
        val short = card(7_000.0, 32_000.0, 10_000.0, 12_000.0) // 2 km: too short to matter
        val ridge = card(7_000.0, 32_000.0, 10_000.0, 25_000.0)
        val pick = DiversionPlanner.bestWindowCard(baseline, 7_000.0, 32_000.0, listOf(same, short, ridge))
        assertNotNull(pick)
        assertEquals(2, pick!!.cardIndex)
        assertNull(DiversionPlanner.bestWindowCard(baseline, 7_000.0, 32_000.0, listOf(same, short)))
    }

    @Test
    fun shapingPointsSitOnTheDivergingStretchOnly() {
        val ridge = card(7_000.0, 32_000.0, 10_000.0, 25_000.0)
        val span = DiversionGeometry.span(baseline, 7_000.0, ridge)!!
        val points = DiversionPlanner.shapingPoints(ridge, span)
        assertEquals(DiversionPlanner.MAX_SHAPING_POINTS, points.size)
        points.forEach { (lat, lon) ->
            val y = lat * METRES_PER_DEGREE
            val x = lon * METRES_PER_DEGREE
            assertTrue("($x, $y) lies off the highway", y > 30.0)
            assertTrue("($x, $y) lies inside the diversion", x in 10_000.0..25_000.0)
        }
        // Evenly spread along the stretch, in riding order.
        val xs = points.map { it.second }
        assertEquals(xs.sorted(), xs)
    }

    @Test
    fun shortDiversionsStillGetTwoShapingPoints() {
        val ridge = card(7_000.0, 32_000.0, 10_000.0, 13_500.0)
        val span = DiversionGeometry.span(baseline, 7_000.0, ridge)!!
        assertEquals(DiversionPlanner.MIN_SHAPING_POINTS, DiversionPlanner.shapingPoints(ridge, span).size)
    }

    @Test
    fun proposalRemeasuresTheFullRideAndNamesTheFork() {
        val vertices = join(straight(5_000.0, 12_000.0, 25.0, step = 50.0), twisty(12_000.0, 27_000.0, 20.0), straight(27_000.0, 60_000.0, 25.0))
        val turn = SyntheticRoutes.indexAtX(vertices, 12_000.0)
        val replacement = track(vertices, turnAtVertex = turn)
        val proposal = DiversionPlanner.proposal(1, baseline, 5_000.0, replacement, speedMps = 25.0)
        assertNotNull(proposal)
        proposal!!
        assertEquals(replacement.totalTimeS - baseline.remainingTimeS(5_000.0), proposal.measure.extraTimeS, 1e-6)
        assertEquals("Ridge Road", proposal.divergenceTurn?.streetName)
        assertEquals(5_000.0, proposal.baselineOffsetAtProposalM, 0.0)
    }

    @Test
    fun aForkTooCloseOrTooFarIsNotProposed() {
        fun replacementFrom(x: Double) = track(
            join(straight(x, 12_000.0, 25.0, step = 50.0), twisty(12_000.0, 27_000.0, 20.0), straight(27_000.0, 60_000.0, 25.0)),
        )
        assertNull("700 m: no time to decide", DiversionPlanner.proposal(1, baseline, 11_300.0, replacementFrom(11_300.0), 25.0))
        assertNull("11 km: too early to speak", DiversionPlanner.proposal(1, baseline, 1_000.0, replacementFrom(1_000.0), 25.0))
        assertNotNull(DiversionPlanner.proposal(1, baseline, 6_000.0, replacementFrom(6_000.0), 25.0))
    }

    @Test
    fun divergenceTurnIsOnlyNamedWhenItIsAtTheFork() {
        val vertices = join(straight(5_000.0, 12_000.0, 25.0, step = 50.0), twisty(12_000.0, 27_000.0, 20.0), straight(27_000.0, 60_000.0, 25.0))
        val farTurn = track(vertices, turnAtVertex = SyntheticRoutes.indexAtX(vertices, 40_000.0))
        val span = DiversionGeometry.span(baseline, 5_000.0, farTurn)!!
        assertNull(DiversionPlanner.divergenceTurn(farTurn, span))
    }
}
