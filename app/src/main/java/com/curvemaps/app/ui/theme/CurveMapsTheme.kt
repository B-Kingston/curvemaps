package com.curvemaps.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The Material 3 *Fidelity* scheme around the brand blue `#0B70C2`, light. Every
 * [ColorScheme] role is set explicitly from [Palette.FidelityLight], so no stock
 * component can fall back to the baseline purple. Used by the planner, its
 * sheets and dialogs, and the settings screens ([CurveMapsTheme]).
 */
internal val CurveMapsLightColors: ColorScheme = lightColorScheme(
    primary = Palette.FidelityLight.primary,
    onPrimary = Palette.FidelityLight.onPrimary,
    primaryContainer = Palette.FidelityLight.primaryContainer,
    onPrimaryContainer = Palette.FidelityLight.onPrimaryContainer,
    inversePrimary = Palette.FidelityLight.inversePrimary,
    secondary = Palette.FidelityLight.secondary,
    onSecondary = Palette.FidelityLight.onSecondary,
    secondaryContainer = Palette.FidelityLight.secondaryContainer,
    onSecondaryContainer = Palette.FidelityLight.onSecondaryContainer,
    tertiary = Palette.FidelityLight.tertiary,
    onTertiary = Palette.FidelityLight.onTertiary,
    tertiaryContainer = Palette.FidelityLight.tertiaryContainer,
    onTertiaryContainer = Palette.FidelityLight.onTertiaryContainer,
    background = Palette.FidelityLight.background,
    onBackground = Palette.FidelityLight.onBackground,
    surface = Palette.FidelityLight.surface,
    onSurface = Palette.FidelityLight.onSurface,
    surfaceVariant = Palette.FidelityLight.surfaceVariant,
    onSurfaceVariant = Palette.FidelityLight.onSurfaceVariant,
    surfaceTint = Palette.FidelityLight.surfaceTint,
    inverseSurface = Palette.FidelityLight.inverseSurface,
    inverseOnSurface = Palette.FidelityLight.inverseOnSurface,
    error = Palette.FidelityLight.error,
    onError = Palette.FidelityLight.onError,
    errorContainer = Palette.FidelityLight.errorContainer,
    onErrorContainer = Palette.FidelityLight.onErrorContainer,
    outline = Palette.FidelityLight.outline,
    outlineVariant = Palette.FidelityLight.outlineVariant,
    scrim = Palette.FidelityLight.scrim,
    surfaceBright = Palette.FidelityLight.surfaceBright,
    surfaceDim = Palette.FidelityLight.surfaceDim,
    surfaceContainer = Palette.FidelityLight.surfaceContainer,
    surfaceContainerHigh = Palette.FidelityLight.surfaceContainerHigh,
    surfaceContainerHighest = Palette.FidelityLight.surfaceContainerHighest,
    surfaceContainerLow = Palette.FidelityLight.surfaceContainerLow,
    surfaceContainerLowest = Palette.FidelityLight.surfaceContainerLowest,
    primaryFixed = Palette.FidelityLight.primaryFixed,
    primaryFixedDim = Palette.FidelityLight.primaryFixedDim,
    onPrimaryFixed = Palette.FidelityLight.onPrimaryFixed,
    onPrimaryFixedVariant = Palette.FidelityLight.onPrimaryFixedVariant,
    secondaryFixed = Palette.FidelityLight.secondaryFixed,
    secondaryFixedDim = Palette.FidelityLight.secondaryFixedDim,
    onSecondaryFixed = Palette.FidelityLight.onSecondaryFixed,
    onSecondaryFixedVariant = Palette.FidelityLight.onSecondaryFixedVariant,
    tertiaryFixed = Palette.FidelityLight.tertiaryFixed,
    tertiaryFixedDim = Palette.FidelityLight.tertiaryFixedDim,
    onTertiaryFixed = Palette.FidelityLight.onTertiaryFixed,
    onTertiaryFixedVariant = Palette.FidelityLight.onTertiaryFixedVariant,
)

/**
 * The same Fidelity scheme, dark. Used for the ride chrome, the media panel and
 * the welcome carousel through [CurveMapsDarkTheme].
 */
