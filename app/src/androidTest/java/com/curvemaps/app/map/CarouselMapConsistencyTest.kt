package com.curvemaps.app.map

import com.curvemaps.app.ui.dismissMediaStartupPrompt
import android.Manifest
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.FocusedRouteIndexKey
import com.curvemaps.app.MainActivity
import com.curvemaps.app.MapRouteCountKey
import com.curvemaps.app.RouteCountKey
import com.curvemaps.app.RouteUiStateKey
import com.curvemaps.app.SelectedRouteKey
import com.curvemaps.app.ui.KnobRobot
import com.curvemaps.app.ui.PlannerFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarouselMapConsistencyTest {

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
    fun routeCardAndFocusedMapRouteStayInSync() {
        // Same pre-flight gate the fuzz campaign uses: tolerate slow cold
        // launches before driving the panel.
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(RouteUiStateKey))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // The slider only exists once START has opened the ride styles.
        PlannerFlow.searchRideStyles(composeRule, "-27.4698,153.0251", "-27.3353,152.7720")
        KnobRobot.performDetentClicks(composeRule, 4)
        KnobRobot.waitForLevel(composeRule, 4) // knob must be at the Twisty stop
        composeRule.waitUntil(300_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "success"))
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                composeRule.onAllNodes(hasContentDescription("Route 1: Twisty,", substring = true))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        // One route per slider stop: no carousel, one card, one map line.
        assertEquals(1, rootNode().config[RouteCountKey])
        assertEquals(0, rootNode().config[SelectedRouteKey])
        assertSelectionMatchesMap()
        composeRule.onNode(hasContentDescription("Route 1: Twisty,", substring = true)).assertExists()
        val target = 0

        // Exercise the MapView stop/start path after selection. The route set
        // and focus stay selected, and the resumed map must become ready again.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(MapRouteCountKey))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertSelectionMatchesMap()
        assertEquals(target, rootNode().config[SelectedRouteKey])
    }

    private fun assertSelectionMatchesMap() {
        val root = rootNode()
        val map = composeRule.onNode(
            SemanticsMatcher.expectValue(MapRouteCountKey, root.config[RouteCountKey]),
        ).fetchSemanticsNode()
        assertEquals(root.config[SelectedRouteKey], map.config[FocusedRouteIndexKey])
        assertEquals(root.config[RouteCountKey], map.config[MapRouteCountKey])
        composeRule.onNode(
            hasContentDescription("Route ${root.config[SelectedRouteKey] + 1}:", substring = true),
        ).assertExists()
    }

    private fun rootNode() =
        composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteCountKey)).fetchSemanticsNode()
}
