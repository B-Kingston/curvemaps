package com.curvemaps.app.media

import com.curvemaps.app.media.ReturnToRideWatch.Step
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ReturnToRideWatchTest {
    @Test fun returnsOnlyAfterLeavingAndObservedPlayback() {
        val watch = ReturnToRideWatch(startedAtMillis = 0)
        assertEquals(Step.WAIT, watch.step(250, rideOnTop = true, playing = false))
        assertEquals(Step.WAIT, watch.step(500, rideOnTop = false, playing = false))
        assertEquals(Step.RETURN, watch.step(750, rideOnTop = false, playing = true))
    }
    @Test fun playbackBeforeThePlayerScreenAppearsWaitsForTheLeave() {
        val watch = ReturnToRideWatch(startedAtMillis = 0)
        assertEquals(Step.WAIT, watch.step(250, rideOnTop = true, playing = true))
        assertEquals(Step.RETURN, watch.step(500, rideOnTop = false, playing = true))
    }
    @Test fun riderComingBackByThemselvesStopsTheWatch() {
        val watch = ReturnToRideWatch(startedAtMillis = 0)
        watch.step(250, rideOnTop = false, playing = false)
        assertEquals(Step.STOP, watch.step(500, rideOnTop = true, playing = true))
    }
    @Test fun unobservableSessionsNeverReturnOnATimer() {
        val watch = ReturnToRideWatch(startedAtMillis = 0)
        assertEquals(Step.STOP, watch.step(250, rideOnTop = false, playing = null))
    }
    @Test fun givesUpAfterTheWindowAndStaysBelowThePanelTimeout() {
        val watch = ReturnToRideWatch(startedAtMillis = 1_000)
        assertEquals(Step.WAIT, watch.step(1_000 + ReturnToRideWindowMillis - 1, rideOnTop = false, playing = false))
        assertEquals(Step.STOP, watch.step(1_000 + ReturnToRideWindowMillis, rideOnTop = false, playing = false))
        assert(ReturnToRideWindowMillis < MediaPanelTimeoutMillis)
    }
    @Test fun randomTracesReturnAtMostOnceAndOnlyWhenAllowed() {
        val random = Random(42)
        repeat(500) {
            val watch = ReturnToRideWatch(startedAtMillis = 0)
            var left = false
            var now = 0L
            while (true) {
                now += 250
                val onTop = random.nextInt(4) == 0
                val playing = if (random.nextInt(30) == 0) null else random.nextBoolean()
                if (!onTop) left = true
                val step = watch.step(now, onTop, playing)
                if (step == Step.WAIT) {
                    assert(now < ReturnToRideWindowMillis)
                    continue
                }
                if (step == Step.RETURN) {
                    assert(left && !onTop && playing == true) { "returned without leaving or playback" }
                }
                break
            }
        }
    }
}
