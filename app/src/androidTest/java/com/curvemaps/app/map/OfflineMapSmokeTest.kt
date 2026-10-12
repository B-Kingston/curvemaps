package com.curvemaps.app.map

import com.curvemaps.app.ui.dismissMediaStartupPrompt
import android.Manifest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.MainActivity
import com.curvemaps.app.MapReadyKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineMapSmokeTest {

    private val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(
        OfflineBasemapRule { it.methodName == "installedFixtureLoadsTheNativeStyleOffline" },
    ).around(permissionRule).around(composeRule)

    @Test(timeout = 180_000)
    fun installedFixtureLoadsTheNativeStyleOffline() {
        listOf(com.curvemaps.app.MapInstalledKey, com.curvemaps.app.MapStyleLoadedKey,
            com.curvemaps.app.MapNativeStyleReadyKey, MapReadyKey).forEach { key ->
            composeRule.waitUntil(60_000) {
                composeRule.onAllNodes(SemanticsMatcher.expectValue(key, true)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(SemanticsMatcher.expectValue(key, true)).assertExists()
        }
        composeRule.onNodeWithContentDescription("Zoom in").performClick()
        composeRule.onNodeWithContentDescription("Zoom out").performClick()
        composeRule.onNode(SemanticsMatcher.expectValue(MapReadyKey, true)).assertExists()
    }

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    @Test(timeout = 240_000)
    fun separatelyInstalledMapOrImportPromptSurvivesControls() {
        composeRule.waitUntil(180_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(MapReadyKey, true))
                .fetchSemanticsNodes()
                .isNotEmpty() ||
                composeRule.onAllNodes(hasText("Offline map file needed"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        val mapReady = composeRule.onAllNodes(SemanticsMatcher.expectValue(MapReadyKey, true))
            .fetchSemanticsNodes()
            .isNotEmpty()
        if (!mapReady) {
            composeRule.onNode(hasText("Offline map file needed")).assertExists()
            composeRule.onNode(hasText("Choose map file")).assertExists()
            composeRule.onNodeWithContentDescription("Planning settings").performClick()
            composeRule.onNodeWithText("Load map file").assertExists()
        }
        // Zoom actions use semantics and do not depend on API-private touch injection.
        composeRule.onNodeWithContentDescription("Zoom in").performClick()
        composeRule.onNodeWithContentDescription("Zoom out").performClick()
        composeRule.onNodeWithContentDescription("Zoom in").assertExists()
        composeRule.onNodeWithContentDescription("Zoom out").assertExists()
    }
}
