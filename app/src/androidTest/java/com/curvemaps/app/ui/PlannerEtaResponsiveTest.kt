package com.curvemaps.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.curvemaps.app.RouteStatusSlot
import com.curvemaps.app.RouteUiState
import com.curvemaps.app.routing.RouteResult
import com.curvemaps.app.ui.theme.CurveMapsTheme
import com.graphhopper.ResponsePath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerEtaResponsiveTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(width: Dp, fontScale: Float, hours: Int = 1) {
        val density = rule.density.density
        val path = ResponsePath().setTime((hours * 60L + 10L) * 60_000L).setDistance(79_000.0)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                CurveMapsTheme {
                    Box(Modifier.width(width)) {
                        RouteStatusSlot(RouteUiState.Success(RouteResult(listOf(path))))
                    }
                }
            }
        }
    }

    private fun assertFullText(text: String) {
        val node = rule.onNodeWithText(text).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("No text layout for $text", layouts.isNotEmpty())
        layouts.forEach { layout ->
            // The paragraph can retain the parent's width after Text measures
            // to its content. Check the visible lines, not that empty space.
            for (line in 0 until layout.lineCount) {
                assertFalse("Ellipsized text: $text", layout.isLineEllipsized(line))
                assertTrue("Clipped line: $text", layout.getLineLeft(line) >= -1f &&
                    layout.getLineRight(line) <= layout.size.width + 1f &&
                    layout.getLineBottom(line) <= layout.size.height + 1f)
            }
            assertTrue("Missing end of text: $text",
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true) == text.length)
        }
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Text outside the status row: $text", bounds.left >= root.left &&
            bounds.right <= root.right + 1f && bounds.top >= root.top && bounds.bottom <= root.bottom + 1f)
    }

    @Test
    fun narrowLargeTextKeepsEtaAndDistanceComplete() {
        show(320.dp, 2f)
        assertFullText("1 h 10 min")
        assertFullText("79 km")
        val eta = rule.onNodeWithText("1 h 10 min").fetchSemanticsNode().boundsInRoot
        val distance = rule.onNodeWithText("79 km").fetchSemanticsNode().boundsInRoot
        assertTrue("ETA and distance overlap",
            distance.left >= eta.right - 1f || distance.top >= eta.bottom - 1f)
    }

    @Test
    fun veryNarrowScreenWrapsLongEtaWithoutClipping() {
        show(200.dp, 2f, hours = 123)
        assertFullText("123 h 10 min")
        assertFullText("79 km")
        val eta = rule.onNodeWithText("123 h 10 min").fetchSemanticsNode().boundsInRoot
        val distance = rule.onNodeWithText("79 km").fetchSemanticsNode().boundsInRoot
        assertTrue("Distance must move below the long ETA", distance.top >= eta.bottom - 1f)
    }

    @Test
    fun normalTextKeepsEtaAndDistanceOnOneRow() {
        show(320.dp, 1f)
        assertFullText("1 h 10 min")
        assertFullText("79 km")
        val eta = rule.onNodeWithText("1 h 10 min").fetchSemanticsNode().boundsInRoot
        val distance = rule.onNodeWithText("79 km").fetchSemanticsNode().boundsInRoot
        assertTrue("Distance must stay beside ETA", distance.left > eta.right && distance.top < eta.bottom)
    }
}
