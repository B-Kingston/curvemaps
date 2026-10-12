package com.curvemaps.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.curvemaps.app.NavigationHudFormat
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ride chrome's own colour pairs (guidance card, lane strip, data bar,
 * suggestion card, END), checked in the dark scheme the chrome is drawn in:
 * WCAG AA for text, including the readouts faded while there is no GPS fix.
 */
class RideChromeContrastTest {
    private val dark = CurveMapsDarkColors

    private fun contrast(foreground: Color, background: Color): Double {
        val fg = foreground.compositeOver(background)
        val a = fg.luminance() + 0.05
        val b = background.luminance() + 0.05
        return maxOf(a, b) / minOf(a, b)
    }

    private fun assertAa(name: String, foreground: Color, background: Color, minimum: Double = 4.5) {
        val ratio = contrast(foreground, background)
        assertTrue("$name is $ratio:1, needs $minimum:1", ratio >= minimum)
    }

    @Test
    fun cardsAndButtonsKeepTheirTextReadable() {
        assertAa("guidance card", dark.onPrimaryContainer, dark.primaryContainer)
        assertAa("B&W guidance card", dark.onSurface, dark.surfaceContainerHighest)
        assertAa("data bar", dark.onSurface, dark.surfaceContainer)
        assertAa("data bar captions", dark.onSurfaceVariant, dark.surfaceContainer)
        assertAa("END", dark.onErrorContainer, dark.errorContainer)
        assertAa("B&W END", dark.inverseOnSurface, dark.inverseSurface)
        assertAa("selected media / voice", dark.onSecondaryContainer, dark.secondaryContainer)
        assertAa("selected B&W map", dark.inverseOnSurface, dark.inverseSurface)
        assertAa("suggestion card", dark.onSurface, dark.surfaceContainerHigh)
        assertAa("suggestion summary", dark.onSurfaceVariant, dark.surfaceContainerHigh)
        assertAa("route tile", dark.onTertiaryContainer, dark.tertiaryContainer)
        assertAa("Take", dark.onTertiary, dark.tertiary)
        assertAa("No GPS chip", dark.onErrorContainer, dark.errorContainer)
        assertAa("No GPS caption", dark.error, dark.surfaceContainer)
    }

    @Test
    fun readoutsFadedWithoutAFixStayReadable() {
        val alpha = NavigationHudFormat.STALE_READOUT_ALPHA
        assertAa("faded data bar value", dark.onSurface.copy(alpha = alpha), dark.surfaceContainer)
        // The manoeuvre glyph fades less (a graphic: 3:1); the distance does not fade at all.
        val guidance = NavigationHudFormat.GUIDANCE_STALE_ALPHA
        assertTrue(guidance in alpha..1f)
        assertAa("faded guidance glyph", dark.onPrimaryContainer.copy(alpha = guidance), dark.primaryContainer, 3.0)
        assertAa("guidance distance", dark.onPrimaryContainer, dark.primaryContainer, 4.5)
        assertAa("faded B&W glyph", dark.onSurface.copy(alpha = guidance), dark.surfaceContainerHighest, 3.0)
    }

    @Test
    fun laneStripKeepsTheRecommendedLaneAndTheDimOnesApart() {
        for ((name, card, ink) in listOf(
            Triple("blue", dark.primaryContainer, dark.onPrimaryContainer),
            Triple("B&W", dark.surfaceContainerHighest, dark.onSurface),
        )) {
            val strip = lerp(card, Color.Black, 0.28f)
            // The recommended lane: strip-coloured arrow on a content-coloured cell.
            assertAa("$name recommended lane", strip, ink)
            // Other lanes: context only, but still visible against the strip.
            assertAa("$name dim lane arrows", ink.copy(alpha = 0.45f), strip, minimum = 3.0)
            assertTrue(contrast(ink, strip) > contrast(ink.copy(alpha = 0.45f), strip))
        }
    }
}
