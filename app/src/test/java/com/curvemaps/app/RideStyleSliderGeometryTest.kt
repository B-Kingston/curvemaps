package com.curvemaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideStyleSliderGeometryTest {
    @Test
    fun sevenStopsSitOnEqualStepsFromTheFirstToTheLastEndOfTheTrack() {
        assertEquals(7, RideStyleSliderGeometry.humpCount)
        val fractions = (0..6).map { RideStyleSliderGeometry.fractionAtLevel(it.toFloat()) }
        assertEquals(0f, fractions.first(), 0f)
        assertEquals(1f, fractions.last(), 0f)
        fractions.forEachIndexed { level, fraction -> assertEquals(level / 6f, fraction, 1e-6f) }
        fractions.forEachIndexed { level, fraction ->
            assertEquals(level.toFloat(), RideStyleSliderGeometry.levelAtFraction(fraction), 0f)
        }
        assertEquals(0f, RideStyleSliderGeometry.levelAtFraction(-1f), 0f)
        assertEquals(6f, RideStyleSliderGeometry.levelAtFraction(2f), 0f)
    }

    @Test
    fun theWaveRunsFromTheFirstCrestToTheLastSoStopsSitOnCrests() {
        val g = RideStyleSliderGeometry
        assertEquals(0.5f, g.positionAtFraction(0f), 1e-6f)
        assertEquals(g.humpCount - 0.5f, g.positionAtFraction(1f), 1e-6f)
        for (level in 0..6) {
            val u = g.positionAtFraction(g.fractionAtLevel(level.toFloat()))
            assertEquals(level + 0.5f, u, 1e-6f)
            assertTrue("crest at stop $level", g.waveHeight(u) > 0f)
        }
    }

    @Test
    fun growthFrontStartsAtTheTrackStartAndReachesItsEndWhenEveryStopIsReady() {
        val g = RideStyleSliderGeometry
        assertEquals(0f, g.fractionAtGrowthFront(0f), 0f)
        assertEquals(1f, g.fractionAtGrowthFront(g.humpCount.toFloat()), 0f)
        val fractions = (0..g.humpCount).map { g.fractionAtGrowthFront(it.toFloat()) }
        assertTrue(fractions.zipWithNext().all { (a, b) -> b >= a })
        // Once a stop is ready the front has passed its dot.
        for (ready in 1..g.humpCount) {
            val lastReadyStop = ready - 1
            assertTrue(g.fractionAtGrowthFront(ready.toFloat()) >= g.fractionAtLevel(lastReadyStop.toFloat()))
        }
    }

    @Test
    fun trackMappingIsContinuousMonotonicAndClampsAtBothEnds() {
        var previous = 0f
        for (step in 0..1000) {
            val level = RideStyleSliderGeometry.levelAtFraction(step / 1000f)
            assertTrue(level in 0f..6f && level >= previous)
            previous = level
        }
    }

    @Test
    fun humpsGrowTallerWithEachLevel() {
        val heights = (0..6).map { RideStyleSliderGeometry.envelope(it.toFloat()) }
        assertTrue(heights.zipWithNext().all { (a, b) -> b > a })
        assertTrue(heights.first() > 0f && heights.last() == 1f)
        // The original four stops keep their crests; mid stops sit halfway.
        assertEquals(listOf(0.3f, 0.6f, 0.8f, 1f), listOf(0, 2, 4, 6).map { heights[it] })
        for (mid in listOf(1, 3, 5)) assertEquals((heights[mid - 1] + heights[mid + 1]) / 2f, heights[mid], 1e-6f)
    }

    @Test
    fun waveStartsInAShallowDipAndRisesGentlyIntoTheFastestCrest() {
        val g = RideStyleSliderGeometry
        val start = g.waveHeight(0f)
        assertTrue(start < 0f)
        // The opening dip is the shallowest swing on the track.
        assertTrue(-start < g.waveHeight(0.5f) && -start < -g.waveHeight(1f))
        val rise = (0..50).map { g.waveHeight(it / 100f) }
        assertTrue(rise.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun swingsGrowSteadilyAlongTheWholeTrack() {
        val g = RideStyleSliderGeometry
        // Alternating dips (whole u) and crests (half u), shallowest first.
        val swings = (0..13).map { kotlin.math.abs(g.waveHeight(it / 2f)) }
        assertTrue(swings.zipWithNext().all { (a, b) -> b > a })
        assertEquals(0.5f, g.waveHeight(6.5f), 1e-6f)
    }

    @Test
    fun dragPhaseMovesTheWaveWithoutSeams() {
        val g = RideStyleSliderGeometry
        val h = 1e-3f
        for (phase in listOf(0f, 0.25f, 0.5f, 0.75f, 1.6f)) {
            for (step in 1 until 700) {
                val u = step / 100f
                assertEquals(g.waveHeight(u, phase), g.waveHeight(u + h, phase), 0.01f)
            }
        }
    }

    @Test
    fun everyDipSinksBelowTheCentreLineAndEveryCrestRisesAboveIt() {
        val g = RideStyleSliderGeometry
        for (dip in 0..7) assertTrue(g.waveHeight(dip.toFloat()) < 0f)
        for (level in 0..6) assertTrue(g.waveHeight(level + 0.5f) > 0f)
        // Dips deepen with the crests around them, like the humps.
        val dips = (1..6).map { g.waveHeight(it.toFloat()) }
        assertTrue(dips.zipWithNext().all { (a, b) -> b < a })
    }

    @Test
    fun higherLevelsWaveFaster() {
        val periods = (0..6).map { RideStyleSliderGeometry.wavePeriodMillis(it.toFloat()) }
        assertTrue(periods.zipWithNext().all { (a, b) -> b < a })
        assertTrue(periods.last() > 0f)
    }

    @Test
    fun waveGrowsOneHumpPerReadyStopWithASmallPop() {
        val count = RideStyleSliderGeometry.humpCount
        val samples = (0..700).map { it * count / 700f }
        // Nothing has finished: a flat line.
        samples.forEach { assertEquals(0f, RideStyleSliderGeometry.growthHeight(0f, it), 1e-6f) }
        // Every stop ready: the whole wave, exactly.
        samples.forEach { assertEquals(1f, RideStyleSliderGeometry.growthHeight(count.toFloat(), it), 1e-6f) }
        for (ready in 1..count) {
            val crest = ready - 0.5f
            // The newest stop's crest is fully up the moment it is ready...
            assertEquals(1f, RideStyleSliderGeometry.growthHeight(ready.toFloat(), crest), 1e-6f)
            // ...while the next stop's hump is still flat.
            if (ready < count) assertEquals(0f, RideStyleSliderGeometry.growthHeight(ready.toFloat(), crest + 1f), 1e-6f)
        }
        var overshoot = 0f
        for (step in 0..700) {
            val grown = step * count / 700f
            val heights = samples.map { RideStyleSliderGeometry.growthHeight(grown, it) }
            heights.forEach { assertTrue(it >= 0f) }
            // Growth runs left to right: nothing ahead of the front is taller
            // than something still rising behind it.
            heights.zipWithNext().forEach { (left, right) -> assertTrue(right <= left + 1e-6f || left >= 1f) }
            overshoot = maxOf(overshoot, heights.max())
        }
        assertTrue(overshoot in 1.05f..1.15f)
    }
}
