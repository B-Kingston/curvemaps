package com.curvemaps.app.routing.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraSeedTest {

    @Test
    fun theNewestProviderPositionWinsHoweverOld() {
        val gps = CameraSeed(-27.47, 153.03, elapsedRealtimeMs = 1_000L)
        val network = CameraSeed(-27.50, 153.00, elapsedRealtimeMs = 90_000_000L)
        assertEquals(network, newestCameraSeed(listOf(gps, network)))
        assertEquals(network, newestCameraSeed(listOf(network, gps)))
    }

    @Test
    fun noPositionMeansNoSeed() {
        assertNull(newestCameraSeed(emptyList()))
    }

    @Test
    fun unsetOrImpossiblePositionsAreIgnored() {
        val real = CameraSeed(-16.92, 145.77, elapsedRealtimeMs = 5L)
        val invalid = listOf(
            CameraSeed(0.0, 0.0, elapsedRealtimeMs = 50L),
            CameraSeed(Double.NaN, 145.0, elapsedRealtimeMs = 60L),
            CameraSeed(91.0, 145.0, elapsedRealtimeMs = 70L),
            CameraSeed(-27.0, 181.0, elapsedRealtimeMs = 80L),
        )
        assertEquals(real, newestCameraSeed(invalid + real))
        assertNull(newestCameraSeed(invalid))
    }
}
