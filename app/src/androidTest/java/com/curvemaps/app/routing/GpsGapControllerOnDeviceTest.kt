package com.curvemaps.app.routing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.curvemaps.app.GUIDANCE_TICK_MS
import com.curvemaps.app.NavigationController
import com.curvemaps.app.routing.navigation.GeoMath
import com.curvemaps.app.routing.navigation.GpsFix
import com.curvemaps.app.routing.navigation.GpsSignal
import com.curvemaps.app.routing.navigation.NavigationSnapshot
import com.curvemaps.app.routing.navigation.NavigationState
import com.curvemaps.app.routing.navigation.PositionSource
import com.curvemaps.app.routing.navigation.RouteTrack
import com.curvemaps.app.routing.navigation.RouteTrackFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The real controller's GPS-gap clock on a real graph route: its own
 * coroutine ticks on the main thread, the estimate carries progress and
 * reaches the voice hook, the debug gate holds it off, GPS recovery eases
 * the marker back, and END stops the clock. The HUD's "No GPS" signal
 * follows the fix itself: it also comes up for a stopped rider who is
 * never estimated. Time is an injected monotonic
 * clock so the run is fast and repeatable; the tick loop itself is real.
 */
@RunWith(AndroidJUnit4::class)
class GpsGapControllerOnDeviceTest {

