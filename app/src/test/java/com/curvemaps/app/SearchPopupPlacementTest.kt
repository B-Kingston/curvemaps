package com.curvemaps.app

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.curvemaps.app.geocoding.GeocodeResult
import com.curvemaps.app.geocoding.GeocodeResultType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPopupPlacementTest {

    @Test
    fun dropdownHeightFitsTheAvailableSpaceAboveItsAnchor() {
        assertEquals(240, maxSearchDropdownHeightPx(240, 320))
        assertEquals(320, maxSearchDropdownHeightPx(600, 320))
        assertEquals(0, maxSearchDropdownHeightPx(-12, 320))
        assertEquals(0, maxSearchDropdownHeightPx(120, -1))
        assertEquals(750, maxSearchDropdownHeightPx(906, 1_000, visibleViewportTopPx = 156))
    }

    @Test
    fun popupWaitsUntilOneCompleteResultTargetFits() {
        assertFalse(hasRoomForCompleteSearchResultRow(47, 48))
        assertTrue(hasRoomForCompleteSearchResultRow(48, 48))
        assertTrue(hasRoomForCompleteSearchResultRow(96, 48))
        assertFalse(hasRoomForCompleteSearchResultRow(200, 0))
    }

    @Test
    fun measuredTextAndPaddingProduceACompleteRowHeight() {
        assertEquals(97, searchResultRowMinimumHeightPx(46, 31, 20, 48))
        assertEquals(48, searchResultRowMinimumHeightPx(26, 0, 20, 48))
        assertEquals(48, searchResultRowMinimumHeightPx(0, 0, 20, 48))
        assertEquals(97, searchResultRowMinimumHeightPx(46, 31, 20, 0))
    }

    @Test
    fun popupSitsDirectlyAboveAnchorWhenThereIsRoom() {
        val position = AboveAnchorPopupPositionProvider().calculatePosition(
            anchorBounds = IntRect(16, 240, 216, 300),
            windowSize = IntSize(400, 800),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(200, 120),
        )

        assertEquals(16, position.x)
        assertEquals(120, position.y)
    }

    @Test
    fun popupTopNeverMovesPastTheWindowWhenItCannotFit() {
        val position = AboveAnchorPopupPositionProvider().calculatePosition(
            anchorBounds = IntRect(16, 80, 216, 140),
            windowSize = IntSize(400, 800),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(200, 120),
        )

        assertEquals(0, position.y)
    }

    @Test
    fun popupHonorsRightToLeftAlignment() {
        val position = AboveAnchorPopupPositionProvider().calculatePosition(
            anchorBounds = IntRect(250, 300, 390, 360),
            windowSize = IntSize(400, 800),
            layoutDirection = LayoutDirection.Rtl,
            popupContentSize = IntSize(180, 120),
        )

        assertEquals(210, position.x)
        assertEquals(180, position.y)
    }

    @Test
    fun popupClampsToTheWindowEdgeWhenTheAnchorLeavesTooLittleRoom() {
        val position = AboveAnchorPopupPositionProvider().calculatePosition(
            anchorBounds = IntRect(350, 300, 390, 360),
            windowSize = IntSize(400, 800),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(180, 120),
        )

        assertEquals(220, position.x)
        assertEquals(180, position.y)
    }

    @Test
    fun centeredPopupUsesTheSameHorizontalAlignmentInBothDirections() {
        val anchor = IntRect(20, 300, 420, 400)
        val popupSize = IntSize(336, 120)
        val provider = AboveAnchorPopupPositionProvider(centerHorizontally = true)

        val ltr = provider.calculatePosition(anchor, IntSize(500, 800), LayoutDirection.Ltr, popupSize)
        val rtl = provider.calculatePosition(anchor, IntSize(500, 800), LayoutDirection.Rtl, popupSize)

        assertEquals(52, ltr.x)
        assertEquals(ltr.x, rtl.x)
        assertEquals(180, ltr.y)
    }

    @Test
    fun popupShowsTwoAndAHalfRowsThenScrolls() {
        // 2.5 rows of 100 px plus the two dividers between the full rows.
        assertEquals(252, visibleSearchResultsHeightPx(rowHeightPx = 100, dividerPx = 1, maxHeightPx = 1000))
        // Never taller than the space above the panel.
        assertEquals(180, visibleSearchResultsHeightPx(rowHeightPx = 100, dividerPx = 1, maxHeightPx = 180))
    }

    @Test
    fun theChipsRowGivesWayBeforeAResultRowDoes() {
        // Room for one row only: no chips, one row.
        assertEquals(SearchPopupHeight(showsHeader = false, heightPx = 60), searchPopupHeight(60, 60, 56))
        // The chips row fits only when a whole row still fits under it.
        assertEquals(SearchPopupHeight(showsHeader = false, heightPx = 100), searchPopupHeight(100, 60, 56))
        assertEquals(SearchPopupHeight(showsHeader = true, heightPx = 116), searchPopupHeight(116, 60, 56))
        // Plenty of room: the chips row plus two and a half rows.
        assertEquals(SearchPopupHeight(showsHeader = true, heightPx = 56 + 150), searchPopupHeight(400, 60, 56))
        // No chips requested: just the rows.
        assertEquals(SearchPopupHeight(showsHeader = false, heightPx = 150), searchPopupHeight(400, 60, 0))
        // Not even one complete row: nothing shows.
        assertEquals(null, searchPopupHeight(59, 60, 56))
        assertEquals(null, searchPopupHeight(400, 0, 56))
    }

    @Test
    fun theTypedWordsAreSetInBoldWhereverTheyOccur() {
        assertEquals(listOf(0..7), searchMatchRanges("Brisbane City", "Brisbane"))
        assertEquals(listOf(6..13), searchMatchRanges("South Brisbane", "brisbane"))
        assertEquals(listOf(0..4, 6..9), searchMatchRanges("South Brisbane", "south bris"))
        // Overlapping and touching matches merge into one run.
        assertEquals(listOf(0..3), searchMatchRanges("Samford", "sam samf amf"))
        assertEquals(emptyList<IntRange>(), searchMatchRanges("Samford", ""))
        assertEquals(emptyList<IntRange>(), searchMatchRanges("Samford", "   "))
        assertEquals(emptyList<IntRange>(), searchMatchRanges("Samford", "zzz"))
    }

    @Test
    fun highlightingNeverChangesTheText() {
        val text = "South Brisbane, Queensland"
        assertEquals(text, highlightedSearchMatch(text, "bris queens").text)
        assertEquals(2, highlightedSearchMatch(text, "bris queens").spanStyles.size)
        assertEquals(0, highlightedSearchMatch(text, "").spanStyles.size)
    }

    @Test
    fun everyResultKindHasItsOwnLeadingSymbol() {
        val symbols = GeocodeResultType.entries.map(::searchResultSymbol)
        assertEquals(symbols.toSet().size, symbols.size)
        assertTrue(symbols.all { it != 0 })
    }

    private fun result(subtitle: String) = GeocodeResult(
        name = "Test place",
        subtitle = subtitle,
        type = GeocodeResultType.LOCALITY,
        lat = -27.0,
        lon = 153.0,
        score = 1.0,
    )
}
