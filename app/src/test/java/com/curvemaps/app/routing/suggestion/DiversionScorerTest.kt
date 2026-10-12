package com.curvemaps.app.routing.suggestion

import com.curvemaps.app.routing.suggestion.SyntheticRoutes.join
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.straight
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.track
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.twisty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DiversionScorerTest {
    private val t = DiversionThresholds()

    /** Comfortably qualifying: 15 km section, +4 min, 8 km more curves. */
    private val good = DiversionMeasure(
        extraTimeS = 240.0,
        extraDistanceM = 2_000.0,
        baselineSectionTimeS = 600.0,
        baselineSectionDistanceM = 15_000.0,
        sectionDistanceM = 17_000.0,
        curvyGainM = 8_000.0,
        curvyShareGain = 0.45,
    )

    @Test
    fun aClearlyBetterRideQualifies() {
        assertNull(DiversionScorer.rejection(good))
        assertEquals(2_000.0, DiversionScorer.score(good), 1e-9)
    }

    @Test
    fun everyThresholdRejectsJustPastItsBoundary() {
        assertEquals("distinct stretch too short", DiversionScorer.rejection(good.copy(sectionDistanceM = t.minDistinctM - 1)))
        assertEquals("too few extra curves", DiversionScorer.rejection(good.copy(curvyGainM = t.minCurvyGainM - 1)))
        assertEquals(
            "curvy share barely changes",
            DiversionScorer.rejection(good.copy(curvyShareGain = t.minCurvyShareGain - 0.001)),
        )
        // Relative cap: half the replaced stretch's 600 s.
        assertEquals("adds too much time", DiversionScorer.rejection(good.copy(extraTimeS = 301.0)))
        assertNull(DiversionScorer.rejection(good.copy(extraTimeS = 300.0)))
        // Absolute cap: 12 minutes even on a long stretch.
        val long = good.copy(baselineSectionTimeS = 3_600.0, curvyGainM = 20_000.0)
        assertNull(DiversionScorer.rejection(long.copy(extraTimeS = 720.0)))
        assertEquals("adds too much time", DiversionScorer.rejection(long.copy(extraTimeS = 721.0)))
        assertEquals(
            "detour too long",
            DiversionScorer.rejection(good.copy(sectionDistanceM = 15_000.0 * t.maxSectionDistanceRatio + 1)),
        )
    }

    @Test
    fun valueForTimeRejectsSlowDetoursForFewCurves() {
        // 3.2 km of curves for 9 extra minutes on a long stretch: 356 m/min.
        val slow = good.copy(extraTimeS = 540.0, baselineSectionTimeS = 2_400.0, curvyGainM = 3_200.0)
        assertEquals("too slow for the curves gained", DiversionScorer.rejection(slow))
        assertTrue(DiversionScorer.qualifies(slow.copy(curvyGainM = 3_600.0)))
    }

    @Test
    fun aFasterCurvierRideIsNeverPenalisedForSavingTime() {
        val faster = good.copy(extraTimeS = -60.0, extraDistanceM = -500.0)
        assertTrue(DiversionScorer.qualifies(faster))
        assertEquals(good.curvyGainM, DiversionScorer.score(faster), 1e-9)
    }

    @Test
    fun moreExtraTimeNeverRaisesTheScore() {
        val random = Random(7)
        repeat(500) {
            val base = good.copy(
                extraTimeS = random.nextDouble(-300.0, 900.0),
                curvyGainM = random.nextDouble(0.0, 20_000.0),
            )
            val worse = base.copy(extraTimeS = base.extraTimeS + random.nextDouble(0.0, 600.0))
            assertTrue(DiversionScorer.score(worse) <= DiversionScorer.score(base) + 1e-9)
            if (!DiversionScorer.qualifies(base)) assertFalse(DiversionScorer.qualifies(worse))
        }
    }

    @Test
    fun lessCurvyNeverQualifies() {
        val random = Random(11)
        repeat(500) {
            val measure = good.copy(curvyGainM = -random.nextDouble(0.0, 10_000.0))
            assertFalse(DiversionScorer.qualifies(measure))
        }
    }

    @Test
    fun bestPicksTheHighestScoreAndKeepsTheFirstOnTies() {
        val better = good.copy(curvyGainM = 9_000.0)
        val rejected = good.copy(curvyGainM = 100.0)
        assertEquals("b", DiversionScorer.best(listOf("a" to good, "b" to better, "c" to rejected))?.first)
        assertEquals("a", DiversionScorer.best(listOf("a" to good, "b" to good))?.first)
        assertNull(DiversionScorer.best(listOf("c" to rejected)))
    }

    @Test
    fun measuresATwistyRidgeRoadAgainstAStraightHighway() {
        val baseline = track(straight(0.0, 40_000.0, speedMps = 25.0))
        val replacement = track(
            join(straight(0.0, 10_000.0, 25.0), twisty(10_000.0, 25_000.0, 20.0), straight(25_000.0, 40_000.0, 25.0)),
        )
        val span = DiversionGeometry.span(baseline, 0.0, replacement)!!
        val measure = DiversionMeasure.of(baseline, 0.0, baseline.totalDistanceM, replacement, span)
        assertEquals(replacement.totalTimeS - baseline.totalTimeS, measure.extraTimeS, 1e-6)
        assertEquals(replacement.totalDistanceM - baseline.totalDistanceM, measure.extraDistanceM, 1e-6)
        assertEquals(600.0, measure.baselineSectionTimeS, 5.0)
        assertTrue("ridge road adds curves: ${measure.curvyGainM}", measure.curvyGainM > 5_000.0)
        assertTrue(measure.curvyShareGain > 0.3)
        assertNull(DiversionScorer.rejection(measure))
    }

    @Test
    fun aStraightParallelRoadIsNotWorthIt() {
        val baseline = track(straight(0.0, 40_000.0, speedMps = 25.0))
        val parallel = track(
            join(
                straight(0.0, 10_000.0, 25.0),
                listOf(SyntheticRoutes.Vertex(10_300.0, 500.0, 25.0)),
                straight(10_400.0, 24_600.0, 25.0, y = 500.0),
                listOf(SyntheticRoutes.Vertex(24_700.0, 500.0, 25.0)),
                straight(25_000.0, 40_000.0, 25.0),
            ),
        )
        val span = DiversionGeometry.span(baseline, 0.0, parallel)!!
        val measure = DiversionMeasure.of(baseline, 0.0, baseline.totalDistanceM, parallel, span)
        assertEquals("too few extra curves", DiversionScorer.rejection(measure))
    }
}
