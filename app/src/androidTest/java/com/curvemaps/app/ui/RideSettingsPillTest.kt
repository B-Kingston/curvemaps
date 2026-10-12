package com.curvemaps.app.ui

import com.curvemaps.app.ui.theme.CurveMapsDarkColors
import com.curvemaps.app.ui.theme.CurveMapsTheme
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.curvemaps.app.MapControlsLayout
import com.curvemaps.app.OfflineSpeechStatus
import com.curvemaps.app.RideRailHitMargin
import com.curvemaps.app.RouteActionsPill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ride-mode toggles keep 48 dp painted buttons in a 56 dp dark pill while
 * exposing glove-sized 64 dp targets.
 */
@RunWith(AndroidJUnit4::class)
class RideSettingsPillTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun soundAndDarkMapStayReachableInOneCompactPill() {
        var soundSettingsOpened = 0
        composeRule.setContent {
            var darkMapEnabled by remember { mutableStateOf(false) }
            CurveMapsTheme {
                Box(Modifier.requiredSize(width = 320.dp, height = 360.dp).background(Color.Black)) {
                    RouteActionsPill(
                        onLoadMap = {},
                        onImportGpx = {},
                        onRouteSettings = {},
                        onMapsSettings = {},
                        onVoiceSettings = { soundSettingsOpened++ },
                        onOpenSourceLicences = {},
                        voiceGuidanceEnabled = true,
                        voiceSpeechStatus = OfflineSpeechStatus.Ready("English"),
                        onOpenSavedRoutes = {},
                        settingsMenuExpanded = false,
                        onSettingsMenuExpandedChange = {},
                        rideMode = true,
                        darkRideMapEnabled = darkMapEnabled,
                        onDarkRideMapToggle = { darkMapEnabled = !darkMapEnabled },
                        monochrome = true,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Ride settings").assertExists()
        val gloveTarget = 64f * composeRule.density.density
        val sound = bounds("Voice guidance settings", substring = true)
        val voiceControl = composeRule.onNodeWithContentDescription(
            "Voice guidance settings, enabled, English offline voice ready",
        )
        voiceControl.assertHasClickAction()
        assertEquals(
            "English offline voice ready",
            voiceControl.fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription
            ],
        )
        val darkOff = bounds("Dark ride map, off")
        listOf("Voice guidance settings" to sound, "Dark ride map, off" to darkOff).forEach { (label, rect) ->
            composeRule.onNodeWithContentDescription(label, substring = label == "Voice guidance settings")
                .assertHasClickAction()
            assertTrue("$label width ${rect.width} < $gloveTarget", rect.width >= gloveTarget)
            assertTrue("$label height ${rect.height} < $gloveTarget", rect.height >= gloveTarget)
        }
        assertTrue("ride settings controls overlap: $sound / $darkOff", sound.bottom <= darkOff.top)
        assertTrue("the pill should stay narrow", bounds("Ride settings").width <= 72f * composeRule.density.density)

        // The painted pill is the dark scheme's surfaceContainer, 56 dp wide;
        // the outer 4 dp each side remains touchable.
        val density = composeRule.density.density
        val pill = CurveMapsDarkColors.surfaceContainer
        val pixels = composeRule.onNodeWithContentDescription("Ride settings").captureToImage().toPixelMap()
        // Midway between the two buttons only the pill is painted.
        val row = pixels.height / 2
        val painted = (0 until pixels.width).filter { pixels[it, row] == pill }
        val visibleWidth = painted.last() - painted.first() + 1
        assertEquals(MapControlsLayout.TOOLBAR_WIDTH.value * density, visibleWidth.toFloat(), 2f * density)
        assertEquals(128f * density, pixels.height.toFloat(), 2f)
        // RouteScreen levels the painted pill's top with the guidance card by
        // subtracting RideRailHitMargin, so the paint must start exactly there.
        val column = pixels.width / 2
        val firstPainted = (0 until pixels.height).first { pixels[column, it] == pill }
        assertEquals(
            RideRailHitMargin.value * density,
            firstPainted.toFloat(),
            2f * density,
        )
        composeRule.onNodeWithContentDescription("Voice guidance settings", substring = true)
            .performTouchInput { click(Offset(2.dp.toPx(), center.y)) }
        assertEquals(1, soundSettingsOpened)
        composeRule.onNodeWithContentDescription("Dark ride map, off")
            .performTouchInput { click(Offset(width - 2.dp.toPx(), center.y)) }
        composeRule.onNodeWithContentDescription("Dark ride map, on").assertExists()
    }

    private fun bounds(description: String, substring: Boolean = false): Rect =
        composeRule.onNodeWithContentDescription(description, substring = substring)
            .fetchSemanticsNode().boundsInRoot
}
