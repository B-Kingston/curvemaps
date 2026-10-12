package com.curvemaps.app

import com.curvemaps.app.routing.DistinctRideStyles
import com.curvemaps.app.routing.RideStyle
import com.curvemaps.app.routing.RideStyleSearch
import com.curvemaps.app.routing.RoundTripLength
import com.curvemaps.app.routing.RoundTrips
import com.curvemaps.app.routing.RouteResult
import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.graphhopper.util.details.PathDetail
import com.graphhopper.util.shapes.GHPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
class RideStyleOptionsTest {

    @Test
    fun oneSearchSettlesEveryStop() = runTest {
        val calls = Collections.synchronizedList(mutableListOf<RideStyle>())
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val options = RideStyleOptions(scope, StandardTestDispatcher(testScheduler)) { _, style, _ ->
            calls += style
            result(style.ordinal * 100)
        }
        val twisty = scope.async { options.route(params(RideStyle.TWISTY)) }
        advanceUntilIdle()
        assertEquals(RideStyle.entries.toSet(), calls.toSet())
        assertEquals(RideStyle.entries.size, calls.size)
        assertEquals(420.0, twisty.await().routes.single().distance, 0.0)
        assertEquals(StyleSearchProgress(RidePlanKey.of(params(RideStyle.TWISTY)), 7), options.progress.value)
        assertEquals(420.0, options.readyOption(params(RideStyle.TWISTY))!!.routes.single().distance, 0.0)
        assertNull(options.readyOption(params(RideStyle.TWISTY).copy(avoidMotorways = true)))
        options.enabled = false
        assertNull(options.readyOption(params(RideStyle.TWISTY)))
        options.close()
    }

