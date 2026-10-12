package com.curvemaps.app.routing.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GpsGapEstimationTest {

    /** Due-east track at the equator; segment i is driven at [speeds]`[i]` m/s by the model. */
    private fun eastTrack(speeds: List<Double>, lonStep: Double = 0.0009): RouteTrack {
        val n = speeds.size + 1
        val lat = DoubleArray(n) { 0.0 }
        val lon = DoubleArray(n) { it * lonStep }
        val dist = DoubleArray(n)
        val time = DoubleArray(n)
        for (v in 1 until n) {
            val len = GeoMath.haversineM(lat[v - 1], lon[v - 1], lat[v], lon[v])
            dist[v] = dist[v - 1] + len
            time[v] = time[v - 1] + len / speeds[v - 1]
        }
        return RouteTrack(lat, lon, dist, time, dist[n - 1], time[n - 1], emptyList())
    }

    private fun uniform(segments: Int, speed: Double) = eastTrack(List(segments) { speed })

    // --- RouteTrack helpers ------------------------------------------------

    @Test
    fun `offsetAtTimeS inverts timeAtOffsetS`() {
        val random = Random(7)
        val track = eastTrack(List(60) { 5.0 + random.nextDouble() * 30.0 })
        repeat(200) {
            val offset = random.nextDouble() * track.totalDistanceM
            assertEquals(offset, track.offsetAtTimeS(track.timeAtOffsetS(offset)), 0.01)
        }
        assertEquals(0.0, track.offsetAtTimeS(-5.0), 0.0)
        assertEquals(track.totalDistanceM, track.offsetAtTimeS(track.totalTimeS + 10.0), 0.0)
    }

    @Test
    fun `offsetAtTimeS never runs backwards across zero-time stretches`() {
        val n = 6
        val lat = DoubleArray(n) { 0.0 }
        val lon = DoubleArray(n) { it * 0.0009 }
        val dist = DoubleArray(n) { it * 100.0 }
        val time = doubleArrayOf(0.0, 5.0, 5.0, 5.0, 10.0, 15.0)
        val track = RouteTrack(lat, lon, dist, time, 500.0, 15.0, emptyList())
        var previous = 0.0
        var t = 0.0
        while (t <= 15.0) {
            val offset = track.offsetAtTimeS(t)
            assertTrue("offset went backwards at $t s", offset >= previous - 1e-9)
            previous = offset
            t += 0.25
        }
    }

    @Test
    fun `model speed and bearing are read from the route`() {
        val track = eastTrack(List(10) { 25.0 } + List(10) { 12.5 })
        assertEquals(25.0, track.modelSpeedMpsAround(400.0, 100.0), 0.01)
        assertEquals(12.5, track.modelSpeedMpsAround(1600.0, 100.0), 0.01)
        assertEquals(90.0, track.bearingAtDeg(500.0), 0.5)
        assertEquals(90.0, track.bearingAtDeg(track.totalDistanceM), 0.5)
    }

    @Test
    fun `bearing skips zero-length segments`() {
        val lat = doubleArrayOf(0.0, 0.0, 0.0, 0.0009)
        val lon = doubleArrayOf(0.0, 0.0009, 0.0009, 0.0009)
        val dist = doubleArrayOf(0.0, 100.0, 100.0, 200.0)
        val time = doubleArrayOf(0.0, 5.0, 5.0, 10.0)
        val track = RouteTrack(lat, lon, dist, time, 200.0, 10.0, emptyList())
        assertEquals(0.0, track.bearingAtDeg(100.0), 0.5)
    }

    // --- RouteProgressEstimator --------------------------------------------

    @Test
    fun `no estimate while fixes are fresh or before the first match`() {
        val track = uniform(50, 20.0)
        val estimator = RouteProgressEstimator()
        assertNull(estimator.estimate(track, 10_000))
        estimator.onMatched(track, 100.0, 20.0, 0)
        assertNull(estimator.estimate(track, 2_999))
        assertNotNull(estimator.estimate(track, 3_000))
    }

    @Test
    fun `a stopped rider is not estimated`() {
        val track = uniform(50, 20.0)
        val estimator = RouteProgressEstimator()
        estimator.onMatched(track, 100.0, 0.5, 0)
        assertNull(estimator.estimate(track, 60_000))
        assertEquals(100.0, estimator.extrapolate(track, 1_000, 1_500), 0.0)
    }

    @Test
    fun `advances along the route at the rider's speed`() {
        val track = uniform(50, 20.0)
        val estimator = RouteProgressEstimator()
        estimator.onMatched(track, 100.0, 20.0, 0)
        val estimate = estimator.estimate(track, 10_000)!!
        assertEquals(300.0, estimate.offsetM, 1.0)
        assertEquals(20.0, estimate.speedMps, 0.1)
        assertFalse(estimate.expired)
    }

    @Test
    fun `the rider's pace carries onto slower road ahead`() {
        // 1 km modelled at 25 m/s, then 1 km at 12.5 m/s; the rider does 90%.
        val track = eastTrack(List(10) { 25.0 } + List(10) { 12.5 })
        val estimator = RouteProgressEstimator()
        val start = 500.0
        estimator.onMatched(track, start, 22.5, 0)
        val fastLeft = track.cumulativeDistanceM[10] - start
        val fastSeconds = fastLeft / 22.5
        val seconds = fastSeconds + 10.0
        val estimate = estimator.estimate(track, (seconds * 1000).toLong())!!
        assertEquals(track.cumulativeDistanceM[10] + 112.5, estimate.offsetM, 3.0)
        assertEquals(11.25, estimate.speedMps, 0.5)
    }

    @Test
    fun `the pace ratio is bounded`() {
        val track = uniform(80, 20.0)
        val estimator = RouteProgressEstimator()
        estimator.onMatched(track, 0.0, 60.0, 0) // 3x the model: clamped to 1.6x
        assertEquals(320.0, estimator.estimate(track, 10_000)!!.offsetM, 1.0)
        estimator.clear()
        estimator.onMatched(track, 0.0, 4.0, 0) // 0.2x: clamped to 0.5x
        assertEquals(100.0, estimator.estimate(track, 10_000)!!.offsetM, 1.0)
    }

    @Test
    fun `without route times the estimate uses the rider's speed`() {
        val n = 11
        val lat = DoubleArray(n) { 0.0 }
        val lon = DoubleArray(n) { it * 0.0009 }
        val dist = DoubleArray(n) { it * 100.0 }
        val track = RouteTrack(lat, lon, dist, DoubleArray(n), 1000.0, 0.0, emptyList())
        val estimator = RouteProgressEstimator()
        estimator.onMatched(track, 100.0, 15.0, 0)
        assertEquals(250.0, estimator.estimate(track, 10_000)!!.offsetM, 0.5)
    }

    @Test
    fun `speed is measured from progress when fixes carry none`() {
        val track = uniform(50, 20.0)
        val estimator = RouteProgressEstimator()
        estimator.onMatched(track, 0.0, Double.NaN, 0)
        assertNull("no speed yet", estimator.estimate(track, 10_000))
        estimator.onMatched(track, 100.0, Double.NaN, 5_000)
        assertEquals(20.0, estimator.smoothedSpeedMps, 0.01)
        assertEquals(180.0, estimator.estimate(track, 9_000)!!.offsetM, 1.0)
    }

    @Test
    fun `the estimate stops short of the destination`() {
        val track = uniform(20, 20.0)
        val estimator = RouteProgressEstimator()
        estimator.onMatched(track, track.totalDistanceM - 200.0, 20.0, 0)
        val estimate = estimator.estimate(track, 120_000)!!
        assertEquals(track.totalDistanceM - 50.0, estimate.offsetM, 0.01)
    }

    @Test
    fun `the estimate holds once it is too old to trust`() {
        val track = uniform(400, 10.0)
        val estimator = RouteProgressEstimator()
        estimator.onMatched(track, 0.0, 10.0, 0)
        val atCap = estimator.estimate(track, 180_000)!!
        val past = estimator.estimate(track, 240_000)!!
        assertFalse(atCap.expired)
        assertTrue(past.expired)
        assertEquals(atCap.offsetM, past.offsetM, 0.0)
    }

    @Test
    fun `estimates are monotonic in time and never behind the anchor`() {
        val random = Random(11)
        repeat(20) {
            val track = eastTrack(List(40) { 5.0 + random.nextDouble() * 30.0 })
            val estimator = RouteProgressEstimator()
            val anchor = random.nextDouble() * track.totalDistanceM * 0.8
            estimator.onMatched(track, anchor, 2.0 + random.nextDouble() * 40.0, 0)
            var previous = anchor
            var t = 3_000L
            while (t < 200_000L) {
                val offset = estimator.estimate(track, t)!!.offsetM
                assertTrue(offset >= previous - 1e-9)
                assertTrue(offset <= maxOf(anchor, track.totalDistanceM - 50.0) + 1e-9)
                previous = offset
                t += 700L
            }
        }
    }

    // --- RecoveryGlide ------------------------------------------------------

    @Test
    fun `ease starts and lands flat and never overshoots`() {
        assertEquals(0.0, RecoveryGlide.ease(0.0), 0.0)
        assertEquals(1.0, RecoveryGlide.ease(1.0), 0.0)
        assertEquals(0.5, RecoveryGlide.ease(0.5), 1e-12)
        var previous = 0.0
        for (i in 1..100) {
            val value = RecoveryGlide.ease(i / 100.0)
            assertTrue(value >= previous && value <= 1.0)
            previous = value
        }
        // Zero slope at both ends: the first and last 1% move under 0.04%.
        assertTrue(RecoveryGlide.ease(0.01) < 0.0004)
        assertTrue(1.0 - RecoveryGlide.ease(0.99) < 0.0004)
    }

    @Test
    fun `an along-route glide shrinks the correction to zero within its bounds`() {
        val glide = RecoveryGlide()
        glide.beginAlongRoute(gapM = 400.0, atMs = 1_000)
        assertTrue(glide.isActive(1_000))
        assertEquals(400.0, glide.correctionM(1_000), 1e-9)
        var previous = 400.0
        var t = 1_000L
        while (glide.isActive(t)) {
            val correction = glide.correctionM(t)
            assertTrue(correction <= previous + 1e-9 && correction >= 0.0)
            previous = correction
            t += 50
        }
        val duration = t - 1_000L
        assertTrue("glide took $duration ms", duration in 1_200L..3_050L)
        assertEquals(0.0, glide.correctionM(t), 0.0)
    }

    @Test
    fun `glide duration grows with the correction and is capped`() {
        fun durationFor(gap: Double): Long {
            val glide = RecoveryGlide()
            glide.beginAlongRoute(gap, 0)
            var t = 0L
            while (glide.isActive(t)) t += 10
            return t
        }
        val small = durationFor(-10.0)
        val medium = durationFor(250.0)
        val huge = durationFor(5_000.0)
        assertTrue(small in 1_200L..1_300L)
        assertTrue(medium in 2_150L..2_250L)
        assertTrue(huge in 3_000L..3_010L)
    }

    @Test
    fun `tiny disagreements are applied without a glide`() {
        val glide = RecoveryGlide()
        glide.beginAlongRoute(1.0, 0)
        assertFalse(glide.isActive(0))
        glide.beginAlongRoute(Double.NaN, 0)
        assertFalse(glide.isActive(0))
    }

    @Test
    fun `an off-route glide blends from the estimate to the live fix`() {
        val glide = RecoveryGlide()
        glide.beginOffRoute(0.0, 0.0, distanceM = 300.0, atMs = 0)
        assertTrue(glide.isOffRoute)
        val (startLat, _) = glide.blend(0.01, 0.0, 0)
        assertEquals(0.0, startLat, 1e-12)
        val (midLat, _) = glide.blend(0.01, 0.0, 1_200)
        assertTrue(midLat > 0.0 && midLat < 0.01)
        assertFalse(glide.isActive(10_000))
        assertEquals(0.01, glide.blend(0.01, 0.0, 10_000).first, 0.0)
    }

    // --- PolylineMatcher.relocate ------------------------------------------

    private fun fixAt(lat: Double, lon: Double) = GpsFix(lat, lon, speedMps = 20.0, accuracyM = 5.0, timestampMs = 0)

    @Test
    fun `relocation reaches past the ordinary forward window`() {
        // 25 m segments: the 40-segment window covers only 1 km.
        val track = eastTrack(List(200) { 20.0 }, lonStep = 0.000225)
        val target = 3_000.0
        val (lat, lon) = track.positionAt(target)
        assertNull(PolylineMatcher(track).project(fixAt(lat, lon), 40, 60.0))
        val matcher = PolylineMatcher(track)
        val offset = matcher.relocate(fixAt(lat, lon), toOffsetM = 3_500.0, expectedOffsetM = 2_500.0, maxSnapM = 60.0)
        assertEquals(target, offset!!, 1.0)
        assertEquals(target, matcher.currentOffsetM, 1.0)
    }

    @Test
    fun `relocation prefers the leg where the rider should be`() {
        // Out 1 km east, a 30 m jog north, then 1 km back west: a hairpin.
        val lats = mutableListOf<Double>()
        val lons = mutableListOf<Double>()
        for (i in 0..10) { lats += 0.0; lons += i * 0.0009 }
        for (i in 10 downTo 0) { lats += 0.00027; lons += i * 0.0009 }
        val n = lats.size
        val dist = DoubleArray(n)
        for (v in 1 until n) dist[v] = dist[v - 1] + GeoMath.haversineM(lats[v - 1], lons[v - 1], lats[v], lons[v])
        val time = DoubleArray(n) { dist[it] / 20.0 }
        val track = RouteTrack(lats.toDoubleArray(), lons.toDoubleArray(), dist, time, dist[n - 1], time[n - 1], emptyList())
        // 5 m north of the outbound leg at 500 m; 25 m from the return leg.
        val fix = fixAt(0.000045, 5 * 0.0009)
        val outbound = PolylineMatcher(track).relocate(fix, track.totalDistanceM, expectedOffsetM = 520.0, maxSnapM = 60.0)!!
        assertEquals(500.0, outbound, 5.0)
        val back = PolylineMatcher(track).relocate(fix, track.totalDistanceM, expectedOffsetM = 1_500.0, maxSnapM = 60.0)!!
        assertTrue("picked $back", back > 1_000.0)
    }

    @Test
    fun `relocation never searches behind the rider and reports misses`() {
        val track = uniform(30, 20.0)
        val matcher = PolylineMatcher(track)
        matcher.seek(2_000.0)
        val (lat, lon) = track.positionAt(500.0)
        assertNull(matcher.relocate(fixAt(lat, lon), 3_000.0, 2_200.0, 60.0))
        assertTrue(matcher.lastMissM > 60.0)
        assertEquals(2_000.0, matcher.currentOffsetM, 0.0)
    }

    @Test
    fun `the gps signal is lost after the quiet period whatever guidance does`() {
        val lastFix = 10_000L
        assertFalse(GpsSignal.isLost(lastFix + 2_999, lastFix))
        // A stopped rider is never estimated, but still has no fix.
        assertTrue(GpsSignal.isLost(lastFix + GpsSignal.LOST_AFTER_MS, lastFix))
        assertFalse(GpsSignal.isLost(lastFix + 100, lastFix))
    }

}
