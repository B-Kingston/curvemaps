package com.curvemaps.app.routing.navigation

import android.location.Location
import android.location.LocationListener
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsFixSourceOnDeviceTest {

    @Test
    fun recentGpsSeedImmediatelyStopsStartupNetworkRequest() {
        runBlocking {
            val client = FakeLocationProviderClient().apply {
                lastKnown[LocationFixProvider.GPS] = location(LocationFixProvider.GPS, 900L, speedMps = 0f)
            }
            val received = Channel<GpsFix>(Channel.UNLIMITED)
            val collection = launch(start = CoroutineStart.UNDISPATCHED) {
                GpsFixSource.fixes(client, fineLocationGranted = true, nowElapsedMs = { 1_000L })
                    .collect(received::send)
            }
            assertEquals(900L, withTimeout(2_000L) { received.receive() }.timestampMs)
            assertEquals(mapOf(LocationFixProvider.GPS to 500L), client.activeRequests)
            collection.cancelAndJoin()
            assertTrue(client.activeRequests.isEmpty())
            received.close()
        }
    }

    @Test
    fun coldStartEmitsNetworkFixImmediatelyThenSwitchesToGps() {
        runBlocking {
            val now = MutableElapsedClock(1_000L)
            val client = FakeLocationProviderClient()
            val received = Channel<GpsFix>(Channel.UNLIMITED)
            val collection = launch(start = CoroutineStart.UNDISPATCHED) {
                GpsFixSource.fixes(client, fineLocationGranted = true, nowElapsedMs = now::get)
                    .collect(received::send)
            }
            yield()

            assertEquals(
                mapOf(LocationFixProvider.GPS to 500L, LocationFixProvider.NETWORK to 1_000L),
                client.activeRequests,
            )
            now.value = 1_100L
            client.emit(location(LocationFixProvider.NETWORK, now.value, speedMps = 0f))
            assertEquals(now.value, withTimeout(2_000L) { received.receive() }.timestampMs)
            assertEquals(0, client.removedUpdateCount)

            now.value = 1_200L
            client.emit(location(LocationFixProvider.GPS, now.value, speedMps = 0f))
            assertEquals(now.value, withTimeout(2_000L) { received.receive() }.timestampMs)
            assertEquals(mapOf(LocationFixProvider.GPS to 500L), client.activeRequests)

            collection.cancelAndJoin()
            assertTrue(client.activeRequests.isEmpty())
            received.close()
        }
    }

    @Test
    fun fallbackRecoversToGpsDeduplicatesAndUnregisters() {
        runBlocking {
            val now = MutableElapsedClock(60_000L)
            val client = FakeLocationProviderClient()
            val received = Channel<GpsFix>(Channel.UNLIMITED)
            val collection = launch(start = CoroutineStart.UNDISPATCHED) {
                GpsFixSource.fixes(client, fineLocationGranted = true, nowElapsedMs = now::get)
                    .collect(received::send)
            }
            yield()

            assertEquals(
                mapOf(LocationFixProvider.GPS to 500L, LocationFixProvider.NETWORK to 1_000L),
                client.activeRequests,
            )

            client.setEnabled(LocationFixProvider.GPS, false)
            client.signalProviderState(LocationFixProvider.GPS)
            assertEquals(mapOf(LocationFixProvider.NETWORK to 1_000L), client.activeRequests)

            now.value = 61_000L
            client.emit(location(LocationFixProvider.NETWORK, 61_000L, speedMps = 4f))
            assertEquals(61_000L, withTimeout(2_000L) { received.receive() }.timestampMs)

            now.value = 91_000L
            client.setEnabled(LocationFixProvider.GPS, true)
            client.signalProviderState(LocationFixProvider.GPS)
            assertEquals(
                mapOf(
                    LocationFixProvider.GPS to 500L,
                    LocationFixProvider.NETWORK to 1_000L,
                    LocationFixProvider.PASSIVE to 5_000L,
                ),
                client.activeRequests,
            )

            now.value = 92_000L
            client.emit(location(LocationFixProvider.NETWORK, 91_000L, speedMps = 0f))
            assertEquals(91_000L, withTimeout(2_000L) { received.receive() }.timestampMs)
            client.emit(location(LocationFixProvider.GPS, 91_000L, speedMps = 0f))
            assertEquals(mapOf(LocationFixProvider.GPS to 500L), client.activeRequests)
            assertTrue(withTimeoutOrNull(150L) { received.receive() } == null)

            now.value = 93_000L
            client.emit(location(LocationFixProvider.NETWORK, 93_000L, speedMps = 0f))
            assertTrue(withTimeoutOrNull(150L) { received.receive() } == null)

            collection.cancelAndJoin()
            assertTrue(client.activeRequests.isEmpty())
            assertTrue(client.removedUpdateCount > 0)
            received.close()
        }
    }

    @Test
    fun approximateLocationDoesNotAttemptGpsProvider() {
        runBlocking {
            val client = FakeLocationProviderClient()
            val received = Channel<GpsFix>(Channel.UNLIMITED)
            val collection = launch(start = CoroutineStart.UNDISPATCHED) {
                GpsFixSource.fixes(client, fineLocationGranted = false, nowElapsedMs = { 1_000L })
                    .collect(received::send)
            }
            yield()

            assertEquals(mapOf(LocationFixProvider.NETWORK to 1_000L), client.activeRequests)
            collection.cancelAndJoin()
            assertTrue(client.activeRequests.isEmpty())
            received.close()
        }
    }

    @Test
    fun passiveSubscriptionAcceptsFixNamedForItsOriginalProvider() {
        runBlocking {
            val client = FakeLocationProviderClient().apply {
                setEnabled(LocationFixProvider.GPS, false)
                setEnabled(LocationFixProvider.NETWORK, false)
            }
            val received = Channel<GpsFix>(Channel.UNLIMITED)
            val collection = launch(start = CoroutineStart.UNDISPATCHED) {
                GpsFixSource.fixes(client, fineLocationGranted = false, nowElapsedMs = { 1_000L })
                    .collect(received::send)
            }
            yield()

            assertEquals(mapOf(LocationFixProvider.PASSIVE to 5_000L), client.activeRequests)
            // PASSIVE relays a GPS fix but Location.provider remains "gps".
            client.emit(location(LocationFixProvider.GPS, 1_000L, speedMps = 0f))
            assertEquals(1_000L, withTimeout(2_000L) { received.receive() }.timestampMs)

            collection.cancelAndJoin()
            assertTrue(client.activeRequests.isEmpty())
            received.close()
        }
    }

    @Test
    fun gpsPermissionFailureFallsBackToNetwork() {
        runBlocking {
            val now = MutableElapsedClock(1_000L)
            val client = FakeLocationProviderClient().apply {
                deniedRequests += LocationFixProvider.GPS
            }
            val received = Channel<GpsFix>(Channel.UNLIMITED)
            val collection = launch(start = CoroutineStart.UNDISPATCHED) {
                GpsFixSource.fixes(client, fineLocationGranted = true, nowElapsedMs = now::get)
                    .collect(received::send)
            }
            yield()

            assertEquals(mapOf(LocationFixProvider.NETWORK to 1_000L), client.activeRequests)
            now.value = 2_000L
            client.emit(location(LocationFixProvider.NETWORK, 2_000L, speedMps = 3f))
            assertEquals(2_000L, withTimeout(2_000L) { received.receive() }.timestampMs)

            collection.cancelAndJoin()
            assertTrue(client.activeRequests.isEmpty())
            received.close()
        }
    }

    @Test
    fun stoppedRideKeepsFastGpsWhilePlannerSlowsDown() {
        runBlocking {
            for (rideActive in listOf(false, true)) {
                val now = MutableElapsedClock(1_000L)
                val client = FakeLocationProviderClient()
                val received = Channel<GpsFix>(Channel.UNLIMITED)
                val collection = launch(start = CoroutineStart.UNDISPATCHED) {
                    GpsFixSource.fixes(
                        client,
                        fineLocationGranted = true,
                        nowElapsedMs = now::get,
                        rideActive = rideActive,
                    ).collect(received::send)
                }
                try {
                    yield()
                    for (timestamp in listOf(1_000L, 61_000L, 61_001L, 180_000L)) {
                        now.value = timestamp
                        client.emit(location(LocationFixProvider.GPS, timestamp, speedMps = 0f))
                        assertEquals(timestamp, withTimeout(2_000L) { received.receive() }.timestampMs)
                        val interval = if (rideActive || timestamp <= 61_000L) 500L else 15_000L
                        assertEquals(mapOf(LocationFixProvider.GPS to interval), client.activeRequests)
                    }
                    now.value = 180_001L
                    client.emit(location(LocationFixProvider.GPS, now.value, speedMps = 4f))
                    assertEquals(now.value, withTimeout(2_000L) { received.receive() }.timestampMs)
                    assertEquals(mapOf(LocationFixProvider.GPS to 500L), client.activeRequests)
                } finally {
                    collection.cancelAndJoin()
                    received.close()
                }
                assertTrue(client.activeRequests.isEmpty())
            }
        }
    }

    private fun location(
        provider: LocationFixProvider,
        elapsedRealtimeMs: Long,
        speedMps: Float,
    ): Location = Location(provider.platformName).apply {
        latitude = -27.47
        longitude = 153.02
        accuracy = 5f
        speed = speedMps
        elapsedRealtimeNanos = elapsedRealtimeMs * 1_000_000L
    }

    private class MutableElapsedClock(var value: Long) {
        fun get(): Long = value
    }

    private class FakeLocationProviderClient : LocationProviderClient {
        private val enabled = LocationFixProvider.entries.associateWith { true }.toMutableMap()
        private val requests = mutableMapOf<LocationFixProvider, Long>()
        val deniedRequests = mutableSetOf<LocationFixProvider>()
        val lastKnown = mutableMapOf<LocationFixProvider, Location>()
        private var listener: LocationListener? = null
        var removedUpdateCount: Int = 0
            private set

        val activeRequests: Map<LocationFixProvider, Long>
            get() = requests.toMap()

        override fun isProviderEnabled(provider: LocationFixProvider): Boolean = enabled[provider] == true

        override fun lastKnownLocation(provider: LocationFixProvider): Location? = lastKnown[provider]

        override fun requestLocationUpdates(
            provider: LocationFixProvider,
            intervalMs: Long,
            listener: LocationListener,
        ) {
            if (provider in deniedRequests) throw SecurityException("Permission denied")
            this.listener = listener
            requests[provider] = intervalMs
        }

        override fun removeUpdates(listener: LocationListener) {
            if (this.listener === listener) {
                requests.clear()
                removedUpdateCount++
            }
        }

        fun setEnabled(provider: LocationFixProvider, isEnabled: Boolean) {
            enabled[provider] = isEnabled
        }

        fun signalProviderState(provider: LocationFixProvider) {
            val target = listener ?: error("No location listener registered")
            if (enabled[provider] == true) {
                target.onProviderEnabled(provider.platformName)
            } else {
                target.onProviderDisabled(provider.platformName)
            }
        }

        fun emit(location: Location) {
            (listener ?: error("No location listener registered")).onLocationChanged(location)
        }
    }
}
