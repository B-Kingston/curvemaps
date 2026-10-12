package com.curvemaps.app.routing.suggestion

import com.curvemaps.app.routing.RideStyle
import com.curvemaps.app.routing.navigation.GeoMath
import com.curvemaps.app.routing.navigation.GpsFix
import com.curvemaps.app.routing.navigation.NavigationSnapshot
import com.curvemaps.app.routing.navigation.NavigationSpeechFormat
import com.curvemaps.app.routing.navigation.NavigationState
import com.curvemaps.app.routing.navigation.RouteTrack
import com.curvemaps.app.routing.navigation.VoiceDistanceUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the ride HUD shows for suggestions. */
sealed interface RideSuggestionState {
    data object None : RideSuggestionState
    data class Pending(val proposal: DiversionProposal) : RideSuggestionState

    /** The last proposal's fate: "accepted", "skipped" or "expired". */
    data class Resolved(val id: Int, val outcome: String) : RideSuggestionState

    /** Stable string for the `RideSuggestion` semantics test seam. */
    val semanticsValue: String
        get() = when (this) {
            None -> "none"
            is Pending -> "pending:${proposal.id}"
            is Resolved -> "$outcome:$id"
        }
}

/** Per-fix inputs owned by the screen rather than the guidance engine. */
data class RideSuggestionInputs(
    val enabled: Boolean,
    val style: RideStyle,
    val battery: BatteryState = BatteryState(),
    val unit: VoiceDistanceUnit = VoiceDistanceUnit.METRIC,
    /** True while the plan still has shaping points ahead (GPX legs, an accepted diversion). */
    val shapingPointsAhead: Boolean = false,
)

/** One check the caller must run off the main thread (two routing calls). */
data class DiversionSearchRequest(
    val generation: Int,
    val proposalId: Int,
    val baseline: RouteTrack,
    val riderOffsetM: Double,
    val riderLat: Double,
    val riderLon: Double,
    val windowStartM: Double,
    val windowEndM: Double,
    val speedMps: Double,
    val style: RideStyle,
)

/**
 * Pure orchestration of mid-ride scenic suggestions. Single-threaded: the
 * screen calls it from the GPS collector and posts search results back on the
 * same thread. It never routes; it hands out [DiversionSearchRequest]s and
 * judges what comes back.
 */
