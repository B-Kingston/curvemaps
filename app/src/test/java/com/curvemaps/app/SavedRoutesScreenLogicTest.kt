package com.curvemaps.app

import com.curvemaps.app.storage.SavedRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure pieces of the saved-routes screen and the GPX preview timeline. */
class SavedRoutesScreenLogicTest {

    @Test
    fun `ride count reads naturally for none, one and many`() {
        assertEquals("0 rides", rideCountLabel(0))
        assertEquals("1 ride", rideCountLabel(1))
        assertEquals("3 rides", rideCountLabel(3))
    }

    @Test
    fun `a ride planned with no extras has no chips`() {
        assertTrue(savedRouteFactors(route()).isEmpty())
    }

    @Test
    fun `chips follow the stored planning choices in a fixed order`() {
        val all = route(
            avoidMotorways = true,
            blockUnpaved = true,
            includeElevation = true,
            avoidStopsAndTowns = true,
        )
        assertEquals(
            listOf(
                SavedRouteFactor.AvoidMotorways,
                SavedRouteFactor.BlockUnpaved,
                SavedRouteFactor.Hills,
                SavedRouteFactor.AvoidStopsAndTowns,
            ),
            savedRouteFactors(all),
        )
        assertEquals(listOf(SavedRouteFactor.Hills), savedRouteFactors(route(includeElevation = true)))
    }

    @Test
    fun `chip labels reuse the route settings wording`() {
        assertEquals("Avoid motorways", SavedRouteFactor.AvoidMotorways.label)
        assertEquals("Block unpaved roads", SavedRouteFactor.BlockUnpaved.label)
        assertEquals(INCLUDE_ELEVATION_TITLE, SavedRouteFactor.Hills.label)
        assertEquals(AVOID_STOPS_AND_TOWNS_TITLE, SavedRouteFactor.AvoidStopsAndTowns.label)
    }

    @Test
    fun `milestone titles number waypoints like their timeline badge`() {
        assertEquals("Route start", milestoneTitle(null, 0, 5))
        assertEquals("Waypoint 1", milestoneTitle(null, 1, 5))
        assertEquals("Waypoint 3", milestoneTitle(null, 3, 5))
        assertEquals("Route finish", milestoneTitle(null, 4, 5))
        assertEquals("Mount Glorious", milestoneTitle("Mount Glorious", 2, 5))
    }

    @Test
    fun `the preview sheet and the map padding agree on how much of the screen the sheet takes`() {
        // The padded map must leave the focused milestone above the sheet.
        val sheet = GPX_PREVIEW_SHEET_FRACTION.toDouble()
        assertTrue(GPX_PREVIEW_MAP_PADDING_FRACTION >= sheet - 0.1)
        assertTrue(GPX_PREVIEW_MAP_PADDING_FRACTION <= sheet)
    }

    private fun route(
        avoidMotorways: Boolean = false,
        blockUnpaved: Boolean = false,
        includeElevation: Boolean = false,
        avoidStopsAndTowns: Boolean = false,
    ) = SavedRoute(
        id = 1L,
        fromName = "A",
        fromLat = -27.0,
        fromLon = 153.0,
        toName = "B",
        toLat = -27.5,
        toLon = 151.9,
        distanceMeters = 1_000.0,
        durationMillis = 60_000L,
        complexity = 0f,
        blockUnpaved = blockUnpaved,
        avoidMotorways = avoidMotorways,
        geometry = "",
        createdAtMillis = 1L,
        includeElevation = includeElevation,
        avoidStopsAndTowns = avoidStopsAndTowns,
    )
}

class RelativeCommentTimeTest {
    @org.junit.Test
    fun `a comment under a minute old reads just now`() {
        val now = 1_000_000_000L
        org.junit.Assert.assertEquals("just now", relativeCommentTime(now, now))
        org.junit.Assert.assertEquals("just now", relativeCommentTime(now - 59_000L, now))
    }
}
