package com.curvemaps.app.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import com.curvemaps.app.onboarding.SKIP_WELCOME_DESCRIPTION
import com.curvemaps.app.onboarding.WelcomePage
import com.curvemaps.app.onboarding.WelcomePrimary

/**
 * Ordinary planner suites skip the first-run welcome carousel (it replaces the
 * planner until finished or skipped); `WelcomeCarouselTest` exercises it. Skip
 * appears only after the safety page is acknowledged, so this walks Next →
 * I understand → Skip.
 */
fun ComposeTestRule.dismissMediaStartupPrompt() {
    waitForIdle()
    fun present(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    if (!present(hasText(WelcomePage.WELCOME.title)) && !present(hasText(WelcomePage.SAFETY.title)) &&
        !present(hasContentDescription(SKIP_WELCOME_DESCRIPTION))
    ) return
    repeat(4) {
        when {
            present(hasContentDescription(SKIP_WELCOME_DESCRIPTION)) -> {
                onNode(hasContentDescription(SKIP_WELCOME_DESCRIPTION)).performClick()
                waitForIdle()
                return
            }
            present(hasText(WelcomePrimary.ACKNOWLEDGE_SAFETY.label)) ->
                onNode(hasText(WelcomePrimary.ACKNOWLEDGE_SAFETY.label)).performClick()
            present(hasText(WelcomePrimary.NEXT.label)) -> onNode(hasText(WelcomePrimary.NEXT.label)).performClick()
        }
        waitForIdle()
    }
    error("Could not get past the welcome carousel")
}
