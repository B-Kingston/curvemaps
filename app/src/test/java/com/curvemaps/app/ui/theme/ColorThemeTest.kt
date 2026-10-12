package com.curvemaps.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The app's colours have one source: `ui/theme`. Screens read
 * `MaterialTheme.colorScheme` or [CurveMapsTheme.colors], so a stock M3
 * component can never fall back to the baseline purple and two screens can
 * never drift onto near-duplicate hex values. Both Fidelity schemes are pinned
 * to their generated values, and every text pair is held to WCAG AA in both.
 */
class ColorThemeTest {

    private val themePackage = "app/src/main/java/com/curvemaps/app/ui/theme/"

    @Test
    fun rawColorLiteralsLiveOnlyInTheThemePackage() {
        val forbidden = Regex(
            """Color\(\s*0x|Color\.(DarkGray|Gray|LightGray|Red|Green|Blue|Yellow|Cyan|Magenta)\b""",
        )
        val violations = uiSources()
            .filterNot { (relative, _) -> relative.startsWith(themePackage) }
            .flatMap { (relative, text) ->
                text.lineSequence().mapIndexedNotNull { index, line ->
                    if (forbidden.containsMatchIn(line)) "$relative:${index + 1}: ${line.trim()}" else null
                }
            }
        assertTrue(
            "Use a theme role (MaterialTheme.colorScheme, CurveMapsTheme.colors) " +
                "instead of a raw colour:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun rippleAndStateLayersAreNeverSwitchedOff() {
        // Material's ripples and state layers are on app-wide; a screen must not
        // null the ripple configuration to opt out.
        val off = Regex("""LocalRippleConfiguration\s+provides\s+null""")
        val violations = uiSources().flatMap { (relative, text) ->
            text.lineSequence().mapIndexedNotNull { index, line ->
                if (off.containsMatchIn(line)) "$relative:${index + 1}: ${line.trim()}" else null
            }.toList()
        }
        assertTrue(
            "Ripples are on (Material 3 state layers); do not disable them:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun bothSchemesPinEveryRoleToTheGeneratedFidelityValues() {
        assertPinned("light", CurveMapsLightColors, lightPins)
        assertPinned("dark", CurveMapsDarkColors, darkPins)
        // The design's two contrast overrides on the generated scheme.
        assertEquals(0xFF2F4868L, argb(CurveMapsLightColors.onSecondaryContainer))
        assertEquals(0xFFD3E4FFL, argb(CurveMapsDarkColors.onSecondaryContainer))
        // The brand blue is the container tone in both; text on it is the light tint.
        assertEquals(Color(0xFF0B70C2), CurveMapsLightColors.primaryContainer)
        assertEquals(Color(0xFF0B70C2), CurveMapsDarkColors.primaryContainer)
    }

    @Test
    fun extendedStatusRolesArePinnedForBothSchemes() {
        val light = CurveMapsLightExtendedColors
        assertEquals(
            listOf(0xFF1B6C35L, 0xFFFFFFFFL, 0xFFC6F0CDL, 0xFF00391AL, 0xFF825500L, 0xFFFFFFFFL, 0xFFFFDDB2L, 0xFF291800L),
            extended(light).map(::argb),
        )
        val dark = CurveMapsDarkExtendedColors
        assertEquals(
            listOf(0xFF8FD9A0L, 0xFF003916L, 0xFF005322L, 0xFFA4F5AFL, 0xFFFBBB5BL, 0xFF452B00L, 0xFF624000L, 0xFFFFDDB2L),
            extended(dark).map(::argb),
        )
    }

    @Test
    fun shapesFollowTheM3Scale() {
        val s = CurveMapsShapes
        assertEquals(RoundedCornerShape(4.dp), s.extraSmall)
        assertEquals(RoundedCornerShape(8.dp), s.small)
        assertEquals(RoundedCornerShape(12.dp), s.medium)
        assertEquals(RoundedCornerShape(16.dp), s.large)
        assertEquals(RoundedCornerShape(28.dp), s.extraLarge)
    }

    @Test
    fun everyComponentRoleOverridesTheBaselineSchemes() {
        // A role left at M3's baseline would paint stock components in the
        // baseline purple/mauve next to the brand blue. Roles that are
        // legitimately pure white/black in the baseline and the Fidelity scheme
        // are listed explicitly.
        val sharedWithBaseline = setOf("onPrimary", "onSecondary", "onTertiary", "onError", "scrim", "surfaceContainerLowest")
        listOf(
            Triple("light", CurveMapsLightColors, lightColorScheme()),
            Triple("dark", CurveMapsDarkColors, darkColorScheme()),
        ).forEach { (scheme, ours, baseline) ->
            val ourRoles = roles(ours)
            val baseRoles = roles(baseline)
            ourRoles.forEach { (name, color) ->
                if (name !in sharedWithBaseline) {
                    assertNotEquals("$scheme $name still uses the baseline value", baseRoles.getValue(name), color)
                }
            }
        }
    }

    @Test
    fun textRolesMeetWcagAaInBothSchemes() {
        val failures = mutableListOf<String>()
        listOf(
            Triple("light", CurveMapsLightColors, CurveMapsLightExtendedColors),
            Triple("dark", CurveMapsDarkColors, CurveMapsDarkExtendedColors),
        ).forEach { (scheme, c, x) ->
            val surfaces = mapOf(
                "background" to c.background,
                "surface" to c.surface,
                "surfaceDim" to c.surfaceDim,
                "surfaceBright" to c.surfaceBright,
                "surfaceContainerLowest" to c.surfaceContainerLowest,
                "surfaceContainerLow" to c.surfaceContainerLow,
                "surfaceContainer" to c.surfaceContainer,
                "surfaceContainerHigh" to c.surfaceContainerHigh,
                "surfaceContainerHighest" to c.surfaceContainerHighest,
            )
            val pairs = mutableListOf<Triple<String, Color, Color>>()
            pairs += listOf(
                Triple("onPrimary/primary", c.onPrimary, c.primary),
                Triple("onPrimaryContainer/primaryContainer", c.onPrimaryContainer, c.primaryContainer),
                Triple("onSecondary/secondary", c.onSecondary, c.secondary),
                Triple("onSecondaryContainer/secondaryContainer", c.onSecondaryContainer, c.secondaryContainer),
                Triple("onTertiary/tertiary", c.onTertiary, c.tertiary),
                Triple("onTertiaryContainer/tertiaryContainer", c.onTertiaryContainer, c.tertiaryContainer),
                Triple("onError/error", c.onError, c.error),
                Triple("onErrorContainer/errorContainer", c.onErrorContainer, c.errorContainer),
                Triple("error/errorContainer", c.error, c.errorContainer),
                Triple("onSurfaceVariant/surfaceVariant", c.onSurfaceVariant, c.surfaceVariant),
                Triple("onSurface/surfaceVariant", c.onSurface, c.surfaceVariant),
                Triple("inverseOnSurface/inverseSurface", c.inverseOnSurface, c.inverseSurface),
                Triple("inversePrimary/inverseSurface", c.inversePrimary, c.inverseSurface),
                Triple("onSuccess/success", x.onSuccess, x.success),
                Triple("onSuccessContainer/successContainer", x.onSuccessContainer, x.successContainer),
                Triple("onWarning/warning", x.onWarning, x.warning),
                Triple("onWarningContainer/warningContainer", x.onWarningContainer, x.warningContainer),
                Triple("onPrimaryFixed/primaryFixed", c.onPrimaryFixed, c.primaryFixed),
                Triple("onPrimaryFixedVariant/primaryFixed", c.onPrimaryFixedVariant, c.primaryFixed),
                Triple("onSecondaryFixed/secondaryFixed", c.onSecondaryFixed, c.secondaryFixed),
                Triple("onSecondaryFixedVariant/secondaryFixed", c.onSecondaryFixedVariant, c.secondaryFixed),
                Triple("onTertiaryFixed/tertiaryFixed", c.onTertiaryFixed, c.tertiaryFixed),
                Triple("onTertiaryFixedVariant/tertiaryFixed", c.onTertiaryFixedVariant, c.tertiaryFixed),
            )
            surfaces.forEach { (name, surface) ->
                pairs += Triple("onSurface/$name", c.onSurface, surface)
                if (name != "background") pairs += Triple("onSurfaceVariant/$name", c.onSurfaceVariant, surface)
                pairs += Triple("primary/$name", c.primary, surface)
                pairs += Triple("error/$name", c.error, surface)
                pairs += Triple("success/$name", x.success, surface)
                pairs += Triple("warning/$name", x.warning, surface)
            }
            pairs.forEach { (name, fg, bg) ->
                val ratio = contrast(fg, bg)
                if (ratio < 4.5) failures += "$scheme $name contrast ${"%.2f".format(ratio)} < 4.5"
            }
        }
        assertTrue("WCAG AA text contrast:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun nonTextGraphicsMeetThreeToOneInBothSchemes() {
        // The design relies on the outline (fields, outlined buttons, switch
        // tracks) and on primary as a graphic (progress, selected icons).
        val failures = mutableListOf<String>()
        listOf("light" to CurveMapsLightColors, "dark" to CurveMapsDarkColors).forEach { (scheme, c) ->
            listOf("surface" to c.surface, "surfaceContainer" to c.surfaceContainer).forEach { (name, bg) ->
                listOf("outline" to c.outline, "primary" to c.primary).forEach { (role, fg) ->
                    val ratio = contrast(fg, bg)
                    if (ratio < 3.0) failures += "$scheme $role/$name contrast ${"%.2f".format(ratio)} < 3"
                }
            }
            val onContainer = contrast(c.primaryContainer, c.surface)
            if (onContainer < 3.0) failures += "$scheme primaryContainer/surface contrast ${"%.2f".format(onContainer)} < 3"
        }
        assertTrue("WCAG non-text contrast:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun assertPinned(scheme: String, colors: ColorScheme, pins: Map<String, Long>) {
        val actual = roles(colors)
        assertEquals("$scheme role set", pins.keys, actual.keys)
        pins.forEach { (name, expected) ->
            assertEquals("$scheme $name", expected, argb(actual.getValue(name)))
        }
    }

    private fun argb(color: Color): Long = color.toArgb().toLong() and 0xFFFFFFFFL

    private fun extended(x: CurveMapsExtendedColors): List<Color> = listOf(
        x.success, x.onSuccess, x.successContainer, x.onSuccessContainer,
        x.warning, x.onWarning, x.warningContainer, x.onWarningContainer,
    )

    private fun roles(c: ColorScheme): Map<String, Color> = mapOf(
        "primary" to c.primary,
        "onPrimary" to c.onPrimary,
        "primaryContainer" to c.primaryContainer,
        "onPrimaryContainer" to c.onPrimaryContainer,
        "inversePrimary" to c.inversePrimary,
        "secondary" to c.secondary,
        "onSecondary" to c.onSecondary,
        "secondaryContainer" to c.secondaryContainer,
        "onSecondaryContainer" to c.onSecondaryContainer,
        "tertiary" to c.tertiary,
        "onTertiary" to c.onTertiary,
        "tertiaryContainer" to c.tertiaryContainer,
        "onTertiaryContainer" to c.onTertiaryContainer,
        "error" to c.error,
        "onError" to c.onError,
        "errorContainer" to c.errorContainer,
        "onErrorContainer" to c.onErrorContainer,
        "background" to c.background,
        "onBackground" to c.onBackground,
        "surface" to c.surface,
        "onSurface" to c.onSurface,
        "surfaceVariant" to c.surfaceVariant,
        "onSurfaceVariant" to c.onSurfaceVariant,
        "surfaceTint" to c.surfaceTint,
        "surfaceDim" to c.surfaceDim,
        "surfaceBright" to c.surfaceBright,
        "surfaceContainerLowest" to c.surfaceContainerLowest,
        "surfaceContainerLow" to c.surfaceContainerLow,
        "surfaceContainer" to c.surfaceContainer,
        "surfaceContainerHigh" to c.surfaceContainerHigh,
        "surfaceContainerHighest" to c.surfaceContainerHighest,
        "inverseSurface" to c.inverseSurface,
        "inverseOnSurface" to c.inverseOnSurface,
        "outline" to c.outline,
        "outlineVariant" to c.outlineVariant,
        "scrim" to c.scrim,
        "primaryFixed" to c.primaryFixed,
        "primaryFixedDim" to c.primaryFixedDim,
        "onPrimaryFixed" to c.onPrimaryFixed,
        "onPrimaryFixedVariant" to c.onPrimaryFixedVariant,
        "secondaryFixed" to c.secondaryFixed,
        "secondaryFixedDim" to c.secondaryFixedDim,
        "onSecondaryFixed" to c.onSecondaryFixed,
        "onSecondaryFixedVariant" to c.onSecondaryFixedVariant,
        "tertiaryFixed" to c.tertiaryFixed,
        "tertiaryFixedDim" to c.tertiaryFixedDim,
        "onTertiaryFixed" to c.onTertiaryFixed,
        "onTertiaryFixedVariant" to c.onTertiaryFixedVariant,
    )

    private val lightPins = mapOf(
        "primary" to 0xFF00579BL,
        "onPrimary" to 0xFFFFFFFFL,
        "primaryContainer" to 0xFF0B70C2L,
        "onPrimaryContainer" to 0xFFECF2FFL,
        "inversePrimary" to 0xFFA2C9FFL,
        "secondary" to 0xFF476081L,
        "onSecondary" to 0xFFFFFFFFL,
        "secondaryContainer" to 0xFFC0D9FFL,
        "onSecondaryContainer" to 0xFF2F4868L,
        "tertiary" to 0xFF884200L,
        "onTertiary" to 0xFFFFFFFFL,
        "tertiaryContainer" to 0xFFAD5600L,
        "onTertiaryContainer" to 0xFFFFEEE6L,
        "error" to 0xFFBA1A1AL,
        "onError" to 0xFFFFFFFFL,
        "errorContainer" to 0xFFFFDAD6L,
        "onErrorContainer" to 0xFF93000AL,
        "background" to 0xFFF8F9FFL,
        "onBackground" to 0xFF181C21L,
        "surface" to 0xFFF8F9FFL,
        "onSurface" to 0xFF181C21L,
        "surfaceVariant" to 0xFFDDE3F0L,
        "onSurfaceVariant" to 0xFF414751L,
        "surfaceTint" to 0xFF0060A9L,
        "surfaceDim" to 0xFFD8DAE1L,
        "surfaceBright" to 0xFFF8F9FFL,
        "surfaceContainerLowest" to 0xFFFFFFFFL,
        "surfaceContainerLow" to 0xFFF2F3FBL,
        "surfaceContainer" to 0xFFECEEF5L,
        "surfaceContainerHigh" to 0xFFE6E8EFL,
        "surfaceContainerHighest" to 0xFFE0E2EAL,
        "inverseSurface" to 0xFF2D3136L,
        "inverseOnSurface" to 0xFFEFF0F8L,
        "outline" to 0xFF717783L,
        "outlineVariant" to 0xFFC1C7D3L,
        "scrim" to 0xFF000000L,
        "primaryFixed" to 0xFFD3E4FFL,
        "primaryFixedDim" to 0xFFA2C9FFL,
        "onPrimaryFixed" to 0xFF001C38L,
        "onPrimaryFixedVariant" to 0xFF004881L,
        "secondaryFixed" to 0xFFD3E4FFL,
        "secondaryFixedDim" to 0xFFAFC8EEL,
        "onSecondaryFixed" to 0xFF001C38L,
        "onSecondaryFixedVariant" to 0xFF2F4867L,
        "tertiaryFixed" to 0xFFFFDCC6L,
        "tertiaryFixedDim" to 0xFFFFB786L,
        "onTertiaryFixed" to 0xFF311400L,
        "onTertiaryFixedVariant" to 0xFF723600L,
    )

    private val darkPins = mapOf(
        "primary" to 0xFFA2C9FFL,
        "onPrimary" to 0xFF00315BL,
        "primaryContainer" to 0xFF0B70C2L,
        "onPrimaryContainer" to 0xFFECF2FFL,
        "inversePrimary" to 0xFF0060A9L,
        "secondary" to 0xFFAFC8EEL,
        "onSecondary" to 0xFF173150L,
        "secondaryContainer" to 0xFF324A6AL,
        "onSecondaryContainer" to 0xFFD3E4FFL,
        "tertiary" to 0xFFFFB786L,
        "onTertiary" to 0xFF502400L,
        "tertiaryContainer" to 0xFFAD5600L,
        "onTertiaryContainer" to 0xFFFFEEE6L,
        "error" to 0xFFFFB4ABL,
        "onError" to 0xFF690005L,
        "errorContainer" to 0xFF93000AL,
        "onErrorContainer" to 0xFFFFDAD6L,
        "background" to 0xFF101419L,
        "onBackground" to 0xFFE0E2EAL,
        "surface" to 0xFF101419L,
        "onSurface" to 0xFFE0E2EAL,
        "surfaceVariant" to 0xFF414751L,
        "onSurfaceVariant" to 0xFFC1C7D3L,
        "surfaceTint" to 0xFFA2C9FFL,
        "surfaceDim" to 0xFF101419L,
        "surfaceBright" to 0xFF36393FL,
        "surfaceContainerLowest" to 0xFF0B0E13L,
        "surfaceContainerLow" to 0xFF181C21L,
        "surfaceContainer" to 0xFF1C2025L,
        "surfaceContainerHigh" to 0xFF272A30L,
        "surfaceContainerHighest" to 0xFF32353BL,
        "inverseSurface" to 0xFFE0E2EAL,
        "inverseOnSurface" to 0xFF2D3136L,
        "outline" to 0xFF8B919DL,
        "outlineVariant" to 0xFF414751L,
        "scrim" to 0xFF000000L,
        "primaryFixed" to 0xFFD3E4FFL,
        "primaryFixedDim" to 0xFFA2C9FFL,
        "onPrimaryFixed" to 0xFF001C38L,
        "onPrimaryFixedVariant" to 0xFF004881L,
        "secondaryFixed" to 0xFFD3E4FFL,
        "secondaryFixedDim" to 0xFFAFC8EEL,
        "onSecondaryFixed" to 0xFF001C38L,
        "onSecondaryFixedVariant" to 0xFF2F4867L,
        "tertiaryFixed" to 0xFFFFDCC6L,
        "tertiaryFixedDim" to 0xFFFFB786L,
        "onTertiaryFixed" to 0xFF311400L,
        "onTertiaryFixedVariant" to 0xFF723600L,
    )

    private fun contrast(fg: Color, bg: Color): Double {
        val a = fg.luminance().toDouble()
        val b = bg.luminance().toDouble()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun uiSources(): List<Pair<String, String>> {
        val root = repoRoot()
        return listOf("app/src/main/java", "app/src/debug/java").flatMap { relative ->
            val dir = root.resolve(relative)
            if (!dir.isDirectory) return@flatMap emptyList()
            Files.walk(dir.toPath()).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .map { root.toPath().relativize(it).toString() to it.toFile().readText() }
                    .toList()
            }
        }
    }

    private fun repoRoot(): File {
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            if (directory.resolve("settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Could not locate repository root")
    }
}
