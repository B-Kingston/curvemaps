package com.curvemaps.app.routing.suggestion

import com.curvemaps.app.routing.navigation.NavigationSession
import com.curvemaps.app.routing.navigation.NavigationState
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.METRES_PER_DEGREE
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.join
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.straight
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.track
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.twisty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiversionAcceptanceTest {
    private val speed = 25.0
    private val baseline = track(straight(0.0, 60_000.0, speed))
    private val replacement = track(
        join(straight(5_000.0, 12_000.0, speed, step = 50.0), twisty(12_000.0, 27_000.0, 20.0), straight(27_000.0, 60_000.0, speed)),
    )
    private val proposal = DiversionPlanner.proposal(1, baseline, 5_000.0, replacement, speed)!!

    @Test
    fun noiseAtTheForkDoesNotDecideAnything() {
        val acceptance = DiversionAcceptance(baseline, proposal)
        var t = 0L
        // Riding up to the fork on the shared road: pending throughout.
        var x = 5_000.0
        while (x <= 12_040.0) {
            t += 1_000
            assertEquals(DiversionAcceptance.Outcome.PENDING, acceptance.onFix(SyntheticRoutes.fix(x, 0.0, speed, t)))
            x += speed
        }
        // One stray fix toward the ridge road, then back on the highway.
        t += 1_000
        assertEquals(DiversionAcceptance.Outcome.PENDING, acceptance.onFix(SyntheticRoutes.fix(12_100.0, 66.0, speed, t)))
        t += 1_000
        assertEquals(DiversionAcceptance.Outcome.PENDING, acceptance.onFix(SyntheticRoutes.fix(12_110.0, 0.0, speed, t)))
    }

    @Test
    fun leavingBothRoadsExpiresSoNormalReroutingCanTakeOver() {
        val acceptance = DiversionAcceptance(baseline, proposal)
        var outcome = DiversionAcceptance.Outcome.PENDING
        var fixes = 0
        while (outcome == DiversionAcceptance.Outcome.PENDING) {
            fixes++
            outcome = acceptance.onFix(SyntheticRoutes.fix(5_000.0 + fixes * 25.0, -500.0, speed, fixes * 1_000L))
        }
        assertEquals(DiversionAcceptance.Outcome.EXPIRED, outcome)
        assertEquals(DiversionAcceptance.LOST_FIXES, fixes)
    }

    @Test
    fun aPendingProposalHoldsTheOffRouteRebuildUntilItDecides() {
        val session = NavigationSession()
        session.startRoute(baseline)
        session.beginFollowing()
        var hold = true
        var rebuilds = 0
        session.setRebuildListener { rebuilds++ }
        session.setRebuildHold { hold }
        var t = 0L
        var x = 0.0
        while (x <= 12_000.0) {
            t += 1_000
            session.onFix(SyntheticRoutes.fix(x, 0.0, speed, t))
            x += speed
        }
        // Now follow the ridge road: far off the highway, many fixes.
        var offset = proposal.span.divergeReplacementOffsetM
        repeat(20) {
            val (lat, lon) = replacement.positionAt(offset)
            t += 1_000
            session.onFix(SyntheticRoutes.fix(lon * METRES_PER_DEGREE, lat * METRES_PER_DEGREE, speed, t))
            offset += speed
        }
        assertEquals("held while the suggestion decides", 0, rebuilds)
        assertNotEquals(NavigationState.NeedRebuild, session.snapshot.value.state)
        hold = false
        val (lat, lon) = replacement.positionAt(offset)
        session.onFix(SyntheticRoutes.fix(lon * METRES_PER_DEGREE, lat * METRES_PER_DEGREE, speed, t + 1_000))
        assertEquals("released: the next miss rebuilds", 1, rebuilds)
    }

    @Test
    fun acceptingKeepsCompletionMonotonicAndFollowsTheReplacementFromTheRider() {
        val session = NavigationSession()
        session.startRoute(baseline)
        session.beginFollowing()
        var t = 0L
        var x = 0.0
        while (x <= 12_000.0) {
            t += 1_000
            session.onFix(SyntheticRoutes.fix(x, 0.0, speed, t))
            x += speed
        }
        val before = session.snapshot.value.completionPercent
        // The replacement starts at 5 km; the rider is 7 km along it now.
        session.applySuggestedRoute(replacement, startOffsetM = 7_000.0)
        val applied = session.snapshot.value
        assertEquals(NavigationState.OnRoute, applied.state)
        assertTrue(applied.completionPercent >= before)
        assertEquals(replacement.remainingDistanceM(7_000.0), applied.remainingDistanceM, 1e-6)
        // The next fix on the ridge road matches the new route (no rebuild).
        var offset = 7_100.0
        repeat(5) {
            val (lat, lon) = replacement.positionAt(offset)
            t += 1_000
            session.onFix(SyntheticRoutes.fix(lon * METRES_PER_DEGREE, lat * METRES_PER_DEGREE, speed, t))
            offset += speed
        }
        val after = session.snapshot.value
        assertEquals(NavigationState.OnRoute, after.state)
        assertTrue(after.completionPercent >= before)
        assertEquals(replacement.remainingDistanceM(offset - speed), after.remainingDistanceM, 30.0)
    }
}
