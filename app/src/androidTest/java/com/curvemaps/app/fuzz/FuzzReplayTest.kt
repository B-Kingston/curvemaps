package com.curvemaps.app.fuzz

import com.curvemaps.app.ui.dismissMediaStartupPrompt
import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FuzzReplayTest {

    private val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val composeRule = createAndroidComposeRule<MainActivity>()

    // The first-run "map needed" dialog is its own window (a second Compose
    // root that also takes Back): these suites are not about it, so the
    // fixture basemap is installed before the Activity starts, as in a
    // normal run.
    @get:Rule
    val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(
        com.curvemaps.app.map.OfflineBasemapRule(),
    ).around(permissionRule).around(composeRule)

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    @Test(timeout = 300_000)
    fun replayCheckedInSeedWithoutCrash() {
        val seedFile = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("fuzzSeedFile") ?: "initial.json"
        val executor = FuzzExecutor(
            rule = composeRule,
            onBack = {
                composeRule.activityRule.scenario.onActivity { activity ->
                    activity.onBackPressedDispatcher.onBackPressed()
                }
            },
            onBackgroundForeground = {
                composeRule.activityRule.scenario.recreate()
                composeRule.waitForIdle()
            },
        )
        FuzzSeed.readActions(seedFile).forEach { action ->
            executor.execute(action)
            composeRule.waitForIdle()
        }
    }
}
