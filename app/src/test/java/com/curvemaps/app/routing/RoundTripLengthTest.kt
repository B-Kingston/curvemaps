package com.curvemaps.app.routing

import com.graphhopper.ResponsePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundTripLengthTest {
    @Test
    fun labelsReadLikeTheRouteCard() {
        assertEquals("15 min", RoundTripLength.Time(15).label)
        assertEquals("1 h", RoundTripLength.Time(60).label)
        assertEquals("1 h 30 min", RoundTripLength.Time(90).label)
        assertEquals("8 h", RoundTripLength.Time(480).label)
        assertEquals("120 km", RoundTripLength.Distance(120).label)
    }

    @Test
    fun steppersMoveAlongTheGrid() {
        assertEquals(RoundTripLength.Time(75), RoundTripLength.Time(60).stepped(1))
        assertEquals(RoundTripLength.Time(45), RoundTripLength.Time(60).stepped(-1))
        // A typed value steps onto the grid, never past the next grid value.
        assertEquals(RoundTripLength.Time(75), RoundTripLength.Time(67).stepped(1))
        assertEquals(RoundTripLength.Time(60), RoundTripLength.Time(67).stepped(-1))
        // Five kilometres up to 50 km, ten above.
        assertEquals(RoundTripLength.Distance(50), RoundTripLength.Distance(45).stepped(1))
        assertEquals(RoundTripLength.Distance(60), RoundTripLength.Distance(50).stepped(1))
        assertEquals(RoundTripLength.Distance(50), RoundTripLength.Distance(60).stepped(-1))
        assertEquals(RoundTripLength.Distance(45), RoundTripLength.Distance(50).stepped(-1))
        assertEquals(RoundTripLength.Distance(40), RoundTripLength.Distance(37).stepped(1))
        assertEquals(RoundTripLength.Distance(35), RoundTripLength.Distance(37).stepped(-1))
        assertEquals(RoundTripLength.Distance(50), RoundTripLength.Distance(55).stepped(-1))
    }

    @Test
    fun steppersStopAtTheEndsOfTheRange() {
        val shortest = RoundTripLength.Time(RoundTripLength.MIN_MINUTES)
        val longest = RoundTripLength.Time(RoundTripLength.MAX_MINUTES)
        assertEquals(shortest, shortest.stepped(-1))
        assertEquals(longest, longest.stepped(1))
        assertTrue(shortest.atMinimum && !shortest.atMaximum)
        assertTrue(longest.atMaximum && !longest.atMinimum)
        val nearest = RoundTripLength.Distance(RoundTripLength.MIN_KILOMETRES)
        val farthest = RoundTripLength.Distance(RoundTripLength.MAX_KILOMETRES)
        assertEquals(nearest, nearest.stepped(-1))
        assertEquals(farthest, farthest.stepped(1))
        assertFalse(RoundTripLength.DEFAULT.atMinimum || RoundTripLength.DEFAULT.atMaximum)
    }

    @Test
    fun everyGridValueStepsUpAndBackToItself() {
        for (unit in listOf<RoundTripLength>(RoundTripLength.Time(0), RoundTripLength.Distance(0))) {
            var length = unit.withAmount(0).coerced()
            var guard = 0
            while (!length.atMaximum) {
                val up = length.stepped(1)
                assertTrue("$length steps up", up.amount > length.amount)
                assertEquals("$length steps back", length, up.stepped(-1))
                length = up
                assertTrue(++guard < 200)
            }
        }
    }

    @Test
    fun typedValuesClampIntoRange() {
        assertEquals(RoundTripLength.Time(RoundTripLength.MIN_MINUTES), RoundTripLength.Time(3).coerced())
        assertEquals(RoundTripLength.Time(RoundTripLength.MAX_MINUTES), RoundTripLength.Time(999).coerced())
        assertEquals(RoundTripLength.Distance(RoundTripLength.MIN_KILOMETRES), RoundTripLength.Distance(0).coerced())
        assertEquals(RoundTripLength.Distance(RoundTripLength.MAX_KILOMETRES), RoundTripLength.Distance(999).coerced())
        assertEquals(RoundTripLength.Distance(137), RoundTripLength.Distance(137).coerced())
    }

    @Test
    fun theStoredFormRoundTripsAndGarbageFallsBackToTheDefault() {
        listOf(RoundTripLength.Time(90), RoundTripLength.Distance(120), RoundTripLength.DEFAULT).forEach {
            assertEquals(it, RoundTripLength.parse(it.storageKey))
        }
        assertEquals(RoundTripLength.DEFAULT, RoundTripLength.parse(null))
        assertEquals(RoundTripLength.DEFAULT, RoundTripLength.parse("speed:9"))
        assertEquals(RoundTripLength.DEFAULT, RoundTripLength.parse("time:lots"))
        assertEquals(RoundTripLength.Distance(RoundTripLength.MAX_KILOMETRES), RoundTripLength.parse("distance:5000"))
    }

    @Test
    fun switchingUnitKeepsAboutTheSameRide() {
        assertEquals(RoundTripLength.Distance(60), RoundTripLength.Time(60).asDistance())
        assertEquals(RoundTripLength.Time(60), RoundTripLength.Distance(55).asTime())
        assertEquals(RoundTripLength.Time(90), RoundTripLength.Time(90).asTime())
        assertEquals(RoundTripLength.Distance(120), RoundTripLength.Distance(120).asDistance())
        // There and back stays within one grid step.
        (RoundTripLength.TIME_PRESETS + RoundTripLength.DISTANCE_PRESETS).forEach { preset ->
            val back = if (preset is RoundTripLength.Time) preset.asDistance().asTime() else preset.asTime().asDistance()
            val step = if (preset is RoundTripLength.Time) RoundTripLength.MINUTE_STEP else 10
            assertTrue("$preset came back as $back", kotlin.math.abs(back.amount - preset.amount) <= step)
        }
    }

    @Test
    fun aRidesOwnLengthLandsOnTheGrid() {
        assertEquals(RoundTripLength.Time(105), RoundTripLength.near(RoundTripLength.DEFAULT, 112L * 60_000, 0.0))
        assertEquals(RoundTripLength.Distance(120), RoundTripLength.near(RoundTripLength.DEFAULT_DISTANCE, 0L, 118_400.0))
        assertEquals(RoundTripLength.Distance(35), RoundTripLength.near(RoundTripLength.DEFAULT_DISTANCE, 0L, 36_000.0))
        assertEquals(RoundTripLength.Time(RoundTripLength.MIN_MINUTES), RoundTripLength.near(RoundTripLength.DEFAULT, 60_000L, 0.0))
    }

    @Test
    fun theRatioComparesTheRideWithTheWish() {
        val path = ResponsePath().setTime(90L * 60_000).setDistance(75_000.0)
        assertEquals(1.5, RoundTripLength.Time(60).ratio(path), 1e-9)
        assertEquals(0.75, RoundTripLength.Distance(100).ratio(path), 1e-9)
        assertEquals(55_000.0, RoundTripLength.Time(60).estimatedRoadMeters, 1e-6)
        assertEquals(100_000.0, RoundTripLength.Distance(100).estimatedRoadMeters, 1e-6)
    }
}
