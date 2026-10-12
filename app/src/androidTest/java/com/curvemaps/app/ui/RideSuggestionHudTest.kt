package com.curvemaps.app.ui

import com.curvemaps.app.ui.theme.CurveMapsTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.curvemaps.app.RideSuggestionBanner
import com.curvemaps.app.routing.navigation.VoiceDistanceUnit
import com.curvemaps.app.routing.suggestion.DiversionMeasure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal val suggestionHudMeasure = DiversionMeasure(360.0, 4000.0, 1200.0, 15000.0, 18000.0, 9000.0, 0.4)

/** The real banner's announcements, glove targets, and callback binding. */
@RunWith(AndroidJUnit4::class)
class RideSuggestionHudTest {
    @get:Rule val compose = createComposeRule()

    @Test fun takeAndSkipResolveTheVisibleOffer() {
        val pending = mutableStateOf(true)
        var outcome = ""
        compose.setContent {
            CurveMapsTheme {
                if (pending.value) RideSuggestionBanner(suggestionHudMeasure, VoiceDistanceUnit.METRIC,
                    onTake = { outcome = "accepted"; pending.value = false },
                    onSkip = { outcome = "skipped"; pending.value = false })
            }
        }
        compose.onNodeWithContentDescription("Scenic diversion: +6 min · +4 km · 9 km curvier").assertIsDisplayed()
        val take = compose.onNodeWithContentDescription("Take scenic diversion")
        val skip = compose.onNodeWithContentDescription("Skip scenic diversion")
        val minimum = 64f * compose.density.density
        listOf(take, skip).forEach {
            it.assertHasClickAction(); it.assertIsDisplayed()
            val bounds = it.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= minimum); assertTrue(bounds.width >= minimum)
        }
        // The design puts Skip first and Take (the primary action) on the right.
        assertTrue(skip.fetchSemanticsNode().boundsInRoot.right <= take.fetchSemanticsNode().boundsInRoot.left)
        take.performClick()
        assertEquals("accepted", outcome)
        take.assertDoesNotExist()
        compose.runOnIdle { pending.value = true }
        skip.performClick()
        assertEquals("skipped", outcome)
        skip.assertDoesNotExist()
    }

    @Test fun theCardNamesTheOfferAndShowsTheCountdownOnlyWhenItHasOne() {
        val fraction = mutableStateOf<Float?>(null)
        compose.setContent {
            CurveMapsTheme {
                RideSuggestionBanner(suggestionHudMeasure, VoiceDistanceUnit.METRIC, onTake = {}, onSkip = {},
                    remainingFraction = fraction.value)
            }
        }
        compose.onNodeWithText("Twistier road ahead").assertIsDisplayed()
        compose.onNodeWithText("+6 min · +4 km · 9 km curvier").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertIsDisplayed()
        compose.onNodeWithText("Take").assertIsDisplayed()
        compose.onNodeWithContentDescription("Offer closes soon").assertDoesNotExist()
        compose.runOnIdle { fraction.value = 0.6f }
        compose.onNodeWithContentDescription("Offer closes soon").assertIsDisplayed()
    }

    @Test fun blackAndWhiteModeKeepsBothActionsAndTheirTargets() {
        compose.setContent {
            CurveMapsTheme {
                RideSuggestionBanner(suggestionHudMeasure, VoiceDistanceUnit.METRIC, onTake = {}, onSkip = {},
                    monochrome = true)
            }
        }
        val minimum = 64f * compose.density.density
        listOf("Take scenic diversion", "Skip scenic diversion").forEach {
            val bounds = compose.onNodeWithContentDescription(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= minimum && bounds.width >= minimum)
        }
    }
}
