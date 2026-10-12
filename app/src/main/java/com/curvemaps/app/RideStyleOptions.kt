package com.curvemaps.app

import com.curvemaps.app.routing.DistinctRideStyles
import com.curvemaps.app.routing.RideFactors
import com.curvemaps.app.routing.RideStyle
import com.curvemaps.app.routing.RideStyleSearch
import com.curvemaps.app.routing.RoundTripLength
import com.curvemaps.app.routing.RoundTrips
import com.curvemaps.app.routing.RouteResult
import com.graphhopper.ResponsePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Every input that changes a route except the ride style. */
internal data class RidePlanKey(
    val points: List<Pair<Double, Double>>,
    val blockUnpaved: Boolean,
    val avoidMotorways: Boolean,
    val factors: RideFactors = RideFactors.NONE,
    val roundTrip: RoundTripLength? = null,
    val passThrough: Boolean = false,
    val startHeading: Double = Double.NaN,
) {
    companion object {
        fun of(params: RouteParams) = RidePlanKey(
            (listOf(params.from) + params.viaPoints + params.to).map { it.lat to it.lon },
            params.blockUnpaved,
            params.avoidMotorways,
            params.factors,
            params.roundTrip,
            params.passThrough,
            params.startHeading,
        )
    }
}

/**
 * How far the current plan's search has got: the lowest [resolved] stops of
 * [total] are settled (stops settle Fastest first), so the slider can grow
 * one hump per finished stop.
 */
internal data class StyleSearchProgress(val key: RidePlanKey, val resolved: Int, val total: Int = RideStyle.entries.size)

/**
 * Computes all seven ride-style options of a plan, each a different ride
 * ([DistinctRideStyles]), so moving the slider is instant once they are in.
 *
 * [route] returns the requested stop as soon as that stop is settled
 * ([progress] counts them): Fastest as soon as its optimum is in, the six
 * stops above it together once every search is done, because they are
 * chosen together ([RideLadder]): a stop's best ride depends on what the
 * stops above it need. A request for a plan that is already being
 * computed joins that work; a new plan (different endpoints, shaping points
 * road toggles or ride factors) cancels the old one. A request that is cancelled (the
 * rider slid on) leaves the plan running. Every stop's own optimum is
 * searched up front, [parallelism] at a time; curve escalation, bounded
 * road penalties and the look-around via rings run beside them
 * ([DistinctRideStyles]). Every stop becomes a ride of its own; only a graph
 * with no other road at all settles a stop on the stop below it (flagged
 * [DistinctRideStyles.Option.repeats]) so the slider keeps working. A search
 * that fails outright fails the plan for every stop still waiting, and a
 * failed plan is forgotten so the next request retries.
 *
 * A round trip ([RouteParams.roundTrip]) resolves its seven stops the same
 * way from loops around the start instead ([RoundTrips]): every stop a loop
 * of its own, Fastest first.
 *
 * With [enabled] off (while riding) [route] computes only the requested
 * stop's optimum: an off-route rebuild must not spend battery on six styles
 * nobody will look at. A rebuilt round trip is an ordinary plan through the
 * loop's remaining corners, so it never reaches the loop search.
 */
