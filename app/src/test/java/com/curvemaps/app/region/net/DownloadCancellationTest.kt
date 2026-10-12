package com.curvemaps.app.region.net

import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class DownloadCancellationTest {
    private class Connection : HttpURLConnection(URL("http://localhost")) {
        var disconnected = false
        override fun disconnect() { disconnected = true }
        override fun connect() {}
        override fun usingProxy() = false
    }

    @Test fun cancellationBeforeConnectRejectsTheLateConnection() {
        val cancellation = DownloadCancellation()
        cancellation.cancel()
        val connection = Connection()
        try {
            cancellation.attach(connection)
            fail("cancelled transfer attached")
        } catch (_: DownloadCancelledException) {}
        assertTrue(connection.disconnected)
    }

    @Test fun cancellationOnlyDisconnectsItsOwnTransfer() {
        val old = DownloadCancellation()
        val fresh = DownloadCancellation()
        val first = Connection()
        val second = Connection()
        old.attach(first)
        fresh.attach(second)
        old.cancel()
        assertTrue(first.disconnected)
        assertFalse(second.disconnected)
    }

    @Test fun staleCleanupCannotDetachARetryConnection() {
        val token = DownloadCancellation()
        val first = Connection()
        val retry = Connection()
        token.attach(first)
        token.detach(first)
        token.attach(retry)
        token.detach(first)
        token.cancel()
        assertTrue(retry.disconnected)
    }
}
