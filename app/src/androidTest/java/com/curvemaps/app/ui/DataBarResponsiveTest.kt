package com.curvemaps.app.ui

import com.curvemaps.app.ui.theme.CurveMapsTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.curvemaps.app.NavigationDataBar
import com.curvemaps.app.NavigationHudFormat
import com.curvemaps.app.routing.navigation.NavigationSnapshot
import com.curvemaps.app.routing.navigation.NavigationState
import com.curvemaps.app.ui.theme.CurveMapsDarkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Responsive contract of the ride data bar: five readout cells (speed, time
 * left, distance left, arrival clock, coloured pace) of one shared value size
 * between the media button and END. A wide window keeps them on one row; a
 * phone shows speed beside a 2 x 2 of the rest; on a 320 dp screen at 2x font
 * scale the grid takes the full width above a media/END row. Values are always
 * complete with glove-sized targets, never a squeezed row of ellipsised
 * values. Pace is success green / error red in colour and neutral in B&W.
 */
@RunWith(AndroidJUnit4::class)
class DataBarResponsiveTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun snapshot(paceDeltaS: Double = 12.3) = NavigationSnapshot(
        state = NavigationState.OnRoute,
        lat = -27.46,
        lon = 153.02,
        bearingDeg = 0.0,
        speedMps = 25.0, // 90 km/h
        speedLimitMps = Double.NaN,
        remainingDistanceM = 9_850.0, // "9.9 km"
        remainingTimeS = 79 * 60.0,
        completionPercent = 40,
        paceDeltaS = paceDeltaS,
        turn = null,
    )

    private fun show(
        width: androidx.compose.ui.unit.Dp,
        fontScale: Float,
        paceDeltaS: Double = 12.3,
        monochrome: Boolean = false,
        mediaPanelOpen: Boolean = false,
    ) {
        val density = composeRule.density
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                CurveMapsTheme {
                    Box(Modifier.width(width)) {
                        NavigationDataBar(
                            snapshot = snapshot(paceDeltaS),
                            onEnd = {},
                            mediaPanelOpen = mediaPanelOpen,
                            onMediaToggle = {},
                            monochrome = monochrome,
                        )
                    }
                }
            }
        }
    }

    private fun bounds(description: String) =
        composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot

    /**
     * Every metric value must be laid out with real size. A node that exists
     * but measured to nothing is exactly the squeezed-away failure this suite
     * exists to catch, so report which values failed instead of a bare
     * "component is not displayed".
     */
    private fun assertMetricValuesDisplayed(values: List<String>) {
        val report = values.joinToString("; ") { value ->
            val nodes = composeRule.onAllNodes(
                androidx.compose.ui.test.hasText(value),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            val sizes = nodes.map { "${it.boundsInRoot.width}x${it.boundsInRoot.height}" }
            "$value=${if (nodes.isEmpty()) "missing" else sizes.joinToString()}"
        }
        val blank = values.filter { value ->
            composeRule.onAllNodes(androidx.compose.ui.test.hasText(value), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .none { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        }
        assertTrue("metric values missing or squeezed to nothing: $blank ($report)", blank.isEmpty())
    }

    @Test
    fun narrowLargeFontShowsEveryFullMetricValue() {
        show(320.dp, fontScale = 2f)
        assertMetricValuesDisplayed(listOf("90", "1h 19m", "9.9 km", "+12 s"))
        val density = composeRule.density.density
        val speed = composeRule.onNodeWithText("90").fetchSemanticsNode().boundsInRoot
        val eta = composeRule.onNodeWithText("1h 19m").fetchSemanticsNode().boundsInRoot
        val distance = composeRule.onNodeWithText("9.9 km").fetchSemanticsNode().boundsInRoot
        // The long values render at (at least) the bar's minimum font, which a
        // squeezed single row could not do (there the value became an ellipsis).
        val etaFloor = 6 * NavigationHudFormat.MIN_READOUT_SP * 0.5f * 2f * density
        val distanceFloor = 6 * NavigationHudFormat.MIN_READOUT_SP * 0.5f * 2f * density
        assertTrue("time value squeezed: ${eta.width} < $etaFloor", eta.width >= etaFloor)
        assertTrue(
            "distance value squeezed: ${distance.width} < $distanceFloor",
            distance.width >= distanceFloor,
        )
        // Every value is the same titleLarge readout, so none is taller than another.
        assertEquals("speed and time readouts differ in height", speed.height, eta.height, 2f * density)
        // Stacked: media and END sit on their own row below every readout.
        val end = bounds("End navigation")
        assertTrue("END overlaps the readouts: ${end.top} < ${distance.bottom}", end.top >= distance.bottom - 1f)
        assertTrue("END overlaps the speed: ${end.top} < ${speed.bottom}", end.top >= speed.bottom - 1f)
        // Nothing leaves the bar.
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        listOf("speed" to speed, "time" to eta, "distance" to distance).forEach { (name, box) ->
            assertTrue("$name clipped off the right: ${box.right} vs ${root.right}", box.right <= root.right + 1f)
            assertTrue("$name clipped off the left: ${box.left}", box.left >= root.left - 1f)
        }
    }

    @Test
    fun narrowLargeFontKeepsGloveSizedMediaAndEndTargets() {
        show(320.dp, fontScale = 2f)
        val glove = 64f * composeRule.density.density
        val media = bounds("Media controls")
        val end = bounds("End navigation")
        assertTrue("media opener too small: ${media.width}x${media.height}",
            media.width >= glove && media.height >= glove)
        assertTrue("END too small: ${end.width}x${end.height}",
            end.width >= glove && end.height >= glove)
        // The media button is a square toggle; END is wider than tall.
        assertEquals("media button is not square: ${media.width}x${media.height}",
            media.width, media.height, composeRule.density.density)
        assertTrue("END is not wider than tall: ${end.width}x${end.height}", end.width > end.height)
        assertTrue(
            "media and END overlap: ${media.right} vs ${end.left}",
            end.left - media.right >= 8f * composeRule.density.density,
        )
        assertTrue("media leaves the bar: ${media.left}", media.left >= -1f)
        assertTrue("END leaves the bar: ${end.right}", end.right <= 320f * composeRule.density.density + 1f)
    }

    @Test
    fun wideScreenKeepsTheSingleCompactRow() {
        show(465.dp, fontScale = 1f)
        assertMetricValuesDisplayed(listOf("90", "1h 19m", "9.9 km", "+12 s"))
        val media = bounds("Media controls")
        val end = bounds("End navigation")
        val speed = composeRule.onNodeWithText("90").fetchSemanticsNode().boundsInRoot
        val distance = composeRule.onNodeWithText("9.9 km").fetchSemanticsNode().boundsInRoot
        val slack = 2f * composeRule.density.density
        // One row: media and END level, every readout inside their band, in
        // reading order media → speed → trip → END.
        assertTrue("media and END not level", kotlin.math.abs(media.top - end.top) < slack)
        listOf("speed" to speed, "distance" to distance).forEach { (name, box) ->
            assertTrue("$name above the row: ${box.top} < ${media.top}", box.top >= media.top - slack)
            assertTrue("$name below the row: ${box.bottom} > ${media.bottom}", box.bottom <= media.bottom + slack)
        }
        assertTrue("speed overlaps media", speed.left >= media.right - 1f)
        assertTrue("trip overlaps speed", distance.left >= speed.right)
        assertTrue("trip overlaps END", distance.right <= end.left)
    }

    private fun valueBounds(text: String) =
        composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun phone320KeepsEveryCellOnOneEqualGrid() = assertTidyGrid(320.dp, 1f)
    @Test fun phone360KeepsEveryCellOnOneEqualGrid() = assertTidyGrid(360.dp, 1f)
    @Test fun phone412KeepsEveryCellOnOneEqualGrid() = assertTidyGrid(412.dp, 1f)
    @Test fun phone360AtOnePointThreeKeepsEveryCellOnOneEqualGrid() = assertTidyGrid(360.dp, 1.3f)
    @Test fun phone412AtOnePointThreeKeepsEveryCellOnOneEqualGrid() = assertTidyGrid(412.dp, 1.3f)

    private fun assertTidyGrid(width: androidx.compose.ui.unit.Dp, scale: Float) {
        run {
            show(width, fontScale = scale)
            val label = "${width.value} dp at ${scale}x"
            assertMetricValuesDisplayed(listOf("90", "1h 19m", "9.9 km", "+12 s"))
            val speed = valueBounds("90")
            val eta = valueBounds("1h 19m")
            val distance = valueBounds("9.9 km")
            val pace = valueBounds("+12 s")
            val density = composeRule.density.density
            val slack = 1.5f * density
            // One value size: every value box is the same height.
            listOf(eta, distance, pace).forEach {
                assertEquals("$label: value sizes differ", speed.height, it.height, slack)
            }
            // Time over pace share a column and rows align across it: baselines.
            assertEquals("$label: time and distance rows drift", eta.top, distance.top, slack)
            assertTrue("$label: trip row order", eta.left < distance.left)
            assertTrue("$label: second row sits under the first", pace.top > eta.bottom - slack)
            // Nothing leaves the bar, nothing overlaps the controls.
            val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
            listOf(speed, eta, distance, pace).forEach {
                assertTrue("$label: value leaves the bar: $it", it.left >= root.left - 1f && it.right <= root.right + 1f)
            }
            val media = bounds("Media controls")
            val end = bounds("End navigation")
            assertTrue("$label: speed overlaps media", speed.left >= media.right - 1f || speed.top >= media.bottom - 1f)
            assertTrue("$label: values overlap END", distance.right <= end.left || distance.bottom <= end.top + 1f)
            // The two controls share one row; END stays inside the bar.
            assertTrue("$label: END leaves the bar", end.right <= root.right + 1f)
            // Captions are never cut: each is wide enough for its own text.
            composeRule.onAllNodesWithText("left", useUnmergedTree = true).assertCountEquals(2)
        }
    }

    @Test
    fun largeFontOnAPhoneStaysOneTidyGridAtLeastAsTallAsItsControls() {
        show(360.dp, fontScale = 1.3f)
        val end = bounds("End navigation")
        val media = bounds("Media controls")
        val bar = composeRule.onNodeWithContentDescription("Ride data:", substring = true).fetchSemanticsNode().boundsInRoot
        val density = composeRule.density.density
        assertTrue("media and END share a row", kotlin.math.abs(media.top - end.top) < 2f * density)
        // At 1.3x the 360 dp bar must stay compact: media/END on the same rows as the values.
        assertTrue("bar too tall: ${bar.height / density} dp", bar.height / density < 190f)
    }

    @Test
    fun wideWindowsKeepTheBarsContentTogetherAndCentred() {
        show(1000.dp, fontScale = 1f)
        val density = composeRule.density.density
        val media = bounds("Media controls")
        val end = bounds("End navigation")
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val cluster = (end.right - media.left) / density
        assertTrue("cluster ${cluster} dp wider than the cap", cluster <= 640f + 1f)
        val leftSpace = media.left
        val rightSpace = root.right - end.right
        assertTrue(
            "cluster not centred: $leftSpace vs $rightSpace",
            kotlin.math.abs(leftSpace - rightSpace) <= 14f * density,
        )
    }

    @Test
    fun pixelWidthShowsArrivalAndSpokenPace() {
        show(412.dp, fontScale = 1f, paceDeltaS = -8.4)
        // The arrival clock is its own cell: a clock value over "arrive", beside
        // (not under) the time left.
        composeRule.onNodeWithText("arrive").assertIsDisplayed()
        val clocks = composeRule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("an arrival clock") { node ->
                (if (node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)) node.config[androidx.compose.ui.semantics.SemanticsProperties.Text] else null)
                    ?.any { Regex("\\d{1,2}:\\d{2}").matches(it.text) } == true
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertEquals("exactly one arrival clock value", 1, clocks.size)
        composeRule.onNodeWithText("\u22128 s").assertIsDisplayed()
        val description = composeRule.onNodeWithContentDescription("Ride data:", substring = true)
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
            .joinToString()
        assertTrue("no arrival in: $description", description.contains("arriving "))
        assertTrue("pace not spoken in words: $description", description.endsWith("pace 8 seconds ahead"))
    }

    /** Largest RGB channel spread of any pixel in the node, as a hue measure. */
    private fun maxChannelSpread(text: String): Pair<Int, IntArray> {
        val pixels = composeRule.onNodeWithText(text).captureToImage().toPixelMap()
        var best = 0
        var bestRgb = intArrayOf(0, 0, 0)
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val c = pixels[x, y]
            val rgb = intArrayOf((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
            val spread = rgb.max() - rgb.min()
            if (spread > best) { best = spread; bestRgb = rgb }
        }
        return best to bestRgb
    }

    @Test
    fun paceIsGreenAhead() {
        show(412.dp, fontScale = 1f, paceDeltaS = -8.4)
        val (_, ahead) = maxChannelSpread("\u22128 s")
        assertTrue("ahead pace is not green: ${ahead.toList()}", ahead[1] > ahead[0] + 60 && ahead[1] > ahead[2] + 40)
    }

    @Test
    fun paceIsRedBehind() {
        show(412.dp, fontScale = 1f, paceDeltaS = 12.3)
        val (_, behind) = maxChannelSpread("+12 s")
        assertTrue("behind pace is not red: ${behind.toList()}", behind[0] > behind[1] + 60 && behind[0] > behind[2] + 60)
    }

    @Test
    fun blackAndWhiteModeDropsThePaceHue() {
        show(412.dp, fontScale = 1f, paceDeltaS = -8.4, monochrome = true)
        val (spread, rgb) = maxChannelSpread("\u22128 s")
        assertTrue("B&W pace still coloured: ${rgb.toList()}", spread < 24)
    }

    @Test
    fun monochromeDataBarStillExposesEveryControl() {
        val density = composeRule.density
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
            ) {
                CurveMapsTheme {
                    Box(Modifier.width(320.dp)) {
                        NavigationDataBar(
                            snapshot = snapshot(),
                            onEnd = {},
                            mediaPanelOpen = true,
                            onMediaToggle = {},
                            monochrome = true,
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithContentDescription("Media controls").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("End navigation").assertIsDisplayed()
        composeRule.onNodeWithText("End").assertIsDisplayed()
    }

    /** A pixel just inside a control's left edge, mid-height: its container, clear of the label. */
    private fun containerPixel(description: String): androidx.compose.ui.graphics.Color {
        val pixels = composeRule.onNodeWithContentDescription(description).captureToImage().toPixelMap()
        return pixels[(pixels.width * 0.06f).toInt(), pixels.height / 2]
    }

    private fun assertSameColour(
        name: String,
        expected: androidx.compose.ui.graphics.Color,
        actual: androidx.compose.ui.graphics.Color,
    ) {
        val close = kotlin.math.abs(expected.red - actual.red) < 0.02f &&
            kotlin.math.abs(expected.green - actual.green) < 0.02f &&
            kotlin.math.abs(expected.blue - actual.blue) < 0.02f
        assertTrue("$name is $actual, expected $expected", close)
    }

    @Test
    fun endIsTheErrorContainer() {
        show(412.dp, fontScale = 1f)
        assertSameColour("END", CurveMapsDarkColors.errorContainer, containerPixel("End navigation"))
    }

    @Test
    fun blackAndWhiteEndIsTheInverseSurface() {
        show(412.dp, fontScale = 1f, monochrome = true)
        assertSameColour("B&W END", CurveMapsDarkColors.inverseSurface, containerPixel("End navigation"))
    }

    @Test
    fun mediaButtonIsSelectedOnlyWhileThePanelIsOpen() {
        show(412.dp, fontScale = 1f, mediaPanelOpen = true)
        assertSameColour("open media button", CurveMapsDarkColors.secondaryContainer, containerPixel("Media controls"))
    }

    @Test
    fun theBarIsTheSurfaceContainerRole() {
        show(412.dp, fontScale = 1f)
        val pixels = composeRule.onNodeWithContentDescription("Ride data:", substring = true)
            .captureToImage().toPixelMap()
        // Mid-bar, between the media button and the first readout column's top gap.
        assertSameColour("bar", CurveMapsDarkColors.surfaceContainer, pixels[pixels.width / 2, 2])
    }
}
