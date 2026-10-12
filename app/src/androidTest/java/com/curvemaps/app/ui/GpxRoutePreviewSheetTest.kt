package com.curvemaps.app.ui

import com.curvemaps.app.ui.theme.CurveMapsTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.curvemaps.app.GpxRoutePreviewSheet
import com.curvemaps.app.storage.GeoPoint
import com.curvemaps.app.storage.GpxMilestone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GpxRoutePreviewSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun centredMilestoneIsAccessibleAndDrivesPreviewFocus() {
        val milestones = List(8) { index ->
            GpxMilestone(
                point = GeoPoint(-26.6 - index * 0.05, 152.9),
                distanceMeters = index * 10_000.0,
                progress = index / 7.0,
                placeName = "Place $index",
                placeDetail = "Queensland",
            )
        }
        var focused = milestones.first()
        composeRule.setContent {
            CurveMapsTheme {
                GpxRoutePreviewSheet(
                    routeName = "Coast ride",
                    milestones = milestones,
                    onFocusMilestone = { focused = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Preview route").assertExists()
        composeRule.onNodeWithContentDescription("GPX route milestones").performTouchInput { swipeUp() }
        composeRule.waitUntil(5_000) { focused != milestones.first() }
        composeRule.onNode(hasContentDescription("focused on map", substring = true)).assertExists()

        val closeBounds = composeRule.onNodeWithContentDescription("Close route preview")
            .fetchSemanticsNode().boundsInRoot
        val minimum = 48f * composeRule.density.density
        assertTrue(closeBounds.width >= minimum)
        assertTrue(closeBounds.height >= minimum)
    }

    @Test
    fun editAndRideButtonsAppearOnlyWhenGivenAndKeepTheirTargets() {
        var edits = 0
        var rides = 0
        var withActions by mutableStateOf(false)
        composeRule.setContent {
            CurveMapsTheme {
                GpxRoutePreviewSheet(
                    routeName = "Coast ride",
                    milestones = sampleMilestones(),
                    note = "Follows the track road for road",
                    onFocusMilestone = {},
                    onDismiss = {},
                    onEdit = if (withActions) ({ edits++ }) else null,
                    onRide = if (withActions) ({ rides++ }) else null,
                )
            }
        }
        composeRule.onAllNodes(hasText("Ride")).assertCountEquals(0)
        composeRule.onAllNodes(hasText("Edit")).assertCountEquals(0)
        withActions = true
        composeRule.waitForIdle()
        val minimum = 48f * composeRule.density.density
        listOf("Edit", "Ride").forEach { label ->
            val bounds = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue("$label is ${bounds.width} x ${bounds.height}", bounds.width >= minimum && bounds.height >= minimum)
        }
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithText("Ride").performClick()
        assertEquals(1, edits)
        assertEquals(1, rides)
    }

    @Test
    fun followedTrackShowsTheSuccessCardAndStopsShowTheNeutralOne() {
        var following by mutableStateOf(true)
        composeRule.setContent {
            CurveMapsTheme {
                GpxRoutePreviewSheet(
                    routeName = "Coast ride",
                    milestones = sampleMilestones(),
                    note = "Follows the track road for road",
                    following = following,
                    onFocusMilestone = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Following your track").assertExists()
        composeRule.onNodeWithText("Follows the track road for road").assertExists()
        following = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Routing between your stops").assertExists()
        composeRule.onAllNodes(hasText("Following your track")).assertCountEquals(0)
    }

    @Test
    fun timelineNamesStartWaypointsAndFinish() {
        composeRule.setContent {
            CurveMapsTheme {
                GpxRoutePreviewSheet(
                    routeName = "Coast ride",
                    milestones = sampleMilestones(named = false).take(3),
                    onFocusMilestone = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNode(hasContentDescription("Route start", substring = true)).assertExists()
        composeRule.onNode(hasContentDescription("Waypoint 1", substring = true)).assertExists()
        composeRule.onNode(hasContentDescription("Route finish", substring = true)).assertExists()
    }

    private fun sampleMilestones(named: Boolean = true) = List(8) { index ->
        GpxMilestone(
            point = GeoPoint(-26.6 - index * 0.05, 152.9),
            distanceMeters = index * 10_000.0,
            progress = index / 7.0,
            placeName = if (named) "Place $index" else null,
            placeDetail = if (named) "Queensland" else null,
        )
    }
}
