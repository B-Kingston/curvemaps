package com.curvemaps.app.routing.suggestion

import com.curvemaps.app.routing.suggestion.SyntheticRoutes.join
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.straight
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.track
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.twisty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiversionGeometryTest {
    private val baselineVertices = straight(0.0, 40_000.0, speedMps = 25.0)
    private val baseline = track(baselineVertices)

    @Test
    fun identicalTracksHaveNoDiversion() {
        assertNull(DiversionGeometry.span(baseline, 0.0, baseline))
    }

    @Test
    fun findsWhereAReplacementLeavesAndRejoins() {
        val replacement = track(
            join(straight(0.0, 10_000.0, 25.0), twisty(10_000.0, 25_000.0, 20.0), straight(25_000.0, 40_000.0, 25.0)),
        )
        val span = DiversionGeometry.span(baseline, 0.0, replacement)!!
        // Leaves within a ramp step of x = 10 km, rejoins within one of 25 km.
        assertEquals(10_000.0, span.divergeBaselineOffsetM, 60.0)
        assertEquals(25_000.0, span.rejoinBaselineOffsetM, 60.0)
        assertEquals(15_000.0, span.replacedBaselineM, 120.0)
        assertTrue("the twisty stretch is longer than the road it replaces", span.distinctM > span.replacedBaselineM)
        assertEquals(10_000.0, span.divergeReplacementOffsetM, 60.0)
    }

    @Test
    fun aReplacementStartingMidRouteIsMeasuredFromThatOffset() {
        val replacement = track(
            join(straight(6_000.0, 10_000.0, 25.0), twisty(10_000.0, 25_000.0, 20.0), straight(25_000.0, 40_000.0, 25.0)),
        )
        val span = DiversionGeometry.span(baseline, 6_000.0, replacement)!!
        assertEquals(10_000.0, span.divergeBaselineOffsetM, 60.0)
        // Replacement offsets start at the rider, 6 km along the baseline.
        assertEquals(4_000.0, span.divergeReplacementOffsetM, 60.0)
    }

    @Test
    fun shortWigglesBelowTheMinimumRunAreIgnored() {
        // One off-road vertex: the off-run to rejoin is ~112 m, below 200 m.
        val vertices = straight(0.0, 40_000.0, 25.0).map {
            if (it.x == 5_000.0) it.copy(y = 50.0) else it
        }
        assertNull(DiversionGeometry.span(baseline, 0.0, track(vertices)))
    }

    @Test
    fun aReplacementThatNeverRejoinsHasNoSpan() {
        val replacement = track(join(straight(0.0, 10_000.0, 25.0), twisty(10_000.0, 25_000.0, 20.0, offsetY = 500.0).dropLast(20)))
        assertNull(DiversionGeometry.span(baseline, 0.0, replacement))
    }
}