internal class RideStyleOptions(
    parent: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val onSettled: (RideStyle, DistinctRideStyles.Option) -> Unit = { _, _ -> },
    private val compute: (params: RouteParams, style: RideStyle, search: RideStyleSearch) -> RouteResult,
) {
    /** One plan's search and a promise per stop, kept as each stop settles. */
    private class Plan(val key: RidePlanKey) {
        val stops: Map<RideStyle, CompletableDeferred<DistinctRideStyles.Option>> =
            RideStyle.entries.associateWith { CompletableDeferred() }
        lateinit var job: Job
    }

    // A failed plan must not cancel the screen's scope.
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val lock = Any()
    private var plan: Plan? = null
    private val _progress = MutableStateFlow<StyleSearchProgress?>(null)
    val progress: StateFlow<StyleSearchProgress?> = _progress.asStateFlow()

    var enabled: Boolean = true

    /** The requested stop of [params]' plan, as soon as that stop is settled. */
    suspend fun route(params: RouteParams): RouteResult {
        val style = RideStyle.fromLevel(params.complexity)
        if (!enabled && params.roundTrip == null) return withContext(dispatcher) { compute(params, style, RideStyleSearch()) }
        val current = synchronized(lock) { planFor(RidePlanKey.of(params), params) }
        val option = try {
            current.stops.getValue(style).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) { if (plan === current) plan = null }
            throw e
        }
        return RouteResult(listOf(option.path))
    }

    /**
     * The requested stop's ride when this plan has already settled it, or null
     * while that stop is still being searched (or the plan is for other
     * inputs). The slider installs this at once as the finger crosses a stop,
     * so a finished ride never waits behind a loading flash or a fresh search.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun readyOption(params: RouteParams): RouteResult? {
        if (!enabled) return null
        val style = RideStyle.fromLevel(params.complexity)
        val current = synchronized(lock) { plan } ?: return null
        if (current.key != RidePlanKey.of(params)) return null
        val stop = current.stops.getValue(style)
        if (!stop.isCompleted) return null
        val option = try {
            stop.getCompleted()
        } catch (_: Exception) {
            // A failed or cancelled plan is answered (and forgotten) by the
            // normal request path; this is only a fast path.
            return null
        }
        return RouteResult(listOf(option.path))
    }

    fun close() {
        scope.cancel()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun planFor(planKey: RidePlanKey, params: RouteParams): Plan {
        plan?.let { if (it.key == planKey) return it }
        plan?.job?.cancel()
        val next = Plan(planKey)
        plan = next
        _progress.value = StyleSearchProgress(planKey, 0)
        val searches = dispatcher.limitedParallelism(parallelism)
        // async, not launch: a failure is answered to the waiting stops below
        // instead of reaching the screen scope's uncaught-exception handler.
        next.job = scope.async(dispatcher) {
            var resolved = 0
            fun settled(style: RideStyle, option: DistinctRideStyles.Option) {
                resolved++
                next.stops.getValue(style).complete(option)
                onSettled(style, option)
                synchronized(lock) {
                    if (plan === next) _progress.value = StyleSearchProgress(planKey, resolved)
                }
            }
            val roundTrip = params.roundTrip
            if (roundTrip != null) {
                RoundTrips.resolve(
                    start = params.from,
                    length = roundTrip,
                    loop = { style, shape ->
                        withContext(searches) {
                            try {
                                compute(params, style, RideStyleSearch(loop = shape)).routes.first()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // That direction has no loop (a corner with no
                                // road to pass through): the others decide.
                                null
                            }
                        }
                    },
                    onResolved = ::settled,
                )
            } else coroutineScope {
                val optima = RideStyle.entries.associateWith { style ->
                    async(searches) { compute(params, style, RideStyleSearch()).routes.first() }
                }
                // Escalation rounds are searched one ahead: while a stop tries
                // one search the next is already running, because a repeated
                // stop usually needs more than one round.
                val escalations = mutableMapOf<Pair<RideStyle, RideStyleSearch>, Deferred<ResponsePath?>>()
                fun escalation(style: RideStyle, search: RideStyleSearch) =
                    escalations.getOrPut(style to search) {
                        async(dispatcher) {
                            try {
                                compute(params, style, search).routes.first()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Nothing routes for that search: keep looking.
                                null
                            }
                        }
                    }
                DistinctRideStyles.resolve(
                    optimum = { optima.getValue(it).await() },
                    // Looking around routes through one more point; a plan
                    // with its own shaping points keeps its legs as imported.
                    rings = if (params.viaPoints.isEmpty()) DistinctRideStyles.detourRings(params.from, params.to) else emptyList(),
                    // Escalations are the critical path (each stop waits for
                    // the one below), so they never queue behind the higher
                    // stops' optima: at most one runs beside them.
                    reweighted = { style, curveWeight ->
                        val next = DistinctRideStyles.nextEscalationWeight(style, curveWeight)
                        if (next <= RideStyle.MAX_CURVE_WEIGHT) {
                            escalation(style, RideStyleSearch(curveWeight = next))
                        }
                        escalation(style, RideStyleSearch(curveWeight = curveWeight)).await()
                    },
                    // Road penalties are last resort, so they are never
                    // speculated: the search the resolution asks for runs as
                    // given.
                    diversified = { style, search -> escalation(style, search).await() },
                    onSearched = { style ->
                        // A searched stop needs no more rounds: drop its speculative one.
                        escalations.filterKeys { it.first == style }.values.forEach { if (!it.isCompleted) it.cancel() }
                    },
                    onResolved = ::settled,
                )
            }
        }
        // A failed or cancelled plan answers every stop still waiting.
        next.job.invokeOnCompletion { cause ->
            if (cause != null) {
                val failure = cause as? Exception ?: CancellationException("Ride style search stopped")
                next.stops.values.forEach { it.completeExceptionally(failure) }
            }
        }
        return next
    }

    private companion object {
        /** Two searches at a time: fast enough, and gentle on a phone's memory. */
        const val DEFAULT_PARALLELISM = 2
    }
}
