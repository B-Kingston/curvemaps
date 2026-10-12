package com.curvemaps.app.routing.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guidance session through a GPS gap: progress continues along the route
 * while fixes are missing, and the first good fix afterwards is found and
 * eased onto rather than jumped to.
 */
class GpsGapSessionTest {

    /**
     * Due-east 10 km track of 25 m segments modelled at 20 m/s, with a right
     * turn at 6 km. 25 m segments keep the ordinary 40-segment forward
     * window to 1 km, so blind stretches longer than that are exercised.
     */
    private fun track(): RouteTrack {
        val n = 401
        val turnVertex = 240
        return RouteTrack.fromPath(
            DoubleArray(n) { 0.0 },
            DoubleArray(n) { it * LON_STEP },
            listOf(0, 2, 4),
            listOf("origin", "turn street", "finish"),
            listOf(turnVertex, n - 1 - turnVertex, 1),
            listOf(turnVertex * segmentS, (n - 1 - turnVertex) * segmentS, 0.0),
        )
    }

    private val segmentM = GeoMath.haversineM(0.0, 0.0, 0.0, LON_STEP)

    /** Model seconds per segment: exactly 20 m/s. */
    private val segmentS = segmentM / 20.0

    private fun lonAt(offsetM: Double) = offsetM / segmentM * LON_STEP
    private fun offsetOf(lon: Double) = lon / LON_STEP * segmentM

    private fun fixAt(offsetM: Double, tMs: Long, speed: Double = 20.0, latOffset: Double = 0.0, accuracy: Double = 5.0) =
        GpsFix(latOffset, lonAt(offsetM), speedMps = speed, accuracyM = accuracy, timestampMs = tMs)

    private class Ride(val session: NavigationSession, val rebuilds: MutableList<RebuildRequest>)

    /** Rides 0 → [toM] at 20 m/s with one fix per second; the last fix lands at time [toM]/20 s. */
    private fun rideTo(toM: Double): Ride {
        val session = NavigationSession()
        val rebuilds = mutableListOf<RebuildRequest>()
        session.setRebuildListener { rebuilds += it }
        session.startRoute(track())
        session.beginFollowing()
        var offset = 0.0
        var t = 0L
        while (offset <= toM + 1e-6) {
            session.onFix(fixAt(offset, t))
            offset += 20.0
            t += 1_000
        }
        return Ride(session, rebuilds)
    }

    private fun lastFixMs(toM: Double) = (toM / 20.0 * 1000).toLong()

    @Test
    fun `a moving rider who loses GPS keeps progressing along the route`() {
        val ride = rideTo(1_000.0)
        val session = ride.session
        val atLoss = session.snapshot.value
        val t0 = lastFixMs(1_000.0)
        assertEquals(PositionSource.GPS, atLoss.positionSource)
        assertNull("fresh fixes are not estimated", session.onTick(t0 + 2_000))

        assertEquals(PositionSource.ESTIMATED, session.onTick(t0 + 4_000))
        val early = session.snapshot.value
        assertEquals(NavigationState.OnRoute, early.state)
        assertEquals(atLoss.remainingDistanceM - 80.0, early.remainingDistanceM, 2.0)
        assertEquals(1_080.0, offsetOf(early.lon), 2.0)
        assertEquals(20.0, early.speedMps, 0.5)
        assertEquals(90.0, early.bearingDeg, 0.5)

        session.onTick(t0 + 10_000)
        val later = session.snapshot.value
        assertEquals(atLoss.remainingDistanceM - 200.0, later.remainingDistanceM, 2.0)
        assertEquals(atLoss.turn!!.distanceM - 200.0, later.turn!!.distanceM, 2.0)
        assertTrue(later.remainingTimeS < atLoss.remainingTimeS)
        assertEquals("the matcher stays on the last real fix", 1_000.0, session.currentRouteCoveredM(), 1.0)
    }

    @Test
    fun `a guess never moves completion, pace delta or off-route state`() {
        val ride = rideTo(2_000.0)
        val session = ride.session
        val atLoss = session.snapshot.value
        val t0 = lastFixMs(2_000.0)
        var t = t0 + 500
        while (t < t0 + 170_000) {
            session.onTick(t)
            val snap = session.snapshot.value
            assertEquals(atLoss.completionPercent, snap.completionPercent)
            assertEquals(atLoss.paceDeltaS, snap.paceDeltaS, 1e-9)
            assertEquals(NavigationState.OnRoute, snap.state)
            t += 500
        }
        assertTrue(ride.rebuilds.isEmpty())
    }

    @Test
    fun `a stopped rider keeps the live position`() {
        val session = NavigationSession()
        session.startRoute(track())
        session.beginFollowing()
        for (i in 0..5) session.onFix(fixAt(500.0, i * 1_000L, speed = 0.0))
        assertNull(session.onTick(60_000))
        assertEquals(PositionSource.GPS, session.snapshot.value.positionSource)
        assertEquals(500.0, offsetOf(session.snapshot.value.lon), 1.0)
    }

