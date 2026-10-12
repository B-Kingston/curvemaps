package com.curvemaps.app

import android.os.SystemClock
import android.util.Log
import com.graphhopper.ResponsePath
import com.graphhopper.util.shapes.GHPoint
import com.curvemaps.app.routing.navigation.GpsFix
import com.curvemaps.app.routing.navigation.GpsSignal
import com.curvemaps.app.routing.navigation.NavigationSession
import com.curvemaps.app.routing.navigation.NavigationSnapshot
import com.curvemaps.app.routing.navigation.NavigationState
import com.curvemaps.app.routing.navigation.PositionSource
import com.curvemaps.app.routing.navigation.RebuildRequest
import com.curvemaps.app.routing.navigation.RouteTrackFactory
import com.curvemaps.app.routing.suggestion.DiversionProposal
import com.curvemaps.app.routing.suggestion.DiversionSearchRequest
import com.curvemaps.app.routing.suggestion.RideSuggestionEngine
import com.curvemaps.app.routing.suggestion.RideSuggestionInputs
import com.curvemaps.app.routing.suggestion.RideSuggestionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CurveMaps.NavSession"
private const val TAG_SUGGEST = "CurveMaps.Suggest"

/** Shaping points within this distance of the route still count as "on it". */
private const val VIA_ON_ROUTE_M = 150.0

/**
 * GPS-gap clock period. Twice per fix interval: often enough that an
 * estimated or easing marker moves continuously under the 600 ms camera ease.
 */
internal const val GUIDANCE_TICK_MS = 500L

/**
 * Owns one guidance session for the route screen and glues the pure engine to
 * Android: GPS fixes in, HUD snapshots out, off-route rebuilds through the
 * same generation-safe route coordinator used for planning, and mid-ride
 * scenic suggestions ([RideSuggestionEngine]).
 *
 * The screen supplies a [rebuild] callback that performs the actual reroute;
 * it must be safe to call from the GPS collection coroutine.
 *
 * While a ride runs, a [GUIDANCE_TICK_MS] clock lets the session carry
 * progress through GPS gaps and ease the marker back when fixes return.
 * Fix arrival and ticks share one elapsed-realtime [clock], independent of
 * any replay clock the caller passes for suggestion cadence.
 */
