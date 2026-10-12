package com.curvemaps.app.routing.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the live pace delta: actual riding time minus the model time
 * budgeted for the distance covered, positive when slower than plan, EMA
 * smoothed, frozen while stationary, resilient to gaps.
 */
class PaceDeltaTest {

    /** Equal-length streets: 12 s per segment in town, then 4 s on a faster road. */
    private fun mixedSpeedTrack(): RouteTrack = RouteTrack.fromPath(
        latitudes = DoubleArray(21) { -27.0 - it * 0.0009 },
        longitudes = DoubleArray(21) { 153.0 },
        instructionSigns = listOf(0, 0, 4),
        instructionNames = listOf("Town", "Highway", "Finish"),
        instructionPointCounts = listOf(10, 10, 1),
        instructionTimesS = listOf(120.0, 40.0, 0.0),
    )

    private fun mixedFix(position: Double, timeMs: Long, speed: Double = 10.0): GpsFix =
        fix(-27.0 - position * 0.0009, speed, timeMs)

    // Independent instruction-time oracle, including fractional vertices.
    private fun mixedModelTimeS(position: Double): Double =
        if (position <= 10.0) position * 12.0 else 120.0 + (position - 10.0) * 4.0

    @Test
    fun mixedSpeedRouteStaysOnPlanAtEveryMeasuredPosition() {
        val session = NavigationSession()
        session.startRoute(mixedSpeedTrack())
        session.beginFollowing()

        // Half-segment fixes exercise interpolation inside both instructions.
        for (step in 0..38) {
            val position = step / 2.0
            val timeMs = 1_000L + (mixedModelTimeS(position) * 1_000).toLong()
            session.onFix(mixedFix(position, timeMs))
            if (position >= 2.0) {
                assertEquals("pace at position $position", 0.0, session.snapshot.value.paceDeltaS, 1e-6)
            }
        }
    }

    @Test
    fun sampleCrossingSpeedChangeUsesBothInstructionBudgets() {
        val session = NavigationSession(NavigationSession.Settings(deltaEmaAlpha = 1.0))
        session.startRoute(mixedSpeedTrack())
        session.beginFollowing()
        session.onFix(mixedFix(8.0, 1_000))
        session.onFix(mixedFix(9.5, 19_000)) // 18 s budget entirely on the town road.
        session.onFix(mixedFix(10.5, 29_000)) // 6 s town + 2 s highway; lose 2 s.

        assertEquals(2.0, session.snapshot.value.paceDeltaS, 1e-6)
    }

    @Test
    fun gainsAndLossesUseLocalBudgetsOnBothRoads() {
        for (timeFactor in listOf(0.5, 1.5)) {
            val session = NavigationSession(NavigationSession.Settings(deltaEmaAlpha = 1.0))
            session.startRoute(mixedSpeedTrack())
            session.beginFollowing()
            for (position in 0..19) {
                val plannedS = mixedModelTimeS(position.toDouble())
                val timeMs = 1_000L + (plannedS * timeFactor * 1_000).toLong()
                session.onFix(mixedFix(position.toDouble(), timeMs))
                if (position >= 2) {
                    assertEquals(
                        "factor $timeFactor at position $position",
                        plannedS * (timeFactor - 1.0), session.snapshot.value.paceDeltaS, 1e-6,
                    )
                }
            }
        }
    }

    @Test
    fun stopAndLongGapResumeAtTheCurrentLocalBudget() {
        val session = NavigationSession()
        session.startRoute(mixedSpeedTrack())
        session.beginFollowing()
        session.onFix(mixedFix(7.0, 1_000))
        session.onFix(mixedFix(8.0, 13_000))
        session.onFix(mixedFix(9.0, 25_000))
        assertEquals(0.0, session.snapshot.value.paceDeltaS, 1e-6)

        session.onFix(mixedFix(9.0, 85_000, speed = 0.0))
        session.onFix(mixedFix(9.5, 91_000))
        assertEquals(0.0, session.snapshot.value.paceDeltaS, 1e-6)

        session.onFix(mixedFix(11.0, 151_000)) // Missing interval spans the speed change.
        session.onFix(mixedFix(12.0, 155_000))
        assertEquals(0.0, session.snapshot.value.paceDeltaS, 1e-6)
    }

    @Test
    fun reroutePreservesLossAndUsesNewRouteLocalTimes() {
        val session = NavigationSession(NavigationSession.Settings(deltaEmaAlpha = 1.0))
        session.startRoute(track())
        session.beginFollowing()
        for (position in 0..3) {
            session.onFix(mixedFix(position.toDouble(), 1_000L + position * 12_000L))
        }
        val before = session.snapshot.value.paceDeltaS
        assertEquals(18.0, before, 1e-6)
        session.applyRebuiltRoute(mixedSpeedTrack(), session.currentRouteCoveredM())

        // First fix anchors the new clock at a nonzero offset, after routing latency.
        session.onFix(mixedFix(9.5, 100_000))
        session.onFix(mixedFix(10.5, 108_000))
        session.onFix(mixedFix(11.5, 112_000))
        assertEquals(before, session.snapshot.value.paceDeltaS, 1e-6)
    }

    @Test
    fun localTimeDeltaRetainsDefaultSmoothing() {
        val session = NavigationSession()
        session.startRoute(mixedSpeedTrack())
        session.beginFollowing()
        for (position in 0..2) {
            session.onFix(mixedFix(position.toDouble(), 1_000L + position * 13_000L))
        }
        assertEquals(2.0, session.snapshot.value.paceDeltaS, 1e-6)
        session.onFix(mixedFix(3.0, 40_000))
        assertEquals(2.25, session.snapshot.value.paceDeltaS, 1e-6)
    }

