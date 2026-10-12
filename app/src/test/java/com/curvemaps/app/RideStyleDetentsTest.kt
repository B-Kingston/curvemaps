package com.curvemaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class RideStyleDetentsTest {
    @Test
    fun approachRampsThenClicksInBothDirections() {
        for ((start, positions, stop) in listOf(
            Triple(0f, listOf(0.63f, 0.75f, 0.85f, 0.95f), 1),
            Triple(2f, listOf(1.37f, 1.25f, 1.15f, 1.05f), 1),
        )) {
            val detents = RideStyleDetents(start)
            val feedback = positions.map(detents::move)
            assertEquals(listOf(1, 2, 3, 0), feedback.map { it.approach })
            assertEquals(listOf(stop), feedback.last().clickedLevels)
            assertEquals(stop.toFloat(), detents.paintedPosition(positions.last()), 0f)
        }
    }

    @Test
    fun dwellingAndJitterDoNotRepeatClicksOrApproachPulses() {
        val detents = RideStyleDetents(0f)
        assertEquals(1, detents.move(0.63f).approach)
        assertEquals(0, detents.move(0.63f).approach)
        assertEquals(0, detents.move(0.65f).approach)
        assertEquals(listOf(1), detents.move(0.96f).clickedLevels)
        assertTrue(!detents.needsReleaseClick(0.96f))
        repeat(100) { index ->
            val feedback = detents.move(if (index % 2 == 0) 0.9f else 1.1f)
            assertTrue(feedback.clickedLevels.isEmpty())
            assertEquals(0, feedback.approach)
        }
        detents.move(1.3f)
        assertTrue(detents.needsReleaseClick(1.3f))
        assertEquals(listOf(1), detents.move(1.02f).clickedLevels)
    }

    @Test
    fun fastSwipesAccountForEveryCrossedStopInTravelOrder() {
        val detents = RideStyleDetents(0f)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), detents.move(9f).clickedLevels)
        assertEquals(listOf(5, 4, 3, 2, 1, 0), detents.move(-5f).clickedLevels)
        assertTrue(detents.move(-9f).clickedLevels.isEmpty())
    }

    @Test
    fun retreatingFromAStopDoesNotRamp() {
        val detents = RideStyleDetents(1f)
        listOf(1.1f, 1.2f, 1.3f, 1.4f).forEach {
            assertEquals(0, detents.move(it).approach)
        }
    }

    @Test
    fun randomPositionsStayBoundedAndSnapOnlyNearAStop() {
        val random = Random(2026)
        val detents = RideStyleDetents(0f)
        repeat(10_000) {
            val raw = random.nextFloat() * 12f - 3f
            val feedback = detents.move(raw)
            assertTrue(feedback.clickedLevels.all { it in 0..6 })
            assertTrue(feedback.approach in 0..3)
            val painted = detents.paintedPosition(raw)
            assertTrue(painted in 0f..6f)
            assertTrue(abs(painted - RideStyleDetents.bounded(raw)) <= RideStyleDetents.SNAP_DISTANCE + 0.00001f)
        }
        assertEquals(0f, RideStyleDetents.bounded(Float.NaN), 0f)
    }
}
