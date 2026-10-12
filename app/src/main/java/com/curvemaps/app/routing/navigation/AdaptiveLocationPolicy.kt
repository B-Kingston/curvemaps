package com.curvemaps.app.routing.navigation

/** LocationManager providers understood by the adaptive location stream. */
internal enum class LocationFixProvider(val platformName: String) {
    GPS("gps"),
    NETWORK("network"),
    PASSIVE("passive");

    companion object {
        fun fromPlatformName(name: String?): LocationFixProvider? =
            entries.firstOrNull { it.platformName == name }
    }
}

internal data class LocationProviderAvailability(
    val gps: Boolean,
    val network: Boolean,
    val passive: Boolean,
) {
    operator fun get(provider: LocationFixProvider): Boolean = when (provider) {
        LocationFixProvider.GPS -> gps
        LocationFixProvider.NETWORK -> network
        LocationFixProvider.PASSIVE -> passive
    }
}

internal data class LocationProviderRequest(
    val provider: LocationFixProvider,
    val intervalMs: Long,
)

internal data class ProviderFixTimestamp(
    val provider: LocationFixProvider,
    val elapsedRealtimeMs: Long,
)

/**
 * GPS-first provider policy. Healthy GPS is the only active subscription.
 * Network starts immediately during GPS acquisition, and again if GPS stalls
 * or is disabled; passive is added when network is unavailable or stalls.
 * GPS requests a fix every 500 ms while moving or acquiring satellites.
 * Ride mode keeps that rate when stopped or during outages. Outside ride mode,
 * GPS slows down when stationary and uses a slow probe during outages.
 * A fresh GPS fix restores the primary-only plan.
 *
 * All time values are elapsed-realtime milliseconds. This policy is confined
 * to a single subscription, so its small per-provider timestamp map is bounded
 * to the three platform providers.
 */
