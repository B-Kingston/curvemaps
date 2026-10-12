package com.curvemaps.app.routing.suggestion

import com.curvemaps.app.routing.RideStyle
import com.curvemaps.app.routing.navigation.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionSchedulerTest {
    private val speed = 25.0 // 90 km/h

    /** Rides [metres] at [speed], one fix per second, returning the new clock. */
    private fun SuggestionScheduler.ride(fromMs: Long, metres: Double, speedMps: Double = speed): Long {
        var now = fromMs
        var covered = 0.0
        while (covered < metres) {
            now += 1_000
            onFix(now, speedMps, speedMps)
            covered += speedMps
        }
        return now
    }

    private fun SuggestionScheduler.due(
        now: Long,
        style: RideStyle = RideStyle.BALANCED,
        state: NavigationState = NavigationState.OnRoute,
        speedMps: Double = speed,
        remainingM: Double = 100_000.0,
        battery: BatteryState = BatteryState(),
        enabled: Boolean = true,
        busy: Boolean = false,
    ) = shouldEvaluate(now, enabled, state, style, speedMps, remainingM, battery, busy)

    @Test
    fun firstCheckComesAfterFiveKilometres() {
        val s = SuggestionScheduler()
        var now = s.ride(0, 4_900.0)
        assertFalse(s.due(now))
        now = s.ride(now, 200.0)
        assertTrue(s.due(now))
    }

    @Test
    fun laterChecksNeedBothTenKilometresAndTenMovingMinutes() {
        val s = SuggestionScheduler()
        var now = s.ride(0, 5_000.0)
        assertTrue(s.due(now))
        s.onEvaluated()
        // 10 km at 40 m/s takes only 250 s: distance alone is not enough.
        now = s.ride(now, 10_100.0, speedMps = 40.0)
        assertFalse(s.due(now, speedMps = 40.0))
        now = s.ride(now, 15_000.0, speedMps = 40.0) // now past 10 moving minutes
        assertTrue(s.due(now, speedMps = 40.0))
    }

    @Test
    fun cadenceIsRoughlySixChecksAnHourAtHighwaySpeed() {
        val s = SuggestionScheduler()
        var now = 0L
        var checks = 0
        while (now < 3_600_000L) {
            now += 1_000
            s.onFix(now, speed, speed)
            if (s.due(now)) {
                s.onEvaluated()
                checks++
            }
        }
        assertTrue("checks per hour: $checks", checks in 5..7)
    }

    @Test
    fun gatesBlockChecksOutsideARealRide() {
        val s = SuggestionScheduler()
        val now = s.ride(0, 6_000.0)
        assertTrue(s.due(now))
        assertFalse("Fastest never gets suggestions", s.due(now, style = RideStyle.FASTEST))
        assertFalse("toggle off", s.due(now, enabled = false))
        assertFalse("one check at a time", s.due(now, busy = true))
        assertFalse("town traffic", s.due(now, speedMps = 6.0))
        assertFalse("unknown speed", s.due(now, speedMps = Double.NaN))
        assertFalse("near the end", s.due(now, remainingM = 14_000.0))
        assertFalse("rebuilding", s.due(now, state = NavigationState.Rebuilding))
        assertFalse("battery saver", s.due(now, battery = BatteryState(powerSaveMode = true)))
        assertFalse("low battery", s.due(now, battery = BatteryState(levelPercent = 15)))
        assertTrue("low battery while charging", s.due(now, battery = BatteryState(levelPercent = 15, charging = true)))
        assertTrue("unknown level counts as healthy", s.due(now, battery = BatteryState(levelPercent = -1)))
        RideStyle.entries.filter { it != RideStyle.FASTEST }.forEach { assertTrue(s.due(now, style = it)) }
    }

    @Test
    fun aDeclinedSuggestionStartsATimeAndDistanceCooldown() {
        val s = SuggestionScheduler()
        var now = s.ride(0, 5_000.0)
        s.onEvaluated()
        s.onProposed(now)
        s.onDeclined(now)
        // 16 km at 25 m/s = 640 s: distance done, 20 minutes not yet.
        now = s.ride(now, 16_000.0)
        assertFalse(s.due(now))
        // Fifteen more minutes pass: now both the distance and the 20 minutes are done.
        now += 15 * 60_000L
        assertTrue(s.due(now))
    }

    @Test
    fun hourlyCapLimitsProposals() {
        val s = SuggestionScheduler()
        var now = s.ride(0, 5_000.0)
        repeat(3) {
            assertTrue(s.due(now))
            s.onEvaluated()
            s.onProposed(now)
            now = s.ride(now, 16_000.0)
        }
        assertFalse("three proposals within the hour", s.due(now))
        now += 3_600_000L
        now = s.ride(now, 100.0)
        assertTrue(s.due(now))
    }

    @Test
    fun resetStartsTheCadenceOver() {
        val s = SuggestionScheduler()
        var now = s.ride(0, 6_000.0)
        s.onProposed(now)
        s.onDeclined(now)
        s.reset()
        assertEquals(0.0, s.riddenMeters, 0.0)
        now = s.ride(now, 5_100.0)
        assertTrue(s.due(now))
    }

    @Test
    fun gpsJumpsAndLongGapsDoNotCount() {
        val s = SuggestionScheduler()
        s.onFix(1_000, speed, 0.0)
        s.onFix(2_000, speed, 50_000.0) // teleport
        s.onFix(120_000, speed, 25.0) // two-minute gap
        assertEquals(25.0, s.riddenMeters, 1e-9)
    }

    @Test
    fun windowSitsAMinuteAheadAndEndsAtTheDestinationWhenClose() {
        val (start, end) = SuggestionWindow.choose(100_000.0, 10_000.0, speedMps = 25.0)!!
        assertEquals(11_500.0, start, 1e-9)
        assertEquals(36_500.0, end, 1e-9)
        val near = SuggestionWindow.choose(40_000.0, 10_000.0, speedMps = 40.0)!!
        assertEquals(12_000.0, near.first, 1e-9) // lead capped at 2 km
        assertEquals(40_000.0, near.second, 1e-9)
        assertEquals(11_000.0, SuggestionWindow.choose(100_000.0, 10_000.0, Double.NaN)!!.first, 1e-9)
        assertNull(SuggestionWindow.choose(15_000.0, 10_000.0, 25.0))
    }

    @Test
    fun divergenceLeadNeedsTimeToDecideButNotTooMuch() {
        assertFalse(SuggestionWindow.divergenceLeadOk(700.0, 10.0))
        assertTrue(SuggestionWindow.divergenceLeadOk(900.0, 10.0))
        // At 30 m/s the 45 s rule (1350 m) dominates the 800 m floor.
        assertFalse(SuggestionWindow.divergenceLeadOk(1_200.0, 30.0))
        assertTrue(SuggestionWindow.divergenceLeadOk(1_400.0, 30.0))
        assertFalse(SuggestionWindow.divergenceLeadOk(11_000.0, 30.0))
    }
}
