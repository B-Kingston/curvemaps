package com.curvemaps.app.routing

import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.graphhopper.util.shapes.GHPoint
import com.curvemaps.app.RouteOutcome
import com.curvemaps.app.RouteParams
import com.curvemaps.app.RouteSearchCoordinator
import com.curvemaps.app.RouteUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteSearchCoordinatorTest {

    @Test
    fun latestGenerationWinsWhenEarlyCompletes() = runTest {
        val early = CompletableDeferred<Unit>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        try {
            val coordinator = RouteSearchCoordinator(
                scope = scope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
                backend = { params ->
                    if (params.complexity == 1.0) {
                        early.await()
                        RouteOutcome.Success(result(1), 0)
                    } else {
                        RouteOutcome.Success(result(2), 0)
                    }
                },
            )
            coordinator.submit(params(1.0))
            runCurrent()
            coordinator.submit(params(2.0))
            advanceUntilIdle()
            assertSuccessDistance(coordinator, 2.0)
            early.complete(Unit)
            advanceUntilIdle()
            assertSuccessDistance(coordinator, 2.0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun staleCompletionIsDiscardedWithoutCancellation() = runTest {
        val early = CompletableDeferred<Unit>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var active = 0
        var maxActive = 0
        try {
            val coordinator = RouteSearchCoordinator(
                scope = scope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
                gate = Semaphore(1),
                backend = { params ->
                    active++
                    maxActive = maxOf(maxActive, active)
                    try {
                        if (params.complexity == 1.0) {
                            withContext(NonCancellable) { early.await() }
                        }
                        RouteOutcome.Success(result(params.complexity.toInt()), 0)
                    } finally {
                        active--
                    }
                },
            )
            coordinator.submit(params(1.0))
            runCurrent()
            coordinator.submit(params(2.0))
            runCurrent()
            assertTrue("the first backend call must be non-cancellable", active == 1)
            early.complete(Unit)
            advanceUntilIdle()
            assertSuccessDistance(coordinator, 2.0)
            assertEquals(1, maxActive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun staleFailureIsDiscarded() = runTest {
        val early = CompletableDeferred<Unit>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        try {
            val coordinator = RouteSearchCoordinator(
                scope = scope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
                gate = Semaphore(1),
                backend = { params ->
                    if (params.complexity == 1.0) {
                        withContext(NonCancellable) { early.await() }
                        RouteOutcome.Failure("old failure")
                    } else {
                        RouteOutcome.Success(result(2), 0)
                    }
                },
            )
            coordinator.submit(params(1.0))
            runCurrent()
            coordinator.submit(params(2.0))
            early.complete(Unit)
            advanceUntilIdle()
            assertSuccessDistance(coordinator, 2.0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun invalidateResetsLoadingToIdle() = runTest {
        val latch = CompletableDeferred<Unit>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        try {
            val coordinator = RouteSearchCoordinator(
                scope = scope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
                backend = {
                    latch.await()
                    RouteOutcome.Success(result(1), 0)
                },
            )
            coordinator.submit(params(1.0))
            runCurrent()
            assertEquals(RouteUiState.Loading, coordinator.state.value)
            coordinator.invalidate()
            assertEquals(RouteUiState.Idle, coordinator.state.value)
            latch.complete(Unit)
            advanceUntilIdle()
            assertEquals(RouteUiState.Idle, coordinator.state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun publishErrorIgnoresStaleGeneration() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        try {
            val coordinator = RouteSearchCoordinator(scope, ioDispatcher = StandardTestDispatcher(testScheduler)) {
                RouteOutcome.Success(result(1), 0)
            }
            val staleGeneration = coordinator.generation
            coordinator.invalidate()
            coordinator.publishError(staleGeneration, "stale")
            assertEquals(RouteUiState.Idle, coordinator.state.value)
            coordinator.publishError(coordinator.generation, "current")
            assertEquals(RouteUiState.Error("current"), coordinator.state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun gateSerializesRapidSubmissionsAndOnlyLatestRuns() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val calls = mutableListOf<RouteParams>()
        var active = 0
        var maxActive = 0
        try {
            val coordinator = RouteSearchCoordinator(
                scope = scope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
                gate = Semaphore(1),
                backend = { params ->
                    calls += params
                    active++
                    maxActive = maxOf(maxActive, active)
                    active--
                    RouteOutcome.Success(result(params.complexity.toInt()), 0)
                },
            )
            repeat(5) { coordinator.submit(params((it + 1).toDouble())) }
            advanceUntilIdle()
            assertEquals(1, calls.size)
            assertEquals(5.0, calls.single().complexity, 0.0)
            assertEquals(1, maxActive)
            assertSuccessDistance(coordinator, 5.0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun invalidateKeepsDisplayedSuccess() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        try {
            val coordinator = RouteSearchCoordinator(scope, ioDispatcher = StandardTestDispatcher(testScheduler)) {
                RouteOutcome.Success(result(7), 0)
            }
            coordinator.submit(params(7.0))
            advanceUntilIdle()
            val before = coordinator.generation
            assertSuccessDistance(coordinator, 7.0)
            coordinator.invalidate()
            assertEquals(before + 1, coordinator.generation)
            assertSuccessDistance(coordinator, 7.0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun preferredGeometryIsForwardedToBackend() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var received: String? = null
        try {
            val coordinator = RouteSearchCoordinator(scope, ioDispatcher = StandardTestDispatcher(testScheduler)) { params ->
                received = params.preferredGeometry
                RouteOutcome.Success(result(1), 0)
            }
            coordinator.submit(params(1.0, preferredGeometry = "encoded-shape"))
            advanceUntilIdle()
            assertEquals("encoded-shape", received)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun viaPointsAreForwardedToBackendForAnImportedPlan() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val via = GHPoint(-27.5, 152.5)
        var received: List<GHPoint> = emptyList()
        try {
            val coordinator = RouteSearchCoordinator(
                scope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            ) { params ->
                received = params.viaPoints
                RouteOutcome.Success(result(1), 0)
            }
            coordinator.submit(params(1.0, viaPoints = listOf(via)))
            advanceUntilIdle()
            assertEquals(listOf(via), received)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun routeSettingsKeepTheRoundTripAndShapingPoints() {
        val home = GHPoint(-26.68, 152.96)
        val corners = listOf(GHPoint(-26.60, 152.90), GHPoint(-26.62, 153.02))
        val loop = RouteParams(
            from = home,
            to = home,
            complexity = 4.0,
            blockUnpaved = false,
            avoidMotorways = false,
            preferredGeometry = "stored",
            viaPoints = corners,
            roundTrip = RoundTripLength.Time(90),
            passThrough = true,
        )
        val applied = loop.withRouteSettings(
            complexity = 4.0,
            blockUnpaved = true,
            avoidMotorways = true,
            factors = RideFactors(elevation = true, stopsAndTowns = true),
        )
        // A round trip's To is its From: without its length it would be a
        // zero-length ride the router rejects as "No route was found".
        assertEquals(RoundTripLength.Time(90), applied.roundTrip)
        assertTrue(applied.passThrough)
        assertEquals(corners, applied.viaPoints)
        assertEquals(home, applied.from)
        assertEquals(home, applied.to)
        assertTrue(applied.blockUnpaved)
        assertTrue(applied.avoidMotorways)
        assertEquals(RideFactors(elevation = true, stopsAndTowns = true), applied.factors)
        assertEquals(null, applied.preferredGeometry)
    }

    private fun params(
        complexity: Double,
        preferredGeometry: String? = null,
        viaPoints: List<GHPoint> = emptyList(),
    ) = RouteParams(
        from = GHPoint(-27.0, 153.0),
        to = GHPoint(-28.0, 152.0),
        complexity = complexity,
        blockUnpaved = false,
        preferredGeometry = preferredGeometry,
        viaPoints = viaPoints,
    )

    private fun result(marker: Int): RouteResult = RouteResult(
        listOf(
            ResponsePath()
                .setPoints(PointList().apply { add(0.0, 0.0); add(0.01, 0.01) })
                .setDistance(marker.toDouble())
                .setTime(marker.toLong()),
        ),
    )

    private fun assertSuccessDistance(coordinator: RouteSearchCoordinator, expected: Double) {
        val success = coordinator.state.value as? RouteUiState.Success
        assertTrue(success != null)
        assertEquals(expected, success!!.result.routes.single().distance, 0.0)
    }
}
