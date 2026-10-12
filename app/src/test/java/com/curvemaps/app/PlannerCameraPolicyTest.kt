package com.curvemaps.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerCameraPolicyTest {

    @Test
    fun firstFixCentresAnUntouchedPlanner() {
        assertTrue(centre())
    }

    @Test
    fun aLateFirstFixNeverPullsTheCameraOffAShownRoute() {
        // Cold GPS: START framed the route before the first fix arrived.
        assertFalse(centre(routeShown = true))
        // A slider move onto an unsettled stop briefly drops the result.
        assertFalse(centre(planSearching = true))
        // The route fit spent the one-shot; Back/END later clears the route.
        assertFalse(centre(alreadySpent = true))
    }

    @Test
    fun followAndGuidanceOwnTheirOwnCamera() {
        assertFalse(centre(followMe = true))
        assertFalse(centre(guidance = true))
    }

    @Test
    fun everyOwnerAloneBlocksTheOneShot() {
        val flags = 5
        for (mask in 1 until (1 shl flags)) {
            assertFalse(
                "mask=$mask",
                PlannerCameraPolicy.centerOnFirstFix(
                    alreadySpent = mask and 1 != 0,
                    routeShown = mask and 2 != 0,
                    planSearching = mask and 4 != 0,
                    followMe = mask and 8 != 0,
                    guidance = mask and 16 != 0,
                ),
            )
        }
    }

    @Test
    fun framingARouteReleasesThePlannerLock() {
        assertFalse(PlannerCameraPolicy.followMeAfterRouteFit(followMe = true, routeFitted = true))
        assertFalse(PlannerCameraPolicy.followMeAfterRouteFit(followMe = false, routeFitted = true))
        assertTrue(PlannerCameraPolicy.followMeAfterRouteFit(followMe = true, routeFitted = false))
    }

    private fun centre(
        alreadySpent: Boolean = false,
        routeShown: Boolean = false,
        planSearching: Boolean = false,
        followMe: Boolean = false,
        guidance: Boolean = false,
    ) = PlannerCameraPolicy.centerOnFirstFix(alreadySpent, routeShown, planSearching, followMe, guidance)
}
