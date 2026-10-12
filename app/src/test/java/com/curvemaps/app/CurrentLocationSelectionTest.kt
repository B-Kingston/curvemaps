package com.curvemaps.app

import com.curvemaps.app.routing.navigation.GpsFix
import com.curvemaps.app.storage.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentLocationSelectionTest {

    @Test
    fun grantedRequestAcceptsOnlyAFreshFixAndCompletes() {
        val waiting = CurrentLocationSelectionState()
            .beginRequest()
            .permissionResult(requestId = 1L, granted = true)
        val request = (waiting.status as CurrentLocationStatus.WaitingForFix).request
        val fix = GpsFix(
            lat = -27.46981,
            lon = 153.02512,
            timestampMs = 99_999L,
        )

        val endpoint = waiting.currentEndpoint(request, fix, nowElapsedMs = 100_000L)
        assertEquals(CURRENT_LOCATION_LABEL, endpoint?.label)
        assertEquals(GeoPoint(fix.lat, fix.lon), endpoint?.point)
        assertEquals(CurrentLocationStatus.Idle, waiting.complete(request.id).status)
    }

    @Test
    fun deniedPermissionShowsAnUnavailableStateWithoutAStalePoint() {
        val denied = CurrentLocationSelectionState()
            .beginRequest()
            .permissionResult(requestId = 1L, granted = false)

        assertEquals(
            CurrentLocationUnavailableReason.PERMISSION_DENIED,
            (denied.status as CurrentLocationStatus.Unavailable).reason,
        )
        assertNull(denied.currentPoint(
            request = CurrentLocationRequest(id = 1L, inputRevision = denied.inputRevision),
            fix = null,
            nowElapsedMs = 100_000L,
        ))
    }

    @Test
    fun missingOrStaleFixEndsAsUnavailableInsteadOfReusingAnOldEndpoint() {
        val waiting = CurrentLocationSelectionState()
            .beginRequest()
            .permissionResult(requestId = 1L, granted = true)
        val request = (waiting.status as CurrentLocationStatus.WaitingForFix).request
        val stale = GpsFix(lat = -27.0, lon = 153.0, timestampMs = 69_999L)

        assertNull(waiting.currentPoint(request, fix = null, nowElapsedMs = 100_000L))
        assertNull(waiting.currentPoint(request, stale, nowElapsedMs = 100_000L))
        assertEquals(
            CurrentLocationUnavailableReason.NO_RECENT_FIX,
            waiting.noRecentFix(request.id).let { (it.status as CurrentLocationStatus.Unavailable).reason },
        )
    }

    @Test
    fun editingFromInvalidatesAnOutstandingFixAndRejectsInvalidCoordinates() {
        val waiting = CurrentLocationSelectionState()
            .beginRequest()
            .permissionResult(requestId = 1L, granted = true)
        val request = (waiting.status as CurrentLocationStatus.WaitingForFix).request
        val edited = waiting.cancel()
        val now = 100_000L

        assertNull(edited.currentPoint(
            request,
            GpsFix(lat = -27.0, lon = 153.0, timestampMs = now),
            nowElapsedMs = now,
        ))
        assertNull(waiting.currentPoint(
            request,
            GpsFix(lat = Double.NaN, lon = 153.0, timestampMs = now),
            nowElapsedMs = now,
        ))
        assertTrue(edited.inputRevision > request.inputRevision)
    }

    @Test
    fun theLocationKeyIsInUseOnlyWhileFromHoldsAResolvedCurrentLocation() {
        assertTrue(currentLocationInFrom(CURRENT_LOCATION_LABEL, fromResolved = true))
        // Still looking for a fix, or the rider typed over it: not in use.
        assertEquals(false, currentLocationInFrom(CURRENT_LOCATION_LABEL, fromResolved = false))
        assertEquals(false, currentLocationInFrom("Palmwoods", fromResolved = true))
        assertEquals(false, currentLocationInFrom("", fromResolved = false))
    }

    @Test
    fun leavingFromKeepsTheCurrentLocationUnlessAnotherPlaceWasWritten() {
        assertTrue(keepsCurrentLocationOnLeave(CURRENT_LOCATION_LABEL))
        assertTrue(keepsCurrentLocationOnLeave(""))
        assertTrue(keepsCurrentLocationOnLeave("  "))
        assertEquals(false, keepsCurrentLocationOnLeave("Palmwoods"))
        assertEquals(false, keepsCurrentLocationOnLeave("-27.47,153.02"))
    }
}
