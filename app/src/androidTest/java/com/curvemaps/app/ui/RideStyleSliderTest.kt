package com.curvemaps.app.ui

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import com.curvemaps.app.RideStyleSlider
import com.curvemaps.app.ui.theme.CurveMapsTheme
import com.curvemaps.app.ui.theme.CurveMapsLightColors
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class RideStyleSliderTest {
    @get:Rule val rule = createComposeRule()
    private val value = mutableFloatStateOf(0f)
    private val finished = mutableListOf<Float>()

    private fun show() {
        rule.setContent {
            CurveMapsTheme { RideStyleSlider(value.floatValue, { value.floatValue = it }, { finished += it }) }
        }
    }

    @Test
    fun horizontalDragsVisitSevenStopsAndCommitOncePerRelease() {
        show()
        KnobRobot.performContinuousDrag(rule, 6)
        KnobRobot.waitForLevel(rule, 6)
        assertEquals(listOf(6f), finished)
        KnobRobot.performContinuousDrag(rule, -6)
        KnobRobot.waitForLevel(rule, 0)
        assertEquals(listOf(6f, 0f), finished)
        KnobRobot.performContinuousDrag(rule, -2)
        assertEquals(listOf(6f, 0f), finished)
    }

    @Test
    fun partialDragSnapsAndCancellationRestoresWithoutCommitting() {
        show()
        KnobRobot.performPartialDrag(rule, 0.65f)
        KnobRobot.waitForLevel(rule, 1)
        assertEquals(listOf(1f), finished)
        KnobRobot.performPartialDrag(rule, 2.7f, cancelGesture = true)
        KnobRobot.waitForLevel(rule, 1)
        assertEquals(listOf(1f), finished)
        rule.runOnIdle { value.floatValue = 3f }
        KnobRobot.waitForLevel(rule, 3)
        assertEquals(listOf(1f), finished)
    }

    @Test
    fun accessibleRangeHasSevenStopsAndSetProgressCommitsNearestStop() {
        show()
        val slider = rule.onNode(KnobRobot.matcher())
        val info = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f..6f, info.range)
        assertEquals(5, info.steps)
        assertEquals("Fastest", slider.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        val bounds = slider.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height >= 48f * rule.density.density)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(3.2f) }
        KnobRobot.waitForLevel(rule, 3)
        assertEquals(listOf(3f), finished)
        assertEquals("Curvy", rule.onNode(KnobRobot.matcher()).fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        // Tap the centre of the track (the Curvy stop); no duplicate commit.
        rule.onNode(KnobRobot.matcher()).performClick()
        assertEquals(listOf(3f), finished)
    }

    @Test
    fun keyboardArrowsAndEndStopsAdjustTheSlider() {
        show()
        val slider = rule.onNode(KnobRobot.matcher())
        slider.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        slider.performKeyInput { pressKey(Key.DirectionRight) }
        KnobRobot.waitForLevel(rule, 1)
        rule.onNode(KnobRobot.matcher()).performKeyInput { pressKey(Key.MoveEnd) }
        KnobRobot.waitForLevel(rule, 6)
        rule.onNode(KnobRobot.matcher()).performKeyInput { pressKey(Key.DirectionLeft) }
        KnobRobot.waitForLevel(rule, 5)
        rule.onNode(KnobRobot.matcher()).performKeyInput { pressKey(Key.MoveHome) }
        KnobRobot.waitForLevel(rule, 0)
        assertEquals(listOf(1f, 6f, 5f, 0f), finished)
    }

    @Test
    fun rendersExactlySevenCrestsAlignedWithStopsAtDifferentWidths() {
        val width = mutableStateOf(240.dp)
        rule.setContent {
            CurveMapsTheme {
                RideStyleSlider(0f, {}, {}, Modifier.width(width.value))
            }
        }
        for (requestedWidth in listOf(240.dp, 360.dp)) {
            rule.runOnIdle { width.value = requestedWidth }
            val pixels = rule.onNode(KnobRobot.matcher()).captureToImage().toPixelMap()
            val density = rule.density.density
            val centerY = pixels.height / 2
            val top = (centerY - 10 * density).toInt()
            val bottom = (centerY - 3 * density).toInt()
            val primary = CurveMapsLightColors.primary
            val crestColumns = (0 until pixels.width).map { x ->
                (top..bottom).any { y ->
                    val color = pixels[x, y]
                    abs(color.red - primary.red) < 0.06f &&
                        abs(color.green - primary.green) < 0.06f &&
                        abs(color.blue - primary.blue) < 0.06f
                }
            }
            val crests = mutableListOf<IntRange>()
            var start = -1
            crestColumns.forEachIndexed { x, painted ->
                if (painted && start < 0) start = x
                if (!painted && start >= 0) { crests += start until x; start = -1 }
            }
            if (start >= 0) crests += start until pixels.width
            // The pill's surface halo can cut its crest into three painted
            // pieces. Join those small mask gaps before counting whole humps.
            val wholeCrests = mutableListOf<IntRange>()
            crests.forEach { crest ->
                val previous = wholeCrests.lastOrNull()
                if (previous != null && crest.first - previous.last <= 6 * density) {
                    wholeCrests[wholeCrests.lastIndex] = previous.first..crest.last
                } else {
                    wholeCrests += crest
                }
            }
            assertEquals("Exactly seven visible humps at $requestedWidth: $wholeCrests", 7, wholeCrests.size)
            val inset = com.curvemaps.app.RIDE_STYLE_SLIDER_END_INSET.value * density
            val span = pixels.width - 2 * inset
            val cap = 2 * density
            wholeCrests.forEachIndexed { level, crest ->
                val expected = inset + span * level / 6f
                when (level) {
                    // The wave starts and ends on these two crests, so each is
                    // half a crest wide: its outer edge (plus the round cap)
                    // is the stop.
                    0 -> assertEquals("Level 0 starts the wave", expected - cap, crest.first.toFloat(), 3 * density)
                    6 -> assertEquals("Level 6 ends the wave", expected + cap, crest.last.toFloat(), 3 * density)
                    else -> assertEquals(
                        "Level $level sits on its crest", expected, (crest.first + crest.last) / 2f, 3 * density,
                    )
                }
            }
            // The wave's two ends are the first and last stops: it does not
            // run on past them (the round caps add 2 dp).
            val painted = (0 until pixels.width).filter { x ->
                (0 until pixels.height).any { y ->
                    val color = pixels[x, y]
                    abs(color.red - primary.red) < 0.06f &&
                        abs(color.green - primary.green) < 0.06f &&
                        abs(color.blue - primary.blue) < 0.06f
                }
            }
            assertEquals("Wave starts at the first stop at $requestedWidth", inset, painted.first().toFloat(), 3 * density)
            assertEquals(
                "Wave ends at the last stop at $requestedWidth",
                pixels.width - inset, painted.last().toFloat(), 3 * density,
            )
        }
    }
}
