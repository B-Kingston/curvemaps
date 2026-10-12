package com.curvemaps.app.ui

import androidx.compose.ui.test.assertCountEquals
import com.curvemaps.app.BACK_TO_ENDPOINTS_DESCRIPTION
import android.Manifest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performScrollTo
import com.curvemaps.app.AVOID_STOPS_AND_TOWNS_TITLE
import com.curvemaps.app.INCLUDE_ELEVATION_TITLE
import com.curvemaps.app.MainActivity
import com.curvemaps.app.RouteUiStateKey
import com.curvemaps.app.ROUND_TRIP_BACK_BY_OPTION
import com.curvemaps.app.ROUND_TRIP_DISTANCE_OPTION
import com.curvemaps.app.ROUND_TRIP_LENGTH_DESCRIPTION
import com.curvemaps.app.ROUND_TRIP_LONGER_DESCRIPTION
import com.curvemaps.app.ROUND_TRIP_MENU_TITLE
import com.curvemaps.app.ROUND_TRIP_SHORTER_DESCRIPTION
import com.curvemaps.app.ROUND_TRIP_TIME_OPTION
import com.curvemaps.app.ROUND_TRIP_TOGGLE_DESCRIPTION
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityTest {

    private val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(
        com.curvemaps.app.map.OfflineBasemapRule(),
    ).around(permissionRule).around(composeRule)

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    @Test
    fun idleControlsExposeLabelsAndReachableTargets() {
        val descriptions = listOf(
            "Saved routes",
            "Planning settings",
            "From",
            "To",
            "Use current location",
        )
        descriptions.forEach { description ->
            composeRule.onNodeWithContentDescription(description).assertExists()
        }
        // The ride styles only exist once a search has opened them.
        composeRule.onAllNodes(KnobRobot.matcher()).assertCountEquals(0)
        assertTargetAtLeast48Dp("Saved routes")
        assertTargetAtLeast48Dp("Planning settings")
        assertTargetAtLeast48Dp("Use current location")
        composeRule.onNodeWithText("Route settings").assertDoesNotExist()
        composeRule.onNodeWithText("Sound settings").assertDoesNotExist()
        val start = composeRule.onNodeWithText("START")
        start.assertHasClickAction()
        val minimum = 48f * composeRule.density.density
        val startBounds = start.fetchSemanticsNode().boundsInRoot
        assertTrue("START width ${startBounds.width} < $minimum", startBounds.width >= minimum)
        assertTrue("START height ${startBounds.height} < $minimum", startBounds.height >= minimum)
    }

    @Test(timeout = 300_000)
    fun rideStylesPaneExposesLabelsAndReachableTargets() {
        PlannerFlow.searchRideStyles(composeRule)
        assertTargetAtLeast48Dp("Ride style level 0", requiresClick = false)
        val slider = composeRule.onNodeWithContentDescription("Ride style level 0").fetchSemanticsNode()
        assertTrue("Ride style must be adjustable by accessibility services",
            slider.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetProgress))
        assertTrue("Ride style must expose seven stops",
            slider.config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].steps == 5)
        assertTargetAtLeast48Dp(BACK_TO_ENDPOINTS_DESCRIPTION)
        composeRule.onNode(ROUTE_CARD).assertExists()
        // The card's bookmark button is a labelled 48 dp target.
        assertTargetAtLeast48Dp(com.curvemaps.app.ROUTE_CARD_SAVE_DESCRIPTION)
        val ride = composeRule.onNodeWithText("RIDE")
        ride.assertHasClickAction()
        val minimum = 48f * composeRule.density.density
        val rideBounds = ride.fetchSemanticsNode().boundsInRoot
        assertTrue("RIDE width ${rideBounds.width} < $minimum", rideBounds.width >= minimum)
        assertTrue("RIDE height ${rideBounds.height} < $minimum", rideBounds.height >= minimum)
    }

    @Test
    fun roundTripControlsExposeLabelsStateAndReachableTargets() {
        val toggle = composeRule.onNode(hasContentDescription(ROUND_TRIP_TOGGLE_DESCRIPTION) and hasClickAction())
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        toggle.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        assertExactTargetAtLeast48Dp(hasContentDescription(ROUND_TRIP_TOGGLE_DESCRIPTION))
        toggle.performClick()
        // The length is a status (description and state), not a control.
        val value = hasContentDescription(ROUND_TRIP_LENGTH_DESCRIPTION, substring = true)
        composeRule.onNode(value)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        assertExactTargetAtLeast48Dp(hasContentDescription(ROUND_TRIP_SHORTER_DESCRIPTION))
        assertExactTargetAtLeast48Dp(hasContentDescription(ROUND_TRIP_LONGER_DESCRIPTION))
        // The measure selector is a labelled group of three radio-style segments.
        composeRule.onNodeWithContentDescription(ROUND_TRIP_MENU_TITLE).assertExists()
        listOf(ROUND_TRIP_TIME_OPTION, ROUND_TRIP_DISTANCE_OPTION, ROUND_TRIP_BACK_BY_OPTION).forEach { option ->
            assertExactTargetAtLeast48Dp(hasText(option))
            composeRule.onNode(hasText(option) and hasClickAction())
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
        }
    }

    /** One node matched exactly (no substring), clickable and at least 48 dp each way. */
    private fun assertExactTargetAtLeast48Dp(matcher: SemanticsMatcher) {
        val node = composeRule.onNode(matcher and hasClickAction())
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val minimum = 48f * composeRule.density.density
        assertTrue("${matcher.description} width ${bounds.width} < $minimum", bounds.width >= minimum - 1f)
        assertTrue("${matcher.description} height ${bounds.height} < $minimum", bounds.height >= minimum - 1f)
    }

    @Test
    fun rideSuggestionsSwitchIsLabelledAndReachable() {
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Route settings").performClick()
        assertTargetAtLeast48Dp("Ride suggestions")
    }

    @Test
    fun voiceSettingsExposeAccessibleControlsAndOfflineStatus() {
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        val soundSettings = composeRule.onNodeWithText("Sound settings")
        soundSettings.assertExists()
        val stateDescription = soundSettings.fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription
        ]
        assertTrue(
            "Sound settings should announce offline voice readiness",
            stateDescription.contains("offline voice", ignoreCase = true) || stateDescription == "Disabled",
        )
        soundSettings.performClick()
        composeRule.onNodeWithText("Voice guidance").assertExists()
        // The raised settings switch keeps M3 Switch semantics: one toggleable Switch node.
        composeRule.onNodeWithContentDescription("Spoken turn guidance")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        composeRule.onNodeWithContentDescription("Announcement interval", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Preview next", substring = true).assertExists()
        composeRule.onNodeWithText("offline voice", substring = true, ignoreCase = true).assertExists()
        assertTargetAtLeast48Dp("Spoken turn guidance")
        assertTargetAtLeast48Dp("Announcement interval", requiresClick = false)
        assertTargetAtLeast48Dp("Preview next", requiresClick = false)
        // The voice screen is also exited with the shared back button.
        composeRule.onNodeWithContentDescription("Back").assertExists()
        assertTargetAtLeast48Dp("Back")
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Spoken turn guidance").assertDoesNotExist()
    }

    @Test
    fun openSourceLicencesExposeLabelsAndReachableTargets() {
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        val row = composeRule.onNode(hasText("Open-source licences").and(hasClickAction()))
        row.assertHasClickAction()
        val minimum = 48f * composeRule.density.density
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        assertTrue("licences row height ${rowBounds.height} < $minimum", rowBounds.height >= minimum)
        row.performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(com.curvemaps.app.OssLicencesStateKey, "list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTargetAtLeast48Dp("Back")
        composeRule.onNodeWithContentDescription("Open-source licence list").assertExists()
        // Each row is one labelled target naming the component and its licence.
        assertTargetAtLeast48Dp("OpenStreetMap data, ODbL-1.0")
        composeRule.onNodeWithContentDescription("OpenStreetMap data, ODbL-1.0").performClick()
        composeRule.onNodeWithContentDescription("Licence text for OpenStreetMap data").assertExists()
        assertTargetAtLeast48Dp("Back")
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithContentDescription("Open-source licence list").assertDoesNotExist()
    }

    @Test
    fun darkRideMapToggleHasAnAccessibleTargetDuringGuidance() {
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(300_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "success"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("RIDE").performClick()
        composeRule.onNodeWithContentDescription("Dark ride map", substring = true).assertExists()
        assertTargetAtLeastDp("Dark ride map", 64f)
        assertTargetAtLeastDp("Voice guidance settings", 64f)
        composeRule.onNodeWithContentDescription("Ride settings").assertExists()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Rider lock on; tap to release"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        assertTargetAtLeast48Dp("Rider lock on; tap to release")
        composeRule.onNodeWithContentDescription("Rider lock on; tap to release").performClick()
        composeRule.onNodeWithContentDescription("Rider lock off; tap to follow").assertExists()
        assertTargetAtLeast48Dp("Rider lock off; tap to follow")
    }

    @Test
    fun mediaControlsHaveAccessibleTargetsDuringGuidance() {
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(300_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "success"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("RIDE").performClick()
        // Glove-sized targets: 64 dp for every control, 80 dp for play/pause.
        assertTargetAtLeastDp("Media controls", 64f)
        assertTargetAtLeastDp("End navigation", 64f)
        composeRule.onNodeWithContentDescription("Media controls").performClick()
        assertTargetAtLeastDp("Close media controls", 64f)
        assertTargetAtLeastDp("Previous track", 64f)
        assertTargetAtLeastDp("Next track", 64f)
        // The unknown-state panel (no player here) offers both directions.
        listOf("Play", "Pause").forEach { direction ->
            val nodes = composeRule.onAllNodes(
                hasContentDescription(direction),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertTrue("missing $direction control", nodes.isNotEmpty())
            val minimum = 80f * composeRule.density.density
            nodes.forEach { node ->
                assertTrue(
                    "$direction target ${node.boundsInRoot.width}x${node.boundsInRoot.height} < $minimum",
                    node.boundsInRoot.width >= minimum && node.boundsInRoot.height >= minimum,
                )
            }
        }
        composeRule.onNodeWithText("End").assertExists()
        composeRule.onNodeWithContentDescription("Close media controls").performClick()
    }

    @Test
    fun settingsAndRouteCardsExposeLabels() {
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Route settings").performClick()
        // Both road-type toggles are labelled, glove-reachable switches.
        listOf("Avoid motorways", "Block unpaved roads", "Ride suggestions").forEach { title ->
            composeRule.onNodeWithContentDescription(title).performScrollTo()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            assertTargetAtLeast48Dp(title)
        }
        // The ride-factor tick boxes are whole-row, labelled 48 dp toggles.
        listOf(INCLUDE_ELEVATION_TITLE, AVOID_STOPS_AND_TOWNS_TITLE).forEach { title ->
            composeRule.onNodeWithContentDescription(title).performScrollTo()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            assertTargetAtLeast48Dp(title)
        }
        // Every screen opened from the settings cog is exited with Back.
        composeRule.onNodeWithContentDescription("Back").assertExists()
        assertTargetAtLeast48Dp("Back")
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithContentDescription("Avoid motorways").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(300_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "success"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val selectedCard = selectedRouteCardPrefix(composeRule)
        composeRule.onNode(hasContentDescription(selectedCard, substring = true)).assertExists()
        assertTargetAtLeast48Dp(selectedCard)
    }

    private fun assertTargetAtLeast48Dp(description: String, requiresClick: Boolean = true) {
        assertTargetAtLeastDp(description, 48f, requiresClick)
    }

    private fun assertTargetAtLeastDp(
        description: String,
        dp: Float,
        requiresClick: Boolean = true,
    ) {
        val node = composeRule.onNode(hasContentDescription(description, substring = true))
        if (requiresClick) node.assertHasClickAction()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val minimum = dp * composeRule.density.density
        assertTrue("$description width ${bounds.width} < $minimum", bounds.width >= minimum)
        assertTrue("$description height ${bounds.height} < $minimum", bounds.height >= minimum)
    }
}
