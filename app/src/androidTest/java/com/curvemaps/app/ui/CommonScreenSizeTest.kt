package com.curvemaps.app.ui

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowInsets
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.NavigationDataBar
import com.curvemaps.app.NavigationHud
import com.curvemaps.app.OfflineSpeechStatus
import com.curvemaps.app.RouteActionsPill
import com.curvemaps.app.RoutePlanPanel
import com.curvemaps.app.routing.RideStyle
import com.curvemaps.app.BACK_TO_ENDPOINTS_DESCRIPTION
import com.curvemaps.app.PlannerStage
import com.curvemaps.app.RouteUiState
import com.curvemaps.app.geocoding.GeocodeController
import com.curvemaps.app.geocoding.GeocodeResult
import com.curvemaps.app.onboarding.WelcomeCarousel
import com.curvemaps.app.routing.RouteResult
import com.curvemaps.app.routing.RoundTripLength
import com.curvemaps.app.routing.RoundTripWish
import com.curvemaps.app.ROUND_TRIP_TOGGLE_DESCRIPTION
import com.curvemaps.app.roundTripLengthDescription
import com.curvemaps.app.ROUND_TRIP_BACK_BY_OPTION
import com.curvemaps.app.ROUND_TRIP_DISTANCE_OPTION
import com.curvemaps.app.ROUND_TRIP_LONGER_DESCRIPTION
import com.curvemaps.app.ROUND_TRIP_SHORTER_DESCRIPTION
import com.curvemaps.app.ROUND_TRIP_TIME_OPTION
import com.curvemaps.app.routing.navigation.NavigationSnapshot
import com.curvemaps.app.routing.navigation.NavigationState
import com.curvemaps.app.ui.theme.CurveMapsTheme
import com.graphhopper.ResponsePath
import com.graphhopper.util.Instruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every production planning/ride/settings/welcome surface at every window size
 * and font scale in [UiScreenSizes]. The suite renders the real composables in
 * a forced virtual window ([DeviceConfigurationOverride.ForcedSize]) so the
 * whole finite viewport is exercised, not just a component's intrinsic size,
 * and it asserts against the *effective* density the override produces rather
 * than the device's.
 *
 * Checks are deliberately metric-first: complete values are proven from
 * [TextLayoutResult] visible glyph bounds (never `hasVisualOverflow`, which
 * keeps reporting true after Text measures to its content), and critical
 * controls must stay inside the measured viewport without overlapping. Intent
 * ellipsis on resolved endpoint rows and the HUD street name is allowed; a
 * squeezed ETA, distance or control is not.
 *
 * The four methods are the four `surface` values the visual driver keys on:
 * `planner`, `ride`, `settings`, `welcome`.
 */
@RunWith(Parameterized::class)
class CommonScreenSizeTest(private val case: UiScreenSizes.Case) {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val rule = createComposeRule()

    /**
     * Effective density/text scale of the forced window, read from inside the
     * content. Held atomically because the traversal runs on the UI thread
     * while assertions run on the instrumentation thread.
     */
    private val densityRef = java.util.concurrent.atomic.AtomicReference<Density>()
    private val measuredDensity: Density
        get() = requireNotNull(densityRef.get()) { "viewport density was never measured" }

    private val geocoder = object : GeocodeController {
        override suspend fun search(query: String, limit: Int): List<GeocodeResult> = emptyList()
    }

    companion object {
        /** Long endpoint names exercise the resolved-row ellipsis contract. */
        private const val LONG_FROM =
            "1234 Bicentennial Bikeway Service Road, Kangaroo Point, Brisbane, Queensland 4169"
        private const val LONG_TO =
            "Sunshine Coast Hinterland Great Walk Trailhead, Montville, Queensland 4560"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<UiScreenSizes.Case> = UiScreenSizes.loadCases()
    }

    // ---------------------------------------------------------------- surfaces

