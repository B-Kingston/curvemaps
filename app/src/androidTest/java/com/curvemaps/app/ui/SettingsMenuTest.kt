package com.curvemaps.app.ui

import android.Manifest
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.MainActivity
import com.curvemaps.app.SETTINGS_COG_CLOSED
import com.curvemaps.app.SETTINGS_COG_OPEN
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings cog (in the planner's vertical floating toolbar, beside the
 * saved-routes bookmark) opens an M3 menu to its left: Route settings and Sound
 * settings, Maps, Load map file and Import GPX route, then Open-source licences,
 * with the map credits below. This suite pins that contract: both toolbar
 * targets keep their size and position while the menu is open, every row sits
 * left of the toolbar in order, and the back-button exit shared by the screens
 * the menu opens.
 */
@RunWith(AndroidJUnit4::class)
class SettingsMenuTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    @Test
    fun openMenuSitsBesideAToolbarThatDoesNotMove() {
        val cogClosed = bounds("Planning settings")
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        val cog = bounds("Planning settings")
        val bookmark = bounds("Saved routes")
        val minimum = 48f * composeRule.density.density

        // Opening the menu must not move or shrink the toolbar's buttons.
        assertEquals(cogClosed.left, cog.left, 1f)
        assertEquals(cogClosed.top, cog.top, 1f)
        assertEquals(cogClosed.right, cog.right, 1f)
        assertEquals(cogClosed.bottom, cog.bottom, 1f)
        listOf("Planning settings" to cog, "Saved routes" to bookmark).forEach { (label, target) ->
            assertTrue(
                "$label ${target.width} x ${target.height} must stay at least 48 dp",
                target.width >= minimum && target.height >= minimum,
            )
        }
        assertEquals(cog.right, bookmark.right, 1f)
        assertTrue("the cog stays above the bookmark", cog.bottom <= bookmark.top + 1f)

        // Rows end where the toolbar starts, in the design's order.
        val rows = listOf(
            "Route settings", "Sound settings", "Maps", "Load map file", "Import GPX route", "Open-source licences",
        )
            .map { rowBounds(it) }
        rows.forEach { row ->
            assertTrue("row right ${row.right} vs toolbar left ${cog.left}", row.right <= cog.left + 1f)
            assertTrue("row height ${row.height} must be at least 48 dp", row.height >= minimum)
        }
        rows.zipWithNext().forEach { (above, below) ->
            assertTrue("rows must stay in order: ${above.top} then ${below.top}", above.top < below.top)
        }
        assertTrue(
            "first row ${rows.first().top} must not start above the toolbar's top ${cog.top}",
            rows.first().top >= cog.top - 1f,
        )

        // The menu is anchored to the top-right: nothing leaves the screen.
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        rows.forEach { row ->
            assertTrue(row.left >= root.left)
            assertTrue(row.right <= root.right)
            assertTrue(row.bottom <= root.bottom)
        }
    }

    @Test
    fun settingsScreensExposeBackButtonsThatReturnToThePlanner() {
        openSettingsScreen("Route settings")
        composeRule.onNodeWithText("Avoid motorways").assertExists()
        exitSettingsScreen()
        composeRule.onNodeWithText("Avoid motorways").assertDoesNotExist()

        openSettingsScreen("Sound settings")
        composeRule.onNodeWithText("Spoken turn guidance").assertExists()
        exitSettingsScreen()
        composeRule.onNodeWithText("Spoken turn guidance").assertDoesNotExist()

        // Maps opens the regional package screen; Back returns to the planner.
        openSettingsScreen("Maps")
        composeRule.onNodeWithContentDescription("Map service", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Import package file").assertExists()
        exitSettingsScreen()
        composeRule.onNodeWithContentDescription("Map service", substring = true).assertDoesNotExist()

        // Open-source licences lists the bundled notices; Back returns to the planner.
        openSettingsScreen("Open-source licences")
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Open-source licence list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        exitSettingsScreen()
        composeRule.onNodeWithContentDescription("Open-source licence list").assertDoesNotExist()
        composeRule.onNodeWithText("START").assertExists()
    }

    @Test
    fun cogTogglesTheMenuAndSystemBackClosesIt() {
        // The open cog is the selected toolbar button and says so.
        assertCogState(SETTINGS_COG_CLOSED)
        openSettingsMenu()
        assertCogState(SETTINGS_COG_OPEN)
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Sound settings").assertDoesNotExist()
        assertCogState(SETTINGS_COG_CLOSED)

        openSettingsMenu()
        Espresso.pressBack()
        composeRule.onNodeWithText("Sound settings").assertDoesNotExist()
        assertCogState(SETTINGS_COG_CLOSED)
    }

    private fun assertCogState(state: String) {
        composeRule.onNodeWithContentDescription("Planning settings")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))
    }

    @Test
    fun tappingThePlannerPanelClosesTheMenu() {
        openSettingsMenu()
        // The planner panel is outside the map region. The dismissal scrim
        // must cover it too, so this tap closes the menu instead of leaking
        // into the field underneath.
        composeRule.onNodeWithContentDescription("From").performClick()
        composeRule.onNodeWithText("Sound settings").assertDoesNotExist()
    }

    @Test
    fun creditsRemainInSettingsAfterStartupCreditDisappears() {
        // LaunchedEffect delay uses Compose's test clock, not wall time.
        composeRule.mainClock.advanceTimeBy(5_001)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("OpenMapTiles.org", substring = true))
                .fetchSemanticsNodes().isEmpty()
        }
        openSettingsMenu()
        composeRule.onNode(hasText("Map credits", substring = true)).assertExists()
        composeRule.onNode(hasText("openstreetmap.org/copyright", substring = true)).assertExists()
        Espresso.pressBack()
        composeRule.onNode(hasText("OpenMapTiles.org", substring = true)).assertDoesNotExist()
    }

    private fun openSettingsMenu() {
        if (composeRule.onAllNodes(hasText("Sound settings")).fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithContentDescription("Planning settings").performClick()
        }
        composeRule.onNodeWithText("Sound settings").assertExists()
    }

    private fun openSettingsScreen(label: String) {
        openSettingsMenu()
        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithContentDescription("Back").assertExists().assertHasClickAction()
    }

    private fun exitSettingsScreen() {
        composeRule.onNodeWithContentDescription("Back").performClick()
    }

    private fun bounds(description: String): Rect =
        composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot

    private fun rowBounds(label: String): Rect =
        composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
}
