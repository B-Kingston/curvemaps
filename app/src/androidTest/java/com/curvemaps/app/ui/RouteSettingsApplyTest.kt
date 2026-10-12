package com.curvemaps.app.ui

import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.AVOID_STOPS_AND_TOWNS_TITLE
import com.curvemaps.app.INCLUDE_ELEVATION_TITLE
import com.curvemaps.app.MainActivity
import com.curvemaps.app.ROUND_TRIP_TOGGLE_DESCRIPTION
import com.curvemaps.app.RouteGenerationKey
import com.curvemaps.app.RouteUiStateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Route settings Apply on real storage: flipping the road-type toggles must
 * persist both into route_preferences and re-route an active plan.
 *
 * The targets are computed relative to whatever is currently persisted so
 * the test stays deterministic across emulator restarts — route_preferences
 * survives both the process and the suite itself.
 */
@RunWith(AndroidJUnit4::class)
class RouteSettingsApplyTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    @Test(timeout = 300_000)
    fun applyingRoadTogglesPersistsPreferencesAndReroutes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Mirrors AVOID_MOTORWAYS_PREF / BLOCK_UNPAVED_PREF in RouteScreen.kt
        // (they stay private there because production reads its own copy).
        val preferences = context.getSharedPreferences("route_preferences", android.content.Context.MODE_PRIVATE)

        // Reach success once so activePlan exists and Apply really re-routes.
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        UiTestWaits.waitForState(composeRule, "success")
        val startGeneration = composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteGenerationKey))
            .fetchSemanticsNode()
            .config[RouteGenerationKey]

        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Route settings").performClick()
        composeRule.onNodeWithText("Route settings", substring = true).assertExists()

        val avoidTarget = !switchOn("Avoid motorways")
        val unpavedTarget = !switchOn("Block unpaved roads")
        composeRule.onNodeWithContentDescription("Avoid motorways").performClick()
        composeRule.onNodeWithContentDescription("Block unpaved roads").performClick()
        assertEquals(avoidTarget, switchOn("Avoid motorways"))
        assertEquals(unpavedTarget, switchOn("Block unpaved roads"))

        composeRule.onNodeWithText("Apply").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(hasText("Route settings")).fetchSemanticsNodes().isEmpty()
        }

        // 1) Persisted exactly as applied.
        assertTrue(preferences.contains("avoid_motorways"))
        assertEquals(avoidTarget, preferences.getBoolean("avoid_motorways", !avoidTarget))
        assertTrue(preferences.contains("block_unpaved_roads"))
        assertEquals(unpavedTarget, preferences.getBoolean("block_unpaved_roads", !unpavedTarget))

        // 2) A live plan existed, so Apply must have re-routed (new generation).
        composeRule.waitUntil(300_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "success"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val nextGeneration = composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteGenerationKey))
            .fetchSemanticsNode()
            .config[RouteGenerationKey]
        assertTrue(nextGeneration > startGeneration)

        // 3) The dialog reopens showing the applied values.
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Route settings").performClick()
        assertEquals(avoidTarget, switchOn("Avoid motorways"))
        assertEquals(unpavedTarget, switchOn("Block unpaved roads"))
    }

    /**
     * A round trip's To is its From. Apply used to re-submit the plan without
     * its round-trip length, so the router saw a zero-length ride and the
     * planner showed "No route was found in the active region" instead of the
     * re-routed loop.
     */
    @Test(timeout = 300_000)
    fun applyingRoadTogglesReroutesARoundTrip() {
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("route_preferences", android.content.Context.MODE_PRIVATE)
        val originalAvoid = preferences.getBoolean("avoid_motorways", false)
        try {
            // Samford, a corpus round-trip start.
            composeRule.onNodeWithContentDescription("From").performTextInput("-27.3720,152.8870")
            composeRule.onNodeWithContentDescription(ROUND_TRIP_TOGGLE_DESCRIPTION).performClick()
            composeRule.onNodeWithText("START").performClick()
            UiTestWaits.waitForState(composeRule, "success")
            val generation = composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteGenerationKey))
                .fetchSemanticsNode().config[RouteGenerationKey]

            composeRule.onNodeWithContentDescription("Planning settings").performClick()
            composeRule.onNodeWithText("Route settings").performClick()
            composeRule.onNodeWithContentDescription("Avoid motorways").performClick()
            composeRule.onNodeWithText("Apply").performClick()

            composeRule.waitUntil(300_000) {
                val node = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(RouteGenerationKey))
                    .fetchSemanticsNodes().firstOrNull() ?: return@waitUntil false
                node.config[RouteGenerationKey] > generation &&
                    composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "loading"))
                        .fetchSemanticsNodes().isEmpty()
            }
            assertTrue(
                "Apply must re-route the round trip, not fail it",
                composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "success"))
                    .fetchSemanticsNodes().isNotEmpty(),
            )
        } finally {
            preferences.edit().putBoolean("avoid_motorways", originalAvoid).commit()
        }
    }

    /** Cancel closes the page and throws the draft away: reopening shows the applied values. */
    @Test(timeout = 300_000)
    fun cancelDiscardsTheDraft() {
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("route_preferences", android.content.Context.MODE_PRIVATE)
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Route settings").performClick()
        val original = switchOn("Avoid motorways")
        val persisted = preferences.getBoolean("avoid_motorways", false)
        composeRule.onNodeWithContentDescription("Avoid motorways").performClick()
        assertEquals(!original, switchOn("Avoid motorways"))
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(hasText("Route settings")).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(persisted, preferences.getBoolean("avoid_motorways", false))
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Route settings").performClick()
        assertEquals(original, switchOn("Avoid motorways"))
    }

    @Test(timeout = 300_000)
    fun suggestionsOnlyApplyPersistsWithoutRerouting() {
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("route_preferences", android.content.Context.MODE_PRIVATE)
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        UiTestWaits.waitForState(composeRule, "success")
        val generation = composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteGenerationKey))
            .fetchSemanticsNode().config[RouteGenerationKey]
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Route settings").performClick()
        val target = !switchOn("Ride suggestions")
        composeRule.onNodeWithContentDescription("Ride suggestions").performClick()
        composeRule.onNodeWithText("Apply").performClick()
        composeRule.waitForIdle()
        assertEquals(target, preferences.getBoolean("ride_suggestions", !target))
        assertEquals(generation, composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteGenerationKey))
            .fetchSemanticsNode().config[RouteGenerationKey])
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Route settings").performClick()
        assertEquals(target, switchOn("Ride suggestions"))
    }

    /**
     * The ride-factor tick boxes persist, re-route, and the new route card
     * shows what each enabled factor measured. Targets flip whatever is
     * persisted (so Apply always changes something), and the original values
     * are restored afterwards so other suites see their usual route cards.
     */
    @Test(timeout = 300_000)
    fun applyingRideFactorsPersistsReroutesAndShowsWhatTheyMeasured() {
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("route_preferences", android.content.Context.MODE_PRIVATE)
        // Mirror INCLUDE_ELEVATION_PREF / AVOID_STOPS_AND_TOWNS_PREF in RouteScreen.kt.
        val originalElevation = preferences.getBoolean("include_elevation", false)
        val originalStops = preferences.getBoolean("avoid_stops_and_towns", false)
        try {
            composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
            composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
            composeRule.onNodeWithText("START").performClick()
            UiTestWaits.waitForState(composeRule, "success")
            val startGeneration = composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteGenerationKey))
                .fetchSemanticsNode().config[RouteGenerationKey]

            composeRule.onNodeWithContentDescription("Planning settings").performClick()
            composeRule.onNodeWithText("Route settings").performClick()
            val elevationTarget = !switchOn(INCLUDE_ELEVATION_TITLE)
            val stopsTarget = !switchOn(AVOID_STOPS_AND_TOWNS_TITLE)
            composeRule.onNodeWithContentDescription(INCLUDE_ELEVATION_TITLE).performScrollTo().performClick()
            composeRule.onNodeWithContentDescription(AVOID_STOPS_AND_TOWNS_TITLE).performScrollTo().performClick()
            assertEquals(elevationTarget, switchOn(INCLUDE_ELEVATION_TITLE))
            assertEquals(stopsTarget, switchOn(AVOID_STOPS_AND_TOWNS_TITLE))
            // The bundled graph carries every factor's data: no missing-data note.
            composeRule.onAllNodes(hasText("This map has no", substring = true)).assertCountEquals(0)
            composeRule.onNodeWithText("Apply").performClick()

            assertEquals(elevationTarget, preferences.getBoolean("include_elevation", !elevationTarget))
            assertEquals(stopsTarget, preferences.getBoolean("avoid_stops_and_towns", !stopsTarget))
            composeRule.waitUntil(300_000) {
                composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "success"))
                    .fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteGenerationKey))
                        .fetchSemanticsNode().config[RouteGenerationKey] > startGeneration
            }
            composeRule.waitUntil(60_000) {
                composeRule.onAllNodes(ROUTE_CARD).fetchSemanticsNodes().isNotEmpty()
            }
            val card = composeRule.onNode(ROUTE_CARD).fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription].joinToString()
            assertEquals(card, elevationTarget, Regex("\\d+ m climb").containsMatchIn(card))
            assertEquals(card, stopsTarget, Regex("\\d+ stops?\\b").containsMatchIn(card))

            composeRule.onNodeWithContentDescription("Planning settings").performClick()
            composeRule.onNodeWithText("Route settings").performClick()
            assertEquals(elevationTarget, switchOn(INCLUDE_ELEVATION_TITLE))
            assertEquals(stopsTarget, switchOn(AVOID_STOPS_AND_TOWNS_TITLE))
        } finally {
            preferences.edit()
                .putBoolean("include_elevation", originalElevation)
                .putBoolean("avoid_stops_and_towns", originalStops)
                .commit()
        }
    }

    private fun switchOn(description: String): Boolean =
        composeRule.onNodeWithContentDescription(description)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ToggleableState] == ToggleableState.On
}
