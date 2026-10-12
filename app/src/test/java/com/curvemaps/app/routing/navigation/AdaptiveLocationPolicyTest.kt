package com.curvemaps.app.routing.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLocationPolicyTest {
    private val allProviders = LocationProviderAvailability(gps = true, network = true, passive = true)

    @Test
    fun coldStartRequestsGpsAndNetworkImmediately() {
        val policy = AdaptiveLocationPolicy(startedAtElapsedMs = 1_000L)
        val acquiring = listOf(
            LocationProviderRequest(LocationFixProvider.GPS, 500L),
            LocationProviderRequest(LocationFixProvider.NETWORK, 1_000L),
        )

        assertEquals(acquiring, policy.requests(1_000L, allProviders))
        policy.recordFix(LocationFixProvider.NETWORK, 2_000L, speedMps = 0.0)
        assertEquals(acquiring, policy.requests(2_000L, allProviders))
        assertEquals(acquiring, policy.requests(13_001L, allProviders))
    }

    @Test
    fun healthyGpsIsSoleSubscriptionAndNetworkRestartsAfterGpsStalls() {
        val policy = AdaptiveLocationPolicy(startedAtElapsedMs = 1_000L)
        policy.recordFix(LocationFixProvider.GPS, 1_000L, speedMps = 0.0)
        assertEquals(
            listOf(LocationProviderRequest(LocationFixProvider.GPS, 500L)),
            policy.requests(1_000L, allProviders),
        )
        assertEquals(
            listOf(
                LocationProviderRequest(LocationFixProvider.GPS, 15_000L),
                LocationProviderRequest(LocationFixProvider.NETWORK, 1_000L),
            ),
            policy.requests(13_001L, allProviders),
        )
    }

    @Test
    fun offlineColdStartKeepsGpsAcquiringAfterStationaryCutoff() {
        val policy = AdaptiveLocationPolicy(startedAtElapsedMs = 1_000L)
        val gpsOnly = LocationProviderAvailability(gps = true, network = false, passive = false)
        for (now in listOf(1_000L, 13_001L, 61_001L, 180_000L)) {
            assertEquals(
                listOf(LocationProviderRequest(LocationFixProvider.GPS, 500L)),
                policy.requests(now, gpsOnly),
            )
        }
    }

    @Test
    fun freshGpsFixRecoversFromFallbackAndRestoresMovingRate() {
        val policy = AdaptiveLocationPolicy(startedAtElapsedMs = 0L)
        policy.requests(13_000L, allProviders)

        assertTrue(policy.recordFix(LocationFixProvider.GPS, 13_001L, speedMps = 8.0))
        assertEquals(
            listOf(LocationProviderRequest(LocationFixProvider.GPS, 500L)),
            policy.requests(13_001L, allProviders),
        )
    }

    @Test
    fun passiveIsAddedOnlyAfterNetworkStalls() {
        val policy = AdaptiveLocationPolicy(startedAtElapsedMs = 0L)
        policy.requests(13_000L, allProviders)
        policy.recordFix(LocationFixProvider.NETWORK, 14_000L, speedMps = 0.0)

        assertEquals(2, policy.requests(43_999L, allProviders).size)
        assertEquals(
            listOf(
                LocationProviderRequest(LocationFixProvider.GPS, 500L),
                LocationProviderRequest(LocationFixProvider.NETWORK, 1_000L),
                LocationProviderRequest(LocationFixProvider.PASSIVE, 5_000L),
            ),
            policy.requests(44_000L, allProviders),
        )
    }

    @Test
    fun approximatePermissionUsesNetworkThenPassiveWithoutGpsRequest() {
        val policy = AdaptiveLocationPolicy(startedAtElapsedMs = 0L)
        val approximateOnly = LocationProviderAvailability(gps = false, network = true, passive = true)

        assertEquals(
            listOf(LocationProviderRequest(LocationFixProvider.NETWORK, 1_000L)),
            policy.requests(0L, approximateOnly),
        )
        assertEquals(
            listOf(
                LocationProviderRequest(LocationFixProvider.NETWORK, 1_000L),
                LocationProviderRequest(LocationFixProvider.PASSIVE, 5_000L),
            ),
            policy.requests(30_000L, approximateOnly),
        )
    }

    @Test
    fun movingAndStationaryRatesUseElapsedRealtime() {
        val policy = AdaptiveLocationPolicy(startedAtElapsedMs = 1_000L)
        policy.recordFix(LocationFixProvider.GPS, 5_000L, speedMps = 7.0)
        policy.recordFix(LocationFixProvider.GPS, 65_001L, speedMps = 0.0)

        assertEquals(
            listOf(LocationProviderRequest(LocationFixProvider.GPS, 15_000L)),
            policy.requests(65_001L, allProviders),
        )
        policy.recordFix(LocationFixProvider.GPS, 66_000L, speedMps = 2.0)
        assertEquals(
            listOf(LocationProviderRequest(LocationFixProvider.GPS, 500L)),
            policy.requests(66_000L, allProviders),
        )
    }

    @Test
    fun rideKeepsHalfSecondGpsAfterStationaryCutoffWithZeroOrUnknownSpeed() {
        for (speedMps in listOf(0.0, -1.0, Double.NaN)) {
            val policy = AdaptiveLocationPolicy(startedAtElapsedMs = 1_000L, rideActive = true)
            for (now in listOf(1_000L, 61_000L, 61_001L, 180_000L)) {
                policy.recordFix(LocationFixProvider.GPS, now, speedMps)
                assertEquals(
                    listOf(LocationProviderRequest(LocationFixProvider.GPS, 500L)),
                    policy.requests(now, allProviders),
                )
            }
        }
    }

    @Test
    fun rideKeepsHalfSecondGpsDuringAnOutageAndRecoversWhileStopped() {
        val policy = AdaptiveLocationPolicy(startedAtElapsedMs = 1_000L, rideActive = true)
        policy.recordFix(LocationFixProvider.GPS, 61_001L, speedMps = 0.0)
        assertEquals(
            listOf(LocationProviderRequest(LocationFixProvider.GPS, 500L)),
            policy.requests(73_001L, allProviders),
        )
        assertEquals(
            listOf(
                LocationProviderRequest(LocationFixProvider.GPS, 500L),
                LocationProviderRequest(LocationFixProvider.NETWORK, 15_000L),
            ),
            policy.requests(73_002L, allProviders),
        )
        assertEquals(
            listOf(
                LocationProviderRequest(LocationFixProvider.GPS, 500L),
                LocationProviderRequest(LocationFixProvider.NETWORK, 15_000L),
                LocationProviderRequest(LocationFixProvider.PASSIVE, 15_000L),
            ),
            policy.requests(103_002L, allProviders),
        )
        policy.recordFix(LocationFixProvider.GPS, 103_003L, speedMps = 0.0)
        assertEquals(
            listOf(LocationProviderRequest(LocationFixProvider.GPS, 500L)),
            policy.requests(103_003L, allProviders),
        )
    }

    @Test
    fun monotonicGateRejectsStaleFutureAndDuplicateTimestamps() {
        val gate = MonotonicFixGate(maximumAgeMs = 30_000L)

        assertFalse(gate.accept(0L, 10_000L))
        assertFalse(gate.accept(10_001L, 10_000L))
        assertFalse(gate.accept(1L, 40_002L))
        assertTrue(gate.accept(9_000L, 10_000L))
        assertFalse(gate.accept(9_000L, 10_000L))
        assertFalse(gate.accept(8_999L, 10_000L))
        assertTrue(gate.accept(9_001L, 10_000L))
    }

    @Test
    fun freshestRecentSeedIgnoresStaleFixesAndPrefersGpsForTies() {
        val selected = freshestRecentProviderFix(
            listOf(
                ProviderFixTimestamp(LocationFixProvider.GPS, 10_000L),
                ProviderFixTimestamp(LocationFixProvider.NETWORK, 10_000L),
                ProviderFixTimestamp(LocationFixProvider.PASSIVE, 5_000L),
            ),
            nowElapsedMs = 10_100L,
            maximumAgeMs = 30_000L,
        )
        assertEquals(ProviderFixTimestamp(LocationFixProvider.GPS, 10_000L), selected)
        assertNull(
            freshestRecentProviderFix(
                listOf(ProviderFixTimestamp(LocationFixProvider.GPS, 1_000L)),
                nowElapsedMs = 31_001L,
                maximumAgeMs = 30_000L,
            ),
        )
    }
}
