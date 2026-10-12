package com.curvemaps.app

import com.graphhopper.util.shapes.GHPoint
import com.curvemaps.app.routing.RideFactors
import com.curvemaps.app.routing.RoundTripLength
import com.curvemaps.app.routing.RouteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface RouteUiState {
    data object Idle : RouteUiState
    data object Loading : RouteUiState
    data class Success(val result: RouteResult, val selectedIndex: Int = 0) : RouteUiState
    data class Error(val message: String) : RouteUiState
}

data class RouteParams(
    val from: GHPoint,
    val to: GHPoint,
    /** Ride-style dial level ([com.curvemaps.app.routing.RideStyle]). */
    val complexity: Double,
    val blockUnpaved: Boolean,
    val avoidMotorways: Boolean = false,
    val preferredGeometry: String? = null,
    val viaPoints: List<GHPoint> = emptyList(),
    /** Route settings ride factors (elevation, stops and towns). */
    val factors: RideFactors = RideFactors.NONE,
    /**
     * A round trip of about this length from [from] back to it ([to] is
     * [from]): each ride style searches loops of its own
     * ([com.curvemaps.app.routing.RoundTrips]). Null for an A-to-B plan.
     */
    val roundTrip: RoundTripLength? = null,
    /**
     * Ride straight through [viaPoints] instead of allowing a turn-around at
     * them: a rebuilt round trip keeps riding the corners of its loop.
     */
    val passThrough: Boolean = false,
    /**
     * Directions the ride passes through each of [viaPoints] (degrees from
     * north, NaN for none; empty for none at all): a rebuilt followed GPX
     * track keeps riding the carriageways it chose.
     */
    val viaHeadings: List<Double> = emptyList(),
    /**
     * A detailed GPX track this plan rides road for road
     * ([com.curvemaps.app.storage.GpxTrackFollow]): [viaPoints] are only its
     * first shaping points, the router adds more wherever the route leaves
     * the line, and no ride style applies.
     */
    val followTrack: com.curvemaps.app.storage.GpxRidePlan? = null,
    /**
     * Direction the route leaves [from] in (degrees from north, NaN for
     * none): an off-route rebuild starts the way the rider is going.
     */
    val startHeading: Double = Double.NaN,
) {
    /**
     * The same plan re-routed with new Route settings: endpoints, GPX shaping
     * points, a followed track, round trip and pass-through are kept (a round trip's [to] is its
     * [from], so dropping [roundTrip] would ask for a zero-length ride), and
     * the stored saved-ride shape is dropped because the new settings may no
     * longer ride it.
     */
    fun withRouteSettings(
        complexity: Double,
        blockUnpaved: Boolean,
        avoidMotorways: Boolean,
        factors: RideFactors,
    ): RouteParams = copy(
        complexity = complexity,
        blockUnpaved = blockUnpaved,
        avoidMotorways = avoidMotorways,
        factors = factors,
        preferredGeometry = null,
    )
}

sealed interface RouteOutcome {
    data class Success(val result: RouteResult, val matchedIndex: Int) : RouteOutcome
    data class Failure(val message: String) : RouteOutcome
}

/** Owns route-search generations, cancellation, serialization, and UI state. */
class RouteSearchCoordinator(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val gate: Semaphore = Semaphore(1),
    private val backend: suspend (RouteParams) -> RouteOutcome,
) {
    private val _state = MutableStateFlow<RouteUiState>(RouteUiState.Idle)
    val state: StateFlow<RouteUiState> = _state.asStateFlow()
    private val _generation = MutableStateFlow(0)

    /**
     * Observable [generation]: a slider move answered from a finished plan
     * changes nothing else the screen shows, so the test seam must not rely
     * on an unrelated recomposition to publish the new generation.
     */
    val generations: StateFlow<Int> = _generation.asStateFlow()

    var generation: Int
        get() = _generation.value
        private set(value) {
            _generation.value = value
        }
    private var job: Job? = null

    fun submit(params: RouteParams) {
        generation++
        val gen = generation
        job?.cancel()
        job = scope.launch {
            _state.value = RouteUiState.Loading
            val outcome: RouteOutcome? = withContext(ioDispatcher) {
                gate.withPermit {
                    if (gen != generation || !isActive) return@withPermit null
                    try {
                        val result = backend(params)
                        if (gen != generation) null else result
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (gen != generation) null else RouteOutcome.Failure(e.message ?: "Routing failed")
                    }
                }
            }
            if (outcome != null) {
                _state.value = when (outcome) {
                    is RouteOutcome.Success -> RouteUiState.Success(outcome.result, outcome.matchedIndex)
                    is RouteOutcome.Failure -> RouteUiState.Error(outcome.message)
                }
            }
        }
    }

    /**
     * Shows a route that was computed elsewhere (an accepted mid-ride
     * suggestion) as the current plan. Like [submit] it bumps the generation,
     * so any in-flight search for the old plan can no longer land.
     */
    fun install(result: RouteResult) {
        generation++
        job?.cancel()
        _state.value = RouteUiState.Success(result, 0)
    }

    fun selectRoute(index: Int) {
        val current = _state.value
        if (current is RouteUiState.Success && index in current.result.routes.indices &&
            current.selectedIndex != index
        ) {
            _state.value = current.copy(selectedIndex = index)
        }
    }

    fun invalidate() {
        generation++
        job?.cancel()
        // Loading belongs to the cancelled job. A displayed success remains
        // visible while the user edits an endpoint.
        if (_state.value is RouteUiState.Loading) _state.value = RouteUiState.Idle
    }

    /** Drops the displayed plan entirely: back to a blank Idle state. */
    fun reset() {
        generation++
        job?.cancel()
        _state.value = RouteUiState.Idle
    }

    fun publishError(gen: Int, message: String) {
        // Resolution errors do not bump generation. A matching in-flight
        // search can still complete, as in the original route screen.
        if (gen == generation) _state.value = RouteUiState.Error(message)
    }
}
