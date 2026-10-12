package com.curvemaps.app.routing.suggestion

import com.curvemaps.app.routing.RideStyle
import com.curvemaps.app.routing.navigation.GpsFix
import com.curvemaps.app.routing.navigation.NavigationSnapshot
import com.curvemaps.app.routing.navigation.NavigationState
import com.curvemaps.app.routing.navigation.RouteTrack
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.METRES_PER_DEGREE
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.join
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.straight
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.track
import com.curvemaps.app.routing.suggestion.SyntheticRoutes.twisty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole "ride it or tap" loop on synthetic roads: a straight 60 km
 * highway with a twisty ridge road that leaves it at 12 km and rejoins at
 * 27 km. The engine is driven fix by fix, as the GPS collector drives it.
 */
class RideSuggestionEngineTest {
    private val speed = 25.0
    private val baseline = track(straight(0.0, 60_000.0, speed))
    private val balanced = RideSuggestionInputs(enabled = true, style = RideStyle.BALANCED)
    private var now = 0L

    private fun replacementFrom(riderX: Double): RouteTrack {
        val vertices = join(
            straight(riderX, 12_000.0, speed, step = 50.0),
            twisty(12_000.0, 27_000.0, 20.0),
            straight(27_000.0, 60_000.0, speed),
        )
        return track(vertices, turnAtVertex = SyntheticRoutes.indexAtX(vertices, 12_000.0))
    }

    private fun snapshot(fix: GpsFix) = NavigationSnapshot(
        state = NavigationState.OnRoute,
        lat = fix.lat,
        lon = fix.lon,
        bearingDeg = 90.0,
        speedMps = fix.speedMps,
        speedLimitMps = Double.NaN,
        remainingDistanceM = 0.0,
        remainingTimeS = 0.0,
        completionPercent = 0,
        paceDeltaS = Double.NaN,
        turn = null,
    )

    /** One fix on the baseline at [x] metres. */
    private fun RideSuggestionEngine.baselineFix(
        x: Double,
        inputs: RideSuggestionInputs = balanced,
    ): RideSuggestionEngine.FixResult {
        now += 1_000
        val fix = SyntheticRoutes.fix(x, 0.0, speed, now)
        return onFix(fix, now, snapshot(fix), baseline, x, inputs)
    }

    /** Rides the baseline from [fromX] until the engine asks for a check. */
    private fun RideSuggestionEngine.rideUntilSearch(fromX: Double, inputs: RideSuggestionInputs = balanced): DiversionSearchRequest? {
        var x = fromX
        while (x < 20_000.0) {
            baselineFix(x, inputs).search?.let { return it }
            x += speed
        }
        return null
    }

    /** Rides to a check and answers it with the twisty proposal. */
    private fun RideSuggestionEngine.propose(): Pair<DiversionSearchRequest, List<RideSuggestionEngine.Action>> {
        val request = rideUntilSearch(0.0)!!
        val proposal = DiversionPlanner.proposal(
            id = request.proposalId,
            baseline = request.baseline,
            riderOffsetM = request.riderOffsetM,
            replacement = replacementFrom(request.riderOffsetM),
            speedMps = request.speedMps,
        )
        assertNotNull("the ridge road qualifies", proposal)
        return request to onSearchFinished(request, proposal, balanced)
    }

    @Test
    fun aQualifyingCheckIsSpokenOnceWithCostTurnAndChoice() {
        val engine = RideSuggestionEngine()
        val (request, actions) = engine.propose()
        assertEquals(5_000.0, request.riderOffsetM, speed)
        assertEquals(request.riderOffsetM + 1_500.0, request.windowStartM, 1e-6)
        val speech = (actions.single() as RideSuggestionEngine.Action.Speak).text
        assertTrue(speech, speech.startsWith("Twistier road ahead: adds "))
        assertTrue(speech, "turn left onto Ridge Road to take it, or carry on to stay on route." in speech)
        val state = engine.state.value as RideSuggestionState.Pending
        assertEquals("pending:${request.proposalId}", state.semanticsValue)
        assertTrue(engine.holdsRebuild)
    }

