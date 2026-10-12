package com.curvemaps.app

import com.graphhopper.util.Instruction
import com.curvemaps.app.routing.navigation.NavigationSnapshot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract of the ride data bar's formatting: pace delta, arrival clock and row layout. */
class NavigationHudDeltaFormatTest {

    @Test
    fun `nan renders as dash`() {
        assertEquals("—", NavigationHudFormat.deltaText(Double.NaN))
        assertEquals(PaceTrend.UNKNOWN, NavigationHudFormat.paceTrend(Double.NaN))
        assertEquals(PaceTrend.UNKNOWN, NavigationHudFormat.paceTrend(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `gained time reads as a true minus and whole seconds`() {
        assertEquals("\u22128 s", NavigationHudFormat.deltaText(-8.4))
        assertEquals("\u221236 s", NavigationHudFormat.deltaText(-35.68))
        assertEquals("\u22121 s", NavigationHudFormat.deltaText(-0.5))
        assertEquals(PaceTrend.AHEAD, NavigationHudFormat.paceTrend(-8.4))
    }

    @Test
    fun `lost time reads as plus whole seconds`() {
        assertEquals("+12 s", NavigationHudFormat.deltaText(12.34))
        assertEquals("+1 s", NavigationHudFormat.deltaText(1.0))
        assertEquals(PaceTrend.BEHIND, NavigationHudFormat.paceTrend(12.34))
    }

    @Test
    fun `under half a second is on plan`() {
        listOf(0.0, 0.04, -0.04, 0.49, -0.49).forEach { delta ->
            assertEquals("0 s", NavigationHudFormat.deltaText(delta))
            assertEquals(PaceTrend.ON_PLAN, NavigationHudFormat.paceTrend(delta))
        }
    }

    @Test
    fun `pace sign and trend always agree`() {
        val random = java.util.Random(20261006)
        repeat(2_000) {
            val delta = (random.nextDouble() - 0.5) * 400.0
            val text = NavigationHudFormat.deltaText(delta)
            when (NavigationHudFormat.paceTrend(delta)) {
                PaceTrend.AHEAD -> assertTrue("$delta -> $text", text.startsWith("\u2212") && delta < 0)
                PaceTrend.BEHIND -> assertTrue("$delta -> $text", text.startsWith("+") && delta > 0)
                PaceTrend.ON_PLAN -> assertEquals("0 s", text)
                PaceTrend.UNKNOWN -> error("finite delta $delta reported unknown")
            }
            assertEquals(
                "seconds shown for $delta",
                kotlin.math.floor(kotlin.math.abs(delta) + 0.5).toInt(),
                text.filter(Char::isDigit).toIntOrNull() ?: 0,
            )
        }
    }

    @Test
    fun `arrival clock follows the phone's hour format`() {
        val zone = java.time.ZoneId.of("Australia/Brisbane")
        val now = java.time.ZonedDateTime.of(2026, 10, 6, 19, 12, 30, 0, zone).toInstant().toEpochMilli()
        val remaining = 72 * 60.0
        assertEquals("8:24", NavigationHudFormat.arrivalClockText(now, remaining, use24Hour = false, zone = zone))
        assertEquals("20:24", NavigationHudFormat.arrivalClockText(now, remaining, use24Hour = true, zone = zone))
    }

    @Test
    fun `arrival clock crosses midnight and rejects unknown time`() {
        val zone = java.time.ZoneId.of("Australia/Brisbane")
        val now = java.time.ZonedDateTime.of(2026, 10, 6, 23, 50, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("0:15", NavigationHudFormat.arrivalClockText(now, 25 * 60.0, use24Hour = true, zone = zone))
        assertEquals("12:15", NavigationHudFormat.arrivalClockText(now, 25 * 60.0, use24Hour = false, zone = zone))
        assertEquals("—", NavigationHudFormat.arrivalClockText(now, Double.NaN, use24Hour = true, zone = zone))
        assertEquals("—", NavigationHudFormat.arrivalClockText(now, -1.0, use24Hour = true, zone = zone))
    }

    @Test
    fun `maneuver descriptions use GraphHopper sign constants`() {
        fun turn(sign: Int) = NavigationSnapshot.TurnInfo(sign, "", 120.0)

        assertEquals("make a U-turn in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.U_TURN_RIGHT)))
        assertEquals("make a U-turn in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.U_TURN_UNKNOWN)))
        assertEquals("enter the roundabout in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.USE_ROUNDABOUT)))
        assertEquals("exit the roundabout in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.LEAVE_ROUNDABOUT)))
        assertEquals("veer slightly left in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.TURN_SLIGHT_LEFT)))
        assertEquals("veer slightly right in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.TURN_SLIGHT_RIGHT)))
        assertEquals("keep left in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.KEEP_LEFT)))
        assertEquals("keep right in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.KEEP_RIGHT)))
    }

    @Test
    fun `roundabout description names the selected exit`() {
        val secondExit = NavigationSnapshot.TurnInfo(
            sign = Instruction.USE_ROUNDABOUT,
            streetName = "",
            distanceM = 120.0,
            roundaboutExitNumber = 2,
        )
        val twelfthExit = secondExit.copy(roundaboutExitNumber = 12)

        assertEquals(
            "enter the roundabout in 120 m, then take the 2nd exit",
            NavigationHudFormat.turnDescription(secondExit),
        )
        assertEquals(
            "enter the roundabout in 120 m, then take the 12th exit",
            NavigationHudFormat.turnDescription(twelfthExit),
        )
    }

    @Test
    fun voiceSettingsDescriptionSeparatesPreferenceFromAvailability() {
        assertEquals(
            "Voice guidance settings, enabled, checking offline voice",
            NavigationHudFormat.voiceSettingsDescription(true, "checking offline voice"),
        )
        assertEquals(
            "Voice guidance settings, enabled",
            NavigationHudFormat.voiceSettingsDescription(true),
        )
        assertEquals(
            "Voice guidance settings, disabled",
            NavigationHudFormat.voiceSettingsDescription(false, "checking offline voice"),
        )
    }

    @Test
    fun darkRideMapDescriptionStatesItsCurrentSetting() {
        assertEquals("Dark ride map, on", NavigationHudFormat.darkRideMapDescription(true))
        assertEquals("Dark ride map, off", NavigationHudFormat.darkRideMapDescription(false))
    }

    private fun turn(sign: Int, angle: Double? = null) =
        NavigationSnapshot.TurnInfo(sign = sign, streetName = "", distanceM = 100.0, turnAngleDeg = angle)

    @Test
    fun `every graphhopper manoeuvre maps to a glyph, symbols where one is faithful`() {
        val expected = mapOf(
            Instruction.CONTINUE_ON_STREET to ManeuverGlyph.STRAIGHT,
            Instruction.FERRY to ManeuverGlyph.STRAIGHT,
            Instruction.UNKNOWN to ManeuverGlyph.STRAIGHT,
            Instruction.TURN_LEFT to ManeuverGlyph.TURN_LEFT,
            Instruction.TURN_RIGHT to ManeuverGlyph.TURN_RIGHT,
            Instruction.TURN_SHARP_LEFT to ManeuverGlyph.SHARP_LEFT,
            Instruction.TURN_SHARP_RIGHT to ManeuverGlyph.SHARP_RIGHT,
            Instruction.TURN_SLIGHT_LEFT to ManeuverGlyph.SLIGHT_LEFT,
            Instruction.TURN_SLIGHT_RIGHT to ManeuverGlyph.SLIGHT_RIGHT,
            Instruction.KEEP_LEFT to ManeuverGlyph.KEEP_LEFT,
            Instruction.KEEP_RIGHT to ManeuverGlyph.KEEP_RIGHT,
            Instruction.U_TURN_LEFT to ManeuverGlyph.U_TURN_LEFT,
            Instruction.U_TURN_RIGHT to ManeuverGlyph.U_TURN_RIGHT,
            Instruction.USE_ROUNDABOUT to ManeuverGlyph.ROUNDABOUT,
            Instruction.LEAVE_ROUNDABOUT to ManeuverGlyph.ROUNDABOUT,
            Instruction.FINISH to ManeuverGlyph.FINISH,
            Instruction.REACHED_VIA to ManeuverGlyph.VIA,
        )
        expected.forEach { (sign, glyph) ->
            assertEquals("sign $sign", glyph, NavigationHudFormat.maneuverGlyph(turn(sign)))
        }
        assertEquals("waiting for an instruction", ManeuverGlyph.STRAIGHT, NavigationHudFormat.maneuverGlyph(null))
    }

    @Test
    fun `an unknown u turn picks a symbol only when route geometry gives its side`() {
        assertEquals(ManeuverGlyph.U_TURN_LEFT, NavigationHudFormat.maneuverGlyph(turn(Instruction.U_TURN_UNKNOWN, -90.0)))
        assertEquals(ManeuverGlyph.U_TURN_RIGHT, NavigationHudFormat.maneuverGlyph(turn(Instruction.U_TURN_UNKNOWN, 90.0)))
        // No side: the drawn both-ways glyph stays, a symbol would invent a side.
        assertEquals(ManeuverGlyph.U_TURN_UNKNOWN_SIDE, NavigationHudFormat.maneuverGlyph(turn(Instruction.U_TURN_UNKNOWN, 180.0)))
        assertEquals(ManeuverGlyph.U_TURN_UNKNOWN_SIDE, NavigationHudFormat.maneuverGlyph(turn(Instruction.U_TURN_UNKNOWN, Double.NaN)))
    }

    @Test
    fun `unknown u turn uses route geometry only when its side is clear`() {
        assertEquals(-1, NavigationHudFormat.uTurnSide(Instruction.U_TURN_LEFT, 90.0))
        assertEquals(1, NavigationHudFormat.uTurnSide(Instruction.U_TURN_RIGHT, -90.0))
        assertEquals(-1, NavigationHudFormat.uTurnSide(Instruction.U_TURN_UNKNOWN, -90.0))
        assertEquals(1, NavigationHudFormat.uTurnSide(Instruction.U_TURN_UNKNOWN, 90.0))
        assertEquals(0, NavigationHudFormat.uTurnSide(Instruction.U_TURN_UNKNOWN, 180.0))
        assertEquals(0, NavigationHudFormat.uTurnSide(Instruction.U_TURN_UNKNOWN, Double.NaN))
    }

    @Test
    fun maneuverAccessibilityDescriptionIncludesTheRoadName() {
        assertEquals(
            "turn left in 120 m onto Queen Street",
            NavigationHudFormat.turnDescription(
                NavigationSnapshot.TurnInfo(Instruction.TURN_LEFT, "Queen Street", 120.0),
            ),
        )
    }

    @Test
    fun `compact eta keeps the data bar narrow`() {
        assertEquals("1h 19m", NavigationHudFormat.etaCompactText(79 * 60.0))
        assertEquals("2h", NavigationHudFormat.etaCompactText(2 * 3600.0))
        assertEquals("45m", NavigationHudFormat.etaCompactText(45 * 60.0))
        assertEquals("<1m", NavigationHudFormat.etaCompactText(30.0))
        assertEquals("<1m", NavigationHudFormat.etaCompactText(0.0))
        assertEquals("12h 5m", NavigationHudFormat.etaCompactText((12 * 60 + 5) * 60.0))
    }

    @Test
    fun `delta accessibility text states direction in words`() {
        assertEquals("pace delta unavailable", NavigationHudFormat.deltaDescription(Double.NaN))
        assertEquals("pace on plan", NavigationHudFormat.deltaDescription(0.3))
        assertEquals("pace 1 second behind", NavigationHudFormat.deltaDescription(1.0))
        assertEquals("pace 12 seconds behind", NavigationHudFormat.deltaDescription(12.3))
        assertEquals("pace 8 seconds ahead", NavigationHudFormat.deltaDescription(-8.4))
    }

    private val values = listOf("88", "1h 19m", "9.9 km", "12:59", "+12 s") + NavigationHudFormat.VALUE_TEMPLATES
    private val captions = listOf("km/h", "left", "left", "arrive", "lost") + NavigationHudFormat.CAPTION_TEMPLATES

    private fun plan(width: Float, scale: Float, vals: List<String> = values) =
        NavigationHudFormat.planDataBar(width, scale, vals, captions)

    @Test
    fun narrowLargeFontStacksInsteadOfEllipsis() {
        // 320 dp at a large font cannot hold a legible value beside the
        // glove-sized media opener and END: the grid takes the full width.
        listOf(1.5f, 2f).forEach { scale ->
            assertEquals("320 dp at ${scale}x", DataBarLayout.STACKED, plan(320f, scale).layout)
        }
        // Even stacked, the size is the floor or the cap, never zero.
        assertTrue(plan(320f, 3f).valueSp > 0f)
    }

    @Test
    fun phoneWidthsUseTheGridBetweenMediaAndEnd() {
        listOf(320f, 360f, 412f).forEach { width ->
            assertEquals("$width dp at 1x", DataBarLayout.GRID, plan(width, 1f).layout)
        }
    }

    @Test
    fun wideWindowsGetTheSingleRowOfFiveEqualCells() {
        assertEquals(DataBarLayout.SINGLE_ROW, plan(465f, 1f).layout)
        assertEquals(DataBarLayout.SINGLE_ROW, plan(640f, 1f).layout)
        // A wide screen at a large font scale still takes the grid.
        assertEquals(DataBarLayout.GRID, plan(465f, 2f).layout)
    }

    @Test
    fun oneSizeForEveryCellAndItGrowsWithTheRoom() {
        val phone = plan(360f, 1f).valueSp
        val wide = plan(412f, 1f).valueSp
        assertTrue("$phone <= $wide", phone <= wide)
        assertEquals(22f, wide, 0f)
        // Fitted sizes step down together: never below the floor, never above the cap.
        listOf(0.85f, 1f, 1.3f, 2f, 3f).forEach { scale ->
            listOf(320f, 360f, 412f, 465f, 640f).forEach { width ->
                val sp = plan(width, scale).valueSp
                assertTrue("$sp <= cap at ${scale}x/$width", sp <= NavigationHudFormat.readoutSp(scale) + 1e-4f)
                assertTrue(
                    "$sp >= floor (or the cap) at ${scale}x/$width",
                    sp >= minOf(NavigationHudFormat.MIN_READOUT_SP, NavigationHudFormat.readoutSp(scale)) - 1e-4f,
                )
            }
        }
    }

    @Test
    fun theChosenSizeAlwaysHoldsEveryValueInItsCell() {
        // Whatever the layout, a value fits its cell at the chosen size unless
        // even the floor does not (only above ~2.3x on a 320 dp phone).
        listOf(1f, 1.3f, 2f).forEach { scale ->
            listOf(360f, 412f, 465f).forEach { width ->
                val result = plan(width, scale)
                val chrome = 64f + 72f + 4f + 8f + 4f
                val cell = when (result.layout) {
                    DataBarLayout.SINGLE_ROW -> (width - chrome) / 5f
                    DataBarLayout.GRID -> (width - chrome) / 3f
                    DataBarLayout.STACKED -> (width - 12f) / 3f
                } - 2 * NavigationHudFormat.CELL_PADDING_DP
                values.forEach { v ->
                    assertTrue(
                        "\"$v\" overflows its ${cell}dp cell at ${scale}x/$width (${result.layout}, ${result.valueSp}sp)",
                        NavigationHudFormat.estimatedValueWidthDp(v, result.valueSp, scale) <= cell + 1e-3f,
                    )
                }
            }
        }
    }

    @Test
    fun dataBarLayoutChromeMirrorsTheComposeLayout() {
        // The layout decision estimates the bar's fixed chrome from these
        // constants; they must be the ones the Compose row actually uses.
        assertEquals(com.curvemaps.app.media.GloveTarget.value, NavigationHudFormat.GloveTargetDp, 0f)
        assertEquals(EndPillMinWidth.value, NavigationHudFormat.EndPillWidthDp, 0f)
        assertEquals(DataBarLeadingPadding.value, NavigationHudFormat.LeadingPaddingDp, 0f)
        assertEquals(DataBarTrailingPadding.value, NavigationHudFormat.TrailingPaddingDp, 0f)
        assertEquals(DataBarEndGap.value, NavigationHudFormat.EndGapDp, 0f)
    }

    @Test
    fun dataBarIsAnIslandLikeThePlannerPanel() {
        // In a ride the bar takes the planner panel's place, so it floats the
        // same way: the planner's margin each side and below, and its corners.
        assertEquals(ROUTE_PANEL_MARGIN, DataBarMargin)
        assertEquals(ROUTE_PANEL_BOTTOM_MARGIN, DataBarBottomMargin)
        assertEquals(ROUTE_PANEL_CORNER_RADIUS, DataBarCorner)
    }

    @Test
    fun endIsWiderThanTheGloveFloorIsTall() {
        // END keeps the glove floor in both directions and is wider than it is
        // tall, so it reads as a button rather than a square.
        assertTrue(EndPillMinWidth > com.curvemaps.app.media.GloveTarget)
    }

    @Test
    fun theMediaPanelFusesOnlyIntoTheGapAboveTheRow() {
        // The island's row has a fixed gap above it; the media panel only ever
        // reaches into that gap, never onto a readout or control.
        assertTrue(DataBarVerticalPadding <= DataBarRowGap)
    }

    @Test
    fun readoutValuesStayCappedAtLargeFontScales() {
        listOf(0.85f, 1f, 1.3f, 2f).forEach { scale ->
            val value = NavigationHudFormat.readoutSp(scale) * scale
            assertTrue("a value renders at most 30 dp at ${scale}x: $value", value <= 30.01f)
        }
        // titleLarge at the default scale.
        assertEquals(22f, NavigationHudFormat.readoutSp(1f), 0f)
    }

    @Test
    fun theArrivalCellNamesItselfInsteadOfRidingUnderTheTime() {
        assertEquals("left", NavigationHudFormat.TIME_CAPTION)
        assertEquals("arrive", NavigationHudFormat.ARRIVAL_CAPTION)
    }

    @Test
    fun paceCaptionsNameWhatTheSignMeans() {
        assertEquals("pace", NavigationHudFormat.paceCaption(Double.NaN))
        assertEquals("on plan", NavigationHudFormat.paceCaption(0.2))
        assertEquals("gained", NavigationHudFormat.paceCaption(-8.4))
        assertEquals("lost", NavigationHudFormat.paceCaption(12.3))
    }

    @Test
    fun laneCellsFitTheFixedGuidanceCardAtAnyLaneCount() {
        // The strip sits in the fixed-width card under 8 dp padding each side.
        val row = NavigationHudFormat.guidanceCardWidthDp(1f) - 16f
        for (lanes in 1..10) {
            val cell = NavigationHudFormat.laneCellWidthDp(lanes, row)
            assertTrue("$lanes lanes: cell $cell", cell in 16f..56f)
            assertTrue("$lanes lanes overflow the card", lanes * cell + (lanes - 1) * 2f <= row + 1e-3f)
        }
        // Two or three lanes keep the roomy 56 dp cell.
        assertEquals(56f, NavigationHudFormat.laneCellWidthDp(3, row), 0f)
    }

    @Test
    fun guidanceCardWidthIsFixedAndGrowsWithFontScaleToACap() {
        assertEquals(212f, NavigationHudFormat.guidanceCardWidthDp(1f), 0f)
        assertEquals(212f, NavigationHudFormat.guidanceCardWidthDp(0.85f), 0f)
        assertTrue(NavigationHudFormat.guidanceCardWidthDp(1.3f) > 212f)
        assertEquals(300f, NavigationHudFormat.guidanceCardWidthDp(2f), 0f)
    }

    @Test
    fun laneCellsNarrowForWideRoadsButStayReadable() {
        assertEquals(56f, NavigationHudFormat.laneCellWidthDp(2), 0f)
        assertEquals(56f, NavigationHudFormat.laneCellWidthDp(3), 0f)
        assertTrue(NavigationHudFormat.laneCellWidthDp(6) < NavigationHudFormat.laneCellWidthDp(3))
        assertEquals(24f, NavigationHudFormat.laneCellWidthDp(10), 0f)
        assertEquals(22f, NavigationHudFormat.laneCellWidthDp(16), 0f)
        // The widest strip still fits the card's 300 dp cap with its padding.
        assertTrue(10 * NavigationHudFormat.laneCellWidthDp(10) + 16f + 9 * 2f <= 300f)
    }
}
