package com.curvemaps.app

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.curvemaps.app.routing.GraphFeatures
import com.curvemaps.app.routing.RideFactors
import com.curvemaps.app.routing.RideStyle

/** The header's Back button: 48 dp, the app's minimum target (M3's IconButton is 40). */
internal val BackButtonSize = 48.dp

internal fun rideStyleLabel(value: Float): String = RideStyle.fromLevel(value).label

/**
 * Screen header for the screens opened from the settings cog: an M3 top app
 * bar (arrow_back navigation icon, title in `titleLarge`) on a transparent
 * container, so the screen's own surface shows through. Every screen opened
 * from the cog exposes this back button (`contentDescription = "Back"`, 48 dp)
 * as its exit affordance. The bar takes no window insets: the screen pads for
 * the status bar itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            // The description sits on the clickable node itself (not on the icon
            // inside it) so the unmerged tree still finds a clickable "Back".
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(BackButtonSize)
                    .semantics { contentDescription = "Back" },
            ) {
                Icon(painterResource(R.drawable.ms_arrow_back), contentDescription = null)
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        modifier = modifier,
    )
}

/** Everything Route settings edits; Apply hands back the whole draft. */
internal data class RouteSettingsValues(
    val blockUnpaved: Boolean,
    val avoidMotorways: Boolean,
    val rideSuggestions: Boolean,
    val factors: RideFactors = RideFactors.NONE,
)

/** Title (and content description) of the elevation ride-factor checkbox row. */
internal const val INCLUDE_ELEVATION_TITLE = "Hills and elevation"

/** Title (and content description) of the stops-and-towns ride-factor checkbox row. */
internal const val AVOID_STOPS_AND_TOWNS_TITLE = "Avoid stops and towns"

/**
 * Corner radii of a grouped settings list: the first row of a group has big
 * top corners, the last big bottom corners, and every corner between two rows
 * is small, so the rows read as one card cut into pieces (Route settings, Voice
 * guidance, Open-source licences).
 */
internal object SettingsGroupShape {
    val OuterRadius = 20.dp
    val InnerRadius = 4.dp
    /** The gap between rows of a group. */
    val RowGap = 2.dp

    fun topRadius(index: Int, count: Int): Dp = if (index == 0) OuterRadius else InnerRadius

    fun bottomRadius(index: Int, count: Int): Dp = if (index == count - 1) OuterRadius else InnerRadius

    fun shape(index: Int, count: Int): Shape {
        val top = topRadius(index, count)
        val bottom = bottomRadius(index, count)
        return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
    }
}

/** The settings screens' footer button geometry: 48 dp keys, the Apply key is wider. */
internal object SettingsFooter {
    val ButtonHeight = 48.dp
    val ApplyStartPadding = 20.dp
    val ApplyEndPadding = 28.dp
    val CheckIconSize = 18.dp
}

/** A group heading (`titleSmall`, primary), announced as a heading. */
@Composable
internal fun SettingsSectionHeading(text: String, modifier: Modifier = Modifier, bottomPadding: Dp = 8.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomPadding)
            .semantics { heading() },
    )
}

/**
 * Full-screen settings page in a platform dialog window (so it sits above the
 * map, the planner and the action rail, and system Back dismisses it): the
 * [SettingsScreenHeader], the scrolling [content], and a footer with Cancel
 * (a text button that calls [onDismiss]) and Apply (a filled button that calls
 * [onApply]) on `surfaceContainer`. Used by Route settings and Voice guidance.
 */