    @Test
    fun aStopIsAnsweredAsSoonAsItSettlesWhileHigherStopsKeepSearching() {
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        val dispatcher = pool.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val options = RideStyleOptions(scope, dispatcher) { _, style, _ ->
                if (style == RideStyle.MAX_TWISTY) release.await(10, TimeUnit.SECONDS)
                result(style.ordinal * 100)
            }
            val fastest = runBlocking { withTimeout(5_000) { options.route(params(RideStyle.FASTEST)) } }
            assertEquals(20.0, fastest.routes.single().distance, 0.0)
            // Max twisty is still searching, so the wave is not fully grown.
            assertTrue(options.progress.value!!.resolved < RideStyle.entries.size)
            release.countDown()
            val maxTwisty = runBlocking { withTimeout(5_000) { options.route(params(RideStyle.MAX_TWISTY)) } }
            assertEquals(620.0, maxTwisty.routes.single().distance, 0.0)
            assertEquals(RideStyle.entries.size, options.progress.value!!.resolved)
            options.close()
        } finally {
            release.countDown()
            scope.cancel()
            pool.shutdownNow()
        }
    }

    @Test
    fun slidingAfterTheSearchComputesNothingNew() = runTest {
        var calls = 0
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val options = RideStyleOptions(scope, StandardTestDispatcher(testScheduler)) { _, style, _ ->
            calls++
            result(style.ordinal * 100)
        }
        // Two requests for one plan share one search.
        scope.async { options.route(params(RideStyle.FASTEST)) }
        scope.async { options.route(params(RideStyle.CURVY)) }
        advanceUntilIdle()
        assertEquals(RideStyle.entries.size, calls)
        val maxTwisty = scope.async { options.route(params(RideStyle.MAX_TWISTY)) }
        advanceUntilIdle()
        assertEquals(RideStyle.entries.size, calls)
        assertEquals(620.0, maxTwisty.await().routes.single().distance, 0.0)
        options.close()
    }

    @Test
    fun progressCountsSettledStopsAndANewPlanStartsAgain() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val options = RideStyleOptions(scope, StandardTestDispatcher(testScheduler)) { _, style, _ ->
            result(style.ordinal * 100)
        }
        scope.async { options.route(params(RideStyle.FASTEST)) }
        advanceUntilIdle()
        assertEquals(7, options.progress.value?.resolved)
        val moved = params(RideStyle.FASTEST).copy(to = GHPoint(-26.0, 152.5))
        // The old plan's count is gone the moment a new plan starts.
        val pending = scope.async { options.route(moved) }
        testScheduler.runCurrent()
        val restarted = options.progress.value
        assertEquals(RidePlanKey.of(moved), restarted?.key)
        advanceUntilIdle()
        assertEquals(1, pending.await().routes.size)
        assertEquals(StyleSearchProgress(RidePlanKey.of(moved), 7), options.progress.value)
        options.close()
    }

    @Test
    fun aNewPlanCancelsTheOldSearch() = runTest {
        val started = mutableListOf<Boolean>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val options = RideStyleOptions(scope, StandardTestDispatcher(testScheduler), parallelism = 1) { params, style, _ ->
            started += params.avoidMotorways
            result(style.ordinal * 100)
        }
        val old = scope.async { runCatching { options.route(params(RideStyle.FASTEST)) } }
        val toggled = params(RideStyle.FASTEST).copy(avoidMotorways = true)
        val fresh = scope.async { options.route(toggled) }
        advanceUntilIdle()
        assertTrue(old.await().isFailure)
        assertEquals(1, fresh.await().routes.size)
        // The old plan stopped instead of finishing all seven stops.
        assertTrue(started.count { !it } < RideStyle.entries.size)
        assertEquals(RideStyle.entries.size, started.count { it })
        assertEquals(RidePlanKey.of(toggled), options.progress.value?.key)
        options.close()
    }

    @Test
    fun aRideFactorChangeIsANewPlanAndReachesEveryStop() = runTest {
        val seen = mutableListOf<com.curvemaps.app.routing.RideFactors>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val options = RideStyleOptions(scope, StandardTestDispatcher(testScheduler), parallelism = 2) { params, style, _ ->
            synchronized(seen) { seen += params.factors }
            result(style.ordinal * 100)
        }
        val plain = params(RideStyle.FASTEST)
        options.route(plain)
        advanceUntilIdle()
        val factors = com.curvemaps.app.routing.RideFactors(elevation = true, stopsAndTowns = true)
        val withFactors = plain.copy(factors = factors)
        assertNotEquals(RidePlanKey.of(plain), RidePlanKey.of(withFactors))
        // The finished plain plan must not answer for the factor plan.
        assertNull(options.readyOption(withFactors))
        options.route(withFactors)
        advanceUntilIdle()
        assertEquals(RideStyle.entries.size, seen.count { it == factors })
        assertEquals(RidePlanKey.of(withFactors), options.progress.value?.key)
        options.close()
    }

    @Test
    fun ridingComputesOnlyTheRequestedStop() = runTest {
        val calls = mutableListOf<RideStyle>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val options = RideStyleOptions(scope, StandardTestDispatcher(testScheduler)) { _, style, search ->
            assertNull(search.curveWeight)
            assertTrue(search.penalizedEdges.isEmpty())
            assertEquals(0.0, search.roadPenalty, 0.0)
            calls += style
            result(style.ordinal * 100)
        }
        options.enabled = false
        scope.async { options.route(params(RideStyle.BALANCED)) }
        advanceUntilIdle()
        assertEquals(listOf(RideStyle.BALANCED), calls)
        assertNull(options.progress.value)
        options.close()
    }

    @Test
    fun aFailedPlanIsRetriedByTheNextRequest() = runTest {
        var failTwisty = true
        var calls = 0
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val options = RideStyleOptions(scope, StandardTestDispatcher(testScheduler)) { _, style, _ ->
            calls++
            if (style == RideStyle.TWISTY && failTwisty) throw IllegalStateException("No route was found")
            result(style.ordinal * 100)
        }
        val first = scope.async { runCatching { options.route(params(RideStyle.FASTEST)) } }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)
        failTwisty = false
        val before = calls
        val retry = scope.async { options.route(params(RideStyle.FASTEST)) }
        advanceUntilIdle()
        assertEquals(1, retry.await().routes.size)
        assertEquals(RideStyle.entries.size, calls - before)
        options.close()
    }

    @Test
    fun aRepeatedStopSearchesAgainWithADifferentSearch() = runTest {
        val searches = mutableListOf<Pair<RideStyle, RideStyleSearch>>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val options = RideStyleOptions(scope, StandardTestDispatcher(testScheduler)) { _, style, search ->
            if (search.curveWeight != null || search.penalizedEdges.isNotEmpty()) {
                searches += style to search
                // A ride of its own, as long as the one it repeats.
                RouteResult(listOf(path(5_000, distance = 20 * EDGE_METRES)))
            } else {
                // Mostly fast's own optimum is Fastest's ride.
                result(if (style == RideStyle.MOSTLY_FAST) 0 else style.ordinal * 100)
            }
        }
        val mostlyFast = scope.async { options.route(params(RideStyle.MOSTLY_FAST)) }
        advanceUntilIdle()
        val shown = mostlyFast.await()
        assertEquals(5_000, shown.routes.single().pathDetails.getValue("edge_id").first().value)
        // Only Mostly fast repeated a lower stop, so only it searched again.
        assertTrue(searches.isNotEmpty())
        assertTrue(searches.all { it.first == RideStyle.MOSTLY_FAST })
        options.close()
    }

    @Test
    fun aStopWithNoDistinctRideShowsTheStopBelowAndTheHigherStopsStillSettle() = runTest {
        val settled = mutableMapOf<RideStyle, DistinctRideStyles.Option>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val options = RideStyleOptions(
            scope,
            StandardTestDispatcher(testScheduler),
            onSettled = { style, option -> settled[style] = option },
        ) { _, style, search ->
            val reSearch = search.curveWeight != null || search.penalizedEdges.isNotEmpty()
            when {
                // Every further search keeps repeating Fastest's ride.
                reSearch || style == RideStyle.MOSTLY_FAST -> result(0)
                else -> result(style.ordinal * 100)
            }
        }
        val mostlyFast = scope.async { options.route(params(RideStyle.MOSTLY_FAST)) }
        val maxTwisty = scope.async { options.route(params(RideStyle.MAX_TWISTY)) }
        advanceUntilIdle()
        // The exhausted stop shows Fastest's ride rather than an error ...
        val shown = mostlyFast.await()
        assertEquals(1, shown.routes.size)
        assertEquals(0, shown.routes.single().pathDetails.getValue("edge_id").first().value)
        assertEquals(RideStyle.FASTEST, settled.getValue(RideStyle.MOSTLY_FAST).repeats)
        // ... and the plan goes on: the stops above it get their own rides.
        assertEquals(600, maxTwisty.await().routes.single().pathDetails.getValue("edge_id").first().value)
        assertEquals(RideStyle.entries.size, settled.size)
        assertEquals(listOf(RideStyle.MOSTLY_FAST), settled.filterValues { it.repeats != null }.keys.toList())
        assertEquals(StyleSearchProgress(RidePlanKey.of(params(RideStyle.FASTEST)), 7), options.progress.value)
        // A repeat is a settled stop: the slider installs it at once.
        assertEquals(shown.routes, options.readyOption(params(RideStyle.MOSTLY_FAST))?.routes)
        options.close()
    }

    @Test
    fun aSearchThatFailsOutrightStillFailsThePlanAndIsRetried() = runTest {
        var routes = false
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val options = RideStyleOptions(scope, StandardTestDispatcher(testScheduler)) { _, style, _ ->
            if (style == RideStyle.BALANCED && !routes) error("No route was found")
            result(style.ordinal * 100)
        }
        val first = scope.async { runCatching { options.route(params(RideStyle.BALANCED)) } }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)
        routes = true
        val retry = scope.async { options.route(params(RideStyle.BALANCED)) }
        advanceUntilIdle()
        assertEquals(200, retry.await().routes.single().pathDetails.getValue("edge_id").first().value)
        options.close()
    }

    @Test
    fun lookingAroundReachesTheRouterWithItsViaButNotForAPlanWithShapingPoints() = runTest {
        val vias = Collections.synchronizedList(mutableListOf<Pair<RideStyle, GHPoint>>())
        val settled = mutableMapOf<RideStyle, DistinctRideStyles.Option>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val options = RideStyleOptions(
            scope,
            StandardTestDispatcher(testScheduler),
            onSettled = { style, option -> settled[style] = option },
        ) { _, style, search ->
            val via = search.via
            when {
                via != null -> {
                    vias += style to via
                    RouteResult(listOf(path(5_000, distance = 20 * EDGE_METRES)))
                }
                // Curvier weights and every penalty keep Fastest's ride.
                search.curveWeight != null || search.penalizedEdges.isNotEmpty() || style == RideStyle.MOSTLY_FAST -> result(0)
                else -> result(style.ordinal * 100)
            }
        }
        val mostlyFast = scope.async { options.route(params(RideStyle.MOSTLY_FAST)) }
        advanceUntilIdle()
        assertEquals(5_000, mostlyFast.await().routes.single().pathDetails.getValue("edge_id").first().value)
        val option = settled.getValue(RideStyle.MOSTLY_FAST)
        assertEquals(DistinctRideStyles.detourRings(params(RideStyle.MOSTLY_FAST).from, params(RideStyle.MOSTLY_FAST).to).first().first(), option.via)
        assertTrue(vias.all { it.first == RideStyle.MOSTLY_FAST })
        options.close()

        // A plan with its own shaping points keeps its legs: no via searches.
        vias.clear()
        val shaped = params(RideStyle.MOSTLY_FAST).copy(viaPoints = listOf(GHPoint(-27.5, 152.5)))
        val options2 = RideStyleOptions(scope, StandardTestDispatcher(testScheduler)) { _, style, search ->
            val via = search.via
            if (via != null) vias += style to via
            if (search.curveWeight != null || search.penalizedEdges.isNotEmpty() || style == RideStyle.MOSTLY_FAST) result(0) else result(style.ordinal * 100)
        }
        val repeated = scope.async { options2.route(shaped) }
        advanceUntilIdle()
        assertEquals(0, repeated.await().routes.single().pathDetails.getValue("edge_id").first().value)
        assertTrue(vias.isEmpty())
        options2.close()
    }

    @Test
    fun aRoundTripSettlesEveryStopOnALoopOfItsOwn() = runTest {
        val loops = Collections.synchronizedList(mutableListOf<RoundTrips.Shape>())
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val options = RideStyleOptions(scope, StandardTestDispatcher(testScheduler)) { params, _, search ->
            val loop = checkNotNull(search.loop) { "A round trip only asks for loops" }
            assertEquals(params.from, params.to)
            assertNull(search.via)
            loops += loop
            loopResult(loop)
        }
        val trip = roundTrip(RideStyle.TWISTY)
        val twisty = scope.async { options.route(trip) }
        advanceUntilIdle()
        assertEquals(1, twisty.await().routes.size)
        assertEquals(StyleSearchProgress(RidePlanKey.of(trip), 7), options.progress.value)
        val shown = RideStyle.entries.map { options.readyOption(roundTrip(it))!!.routes.single() }
        assertEquals(RideStyle.entries.size, shown.map { it.pathDetails.getValue("edge_id").first().value }.toSet().size)
        assertTrue(loops.size >= RideStyle.entries.size)
        // Another length, or the same place as an A-to-B plan, is another plan.
        assertNull(options.readyOption(trip.copy(roundTrip = RoundTripLength.Distance(80))))
        assertNull(options.readyOption(trip.copy(roundTrip = null)))
        assertNotEquals(RidePlanKey.of(trip), RidePlanKey.of(trip.copy(roundTrip = RoundTripLength.Time(60))))
        assertNotEquals(RidePlanKey.of(trip), RidePlanKey.of(trip.copy(passThrough = true)))
        options.close()
    }

    @Test
    fun aRoundTripIsAlwaysSearchedAsLoopsEvenWithTheOtherStylesOff() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val options = RideStyleOptions(scope, StandardTestDispatcher(testScheduler)) { _, _, search ->
            loopResult(checkNotNull(search.loop) { "A round trip has no A-to-B optimum" })
        }
        options.enabled = false
        val fastest = scope.async { options.route(roundTrip(RideStyle.FASTEST)) }
        advanceUntilIdle()
        assertEquals(1, fastest.await().routes.size)
        options.close()
    }

    @Test
    fun aRoundTripWithNoLoopAnywhereFailsWithItsOwnMessage() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val options = RideStyleOptions(scope, StandardTestDispatcher(testScheduler)) { _, _, _ ->
            throw IllegalStateException("No through road near the via point")
        }
        val failed = scope.async { runCatching { options.route(roundTrip(RideStyle.CURVY)) } }
        advanceUntilIdle()
        assertEquals(RoundTrips.NO_ROUND_TRIP_MESSAGE, failed.await().exceptionOrNull()?.message)
        options.close()
    }

    private fun roundTrip(style: RideStyle): RouteParams {
        val start = GHPoint(-27.3727, 152.8864)
        return RouteParams(
            from = start,
            to = start,
            complexity = style.level.toDouble(),
            blockUnpaved = false,
            roundTrip = RoundTripLength.Distance(50),
        )
    }

    /** A 50 km loop whose roads are its direction's own. */
    private fun loopResult(loop: RoundTrips.Shape) =
        RouteResult(listOf(path(10_000 + (loop.bearing / 11.25).roundToInt() * 100, distance = 50_000.0)))

    private fun params(style: RideStyle) = RouteParams(
        from = GHPoint(-27.0, 153.0),
        to = GHPoint(-28.0, 152.0),
        complexity = style.level.toDouble(),
        blockUnpaved = false,
    )

    private fun result(firstEdge: Int) = RouteResult(listOf(path(firstEdge)))

    /** Twenty ~1.1 km edges starting at [firstEdge]; distance encodes the start. */
    private fun path(firstEdge: Int, distance: Double = (firstEdge + 20) * EDGE_METRES): ResponsePath {
        val ids = (firstEdge until firstEdge + 20).toList()
        val points = PointList().apply { (0..ids.size).forEach { add(it * 0.01, 0.0) } }
        val details = ids.mapIndexed { index, id ->
            PathDetail(id).apply {
                first = index
                last = index + 1
            }
        }
        return ResponsePath()
            .setPoints(points)
            .setDistance(distance)
            .setTime(60_000L)
            .apply { addPathDetails(mapOf("edge_id" to details)) }
    }

    private companion object {
        const val EDGE_METRES = 1.0
    }
}
