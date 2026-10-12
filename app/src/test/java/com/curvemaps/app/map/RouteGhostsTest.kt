package com.curvemaps.app.map

import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.LineString

/**
 * The planner's ghost history and its GeoJSON: what the map displayed is what
 * history follows — not slider events, not re-renders — and a new plan,
 * endpoint edit, idle or guidance starts clean.
 */
class RouteGhostsTest {

    @Test
    fun theFirstDisplayedRouteOfAPlanHasNoGhosts() {
        val history = RouteGhostHistory<String>()
        val first = path(0)
        history.onDisplayed(PLAN, first)
        assertTrue(history.ghosts().isEmpty())
        assertEquals(PLAN, history.currentPlan())
    }

    @Test
    fun thePreviousTwoDisplayedRoutesAreKeptNewestFirst() {
        val history = RouteGhostHistory<String>()
        val shown = (0..3).map { path(it) }
        shown.forEach { history.onDisplayed(PLAN, it) }
        assertEquals(listOf(shown[2], shown[1]), history.ghosts())
    }

    @Test
    fun redisplayingTheSameRouteObjectRecordsNothing() {
        val history = RouteGhostHistory<String>()
        val first = path(0)
        val second = path(1)
        history.onDisplayed(PLAN, first)
        history.onDisplayed(PLAN, second)
        val afterSwitch = history.ghosts()
        history.onDisplayed(PLAN, second)
        assertSame("a re-render must not rebuild or grow the history", afterSwitch, history.ghosts())
        assertEquals(listOf(first), history.ghosts())
    }

    @Test
    fun anEqualReRouteOfTheRideOnScreenIsNotADisplayedChange() {
        val history = RouteGhostHistory<String>()
        val first = path(0)
        val second = path(1)
        history.onDisplayed(PLAN, first)
        history.onDisplayed(PLAN, second)
        // The release re-route of the style already shown: an equal path.
        history.onDisplayed(PLAN, equalPath(second))
        assertEquals(listOf(first), history.ghosts())
    }

    @Test
    fun differentInteriorRoadsWithEqualEndpointsRemainDifferentRides() {
        val history = RouteGhostHistory<String>()
        fun corridor(latitude: Double) = ResponsePath().setPoints(PointList().apply {
            add(-27.0, 153.0)
            add(latitude, 153.01)
            add(-27.02, 153.02)
        })
        val first = corridor(-27.01)
        val second = corridor(-27.015)
        history.onDisplayed(PLAN, first)
        history.onDisplayed(PLAN, second)
        assertEquals(listOf(first), history.ghosts())
    }

    @Test
    fun aTransientBlankKeepsTheHistoryAndThePlan() {
        val history = RouteGhostHistory<String>()
        val first = path(0)
        val second = path(1)
        history.onDisplayed(PLAN, first)
        history.onDisplayed(PLAN, second)
        history.onDisplayed(PLAN, null)
        assertEquals(listOf(first), history.ghosts())
        assertEquals(PLAN, history.currentPlan())
    }

    @Test
    fun aNewPlanStartsWithoutThePreviousPlansGhosts() {
        val history = RouteGhostHistory<String>()
        history.onDisplayed(PLAN, path(0))
        history.onDisplayed(PLAN, path(1))
        history.onDisplayed(OTHER_PLAN, path(2))
        assertTrue(history.ghosts().isEmpty())
        assertEquals(OTHER_PLAN, history.currentPlan())
    }

    @Test
    fun aPlanKeyChangeWhileTheOldRouteIsStillShownDoesNotAdoptIt() {
        val history = RouteGhostHistory<String>()
        val old = path(0)
        history.onDisplayed(PLAN, path(1))
        history.onDisplayed(PLAN, old)
        // A new plan was submitted and the old route is still on screen while
        // its search runs: the old route belongs to the old plan.
        history.onDisplayed(OTHER_PLAN, old)
        assertEquals(PLAN, history.currentPlan())
        assertTrue(history.ghosts().isEmpty())
    }

    @Test
    fun editingTheEndpointsDropsGhostsAndDetachesTheShownRoute() {
        val history = RouteGhostHistory<String>()
        val first = path(0)
        val second = path(1)
        history.onDisplayed(PLAN, first)
        history.onDisplayed(PLAN, second)
        history.onDisplayed(null, second)
        assertTrue(history.ghosts().isEmpty())
        assertNull(history.currentPlan())
        history.onDisplayed(OTHER_PLAN, second)
        assertTrue("the detached route must not become the next plan's ghost", history.ghosts().isEmpty())
    }

