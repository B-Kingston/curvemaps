package com.curvemaps.app.routing.navigation

import com.graphhopper.util.Instruction
import org.junit.Assert.assertEquals
import org.junit.Test

class DiversionSpeechTest {
    private val leftOntoRidge = NavigationSnapshot.TurnInfo(
        sign = Instruction.TURN_LEFT,
        streetName = "Mount Nebo Road",
        distanceM = 0.0,
    )

    @Test
    fun offerStatesCostCurvesTurnAndBothChoices() {
        assertEquals(
            "Twistier road ahead: adds 6 minutes and is 4 kilometres longer, with 9 kilometres of extra curves. " +
                "In 1.2 kilometres, turn left onto Mount Nebo Road to take it, or carry on to stay on route.",
            NavigationSpeechFormat.diversionOffer(
                extraTimeS = 362.0,
                extraDistanceM = 4_020.0,
                curvyGainM = 9_000.0,
                turn = leftOntoRidge,
                distanceAheadM = 1_210.0,
                unit = VoiceDistanceUnit.METRIC,
            ),
        )
    }

    @Test
    fun offerIsHonestWhenTheTwistyRoadSavesTimeOrDistance() {
        val text = NavigationSpeechFormat.diversionOffer(-70.0, -900.0, 3_000.0, null, 2_000.0, VoiceDistanceUnit.METRIC)
        assertEquals(
            "Twistier road ahead: saves 1 minute and is 900 metres shorter, with 3 kilometres of extra curves. " +
                "In 2 kilometres, take the twistier road to take it, or carry on to stay on route.",
            text,
        )
        val same = NavigationSpeechFormat.diversionOffer(20.0, 10.0, 3_000.0, leftOntoRidge, 900.0, VoiceDistanceUnit.METRIC)
        assertEquals(true, same.startsWith("Twistier road ahead: takes about the same time and is about the same distance,"))
    }

    @Test
    fun offerUsesTheRidersUnits() {
        val text = NavigationSpeechFormat.diversionOffer(600.0, 3_218.7, 8_046.7, leftOntoRidge, 1_609.344, VoiceDistanceUnit.IMPERIAL)
        assertEquals(
            "Twistier road ahead: adds 10 minutes and is 2 miles longer, with 5 miles of extra curves. " +
                "In 1 mile, turn left onto Mount Nebo Road to take it, or carry on to stay on route.",
            text,
        )
    }

    @Test
    fun reminderAndConfirmation() {
        assertEquals(
            "Scenic option: turn left onto Mount Nebo Road now, or continue on the route.",
            NavigationSpeechFormat.diversionReminder(leftOntoRidge),
        )
        assertEquals("Taking the scenic route.", NavigationSpeechFormat.DIVERSION_TAKEN)
    }
}
