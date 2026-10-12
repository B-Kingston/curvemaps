package com.curvemaps.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Keeps the bottom-right map controls (Centre on me, the zoom pill) clear of
 * what covers the bottom of the map. Pure, so the decision is JVM-tested; all
 * values are window pixels.
 *
 * The planner's search results open as a sheet in their own popup window on
 * top of the map, so the map box itself does not shrink: without a lift the
 * sheet would cut the centre button in half and hide the zoom pill.
 */
internal object MapControlsLayout {

    // --- Map chrome geometry (Planner.dc.html / Ride.dc.html), pure so it is pinned by JVM tests. ---

    /** Width of every floating toolbar: a 48 dp button plus 4 dp of padding each side. */
    val TOOLBAR_WIDTH: Dp = 56.dp

    /** Padding inside a floating toolbar around its 48 dp buttons. */
    val TOOLBAR_PADDING: Dp = 4.dp

    /** The zoom group's two 48 dp cells, the toolbar gap between them and its padding. */
    val ZOOM_CELL: Dp = 48.dp
    val ZOOM_HEIGHT: Dp = ZOOM_CELL * 2 + 4.dp + TOOLBAR_PADDING * 2

    /** The Centre on me / Follow rider FAB (M3 default FAB). */
    val CENTRE_BUTTON_SIZE: Dp = 56.dp

    /** Gap between the zoom group and the FAB below it. */
    val CONTROL_GAP: Dp = 16.dp

    /** Distance from the screen's right edge: planner toolbars, then ride-mode controls. */
    val PLANNER_END: Dp = 16.dp
    val RIDE_END: Dp = 12.dp

    /** Touch box of each ride toggle (glove sized); the painted button inside is 48 dp. */
    val RIDE_TOGGLE_TARGET: Dp = 64.dp

    fun endPadding(rideMode: Boolean): Dp = if (rideMode) RIDE_END else PLANNER_END

    /**
     * End padding that centres a [RIDE_TOGGLE_TARGET]-wide box on the same vertical
     * axis as a [TOOLBAR_WIDTH]-wide toolbar placed at [endPadding].
     */
    fun rideToggleEndPadding(): Dp = RIDE_END - (RIDE_TOGGLE_TARGET - TOOLBAR_WIDTH) / 2

    /**
     * Bottom offset of the Centre on me FAB: above the ride data bar ([dataBarHeight])
     * in a ride, above the search-results sheet ([sheetCover]) while planning.
     */
    fun centreBottom(rideMode: Boolean, dataBarHeight: Dp, sheetCover: Dp): Dp =
        CONTROL_GAP + if (rideMode) dataBarHeight else sheetCover

    /** Bottom offset of the zoom group: [CONTROL_GAP] above the FAB. */
    fun zoomBottom(rideMode: Boolean, dataBarHeight: Dp, sheetCover: Dp): Dp =
        centreBottom(rideMode, dataBarHeight, sheetCover) + CENTRE_BUTTON_SIZE + CONTROL_GAP

    /** Map pixels hidden under the search-results sheet; 0 when it is closed. */
    fun coveredBottomPx(mapBottomPx: Int, sheetTopPx: Int?): Int =
        if (sheetTopPx == null) 0 else (mapBottomPx - sheetTopPx).coerceAtLeast(0)

    /**
     * True when a control of [controlHeightPx] whose bottom sits [bottomOffsetPx]
     * above the map's bottom edge stays inside the map and below the action
     * rail ([railBottomPx] plus [gapPx]). A control that would not fit is
     * hidden rather than drawn under the rail or the sheet.
     */
    fun fits(
        mapTopPx: Int,
        mapBottomPx: Int,
        bottomOffsetPx: Int,
        controlHeightPx: Int,
        railBottomPx: Int,
        gapPx: Int,
    ): Boolean {
        val controlTop = mapBottomPx - bottomOffsetPx - controlHeightPx
        return controlTop >= maxOf(mapTopPx, railBottomPx + gapPx)
    }

    /** Where a bottom-right map control is drawn. */
    enum class Slot {
        /** The normal bottom-right column above the data bar / search sheet. */
        COLUMN,

        /** Left of the toolbar column, top-aligned with it: short windows. */
        BESIDE,

        /** No room anywhere without overlapping something. */
        HIDDEN,
    }

    /**
     * Picks the slot for a control of [controlHeightPx]. The column is used when
     * [fits] says so; otherwise (a short window, such as phone landscape, where the
     * toolbar and the control cannot share the right edge) the control moves to the
     * column left of the toolbar, top-aligned with it ([railTopPx]), when it fits
     * above [reservedBottomPx] (data bar or search sheet) plus [gapPx]; otherwise
     * it is hidden rather than drawn over another control or off the screen.
     */
    fun slot(
        mapTopPx: Int,
        mapBottomPx: Int,
        bottomOffsetPx: Int,
        controlHeightPx: Int,
        railTopPx: Int,
        railBottomPx: Int,
        reservedBottomPx: Int,
        gapPx: Int,
    ): Slot {
        if (fits(mapTopPx, mapBottomPx, bottomOffsetPx, controlHeightPx, railBottomPx, gapPx)) return Slot.COLUMN
        val besideTop = maxOf(mapTopPx, railTopPx)
        return if (besideTop + controlHeightPx <= mapBottomPx - reservedBottomPx - gapPx) Slot.BESIDE else Slot.HIDDEN
    }

    /** Gap between the toolbar column and a control relocated beside it. */
    val BESIDE_GAP: Dp = 12.dp

    /**
     * End padding of the [index]th relocated control (0 = nearest the toolbar):
     * one toolbar width plus a gap per column, past the toolbar's own [endPadding].
     */
    fun besideEnd(rideMode: Boolean, index: Int): Dp =
        endPadding(rideMode) + TOOLBAR_WIDTH + BESIDE_GAP + (TOOLBAR_WIDTH + BESIDE_GAP) * index

    /** The compass button (Ride.dc.html: 48 dp). */
    val COMPASS_SIZE: Dp = 48.dp

    /** Gap between the compass and the toolbar below it. */
    val COMPASS_GAP: Dp = 12.dp

    /**
     * End padding that centres the [COMPASS_SIZE] compass on the toolbar column's
     * axis (the column is [TOOLBAR_WIDTH] wide, [endPadding] from the edge).
     */
    fun compassEnd(rideMode: Boolean): Dp = endPadding(rideMode) + (TOOLBAR_WIDTH - COMPASS_SIZE) / 2
}