    private lateinit var scope: CoroutineScope
    private lateinit var controller: NavigationController
    private lateinit var track: RouteTrack
    private val now = AtomicLong(1_000_000L)
    private val allowed = AtomicBoolean(true)
    private val estimated = Collections.synchronizedList(mutableListOf<NavigationSnapshot>())

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val router = GraphHopperRouter(context)
        val corridor = ROUTE_CORPUS.first { it.name == "Samford to southern Brisbane scenic suggestion corridor" }
        val path = router.route(corridor.from, corridor.to, RideStyle.FASTEST.level.toDouble()).routes.first()
        track = RouteTrackFactory.fromPath(path)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        controller = NavigationController(scope, clock = { now.get() })
        withContext(Dispatchers.Main) {
            controller.setEstimationHandlers(allowed = { allowed.get() }, onEstimated = { estimated += it })
            controller.start(path)
        }
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) { controller.stop() }
        scope.cancel()
    }

    private suspend fun fixAt(offsetM: Double, speedMps: Double = 15.0) {
        val (lat, lon) = track.positionAt(offsetM)
        withContext(Dispatchers.Main) {
            controller.onFix(
                GpsFix(lat, lon, speedMps = speedMps, accuracyM = 3.0, timestampMs = now.get()),
                nowMs = now.get(),
            )
        }
    }

    /** Rides 0 → [toM] at 15 m/s, one fix per (injected) second. */
    private suspend fun rideTo(toM: Double) {
        var offset = 0.0
        while (offset <= toM) {
            fixAt(offset)
            now.addAndGet(1_000)
            offset += 15.0
        }
    }

    private fun snapshot() = controller.snapshot.value

    private suspend fun awaitSource(source: PositionSource): NavigationSnapshot? =
        withTimeoutOrNull(GUIDANCE_TICK_MS * 6) {
            while (snapshot().positionSource != source) delay(50)
            snapshot()
        }

    private suspend fun awaitGpsLost(lost: Boolean): Boolean =
        withTimeoutOrNull(GUIDANCE_TICK_MS * 6) {
            while (controller.gpsSignalLost.value != lost) delay(50)
            true
        } ?: false

    private fun estimatedOffset(snap: NavigationSnapshot) = track.totalDistanceM - snap.remainingDistanceM

    @Test
    fun gpsLossIsEstimatedAlongTheRouteAndAnnounced() = runBlocking {
        rideTo(2_000.0)
        val atLoss = snapshot()
        assertEquals(PositionSource.GPS, atLoss.positionSource)

        now.addAndGet(20_000)
        val estimate = awaitSource(PositionSource.ESTIMATED)
        assertNotNull("no estimate within ${GUIDANCE_TICK_MS * 6} ms", estimate)
        estimate!!
        assertEquals(NavigationState.OnRoute, estimate.state)
        assertTrue("no GPS signal while estimating", controller.gpsSignalLost.value)
        val advanced = atLoss.remainingDistanceM - estimate.remainingDistanceM
        // 20 s at the rider's pace, bounded to 0.5x..1.6x of the model.
        assertTrue("estimate advanced $advanced m in 20 s", advanced in 100.0..900.0)
        assertEquals(atLoss.completionPercent, estimate.completionPercent)
        val onRoute = track.positionAt(estimatedOffset(estimate))
        assertTrue(GeoMath.haversineM(estimate.lat, estimate.lon, onRoute.first, onRoute.second) < 2.0)
        assertTrue("voice hook saw no estimate", estimated.isNotEmpty())
        assertTrue(estimated.all { it.positionSource == PositionSource.ESTIMATED })
    }

    @Test
    fun freshFallbackFixesHideTheBadgeWhileGuidanceKeepsEstimating() = runBlocking {
        rideTo(2_000.0)
        now.addAndGet(10_000)
        val estimate = awaitSource(PositionSource.ESTIMATED)!!
        assertTrue(awaitGpsLost(true))
        val (lat, lon) = track.positionAt(estimatedOffset(estimate))
        repeat(4) {
            now.addAndGet(1_000)
            withContext(Dispatchers.Main) {
                controller.onFix(
                    GpsFix(lat, lon, accuracyM = 500.0, timestampMs = now.get()),
                    nowMs = now.get(),
                )
            }
            assertEquals(PositionSource.ESTIMATED, snapshot().positionSource)
            assertFalse("fresh Android fixes must hide the badge", controller.gpsSignalLost.value)
        }
        now.addAndGet(GpsSignal.LOST_AFTER_MS - 1)
        withContext(Dispatchers.Main) { controller.tick() }
        assertFalse(controller.gpsSignalLost.value)
        now.incrementAndGet()
        withContext(Dispatchers.Main) { controller.tick() }
        assertTrue("the badge returns when fallback fixes stop", controller.gpsSignalLost.value)
    }

    @Test
    fun theDebugGateHoldsEstimationOff() = runBlocking {
        rideTo(1_000.0)
        allowed.set(false)
        now.addAndGet(30_000)
        delay(GUIDANCE_TICK_MS * 3)
        assertEquals(PositionSource.GPS, snapshot().positionSource)
        assertTrue(estimated.isEmpty())
        assertFalse("debug fixes own guidance: no badge", controller.gpsSignalLost.value)
        allowed.set(true)
        assertNotNull(awaitSource(PositionSource.ESTIMATED))
    }

    @Test
    fun recoveryEasesFromTheEstimateOntoTheLiveFix() = runBlocking {
        rideTo(2_000.0)
        now.addAndGet(30_000)
        val estimate = awaitSource(PositionSource.ESTIMATED)!!
        val estimatedAt = estimatedOffset(estimate)
        // The rider actually crawled through: well behind the estimate.
        val live = (estimatedAt - 250.0).coerceAtLeast(2_001.0)
        assertTrue(controller.gpsSignalLost.value)
        fixAt(live)
        assertFalse("the badge leaves with the first fix", controller.gpsSignalLost.value)
        val first = snapshot()
        assertEquals(PositionSource.RECONCILING, first.positionSource)
        assertEquals("guidance distances are live at once", track.totalDistanceM - live, first.remainingDistanceM, 5.0)
        val estimatePoint = track.positionAt(estimatedAt)
        assertTrue(
            "marker should start at the estimate",
            GeoMath.haversineM(first.lat, first.lon, estimatePoint.first, estimatePoint.second) < 10.0,
        )

        // Half a second later the ticker has moved the marker part-way.
        now.addAndGet(GUIDANCE_TICK_MS)
        delay(GUIDANCE_TICK_MS * 2)
        val mid = snapshot()
        val livePoint = track.positionAt(live)
        val fromEstimate = GeoMath.haversineM(mid.lat, mid.lon, estimatePoint.first, estimatePoint.second)
        val toLive = GeoMath.haversineM(mid.lat, mid.lon, livePoint.first, livePoint.second)
        assertEquals(PositionSource.RECONCILING, mid.positionSource)
        assertTrue("mid glide: $fromEstimate m from the estimate, $toLive m from live", fromEstimate > 1.0 && toLive > 1.0)

        var offset = live
        repeat(4) {
            now.addAndGet(1_000)
            offset += 15.0
            fixAt(offset)
        }
        val settled = snapshot()
        assertEquals(PositionSource.GPS, settled.positionSource)
        val settledPoint = track.positionAt(offset)
        assertTrue(GeoMath.haversineM(settled.lat, settled.lon, settledPoint.first, settledPoint.second) < 2.0)
    }

    @Test
    fun endingTheRideStopsTheClock() = runBlocking {
        rideTo(1_000.0)
        withContext(Dispatchers.Main) { controller.stop() }
        now.addAndGet(60_000)
        delay(GUIDANCE_TICK_MS * 3)
        assertEquals(NavigationState.Idle, snapshot().state)
        assertTrue(estimated.isEmpty())
        assertFalse(controller.gpsSignalLost.value)
    }

    @Test
    fun aStoppedRiderLosingGpsIsToldWithoutBeingEstimated() = runBlocking {
        rideTo(1_000.0)
        // Pull over: a few stationary fixes, then the signal goes.
        repeat(3) {
            fixAt(1_000.0, speedMps = 0.0)
            now.addAndGet(1_000)
        }
        assertFalse(controller.gpsSignalLost.value)
        now.addAndGet(5_000)
        assertTrue("no badge for a stopped rider without GPS", awaitGpsLost(true))
        assertEquals("a stopped rider is never estimated", PositionSource.GPS, snapshot().positionSource)
        assertTrue(estimated.isEmpty())
        fixAt(1_000.0, speedMps = 0.0)
        assertFalse(controller.gpsSignalLost.value)
    }

    @Test
    fun aRideStartedBeforeTheFirstFixSaysSo() = runBlocking {
        assertFalse(controller.gpsSignalLost.value)
        now.addAndGet(GpsSignal.LOST_AFTER_MS)
        assertTrue(awaitGpsLost(true))
        fixAt(0.0)
        assertFalse(controller.gpsSignalLost.value)
    }
}
