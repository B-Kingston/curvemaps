package com.curvemaps.app.map

import android.Manifest
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.MainActivity
import com.curvemaps.app.region.RegionDatasets
import com.curvemaps.app.ui.dismissMediaStartupPrompt
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * What a rider sees with no map installed. In a build that ships no data of its
 * own (the distributed APK) the first-run dialog (an M3 AlertDialog over a
 * scrim) asks for ONE `.motomap` package and offers the Maps screen; a build
 * that bundles Queensland still asks for a basemap only. Either way the
 * dialog's controls are 48 dp tall, Back dismisses it so the toolbar and its
 * settings menu stay reachable, and the Maps screen it opens does not claim a
 * bundled map the build does not have.
 */
@RunWith(AndroidJUnit4::class)
class MissingMapPromptTest {

    private val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(NoInstalledMapRule()).around(permissionRule).around(composeRule)

    @Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    private fun waitForPrompt() {
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodes(hasText("Choose map file")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test(timeout = 180_000)
    fun theFirstRunCardNamesTheFileThisBuildNeeds() {
        waitForPrompt()
        if (RegionDatasets.bundledAvailable) {
            composeRule.onNodeWithText("Offline map file needed").assertExists()
            composeRule.onNodeWithText(".motomap package, or a .pmtiles", substring = true).assertExists()
            composeRule.onNodeWithText("Open Maps").assertDoesNotExist()
        } else {
            composeRule.onNodeWithText("Map package needed").assertExists()
            composeRule.onNodeWithText("map, routing and", substring = true).assertExists()
            composeRule.onNodeWithText("Open Maps").assertExists()
        }
        composeRule.onNodeWithText("Choose map file").assertHasClickAction()
        val minimum = 48f * composeRule.density.density
        listOf("Choose map file") .plus(if (RegionDatasets.bundledAvailable) emptyList() else listOf("Open Maps"))
            .forEach { label ->
                val bounds = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
                assertTrue("$label ${bounds.height} px < 48 dp", bounds.height >= minimum)
            }
    }

    @Test(timeout = 180_000)
    fun backDismissesTheDialogAndTheSettingsToolbarStaysReachable() {
        waitForPrompt()
        Espresso.pressBack()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("Choose map file")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription("Planning settings").assertExists().assertHasClickAction()
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Maps").assertExists().assertHasClickAction()
        composeRule.onNodeWithText("Load map file").assertExists().assertHasClickAction()
    }

    @Test(timeout = 180_000)
    fun openMapsReachesTheMapsScreenAndBackReturns() {
        waitForPrompt()
        if (RegionDatasets.bundledAvailable) return // the shortcut exists only without bundled data
        composeRule.onNodeWithText("Open Maps").performClick()
        composeRule.onNodeWithContentDescription("Map service", substring = true).assertExists()
        composeRule.onNodeWithText("Queensland (bundled)").assertDoesNotExist()
        composeRule.onNodeWithText("No maps are installed.", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Active map ${RegionDatasets.NO_MAP_DISPLAY_NAME}").assertExists()
        Espresso.pressBack()
        composeRule.onNodeWithContentDescription("Map service", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Choose map file").assertExists()
    }
}

/**
 * Starts the Activity with no basemap and no installed region (the first-run
 * state), then restores exactly what was there. Outermost rule, so the files
 * are settled before the Activity launches.
 */
private class NoInstalledMapRule : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            val stamp = System.nanoTime()
            val moved = listOf("tiles", "regions").mapNotNull { name ->
                val dir = File(files, name)
                if (!dir.exists()) return@mapNotNull null
                val parked = File(files, "$name.parked-$stamp")
                check(dir.renameTo(parked)) { "Cannot set aside $dir for the first-run state" }
                dir to parked
            }
            try {
                base.evaluate()
            } finally {
                moved.forEach { (dir, parked) ->
                    dir.deleteRecursively()
                    check(parked.renameTo(dir)) { "Cannot restore $dir from $parked" }
                }
            }
        }
    }
}
