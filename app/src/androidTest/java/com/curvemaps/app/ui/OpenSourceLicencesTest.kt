package com.curvemaps.app.ui

import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.MainActivity
import com.curvemaps.app.OSS_LICENCES_LIST_DESCRIPTION
import com.curvemaps.app.OSS_LICENCES_TITLE
import com.curvemaps.app.OSS_LICENCE_TEXT_DESCRIPTION_PREFIX
import com.curvemaps.app.OssLicencesStateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Open-source licences screen in the real app: opened from the settings
 * card, listing the bundled (build-time generated) licence data, opening full
 * licence texts offline, and peeling back to the planner with the header back
 * button and system Back. Font scale / 320 dp coverage lives in FontScaleTest.
 */
@RunWith(AndroidJUnit4::class)
class OpenSourceLicencesTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    @Test
    fun settingsCardOpensTheListWithDataAndKeyLibraries() {
        openLicences()
        // Map data, fonts and style assets come first; none is a Gradle dependency.
        listOf(
            "OpenStreetMap data, ODbL-1.0",
            "OpenMapTiles schema and style, CC-BY-4.0, BSD-3-Clause",
            "OSM Bright map icons, CC-BY-4.0, BSD-3-Clause",
            "Noto Sans (map label glyphs), OFL-1.1",
            "Material Symbols (app icons), Apache-2.0",
        ).forEach { description -> assertRowExists(hasContentDescription(description)) }
        // Libraries come from the APK's own dependency graph.
        assertRowExists(hasContentDescription("GraphHopper Core, Apache-2.0"))
        assertRowExists(MAPLIBRE_SDK)
        val maplibre = composeRule.onNode(MAPLIBRE_SDK.and(hasClickAction()), useUnmergedTree = false)
            .fetchSemanticsNode()
        assertTrue(
            "MapLibre is BSD-2-Clause: ${maplibre.config[SemanticsProperties.ContentDescription]}",
            maplibre.config[SemanticsProperties.ContentDescription].single().endsWith(", BSD-2-Clause"),
        )
        assertRowExists(hasContentDescription("SLF4J Android Binding, MIT"))
        val count = Regex("""Software libraries \((\d+)\)""")
        list().performScrollToNode(hasText("Software libraries (", substring = true))
        val heading = composeRule.onNode(hasText("Software libraries (", substring = true)).fetchSemanticsNode()
        val libraries = count.find(heading.config[SemanticsProperties.Text].single().text)!!.groupValues[1].toInt()
        assertTrue("the APK ships far more than $libraries libraries", libraries >= 60)
    }

    @Test
    fun openingAnEntryShowsItsFullLicenceTextAndBackReturnsToTheList() {
        openLicences()
        list().performScrollToNode(hasContentDescription("GraphHopper Core, Apache-2.0"))
        composeRule.onNode(hasContentDescription("GraphHopper Core, Apache-2.0").and(hasClickAction())).performClick()
        awaitState("detail:com.graphhopper:graphhopper-core")
        val text = composeRule.onNodeWithContentDescription(OSS_LICENCE_TEXT_DESCRIPTION_PREFIX + "GraphHopper Core")
        text.assertExists()
        text.performScrollToNode(hasText("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", substring = true))
        text.performScrollToNode(hasText("END OF TERMS AND CONDITIONS", substring = true))

        composeRule.onNodeWithContentDescription("Back").performClick()
        awaitState("list")
        // The list keeps its scroll position: the row tapped is still on screen.
        composeRule.onNode(hasContentDescription("GraphHopper Core, Apache-2.0")).assertExists()

        composeRule.onNodeWithContentDescription("Back").performClick()
        awaitClosed()
        composeRule.onNodeWithText("START").assertExists()
    }

    @Test
    fun systemBackPeelsTheLicenceTextThenTheListThenReturnsToThePlanner() {
        openLicences()
        composeRule.onNode(hasContentDescription("OpenStreetMap data, ODbL-1.0").and(hasClickAction())).performClick()
        awaitState("detail:curvemaps.data:openstreetmap")
        val text = composeRule.onNodeWithContentDescription(OSS_LICENCE_TEXT_DESCRIPTION_PREFIX + "OpenStreetMap data")
        text.performScrollToNode(hasText("© OpenStreetMap contributors", substring = true))
        text.performScrollToNode(hasText("Open Database License", substring = true))

        Espresso.pressBack()
        awaitState("list")
        Espresso.pressBack()
        awaitClosed()
        composeRule.onNodeWithContentDescription("Planning settings").assertExists()
    }

    @Test
    fun bsdLibrariesShowTheirCopyrightNoticeAndApacheJarsTheirNotice() {
        openLicences()
        list().performScrollToNode(MAPLIBRE_SDK)
        composeRule.onNode(MAPLIBRE_SDK.and(hasClickAction())).performClick()
        val text = composeRule.onNode(hasContentDescription(OSS_LICENCE_TEXT_DESCRIPTION_PREFIX, substring = true))
        text.performScrollToNode(hasText("MapLibre Native LICENSE.md"))
        text.performScrollToNode(hasText("Copyright (c) 2021 MapLibre contributors", substring = true))
        text.performScrollToNode(hasText("Copyright (c) 2014-2020 Mapbox", substring = true))
        text.performScrollToNode(hasText("https://opensource.org/licenses/BSD-2-Clause"))
        Espresso.pressBack()
        awaitState("list")

        list().performScrollToNode(hasContentDescription("Jackson-core, Apache-2.0"))
        composeRule.onNode(hasContentDescription("Jackson-core, Apache-2.0").and(hasClickAction())).performClick()
        composeRule.onNode(hasContentDescription(OSS_LICENCE_TEXT_DESCRIPTION_PREFIX, substring = true))
            .performScrollToNode(hasText("META-INF/NOTICE"))
    }

    @Test
    fun rowsAndBackButtonsAreAtLeast48Dp() {
        openLicences()
        val minimum = 48f * composeRule.density.density
        assertBackTarget(minimum)
        val rows = composeRule.onAllNodes(
            hasClickAction().and(hasAnyAncestor(hasContentDescription(OSS_LICENCES_LIST_DESCRIPTION))).and(SemanticsMatcher("licence row") { node ->
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                    .any { ", " in it }
            }),
        ).fetchSemanticsNodes()
        assertTrue("expected visible licence rows", rows.size >= 4)
        rows.forEach { row ->
            assertTrue(
                "${row.config[SemanticsProperties.ContentDescription]} is ${row.size.height}px tall",
                row.size.height >= minimum && row.size.width >= minimum,
            )
        }
        composeRule.onNode(hasContentDescription("Noto Sans (map label glyphs), OFL-1.1").and(hasClickAction()))
            .performClick()
        awaitState("detail:curvemaps.data:noto-sans")
        assertBackTarget(minimum)
    }

    @Test
    fun filterChipsNarrowTheListAndAllBringsItBack() {
        openLicences()
        val osm = hasContentDescription("OpenStreetMap data, ODbL-1.0")
        val noto = hasContentDescription("Noto Sans (map label glyphs), OFL-1.1")
        val graphHopper = hasContentDescription("GraphHopper Core, Apache-2.0")

        chip("Libraries").performClick()
        composeRule.onAllNodes(osm).assertCountEquals(0)
        list().performScrollToNode(graphHopper)
        composeRule.onNode(graphHopper).assertExists()

        chip("Fonts").performClick()
        composeRule.onAllNodes(graphHopper).assertCountEquals(0)
        composeRule.onAllNodes(osm).assertCountEquals(0)
        composeRule.onNode(noto).assertExists()

        chip("Map data").performClick()
        composeRule.onAllNodes(noto).assertCountEquals(0)
        composeRule.onNode(osm).assertExists()

        chip("All").performClick()
        composeRule.onNode(osm).assertExists()
        list().performScrollToNode(graphHopper)
        composeRule.onNode(graphHopper).assertExists()
    }

    @Test
    fun theFullScreenPageHidesTheToolbarAndSystemBackReturnsToThePlanner() {
        openLicences()
        // Full-screen like Maps: the planner toolbar steps aside instead of
        // covering the page's header and chips.
        composeRule.onNodeWithContentDescription("Planning settings").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Saved routes").assertDoesNotExist()
        awaitState("list")
        Espresso.pressBack()
        awaitClosed()
        composeRule.onNodeWithContentDescription("Planning settings").assertExists()
    }

    private fun openLicences() {
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNode(hasText(OSS_LICENCES_TITLE).and(hasClickAction())).assertHasClickAction().performClick()
        composeRule.onNodeWithText("Sound settings").assertDoesNotExist()
        awaitState("list")
        composeRule.onNodeWithContentDescription("Back").assertHasClickAction()
    }

    private fun chip(label: String) =
        composeRule.onNode(hasText(label).and(hasClickAction())).performScrollTo()

    private fun list() = composeRule.onNodeWithContentDescription(OSS_LICENCES_LIST_DESCRIPTION)

    private fun assertRowExists(matcher: SemanticsMatcher) {
        list().performScrollToNode(matcher)
        composeRule.onNode(matcher.and(hasClickAction())).assertExists()
    }

    private fun assertBackTarget(minimum: Float) {
        val back = composeRule.onNodeWithContentDescription("Back").fetchSemanticsNode()
        assertTrue("Back is ${back.size}", back.size.width >= minimum && back.size.height >= minimum)
    }

    private fun awaitState(state: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(OssLicencesStateKey, state), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            state,
            composeRule.onNode(SemanticsMatcher.keyIsDefined(OssLicencesStateKey), useUnmergedTree = true)
                .fetchSemanticsNode().config[OssLicencesStateKey],
        )
    }

    private fun awaitClosed() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(OssLicencesStateKey), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    private companion object {
        /** MapLibre's map SDK row; debug ships the OpenGL ES artifact, release the Vulkan one. */
        val MAPLIBRE_SDK = SemanticsMatcher("MapLibre map SDK row") { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any {
                Regex("""^org\.maplibre\.gl:android-sdk(-opengl)? """).containsMatchIn(it.text)
            }
        }
    }
}