class RideSuggestionEngine(
    private val scheduler: SuggestionScheduler = SuggestionScheduler(),
) {
    sealed interface Action {
        data class Speak(val text: String) : Action

        /** Install [proposal]'s replacement; the rider is [replacementOffsetM] along it. */
        data class Accept(val proposal: DiversionProposal, val replacementOffsetM: Double) : Action
    }

    data class FixResult(val actions: List<Action>, val search: DiversionSearchRequest?)

    private val _state = MutableStateFlow<RideSuggestionState>(RideSuggestionState.None)
    val state: StateFlow<RideSuggestionState> = _state.asStateFlow()

    private var generation = 0
    private var nextProposalId = 1
    private var searchInFlight = false
    private var acceptance: DiversionAcceptance? = null
    private var reminded = false
    private var lastFixLat = Double.NaN
    private var lastFixLon = Double.NaN
    private var lastBaselineOffsetM = 0.0
    private var lastSpeedMps = Double.NaN
    private var lastNowMs = 0L

    /** Debug/test observation; at most one bounded check may be in flight. */
    val isChecking: Boolean get() = searchInFlight

    /** True while a proposal is pending: off-route rebuilds wait for its verdict. */
    val holdsRebuild: Boolean get() = acceptance != null

    /** A brand-new ride: cadence, cooldown and the hourly cap start over. */
    fun onRideStarted() {
        scheduler.reset()
        onRouteReplaced()
        _state.value = RideSuggestionState.None
        lastFixLat = Double.NaN
        lastFixLon = Double.NaN
    }

    /** The route changed under us (rebuild or acceptance): stale work is void. */
    fun onRouteReplaced() {
        generation++
        searchInFlight = false
        acceptance = null
        reminded = false
        if (_state.value is RideSuggestionState.Pending) _state.value = RideSuggestionState.None
    }

    fun onRideStopped() {
        onRideStarted()
    }

    /** Toggle off mid-ride: drop any pending proposal silently, no cooldown. */
    fun onDisabled() {
        generation++
        searchInFlight = false
        acceptance = null
        reminded = false
        _state.value = RideSuggestionState.None
    }

    fun onFix(
        fix: GpsFix,
        nowMs: Long,
        snapshot: NavigationSnapshot,
        baseline: RouteTrack?,
        baselineOffsetM: Double,
        inputs: RideSuggestionInputs,
    ): FixResult {
        val moved = if (lastFixLat.isNaN()) 0.0 else GeoMath.haversineM(lastFixLat, lastFixLon, fix.lat, fix.lon)
        lastFixLat = fix.lat
        lastFixLon = fix.lon
        lastBaselineOffsetM = baselineOffsetM
        lastSpeedMps = if (fix.hasSpeed) fix.speedMps else Double.NaN
        lastNowMs = nowMs
        scheduler.onFix(nowMs, lastSpeedMps, moved)

        if (!inputs.enabled) {
            if (acceptance != null || _state.value is RideSuggestionState.Pending) onDisabled()
            return FixResult(emptyList(), null)
        }

        val actions = mutableListOf<Action>()
        val pendingAcceptance = acceptance
        val pending = (_state.value as? RideSuggestionState.Pending)?.proposal
        if (pendingAcceptance != null && pending != null) {
            when (pendingAcceptance.onFix(fix)) {
                DiversionAcceptance.Outcome.ACCEPTED -> {
                    actions += accept(pending, pendingAcceptance.replacementOffsetM)
                    return FixResult(actions, null)
                }
                DiversionAcceptance.Outcome.EXPIRED -> {
                    resolve(pending.id, "expired")
                    scheduler.onDeclined(nowMs)
                    return FixResult(actions, null)
                }
                DiversionAcceptance.Outcome.PENDING -> {
                    val toFork = pending.span.divergeReplacementOffsetM - pendingAcceptance.replacementOffsetM
                    val speed = lastSpeedMps.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                    if (!reminded && toFork in 0.0..maxOf(REMINDER_MIN_M, speed * REMINDER_SECONDS)) {
                        reminded = true
                        actions += Action.Speak(NavigationSpeechFormat.diversionReminder(pending.divergenceTurn?.toTurnInfo()))
                    }
                }
            }
            return FixResult(actions, null)
        }

        val track = baseline ?: return FixResult(actions, null)
        if (inputs.shapingPointsAhead) return FixResult(actions, null)
        val due = scheduler.shouldEvaluate(
            nowMs = nowMs,
            enabled = true,
            state = snapshot.state,
            style = inputs.style,
            speedMps = lastSpeedMps,
            remainingM = track.remainingDistanceM(baselineOffsetM),
            battery = inputs.battery,
            busy = searchInFlight,
        )
        if (!due) return FixResult(actions, null)
        scheduler.onEvaluated()
        val window = SuggestionWindow.choose(track.totalDistanceM, baselineOffsetM, lastSpeedMps)
            ?: return FixResult(actions, null)
        searchInFlight = true
        return FixResult(
            actions,
            DiversionSearchRequest(
                generation = generation,
                proposalId = nextProposalId++,
                baseline = track,
                riderOffsetM = baselineOffsetM,
                riderLat = snapshot.lat.takeIf { it.isFinite() } ?: fix.lat,
                riderLon = snapshot.lon.takeIf { it.isFinite() } ?: fix.lon,
                windowStartM = window.first,
                windowEndM = window.second,
                speedMps = lastSpeedMps,
                style = inputs.style,
            ),
        )
    }

    /**
     * A check finished. [proposal] is null when nothing qualified. Returns the
     * spoken offer when the proposal is still current and still ahead.
     */
    fun onSearchFinished(
        request: DiversionSearchRequest,
        proposal: DiversionProposal?,
        inputs: RideSuggestionInputs,
    ): List<Action> {
        if (request.generation != generation) return emptyList()
        searchInFlight = false
        if (proposal == null || !inputs.enabled) return emptyList()
        // The rider kept riding while the router worked: re-check the lead.
        val riddenSince = (lastBaselineOffsetM - request.riderOffsetM).coerceAtLeast(0.0)
        val ahead = proposal.span.divergeReplacementOffsetM - riddenSince
        if (!SuggestionWindow.divergenceLeadOk(ahead, lastSpeedMps)) return emptyList()
        acceptance = DiversionAcceptance(request.baseline, proposal, lastBaselineOffsetM)
        reminded = false
        scheduler.onProposed(lastNowMs)
        _state.value = RideSuggestionState.Pending(proposal)
        return listOf(
            Action.Speak(
                NavigationSpeechFormat.diversionOffer(
                    extraTimeS = proposal.measure.extraTimeS,
                    extraDistanceM = proposal.measure.extraDistanceM,
                    curvyGainM = proposal.measure.curvyGainM,
                    turn = proposal.divergenceTurn?.toTurnInfo(),
                    distanceAheadM = ahead,
                    unit = inputs.unit,
                ),
            ),
        )
    }

    /** A check failed (routing error); the cadence simply moves on. */
    fun onSearchFailed(request: DiversionSearchRequest) {
        if (request.generation == generation) searchInFlight = false
    }

    /** The rider tapped Take. */
    fun take(): List<Action> {
        val pending = (_state.value as? RideSuggestionState.Pending)?.proposal ?: return emptyList()
        val offset = acceptance?.replacementOffsetM ?: 0.0
        return accept(pending, offset)
    }

    /** The rider tapped Skip. */
    fun skip(nowMs: Long) {
        val pending = (_state.value as? RideSuggestionState.Pending)?.proposal ?: return
        resolve(pending.id, "skipped")
        scheduler.onDeclined(nowMs)
    }

    private fun accept(proposal: DiversionProposal, offsetM: Double): List<Action> {
        acceptance = null
        reminded = false
        generation++
        searchInFlight = false
        _state.value = RideSuggestionState.Resolved(proposal.id, "accepted")
        return listOf(Action.Accept(proposal, offsetM), Action.Speak(NavigationSpeechFormat.DIVERSION_TAKEN))
    }

    private fun resolve(id: Int, outcome: String) {
        acceptance = null
        reminded = false
        _state.value = RideSuggestionState.Resolved(id, outcome)
    }

    private fun RouteTrack.TurnNode.toTurnInfo() = NavigationSnapshot.TurnInfo(
        sign = sign,
        streetName = streetName,
        distanceM = 0.0,
        turnAngleDeg = turnAngleDeg,
        roundaboutExitNumber = roundaboutExitNumber,
        roundaboutClockwise = roundaboutClockwise,
        maneuverId = vertexIndex,
    )

    private companion object {
        /** The fork reminder fires within 150 m or 8 s of the fork, whichever is farther. */
        const val REMINDER_MIN_M = 150.0
        const val REMINDER_SECONDS = 8.0
    }
}
