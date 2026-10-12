package com.curvemaps.app.ui

import android.Manifest
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.BACK_TO_ENDPOINTS_DESCRIPTION
import com.curvemaps.app.RIDE_BUTTON_HEIGHT
import com.curvemaps.app.MainActivity
import com.curvemaps.app.MapRouteCountKey
import com.curvemaps.app.RouteUiStateKey
import com.curvemaps.app.ROUTE_ENDPOINT_GROUP_TEST_TAG
import com.curvemaps.app.ROUND_TRIP_BACK_BY_OPTION
import com.curvemaps.app.ROUND_TRIP_DISTANCE_OPTION
import com.curvemaps.app.ROUND_TRIP_LENGTH_DESCRIPTION
import com.curvemaps.app.ROUND_TRIP_LENGTH_PREF
import com.curvemaps.app.ROUND_TRIP_LONGER_DESCRIPTION
import com.curvemaps.app.ROUND_TRIP_MENU_TITLE
import com.curvemaps.app.ROUND_TRIP_SHORTER_DESCRIPTION
import com.curvemaps.app.ROUND_TRIP_TIME_OPTION
import com.curvemaps.app.ROUND_TRIP_TOGGLE_DESCRIPTION
import com.curvemaps.app.ROUTE_PREFS
import com.curvemaps.app.routing.RoundTripMeasure
import com.curvemaps.app.routing.RoundTripWish
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerPanelTest {

    private val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val composeRule = createAndroidComposeRule<MainActivity>()

    // The first-run "map needed" dialog is its own window (a second Compose
    // root that also takes Back): these suites are not about it, so the
    // fixture basemap is installed before the Activity starts, as in a
    // normal run.
    @get:Rule
    val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(
        com.curvemaps.app.map.OfflineBasemapRule(),
    ).around(permissionRule).around(composeRule)

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    /** The wished length persists; every test starts from the default again. */
    @org.junit.After
    fun forgetRoundTripLength() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences(ROUTE_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().remove(ROUND_TRIP_LENGTH_PREF).commit()
    }

    @Test
    fun startIsReachableAndEnabledWhileIdle() {
        val start = composeRule.onNodeWithText("START")
        start.assertExists()
        start.assertIsEnabled()
        start.assertHasClickAction()
        val bounds = start.fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= root.left)
        assertTrue(bounds.right <= root.right)
        assertTrue(bounds.top >= root.top)
        assertTrue(bounds.bottom <= root.bottom)
        // No ride styles before a search: the slider only exists once START
        // has opened them, and no status copy sits beside the pill.
        composeRule.onAllNodes(KnobRobot.matcher()).assertCountEquals(0)
        composeRule.onNodeWithText("Search to compare 7 ride styles").assertDoesNotExist()
        // From and To are stacked above the full-width START button.
        val from = composeRule.onNodeWithContentDescription("From").fetchSemanticsNode().boundsInRoot
        val to = composeRule.onNodeWithContentDescription("To").fetchSemanticsNode().boundsInRoot
        assertTrue("From is above To", from.bottom <= to.top + 1f)
        assertTrue("To is above START", to.bottom <= bounds.top + 1f)
        assertTrue("START is as wide as the fields", bounds.width >= from.width)
        val minimum = 48f * composeRule.density.density
        assertTrue("START keeps a 48 dp target: ${bounds.height}", bounds.height >= minimum - 1f)
        composeRule.onNodeWithText("Slide right for curvier roads").assertDoesNotExist()
    }

    @Test
    fun currentLocationShortcutIsReachableBesideFrom() {
        val shortcut = composeRule.onNodeWithContentDescription("Use current location")
        shortcut.assertHasClickAction()
        val bounds = shortcut.fetchSemanticsNode().boundsInRoot
        val minimum = 48f * composeRule.density.density
        assertTrue("current-location target width ${bounds.width} < $minimum", bounds.width >= minimum)
        assertTrue("current-location target height ${bounds.height} < $minimum", bounds.height >= minimum)
        val from = composeRule.onNodeWithContentDescription("From").fetchSemanticsNode().boundsInRoot
        assertTrue("current-location shortcut must sit beside the From field", bounds.left >= from.right - 1f)
        assertTrue(
            "current-location shortcut must share From's row",
            bounds.center.y in from.top - minimum / 2..from.bottom + minimum / 2,
        )
    }

    @Test
    fun firstSearchPopupStaysAboveBothPlannerRows() {
        composeRule.onNodeWithContentDescription("From").performTextInput("Brisbane")
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Search results"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        val popup = composeRule.onNodeWithContentDescription("Search results")
            .fetchSemanticsNode().boundsOnScreen()
        // Popups have their own Compose root. Select the route-screen node
        // explicitly instead of `onRoot()`, which is ambiguous while the
        // popup is present.
        val root = composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteUiStateKey))
            .fetchSemanticsNode().boundsOnScreen()
        val from = composeRule.onNodeWithContentDescription("From")
            .fetchSemanticsNode().boundsOnScreen()
        val to = composeRule.onNodeWithContentDescription("To")
            .fetchSemanticsNode().boundsOnScreen()
        val endpointGroup = composeRule.onNodeWithTag(ROUTE_ENDPOINT_GROUP_TEST_TAG)
            .fetchSemanticsNode().boundsOnScreen()
        assertTrue("search popup must remain on-screen", popup.top >= root.top && popup.bottom <= root.bottom)
        assertTrue("search popup must stay above From", popup.bottom <= from.top)
        assertTrue("search popup must stay above To", popup.bottom <= to.top)
        // The results extend the planner panel above the whole endpoints row.
        assertEquals(endpointGroup.width, popup.width, 1f)
        assertEquals(endpointGroup.center.x, popup.center.x, 1f)
        assertEquals(endpointGroup.top, popup.bottom, 1f)
    }

    @Test
    fun tappingAFilledFieldSelectsItsWholeEntryForReplacement() {
        val from = composeRule.onNodeWithContentDescription("From")
        from.performTextInput("Brisbane")
        // Move focus away, then come back with a tap as a rider would.
        composeRule.onNodeWithContentDescription("To").performClick()
        from.performClick()
        composeRule.waitForIdle()
        from.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, "Brisbane".length)),
        )
        // Typing replaces the whole entry in one go.
        from.performTextInput("Samford")
        from.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Samford")))
    }

    @Test
    fun mapControlsStayClearOfTheSearchResultsSheetAndComeBackWhenItCloses() {
        composeRule.onNodeWithContentDescription("From").performTextInput("Brisbane")
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Search results")).fetchSemanticsNodes().isNotEmpty()
        }
        // The sheet is part of the panel, so the map above it shrinks. Keyboard
        // up: the zoom pill stays hidden, and the centre button is shown only
        // where it fits whole between the action rail and the sheet.
        composeRule.onNodeWithContentDescription("Zoom in").assertDoesNotExist()
        assertControlsClearOfSheet(listOf("Centre on me"))

        // Keyboard down: the field loses focus, the results close, and both
        // controls come back whole, above the panel and below the action rail.
        Espresso.closeSoftKeyboard()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Zoom in")).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodes(hasContentDescription("Search results")).fetchSemanticsNodes().isEmpty()
        }
        val panel = composeRule.onNodeWithTag(ROUTE_ENDPOINT_GROUP_TEST_TAG).fetchSemanticsNode().boundsOnScreen()
        val rail = composeRule.onNodeWithContentDescription("Saved routes").fetchSemanticsNode().boundsOnScreen()
        listOf("Centre on me", "Zoom in", "Zoom out").forEach { description ->
            val control = composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsOnScreen()
            assertTrue("$description $control must end above the panel $panel", control.bottom <= panel.top)
            assertTrue("$description $control must stay clear of the rail $rail", !control.overlaps(rail))
        }
    }

    /** Whichever of [controls] are shown must sit between the action rail and the sheet. */
    private fun assertControlsClearOfSheet(controls: List<String>) {
        composeRule.waitForIdle()
        val sheet = composeRule.onNodeWithContentDescription("Search results")
            .fetchSemanticsNode().boundsOnScreen()
        val rail = composeRule.onNodeWithContentDescription("Saved routes")
            .fetchSemanticsNode().boundsOnScreen()
        controls.forEach { description ->
            val nodes = composeRule.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes()
            nodes.forEach { node ->
                val control = node.boundsOnScreen()
                assertTrue("$description $control must end above the sheet $sheet", control.bottom <= sheet.top + 1f)
                // Below the rail, or (a short map) beside it: never over it.
                assertTrue("$description $control must stay clear of the rail $rail", !control.overlaps(rail))
            }
        }
    }

    @Test
    fun planningSettingsAndSavedRoutesFormAReachableVerticalPill() {
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val settings = composeRule.onNodeWithContentDescription("Planning settings")
            .fetchSemanticsNode().boundsInRoot
        val saved = composeRule.onNodeWithContentDescription("Saved routes")
            .fetchSemanticsNode().boundsInRoot
        val zoomIn = composeRule.onNodeWithContentDescription("Zoom in")
            .fetchSemanticsNode().boundsInRoot

        assertTrue(settings.top < saved.top)
        assertTrue(settings.left >= root.center.x)
        assertEquals(settings.width, zoomIn.width, 1f)
        assertEquals(settings.right, zoomIn.right, 1f)
        assertTrue(saved.right <= root.right)
        assertTrue(saved.bottom <= root.bottom)
        composeRule.onNodeWithText("Load map file").assertDoesNotExist()
        composeRule.onNodeWithText("Import GPX route").assertDoesNotExist()
    }

    @Test
    fun settingsCogOpensAccessibleMenuAndRouteActionDismissesIt() {
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        listOf("Route settings", "Load map file", "Import GPX route", "Sound settings")
            .forEach { label ->
                val item = composeRule.onNodeWithText(label)
                item.assertExists()
                item.assertHasClickAction()
                val bounds = item.fetchSemanticsNode().boundsInRoot
                val minimum = 48f * composeRule.density.density
                assertTrue("$label width ${bounds.width} < $minimum", bounds.width >= minimum)
                assertTrue("$label height ${bounds.height} < $minimum", bounds.height >= minimum)
            }
        composeRule.onNodeWithText("Route settings").performClick()
        composeRule.onNodeWithContentDescription("Ride suggestions").assertExists()
        composeRule.onNodeWithText("Load map file").assertDoesNotExist()
    }

    @Test
    fun settingsMenuDismissesOnBackAndOutsideTap() {
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Sound settings").assertExists()
        Espresso.pressBack()
        composeRule.onNodeWithText("Sound settings").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Sound settings").assertExists()
        val outside = composeRule.onNodeWithContentDescription("From")
            .fetchSemanticsNode().boundsInRoot.center
        composeRule.onRoot().performTouchInput { click(outside) }
        composeRule.onNodeWithText("Sound settings").assertDoesNotExist()
    }

    private fun SemanticsNode.boundsOnScreen(): Rect {
        val position = positionOnScreen
        check(position.isSpecified) { "${config} has no screen position" }
        return Rect(
            left = position.x,
            top = position.y,
            right = position.x + size.width.toFloat(),
            bottom = position.y + size.height.toFloat(),
        )
    }

    @Test
    fun invalidInputShowsErrorAndReenablesStart() {
        composeRule.onNodeWithContentDescription("From").performTextInput("not a real place")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "error"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("START").assertIsEnabled()
        composeRule.onNodeWithText("No match", substring = true).assertExists()
    }
    @Test
    fun rapidEmptySubmissionsNeverLeaveStartLoading() {
        repeat(5) { composeRule.onNodeWithText("START").performClick() }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "error"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("START").assertIsEnabled()
        composeRule.onNode(SemanticsMatcher.expectValue(RouteUiStateKey, "loading")).assertDoesNotExist()
    }

    @Test(timeout = 300_000)
    fun rideStylesReplaceTheFieldsAndBackRestoresTheFrame() {
        // Routeless frame contract: the panel is bottom-anchored, so START
        // and the From/To rows sit at the same spot in every state that has
        // no route (idle, typing, error).
        val idleBounds = composeRule.onNodeWithText("START").fetchSemanticsNode().boundsInRoot
        val idleFromTop = composeRule.onNodeWithContentDescription("From")
            .fetchSemanticsNode().boundsInRoot.top
        PlannerFlow.searchRideStyles(composeRule)

        // The styles pane takes the fields' place: route card on top, then
        // back, the wavy slider and RIDE in one row.
        composeRule.onAllNodes(hasContentDescription("From")).assertCountEquals(0)
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val card = composeRule.onNode(ROUTE_CARD).fetchSemanticsNode().boundsInRoot
        val back = composeRule.onNodeWithContentDescription(BACK_TO_ENDPOINTS_DESCRIPTION)
            .fetchSemanticsNode().boundsInRoot
        val slider = composeRule.onNode(KnobRobot.matcher()).fetchSemanticsNode().boundsInRoot
        val ride = composeRule.onNodeWithText("RIDE").fetchSemanticsNode().boundsInRoot
        assertTrue("wavy slider must span the planner", slider.width > root.width / 2f)
        assertTrue("back sits left of the wave", back.right <= slider.left + 1f)
        assertTrue("RIDE sits below the wave", ride.top >= slider.bottom - 1f)
        assertTrue("card sits above the slider row", card.bottom <= slider.top)
        assertEquals("RIDE keeps START's right edge", idleBounds.right, ride.right, 1f)
        val map = composeRule.onNode(SemanticsMatcher.keyIsDefined(MapRouteCountKey))
            .fetchSemanticsNode().boundsInRoot
        assertTrue("the map ends where the panel begins", map.bottom <= card.top)
        val minTarget = with(composeRule.density) { 48.dp.toPx() }
        assertTrue(back.width >= minTarget - 1f && back.height >= minTarget - 1f)
        // RIDE is a filled button as tall as START (56 dp) and wider than it is tall.
        val rideHeight = with(composeRule.density) { RIDE_BUTTON_HEIGHT.toPx() }
        assertEquals("RIDE is ${RIDE_BUTTON_HEIGHT} tall", rideHeight, ride.height, 1f)
        assertTrue("RIDE is a pill, wider than tall", ride.width > ride.height)
        // The route card is a pill as wide as the planner's content.
        assertTrue("route card spans the planner", card.width > root.width / 2f)

        // Back returns the fields exactly where they were.
        PlannerFlow.backToEndpoints(composeRule)
        composeRule.waitForIdle()
        assertEquals(idleBounds, composeRule.onNodeWithText("START").fetchSemanticsNode().boundsInRoot)
        assertEquals(
            idleFromTop,
            composeRule.onNodeWithContentDescription("From").fetchSemanticsNode().boundsInRoot.top,
            1f,
        )
    }

    @Test
    fun roundTripToggleBesideToTradesToForTheLength() {
        val toggle = composeRule.onNodeWithContentDescription(ROUND_TRIP_TOGGLE_DESCRIPTION)
        toggle.assertHasClickAction()
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        val minimum = 48f * composeRule.density.density
        val toggleBounds = toggle.fetchSemanticsNode().boundsInRoot
        val to = composeRule.onNodeWithContentDescription("To").fetchSemanticsNode().boundsInRoot
        val start = composeRule.onNodeWithText("START").fetchSemanticsNode().boundsInRoot
        assertTrue("toggle target ${toggleBounds.width} x ${toggleBounds.height}",
            toggleBounds.width >= minimum && toggleBounds.height >= minimum)
        assertTrue("the toggle sits beside To", toggleBounds.left >= to.right - 1f && toggleBounds.right <= start.right + 1f)
        assertTrue("the toggle sits on To's row", toggleBounds.center.y in to.top - minimum / 2..to.bottom + minimum / 2)

        toggle.performClick()
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        composeRule.onAllNodes(hasContentDescription("To")).assertCountEquals(0)
        val row = composeRule.onNode(hasContentDescription(ROUND_TRIP_LENGTH_DESCRIPTION, substring = true))
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        assertTrue("length height ${rowBounds.height}", rowBounds.height >= minimum - 1f)
        assertTrue("the length sits above START", rowBounds.bottom <= start.top + 1f)
        // The segmented selector and the stepper come with it.
        composeRule.onNodeWithContentDescription(ROUND_TRIP_MENU_TITLE).assertExists()
        composeRule.onNodeWithContentDescription(ROUND_TRIP_SHORTER_DESCRIPTION).assertHasClickAction()
        // START keeps its place: the pane does not jump when To gives way.
        assertEquals(start, composeRule.onNodeWithText("START").fetchSemanticsNode().boundsInRoot)

        toggle.performClick()
        composeRule.onNodeWithContentDescription("To").assertExists()
        composeRule.onAllNodes(hasContentDescription(ROUND_TRIP_LENGTH_DESCRIPTION, substring = true)).assertCountEquals(0)
    }

    @Test
    fun roundTripMeasuresKeepTheirOwnValues() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences(ROUTE_PREFS, android.content.Context.MODE_PRIVATE)
        // The screen read the same preference when it launched.
        var wish = RoundTripWish.parse(prefs.getString(ROUND_TRIP_LENGTH_PREF, null))
        composeRule.onNodeWithContentDescription(ROUND_TRIP_TOGGLE_DESCRIPTION).performClick()
        fun pick(option: String, measure: RoundTripMeasure) {
            // The measure selector is always shown with the stepper: one tap.
            composeRule.onNode(hasText(option) and hasClickAction()).performClick()
            composeRule.waitForIdle()
            wish = wish.withMeasure(measure, System.currentTimeMillis())
        }
        fun step(): Int {
            val direction = if (wish.atMaximum(System.currentTimeMillis())) -1 else 1
            composeRule.onNodeWithContentDescription(
                if (direction > 0) ROUND_TRIP_LONGER_DESCRIPTION else ROUND_TRIP_SHORTER_DESCRIPTION,
            ).performClick()
            wish = wish.stepped(direction, System.currentTimeMillis())
            return direction
        }

        // Ride time: one press moves the time and nothing else.
        pick(ROUND_TRIP_TIME_OPTION, RoundTripMeasure.TIME)
        composeRule.onNode(hasText(ROUND_TRIP_TIME_OPTION) and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        val distanceBefore = wish.distance
        step()
        composeRule.onNodeWithContentDescription("$ROUND_TRIP_LENGTH_DESCRIPTION, ${wish.time.label}").assertExists()

        // Distance: comes back as it was (the time did not move it), then steps alone.
        pick(ROUND_TRIP_DISTANCE_OPTION, RoundTripMeasure.DISTANCE)
        composeRule.onNode(hasText(ROUND_TRIP_DISTANCE_OPTION) and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeRule.onNodeWithContentDescription("$ROUND_TRIP_LENGTH_DESCRIPTION, ${distanceBefore.label}").assertExists()
        val timeBefore = wish.time
        step()
        composeRule.onNodeWithContentDescription("$ROUND_TRIP_LENGTH_DESCRIPTION, ${wish.distance.label}").assertExists()
        assertEquals(timeBefore, wish.time)

        // Back by: a clock time to be home by.
        pick(ROUND_TRIP_BACK_BY_OPTION, RoundTripMeasure.BACK_BY)
        composeRule.onNode(hasContentDescription("$ROUND_TRIP_LENGTH_DESCRIPTION, back by", substring = true))
            .assertExists()
        composeRule.onNodeWithContentDescription(ROUND_TRIP_SHORTER_DESCRIPTION).assertIsEnabled()

        // The whole setting is kept for the next round trip.
        val stored = RoundTripWish.parse(prefs.getString(ROUND_TRIP_LENGTH_PREF, null))
        assertEquals(RoundTripMeasure.BACK_BY, stored.measure)
        assertEquals(wish.time, stored.time)
        assertEquals(wish.distance, stored.distance)
    }

    @Test
    fun roundTripPresetChipsSetTheMeasureInUseOnly() {
        composeRule.onNodeWithContentDescription(ROUND_TRIP_TOGGLE_DESCRIPTION).performClick()
        composeRule.onNode(hasText(ROUND_TRIP_TIME_OPTION) and hasClickAction()).performClick()
        composeRule.onNode(hasText("2 h") and hasClickAction()).performClick()
        composeRule.onNodeWithContentDescription("$ROUND_TRIP_LENGTH_DESCRIPTION, 2 h").assertExists()
        composeRule.onNode(hasText("2 h") and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))

        // Distance has its own chips, and setting one leaves the ride time alone.
        composeRule.onNode(hasText(ROUND_TRIP_DISTANCE_OPTION) and hasClickAction()).performClick()
        composeRule.onNode(hasText("100 km") and hasClickAction()).performClick()
        composeRule.onNodeWithContentDescription("$ROUND_TRIP_LENGTH_DESCRIPTION, 100 km").assertExists()
        composeRule.onNode(hasText("2 h") and hasClickAction()).assertDoesNotExist()
        composeRule.onNode(hasText(ROUND_TRIP_TIME_OPTION) and hasClickAction()).performClick()
        composeRule.onNodeWithContentDescription("$ROUND_TRIP_LENGTH_DESCRIPTION, 2 h").assertExists()

        // A back-by time is a clock time: no chips.
        composeRule.onNode(hasText(ROUND_TRIP_BACK_BY_OPTION) and hasClickAction()).performClick()
        composeRule.onNode(hasText("2 h") and hasClickAction()).assertDoesNotExist()
        composeRule.onNode(hasText("100 km") and hasClickAction()).assertDoesNotExist()
    }

    @Test(timeout = 300_000)
    fun roundTripStartFindsALoopAndBackKeepsTheRoundTrip() {
        composeRule.onNodeWithContentDescription("From").performTextInput(PlannerFlow.FROM)
        composeRule.onNodeWithContentDescription(ROUND_TRIP_TOGGLE_DESCRIPTION).performClick()
        composeRule.onNodeWithText("START").performClick()
        PlannerFlow.awaitRideStyles(composeRule)
        composeRule.onNode(ROUTE_CARD).assertExists()
        PlannerFlow.backToEndpoints(composeRule)
        composeRule.onNode(hasContentDescription(ROUND_TRIP_LENGTH_DESCRIPTION, substring = true)).assertExists()
        composeRule.onNodeWithContentDescription(ROUND_TRIP_TOGGLE_DESCRIPTION)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }
}
