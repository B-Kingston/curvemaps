package com.curvemaps.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalFloatingToolbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.curvemaps.app.media.GloveTarget
import com.curvemaps.app.ui.theme.CurveMapsDarkTheme

internal const val MAP_CREDITS = "© OpenMapTiles.org © OpenStreetMap contributors · Noto (OFL) · icons CC BY 4.0"

/** The startup chip's visible text: the full line is its accessibility text and the menu's. */
internal const val MAP_CREDITS_SHORT = "MapLibre · © OpenStreetMap"

/** Space the startup credits chip leaves for MapLibre's logo and attribution button. */
internal val MAP_LOGO_CLEARANCE = 120.dp

/** Settings menu width (Planner/SettingsMenu.dc.html). */
private val SETTINGS_MENU_WIDTH = 264.dp

/** Gap between the settings menu and the toolbar it opens beside. */
private val SETTINGS_MENU_GAP = 8.dp

/** The menu's top edge sits this far below the toolbar's top edge. */
private val SETTINGS_MENU_TOP_INSET = 4.dp

/** Margin kept between the open menu and the screen's left and bottom edges. */
private val SETTINGS_MENU_MARGIN = 16.dp

/** A button inside a floating toolbar. */
private val TOOLBAR_BUTTON = 48.dp

/**
 * Transparent touch margin between the ride toggles' glove-sized hit boxes and
 * their painted pill, above the first and below the last. [RouteScreen]
 * subtracts it so the painted top lines up with the guidance card.
 */
internal val RideRailHitMargin = 4.dp

/**
 * Map-side action rail. Planning: a vertical floating toolbar holding the
 * settings cog and the saved-routes bookmark. The cog is selected (secondary
 * container, filled glyph) while its menu is open; the menu grows leftwards
 * from the toolbar as an M3 menu surface (groups, dividers, the credits text)
 * and both buttons stay reachable. Riding: the voice and B&W-map toggles on
 * the dark scheme, each in a 64 dp glove-sized hit box.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RouteActionsPill(
    onLoadMap: () -> Unit,
    onImportGpx: () -> Unit,
    onRouteSettings: () -> Unit,
    onMapsSettings: () -> Unit,
    onVoiceSettings: () -> Unit,
    onOpenSourceLicences: () -> Unit,
    voiceGuidanceEnabled: Boolean,
    voiceSpeechStatus: OfflineSpeechStatus,
    onOpenSavedRoutes: () -> Unit,
    settingsMenuExpanded: Boolean,
    onSettingsMenuExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    rideMode: Boolean = false,
    darkRideMapEnabled: Boolean = false,
    onDarkRideMapToggle: () -> Unit = {},
    monochrome: Boolean = false,
) {
    val soundStateDescription = when {
        !voiceGuidanceEnabled -> "Disabled"
        voiceSpeechStatus is OfflineSpeechStatus.Ready -> "English offline voice ready"
        voiceSpeechStatus is OfflineSpeechStatus.Checking -> "Checking for an English offline voice"
        voiceSpeechStatus is OfflineSpeechStatus.NotStarted -> "English offline voice not checked yet"
        else -> "English offline voice unavailable"
    }
    if (rideMode) {
        RideSettingsToggles(
            voiceGuidanceEnabled = voiceGuidanceEnabled,
            voiceReady = voiceSpeechStatus is OfflineSpeechStatus.Ready,
            soundStateDescription = soundStateDescription,
            onVoiceSettings = onVoiceSettings,
            darkRideMapEnabled = darkRideMapEnabled,
            onDarkRideMapToggle = onDarkRideMapToggle,
            monochrome = monochrome,
            modifier = modifier,
        )
        return
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            // An open menu stays inside the window: its height is capped to the
            // space below the toolbar's top minus a margin and the navigation
            // bar, and the menu scrolls inside. (Only while open: the collapsed
            // rail's measured height must stay the toolbar's.)
            .then(
                if (settingsMenuExpanded) {
                    Modifier.navigationBarsPadding().padding(bottom = SETTINGS_MENU_MARGIN)
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = "Route actions" },
    ) {
        if (settingsMenuExpanded) {
            SettingsMenu(
                soundStateDescription = soundStateDescription,
                onDismiss = { onSettingsMenuExpandedChange(false) },
                onRouteSettings = onRouteSettings,
                onVoiceSettings = onVoiceSettings,
                onMapsSettings = onMapsSettings,
                onLoadMap = onLoadMap,
                onImportGpx = onImportGpx,
                onOpenSourceLicences = onOpenSourceLicences,
                modifier = Modifier
                    .weight(1f, fill = false)
                    // Never closer to the screen's left edge than the margin
                    // (a 320 dp phone has no room for the full 264 dp).
                    .padding(start = SETTINGS_MENU_MARGIN, top = SETTINGS_MENU_TOP_INSET),
            )
            Spacer(Modifier.width(SETTINGS_MENU_GAP))
        }
        VerticalFloatingToolbar(
            expanded = true,
            modifier = Modifier.width(MapControlsLayout.TOOLBAR_WIDTH),
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                toolbarContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            contentPadding = PaddingValues(MapControlsLayout.TOOLBAR_PADDING),
            expandedShadowElevation = 6.dp,
        ) {
            IconButton(
                onClick = { onSettingsMenuExpandedChange(!settingsMenuExpanded) },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (settingsMenuExpanded) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    contentColor = if (settingsMenuExpanded) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
                // requiredSize: at fractional densities the toolbar's rounded
                // 56 dp width less its rounded padding is a pixel short of 48 dp.
                modifier = Modifier
                    .requiredSize(TOOLBAR_BUTTON)
                    .semantics {
                        contentDescription = "Planning settings"
                        stateDescription = if (settingsMenuExpanded) SETTINGS_COG_OPEN else SETTINGS_COG_CLOSED
                    },
            ) {
                Icon(
                    painter = painterResource(if (settingsMenuExpanded) R.drawable.ms_settings_fill else R.drawable.ms_settings),
                    contentDescription = null,
                )
            }
            IconButton(
                onClick = onOpenSavedRoutes,
                // requiredSize: at fractional densities the toolbar's rounded
                // 56 dp width less its rounded padding is a pixel short of 48 dp.
                modifier = Modifier
                    .requiredSize(TOOLBAR_BUTTON)
                    .semantics { contentDescription = "Saved routes" },
            ) {
                Icon(painter = painterResource(R.drawable.ms_bookmarks), contentDescription = null)
            }
        }
    }
}

/** The cog's state, announced with its name (and a test seam). */
internal const val SETTINGS_COG_OPEN = "Settings card open"
internal const val SETTINGS_COG_CLOSED = "Settings card closed"