    @Test
    fun ridingTheScenicTurnAcceptsWithoutATap() {
        val engine = RideSuggestionEngine()
        engine.propose()
        val proposal = (engine.state.value as RideSuggestionState.Pending).proposal
        val replacement = proposal.replacement
        var accepted: RideSuggestionEngine.Action.Accept? = null
        // Ride along the replacement from where the rider is now (it starts
        // at the rider's position when the check ran).
        var offset = speed
        while (accepted == null && offset < replacement.totalDistanceM) {
            val (lat, lon) = replacement.positionAt(offset)
            now += 1_000
            val fix = SyntheticRoutes.fix(lon * METRES_PER_DEGREE, lat * METRES_PER_DEGREE, speed, now)
            val baselineOffset = (lon * METRES_PER_DEGREE).coerceAtMost(baseline.totalDistanceM)
            accepted = engine.onFix(fix, now, snapshot(fix), baseline, baselineOffset, balanced)
                .actions.filterIsInstance<RideSuggestionEngine.Action.Accept>().firstOrNull()
            offset += speed
        }
        accepted!!
        assertTrue(
            "accepted just after the fork",
            accepted.replacementOffsetM in proposal.span.divergeReplacementOffsetM..proposal.span.divergeReplacementOffsetM + 300.0,
        )
        assertEquals("accepted:${proposal.id}", engine.state.value.semanticsValue)
        assertTrue(!engine.holdsRebuild)
    }

    @Test
    fun carryingStraightOnExpiresItAndStartsTheCooldown() {
        val engine = RideSuggestionEngine()
        engine.propose()
        val id = (engine.state.value as RideSuggestionState.Pending).proposal.id
        var x = 5_025.0
        var spokeReminder = false
        while (engine.state.value is RideSuggestionState.Pending && x < 14_000.0) {
            val speech = engine.baselineFix(x).actions.filterIsInstance<RideSuggestionEngine.Action.Speak>()
            if (speech.any { it.text.startsWith("Scenic option: turn left onto Ridge Road now") }) {
                assertTrue("reminder within 200 m of the fork, at $x", x in 11_790.0..12_000.0)
                spokeReminder = true
            }
            x += speed
        }
        assertTrue(spokeReminder)
        assertEquals("expired:$id", engine.state.value.semanticsValue)
        assertTrue(x < 12_400.0)
        // Cooldown: the next 15 km of riding asks for nothing.
        var next: DiversionSearchRequest? = null
        while (next == null && x < 27_000.0) {
            next = engine.baselineFix(x).search
            x += speed
        }
        assertNull(next)
    }

    @Test
    fun tapTakeAndTapSkip() {
        val take = RideSuggestionEngine()
        take.propose()
        val actions = take.take()
        assertTrue(actions.first() is RideSuggestionEngine.Action.Accept)
        assertEquals("Taking the scenic route.", (actions.last() as RideSuggestionEngine.Action.Speak).text)

        val skip = RideSuggestionEngine()
        skip.propose()
        skip.skip(now)
        assertTrue(skip.state.value.semanticsValue.startsWith("skipped:"))
        assertTrue(skip.take().isEmpty())
    }

    @Test
    fun switchingOffMidProposalClearsItSilently() {
        val engine = RideSuggestionEngine()
        engine.propose()
        val result = engine.baselineFix(5_100.0, balanced.copy(enabled = false))
        assertTrue(result.actions.isEmpty())
        assertEquals(RideSuggestionState.None, engine.state.value)
        assertTrue(!engine.holdsRebuild)
    }

    @Test
    fun staleChecksAreDroppedAfterTheRouteChanges() {
        val engine = RideSuggestionEngine()
        val request = engine.rideUntilSearch(0.0)!!
        engine.onRouteReplaced()
        val proposal = DiversionPlanner.proposal(
            request.proposalId, request.baseline, request.riderOffsetM,
            replacementFrom(request.riderOffsetM), request.speedMps,
        )
        assertTrue(engine.onSearchFinished(request, proposal, balanced).isEmpty())
        assertEquals(RideSuggestionState.None, engine.state.value)
    }

    @Test
    fun fastestRidesAndGpxShapedRidesNeverAsk() {
        val fastest = RideSuggestionEngine()
        assertNull(fastest.rideUntilSearch(0.0, balanced.copy(style = RideStyle.FASTEST)))
        val shaped = RideSuggestionEngine()
        assertNull(shaped.rideUntilSearch(0.0, balanced.copy(shapingPointsAhead = true)))
    }

    @Test
    fun oneCheckAtATimeAndNothingWhileAProposalIsPending() {
        val engine = RideSuggestionEngine()
        val request = engine.rideUntilSearch(0.0)!!
        assertTrue(engine.isChecking)
        // In flight: no second request however far the rider goes.
        assertNull(engine.rideUntilSearch(5_100.0))
        engine.onSearchFailed(request)
        assertTrue(!engine.isChecking)
    }
}
