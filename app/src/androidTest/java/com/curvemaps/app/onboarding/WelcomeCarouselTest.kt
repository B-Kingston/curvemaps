package com.curvemaps.app.onboarding

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.MainActivity
import com.curvemaps.app.media.NotificationListenerAccess
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The first-run welcome carousel replaces the old stacked startup prompts
 * (location dialog + media dialog + map card). Location is pre-granted here so
 * the system dialog never covers the page; [WelcomeModelTest] pins the
 * "ask once on arrival" decision.
 */
class WelcomeCarouselTest {
    @get:Rule val location: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalAccess = false
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun freshInstall() {
        originalAccess = NotificationListenerAccess.isGranted(context)
        context.getSharedPreferences("welcome", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun restore() {
        scenario?.close()
        NotificationListenerAccess.setGranted(context, originalAccess)
        WelcomeStore.markCompleted(context)
    }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }

    @Test fun walkthroughExplainsEachStepThenOpensThePlannerOnce() {
        assumeTrue(NotificationListenerAccess.setGranted(context, false))
        launch()
        rule.onNodeWithText("Welcome to CurveMaps").assertIsDisplayed()
        rule.onNodeWithText("START").assertDoesNotExist()
        rule.onNodeWithText("Enable media player control?").assertDoesNotExist()

        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Ride safely").assertIsDisplayed()
        rule.onNodeWithText(WelcomeModel.SAFETY_BODY).assertIsDisplayed()
        rule.onNodeWithText(WelcomeModel.SAFETY_DATA_NOTE).assertIsDisplayed()
        rule.onNodeWithText("Not now").assertDoesNotExist()
        rule.onNodeWithText("I understand").performClick()
        rule.onNodeWithText("Your location").assertIsDisplayed()
        rule.onNodeWithText("Location allowed").assertIsDisplayed()

        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Music while you ride").assertIsDisplayed()
        rule.onNodeWithText("Not enabled").assertIsDisplayed()
        rule.onNodeWithText("Enable notification access").assertIsDisplayed()
        rule.onNodeWithText("Not now").performClick()

        rule.onNodeWithText("Offline maps").assertIsDisplayed()
        rule.onNodeWithContentDescription(SKIP_WELCOME_DESCRIPTION).assertDoesNotExist()
        rule.waitUntil(5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasText("Start planning"))
                .fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(androidx.compose.ui.test.hasText("Choose map file"))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        val map = WelcomeStore.mapStatus(context)
        if (map.ready) {
            rule.onNodeWithText("Start planning").performClick()
        } else {
            rule.onNodeWithText("Choose map file").assertIsDisplayed()
            if (!map.dataPresent) rule.onNodeWithText("Download in Maps").assertIsDisplayed()
            rule.onNodeWithText("Later").performClick()
        }
        rule.onNodeWithText("START").assertIsDisplayed()
        assertTrue(WelcomeStore.isCompleted(context))

        scenario!!.recreate()
        rule.onNodeWithText("Welcome to CurveMaps").assertDoesNotExist()
        scenario!!.close()
        launch()
        rule.onNodeWithText("START").assertIsDisplayed()
        rule.onNodeWithText("Welcome to CurveMaps").assertDoesNotExist()
    }

    @Test fun safetyMustBeAcknowledgedBeforeSkipOrSwipingPastIt() {
        launch()
        rule.onNodeWithText("Welcome to CurveMaps").assertIsDisplayed()
        rule.onNodeWithContentDescription(SKIP_WELCOME_DESCRIPTION).assertDoesNotExist()
        rule.onRoot().performTouchInput { swipeLeft() }
        rule.onNodeWithText("Ride safely").assertIsDisplayed()
        rule.onNodeWithContentDescription(SKIP_WELCOME_DESCRIPTION).assertDoesNotExist()
        rule.onRoot().performTouchInput { swipeLeft() }
        rule.onNodeWithText("Ride safely").assertIsDisplayed()
        // The pager may keep a neighbour composed off-screen; it must not be shown.
        rule.onNodeWithText("Your location").assertIsNotDisplayed()
        rule.onNodeWithText("I understand").assertIsDisplayed()

        rule.onNodeWithText("I understand").performClick()
        rule.onNodeWithText("Your location").assertIsDisplayed()
        Espresso.pressBack()
        rule.onNodeWithText("Ride safely").assertIsDisplayed()
        rule.onRoot().performTouchInput { swipeLeft() }
        rule.onNodeWithText("Your location").assertIsDisplayed()
        rule.onNodeWithContentDescription(SKIP_WELCOME_DESCRIPTION).performClick()
        rule.onNodeWithText("START").assertIsDisplayed()
        assertTrue(WelcomeStore.isCompleted(context))
    }

    @Test fun grantedMediaAccessShowsEnabledAndAdvances() {
        assumeTrue(NotificationListenerAccess.setGranted(context, true))
        launch()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("I understand").performClick()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Media control enabled").assertIsDisplayed()
        rule.onNodeWithText("Enable notification access").assertDoesNotExist()
    }

    @Test fun controlsAreAtLeastFortyEightDp() {
        launch()
        rule.onNodeWithText("Next").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("I understand").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithContentDescription(SKIP_WELCOME_DESCRIPTION)
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        rule.onNodeWithText("Next", useUnmergedTree = false).assertHeightIsAtLeast(48.dp)
    }

    @Test fun pageIndicatorAnnouncesPositionAndEachPageShowsItsCard() {
        assumeTrue(NotificationListenerAccess.setGranted(context, false))
        launch()
        rule.onNode(androidx.compose.ui.test.hasStateDescription("Page 1 of 5")).assertIsDisplayed()
        rule.onNodeWithText(WelcomeModel.WELCOME_FEATURES.first()).assertIsDisplayed()
        rule.onNodeWithText("Next").performClick()
        rule.onNode(androidx.compose.ui.test.hasStateDescription("Page 2 of 5")).assertIsDisplayed()
        rule.onNodeWithText("I understand").performClick()
        rule.onNodeWithText("Next").performClick()
        rule.onNode(androidx.compose.ui.test.hasStateDescription("Page 4 of 5")).assertIsDisplayed()
        rule.onNodeWithText(WelcomeModel.MEDIA_LEAD).assertIsDisplayed()
        rule.onNodeWithText(WelcomeModel.MEDIA_PRIVACY_NOTE).assertIsDisplayed()
        rule.onNodeWithText(WelcomeModel.MEDIA_OPTIONAL_NOTE).assertIsDisplayed()
        rule.onNodeWithText("Not now").assertHeightIsAtLeast(48.dp)
    }

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
}