@Composable
internal fun SettingsPageDialog(
    title: String,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DialogStatusBarIcons()
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                SettingsScreenHeader(
                    title = title,
                    onBack = onDismiss,
                    modifier = Modifier.statusBarsPadding(),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    content = content,
                )
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.height(SettingsFooter.ButtonHeight),
                        ) { Text("Cancel") }
                        Button(
                            onClick = onApply,
                            contentPadding = PaddingValues(
                                start = SettingsFooter.ApplyStartPadding,
                                end = SettingsFooter.ApplyEndPadding,
                            ),
                            modifier = Modifier.height(SettingsFooter.ButtonHeight),
                        ) {
                            Icon(
                                painterResource(R.drawable.ms_check),
                                contentDescription = null,
                                modifier = Modifier.size(SettingsFooter.CheckIconSize),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Apply")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Makes the status and navigation bar icons of the enclosing dialog window read
 * on the page behind them: dark icons on a light scheme, light on a dark one.
 * (A full-screen `Dialog` is its own window; the activity's setting does not apply.)
 */
@Composable
internal fun DialogStatusBarIcons() {
    val view = LocalView.current
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }
}

@Composable
internal fun RouteSettingsDialog(
    current: RouteSettingsValues,
    /** The loaded map's ride-factor data; null before any route loaded it. */
    graphFeatures: GraphFeatures?,
    onDismiss: () -> Unit,
    onApply: (RouteSettingsValues) -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    SettingsPageDialog(
        title = "Route settings",
        onDismiss = onDismiss,
        onApply = { onApply(draft) },
    ) {
        SettingsSectionHeading("Road types")
        Column(verticalArrangement = Arrangement.spacedBy(SettingsGroupShape.RowGap)) {
            RouteToggleRow(
                icon = R.drawable.ms_remove_road,
                title = "Avoid motorways",
                detail = "Stay off motorways and trunk roads unless there is no sensible way around them.",
                checked = draft.avoidMotorways,
                onCheckedChange = { draft = draft.copy(avoidMotorways = it) },
                shape = SettingsGroupShape.shape(0, 2),
            )
            RouteToggleRow(
                icon = R.drawable.ms_block,
                title = "Block unpaved roads",
                detail = "Exclude roads tagged as unpaved, gravel, dirt, ground, grass, or sand.",
                checked = draft.blockUnpaved,
                onCheckedChange = { draft = draft.copy(blockUnpaved = it) },
                shape = SettingsGroupShape.shape(1, 2),
            )
        }
        SettingsSectionHeading("While riding")
        RouteToggleRow(
            icon = R.drawable.ms_alt_route,
            title = "Ride suggestions",
            detail = "Occasionally suggest a twistier detour mid-ride (Balanced style and above). " +
                "Pauses in battery saver.",
            checked = draft.rideSuggestions,
            onCheckedChange = { draft = draft.copy(rideSuggestions = it) },
            shape = SettingsGroupShape.shape(0, 1),
        )
        SettingsSectionHeading("Include in route calculations", bottomPadding = 0.dp)
        Text(
            "Tick a factor to see how it changes each ride style. The route card shows what it measured.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(SettingsGroupShape.RowGap)) {
            RouteFactorRow(
                icon = R.drawable.ms_landscape,
                title = INCLUDE_ELEVATION_TITLE,
                detail = "Climbs and descents count as fun roads, like bends, on Mostly fast and " +
                    "curvier styles. The route card shows the total climb.",
                missingData = if (graphFeatures?.supportsElevation == false) {
                    "This map has no elevation data. A newer map file adds it."
                } else null,
                checked = draft.factors.elevation,
                onCheckedChange = { draft = draft.copy(factors = draft.factors.copy(elevation = it)) },
                shape = SettingsGroupShape.shape(0, 2),
            )
            RouteFactorRow(
                icon = R.drawable.ms_traffic,
                title = AVOID_STOPS_AND_TOWNS_TITLE,
                detail = "Each traffic light, stop or give-way sign costs about 20 seconds, and " +
                    "built-up areas weigh more, at every style. The route card counts the stops.",
                missingData = if (graphFeatures?.supportsStopsAndTowns == false) {
                    "This map has no stop or town data. A newer map file adds it."
                } else null,
                checked = draft.factors.stopsAndTowns,
                onCheckedChange = { draft = draft.copy(factors = draft.factors.copy(stopsAndTowns = it)) },
                shape = SettingsGroupShape.shape(1, 2),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * One grouped-list row on `surfaceContainerLow`: [leading], a text column
 * ([headline] over optional [supporting] lines) and [trailing], all centred
 * vertically as the design draws them (M3's own ListItem top-aligns them once the
 * supporting text runs to three lines). At least 56 dp tall; the caller adds
 * the shape clip and the click or toggle behaviour through [modifier].
 */
@Composable
internal fun SettingsRow(
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    headline: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (leading != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { leading() }
        }
        Column(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge) { headline() }
            }
            if (supporting != null) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { supporting() }
                }
            }
        }
        if (trailing != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { trailing() }
        }
    }
}

/** A settings row's leading symbol. */
@Composable
internal fun SettingsRowIcon(@DrawableRes icon: Int) {
    Icon(painterResource(icon), contentDescription = null)
}

/** The detail line under a settings row's title; [extra] adds lines under it. */
@Composable
private fun RowDetail(detail: String, extra: String? = null) {
    Column {
        Text(detail)
        if (extra != null) {
            Text(extra, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * One ride-factor checkbox row. The whole row is the toggle target (role
 * Checkbox, description = title, at least 48 dp), so a gloved tap anywhere on
 * the text works; the [Checkbox] only shows the state.
 */
@Composable
private fun RouteFactorRow(
    @DrawableRes icon: Int,
    title: String,
    detail: String,
    missingData: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
) {
    SettingsRow(
        headline = { Text(title) },
        supporting = { RowDetail(detail, missingData) },
        leading = { SettingsRowIcon(icon) },
        trailing = { Checkbox(checked = checked, onCheckedChange = null) },
        modifier = Modifier
            .clip(shape)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .semantics { contentDescription = title },
    )
}

/** One Route settings switch row; the row is one Switch-role toggle described by its title. */
@Composable
private fun RouteToggleRow(
    @DrawableRes icon: Int,
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
) {
    SettingsRow(
        headline = { Text(title) },
        supporting = { RowDetail(detail) },
        leading = { SettingsRowIcon(icon) },
        trailing = { SettingsSwitch(checked) },
        modifier = Modifier
            .clip(shape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { contentDescription = title },
    )
}

/**
 * The M3 [Switch] with a check in the thumb when on. It is display-only
 * (`onCheckedChange = null`): the enclosing row owns the toggle, so the row is
 * the single Switch node.
 */
@Composable
internal fun SettingsSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        modifier = modifier,
        // The thumb is light when on: a primary check reads against it.
        colors = SwitchDefaults.colors(checkedIconColor = MaterialTheme.colorScheme.primary),
        thumbContent = if (checked) {
            {
                Icon(
                    painterResource(R.drawable.ms_check),
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            }
        } else null,
    )
}

