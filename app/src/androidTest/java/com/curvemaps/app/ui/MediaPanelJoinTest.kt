package com.curvemaps.app.ui

import android.Manifest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.DataBarMargin
import com.curvemaps.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The open media control center and the ride data bar must read as one
 * continuous surface: one floating island, inset alike on both sides with no
 * step, and fused at the seam rather than merely adjacent. The JVM
 * [com.curvemaps.app.media.MediaPanelJoinTest] pins the constant
 * relationships; this suite proves the real laid-out geometry on screen.
 */
@RunWith(AndroidJUnit4::class)
class MediaPanelJoinTest {

    private val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val composeRule = createAndroidComposeRule<MainActivity>()

    // The map fixture installs before launch: without a basemap the app shows
    // its own "Offline map file needed" dialog, a second window over the ride.
    @get:Rule
    val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(
        com.curvemaps.app.map.OfflineBasemapRule(),
    ).around(permissionRule).around(composeRule)

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    private fun openPanelOverTheBar() {
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(120_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.SemanticsMatcher.expectValue(
                    com.curvemaps.app.RouteUiStateKey,
                    "success",
                ),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("RIDE").performClick()
        composeRule.onNodeWithContentDescription("Media controls").performClick()
        composeRule.waitForIdle()
    }

    private fun bounds(description: String, substring: Boolean = false) =
        composeRule.onNodeWithContentDescription(description, substring = substring)
            .fetchSemanticsNode().boundsInRoot

    @Test
    fun thePanelAndTheBarShareOneIslandWidth() {
        openPanelOverTheBar()
        val panel = bounds("Media control center")
        val bar = bounds("Ride data:", substring = true)
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val epsilon = composeRule.density.density
        val margin = with(composeRule.density) { DataBarMargin.toPx() }
        // The bar floats like the planner panel: map shows below it, above
        // the navigation bar.
        assertTrue(
            "bar bottom ${bar.bottom} sits on the screen edge ${root.bottom}",
            bar.bottom <= root.bottom - margin + epsilon,
        )
        // Any inset on either side shows as a step at the join, which is what
        // made the panel read as a separate card.
        assertTrue(
            "panel left ${panel.left} vs bar left ${bar.left}",
            kotlin.math.abs(panel.left - bar.left) <= epsilon,
        )
        assertTrue(
            "panel right ${panel.right} vs bar right ${bar.right}",
            kotlin.math.abs(panel.right - bar.right) <= epsilon,
        )
        // And both float the island's margin in from the screen edges.
        assertTrue(
            "panel left ${panel.left} vs screen ${root.left} + margin",
            kotlin.math.abs(panel.left - (root.left + margin)) <= epsilon,
        )
        assertTrue(
            "panel right ${panel.right} vs screen ${root.right} - margin",
            kotlin.math.abs(panel.right - (root.right - margin)) <= epsilon,
        )
    }

    @Test
    fun thePanelOverlapsTheBarRatherThanStoppingAboveIt() {
        openPanelOverTheBar()
        val panel = bounds("Media control center")
        val bar = bounds("Ride data:", substring = true)
        val epsilon = composeRule.density.density
        // The panel reaches down into the bar's empty top padding, so the two
        // surfaces overlap instead of meeting at a possibly-antialiased seam.
        assertTrue(
            "panel bottom ${panel.bottom} stops short of the bar top ${bar.top}",
            panel.bottom > bar.top + epsilon,
        )
        // ...but only into that padding: never onto a metric or a control.
        val opener = bounds("Media controls")
        val end = bounds("End navigation")
        assertTrue(
            "the fused edge covers the media opener: panel ${panel.bottom} vs ${opener.top}",
            panel.bottom <= opener.top + epsilon,
        )
        assertTrue(
            "the fused edge covers END: panel ${panel.bottom} vs ${end.top}",
            panel.bottom <= end.top + epsilon,
        )
        // END and the opener both stay live under the fused edge.
        composeRule.onNodeWithText("End").assertExists()
        composeRule.onNodeWithContentDescription("Media controls").assertExists()
    }

    @Test
    fun inactivityTimeoutClosesThePanelWithoutEndingTheRideAndReopeningStartsFresh() {
        openPanelOverTheBar()
        Thread.sleep(7_000)
        composeRule.onNodeWithContentDescription("Media control center").performTouchInput {
            val tap = Offset(visibleSize.width / 2f, 2f)
            down(tap)
            up()
        }
        Thread.sleep(8_500)
        composeRule.onNodeWithContentDescription("Media control center").assertExists()
        composeRule.waitUntil(8_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Media control center"),
            ).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("End").assertExists()
        composeRule.onNodeWithContentDescription("Media controls").performClick()
        val inactivityTimer =
            composeRule.onNodeWithContentDescription("Media controls inactivity timer")
                .fetchSemanticsNode().config[
                    androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo
                ]
        assertTrue("fresh opening should restart the inactivity timer", inactivityTimer.current > 0.9f)
        composeRule.onNodeWithContentDescription("Close media controls").performClick()
    }

    @Test
    fun closingThePanelRestoresTheBarsOwnRoundedSilhouette() {
        openPanelOverTheBar()
        composeRule.onNodeWithContentDescription("Close media controls").performClick()
        composeRule.waitForIdle()
        val bar = bounds("Ride data:", substring = true)
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val epsilon = composeRule.density.density
        val margin = with(composeRule.density) { DataBarMargin.toPx() }
        // Closed, the bar is a standalone rounded island again, and the panel
        // is gone rather than left fused to it.
        assertTrue(
            "closed bar left ${bar.left} vs screen ${root.left} + margin",
            kotlin.math.abs(bar.left - (root.left + margin)) <= epsilon,
        )
        assertTrue(
            "the panel must be gone when closed",
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Media control center"),
            ).fetchSemanticsNodes().isEmpty(),
        )
    }
}
