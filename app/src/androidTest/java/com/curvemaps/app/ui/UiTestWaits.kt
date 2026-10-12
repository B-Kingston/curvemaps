package com.curvemaps.app.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.curvemaps.app.RouteUiStateKey

/** Shared UI-test helpers: one home for waits every suite repeats. */
object UiTestWaits {
    /** Waits until the route screen reports [state] via [RouteUiStateKey]. */
    fun waitForState(rule: ComposeTestRule, state: String, timeoutMillis: Long = 300_000) {
        try {
            rule.waitUntil(timeoutMillis) {
                rule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, state))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            val seen = rule.onAllNodes(SemanticsMatcher.keyIsDefined(RouteUiStateKey))
                .fetchSemanticsNodes().map { it.config[RouteUiStateKey] }
            throw IllegalStateException("The route screen never reported \"$state\"; it reports $seen", timeout)
        }
    }
}
