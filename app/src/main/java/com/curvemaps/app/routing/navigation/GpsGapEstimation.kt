package com.curvemaps.app.routing.navigation

/** Where the rider position in a [NavigationSnapshot] comes from. */
enum class PositionSource {
    /** Live GPS (matched to the route, or the raw fix while off it). */
    GPS,

    /** GPS went quiet while riding; the position is estimated along the route. */
    ESTIMATED,

    /** GPS has been gone longer than estimation is trusted; the position holds. */
    LOST,

    /** GPS is back; the marker is easing from the estimate onto the live position. */
    RECONCILING;

    /** True while the speed and position are measured rather than guessed. */
    val isLive: Boolean get() = this == GPS || this == RECONCILING
}

/**
 * Whether Android has stopped supplying location fixes to the ride.
 * Route estimation does not imply a lost signal: fresh fallback fixes can
 * still arrive while guidance keeps its more precise route estimate.
 */
object GpsSignal {
    /** Same quiet period after which guidance starts estimating. */
    const val LOST_AFTER_MS: Long = 3_000L

    /**
     * [lastFixAtMs] is when the last fix from the location stream arrived (or
     * the ride started, before the first one). The stream rejects stale and
     * duplicate fixes. The guidance position source does not affect the signal.
     */
    fun isLost(nowMs: Long, lastFixAtMs: Long, lostAfterMs: Long = LOST_AFTER_MS): Boolean =
        nowMs - lastFixAtMs >= lostAfterMs
}

/**
 * Along-route dead reckoning for GPS gaps (tunnels, gorges, a phone that
 * stops reporting). Pure JVM; the session anchors it on every matched fix and
 * asks it where the rider probably is when fixes stop arriving.
 *
 * The estimate never leaves the route: it assumes the rider keeps following
 * it, which is what a rider on a planned ride in a tunnel does. Speed comes
 * from the rider, shape from the route: the rider's smoothed speed at the
 * anchor is turned into a pace ratio against the route's model speed there,
 * and the estimate then advances through the route's model time at that
 * ratio. Riding 10% under the model on a 100 km/h tunnel therefore keeps
 * riding 10% under it when the route drops to a 60 km/h street, instead of
 * carrying motorway speed into town.
 *
 * It refuses to guess when the rider was not moving (a stop is not a GPS
 * loss), stops a little short of the destination (only a real fix may
 * finish a ride), and gives up advancing after [Settings.maxEstimateMs].
 */
class RouteProgressEstimator(private val settings: Settings = Settings()) {
    data class Settings(
        /** A moving rider without a fix for this long is estimated. */
        val gpsLostAfterMs: Long = GpsSignal.LOST_AFTER_MS,
        /** Below this smoothed speed (m/s) the rider is stopped: nothing to estimate. */
        val minSpeedMps: Double = 2.0,
        /** Estimation stops advancing after this long without a fix. */
        val maxEstimateMs: Long = 180_000L,
        /** Bounds of rider speed / model speed, so a slow junction cannot multiply speed. */
        val minPaceRatio: Double = 0.5,
        val maxPaceRatio: Double = 1.6,
        /** EMA weight of a fresh speed sample. */
        val speedSmoothing: Double = 0.5,
        /** Model speed is averaged over this distance either side of the anchor. */
        val modelSpeedWindowM: Double = 100.0,
        /** An estimate stops this far before the destination. */
        val finishHoldbackM: Double = 50.0,
    )

    /** One estimated rider position. */
    data class Estimate(
        val offsetM: Double,
        /** Estimated speed in m/s at [offsetM]. */
        val speedMps: Double,
        /** Time since the last fix. */
        val sinceFixMs: Long,
        /** True once [Settings.maxEstimateMs] has passed and the estimate holds. */
        val expired: Boolean,
    )

    private var anchorOffsetM = Double.NaN
    private var anchorAtMs = 0L
    private var speedMps = Double.NaN
    private var paceRatio = Double.NaN

    val hasAnchor: Boolean get() = !anchorOffsetM.isNaN()

