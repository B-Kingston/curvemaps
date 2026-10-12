package com.curvemaps.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import com.graphhopper.util.shapes.GHPoint
import com.curvemaps.app.routing.GraphHopperRouter
import com.curvemaps.app.routing.RideFactors
import com.curvemaps.app.routing.RideStyle
import com.curvemaps.app.routing.navigation.RouteTrackFactory
import com.curvemaps.app.routing.suggestion.BatteryState
import com.curvemaps.app.routing.suggestion.DiversionPlanner
import com.curvemaps.app.routing.suggestion.DiversionProposal
import com.curvemaps.app.routing.suggestion.DiversionSearchRequest
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The two routing calls behind one ride-suggestion check, on a single
 * background-priority thread so a check never competes with rendering or a
 * rider-initiated route search for CPU.
 */
internal class RideSuggestionSearch : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            },
            "ride-suggest",
        ).apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    suspend fun search(
        router: GraphHopperRouter,
        request: DiversionSearchRequest,
        destination: GHPoint,
        blockUnpaved: Boolean,
        avoidMotorways: Boolean,
        factors: RideFactors = RideFactors.NONE,
    ): DiversionProposal? = withContext(dispatcher) {
        val baseline = request.baseline
        val (startLat, startLon) = baseline.positionAt(request.windowStartM)
        val (endLat, endLon) = baseline.positionAt(request.windowEndM)
        // Two stops twistier (one calibrated style) explores, with its
        // alternatives; the top stops explore Max twisty's own alternatives.
        val exploreLevel = (request.style.level + EXPLORE_STOPS).coerceAtMost(RideStyle.MAX_LEVEL)
        val cards = router.route(
            GHPoint(startLat, startLon),
            GHPoint(endLat, endLon),
            exploreLevel.toDouble(),
            blockUnpaved,
            avoidMotorways,
            alternatives = true,
            factors = factors,
        ).routes.map(RouteTrackFactory::fromPath)
        ensureActive()
        val pick = DiversionPlanner.bestWindowCard(baseline, request.windowStartM, request.windowEndM, cards)
            ?: return@withContext null
        val shaping = DiversionPlanner.shapingPoints(pick.card, pick.span)
        val path = router.route(
            GHPoint(request.riderLat, request.riderLon),
            destination,
            request.style.level.toDouble(),
            blockUnpaved,
            avoidMotorways,
            shaping.map { (lat, lon) -> GHPoint(lat, lon) },
            factors = factors,
        ).routes.first()
        ensureActive()
        val replacement = RouteTrackFactory.fromPath(path)
        DiversionPlanner.proposal(
            id = request.proposalId,
            baseline = baseline,
            riderOffsetM = request.riderOffsetM,
            replacement = replacement,
            speedMps = request.speedMps,
            path = path,
            viaPoints = shaping,
        )
    }

    override fun close() {
        dispatcher.close()
    }

    private companion object {
        /** Stops between the rider's style and the one a check explores. */
        const val EXPLORE_STOPS = 2
    }
}

/** Battery saver and level, read only when a check is due (sticky broadcast, no receiver). */
internal fun readBatteryState(context: Context): BatteryState {
    val power = context.getSystemService(PowerManager::class.java)
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    return BatteryState(
        powerSaveMode = power?.isPowerSaveMode == true,
        levelPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
    )
}

/** Re-reads the battery at most once a minute; the inputs are polled per GPS fix. */
internal class BatteryStateCache(private val context: Context) {
    private var cached = BatteryState()
    private var readAtMs = Long.MIN_VALUE

    fun get(nowMs: Long): BatteryState {
        if (readAtMs == Long.MIN_VALUE || nowMs - readAtMs >= REFRESH_MS) {
            cached = runCatching { readBatteryState(context) }.getOrDefault(BatteryState())
            readAtMs = nowMs
        }
        return cached
    }

    private companion object {
        const val REFRESH_MS = 60_000L
    }
}
