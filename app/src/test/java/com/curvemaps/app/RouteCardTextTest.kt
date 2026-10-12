package com.curvemaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RouteCardTextTest {
    @Test
    fun aNumberAndItsUnitNeverBreakAcrossLines() {
        val shown = keepTogether("1 h 10 min")
        assertFalse(shown.contains(' '))
        assertEquals("1 h 10 min", shown)
        assertEquals("79 km", keepTogether("79 km"))
    }

    @Test
    fun theSeparatorBetweenItemsStaysABreakPoint() {
        val detail = listOf("1 h 10 min", "79 km").joinToString(" · ") { keepTogether(it) }
        assertEquals(2, detail.split(" · ").size)
        assertEquals("1 h 10 min · 79 km", detail)
    }
}
