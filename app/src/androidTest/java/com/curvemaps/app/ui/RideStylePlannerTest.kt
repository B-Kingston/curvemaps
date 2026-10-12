package com.curvemaps.app.ui

import android.Manifest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.BACK_TO_ENDPOINTS_DESCRIPTION
import com.curvemaps.app.MainActivity
import com.curvemaps.app.RouteGenerationKey
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideStylePlannerTest {

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

    @Test(timeout = 300_000)
    fun sliderStopsAtBothEndsNamesEachStyleAndSurvivesRecreation() {
        // The slider only exists once a search has opened the ride styles.
        composeRule.onAllNodes(KnobRobot.matcher()).assertCountEquals(0)
        PlannerFlow.searchRideStyles(composeRule)
        composeRule.onNodeWithText("Fastest").assertExists()
        // Positive control for both slide directions: if a synthesized
        // gesture were silently dropped, everything below would be vacuous.
        KnobRobot.performDetentClicks(composeRule, 4)
        KnobRobot.waitForLevel(composeRule, 4) // rightward detents must register
        composeRule.onNodeWithText("Twisty").assertExists()
        KnobRobot.performDetentClicks(composeRule, -1)
        KnobRobot.waitForLevel(composeRule, 3) // leftward detents must register while away from the stop
        composeRule.onNodeWithText("Curvy").assertExists() // a mid stop names itself too
        KnobRobot.performDetentClicks(composeRule, -1)
        KnobRobot.waitForLevel(composeRule, 2)
        composeRule.onNodeWithText("Balanced").assertExists()

        // A deep leftward run clamps hard at Fastest (0) and stays there.
        KnobRobot.performDetentClicks(composeRule, -6, clampAtEnds = true)
        KnobRobot.waitForLevel(composeRule, 0)

        // A deep rightward run clamps hard at the last stop: the dial is bounded.
        KnobRobot.performDetentClicks(composeRule, KnobRobot.MAX_LEVEL + 3, clampAtEnds = true)
        KnobRobot.waitForLevel(composeRule, KnobRobot.MAX_LEVEL)
        composeRule.onNodeWithText("Max twisty").assertExists()

        // The computed route does not survive recreation (START re-runs it),
        // but the chosen style does: the next search opens on Max twisty.
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("START").performClick()
        PlannerFlow.awaitRideStyles(composeRule)
        KnobRobot.waitForLevel(composeRule, KnobRobot.MAX_LEVEL)
    }

    @Test(timeout = 300_000)
    fun draggingSliderChangesTheRouteBeforeReleaseAndCancellationRestoresIt() {
        PlannerFlow.searchRideStyles(composeRule)
        // The wave drifts while the finger is down; it is an infinite animation,
        // which the test clock pauses, so Compose still reaches idle mid-drag.
        KnobRobot.startHeldDrag(composeRule, 1)
        try {
            KnobRobot.waitForLevel(composeRule, 1)
            UiTestWaits.waitForState(composeRule, "success", timeoutMillis = 60_000)
            composeRule.onNode(hasContentDescription("Route 1: Mostly fast,", substring = true)).assertExists()
        } finally {
            KnobRobot.finishHeldDrag(composeRule, cancelGesture = true)
        }
        KnobRobot.waitForLevel(composeRule, 0)
        UiTestWaits.waitForState(composeRule, "success")
        composeRule.onNode(hasContentDescription("Route 1: Fastest,", substring = true)).assertExists()
    }

    @Test(timeout = 300_000)
    fun startOpensTheRideStylesAndBackReturnsToTheFields() {
        composeRule.onNodeWithContentDescription("From").performTextInput(PlannerFlow.FROM)
        composeRule.onNodeWithContentDescription("To").performTextInput(PlannerFlow.TO)
        composeRule.onNodeWithText("START").performClick()
        // The styles pane opens at once and takes the fields' place.
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(KnobRobot.matcher()).fetchSemanticsNodes().isNotEmpty() }
        PlannerFlow.awaitRideStyles(composeRule)
        composeRule.onAllNodes(hasContentDescription("From")).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(BACK_TO_ENDPOINTS_DESCRIPTION).assertHasClickAction()

        PlannerFlow.backToEndpoints(composeRule)
        composeRule.onNodeWithContentDescription("From").assertExists()
        composeRule.onNodeWithText("START").assertIsEnabled()
        // The route stays on the map, and START answers from the finished plan.
        UiTestWaits.waitForState(composeRule, "success")
        composeRule.onNodeWithText("START").performClick()
        PlannerFlow.awaitRideStyles(composeRule)

        // System Back peels the styles pane too.
        Espresso.pressBack()
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(KnobRobot.matcher()).fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithContentDescription("From").assertExists()
    }

}