    /** Offset of the last matched fix, NaN before the first one. */
    val anchorOffset: Double get() = anchorOffsetM

    /** Smoothed rider speed (m/s) at the anchor, NaN when unknown. */
    val smoothedSpeedMps: Double get() = speedMps

    /**
     * Anchors on a fix matched at [offsetM] and received at [atMs].
     * [fixSpeedMps] is the reported speed, or NaN; without one the speed is
     * measured from the progress since the previous anchor.
     */
    fun onMatched(track: RouteTrack, offsetM: Double, fixSpeedMps: Double, atMs: Long) {
        val measured = when {
            fixSpeedMps.isFinite() && fixSpeedMps >= 0.0 -> fixSpeedMps
            hasAnchor && atMs > anchorAtMs ->
                (offsetM - anchorOffsetM).coerceAtLeast(0.0) / ((atMs - anchorAtMs) / 1000.0)
            else -> Double.NaN
        }
        if (measured.isFinite()) {
            speedMps = if (speedMps.isNaN()) measured else speedMps + settings.speedSmoothing * (measured - speedMps)
        }
        anchorOffsetM = offsetM
        anchorAtMs = atMs
        val model = track.modelSpeedMpsAround(offsetM, settings.modelSpeedWindowM)
        paceRatio = if (speedMps.isFinite() && model.isFinite() && model > 0.0) {
            (speedMps / model).coerceIn(settings.minPaceRatio, settings.maxPaceRatio)
        } else {
            Double.NaN
        }
    }

    /** Forgets the anchor: the rider left the route, or the route changed. */
    fun clear() {
        anchorOffsetM = Double.NaN
        anchorAtMs = 0L
        speedMps = Double.NaN
        paceRatio = Double.NaN
    }

    /**
     * Where the rider probably is at [nowMs], or null while GPS is still
     * fresh, before the first match, or when the rider was not moving.
     */
    fun estimate(track: RouteTrack, nowMs: Long): Estimate? {
        if (!hasAnchor || !moving()) return null
        val sinceFix = nowMs - anchorAtMs
        if (sinceFix < settings.gpsLostAfterMs) return null
        val offset = advance(track, sinceFix.coerceAtMost(settings.maxEstimateMs))
        return Estimate(offset, speedAt(track, offset), sinceFix, sinceFix > settings.maxEstimateMs)
    }

    /**
     * Short extrapolation between live fixes (at most [maxMs]) so a marker
     * easing back after a gap keeps moving with the rider rather than
     * stepping once per fix. Returns the anchor when the rider is stopped.
     */
    fun extrapolate(track: RouteTrack, nowMs: Long, maxMs: Long): Double {
        if (!hasAnchor) return Double.NaN
        if (!moving()) return anchorOffsetM
        return advance(track, (nowMs - anchorAtMs).coerceIn(0L, maxMs))
    }

    private fun moving(): Boolean = speedMps.isFinite() && speedMps >= settings.minSpeedMps

    private fun advance(track: RouteTrack, elapsedMs: Long): Double {
        val seconds = elapsedMs / 1000.0
        val raw = if (paceRatio.isFinite()) {
            track.offsetAtTimeS(track.timeAtOffsetS(anchorOffsetM) + paceRatio * seconds)
        } else {
            anchorOffsetM + speedMps * seconds
        }
        val limit = maxOf(anchorOffsetM, track.totalDistanceM - settings.finishHoldbackM)
        return raw.coerceIn(anchorOffsetM, limit)
    }

    private fun speedAt(track: RouteTrack, offsetM: Double): Double {
        if (!paceRatio.isFinite()) return speedMps
        val model = track.modelSpeedMpsAround(offsetM, settings.modelSpeedWindowM)
        return if (model.isFinite()) paceRatio * model else speedMps
    }
}

