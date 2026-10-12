package com.curvemaps.app

import androidx.compose.ui.unit.dp
import com.curvemaps.app.routing.RoundTripLength
import com.curvemaps.app.routing.RoundTripMeasure
import com.curvemaps.app.routing.RoundTripWish
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure side of the round trip's settings card: texts, presets and the fixed dimensions. */
class RoundTripControlsTest {
    private val now = 1_700_000_000_000L

    @Test
    fun theSegmentedSelectorKeepsTheMenusThreeOptionsInOrder() {
        assertEquals(
            listOf("Ride time", "Distance", "Back by"),
            RoundTripMeasure.entries.map(::roundTripMeasureTitle),
        )
        assertEquals("Measure the ride by", ROUND_TRIP_MENU_TITLE)
    }

    @Test
    fun valueDescriptionAndCaptionFollowTheMeasureInUse() {
        val time = RoundTripWish(time = RoundTripLength.Time(90))
        assertEquals("1 h 30 min", roundTripValueLabel(time, now, use24Hour = false))
        assertEquals("Round trip length, 1 h 30 min", roundTripLengthDescription(time, now, use24Hour = false))
        assertEquals("≈ 80 km, back to From", roundTripCaption(time, now))

        val distance = RoundTripWish(measure = RoundTripMeasure.DISTANCE, distance = RoundTripLength.Distance(80))
        assertEquals("80 km", roundTripValueLabel(distance, now, use24Hour = false))
        assertEquals("Round trip length, 80 km", roundTripLengthDescription(distance, now, use24Hour = false))
        assertEquals("≈ 1 h 30 min, back to From", roundTripCaption(distance, now))
    }

    @Test
    fun backByReadsAsAClockTimeIn12And24HourForm() {
        val utc = ZoneOffset.UTC
        val epochMinute = java.time.Instant.parse("2026-10-09T17:30:00Z").epochSecond / 60
        assertEquals("5:30 pm", backByClockText(epochMinute, use24Hour = false, zone = utc))
        assertEquals("17:30", backByClockText(epochMinute, use24Hour = true, zone = utc))
        val wish = RoundTripWish(measure = RoundTripMeasure.BACK_BY, backByEpochMinute = epochMinute)
        val at = (epochMinute - 60) * 60_000L
        assertEquals("Back 5:30 pm", roundTripValueLabel(wish, at, use24Hour = false, zone = utc))
        assertEquals("Round trip length, back by 5:30 pm", roundTripLengthDescription(wish, at, use24Hour = false, zone = utc))
    }

    @Test
    fun presetsAreTheLengthModelsOwnPerMeasureAndNoneForBackBy() {
        val time = RoundTripWish()
        assertEquals(listOf("30 min", "1 h", "2 h", "3 h", "4 h"), roundTripPresets(time).map { it.label })
        val distance = time.copy(measure = RoundTripMeasure.DISTANCE)
        assertEquals(listOf("25 km", "50 km", "100 km", "150 km", "200 km"), roundTripPresets(distance).map { it.label })
        assertTrue(roundTripPresets(time.copy(measure = RoundTripMeasure.BACK_BY)).isEmpty())
    }

    @Test
    fun aPresetSetsOnlyItsOwnMeasureAndIsSelectedWhileItIsTheValue() {
        val wish = RoundTripWish(time = RoundTripLength.Time(90), distance = RoundTripLength.Distance(80))
        val twoHours = roundTripPresets(wish).first { it.label == "2 h" }
        assertFalse(wish.isPreset(twoHours))
        val set = wish.withPreset(twoHours)
        assertEquals(RoundTripLength.Time(120), set.time)
        assertEquals("the distance does not follow the time", wish.distance, set.distance)
        assertEquals(wish.measure, set.measure)
        assertTrue(set.isPreset(twoHours))

        val distance = set.copy(measure = RoundTripMeasure.DISTANCE)
        val hundred = roundTripPresets(distance).first { it.label == "100 km" }
        val setDistance = distance.withPreset(hundred)
        assertEquals(RoundTripLength.Distance(100), setDistance.distance)
        assertEquals(RoundTripLength.Time(120), setDistance.time)
        // A time preset is not selected on the distance measure, even at the same number.
        assertFalse(setDistance.isPreset(twoHours))
    }

    @Test
    fun everyPresetIsOnTheSteppersRangeAndGridSoItsLabelRoundTrips() {
        (RoundTripLength.TIME_PRESETS + RoundTripLength.DISTANCE_PRESETS).forEach { length ->
            assertEquals(length, length.coerced())
            val wish = RoundTripWish().withPreset(RoundTripPreset(length.label, length))
            assertEquals(length, if (length is RoundTripLength.Time) wish.time else wish.distance)
        }
    }

    @Test
    fun theKeysAndStepperHaveTheDesignsDimensions() {
        assertEquals(52.dp, PlannerKeys.KeyWidth)
        assertEquals(56.dp, PlannerKeys.KeyHeight)
        assertEquals(2.dp, PlannerKeys.ConnectedGap)
        assertEquals(64.dp, PlannerKeys.StepperHeight)
        assertEquals(64.dp, PlannerKeys.StepperEndWidth)
        // Every key is a 48 dp target.
        assertTrue(PlannerKeys.KeyWidth >= 48.dp && PlannerKeys.KeyHeight >= 48.dp)
        assertTrue(PlannerKeys.StepperEndWidth >= 48.dp && PlannerKeys.StepperHeight >= 48.dp)
        // Connected corners: 16 dp outside, 4 dp where the members meet.
        assertEquals(16.dp, PlannerKeys.OuterCorner)
        assertEquals(4.dp, PlannerKeys.InnerCorner)
    }
}
