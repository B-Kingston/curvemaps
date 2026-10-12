package com.curvemaps.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.curvemaps.app.NavigationDataBar
import com.curvemaps.app.NavigationHud
import com.curvemaps.app.NavigationHudFormat
import com.curvemaps.app.routing.navigation.NavigationSnapshot
import com.curvemaps.app.routing.navigation.NavigationState
import com.curvemaps.app.routing.navigation.PositionSource
import com.curvemaps.app.ui.theme.CurveMapsTheme
import com.graphhopper.util.Instruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The ride data bar through a GPS gap: while guidance estimates the position
 * the speedometer shows a dash over "No GPS" (an estimate is not a measured
 * speed) and says so to TalkBack; once GPS is back, including while the
 * marker is still easing onto it, the live speed returns. Holds at 320 dp /
 * 2x font scale and in B&W ride mode.
 *
 * The directions HUD pops a "No GPS" chip up under the guidance card while
 * there is no fix, and keeps it clear of the ride toolbar.
 * Meanwhile every GPS-derived readout fades (in pixels, not just semantics)
 * and the pace delta drops its hue, so nothing looks current that is not.
 */
@RunWith(AndroidJUnit4::class)
class GpsLossHudTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val source = mutableStateOf(PositionSource.GPS)
    private val gpsLost = mutableStateOf(false)

    private fun snapshot(positionSource: PositionSource, turn: NavigationSnapshot.TurnInfo? = null) = NavigationSnapshot(
        state = NavigationState.OnRoute,
        lat = -27.46,
        lon = 153.02,
        bearingDeg = 0.0,
        speedMps = 25.0, // 90 km/h
        speedLimitMps = Double.NaN,
        remainingDistanceM = 9_850.0,
        remainingTimeS = 79 * 60.0,
        completionPercent = 40,
        paceDeltaS = 12.3,
        turn = turn,
        positionSource = positionSource,
    )

    private val railClearance = 72.dp

    private fun showHud(width: Dp = 412.dp, fontScale: Float = 1f, streetName: String = "Main Street") {
        val density = composeRule.density
        val turn = NavigationSnapshot.TurnInfo(Instruction.TURN_RIGHT, streetName, 420.0)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CurveMapsTheme {
                    Box(Modifier.width(width)) {
                        NavigationHud(
                            snapshot = snapshot(source.value, turn),
                            gpsLost = gpsLost.value,
                            trailingClearance = railClearance,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    private fun descriptionNodes(prefix: String) = composeRule.onAllNodes(
        androidx.compose.ui.test.SemanticsMatcher(prefix) { node ->
            node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }
                ?.any { it.startsWith(prefix) } == true
        },
    ).fetchSemanticsNodes()

    private fun badgeBounds() = descriptionNodes("No GPS fix").single().boundsInRoot
    private fun cardBounds() = descriptionNodes("Navigation guidance:").single().boundsInRoot

    private fun show(width: Dp = 412.dp, fontScale: Float = 1f, monochrome: Boolean = false) {
        val density = composeRule.density
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CurveMapsTheme {
                    Box(Modifier.width(width)) {
                        NavigationDataBar(
                            snapshot = snapshot(source.value),
                            onEnd = {},
                            monochrome = monochrome,
                            gpsLost = gpsLost.value,
                        )
                    }
                }
            }
        }
    }

    private fun rideDataDescription(): String =
        composeRule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("ride data") { node ->
                node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }
                    ?.any { it.startsWith("Ride data:") } == true
            },
        ).fetchSemanticsNodes().single().config[SemanticsProperties.ContentDescription].single()

    private fun assertShown(text: String) {
        val nodes = composeRule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(
            "\"$text\" missing or squeezed: ${nodes.map { it.boundsInRoot }}",
            nodes.any { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f },
        )
    }

    private fun assertAbsent(text: String) {
        assertTrue(
            "\"$text\" should not be shown",
            composeRule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun estimatedPositionReplacesTheSpeedWithNoGps() {
        show()
        assertShown("90")
        assertShown(NavigationHudFormat.SPEED_UNIT)

        source.value = PositionSource.ESTIMATED
        composeRule.waitForIdle()
        assertShown(NavigationHudFormat.NO_GPS_LABEL)
        assertAbsent("90")
        assertAbsent(NavigationHudFormat.SPEED_UNIT)
        assertTrue(rideDataDescription().contains("GPS signal lost, position estimated"))
        // Distance and time left keep counting down on the estimate.
        assertShown("9.9 km")
        assertShown("1h 19m")
    }

    @Test
    fun heldEstimateSaysGpsIsLost() {
        source.value = PositionSource.LOST
        show()
        assertShown(NavigationHudFormat.NO_GPS_LABEL)
        val description = rideDataDescription()
        assertTrue(description, description.contains("GPS signal lost,"))
        assertTrue(description, !description.contains("estimated"))
    }

    @Test
    fun liveSpeedReturnsAsSoonAsGpsIsBack() {
        source.value = PositionSource.ESTIMATED
        show()
        assertShown(NavigationHudFormat.NO_GPS_LABEL)
        source.value = PositionSource.RECONCILING
        composeRule.waitForIdle()
        assertShown("90")
        assertShown(NavigationHudFormat.SPEED_UNIT)
        assertAbsent(NavigationHudFormat.NO_GPS_LABEL)
        assertTrue(rideDataDescription().startsWith("Ride data: 90 kilometres per hour"))
    }

    @Test
    fun noGpsStaysReadableOnANarrowLargeFontScreen() {
        source.value = PositionSource.ESTIMATED
        show(width = 320.dp, fontScale = 2f)
        assertShown(NavigationHudFormat.NO_GPS_LABEL)
        assertShown("9.9 km")
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val label = composeRule.onNodeWithText(NavigationHudFormat.NO_GPS_LABEL, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("label $label leaves the bar $root", label.left >= root.left && label.right <= root.right)
    }

    @Test
    fun noGpsRendersInBlackAndWhiteRideMode() {
        source.value = PositionSource.ESTIMATED
        show(monochrome = true)
        assertShown(NavigationHudFormat.NO_GPS_LABEL)
        assertEquals(1, composeRule.onAllNodes(hasText("—"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun noGpsBadgePopsUpUnderTheGuidanceCardAndLeaves() {
        showHud()
        assertTrue(descriptionNodes("No GPS fix").isEmpty())

        source.value = PositionSource.ESTIMATED
        gpsLost.value = true
        composeRule.waitForIdle()
        val badge = badgeBounds()
        val card = cardBounds()
        assertTrue("badge $badge should sit under the card $card", badge.top >= card.bottom)
        assertTrue("badge $badge should left-align with the card $card", abs(badge.left - card.left) < 2f)
        assertEquals(
            NavigationHudFormat.noGpsDescription(PositionSource.ESTIMATED),
            descriptionNodes("No GPS fix").single().config[SemanticsProperties.ContentDescription].single(),
        )
        assertShown("position estimated")

        source.value = PositionSource.RECONCILING
        gpsLost.value = false
        composeRule.waitForIdle()
        assertTrue(descriptionNodes("No GPS fix").isEmpty())
    }

    @Test
    fun estimatedPositionShowsNoBadgeWhileLocationFixesStillArrive() {
        source.value = PositionSource.ESTIMATED
        showHud()
        composeRule.waitForIdle()
        assertTrue(descriptionNodes("No GPS fix").isEmpty())
        gpsLost.value = true
        composeRule.waitForIdle()
        assertTrue(descriptionNodes("No GPS fix").isNotEmpty())
        gpsLost.value = false
        composeRule.waitForIdle()
        assertEquals(PositionSource.ESTIMATED, source.value)
        assertTrue(descriptionNodes("No GPS fix").isEmpty())
    }

    @Test
    fun aStoppedRiderWithoutAFixStillSeesTheBadge() {
        // Nothing is estimated (the source stays GPS), but there is no fix.
        gpsLost.value = true
        showHud()
        assertEquals("No GPS fix", descriptionNodes("No GPS fix").single().config[SemanticsProperties.ContentDescription].single())
        assertShown(NavigationHudFormat.NO_GPS_LABEL)
        assertAbsent("position estimated")
    }

    @Test
    fun noGpsBadgeStaysClearOfTheRailOnANarrowLargeFontScreen() {
        source.value = PositionSource.LOST
        gpsLost.value = true
        showHud(width = 320.dp, fontScale = 2f, streetName = "Mount Glorious Road via Samford Valley and Mount Nebo")
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val clearancePx = with(composeRule.density) { railClearance.toPx() }
        val badge = badgeBounds()
        val card = cardBounds()
        assertTrue("badge $badge reaches into the rail (root $root)", badge.right <= root.right - clearancePx + 1f)
        assertTrue("badge $badge overlaps the card $card", badge.top >= card.bottom)
        assertTrue("card squeezed away: $card", card.width > 0f)
        assertShown(NavigationHudFormat.NO_GPS_LABEL)
        assertShown("position held")
    }

    /** Brightest channel value of a text node's pixels (the glyphs, over the dark surface). */
    private fun peak(text: String): Int {
        val pixels = composeRule.onNodeWithText(text, useUnmergedTree = true).captureToImage().toPixelMap()
        var peak = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val c = pixels[x, y]
            peak = maxOf(peak, (maxOf(c.red, c.green, c.blue) * 255).toInt())
        }
        return peak
    }

    /** Brightest pixel's luminance (0..255): fading shows on any background, a blue card included. */
    private fun glow(text: String): Int {
        val pixels = composeRule.onNodeWithText(text, useUnmergedTree = true).captureToImage().toPixelMap()
        var best = 0f
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val c = pixels[x, y]
            best = maxOf(best, 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue)
        }
        return (best * 255).toInt()
    }

    /** Largest red/green/blue spread among a node's pixels: 0 for pure greys. */
    private fun hue(text: String): Int {
        val pixels = composeRule.onNodeWithText(text, useUnmergedTree = true).captureToImage().toPixelMap()
        var spread = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val c = pixels[x, y]
            spread = maxOf(spread, ((maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)) * 255).toInt())
        }
        return spread
    }

    private fun assertFaded(text: String, live: Int) {
        val stale = glow(text)
        assertTrue("\"$text\" should fade without GPS: live $live, stale $stale", stale < live * 0.85)
        assertTrue("\"$text\" faded out of sight: $stale", stale > 110)
    }

    @Test
    fun gpsDerivedReadoutsFadeWithoutAFixAndReturn() {
        // A stopped rider: the source stays GPS, so only the fade says "not current".
        show()
        val live = listOf("90", "9.9 km", "1h 19m", "+12 s").associateWith(::glow)
        assertTrue("pace delta should be red while live", hue("+12 s") > 60)

        gpsLost.value = true
        composeRule.waitForIdle()
        live.forEach { (text, brightness) -> assertFaded(text, brightness) }
        assertTrue("faded pace delta keeps its hue: ${hue("+12 s")}", hue("+12 s") < 12)
        assertEquals(
            NavigationHudFormat.STALE_STATE_DESCRIPTION,
            descriptionNodes("Ride data:").single().config[SemanticsProperties.StateDescription],
        )

        gpsLost.value = false
        composeRule.waitForIdle()
        live.forEach { (text, brightness) ->
            assertTrue("\"$text\" did not come back", glow(text) >= brightness - 8)
        }
    }

    @Test
    fun noGpsLabelNeverFades() {
        source.value = PositionSource.ESTIMATED
        gpsLost.value = true
        show()
        assertTrue(peak(NavigationHudFormat.NO_GPS_LABEL) > 235)
    }

    /** Brightest luminance (0..255) in the card's glyph square (top-left, under 16 dp padding). */
    private fun glyphGlow(): Int {
        val pixels = composeRule.onNodeWithContentDescription("Navigation guidance:", substring = true)
            .captureToImage().toPixelMap()
        val dp = composeRule.density.density
        var best = 0f
        for (y in (16 * dp).toInt() until (68 * dp).toInt()) for (x in (8 * dp).toInt() until (68 * dp).toInt()) {
            val c = pixels[x, y]
            best = maxOf(best, 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue)
        }
        return (best * 255).toInt()
    }

    @Test
    fun glyphFadesButTheDistanceAndTheBadgeStayBright() {
        showHud()
        val liveDistance = glow("420 m")
        val liveGlyph = glyphGlow()
        gpsLost.value = true
        composeRule.waitForIdle()
        // The turn distance is large text on the blue card with little contrast
        // to spare, so it holds full strength; the glyph carries "not current".
        val staleDistance = glow("420 m")
        assertTrue("distance must not fade: live $liveDistance, stale $staleDistance", staleDistance >= liveDistance * 0.97)
        val staleGlyph = glyphGlow()
        assertTrue("glyph should fade without GPS: live $liveGlyph, stale $staleGlyph", staleGlyph < liveGlyph * 0.92)
        assertTrue("glyph faded out of sight: $staleGlyph", staleGlyph > 110)
        assertTrue(peak(NavigationHudFormat.NO_GPS_LABEL) > 235)
        assertEquals(
            NavigationHudFormat.STALE_STATE_DESCRIPTION,
            descriptionNodes("Navigation guidance:").single().config[SemanticsProperties.StateDescription],
        )
    }
}
