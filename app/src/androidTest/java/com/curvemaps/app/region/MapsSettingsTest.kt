package com.curvemaps.app.region

import com.curvemaps.app.ui.dismissMediaStartupPrompt
import android.Manifest
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Maps screen: reachable from the settings menu, offers the fixed map
 * service (Browse maps) without contacting it, has no way to change the server,
 * exposes every control with a content description and a
 * glove-sized target, and returns to the planner with the shared back button.
 */
@RunWith(AndroidJUnit4::class)
class MapsSettingsTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    private fun openMapsScreen() {
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Maps").performClick()
        composeRule.onNodeWithContentDescription("Map service", substring = true).assertExists()
    }

    @Test
    fun exposesTheServerControlsWithGloveSizedTargets() {
        openMapsScreen()
        val minimum = 48f * composeRule.density.density
        val check = { description: String ->
            val bounds: Rect = composeRule.onNodeWithContentDescription(description)
                .assertExists()
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$description ${bounds.width} x ${bounds.height} must be at least 48 dp",
                bounds.width >= minimum && bounds.height >= minimum,
            )
        }
        listOf("Browse maps", "Import package file").forEach(check)
        composeRule.onNodeWithContentDescription("Browse maps").assertHasClickAction()
        composeRule.onNodeWithContentDescription("Import package file").assertHasClickAction()
    }

    @Test
    fun theDefaultServiceIsOfferedButNotContactedUntilBrowse() {
        openMapsScreen()
        composeRule.onNodeWithContentDescription("Map service CurveMaps map service").assertExists()
        composeRule.onNodeWithText("Nothing is sent until you tap Browse maps", substring = true).assertExists()
        composeRule.onNodeWithText("Not connected").assertExists()
    }

    @Test
    fun theServerIsFixedAndTheCatalogWaitsForATap() {
        openMapsScreen()
        // Riders cannot point the app at another server, and nothing is
        // fetched until they ask for the catalog.
        composeRule.onNodeWithContentDescription("Use a custom server").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Map server address").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Allow insecure HTTP").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Browse maps").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Refresh maps").assertDoesNotExist()
    }

    @Test
    fun systemBackReturnsToThePlanner() {
        openMapsScreen()
        Espresso.pressBack()
        composeRule.onNodeWithContentDescription("Map service", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Planning settings").assertExists()
    }

    @Test
    fun thePlannerSettingsCardListsMaps() {
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Maps").assertExists()
        composeRule.onNodeWithText("Load map file").assertExists()
    }

    @Test
    fun bundledOnlyStateIsHonestAndCannotBeDeleted() {
        openMapsScreen()
        if (RegionDatasets.bundledAvailable) {
            composeRule.onNodeWithText("Queensland (bundled)").assertExists()
            composeRule.onNodeWithText("cannot be deleted", substring = true).assertExists()
            composeRule.onNodeWithText("No downloaded maps are installed.", substring = true).assertExists()
        } else {
            // A build with no data of its own must not offer or claim a bundled map.
            composeRule.onNodeWithText("Queensland (bundled)").assertDoesNotExist()
            composeRule.onNodeWithText("Use bundled Queensland data").assertDoesNotExist()
            composeRule.onNodeWithText("No maps are installed.", substring = true).assertExists()
        }
        // Nothing here is deletable: the bundled data is immutable, and with no
        // installed map there is nothing to delete.
        assertTrue(
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Delete", substring = true),
            ).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun headerNamesTheActiveMapAndThePlannerToolbarStepsAside() {
        openMapsScreen()
        composeRule.onNodeWithContentDescription("Active map ${RegionDatasets.fallbackDisplayName}").assertExists()
        // The Maps page is full-screen: the planner toolbar is hidden while it is
        // open, so it can never cover the header, the banner or a control.
        composeRule.onNodeWithContentDescription("Planning settings").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Saved routes").assertDoesNotExist()
    }
}