    @Composable
    private fun plannerPanel(stage: PlannerStage, resolved: Boolean = true, roundTrip: RoundTripWish? = null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            RoutePlanPanel(
                fromText = LONG_FROM,
                fromResolved = resolved,
                fromHint = "",
                onFromChange = {},
                onEditFrom = {},
                onFromPicked = {},
                onUseCurrentLocation = {},
                toText = LONG_TO,
                toResolved = resolved,
                onToChange = {},
                onEditTo = {},
                onToPicked = {},
                geocodeController = geocoder,
                state = RouteUiState.Success(RouteResult(listOf(routePath()))),
                stage = stage,
                readyStops = RideStyle.entries.size,
                onBackToEndpoints = {},
                onSelectRoute = {},
                onRoute = {},
                complexity = 1f,
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
                roundTrip = roundTrip,
            )
        }
    }

    @Test
    fun plannerRoundTripSettingsAndKeysFitInsideThePanel() {
        val wish = RoundTripWish(time = RoundTripLength.Time(90))
        render { plannerPanel(PlannerStage.Endpoints, roundTrip = wish) }
        assertViewportHonored()
        // To gives way to the round-trip card: the loop comes back to From.
        rule.onNodeWithContentDescription("To").assertDoesNotExist()
        val minimum = 48f * measuredDensity.density - 1f
        val valueDescription = roundTripLengthDescription(wish, 0L, use24Hour = false)
        val controls = linkedMapOf(
            "START" to hasText("START"),
            "round trip length" to hasContentDescription(valueDescription),
            ROUND_TRIP_SHORTER_DESCRIPTION to hasContentDescription(ROUND_TRIP_SHORTER_DESCRIPTION),
            ROUND_TRIP_LONGER_DESCRIPTION to hasContentDescription(ROUND_TRIP_LONGER_DESCRIPTION),
            "round trip toggle" to hasContentDescription(ROUND_TRIP_TOGGLE_DESCRIPTION),
            "current location" to hasContentDescription("Use current location"),
        )
        // The settings scroll above the pinned START in a short window: bring
        // them into view first, then take one snapshot of the layout relations
        // (the fields and settings scroll together; START stays put).
        rule.onNode(controls.getValue("round trip length")).performScrollTo()
        val at = controls.mapValues { (_, matcher) -> rule.onNode(matcher).fetchSemanticsNode().boundsInRoot }
        val start = at.getValue("START")
        val row = at.getValue("round trip length")
        val shorter = at.getValue(ROUND_TRIP_SHORTER_DESCRIPTION)
        val longer = at.getValue(ROUND_TRIP_LONGER_DESCRIPTION)
        val toggle = at.getValue("round trip toggle")
        val location = at.getValue("current location")
        assertTrue("stepper reads − value +: $shorter $row $longer", shorter.right <= row.left + 1f && row.right <= longer.left + 1f)
        assertTrue("the settings sit above START: ${row.bottom} vs ${start.top}", row.bottom <= start.top + 1f)
        assertTrue("the keys sit above the settings: ${toggle.bottom} vs ${row.top}", toggle.bottom <= row.top + 1f)
        assertTrue("current location ${location.right} sits left of the toggle ${toggle.left}", location.right <= toggle.left + 1f)
        assertEquals("the two keys share a row", location.top, toggle.top, 1.5f)
        assertTrue(
            "the keys stay a visible gap apart: ${toggle.left - location.right}",
            toggle.left - location.right >= 1f * measuredDensity.density,
        )
        // Then every control is whole inside the viewport, scrolled to if need be.
        controls.forEach { (label, matcher) ->
            val node = rule.onNode(matcher)
            // START is pinned under the scrolling fields: always in view.
            if (label != "START") node.performScrollTo()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertInViewport(label, bounds)
            assertTrue("$label keeps a 48 dp target: ${bounds.width} x ${bounds.height}",
                minOf(bounds.width, bounds.height) >= minimum)
        }
        assertTextFitsRow(wish.time.label, "round trip length", rule.onNode(controls.getValue("round trip length")).fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun plannerRoundTripMeasureSelectorFitsInsideThePanel() {
        val wish = RoundTripWish(time = RoundTripLength.Time(90))
        render { plannerPanel(PlannerStage.Endpoints, roundTrip = wish) }
        val stepper = rule.onNodeWithContentDescription(ROUND_TRIP_SHORTER_DESCRIPTION).fetchSemanticsNode().boundsInRoot
        val options = listOf(ROUND_TRIP_TIME_OPTION, ROUND_TRIP_DISTANCE_OPTION, ROUND_TRIP_BACK_BY_OPTION)
        val at = options.map { option -> rule.onNode(hasText(option) and hasClickAction()).fetchSemanticsNode().boundsInRoot }
        at.forEach { bounds ->
            // One segmented row above the stepper, left to right in menu order.
            assertTrue("selector ${bounds.bottom} sits above the stepper ${stepper.top}", bounds.bottom <= stepper.top + 1f)
            assertEquals("one row", at.first().top, bounds.top, 1.5f)
        }
        // M3 segmented buttons share their 1 dp border, so neighbours overlap by it.
        val sharedBorder = 1f * measuredDensity.density + 1f
        assertTrue(
            "options read left to right",
            at[0].right <= at[1].left + sharedBorder && at[1].right <= at[2].left + sharedBorder &&
                at[0].left < at[1].left && at[1].left < at[2].left,
        )
        options.forEach { option ->
            // A short landscape window at large text scrolls the panel.
            val node = rule.onNode(hasText(option) and hasClickAction())
            node.performScrollTo()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertInViewport("$option option", bounds)
            assertTrue("$option keeps a 48 dp target: ${bounds.height}", bounds.height >= 48f * measuredDensity.density - 1f)
        }
    }

    @Test
    fun plannerEndpointsKeepLongNamesAndStartInside() {
        render { plannerPanel(PlannerStage.Endpoints) }
        // The gallery's "planner" frame is the styles pane (below).
        assertViewportHonored()

        // Long resolved endpoints may ellipsize intentionally, but they must
        // stay laid out inside the viewport.
        assertInViewport(
            "resolved From endpoint",
            rule.onNodeWithContentDescription("From selected: $LONG_FROM")
                .fetchSemanticsNode().boundsInRoot,
        )
        assertInViewport(
            "resolved To endpoint",
            rule.onNodeWithContentDescription("To selected: $LONG_TO")
                .fetchSemanticsNode().boundsInRoot,
        )
        val start = rule.onNodeWithText("START")
        start.assertHasClickAction()
        assertInViewport("START pill", start.fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun plannerPickedPlacesFitTheirRowsBesideTheKeys() {
        render { plannerPanel(PlannerStage.Endpoints) }
        assertViewportHonored()
        val start = rule.onNodeWithText("START").fetchSemanticsNode().boundsInRoot
        val location = rule.onNodeWithContentDescription("Use current location").fetchSemanticsNode().boundsInRoot
        listOf("From" to LONG_FROM, "To" to LONG_TO).forEach { (label, name) ->
            val row = rule.onNodeWithContentDescription("$label selected: $name", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue("$label row ${row.right} overlaps the keys ${location.left}", row.right <= location.left + 1f)
            assertTrue("$label row ${row.bottom} overlaps START ${start.top}", row.bottom <= start.top + 1f)
            // The name may ellipsize sideways, never lose its line height.
            assertTextFitsRow(name, "$label name", row)
            // The label line above the name stays at every font scale.
            val labels = rule.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("$label line is missing at ${case.fontScale}x", labels.isNotEmpty())
            labels.forEach { assertTrue("$label line leaves the panel", it.boundsInRoot.bottom <= viewport().bottom + 1f) }
        }
        val from = rule.onNodeWithContentDescription("From selected: $LONG_FROM", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val to = rule.onNodeWithContentDescription("To selected: $LONG_TO", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("From ${from.bottom} overlaps To ${to.top}", from.bottom <= to.top + 1f)
        assertInViewport("START pill", start)
        captureOpportunity("endpoints")
    }

    @Test
    fun plannerTypedEndpointsFitTheirRowsBesideTheKeys() {
        render { plannerPanel(PlannerStage.Endpoints, resolved = false) }
        assertViewportHonored()
        val location = rule.onNodeWithContentDescription("Use current location").fetchSemanticsNode().boundsInRoot
        listOf("From" to LONG_FROM, "To" to LONG_TO).forEach { (label, text) ->
            val field = rule.onNodeWithContentDescription(label)
            val bounds = field.fetchSemanticsNode().boundsInRoot
            assertTrue("$label field overlaps the keys", bounds.right <= location.left + 1f)
            assertInViewport("$label field", bounds)
            val layouts = mutableListOf<TextLayoutResult>()
            field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("no text layout for $label", layouts.isNotEmpty())
            val lineHeight = layouts.first().let { it.getLineBottom(0) - it.getLineTop(0) }
            assertTrue(
                "$label field ${bounds.height} px is shorter than its $lineHeight px line of \"$text\"",
                bounds.height + 1f >= lineHeight,
            )
            // Grows only as far as its label and text need, never to fill the panel.
            val rowMinimum = 57f * measuredDensity.density
            val twoLines = lineHeight * 2f + 16f * measuredDensity.density
            assertTrue(
                "$label field ${bounds.height} px stretches past max($rowMinimum, $twoLines) px",
                bounds.height <= maxOf(rowMinimum, twoLines) + 2f,
            )
        }
        val from = rule.onNodeWithContentDescription("From").fetchSemanticsNode().boundsInRoot
        val to = rule.onNodeWithContentDescription("To").fetchSemanticsNode().boundsInRoot
        assertTrue("From ${from.bottom} overlaps To ${to.top}", from.bottom <= to.top + 1f)
    }

    @Test
    fun plannerSurfaceKeepsEtaDistanceSliderAndRidePill() {
        render { plannerPanel(PlannerStage.Styles) }
        captureOpportunity("planner")
        assertViewportHonored()

        assertTextComplete("1 h 10 min", "planner ETA")
        assertTextComplete("79 km", "planner distance")

        // The panel scrolls when the window is short: bring each control into view first.
        runCatching { rule.onNodeWithContentDescription(BACK_TO_ENDPOINTS_DESCRIPTION).performScrollTo() }
        val back = rule.onNodeWithContentDescription(BACK_TO_ENDPOINTS_DESCRIPTION).fetchSemanticsNode()
        assertInViewport("back to From and To", back.boundsInRoot)
        assertTrue(
            "back keeps a 48 dp target: ${back.boundsInRoot.width}",
            back.boundsInRoot.width >= 48f * measuredDensity.density - 1f,
        )

        runCatching { rule.onNode(hasContentDescription("Ride style level ", substring = true)).performScrollTo() }
        val slider = rule.onNode(hasContentDescription("Ride style level ", substring = true))
            .fetchSemanticsNode()
        assertInViewport("ride style slider", slider.boundsInRoot)
        assertTrue(
            "slider keeps a 48 dp target: ${slider.boundsInRoot.height}",
            slider.boundsInRoot.height >= 48f * measuredDensity.density - 1f,
        )
        assertTrue(
            "slider is not adjustable by accessibility services",
            slider.config.contains(SemanticsActions.SetProgress),
        )

        val pill = rule.onNodeWithText("RIDE")
        runCatching { pill.performScrollTo() }
        pill.assertHasClickAction()
        assertInViewport("RIDE pill", pill.fetchSemanticsNode().boundsInRoot)
        val pillBounds = pill.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "RIDE pill width ${pillBounds.width}",
            pillBounds.width >= 48f * measuredDensity.density - 1f,
        )
        assertTrue(
            "RIDE pill height ${pillBounds.height}",
            pillBounds.height >= 48f * measuredDensity.density - 1f,
        )
    }

    @Test
    fun rideSurfaceKeepsTurnAndCompleteMetrics() {
        val snapshot = navigationSnapshot()
        render {
            Box(Modifier.fillMaxSize()) {
                NavigationHud(snapshot, Modifier.align(Alignment.TopStart))
                NavigationDataBar(
                    snapshot = snapshot,
                    onEnd = {},
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        captureOpportunity("ride")
        assertViewportHonored()

        val guidance = rule.onNode(hasContentDescription("Navigation guidance:", substring = true))
        guidance.assertExists()
        assertTextComplete("350 m", "turn distance")

        assertTextComplete("90", "ride speed")
        assertTextComplete("1h 19m", "ride ETA")
        assertTextComplete("79 km", "ride distance")

        val media = rule.onNodeWithContentDescription("Media controls").fetchSemanticsNode().boundsInRoot
        val end = rule.onNodeWithContentDescription("End navigation").fetchSemanticsNode().boundsInRoot
        assertInViewport("media opener", media)
        assertInViewport("END", end)
        val glove = 64f * measuredDensity.density
        assertTrue("media opener target ${media.width}x${media.height}",
            media.width >= glove - 1f && media.height >= glove - 1f)
        assertTrue("END target ${end.width}x${end.height}",
            end.width >= glove - 1f && end.height >= glove - 1f)

        // The turn card and the data bar occupy opposite ends of the viewport
        // and must never cover each other.
        val hud = guidance.fetchSemanticsNode().boundsInRoot
        val bar = rule.onNode(hasContentDescription("Ride data:", substring = true))
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "directions HUD bottom ${hud.bottom} overlaps data bar top ${bar.top}",
            hud.bottom <= bar.top + 1f,
        )
    }

    @Test
    fun settingsSurfaceKeepsEveryRowAndPillReachable() {
        val expanded = mutableStateOf(true)
        render {
            Box(Modifier.fillMaxSize()) {
                RouteActionsPill(
                    onLoadMap = {},
                    onImportGpx = {},
                    onRouteSettings = {},
                    onMapsSettings = {},
                    onVoiceSettings = {},
                    onOpenSourceLicences = {},
                    voiceGuidanceEnabled = true,
                    voiceSpeechStatus = OfflineSpeechStatus.NotStarted,
                    onOpenSavedRoutes = {},
                    settingsMenuExpanded = expanded.value,
                    onSettingsMenuExpandedChange = { expanded.value = it },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 32.dp, end = 8.dp),
                )
            }
        }
        captureOpportunity("settings")
        assertViewportHonored()

        val minimum = 48f * measuredDensity.density
        listOf("Route settings", "Load map file", "Maps", "Import GPX route", "Sound settings", "Open-source licences")
            .forEach { label ->
                val row = rule.onNodeWithText(label)
                row.assertExists()
                row.performScrollTo()
                row.assertHasClickAction()
                val bounds = row.fetchSemanticsNode().boundsInRoot
                assertInViewport("settings row \"$label\"", bounds)
                assertTrue("settings row \"$label\" height ${bounds.height}", bounds.height >= minimum - 1f)
            }

        captureOpportunity("settings")
        listOf("Planning settings", "Saved routes").forEach { description ->
            val pill = rule.onNodeWithContentDescription(description)
            pill.assertExists()
            pill.assertHasClickAction()
            val bounds = pill.fetchSemanticsNode().boundsInRoot
            assertInViewport("settings pill \"$description\"", bounds)
            assertTrue("settings pill \"$description\" size ${bounds.width}x${bounds.height}",
                bounds.width >= minimum - 1f && bounds.height >= minimum - 1f)
        }

        rule.onNodeWithContentDescription("Planning settings").performClick()
        rule.onNodeWithText("Route settings").assertDoesNotExist()
        rule.onNodeWithContentDescription("Saved routes").assertIsDisplayed()
    }

    @Test
    fun welcomeSurfaceAcknowledgesSafetyBeforeAdvancing() {
        render {
            WelcomeCarousel(onFinish = {})
        }
        // The welcome page's Next control is the first step of the carousel.
        // The acknowledgement label only exists once SAFETY is the current
        // page, so it is a stronger signal than the (pre-composed) title.
        rule.onNodeWithText("Next").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("I understand").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Ride safely").performScrollTo()
        captureOpportunity("welcome")
        assertViewportHonored()

        rule.onNodeWithText("Ride safely").assertIsDisplayed()
        val acknowledge = rule.onNodeWithText("I understand")
        acknowledge.assertExists()
        acknowledge.assertHasClickAction()
        assertInViewport("safety acknowledgement", acknowledge.fetchSemanticsNode().boundsInRoot)

        // Acknowledging unlocks Skip and advances past the safety page: Skip
        // appears and the acknowledgement button (only ever rendered for the
        // SAFETY page) is gone.
        acknowledge.performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithContentDescription("Skip welcome").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithText("I understand").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithContentDescription("Skip welcome").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ state

    private fun navigationSnapshot() = NavigationSnapshot(
        state = NavigationState.OnRoute,
        lat = -27.46,
        lon = 153.02,
        bearingDeg = 0.0,
        speedMps = 25.0,
        speedLimitMps = Double.NaN,
        remainingDistanceM = 79_000.0,
        remainingTimeS = 79 * 60.0,
        completionPercent = 40,
        paceDeltaS = 12.3,
        turn = NavigationSnapshot.TurnInfo(
            sign = Instruction.TURN_RIGHT,
            streetName = "Sutton St",
            distanceM = 350.0,
            turnAngleDeg = 90.0,
        ),
        upcomingTurns = emptyList(),
    )

    private fun routePath(): ResponsePath =
        ResponsePath().setTime(70L * 60_000L).setDistance(79_000.0)

    // -------------------------------------------------------------- harness

    /**
     * Renders [content] in a virtual window of exactly the case's size and font
     * scale, wrapped in a tagged box that measures the viewport itself. The
     * [ForcedSize] override supplies the density (shrinking it when the
     * requested window is larger than the device), so [measuredDensity] is the
     * true effective density for every pixel assertion below.
     */
    private fun render(content: @Composable () -> Unit) {
        val configOverride = DeviceConfigurationOverride.ForcedSize(
            DpSize(case.widthDp.dp, case.heightDp.dp),
        ) then DeviceConfigurationOverride.FontScale(case.fontScale) then
            DeviceConfigurationOverride.WindowInsets(WindowInsetsCompat.CONSUMED)
        rule.setContent {
            CurveMapsTheme {
                DeviceConfigurationOverride(configOverride) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag(UiScreenSizes.VIEWPORT_TAG),
                    ) {
                        val density = LocalDensity.current
                        SideEffect { densityRef.set(density) }
                        content()
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun captureOpportunity(surface: String) {
        if (!UiScreenSizes.capturesEnabled()) return
        UiScreenSizes.exportFrame(
            rule = rule,
            label = case.label(surface),
            widthDp = case.widthDp,
            heightDp = case.heightDp,
            fontScale = case.fontScale,
            surface = surface,
        )
    }

    private fun viewport(): Rect =
        rule.onNodeWithTag(UiScreenSizes.VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot

    /** Proves the forced window was actually measured at the requested dp size. */
    private fun assertViewportHonored() {
        val bounds = viewport()
        val density = measuredDensity.density
        assertEquals("effective font scale", case.fontScale, measuredDensity.fontScale, 0.01f)
        assertEquals("measured viewport width", case.widthDp * density, bounds.width, 2f)
        assertEquals("measured viewport height", case.heightDp * density, bounds.height, 2f)
    }

    private fun assertInViewport(label: String, bounds: Rect) {
        val viewport = viewport()
        assertTrue("$label left ${bounds.left} outside viewport left ${viewport.left}",
            bounds.left >= viewport.left - 1f)
        assertTrue("$label top ${bounds.top} outside viewport top ${viewport.top}",
            bounds.top >= viewport.top - 1f)
        assertTrue("$label right ${bounds.right} outside viewport right ${viewport.right}",
            bounds.right <= viewport.right + 1f)
        assertTrue("$label bottom ${bounds.bottom} outside viewport bottom ${viewport.bottom}",
            bounds.bottom <= viewport.bottom + 1f)
    }

    /** A one-line label keeps its full line height and stays inside [row]. */
    private fun assertTextFitsRow(text: String, label: String, row: Rect) {
        val node = rule.onNodeWithText(text, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("no text layout for $label", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertTrue(
                "$label clips bottom (${layout.getLineBottom(0)} > ${layout.size.height})",
                layout.getLineBottom(0) <= layout.size.height + 1f,
            )
        }
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("$label top ${bounds.top} above its row ${row.top}", bounds.top >= row.top - 1f)
        assertTrue("$label bottom ${bounds.bottom} below its row ${row.bottom}", bounds.bottom <= row.bottom + 1f)
    }

    /**
     * A metric must render every glyph: no ellipsis, no line leaving its
     * layout box, the final visible character reaching the end of the string,
     * and the node itself inside the viewport.
     */
    private fun assertTextComplete(text: String, label: String) {
        val node = rule.onNodeWithText(text, useUnmergedTree = true)
        node.assertExists()
        // The planner panel scrolls in a short window; other surfaces do not.
        runCatching { node.performScrollTo() }
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("no text layout for $label", layouts.isNotEmpty())
        layouts.forEach { layout ->
            for (line in 0 until layout.lineCount) {
                assertFalse("$label is ellipsized: \"$text\"", layout.isLineEllipsized(line))
                assertTrue("$label clips left on line $line", layout.getLineLeft(line) >= -1f)
                assertTrue(
                    "$label clips right on line $line (${layout.getLineRight(line)} > ${layout.size.width})",
                    layout.getLineRight(line) <= layout.size.width + 1f,
                )
                assertTrue(
                    "$label clips bottom on line $line (${layout.getLineBottom(line)} > ${layout.size.height})",
                    layout.getLineBottom(line) <= layout.size.height + 1f,
                )
            }
            assertEquals(
                "$label truncates \"$text\"",
                text.length,
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true),
            )
        }
        assertInViewport(label, node.fetchSemanticsNode().boundsInRoot)
    }
}
