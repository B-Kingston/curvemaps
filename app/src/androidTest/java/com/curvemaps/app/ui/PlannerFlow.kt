package com.curvemaps.app.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.curvemaps.app.BACK_TO_ENDPOINTS_DESCRIPTION

/**
 * The planner's two panes. The ride-style slider exists only in the styles
 * pane, which START opens the moment a search begins; Back returns to the
 * From/To fields. Instrumented suites reach the slider through here.
 */
object PlannerFlow {
    /** Brisbane CBD to Samford: short enough to search quickly, long enough for seven rides. */
    const val FROM = "-27.4698,153.0251"
    const val TO = "-27.3353,152.7720"

    /** Types [from] and [to], presses START and waits for the selected style's route. */
    fun searchRideStyles(rule: ComposeTestRule, from: String = FROM, to: String = TO) {
        rule.onNodeWithContentDescription("From").performTextInput(from)
        rule.onNodeWithContentDescription("To").performTextInput(to)
        rule.onNodeWithText("START").performClick()
        awaitRideStyles(rule)
    }

    /** Waits until the styles pane shows the slider and a route is on the card. */
    fun awaitRideStyles(rule: ComposeTestRule) {
        UiTestWaits.waitForState(rule, "success")
        rule.waitUntil(30_000) {
            rule.onAllNodes(KnobRobot.matcher()).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodes(ROUTE_CARD).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Back from the ride styles to the From/To fields. */
    fun backToEndpoints(rule: ComposeTestRule) {
        rule.onNodeWithContentDescription(BACK_TO_ENDPOINTS_DESCRIPTION).performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(KnobRobot.matcher()).fetchSemanticsNodes().isEmpty()
        }
    }
}
