package com.curvemaps.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The grouped-row corner language shared by Route settings, Voice guidance and the licences list. */
class SettingsGroupShapeTest {

    @Test
    fun aSingleRowHasBigCornersAllRound() {
        assertEquals(20.dp, SettingsGroupShape.topRadius(0, 1))
        assertEquals(20.dp, SettingsGroupShape.bottomRadius(0, 1))
    }

    @Test
    fun theFirstRowIsRoundedAtTheTopAndTheLastAtTheBottom() {
        assertEquals(20.dp, SettingsGroupShape.topRadius(0, 3))
        assertEquals(4.dp, SettingsGroupShape.bottomRadius(0, 3))
        assertEquals(4.dp, SettingsGroupShape.topRadius(1, 3))
        assertEquals(4.dp, SettingsGroupShape.bottomRadius(1, 3))
        assertEquals(4.dp, SettingsGroupShape.topRadius(2, 3))
        assertEquals(20.dp, SettingsGroupShape.bottomRadius(2, 3))
    }

    @Test
    fun theRowsOfAGroupAreTwoDpApart() {
        assertEquals(2.dp, SettingsGroupShape.RowGap)
    }

    @Test
    fun footerButtonsKeepA48DpTarget() {
        assertEquals(48.dp, SettingsFooter.ButtonHeight)
    }
}