class NavigationController(
    private val scope: CoroutineScope,
    private val session: NavigationSession = NavigationSession(),
    private val suggestions: RideSuggestionEngine = RideSuggestionEngine(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    val snapshot = session.snapshot

    /** Pending/resolved scenic suggestion for the ride HUD. */
    val suggestionState: StateFlow<RideSuggestionState> = suggestions.state

    internal val suggestionCheckInFlight: Boolean get() = suggestions.isChecking

    private val _gpsSignalLost = MutableStateFlow(false)

    /**
     * True while Android supplies no location fixes for the ride ([GpsSignal.isLost]).
     * The HUD's "No GPS" badge does not follow route estimation. Refreshed on
     * every fix and tick; false outside a ride and while debug fixes hold estimation off.
     */
    val gpsSignalLost: StateFlow<Boolean> = _gpsSignalLost.asStateFlow()

    // When the last fix of any kind arrived, or the ride started before one did.
    private var lastFixAtMs = 0L

    private var rebuild: (suspend (RebuildRequest) -> Unit)? = null
    private var search: (suspend (DiversionSearchRequest) -> DiversionProposal?)? = null
    private var inputs: () -> RideSuggestionInputs = { RideSuggestionInputs(enabled = false, style = com.curvemaps.app.routing.RideStyle.FASTEST) }
    private var speak: (String) -> Unit = {}
    private var onAccepted: (DiversionProposal) -> Unit = {}
    private var searchJob: Job? = null
    private var tickJob: Job? = null
    private var estimationAllowed: () -> Boolean = { true }
    private var onEstimated: (NavigationSnapshot) -> Unit = {}

    // The path most recently installed directly (an accepted suggestion). The
    // screen echoes it back through start(); that echo must not restart the ride.
    private var installedPath: ResponsePath? = null

    /** Registers the async rebuild callback; call before [start]. */
    fun setRebuildHandler(handler: suspend (RebuildRequest) -> Unit) {
        rebuild = handler
    }

    /**
     * Wires GPS-gap estimation. [allowed] is read every tick (the debug fix
     * seam turns it off while it drives guidance deterministically);
     * [onEstimated] receives each estimated snapshot so voice guidance keeps
     * announcing turns while GPS is gone.
     */
    fun setEstimationHandlers(allowed: () -> Boolean, onEstimated: (NavigationSnapshot) -> Unit) {
        estimationAllowed = allowed
        this.onEstimated = onEstimated
    }

    /**
     * Wires ride suggestions. [search] runs the two routing calls off the main
     * thread; [inputs] is read per fix; [speak] announces offers; [onAccepted]
     * lets the screen show the accepted ride (the session already follows it).
     */
    fun setSuggestionHandlers(
        search: suspend (DiversionSearchRequest) -> DiversionProposal?,
        inputs: () -> RideSuggestionInputs,
        speak: (String) -> Unit,
        onAccepted: (DiversionProposal) -> Unit,
    ) {
        this.search = search
        this.inputs = inputs
        this.speak = speak
        this.onAccepted = onAccepted
    }

    /** Begins following [path], or atomically installs it as an off-route rebuild. */
    fun start(path: ResponsePath) {
        if (path === installedPath) return
        installedPath = null
        val track = RouteTrackFactory.fromPath(path)
        cancelSearch()
        if (session.snapshot.value.state in setOf(NavigationState.NeedRebuild, NavigationState.Rebuilding)) {
            session.applyRebuiltRoute(track, session.currentRouteCoveredM())
            suggestions.onRouteReplaced()
        } else {
            session.startRoute(track)
            session.beginFollowing()
            suggestions.onRideStarted()
            lastFixAtMs = clock()
        }
        startTicking()
        Log.i(TAG, "Guidance started (${track.totalDistanceM.toInt()} m, ${track.turns.size} turns)")
    }

    /** Feeds the shared screen-level location stream into guidance. */
    fun onFix(fix: GpsFix, nowMs: Long = SystemClock.elapsedRealtime()) {
        // Suggestions anchor on the rider's real position: never on a GPS-gap
        // guess or a marker still easing back onto the live fix.
        val snapshot = session.snapshot.value.let {
            if (it.positionSource == PositionSource.GPS) it else it.copy(lat = Double.NaN, lon = Double.NaN)
        }
        val result = suggestions.onFix(
            fix = fix,
            nowMs = nowMs,
            snapshot = snapshot,
            baseline = session.activeTrack(),
            baselineOffsetM = session.currentRouteCoveredM(),
            inputs = inputs(),
        )
        perform(result.actions)
        result.search?.let(::launchSearch)
        val wasGuessing = session.snapshot.value.positionSource.let {
            it == PositionSource.ESTIMATED || it == PositionSource.LOST
        }
        lastFixAtMs = clock()
        session.onFix(fix, lastFixAtMs)
        refreshGpsSignal()
        if (wasGuessing && session.snapshot.value.positionSource.isLive) {
            Log.i(TAG, "GPS back: easing from the estimate onto the live position")
        }
    }

    /** One GPS-gap clock step; the on-device suite also calls it to drive the clock deterministically. */
    internal fun tick() {
        if (!estimationAllowed()) {
            _gpsSignalLost.value = false
            return
        }
        val before = session.snapshot.value.positionSource
        val published = session.onTick(clock())
        refreshGpsSignal()
        if (published == null) return
        if (published != before) Log.i(TAG, "Position source: $before -> $published")
        if (published == PositionSource.ESTIMATED) onEstimated(session.snapshot.value)
    }

    private fun refreshGpsSignal() {
        val lost = tickJob != null && estimationAllowed() &&
            GpsSignal.isLost(clock(), lastFixAtMs)
        if (lost != _gpsSignalLost.value) Log.i(TAG, if (lost) "No GPS fix" else "GPS fix back")
        _gpsSignalLost.value = lost
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(GUIDANCE_TICK_MS)
                tick()
            }
        }
    }

    /** The rider tapped Take on the suggestion banner. */
    fun takeSuggestion() {
        perform(suggestions.take())
    }

    /** The rider tapped Skip on the suggestion banner. */
    fun skipSuggestion() {
        suggestions.skip(SystemClock.elapsedRealtime())
        Log.i(TAG_SUGGEST, "Suggestion skipped")
    }

    /** Ride suggestions were switched off mid-ride. */
    fun disableSuggestions() {
        cancelSearch()
        suggestions.onDisabled()
    }

    /**
     * The plan's shaping points the rider has not passed yet, in order. A
     * rebuild keeps them so it cannot silently drop GPX legs or an accepted
     * scenic diversion.
     */
    fun remainingViaPoints(viaPoints: List<GHPoint>): List<GHPoint> =
        remainingViaIndices(viaPoints).map { viaPoints[it] }

    /** Indices into [viaPoints] of [remainingViaPoints], for data kept beside them. */
    fun remainingViaIndices(viaPoints: List<GHPoint>): List<Int> {
        val track = session.activeTrack() ?: return viaPoints.indices.toList()
        val offset = session.currentRouteCoveredM()
        return viaPoints.indices.filter {
            track.nearestVertexOffsetM(viaPoints[it].lat, viaPoints[it].lon, offset, VIA_ON_ROUTE_M) != null
        }
    }

    /** Stops guidance. Location ownership remains with the screen. */
    fun stop() {
        cancelSearch()
        tickJob?.cancel()
        tickJob = null
        _gpsSignalLost.value = false
        installedPath = null
        session.stop()
        suggestions.onRideStopped()
        Log.i(TAG, "Guidance stopped")
    }

    private fun perform(actions: List<RideSuggestionEngine.Action>) {
        actions.forEach { action ->
            when (action) {
                is RideSuggestionEngine.Action.Speak -> speak(action.text)
                is RideSuggestionEngine.Action.Accept -> {
                    val proposal = action.proposal
                    val path = proposal.path ?: return@forEach
                    cancelSearch()
                    installedPath = path
                    session.applySuggestedRoute(proposal.replacement, action.replacementOffsetM)
                    Log.i(
                        TAG_SUGGEST,
                        "Suggestion ${proposal.id} accepted (+${proposal.measure.extraTimeS.toInt()} s, " +
                            "+${proposal.measure.extraDistanceM.toInt()} m, +${proposal.measure.curvyGainM.toInt()} m curvy)",
                    )
                    onAccepted(proposal)
                }
            }
        }
    }

    private fun launchSearch(request: DiversionSearchRequest) {
        val handler = search ?: run {
            suggestions.onSearchFailed(request)
            return
        }
        searchJob = scope.launch {
            val started = SystemClock.elapsedRealtime()
            val proposal = try {
                handler(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG_SUGGEST, "Suggestion check failed: ${e.message}")
                suggestions.onSearchFailed(request)
                return@launch
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            if (proposal == null) {
                Log.d(TAG_SUGGEST, "Suggestion check ${request.proposalId}: nothing worth it ($elapsed ms)")
            } else {
                Log.i(
                    TAG_SUGGEST,
                    "Suggestion check ${request.proposalId}: +${proposal.measure.extraTimeS.toInt()} s, " +
                        "+${proposal.measure.extraDistanceM.toInt()} m, +${proposal.measure.curvyGainM.toInt()} m curvy " +
                        "($elapsed ms)",
                )
            }
            perform(suggestions.onSearchFinished(request, proposal, inputs()))
        }
    }

    private fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
    }

    init {
        session.setRebuildHold { suggestions.holdsRebuild }
        session.setRebuildListener { request ->
            val handler = rebuild ?: run {
                Log.w(TAG, "Off-route detected but no rebuild handler; guidance continues stale")
                return@setRebuildListener
            }
            Log.i(
                TAG,
                "Off-route: rebuilding from the rider (heading ${request.headingDeg})",
            )
            session.markRebuilding()
            scope.launch {
                handler(request)
            }
        }
    }
}