    /** Straight 2 km track, 21 vertices 100 m apart, model pace 100 m / 6 s. */
    private fun track(): RouteTrack {
        val n = 21
        val latitudes = DoubleArray(n) { -27.0 - it * 0.0009 }
        val longitudes = DoubleArray(n) { 153.0 }
        val signs = IntArray(n) { if (it == 0) 0 else 0 }.toList()
        val names = List(n) { "seg$it" }
        val counts = List(n) { if (it == 0) n - 1 else 0 }
        val times = List(n) { if (it == 0) (n - 1) * 6.0 else 0.0 }
        return RouteTrack.fromPath(latitudes, longitudes, signs, names, counts, times)
    }

    private fun fix(lat: Double, speed: Double, t: Long) =
        GpsFix(lat, 153.0, speedMps = speed, timestampMs = t)

    /** Feeds fixes riding exactly at model pace: delta must stay ~0. */
    @Test
    fun `riding at model pace keeps delta near zero`() {
        val session = NavigationSession()
        session.setRebuildListener { }
        session.startRoute(track())
        session.beginFollowing()
        // Model pace is 100 m per 6 s; lat step 0.0009 deg ~= 100 m.
        var t = 0L
        for (i in 0 until 40) {
            t += 6_000
            session.onFix(fix(-27.0 - i * 0.0009, speed = 100.0 / 6.0, t = t))
        }
        val delta = session.snapshot.value.paceDeltaS
        assertFalse(delta.isNaN())
        assertEquals(0.0, delta, 5.0)
    }

    /** Riding half model pace must produce a clearly positive delta. */
    @Test
    fun `riding slower than plan loses time`() {
        val session = NavigationSession()
        session.setRebuildListener { }
        session.startRoute(track())
        session.beginFollowing()
        var t = 0L
        for (i in 0 until 40) {
            t += 12_000 // double the budgeted time per 100 m
            session.onFix(fix(-27.0 - i * 0.0009, speed = 100.0 / 12.0, t = t))
        }
        val delta = session.snapshot.value.paceDeltaS
        assertFalse(delta.isNaN())
        assertTrue("expected lost time, was $delta", delta > 60.0)
    }

    /** Riding double model pace must produce a negative delta. */
    @Test
    fun `riding faster than plan gains time`() {
        val session = NavigationSession()
        session.setRebuildListener { }
        session.startRoute(track())
        session.beginFollowing()
        var t = 0L
        for (i in 0 until 40) {
            t += 3_000 // half the budgeted time per 100 m
            session.onFix(fix(-27.0 - i * 0.0009, speed = 100.0 / 3.0, t = t))
        }
        val delta = session.snapshot.value.paceDeltaS
        assertFalse(delta.isNaN())
        assertTrue("expected gained time, was $delta", delta < -30.0)
    }

    /** Stationary fixes freeze the delta instead of charging stop time. */
    @Test
    fun `stops do not charge lost time`() {
        val session = NavigationSession()
        session.setRebuildListener { }
        session.startRoute(track())
        session.beginFollowing()
        var t = 0L
        for (i in 0 until 30) {
            t += 6_000
            session.onFix(fix(-27.0 - i * 0.0009, speed = 100.0 / 6.0, t = t))
        }
        val before = session.snapshot.value.paceDeltaS
        assertFalse(before.isNaN())
        // Parked for 5 minutes: delta must not move.
        for (i in 0 until 50) {
            t += 6_000
            session.onFix(fix(-27.0 - 29 * 0.0009, speed = 0.0, t = t))
        }
        assertEquals(before, session.snapshot.value.paceDeltaS, 0.5)
    }

    /** A long gap (rebuild freeze, tunnel) must not corrupt the delta. */
    @Test
    fun `long gap resyncs without charging`() {
        val session = NavigationSession()
        session.setRebuildListener { }
        session.startRoute(track())
        session.beginFollowing()
        var t = 0L
        for (i in 0 until 30) {
            t += 6_000
            session.onFix(fix(-27.0 - i * 0.0009, speed = 100.0 / 6.0, t = t))
        }
        val before = session.snapshot.value.paceDeltaS
        t += 10 * 60_000 // ten-minute gap
        session.onFix(fix(-27.0 - 30 * 0.0009, speed = 100.0 / 6.0, t = t))
        assertEquals(before, session.snapshot.value.paceDeltaS, 0.5)
    }

    /** Fresh route start publishes NaN until enough distance accrues. */
    @Test
    fun `delta is NaN before minimum distance`() {
        val session = NavigationSession()
        session.setRebuildListener { }
        session.startRoute(track())
        session.beginFollowing()
        session.onFix(fix(-27.0, speed = 100.0 / 6.0, t = 1_000))
        session.onFix(fix(-27.0009, speed = 100.0 / 6.0, t = 7_000))
        assertTrue(session.snapshot.value.paceDeltaS.isNaN())
    }

    @Test
    fun `rebuild preserves measured pace but resyncs the fix clock`() {
        val session = NavigationSession()
        session.startRoute(track())
        session.beginFollowing()
        var time = 0L
        for (index in 0..5) {
            time += 12_000
            session.onFix(fix(-27.0 - index * 0.0009, speed = 100.0 / 12.0, t = time))
        }
        val before = session.snapshot.value.paceDeltaS
        assertFalse(before.isNaN())

        session.applyRebuiltRoute(track(), session.currentRouteCoveredM())

        assertEquals(before, session.snapshot.value.paceDeltaS, 0.0)
    }
}
