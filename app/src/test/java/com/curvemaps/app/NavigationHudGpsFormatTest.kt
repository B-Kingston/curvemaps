package com.curvemaps.app

import com.curvemaps.app.routing.navigation.PositionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationHudGpsFormatTest {

    @Test
    fun `live sources show the measured speed and unit`() {
        for (source in listOf(PositionSource.GPS, PositionSource.RECONCILING)) {
            assertTrue(source.isLive)
            assertEquals("90", NavigationHudFormat.speedText(25.0, source))
            assertEquals("km/h", NavigationHudFormat.speedLabel(source))
            assertEquals("90 kilometres per hour", NavigationHudFormat.speedDescription("90", source))
        }
    }

    @Test
    fun `a GPS gap never shows the estimated speed as a reading`() {
        for (source in listOf(PositionSource.ESTIMATED, PositionSource.LOST)) {
            assertFalse(source.isLive)
            assertEquals("—", NavigationHudFormat.speedText(25.0, source))
            assertEquals("No GPS", NavigationHudFormat.speedLabel(source))
        }
        assertEquals(
            "GPS signal lost, position estimated",
            NavigationHudFormat.speedDescription("—", PositionSource.ESTIMATED),
        )
        assertEquals("GPS signal lost", NavigationHudFormat.speedDescription("—", PositionSource.LOST))
    }

    @Test
    fun `an unknown live speed is still a dash`() {
        assertEquals("—", NavigationHudFormat.speedText(Double.NaN, PositionSource.GPS))
    }

    @Test
    fun `the no gps chip says what guidance is doing`() {
        assertEquals("position estimated", NavigationHudFormat.noGpsDetail(PositionSource.ESTIMATED))
        assertEquals("position held", NavigationHudFormat.noGpsDetail(PositionSource.LOST))
        assertEquals(null, NavigationHudFormat.noGpsDetail(PositionSource.GPS))
        assertEquals(
            "No GPS fix, position estimated along the route",
            NavigationHudFormat.noGpsDescription(PositionSource.ESTIMATED),
        )
        assertEquals("No GPS fix, position held", NavigationHudFormat.noGpsDescription(PositionSource.LOST))
        // A stopped or off-route rider without a fix: nothing is being guessed.
        assertEquals("No GPS fix", NavigationHudFormat.noGpsDescription(PositionSource.GPS))
    }
}
