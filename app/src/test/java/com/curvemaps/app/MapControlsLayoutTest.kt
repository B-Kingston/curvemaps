package com.curvemaps.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapControlsLayoutTest {

    @Test
    fun aClosedSheetCoversNothing() {
        assertEquals(0, MapControlsLayout.coveredBottomPx(mapBottomPx = 2000, sheetTopPx = null))
    }

    @Test
    fun theCoverIsTheMapBelowTheSheetTop() {
        assertEquals(450, MapControlsLayout.coveredBottomPx(mapBottomPx = 2000, sheetTopPx = 1550))
        // A sheet that starts below the map (inside the panel) covers nothing.
        assertEquals(0, MapControlsLayout.coveredBottomPx(mapBottomPx = 2000, sheetTopPx = 2100))
    }

    @Test
    fun aLiftedControlFitsOnlyBelowTheRailAndInsideTheMap() {
        // Map 0..2000, rail ends at 560, 8 px gap.
        assertTrue(fits(bottomOffset = 450 + 24, height = 144))
        // Pushed up to the rail: the control would overlap it.
        assertFalse(fits(bottomOffset = 2000 - 560 - 144, height = 144))
        assertTrue(fits(bottomOffset = 2000 - 568 - 144, height = 144))
        // A keyboard-shrunk map whose top is below the rail clamps on the map top.
        assertFalse(
            MapControlsLayout.fits(
                mapTopPx = 900, mapBottomPx = 1000, bottomOffsetPx = 0,
                controlHeightPx = 144, railBottomPx = 0, gapPx = 8,
            ),
        )
    }

    @Test
    fun liftingNeverTurnsAFittingControlIntoOneThatOverlaps() {
        // Monotone: if a control fits at an offset, it fits at every lower offset.
        for (height in listOf(144, 288)) {
            var fitted = true
            for (offset in 0..2000 step 7) {
                val fitsHere = fits(offset, height)
                if (!fitted) assertFalse("offset=$offset height=$height", fitsHere)
                fitted = fitsHere
            }
        }
    }

    @Test
    fun theMapControlGeometryMatchesTheDesign() {
        // Planner.dc.html / Ride.dc.html: 56 dp pills of 48 dp buttons, a 56 dp FAB
        // 16 dp below the zoom group, 16 dp (planner) or 12 dp (ride) from the edge.
        assertEquals(56f, MapControlsLayout.TOOLBAR_WIDTH.value, 0f)
        assertEquals(48f, MapControlsLayout.ZOOM_CELL.value, 0f)
        assertEquals(108f, MapControlsLayout.ZOOM_HEIGHT.value, 0f)
        assertEquals(56f, MapControlsLayout.CENTRE_BUTTON_SIZE.value, 0f)
        assertEquals(16f, MapControlsLayout.endPadding(rideMode = false).value, 0f)
        assertEquals(12f, MapControlsLayout.endPadding(rideMode = true).value, 0f)
    }

    @Test
    fun everyMapControlKeepsA48DpTarget() {
        assertTrue(MapControlsLayout.ZOOM_CELL >= 48.dp)
        assertTrue(MapControlsLayout.CENTRE_BUTTON_SIZE >= 48.dp)
        // The ride toggles keep their glove-sized hit box inside the 56 dp pill's axis.
        assertTrue(MapControlsLayout.RIDE_TOGGLE_TARGET >= 64.dp)
    }

    @Test
    fun theFabLiftsAboveTheDataBarInARideAndTheSheetWhilePlanning() {
        val bar = 100.dp
        val sheet = 220.dp
        assertEquals(116f, MapControlsLayout.centreBottom(rideMode = true, dataBarHeight = bar, sheetCover = sheet).value, 0f)
        assertEquals(236f, MapControlsLayout.centreBottom(rideMode = false, dataBarHeight = bar, sheetCover = sheet).value, 0f)
        assertEquals(16f, MapControlsLayout.centreBottom(rideMode = false, dataBarHeight = bar, sheetCover = 0.dp).value, 0f)
    }

    @Test
    fun theZoomGroupSitsOneGapAboveTheFab() {
        listOf(true, false).forEach { ride ->
            val centre = MapControlsLayout.centreBottom(ride, 100.dp, 220.dp)
            val zoom = MapControlsLayout.zoomBottom(ride, 100.dp, 220.dp)
            assertEquals(
                MapControlsLayout.CENTRE_BUTTON_SIZE.value + MapControlsLayout.CONTROL_GAP.value,
                zoom.value - centre.value,
                0f,
            )
        }
    }

    @Test
    fun theRideTogglesShareTheZoomGroupsVerticalAxis() {
        // Centre of the 64 dp toggle box and of the 56 dp toolbar, both measured from the edge.
        val toggleAxis = MapControlsLayout.rideToggleEndPadding().value + MapControlsLayout.RIDE_TOGGLE_TARGET.value / 2
        val toolbarAxis = MapControlsLayout.RIDE_END.value + MapControlsLayout.TOOLBAR_WIDTH.value / 2
        assertEquals(toolbarAxis, toggleAxis, 0f)
        // The FAB is as wide as the toolbar, so it shares the axis too.
        assertEquals(MapControlsLayout.TOOLBAR_WIDTH.value, MapControlsLayout.CENTRE_BUTTON_SIZE.value, 0f)
    }

    private fun fits(bottomOffset: Int, height: Int) = MapControlsLayout.fits(
        mapTopPx = 0,
        mapBottomPx = 2000,
        bottomOffsetPx = bottomOffset,
        controlHeightPx = height,
        railBottomPx = 560,
        gapPx = 8,
    )

    @Test
    fun aControlThatFitsTheColumnStaysThere() {
        assertEquals(MapControlsLayout.Slot.COLUMN, slot(bottomOffset = 474, height = 144))
    }

    @Test
    fun aShortWindowMovesAControlBesideTheToolbarInsteadOfOverlappingIt() {
        // Landscape: map 0..360, toolbar 40..300. The 144 px control cannot sit
        // above the data bar without reaching the toolbar; beside it, top-aligned
        // at 40, it ends at 184, inside the map above the 0 reserved bottom.
        val slot = MapControlsLayout.slot(
            mapTopPx = 0, mapBottomPx = 360, bottomOffsetPx = 16, controlHeightPx = 144,
            railTopPx = 40, railBottomPx = 300, reservedBottomPx = 0, gapPx = 8,
        )
        assertEquals(MapControlsLayout.Slot.BESIDE, slot)
    }

    @Test
    fun aControlWithNoRoomAnywhereIsHiddenNotOverlapped() {
        val slot = MapControlsLayout.slot(
            mapTopPx = 0, mapBottomPx = 180, bottomOffsetPx = 16, controlHeightPx = 144,
            railTopPx = 40, railBottomPx = 160, reservedBottomPx = 0, gapPx = 8,
        )
        assertEquals(MapControlsLayout.Slot.HIDDEN, slot)
    }

    @Test
    fun aRelocatedControlReservesTheDataBarOrSheet() {
        // Beside placement must stay above the reserved bottom (data bar / sheet).
        val slot = MapControlsLayout.slot(
            mapTopPx = 0, mapBottomPx = 360, bottomOffsetPx = 16, controlHeightPx = 144,
            railTopPx = 40, railBottomPx = 300, reservedBottomPx = 200, gapPx = 8,
        )
        assertEquals(MapControlsLayout.Slot.HIDDEN, slot)
    }

    @Test
    fun relocatedControlsStackLeftOfTheToolbarColumn() {
        listOf(false, true).forEach { ride ->
            val first = MapControlsLayout.besideEnd(ride, 0)
            val second = MapControlsLayout.besideEnd(ride, 1)
            val toolbarLeft = MapControlsLayout.endPadding(ride) + MapControlsLayout.TOOLBAR_WIDTH
            assertEquals(MapControlsLayout.BESIDE_GAP.value, (first - toolbarLeft).value, 0f)
            assertEquals(
                MapControlsLayout.TOOLBAR_WIDTH.value + MapControlsLayout.BESIDE_GAP.value,
                (second - first).value,
                0f,
            )
        }
    }

    @Test
    fun theCompassSharesTheToolbarColumnsAxis() {
        listOf(false, true).forEach { ride ->
            val compassAxis = MapControlsLayout.compassEnd(ride).value + MapControlsLayout.COMPASS_SIZE.value / 2
            val toolbarAxis = MapControlsLayout.endPadding(ride).value + MapControlsLayout.TOOLBAR_WIDTH.value / 2
            assertEquals(toolbarAxis, compassAxis, 0f)
        }
        assertEquals(48f, MapControlsLayout.COMPASS_SIZE.value, 0f)
        assertEquals(12f, MapControlsLayout.COMPASS_GAP.value, 0f)
    }

    private fun slot(bottomOffset: Int, height: Int) = MapControlsLayout.slot(
        mapTopPx = 0, mapBottomPx = 2000, bottomOffsetPx = bottomOffset, controlHeightPx = height,
        railTopPx = 40, railBottomPx = 560, reservedBottomPx = 0, gapPx = 8,
    )
}