internal val CurveMapsDarkColors: ColorScheme = darkColorScheme(
    primary = Palette.FidelityDark.primary,
    onPrimary = Palette.FidelityDark.onPrimary,
    primaryContainer = Palette.FidelityDark.primaryContainer,
    onPrimaryContainer = Palette.FidelityDark.onPrimaryContainer,
    inversePrimary = Palette.FidelityDark.inversePrimary,
    secondary = Palette.FidelityDark.secondary,
    onSecondary = Palette.FidelityDark.onSecondary,
    secondaryContainer = Palette.FidelityDark.secondaryContainer,
    onSecondaryContainer = Palette.FidelityDark.onSecondaryContainer,
    tertiary = Palette.FidelityDark.tertiary,
    onTertiary = Palette.FidelityDark.onTertiary,
    tertiaryContainer = Palette.FidelityDark.tertiaryContainer,
    onTertiaryContainer = Palette.FidelityDark.onTertiaryContainer,
    background = Palette.FidelityDark.background,
    onBackground = Palette.FidelityDark.onBackground,
    surface = Palette.FidelityDark.surface,
    onSurface = Palette.FidelityDark.onSurface,
    surfaceVariant = Palette.FidelityDark.surfaceVariant,
    onSurfaceVariant = Palette.FidelityDark.onSurfaceVariant,
    surfaceTint = Palette.FidelityDark.surfaceTint,
    inverseSurface = Palette.FidelityDark.inverseSurface,
    inverseOnSurface = Palette.FidelityDark.inverseOnSurface,
    error = Palette.FidelityDark.error,
    onError = Palette.FidelityDark.onError,
    errorContainer = Palette.FidelityDark.errorContainer,
    onErrorContainer = Palette.FidelityDark.onErrorContainer,
    outline = Palette.FidelityDark.outline,
    outlineVariant = Palette.FidelityDark.outlineVariant,
    scrim = Palette.FidelityDark.scrim,
    surfaceBright = Palette.FidelityDark.surfaceBright,
    surfaceDim = Palette.FidelityDark.surfaceDim,
    surfaceContainer = Palette.FidelityDark.surfaceContainer,
    surfaceContainerHigh = Palette.FidelityDark.surfaceContainerHigh,
    surfaceContainerHighest = Palette.FidelityDark.surfaceContainerHighest,
    surfaceContainerLow = Palette.FidelityDark.surfaceContainerLow,
    surfaceContainerLowest = Palette.FidelityDark.surfaceContainerLowest,
    primaryFixed = Palette.FidelityDark.primaryFixed,
    primaryFixedDim = Palette.FidelityDark.primaryFixedDim,
    onPrimaryFixed = Palette.FidelityDark.onPrimaryFixed,
    onPrimaryFixedVariant = Palette.FidelityDark.onPrimaryFixedVariant,
    secondaryFixed = Palette.FidelityDark.secondaryFixed,
    secondaryFixedDim = Palette.FidelityDark.secondaryFixedDim,
    onSecondaryFixed = Palette.FidelityDark.onSecondaryFixed,
    onSecondaryFixedVariant = Palette.FidelityDark.onSecondaryFixedVariant,
    tertiaryFixed = Palette.FidelityDark.tertiaryFixed,
    tertiaryFixedDim = Palette.FidelityDark.tertiaryFixedDim,
    onTertiaryFixed = Palette.FidelityDark.onTertiaryFixed,
    onTertiaryFixedVariant = Palette.FidelityDark.onTertiaryFixedVariant,
)

/** Roles Material 3 has no slot for: status tones used by settings, Maps and the ride chrome. */
@Immutable
internal data class CurveMapsExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val CurveMapsLightExtendedColors = CurveMapsExtendedColors(
    success = Palette.StatusLight.success,
    onSuccess = Palette.StatusLight.onSuccess,
    successContainer = Palette.StatusLight.successContainer,
    onSuccessContainer = Palette.StatusLight.onSuccessContainer,
    warning = Palette.StatusLight.warning,
    onWarning = Palette.StatusLight.onWarning,
    warningContainer = Palette.StatusLight.warningContainer,
    onWarningContainer = Palette.StatusLight.onWarningContainer,
)

internal val CurveMapsDarkExtendedColors = CurveMapsExtendedColors(
    success = Palette.StatusDark.success,
    onSuccess = Palette.StatusDark.onSuccess,
    successContainer = Palette.StatusDark.successContainer,
    onSuccessContainer = Palette.StatusDark.onSuccessContainer,
    warning = Palette.StatusDark.warning,
    onWarning = Palette.StatusDark.onWarning,
    warningContainer = Palette.StatusDark.warningContainer,
    onWarningContainer = Palette.StatusDark.onWarningContainer,
)

private val LocalExtendedColors = staticCompositionLocalOf { CurveMapsLightExtendedColors }

/** The M3 shape scale: 4 / 8 / 12 / 16 / 28 dp. Full is `CircleShape`. */
internal val CurveMapsShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The app root: the light scheme, the M3 type scale on the system font, the M3
 * shape scale, expressive motion, and Material's own ripples and state layers
 * (nothing here switches them off; `ColorThemeTest` fails if anything does).
 */
@Composable
internal fun CurveMapsTheme(content: @Composable () -> Unit) {
    CurveMapsThemeWith(CurveMapsLightColors, CurveMapsLightExtendedColors, content)
}

/**
 * Wraps a subtree in the dark scheme (same shapes, type and motion) and the dark
 * [CurveMapsTheme.colors]. Use it around the ride chrome, the media panel and the
 * welcome carousel; the rest of the app stays on [CurveMapsTheme]'s light scheme.
 */
@Composable
internal fun CurveMapsDarkTheme(content: @Composable () -> Unit) {
    CurveMapsThemeWith(CurveMapsDarkColors, CurveMapsDarkExtendedColors, content)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CurveMapsThemeWith(
    colorScheme: ColorScheme,
    extended: CurveMapsExtendedColors,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = CurveMapsShapes,
            typography = Typography(),
            content = content,
        )
    }
}

internal object CurveMapsTheme {
    val colors: CurveMapsExtendedColors
        @Composable @ReadOnlyComposable
        get() = LocalExtendedColors.current
}

/** M3's disabled content colour: `onSurface` at 38 %. */
internal val ColorScheme.disabledContent: Color get() = onSurface.copy(alpha = 0.38f)

/** M3's disabled container colour: `onSurface` at 12 %. */
internal val ColorScheme.disabledContainer: Color get() = onSurface.copy(alpha = 0.12f)