/**
 * The settings menu: Route settings and Sound settings, Maps, Load map file and
 * Import GPX route, Open-source licences, each group set off by a divider, then
 * the map credits. A menu surface in the planner's own window (not a popup), so
 * it dismisses with the scrim [RouteScreen] draws behind it and with system Back.
 */
@Composable
private fun SettingsMenu(
    soundStateDescription: String,
    onDismiss: () -> Unit,
    onRouteSettings: () -> Unit,
    onVoiceSettings: () -> Unit,
    onMapsSettings: () -> Unit,
    onLoadMap: () -> Unit,
    onImportGpx: () -> Unit,
    onOpenSourceLicences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
        modifier = modifier
            .widthIn(max = SETTINGS_MENU_WIDTH)
            .semantics { contentDescription = "Settings menu" },
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            SettingsMenuItem("Route settings", R.drawable.ms_tune) {
                onDismiss()
                onRouteSettings()
            }
            SettingsMenuItem("Sound settings", R.drawable.ms_volume_up, stateDescription = soundStateDescription) {
                onDismiss()
                onVoiceSettings()
            }
            MenuDivider()
            SettingsMenuItem("Maps", R.drawable.ms_map) {
                onDismiss()
                onMapsSettings()
            }
            SettingsMenuItem("Load map file", R.drawable.ms_file_open) {
                onDismiss()
                onLoadMap()
            }
            SettingsMenuItem("Import GPX route", R.drawable.ms_upload_file) {
                onDismiss()
                onImportGpx()
            }
            MenuDivider()
            SettingsMenuItem(OSS_LICENCES_TITLE, R.drawable.ms_license) {
                onDismiss()
                onOpenSourceLicences()
            }
            Text(
                // Non-breaking spaces keep "CC BY 4.0" together (no orphan "4.0").
                text = "Map credits\n${MAP_CREDITS.replace("CC BY 4.0", "CC\u00A0BY\u00A04.0")}\n" +
                    "OpenStreetMap data: ODbL\nopenstreetmap.org/copyright",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun SettingsMenuItem(
    label: String,
    iconRes: Int,
    stateDescription: String? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = if (stateDescription == null) {
            Modifier
        } else {
            Modifier.semantics {
                contentDescription = label
                this.stateDescription = stateDescription
            }
        },
    )
}

/**
 * Ride-mode toggles (voice guidance, B&W map) on the dark scheme: a 56 dp pill
 * of two 48 dp buttons painted inside 64 dp glove-sized hit boxes. The B&W
 * toggle is `inverseSurface` while on; with the B&W map ([monochrome]) the voice
 * toggle uses neutral greys too, so no blue accent sits over the grey map.
 */
@Composable
private fun RideSettingsToggles(
    voiceGuidanceEnabled: Boolean,
    voiceReady: Boolean,
    soundStateDescription: String,
    onVoiceSettings: () -> Unit,
    darkRideMapEnabled: Boolean,
    onDarkRideMapToggle: () -> Unit,
    monochrome: Boolean,
    modifier: Modifier = Modifier,
) {
    CurveMapsDarkTheme {
        val colors = MaterialTheme.colorScheme
        val voiceOn = voiceGuidanceEnabled && voiceReady
        Box(
            modifier = modifier.semantics { contentDescription = "Ride settings" },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = colors.surfaceContainer,
                shadowElevation = 6.dp,
                modifier = Modifier.size(
                    width = MapControlsLayout.TOOLBAR_WIDTH,
                    height = GloveTarget * 2 - RideRailHitMargin * 2,
                ),
            ) {}
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RideToggleTarget(
                    contentDescription = NavigationHudFormat.voiceSettingsDescription(
                        enabled = voiceGuidanceEnabled,
                        statusDescription = soundStateDescription,
                    ),
                    stateDescription = soundStateDescription,
                    onClick = onVoiceSettings,
                    iconRes = if (voiceOn) R.drawable.ms_volume_up_fill else R.drawable.ms_volume_off,
                    // Selected stays visibly selected over the B&W map: a neutral
                    // inverse fill like the B&W toggle's (no blue accent).
                    containerColor = when {
                        !voiceOn -> Color.Transparent
                        monochrome -> colors.inverseSurface
                        else -> colors.secondaryContainer
                    },
                    contentColor = when {
                        !voiceOn -> colors.onSurfaceVariant
                        monochrome -> colors.inverseOnSurface
                        else -> colors.onSecondaryContainer
                    },
                )
                RideToggleTarget(
                    contentDescription = NavigationHudFormat.darkRideMapDescription(darkRideMapEnabled),
                    stateDescription = if (darkRideMapEnabled) "on" else "off",
                    onClick = onDarkRideMapToggle,
                    iconRes = if (darkRideMapEnabled) R.drawable.ms_dark_mode_fill else R.drawable.ms_dark_mode,
                    containerColor = if (darkRideMapEnabled) colors.inverseSurface else Color.Transparent,
                    contentColor = if (darkRideMapEnabled) colors.inverseOnSurface else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One ride toggle: the whole [MapControlsLayout.RIDE_TOGGLE_TARGET] box takes the
 * tap and carries the semantics; the stock [IconButton] inside paints the 48 dp
 * circle and shares the interaction source, so a press anywhere in the box shows
 * its state layer and ripple.
 */
@Composable
private fun RideToggleTarget(
    contentDescription: String,
    stateDescription: String,
    onClick: () -> Unit,
    iconRes: Int,
    containerColor: Color,
    contentColor: Color,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(MapControlsLayout.RIDE_TOGGLE_TARGET)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                this.stateDescription = stateDescription
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        IconButton(
            onClick = onClick,
            interactionSource = interaction,
            colors = IconButtonDefaults.iconButtonColors(containerColor = containerColor, contentColor = contentColor),
            modifier = Modifier
                .size(TOOLBAR_BUTTON)
                .clearAndSetSemantics {},
        ) {
            Icon(painter = painterResource(iconRes), contentDescription = null)
        }
    }
}
