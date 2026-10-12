package com.curvemaps.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color

/**
 * Every raw colour value the app's UI uses. This package is the only place a
 * `Color(0x…)` literal may appear (`ColorTokenConfinementTest` enforces it):
 * screens read roles from `MaterialTheme.colorScheme` or [CurveMapsTheme.colors],
 * never a private hex value.
 *
 * Map-layer colours (route carousel series, suggestion line, MapLibre styles)
 * are data colours inside the map and live with their layers in `map/`.
 */
internal object Palette {
    // ---- Material 3 Fidelity schemes (seed #0B70C2) -------------------------------
    // Generated once from the Material colour utilities; ColorThemeTest pins every
    // value so drift is caught. CurveMapsLightColors / CurveMapsDarkColors read these.

    /** Material 3 Fidelity scheme from seed #0B70C2, light (contrast 0); onSecondaryContainer is the design override. */
    object FidelityLight {
        val primary = Color(0xFF00579B)
        val onPrimary = Color(0xFFFFFFFF)
        val primaryContainer = Color(0xFF0B70C2)
        val onPrimaryContainer = Color(0xFFECF2FF)
        val inversePrimary = Color(0xFFA2C9FF)
        val secondary = Color(0xFF476081)
        val onSecondary = Color(0xFFFFFFFF)
        val secondaryContainer = Color(0xFFC0D9FF)
        val onSecondaryContainer = Color(0xFF2F4868)
        val tertiary = Color(0xFF884200)
        val onTertiary = Color(0xFFFFFFFF)
        val tertiaryContainer = Color(0xFFAD5600)
        val onTertiaryContainer = Color(0xFFFFEEE6)
        val error = Color(0xFFBA1A1A)
        val onError = Color(0xFFFFFFFF)
        val errorContainer = Color(0xFFFFDAD6)
        val onErrorContainer = Color(0xFF93000A)
        val background = Color(0xFFF8F9FF)
        val onBackground = Color(0xFF181C21)
        val surface = Color(0xFFF8F9FF)
        val onSurface = Color(0xFF181C21)
        val surfaceVariant = Color(0xFFDDE3F0)
        val onSurfaceVariant = Color(0xFF414751)
        val surfaceTint = Color(0xFF0060A9)
        val surfaceDim = Color(0xFFD8DAE1)
        val surfaceBright = Color(0xFFF8F9FF)
        val surfaceContainerLowest = Color(0xFFFFFFFF)
        val surfaceContainerLow = Color(0xFFF2F3FB)
        val surfaceContainer = Color(0xFFECEEF5)
        val surfaceContainerHigh = Color(0xFFE6E8EF)
        val surfaceContainerHighest = Color(0xFFE0E2EA)
        val inverseSurface = Color(0xFF2D3136)
        val inverseOnSurface = Color(0xFFEFF0F8)
        val outline = Color(0xFF717783)
        val outlineVariant = Color(0xFFC1C7D3)
        val scrim = Color(0xFF000000)
        val primaryFixed = Color(0xFFD3E4FF)
        val primaryFixedDim = Color(0xFFA2C9FF)
        val onPrimaryFixed = Color(0xFF001C38)
        val onPrimaryFixedVariant = Color(0xFF004881)
        val secondaryFixed = Color(0xFFD3E4FF)
        val secondaryFixedDim = Color(0xFFAFC8EE)
        val onSecondaryFixed = Color(0xFF001C38)
        val onSecondaryFixedVariant = Color(0xFF2F4867)
        val tertiaryFixed = Color(0xFFFFDCC6)
        val tertiaryFixedDim = Color(0xFFFFB786)
        val onTertiaryFixed = Color(0xFF311400)
        val onTertiaryFixedVariant = Color(0xFF723600)
    }

    /** Material 3 Fidelity scheme from seed #0B70C2, dark (contrast 0); onSecondaryContainer is the design override. */
    object FidelityDark {
        val primary = Color(0xFFA2C9FF)
        val onPrimary = Color(0xFF00315B)
        val primaryContainer = Color(0xFF0B70C2)
        val onPrimaryContainer = Color(0xFFECF2FF)
        val inversePrimary = Color(0xFF0060A9)
        val secondary = Color(0xFFAFC8EE)
        val onSecondary = Color(0xFF173150)
        val secondaryContainer = Color(0xFF324A6A)
        val onSecondaryContainer = Color(0xFFD3E4FF)
        val tertiary = Color(0xFFFFB786)
        val onTertiary = Color(0xFF502400)
        val tertiaryContainer = Color(0xFFAD5600)
        val onTertiaryContainer = Color(0xFFFFEEE6)
        val error = Color(0xFFFFB4AB)
        val onError = Color(0xFF690005)
        val errorContainer = Color(0xFF93000A)
        val onErrorContainer = Color(0xFFFFDAD6)
        val background = Color(0xFF101419)
        val onBackground = Color(0xFFE0E2EA)
        val surface = Color(0xFF101419)
        val onSurface = Color(0xFFE0E2EA)
        val surfaceVariant = Color(0xFF414751)
        val onSurfaceVariant = Color(0xFFC1C7D3)
        val surfaceTint = Color(0xFFA2C9FF)
        val surfaceDim = Color(0xFF101419)
        val surfaceBright = Color(0xFF36393F)
        val surfaceContainerLowest = Color(0xFF0B0E13)
        val surfaceContainerLow = Color(0xFF181C21)
        val surfaceContainer = Color(0xFF1C2025)
        val surfaceContainerHigh = Color(0xFF272A30)
        val surfaceContainerHighest = Color(0xFF32353B)
        val inverseSurface = Color(0xFFE0E2EA)
        val inverseOnSurface = Color(0xFF2D3136)
        val outline = Color(0xFF8B919D)
        val outlineVariant = Color(0xFF414751)
        val scrim = Color(0xFF000000)
        val primaryFixed = Color(0xFFD3E4FF)
        val primaryFixedDim = Color(0xFFA2C9FF)
        val onPrimaryFixed = Color(0xFF001C38)
        val onPrimaryFixedVariant = Color(0xFF004881)
        val secondaryFixed = Color(0xFFD3E4FF)
        val secondaryFixedDim = Color(0xFFAFC8EE)
        val onSecondaryFixed = Color(0xFF001C38)
        val onSecondaryFixedVariant = Color(0xFF2F4867)
        val tertiaryFixed = Color(0xFFFFDCC6)
        val tertiaryFixedDim = Color(0xFFFFB786)
        val onTertiaryFixed = Color(0xFF311400)
        val onTertiaryFixedVariant = Color(0xFF723600)
    }

    /** Success and warning roles Material 3 has no slot for, light (design values). */
    object StatusLight {
        val success = Color(0xFF1B6C35)
        val onSuccess = Color(0xFFFFFFFF)
        val successContainer = Color(0xFFC6F0CD)
        val onSuccessContainer = Color(0xFF00391A)
        val warning = Color(0xFF825500)
        val onWarning = Color(0xFFFFFFFF)
        val warningContainer = Color(0xFFFFDDB2)
        val onWarningContainer = Color(0xFF291800)
    }

    /** Success and warning roles Material 3 has no slot for, dark (design values). */
    object StatusDark {
        val success = Color(0xFF8FD9A0)
        val onSuccess = Color(0xFF003916)
        val successContainer = Color(0xFF005322)
        val onSuccessContainer = Color(0xFFA4F5AF)
        val warning = Color(0xFFFBBB5B)
        val onWarning = Color(0xFF452B00)
        val warningContainer = Color(0xFF624000)
        val onWarningContainer = Color(0xFFFFDDB2)
    }
}
