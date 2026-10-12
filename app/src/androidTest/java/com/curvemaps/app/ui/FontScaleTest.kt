package com.curvemaps.app.ui

import com.curvemaps.app.BACK_TO_ENDPOINTS_DESCRIPTION
import com.curvemaps.app.ui.theme.CurveMapsTheme
import android.Manifest
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.RouteScreen
import com.curvemaps.app.PlannerStage
import com.curvemaps.app.RoutePlanPanel
import com.curvemaps.app.RouteUiState
import com.curvemaps.app.ROUND_TRIP_BACK_BY_OPTION
import com.curvemaps.app.ROUND_TRIP_LENGTH_DESCRIPTION
import com.curvemaps.app.geocoding.GeocodeController
import com.curvemaps.app.geocoding.GeocodeResult
import com.curvemaps.app.routing.RoundTripMeasure
import com.curvemaps.app.routing.RoundTripWish
import com.curvemaps.app.ROUND_TRIP_DISTANCE_OPTION
import com.curvemaps.app.ROUND_TRIP_LONGER_DESCRIPTION
import com.curvemaps.app.ROUND_TRIP_SHORTER_DESCRIPTION
import com.curvemaps.app.ROUND_TRIP_TIME_OPTION
import com.curvemaps.app.routing.RoundTripLength
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FontScaleTest {

    private val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val composeRule = createComposeRule()

    // The first-run "map needed" dialog is its own window (a second Compose
    // root that also takes Back): these suites are not about it, so the
    // fixture basemap is installed before the Activity starts, as in a
    // normal run.
    @get:Rule
    val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(
        com.curvemaps.app.map.OfflineBasemapRule(),
    ).around(permissionRule).around(composeRule)

    @Test
    fun fontScaleTwoKeepsStartAndAttributionReachable() {
        setRouteScreen(fontScale = 2f)
        assertStableSurfaceAndStart()
        composeRule.onNodeWithContentDescription("From").performTextInput("invalid input")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(hasText("No match", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val error = composeRule.onNode(hasText("No match", substring = true)).fetchSemanticsNode().boundsInRoot
        assertTrue(error.left >= root.left)
        assertTrue(error.right <= root.right)
        assertTrue(error.top >= root.top)
        assertTrue(error.bottom <= root.bottom)
    }

    @Test
    fun fontScaleOnePointThreeKeepsStartReachable() {
        setRouteScreen(fontScale = 1.3f)
        assertStableSurfaceAndStart()
    }

    @Test
    fun fontScaleTwoKeepsSettingsCardRowsTappable() {
        setRouteScreen(fontScale = 2f)
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        val minimum = 48f * composeRule.density.density
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        listOf("Route settings", "Load map file", "Import GPX route", "Sound settings", "Open-source licences")
            .forEach { label ->
                val row = composeRule.onNodeWithText(label)
                row.assertExists()
                row.assertHasClickAction()
                val bounds = row.fetchSemanticsNode().boundsInRoot
                assertTrue("$label height ${bounds.height}", bounds.height >= minimum)
                assertTrue("$label must stay on screen", bounds.left >= root.left)
                assertTrue("$label must stay on screen", bounds.right <= root.right)
            }
    }

    @Test
    fun fontScaleTwoKeepsScenicChoicesOnScreen() {
        val density = composeRule.density
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                CurveMapsTheme {
                    com.curvemaps.app.RideSuggestionBanner(suggestionHudMeasure,
                        com.curvemaps.app.routing.navigation.VoiceDistanceUnit.IMPERIAL, {}, {})
                }
            }
        }
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val bounds = listOf("Take scenic diversion", "Skip scenic diversion").map { label ->
            val node = composeRule.onNodeWithContentDescription(label)
            node.assertHasClickAction()
            val b = node.fetchSemanticsNode().boundsInRoot
            assertTrue(b.left >= root.left && b.right <= root.right && b.bottom <= root.bottom)
            assertTrue(b.height >= 64f * density.density)
            b
        }
        assertTrue(bounds[0].right < bounds[1].left)
    }

    @Test
    fun fontScaleTwoKeepsTheLicenceScreenReadableAt320Dp() {
        val density = composeRule.density
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        var dismissed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                CurveMapsTheme {
                    androidx.compose.foundation.layout.Box(
                        Modifier.requiredSize(width = 320.dp, height = 640.dp),
                    ) {
                        com.curvemaps.app.OpenSourceLicencesScreen(
                            onDismiss = { dismissed = true },
                            loadCatalog = { com.curvemaps.app.licences.LicenceCatalogLoader.load(context) },
                        )
                    }
                }
            }
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(com.curvemaps.app.OssLicencesStateKey, "list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val minimum = 48f * density.density
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val back = composeRule.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot
        assertTrue("Back stays on screen", back.left >= root.left && back.right <= root.right && back.top >= root.top)
        assertTrue("Back keeps 48 dp", back.width >= minimum - 1f && back.height >= minimum - 1f)
        val list = composeRule.onNodeWithContentDescription("Open-source licence list")
        // Rows grow with the text instead of clipping, and the whole list scrolls.
        listOf("OpenStreetMap data, ODbL-1.0", "GraphHopper Core, Apache-2.0", "SLF4J Android Binding, MIT")
            .forEach { description ->
                list.performScrollToNode(hasContentDescription(description))
                val row = composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
                assertTrue("$description width", row.left >= root.left && row.right <= root.right + 1f)
                assertTrue("$description height ${row.height}", row.height >= minimum)
            }
        composeRule.onNodeWithContentDescription("SLF4J Android Binding, MIT").performClick()
        val text = composeRule.onNodeWithContentDescription("Licence text for SLF4J Android Binding")
        text.performScrollToNode(hasText("Permission is hereby granted", substring = true))
        // The MIT text appears twice: in SLF4J's own LICENSE.txt notice and the licence.
        val paragraph = composeRule.onAllNodes(hasText("Permission is hereby granted", substring = true))
            .onFirst().fetchSemanticsNode().boundsInRoot
        assertTrue("licence text wraps inside 320 dp", paragraph.left >= root.left && paragraph.right <= root.right + 1f)
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.runOnIdle { assertTrue("Back from the list dismisses the screen", dismissed) }
    }

    @Test
    fun fontScaleTwoKeepsTheRoundTripStepperAndSelectorUsable() {
        val density = composeRule.density
        var wish by mutableStateOf(RoundTripWish(time = RoundTripLength.Time(90)))
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                CurveMapsTheme {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                        RoutePlanPanel(
                            fromText = "Samford",
                            fromResolved = true,
                            fromHint = "Route from",
                            onFromChange = {},
                            onEditFrom = {},
                            onFromPicked = {},
                            onUseCurrentLocation = {},
                            toText = "",
                            toResolved = false,
                            onToChange = {},
                            onEditTo = {},
                            onToPicked = {},
                            geocodeController = object : GeocodeController {
                                override suspend fun search(query: String, limit: Int): List<GeocodeResult> = emptyList()
                            },
                            state = RouteUiState.Idle,
                            stage = PlannerStage.Endpoints,
                            readyStops = 0,
                            onBackToEndpoints = {},
                            onSelectRoute = {},
                            onRoute = {},
                            complexity = 0f,
                            onComplexityChange = {},
                            onComplexityChangeFinished = {},
                            onSaveRoute = { true },
                            onToggleGuidance = {},
                            guidanceActive = false,
                            gpxImportError = null,
                            onDismissGpxError = {},
                            importedGpxName = null,
                            importedGpxMilestoneCount = 0,
                            onOpenGpxPreview = {},
                            onEditImportedGpx = {},
                            roundTrip = wish,
                            onRoundTripChange = { wish = it },
                        )
                    }
                }
            }
        }
        val minimum = 48f * density.density
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        // The panel scrolls when large text outgrows the window, so bring each
        // control into view before measuring it.
        listOf(
            hasContentDescription(ROUND_TRIP_SHORTER_DESCRIPTION) and hasClickAction(),
            hasContentDescription(ROUND_TRIP_LONGER_DESCRIPTION) and hasClickAction(),
            // The value is a status, not a control, but it keeps the stepper's height.
            hasContentDescription(ROUND_TRIP_LENGTH_DESCRIPTION, substring = true),
        ).forEach { matcher ->
            composeRule.onNode(matcher).performScrollTo()
            val bounds = composeRule.onNode(matcher).fetchSemanticsNode().boundsInRoot
            assertTrue("${matcher.description} height ${bounds.height}", bounds.height >= minimum - 1f)
        }
        composeRule.onNodeWithContentDescription(ROUND_TRIP_LONGER_DESCRIPTION).performClick()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(RoundTripLength.Time(105), wish.time) }
        listOf(ROUND_TRIP_TIME_OPTION, ROUND_TRIP_DISTANCE_OPTION, ROUND_TRIP_BACK_BY_OPTION).forEach { option ->
            // The measure selector is always shown; it scrolls with the panel.
            val node = composeRule.onNode(hasText(option) and hasClickAction())
            node.performScrollTo()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$option height ${bounds.height}", bounds.height >= minimum - 1f)
            assertTrue("$option leaves the screen", bounds.top >= root.top - 1f && bounds.bottom <= root.bottom + 1f)
        }
        composeRule.onNode(hasText(ROUND_TRIP_DISTANCE_OPTION) and hasClickAction()).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(RoundTripMeasure.DISTANCE, wish.measure)
            // Picking a measure never changes another one's value.
            org.junit.Assert.assertEquals(RoundTripLength.Time(105), wish.time)
        }
    }

    private fun setRouteScreen(fontScale: Float) {
        val density = composeRule.density
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale),
            ) {
                CurveMapsTheme { RouteScreen() }
            }
        }
    }

    private fun assertStableSurfaceAndStart() {
        val start = composeRule.onNodeWithText("START")
        start.assertExists()
        start.assertHasClickAction()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val bounds = start.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= root.left)
        assertTrue(bounds.right <= root.right)
        assertTrue(bounds.bottom <= root.bottom)
    }

    @Test(timeout = 300_000)
    fun fontScaleTwoKeepsTheRideStylesPaneReachable() {
        setRouteScreen(fontScale = 2f)
        PlannerFlow.searchRideStyles(composeRule)
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val minimum = 48f * composeRule.density.density
        val slider = composeRule.onNode(KnobRobot.matcher()).fetchSemanticsNode().boundsInRoot
        assertTrue("slider remains on screen at large font sizes",
            slider.left >= root.left && slider.right <= root.right && slider.bottom <= root.bottom)
        assertTrue("slider retains a 48 dp target", slider.height >= minimum)
        listOf(
            composeRule.onNodeWithContentDescription(BACK_TO_ENDPOINTS_DESCRIPTION),
            composeRule.onNodeWithText("RIDE"),
        ).forEach { node ->
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= root.left && bounds.right <= root.right && bounds.bottom <= root.bottom)
            assertTrue(bounds.width >= minimum - 1f && bounds.height >= minimum - 1f)
        }
    }

    /**
     * A followed GPX track's pane at 2x on a 320 dp phone: the note that
     * stands where the slider would be (here the longer "leaves your track"
     * warning) stays whole on screen beside full-size Back and RIDE keys.
     */
    @Test
    fun fontScaleTwoKeepsTheFollowedGpxNoteReadableAt320Dp() {
        val density = composeRule.density
        val path = com.graphhopper.ResponsePath().apply {
            points = com.graphhopper.util.PointList().apply {
                add(-27.3717, 152.8867)
                add(-27.3311, 152.7614)
                add(-27.3963, 152.7843)
            }
            distance = 55_900.0
            time = 4_200_000L
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                CurveMapsTheme {
                    Column(Modifier.requiredSize(320.dp, 760.dp), verticalArrangement = Arrangement.Bottom) {
                        RoutePlanPanel(
                            fromText = "Samford",
                            fromResolved = true,
                            fromHint = "Route from",
                            onFromChange = {},
                            onEditFrom = {},
                            onFromPicked = {},
                            onUseCurrentLocation = {},
                            toText = "Samford",
                            toResolved = true,
                            onToChange = {},
                            onEditTo = {},
                            onToPicked = {},
                            geocodeController = object : GeocodeController {
                                override suspend fun search(query: String, limit: Int): List<GeocodeResult> = emptyList()
                            },
                            state = RouteUiState.Success(com.curvemaps.app.routing.RouteResult(listOf(path))),
                            stage = PlannerStage.Styles,
                            readyStops = 7,
                            onBackToEndpoints = {},
                            onSelectRoute = {},
                            onRoute = {},
                            complexity = 0f,
                            onComplexityChange = {},
                            onComplexityChangeFinished = {},
                            onSaveRoute = { true },
                            onToggleGuidance = {},
                            guidanceActive = false,
                            gpxImportError = null,
                            onDismissGpxError = {},
                            importedGpxName = "Mount Glorious loop",
                            importedGpxMilestoneCount = 7,
                            onOpenGpxPreview = {},
                            onEditImportedGpx = {},
                            followedGpx = com.curvemaps.app.FollowedGpx("Mount Glorious loop", 2_300.0),
                        )
                    }
                }
            }
        }
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val note = composeRule.onNode(
            hasContentDescription("${com.curvemaps.app.FOLLOWED_GPX_NOTE_PREFIX}: Leaves your track for 2.3 km", substring = true),
        ).fetchSemanticsNode().boundsInRoot
        assertTrue("note stays on screen: $note in $root", note.left >= root.left && note.right <= root.right && note.bottom <= root.bottom)
        assertTrue("note keeps a readable width", note.width >= 100f * density.density)
        // No slider for a followed track.
        assertTrue(composeRule.onAllNodes(KnobRobot.matcher()).fetchSemanticsNodes().isEmpty())
        val minimum = 48f * density.density
        listOf(
            composeRule.onNodeWithContentDescription(BACK_TO_ENDPOINTS_DESCRIPTION),
            composeRule.onNodeWithText("RIDE"),
        ).forEach { node ->
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= root.left && bounds.right <= root.right && bounds.bottom <= root.bottom)
            assertTrue(bounds.width >= minimum - 1f && bounds.height >= minimum - 1f)
        }
        composeRule.onNode(hasContentDescription("Route 1: Mount Glorious loop", substring = true)).assertExists()
    }
}
