package com.curvemaps.app.ui

import android.Manifest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.MainActivity
import com.curvemaps.app.ROUTE_CARD_SAVE_DESCRIPTION
import com.curvemaps.app.storage.SAVED_ROUTES_DB_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedRouteFlowTest {

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

    @Before
    fun resetSavedRoutes() {
        composeRule.activity.deleteDatabase(SAVED_ROUTES_DB_NAME)
    }

    @Test(timeout = 300_000)
    fun saveCommentDeleteAndReloadPreservesPlanningState() {
        val from = "-27.4698,153.0251"
        val to = "-27.3353,152.7720"
        enterCoordinates(from, to)
        composeRule.onNodeWithText("START").performClick()
        UiTestWaits.waitForState(composeRule, "success")

        composeRule.onNode(selectedRouteCard(composeRule))
            .performTouchInput { longClick() }
        composeRule.onNodeWithContentDescription("Save route").performClick()
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasText("Saved")).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Saved routes").performClick()
        waitForRouteRow(from, to)
        composeRule.onNodeWithContentDescription(
            "Comments for saved route: $from to $to",
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasText("Add a comment")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Add a comment").performTextInput("test note")
        composeRule.onNodeWithContentDescription("Add comment").performClick()
        composeRule.onNodeWithText("test note").assertExists()

        composeRule.onNodeWithContentDescription("Delete saved route").performClick()
        composeRule.onNodeWithText("Delete saved route?").assertExists()
        composeRule.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(
                hasContentDescription("Saved route: $from to $to"),
            ).fetchSemanticsNodes().isEmpty()
        }

        closeSheet()
        saveCurrentRouteAgain()
        composeRule.onNodeWithContentDescription("Saved routes").performClick()
        waitForRouteRow(from, to)
        composeRule.onNodeWithContentDescription(
            "Saved route: $from to $to",
            useUnmergedTree = true,
        ).performClick()
        // A loaded ride opens on its ride styles, like a search.
        PlannerFlow.awaitRideStyles(composeRule)
        composeRule.onNodeWithContentDescription("Ride style level 0").assertExists()
        composeRule.onNode(selectedRouteCard(composeRule)).assertExists()
        assertTrue(composeRule.onAllNodes(hasText("min", substring = true)).fetchSemanticsNodes().isNotEmpty())
        // Back shows the restored endpoints.
        PlannerFlow.backToEndpoints(composeRule)
        composeRule.onNodeWithContentDescription("From selected: $from").performClick()
        assertEquals(from, fieldText("From"))
        composeRule.onNodeWithContentDescription("To selected: $to").performClick()
        assertEquals(to, fieldText("To"))
    }

    /** The card's bookmark button saves without the long-press: one tap, the bubble confirms. */
    @Test(timeout = 300_000)
    fun theRouteCardsBookmarkButtonSavesInOneTap() {
        val from = "-27.4698,153.0251"
        val to = "-27.3353,152.7720"
        enterCoordinates(from, to)
        composeRule.onNodeWithText("START").performClick()
        UiTestWaits.waitForState(composeRule, "success")

        composeRule.onNodeWithContentDescription(ROUTE_CARD_SAVE_DESCRIPTION).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("Saved")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Saved routes").performClick()
        waitForRouteRow(from, to)
    }

    private fun enterCoordinates(from: String, to: String) {
        composeRule.onNodeWithContentDescription("From").performTextInput(from)
        composeRule.onNodeWithContentDescription("To").performTextInput(to)
    }

    private fun saveCurrentRouteAgain() {
        composeRule.onNode(selectedRouteCard(composeRule))
            .performTouchInput { longClick() }
        composeRule.onNodeWithContentDescription("Save route").performClick()
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasText("Saved")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForRouteRow(from: String, to: String) {
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(
                hasContentDescription("Saved route: $from to $to"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun closeSheet() {
        composeRule.onNodeWithContentDescription("Close saved routes").performClick()
        composeRule.waitForIdle()
    }

    private fun fieldText(label: String): String =
        composeRule.onNodeWithContentDescription(label)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText]
            .text

}
