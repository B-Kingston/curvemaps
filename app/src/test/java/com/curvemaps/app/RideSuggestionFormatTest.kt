package com.curvemaps.app

import com.curvemaps.app.routing.navigation.VoiceDistanceUnit
import com.curvemaps.app.routing.suggestion.DiversionMeasure
import org.junit.Assert.assertEquals
import org.junit.Test

class RideSuggestionFormatTest {
    private val measure = DiversionMeasure(
        extraTimeS = 362.0,
        extraDistanceM = 4_020.0,
        baselineSectionTimeS = 900.0,
        baselineSectionDistanceM = 15_000.0,
        sectionDistanceM = 19_000.0,
        curvyGainM = 9_000.0,
        curvyShareGain = 0.4,
    )

    @Test
    fun summaryShowsSignedCostAndCurves() {
        assertEquals("+6 min · +4 km · 9 km curvier", RideSuggestionFormat.summary(measure, VoiceDistanceUnit.METRIC))
        assertEquals(
            "−1 min · −0.9 km · 3.5 km curvier",
            RideSuggestionFormat.summary(
                measure.copy(extraTimeS = -70.0, extraDistanceM = -900.0, curvyGainM = 3_450.0),
                VoiceDistanceUnit.METRIC,
            ),
        )
        assertEquals(
            "±0 min · ±0 km · 12 km curvier",
            RideSuggestionFormat.summary(
                measure.copy(extraTimeS = 10.0, extraDistanceM = 20.0, curvyGainM = 12_340.0),
                VoiceDistanceUnit.METRIC,
            ),
        )
    }

    @Test
    fun summaryUsesMilesForImperialRiders() {
        assertEquals("+6 min · +2.5 mi · 5.6 mi curvier", RideSuggestionFormat.summary(measure, VoiceDistanceUnit.IMPERIAL))
    }

    @Test
    fun countdownRingEmptiesAsTheRiderApproachesTheFork() {
        // Offered at 1,000 m with the fork at 3,000 m.
        assertEquals(1f, RideSuggestionFormat.remainingFraction(3_000.0, 1_000.0, 1_000.0), 1e-6f)
        assertEquals(0.5f, RideSuggestionFormat.remainingFraction(3_000.0, 1_000.0, 2_000.0), 1e-6f)
        assertEquals(0f, RideSuggestionFormat.remainingFraction(3_000.0, 1_000.0, 3_000.0), 1e-6f)
        // Clamped either side, and empty when the fork is not ahead of the offer.
        assertEquals(1f, RideSuggestionFormat.remainingFraction(3_000.0, 1_000.0, 500.0), 1e-6f)
        assertEquals(0f, RideSuggestionFormat.remainingFraction(3_000.0, 1_000.0, 3_500.0), 1e-6f)
        assertEquals(0f, RideSuggestionFormat.remainingFraction(1_000.0, 1_000.0, 1_000.0), 0f)
        assertEquals(0f, RideSuggestionFormat.remainingFraction(Double.NaN, 1_000.0, 1_000.0), 0f)
    }
}
