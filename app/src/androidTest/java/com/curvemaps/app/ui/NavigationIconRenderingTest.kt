package com.curvemaps.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.foundation.layout.Column
import com.curvemaps.app.NavigationHud
import com.curvemaps.app.drawTurnArrow
import com.curvemaps.app.routing.navigation.NavigationSnapshot
import com.curvemaps.app.routing.navigation.NavigationState
import com.graphhopper.util.Instruction
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pixel invariants catch missing arrowheads and clipped glyphs that semantics cannot see. */
@RunWith(AndroidJUnit4::class)
class NavigationIconRenderingTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun pendingInstructionHasAnArrowheadRatherThanABareStem() {
        composeRule.setContent {
            Canvas(Modifier.size(44.dp).background(Color.Black).testTag("arrow")) {
                drawTurnArrow(null, size.width, size.height)
            }
        }
        val pixels = composeRule.onNodeWithTag("arrow").captureToImage().toPixelMap()
        fun spanAt(fraction: Float): Int {
            val y = (pixels.height * fraction).toInt()
            val lit = (0 until pixels.width).filter { pixels[it, y].red > 0.5f }
            return if (lit.isEmpty()) 0 else lit.last() - lit.first() + 1
        }
        assertTrue("The head must visibly spread beyond the shaft", spanAt(0.33f) > spanAt(0.6f) * 1.8f)
    }

    @Test
    fun drawnGlyphsForRoundaboutsAndSidelessUTurnsStayInsideTheirCanvas() {
        val roundabout = NavigationSnapshot.TurnInfo(
            sign = Instruction.USE_ROUNDABOUT, streetName = "", distanceM = 100.0,
            roundaboutExitNumber = 2, roundaboutClockwise = true,
        )
        val leaving = roundabout.copy(sign = Instruction.LEAVE_ROUNDABOUT, roundaboutClockwise = false)
        val uTurn = NavigationSnapshot.TurnInfo(
            sign = Instruction.U_TURN_UNKNOWN, streetName = "", distanceM = 35.0, turnAngleDeg = 180.0,
        )
        val glyphs = mapOf("roundabout" to roundabout, "leaving" to leaving, "u-turn" to uTurn)
        composeRule.setContent {
            Row {
                glyphs.forEach { (name, turn) ->
                    Canvas(Modifier.size(52.dp).background(Color.Black).testTag(name)) {
                        drawTurnArrow(turn, size.width, size.height)
                    }
                }
            }
        }
        for (name in glyphs.keys) {
            val pixels = composeRule.onNodeWithTag(name).captureToImage().toPixelMap()
            var ink = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (pixels[x, y].red > 0.5f) {
                    ink++
                    assertTrue("$name touches the canvas edge", x > 0 && y > 0 && x < pixels.width - 1 && y < pixels.height - 1)
                }
            }
            assertTrue("$name must render visible ink", ink > 40)
        }
    }

    @Test
    fun everySymbolManeuverRendersVisibleInkOnTheGuidanceCard() {
        val signs = listOf(
            Instruction.CONTINUE_ON_STREET, Instruction.TURN_LEFT, Instruction.TURN_RIGHT,
            Instruction.TURN_SHARP_LEFT, Instruction.TURN_SHARP_RIGHT, Instruction.TURN_SLIGHT_LEFT,
            Instruction.TURN_SLIGHT_RIGHT, Instruction.KEEP_LEFT, Instruction.KEEP_RIGHT,
            Instruction.U_TURN_LEFT, Instruction.U_TURN_RIGHT, Instruction.FINISH, Instruction.REACHED_VIA,
            Instruction.USE_ROUNDABOUT, Instruction.U_TURN_UNKNOWN,
        )
        // One card at a time: fifteen stacked cards run far below the window,
        // and a node outside the window cannot be captured (zero-size bitmap).
        var current by androidx.compose.runtime.mutableStateOf(signs.first())
        composeRule.setContent {
            Box {
                NavigationHud(
                    NavigationSnapshot(
                        state = NavigationState.OnRoute, lat = -27.0, lon = 153.0, bearingDeg = 0.0,
                        speedMps = 0.0, speedLimitMps = Double.NaN, remainingDistanceM = 500.0,
                        remainingTimeS = 600.0, completionPercent = 0, paceDeltaS = Double.NaN,
                        turn = NavigationSnapshot.TurnInfo(current, "", 120.0),
                    ),
                )
            }
        }
        signs.forEach { sign ->
            composeRule.runOnIdle { current = sign }
            composeRule.waitForIdle()
            val pixels = composeRule.onNodeWithContentDescription("Navigation guidance:", substring = true)
                .captureToImage().toPixelMap()
            // The glyph is the card's top-left 52 dp square under 16 dp padding
            // (symbols are nudged left a little so their ink meets the text edge).
            val dp = composeRule.density.density
            var light = 0
            for (y in (16 * dp).toInt() until (68 * dp).toInt()) for (x in (8 * dp).toInt() until (68 * dp).toInt()) {
                val c = pixels[x, y]
                if (c.red > 0.8f && c.green > 0.8f && c.blue > 0.8f) light++
            }
            assertTrue("sign $sign draws no glyph on the guidance card", light > 40)
        }
    }
}