/**
 * Eases the displayed rider position from where the estimate had it onto
 * the live position when GPS returns, instead of teleporting the marker and
 * camera. Pure JVM; times are on the session's tick clock.
 *
 * On the route the glide works in along-route metres: the display is the
 * live offset plus a correction that shrinks to zero, so the marker stays on
 * the road through curves. When the first live fix is off the route it
 * blends latitude/longitude from the estimate toward the latest fix.
 * The correction follows smoothstep, so the marker leaves the estimate and
 * lands on the live position without a velocity kick at either end.
 */
class RecoveryGlide(private val settings: Settings = Settings()) {
    data class Settings(
        val minDurationMs: Long = 1_200L,
        val maxDurationMs: Long = 3_000L,
        /** Each this-many metres of correction add a second, up to [maxDurationMs]. */
        val metresPerSecond: Double = 250.0,
        /** Smaller disagreements are applied immediately; they are invisible. */
        val minGapM: Double = 2.0,
    )

    private var startedAtMs = 0L
    private var durationMs = 0L
    private var gapM = 0.0
    private var fromLat = Double.NaN
    private var fromLon = Double.NaN
    private var mode = Mode.NONE

    private enum class Mode { NONE, ALONG_ROUTE, OFF_ROUTE }

    /** Starts an along-route glide; [gapM] is displayed minus live offset. */
    fun beginAlongRoute(gapM: Double, atMs: Long) {
        if (!gapM.isFinite() || kotlin.math.abs(gapM) < settings.minGapM) {
            cancel()
            return
        }
        this.gapM = gapM
        start(kotlin.math.abs(gapM), atMs, Mode.ALONG_ROUTE)
    }

    /** Starts a free glide from the estimate at [lat],[lon] toward live raw fixes. */
    fun beginOffRoute(lat: Double, lon: Double, distanceM: Double, atMs: Long) {
        if (!lat.isFinite() || !lon.isFinite() || !(distanceM >= settings.minGapM)) {
            cancel()
            return
        }
        fromLat = lat
        fromLon = lon
        start(distanceM, atMs, Mode.OFF_ROUTE)
    }

    private fun start(distanceM: Double, atMs: Long, mode: Mode) {
        startedAtMs = atMs
        durationMs = (settings.minDurationMs + distanceM / settings.metresPerSecond * 1000.0)
            .toLong()
            .coerceIn(settings.minDurationMs, settings.maxDurationMs)
        this.mode = mode
    }

    fun cancel() {
        mode = Mode.NONE
        gapM = 0.0
        fromLat = Double.NaN
        fromLon = Double.NaN
    }

    val isAlongRoute: Boolean get() = mode == Mode.ALONG_ROUTE
    val isOffRoute: Boolean get() = mode == Mode.OFF_ROUTE

    /** True while the glide has time left at [nowMs]; ends itself when done. */
    fun isActive(nowMs: Long): Boolean {
        if (mode == Mode.NONE) return false
        if (nowMs - startedAtMs >= durationMs) {
            cancel()
            return false
        }
        return true
    }

    /** Along-route correction to add to the live offset at [nowMs]. */
    fun correctionM(nowMs: Long): Double =
        if (mode == Mode.ALONG_ROUTE) gapM * (1.0 - ease(progress(nowMs))) else 0.0

    /** Off-route display position between the estimate and the live fix. */
    fun blend(toLat: Double, toLon: Double, nowMs: Long): Pair<Double, Double> {
        if (mode != Mode.OFF_ROUTE) return toLat to toLon
        val e = ease(progress(nowMs))
        return (fromLat + e * (toLat - fromLat)) to (fromLon + e * (toLon - fromLon))
    }

    private fun progress(nowMs: Long): Double =
        if (durationMs <= 0L) 1.0 else ((nowMs - startedAtMs).toDouble() / durationMs).coerceIn(0.0, 1.0)

    companion object {
        /** Smoothstep: 0 → 0, 1 → 1, zero slope at both ends, monotonic between. */
        fun ease(t: Double): Double {
            val x = t.coerceIn(0.0, 1.0)
            return x * x * (3.0 - 2.0 * x)
        }
    }
}