    @Test
    fun `an estimate never finishes the ride and gives up advancing after the cap`() {
        val ride = rideTo(9_000.0)
        val session = ride.session
        val t0 = lastFixMs(9_000.0)
        assertEquals(PositionSource.ESTIMATED, session.onTick(t0 + 120_000))
        val held = session.snapshot.value
        assertEquals(NavigationState.OnRoute, held.state)
        assertEquals(50.0, held.remainingDistanceM, 1.0)

        assertEquals(PositionSource.LOST, session.onTick(t0 + 181_000))
        assertNull("a held estimate is published once", session.onTick(t0 + 185_000))
        assertEquals(PositionSource.LOST, session.snapshot.value.positionSource)
        assertEquals(NavigationState.OnRoute, session.snapshot.value.state)
    }

    @Test
    fun `GPS returning behind the estimate eases back instead of jumping`() {
        val ride = rideTo(1_000.0)
        val session = ride.session
        val t0 = lastFixMs(1_000.0)
        session.onTick(t0 + 30_000) // estimate: 1600 m
        val estimated = offsetOf(session.snapshot.value.lon)
        assertEquals(1_600.0, estimated, 2.0)

        // The rider actually slowed in the tunnel and is only at 1100 m.
        val recoveredAt = t0 + 30_000
        session.onFix(fixAt(1_100.0, recoveredAt), recoveredAt)
        val first = session.snapshot.value
        assertEquals(PositionSource.RECONCILING, first.positionSource)
        assertEquals("distances switch to the live fix at once", track().totalDistanceM - 1_100.0, first.remainingDistanceM, 2.0)
        assertEquals("the marker starts where the estimate was", estimated, offsetOf(first.lon), 2.0)
        assertEquals(1_100.0, session.currentRouteCoveredM(), 2.0)

        // Tick and keep riding at 20 m/s; record each displayed position.
        val displayed = mutableListOf(offsetOf(first.lon))
        var t = recoveredAt
        var live = 1_100.0
        while (t < recoveredAt + 4_000) {
            t += 250
            if ((t - recoveredAt) % 1_000 == 0L) {
                live += 20.0
                session.onFix(fixAt(live, t), t)
            } else {
                session.onTick(t)
            }
            displayed += offsetOf(session.snapshot.value.lon)
        }
        val steps = displayed.zipWithNext { a, b -> kotlin.math.abs(b - a) }
        assertTrue("largest step ${steps.max()} m of a 500 m correction", steps.max() < 150.0)
        assertEquals(PositionSource.GPS, session.snapshot.value.positionSource)
        assertEquals(live, offsetOf(session.snapshot.value.lon), 1.0)
        assertTrue(ride.rebuilds.isEmpty())
    }

    @Test
    fun `GPS returning far past the estimate is matched, not treated as off-route`() {
        val ride = rideTo(1_000.0)
        val session = ride.session
        val t0 = lastFixMs(1_000.0)
        session.onTick(t0 + 60_000) // estimate: 2200 m, 48 segments past the last fix
        assertEquals(2_200.0, offsetOf(session.snapshot.value.lon), 2.0)

        // The rider was quicker: 2600 m, beyond the ordinary forward window.
        session.onFix(fixAt(2_600.0, t0 + 60_000), t0 + 60_000)
        val snap = session.snapshot.value
        assertEquals(NavigationState.OnRoute, snap.state)
        assertEquals(PositionSource.RECONCILING, snap.positionSource)
        assertEquals(track().totalDistanceM - 2_600.0, snap.remainingDistanceM, 2.0)
        assertEquals("the marker eases forward from the estimate", 2_200.0, offsetOf(snap.lon), 2.0)
        assertTrue(ride.rebuilds.isEmpty())

        // Afterwards ordinary following resumes from the recovered position.
        session.onFix(fixAt(2_620.0, t0 + 61_000), t0 + 61_000)
        assertEquals(2_620.0, session.currentRouteCoveredM(), 2.0)
    }

    @Test
    fun `GPS returning past a turn moves the guidance on to the next maneuver`() {
        val ride = rideTo(5_800.0)
        val session = ride.session
        val t0 = lastFixMs(5_800.0)
        assertEquals("turn street", session.snapshot.value.turn!!.streetName)
        session.onTick(t0 + 20_000)
        session.onFix(fixAt(6_200.0, t0 + 20_000), t0 + 20_000)
        assertNull(session.snapshot.value.turn)
    }