internal class AdaptiveLocationPolicy(
    private val startedAtElapsedMs: Long,
    private val rideActive: Boolean = false,
) {
    private val lastProviderFixMs = mutableMapOf<LocationFixProvider, Long>()
    private var lastMovingFixElapsedMs = startedAtElapsedMs
    private var fallbackStartedElapsedMs: Long? = null

    fun recordFix(
        provider: LocationFixProvider,
        elapsedRealtimeMs: Long,
        speedMps: Double,
    ): Boolean {
        val previous = lastProviderFixMs[provider]
        if (elapsedRealtimeMs <= 0L || (previous != null && elapsedRealtimeMs <= previous)) return false
        lastProviderFixMs[provider] = elapsedRealtimeMs
        if (speedMps.isFinite() && speedMps >= MOVING_SPEED_MPS) {
            lastMovingFixElapsedMs = elapsedRealtimeMs
        }
        if (provider == LocationFixProvider.GPS) fallbackStartedElapsedMs = null
        return true
    }

    fun requests(
        nowElapsedMs: Long,
        available: LocationProviderAvailability,
    ): List<LocationProviderRequest> {
        val activeInterval = if (nowElapsedMs - lastMovingFixElapsedMs > STATIONARY_CUTOFF_MS) {
            SLOW_INTERVAL_MS
        } else {
            MOVING_INTERVAL_MS
        }
        val gpsActiveInterval = if (rideActive || activeInterval == MOVING_INTERVAL_MS) {
            GPS_INTERVAL_MS
        } else {
            SLOW_INTERVAL_MS
        }
        val gpsFix = lastProviderFixMs[LocationFixProvider.GPS]
        val gpsTimeout = maxOf(GPS_FALLBACK_TIMEOUT_MS, gpsActiveInterval * 2 + GPS_TIMEOUT_MARGIN_MS)
        val gpsAge = gpsFix?.let { nowElapsedMs - it }
        val fallbackStarted = fallbackStartedElapsedMs
        val gpsHealthy = available.gps && gpsAge != null && gpsAge in 0..gpsTimeout &&
            (fallbackStarted == null || gpsFix >= fallbackStarted)

        if (gpsHealthy) {
            return listOf(LocationProviderRequest(LocationFixProvider.GPS, gpsActiveInterval))
        }

        if (fallbackStarted == null) fallbackStartedElapsedMs = nowElapsedMs
        val requests = mutableListOf<LocationProviderRequest>()
        if (available.gps) {
            // A cold receiver has not stalled: it is still acquiring satellites.
            // Keep requesting frequent fixes even if acquisition takes a minute.
            val gpsInterval = if (rideActive || gpsFix == null) GPS_INTERVAL_MS else GPS_PROBE_INTERVAL_MS
            requests += LocationProviderRequest(LocationFixProvider.GPS, gpsInterval)
        }

        if (available.network) {
            requests += LocationProviderRequest(LocationFixProvider.NETWORK, activeInterval)
            val networkFix = lastProviderFixMs[LocationFixProvider.NETWORK]
            val networkStalledSince = maxOf(fallbackStartedElapsedMs ?: nowElapsedMs, networkFix ?: 0L)
            if (available.passive && nowElapsedMs - networkStalledSince >= FALLBACK_PROVIDER_TIMEOUT_MS) {
                requests += LocationProviderRequest(
                    LocationFixProvider.PASSIVE,
                    passiveInterval(activeInterval),
                )
            }
        } else if (available.passive) {
            requests += LocationProviderRequest(
                LocationFixProvider.PASSIVE,
                passiveInterval(activeInterval),
            )
        }

        return requests.sortedBy { it.provider.ordinal }
    }

    private fun passiveInterval(activeInterval: Long): Long =
        if (activeInterval == MOVING_INTERVAL_MS) PASSIVE_FALLBACK_INTERVAL_MS else SLOW_INTERVAL_MS

    private companion object {
        const val GPS_INTERVAL_MS = 500L
        const val MOVING_INTERVAL_MS = 1_000L
        const val SLOW_INTERVAL_MS = 15_000L
        const val PASSIVE_FALLBACK_INTERVAL_MS = 5_000L
        const val STATIONARY_CUTOFF_MS = 60_000L
        const val GPS_FALLBACK_TIMEOUT_MS = 12_000L
        const val GPS_TIMEOUT_MARGIN_MS = 2_000L
        const val GPS_PROBE_INTERVAL_MS = 15_000L
        const val FALLBACK_PROVIDER_TIMEOUT_MS = 30_000L
        const val MOVING_SPEED_MPS = 1.0
    }
}

/** A recent monotonic fix timestamp that is newer than every emitted fix. */
internal class MonotonicFixGate(private val maximumAgeMs: Long) {
    private var lastEmittedElapsedMs: Long? = null

    fun accept(elapsedRealtimeMs: Long, nowElapsedMs: Long): Boolean {
        val age = nowElapsedMs - elapsedRealtimeMs
        if (elapsedRealtimeMs <= 0L || age !in 0..maximumAgeMs) return false
        val previous = lastEmittedElapsedMs
        if (previous != null && elapsedRealtimeMs <= previous) return false
        lastEmittedElapsedMs = elapsedRealtimeMs
        return true
    }
}

/** Selects the freshest recent last-known fix; ties prefer GPS over fallback providers. */
internal fun freshestRecentProviderFix(
    candidates: Iterable<ProviderFixTimestamp>,
    nowElapsedMs: Long,
    maximumAgeMs: Long,
): ProviderFixTimestamp? = candidates
    .asSequence()
    .filter { candidate ->
        candidate.elapsedRealtimeMs > 0L &&
            nowElapsedMs - candidate.elapsedRealtimeMs in 0..maximumAgeMs
    }
    .maxWithOrNull(
        compareBy<ProviderFixTimestamp> { it.elapsedRealtimeMs }
            .thenBy { providerPreference(it.provider) },
    )

private fun providerPreference(provider: LocationFixProvider): Int = when (provider) {
    LocationFixProvider.GPS -> 2
    LocationFixProvider.NETWORK -> 1
    LocationFixProvider.PASSIVE -> 0
}
