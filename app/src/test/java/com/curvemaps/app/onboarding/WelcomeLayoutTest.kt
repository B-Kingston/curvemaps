package com.curvemaps.app.onboarding

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomeLayoutTest {
    @Test fun heroKeepsItsDesignedSizeWhereThePageHasRoom() {
        assertEquals(220f, WelcomeLayout.heroSize(220, 620.dp, 1f, sideBySide = false), 0f)
    }

    @Test fun heroShrinksWithTheHeightButNeverBelowTheFloor() {
        val tall = WelcomeLayout.heroSize(220, 500.dp, 1f, false)
        val short = WelcomeLayout.heroSize(220, 350.dp, 1f, false)
        assertTrue(short < tall)
        assertEquals(WelcomeLayout.MIN_HERO_DP.toFloat(), WelcomeLayout.heroSize(220, 120.dp, 1f, false), 0f)
    }

    @Test fun largerFontsGiveTheTextTheRoom() {
        assertTrue(WelcomeLayout.heroSize(220, 620.dp, 2f, false) < WelcomeLayout.heroSize(220, 620.dp, 1f, false))
        assertEquals(WelcomeLayout.MIN_HERO_DP.toFloat(), WelcomeLayout.heroSize(220, 400.dp, 2f, false), 0f)
    }

    @Test fun everyHeroStaysInsideItsDesignedRange() {
        for (page in WelcomePage.entries) {
            val design = WelcomeHero.spec(page).sizeDp
            for (h in listOf(80, 200, 350, 700, 1200)) for (f in listOf(1f, 1.3f, 2f)) for (side in listOf(false, true)) {
                val size = WelcomeLayout.heroSize(design, h.dp, f, side)
                assertTrue(size >= WelcomeLayout.MIN_HERO_DP && size <= design)
            }
        }
    }

    @Test fun compactIsShortAndWideOnly() {
        assertTrue(WelcomeLayout.isCompact(640.dp, 360.dp))
        assertTrue(WelcomeLayout.isCompact(840.dp, 479.dp))
        assertTrue(WelcomeLayout.isCompact(915.dp, 412.dp))
        assertFalse(WelcomeLayout.isCompact(412.dp, 915.dp))
        assertFalse(WelcomeLayout.isCompact(320.dp, 568.dp))
        assertFalse(WelcomeLayout.isCompact(1280.dp, 800.dp))
    }

    @Test fun featureListKeepsTheLastTwoWordsTogether() {
        assertTrue(WelcomeModel.WELCOME_FEATURES.first().endsWith("Max twisty"))
    }
}