    @Test
    fun `coarse fallback fixes do not end the estimate`() {
        val ride = rideTo(1_000.0)
        val session = ride.session
        val t0 = lastFixMs(1_000.0)
        session.onTick(t0 + 5_000)
        // A cell fix 400 m north, accurate to 500 m.
        session.onFix(fixAt(1_100.0, t0 + 5_500, latOffset = 0.0036, accuracy = 500.0), t0 + 5_500)
        assertEquals(PositionSource.ESTIMATED, session.snapshot.value.positionSource)
        assertEquals(PositionSource.ESTIMATED, session.onTick(t0 + 6_000))
        assertEquals(1_120.0, offsetOf(session.snapshot.value.lon), 2.0)
        assertTrue(ride.rebuilds.isEmpty())
    }

    @Test
    fun `GPS returning off the route glides to the rider and rebuilds from there`() {
        val ride = rideTo(1_000.0)
        val session = ride.session
        val t0 = lastFixMs(1_000.0)
        session.onTick(t0 + 30_000) // estimate: 1600 m
        // The rider left by a tunnel exit and is 500 m north of the route.
        val offLat = 0.0045
        var t = t0 + 30_000
        session.onFix(fixAt(1_500.0, t, latOffset = offLat), t)
        val first = session.snapshot.value
        assertEquals(PositionSource.RECONCILING, first.positionSource)
        assertEquals("the marker starts at the estimate", 0.0, first.lat, 1e-6)
        session.onTick(t + 1_000)
        val mid = session.snapshot.value.lat
        assertTrue("mid glide $mid", mid > 0.0 && mid < offLat)

        var step = 0
        while (ride.rebuilds.isEmpty() && step < 20) {
            t += 1_000
            step++
            session.onFix(fixAt(1_500.0 + step * 20.0, t, latOffset = offLat), t)
        }
        val request = ride.rebuilds.single()
        assertEquals("rebuild from the rider, not the pre-gap point", offLat, request.lat, 1e-6)
        // The rebuild can fire mid-glide; the marker keeps easing onto the
        // rider while it runs instead of freezing short of the rider.
        session.markRebuilding()
        repeat(5) {
            t += 1_000
            step++
            session.onFix(fixAt(1_500.0 + step * 20.0, t, latOffset = offLat), t)
        }
        assertEquals(NavigationState.Rebuilding, session.snapshot.value.state)
        assertEquals(offLat, session.snapshot.value.lat, 1e-9)
        assertEquals(1_500.0 + step * 20.0, offsetOf(session.snapshot.value.lon), 0.5)
    }

    @Test
    fun `a wrong turn rebuilds from the rider on the third moving miss, heading the rider's way`() {
        val ride = rideTo(1_000.0)
        val session = ride.session
        var t = lastFixMs(1_000.0)
        // The rider turns north off the eastbound route at 1000 m, 20 m per fix.
        // The fixes 20 m and 40 m out still match (55 m tolerance); 60 m misses.
        val metresNorth = mutableListOf<Double>()
        while (ride.rebuilds.isEmpty() && metresNorth.size < 20) {
            t += 1_000
            val north = (metresNorth.size + 1) * 20.0
            metresNorth += north
            session.onFix(fixAt(1_000.0, t, latOffset = north / GeoMath.haversineM(0.0, 0.0, 1.0, 0.0)), t)
        }
        assertEquals("third miss: 60, 80, 100 m", 100.0, metresNorth.last(), 0.0)
        val request = ride.rebuilds.single()
        assertEquals("rebuild from the rider, not the turn behind", 100.0, GeoMath.haversineM(0.0, 0.0, request.lat, 0.0), 0.5)
        assertEquals(1_000.0, offsetOf(request.lon), 0.5)
        assertEquals("leaving north, as the rider rides", 0.0, request.headingDeg, 1.0)
    }

    @Test
    fun `a new route or stop drops the estimate`() {
        val ride = rideTo(1_000.0)
        val session = ride.session
        val t0 = lastFixMs(1_000.0)
        assertEquals(PositionSource.ESTIMATED, session.onTick(t0 + 5_000))
        session.applyRebuiltRoute(track(), session.currentRouteCoveredM())
        assertEquals(PositionSource.GPS, session.snapshot.value.positionSource)
        assertNull(session.onTick(t0 + 10_000))

        session.onFix(fixAt(100.0, t0 + 11_000), t0 + 11_000)
        session.stop()
        assertNull(session.onTick(t0 + 30_000))
        assertEquals(PositionSource.GPS, session.snapshot.value.positionSource)
    }

    @Test
    fun `fix-only callers see unchanged behaviour`() {
        // Without ticks nothing is ever estimated: the pre-existing contract.
        val ride = rideTo(3_000.0)
        assertEquals(PositionSource.GPS, ride.session.snapshot.value.positionSource)
        assertEquals(3_000.0, offsetOf(ride.session.snapshot.value.lon), 1.0)
    }

    private companion object {
        const val LON_STEP = 0.000225
    }
}