    @Test
    fun returningToAnEarlierStopMakesTheStopJustLeftTheNewestGhost() {
        val history = RouteGhostHistory<String>()
        val stopZero = path(0)
        val stopOne = path(1)
        history.onDisplayed(PLAN, stopZero)
        history.onDisplayed(PLAN, stopOne)
        history.onDisplayed(PLAN, stopZero)
        assertEquals(listOf(stopOne), history.ghosts())
    }

    @Test
    fun clearDropsEveryGhostAndThePlan() {
        val history = RouteGhostHistory<String>()
        history.onDisplayed(PLAN, path(0))
        history.onDisplayed(PLAN, path(1))
        history.clear()
        assertTrue(history.ghosts().isEmpty())
        assertNull(history.currentPlan())
    }

    @Test
    fun ghostFeaturesDrawTheOlderGhostFirstAndTheNewestOnTop() {
        val newest = path(0)
        val older = path(1)
        val features = RouteGhostGeometryCache()
            .collectionFor(listOf(newest, older))
            .features()
            .orEmpty()
        assertEquals(2, features.size)
        // Oldest first so the newest draws on top where the two overlap.
        assertEquals(1, features[0].getNumberProperty(GHOST_INDEX_PROPERTY).toInt())
        assertEquals(0, features[1].getNumberProperty(GHOST_INDEX_PROPERTY).toInt())
        assertEquals(
            ghostOpacityFor(1),
            features[0].getNumberProperty(GHOST_OPACITY_PROPERTY).toFloat(),
            1e-6f,
        )
        assertEquals(
            ghostOpacityFor(0),
            features[1].getNumberProperty(GHOST_OPACITY_PROPERTY).toFloat(),
            1e-6f,
        )
        val line = features[1].geometry() as LineString
        assertEquals(
            newest.points.map { it.lat to it.lon },
            line.coordinates().map { it.latitude() to it.longitude() },
        )
    }

    @Test
    fun aGhostWithFewerThanTwoPointsIsNotDrawn() {
        val single = ResponsePath().setPoints(PointList().apply { add(-27.0, 153.0) })
        val features = RouteGhostGeometryCache()
            .collectionFor(listOf(path(0), single))
            .features()
            .orEmpty()
        assertEquals(1, features.size)
        assertEquals(0, features[0].getNumberProperty(GHOST_INDEX_PROPERTY).toInt())
    }

    @Test
    fun olderGhostsAreFainterThanTheNewestYetStillVisible() {
        assertTrue(ghostOpacityFor(0) > ghostOpacityFor(1))
        assertTrue("the older ghost must stay visible", ghostOpacityFor(1) > 0.25f)
        assertTrue("no ghost may outshine the route", ghostOpacityFor(0) < 1f)
        assertEquals(2, GHOST_ROUTE_LIMIT)
    }

    @Test
    fun ghostGeometryIsBuiltOncePerUnchangedGhostSet() {
        val cache = RouteGhostGeometryCache()
        val ghosts = listOf(path(0), path(1))
        assertTrue(cache.needsRefresh(ghosts))
        val first = cache.collectionFor(ghosts)
        assertSame(first, cache.collectionFor(ghosts))
        assertFalse(cache.needsRefresh(ghosts))
        val other = listOf(path(2))
        assertTrue(cache.needsRefresh(other))
        assertNotSame(first, cache.collectionFor(other))
        cache.invalidate()
        assertTrue(cache.needsRefresh(other))
    }

    @Test
    fun aGhostThatStaysInHistoryReusesItsLineAcrossSliderSteps() {
        val cache = RouteGhostGeometryCache()
        val shared = path(0)
        val first = cache.collectionFor(listOf(shared, path(1)))
        val shifted = cache.collectionFor(listOf(shared))
        assertSame(
            "the surviving ghost's line must not be rebuilt for the next slider step",
            first.features().orEmpty().last().geometry(),
            shifted.features().orEmpty().single().geometry(),
        )
    }

    private fun path(index: Int): ResponsePath =
        ResponsePath().setPoints(PointList().apply {
            add(-27.0 - index * 0.01, 153.0 + index * 0.01)
            add(-27.005 - index * 0.01, 153.005 + index * 0.01)
        })

    /** The same ride values as [of], as a fresh object: the release re-route case. */
    private fun equalPath(of: ResponsePath): ResponsePath =
        ResponsePath().setPoints(PointList().apply {
            for (i in 0 until of.points.size()) add(of.points.getLat(i), of.points.getLon(i))
        })

    private companion object {
        const val PLAN = "plan"
        const val OTHER_PLAN = "other-plan"
    }
}
