package com.curvemaps.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.curvemaps.app.routing.RoundTripLength
import com.curvemaps.app.routing.RoundTripMeasure
import com.curvemaps.app.routing.RoundTripWish
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Accessibility name (and test seam) of the round-trip toggle beside From / To. */
internal const val ROUND_TRIP_TOGGLE_DESCRIPTION = "Round trip"

/** Accessibility name prefix (and test seam) of the round-trip length value: "Round trip length, 1 h". */
internal const val ROUND_TRIP_LENGTH_DESCRIPTION = "Round trip length"

/**
 * Test seams of the inline stepper and the measure selector. The selector is a
 * [SingleChoiceSegmentedButtonRow]: its
 * accessibility name is [ROUND_TRIP_MENU_TITLE] and its three segments are
 * labelled with the three options.
 */
internal const val ROUND_TRIP_SHORTER_DESCRIPTION = "Shorter round trip"
internal const val ROUND_TRIP_LONGER_DESCRIPTION = "Longer round trip"
internal const val ROUND_TRIP_MENU_TITLE = "Measure the ride by"
internal const val ROUND_TRIP_TIME_OPTION = "Ride time"
internal const val ROUND_TRIP_DISTANCE_OPTION = "Distance"
internal const val ROUND_TRIP_BACK_BY_OPTION = "Back by"

/** The option title for [measure]. */
internal fun roundTripMeasureTitle(measure: RoundTripMeasure): String = when (measure) {
    RoundTripMeasure.TIME -> ROUND_TRIP_TIME_OPTION
    RoundTripMeasure.DISTANCE -> ROUND_TRIP_DISTANCE_OPTION
    RoundTripMeasure.BACK_BY -> ROUND_TRIP_BACK_BY_OPTION
}

/** "5:30 pm" (or "17:30" on a 24-hour phone) for a back-by epoch minute. */
internal fun backByClockText(epochMinute: Long, use24Hour: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochSecond(epochMinute * 60L).atZone(zone)
    return if (use24Hour) {
        DateTimeFormatter.ofPattern("H:mm", Locale.US).format(at)
    } else {
        DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(at).lowercase(Locale.US)
    }
}

/** What one measure is set to, as the selector's value shows it: "1 h 30 min", "80 km", "5:30 pm". */
internal fun roundTripMeasureValue(
    wish: RoundTripWish,
    measure: RoundTripMeasure,
    nowMillis: Long,
    use24Hour: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): String = when (measure) {
    RoundTripMeasure.TIME -> wish.time.label
    RoundTripMeasure.DISTANCE -> wish.distance.label
    RoundTripMeasure.BACK_BY -> backByClockText(wish.backBy(nowMillis), use24Hour, zone)
}

/** The stepper's big value: "1 h 30 min", "80 km" or "Back 5:30 pm". */
internal fun roundTripValueLabel(
    wish: RoundTripWish,
    nowMillis: Long,
    use24Hour: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): String = when (wish.measure) {
    RoundTripMeasure.BACK_BY -> "Back ${roundTripMeasureValue(wish, RoundTripMeasure.BACK_BY, nowMillis, use24Hour, zone)}"
    else -> roundTripMeasureValue(wish, wish.measure, nowMillis, use24Hour, zone)
}

/**
 * The line under the value: about how much ride it is in the other unit, and
 * that it comes back. Rough on purpose (55 km/h), like the loop search's
 * first guess; it never changes another measure's value.
 */
internal fun roundTripCaption(wish: RoundTripWish, nowMillis: Long): String = when (wish.measure) {
    RoundTripMeasure.TIME -> {
        val about = RoundTripLength.near(RoundTripLength.DEFAULT_DISTANCE, 0L, wish.time.estimatedRoadMeters)
        "≈ ${about.label}, back to From"
    }
    RoundTripMeasure.DISTANCE -> {
        val millis = (wish.distance.kilometres / RoundTripLength.ASSUMED_SPEED_KMH * 3_600_000.0).toLong()
        "≈ ${RoundTripLength.near(RoundTripLength.DEFAULT, millis, 0.0).label}, back to From"
    }
    RoundTripMeasure.BACK_BY -> "≈ ${wish.length(nowMillis).label} ride, leaving now"
}

/** The length value's accessibility name, e.g. "Round trip length, back by 5:30 pm". */
internal fun roundTripLengthDescription(
    wish: RoundTripWish,
    nowMillis: Long,
    use24Hour: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val value = roundTripMeasureValue(wish, wish.measure, nowMillis, use24Hour, zone)
    return when (wish.measure) {
        RoundTripMeasure.BACK_BY -> "$ROUND_TRIP_LENGTH_DESCRIPTION, back by $value"
        else -> "$ROUND_TRIP_LENGTH_DESCRIPTION, $value"
    }
}

/** One preset chip: its label and the length one tap sets. */
internal data class RoundTripPreset(val label: String, val length: RoundTripLength)

/**
 * The one-tap lengths for the measure in use: the ride-time presets for a
 * ride time, the distance presets for a distance. A back-by time has none
 * (it is a clock time, set with the stepper).
 */
internal fun roundTripPresets(wish: RoundTripWish): List<RoundTripPreset> = when (wish.measure) {
    RoundTripMeasure.TIME -> RoundTripLength.TIME_PRESETS
    RoundTripMeasure.DISTANCE -> RoundTripLength.DISTANCE_PRESETS
    RoundTripMeasure.BACK_BY -> emptyList()
}.map { RoundTripPreset(it.label, it) }

/** Whether [preset] is the value the measure in use is set to right now. */
internal fun RoundTripWish.isPreset(preset: RoundTripPreset): Boolean = when (measure) {
    RoundTripMeasure.TIME -> preset.length == time
    RoundTripMeasure.DISTANCE -> preset.length == distance
    RoundTripMeasure.BACK_BY -> false
}

/** This wish with [preset] set on the measure it belongs to; the other measures keep their values. */
internal fun RoundTripWish.withPreset(preset: RoundTripPreset): RoundTripWish = when (val length = preset.length) {
    is RoundTripLength.Time -> copy(time = length)
    is RoundTripLength.Distance -> copy(distance = length)
}

/**
 * The wall clock, ticking on each minute boundary: back-by values and their
 * "ride, leaving now" line follow it while the planner sits open.
 */
@Composable
internal fun rememberMinuteClock(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(60_000L - Math.floorMod(value, 60_000L))
        value = System.currentTimeMillis()
    }
}

/** Dimensions the design fixes for the planner's two toggle keys and the stepper. */
internal object PlannerKeys {
    /** Each toggle key beside From / To is this wide and as tall as a field. */
    val KeyWidth = 52.dp
    val KeyHeight = 56.dp

    /** Gap between connected keys and between the stacked fields. */
    val ConnectedGap = 2.dp

    /** The stepper's end buttons and value card. */
    val StepperHeight = 64.dp
    val StepperEndWidth = 64.dp

    /** Corner of a connected group's outer end and of the corners where members meet. */
    val OuterCorner = 16.dp
    val InnerCorner = 4.dp
}

/** Stacked beside the fields: Use current location on top, Round trip below (rounded 16 dp outside, 4 dp where they meet). */
internal val PLANNER_KEYS_TOP_SHAPE = RoundedCornerShape(
    topStart = PlannerKeys.OuterCorner, topEnd = PlannerKeys.OuterCorner,
    bottomStart = PlannerKeys.InnerCorner, bottomEnd = PlannerKeys.InnerCorner,
)
internal val PLANNER_KEYS_BOTTOM_SHAPE = RoundedCornerShape(
    topStart = PlannerKeys.InnerCorner, topEnd = PlannerKeys.InnerCorner,
    bottomStart = PlannerKeys.OuterCorner, bottomEnd = PlannerKeys.OuterCorner,
)

/** Side by side (round trip): Use current location on the left, Round trip on the right. */
internal val PLANNER_KEYS_START_SHAPE = RoundedCornerShape(
    topStart = PlannerKeys.OuterCorner, bottomStart = PlannerKeys.OuterCorner,
    topEnd = PlannerKeys.InnerCorner, bottomEnd = PlannerKeys.InnerCorner,
)
internal val PLANNER_KEYS_END_SHAPE = RoundedCornerShape(
    topStart = PlannerKeys.InnerCorner, bottomStart = PlannerKeys.InnerCorner,
    topEnd = PlannerKeys.OuterCorner, bottomEnd = PlannerKeys.OuterCorner,
)

/**
 * A toggle key beside the planner's fields: an M3 [ToggleButton] 52 dp wide and
 * at least 56 dp tall (the caller stretches it to its field's height) in
 * the connected-group [shape]. Unchecked it is a quiet `surfaceContainerHighest`
 * key; checked it takes M3's selected colours (`primary` / `onPrimary`). The
 * caller supplies the semantics (description, state, role).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlannerToggleKey(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.width(PlannerKeys.KeyWidth).heightIn(min = PlannerKeys.KeyHeight),
        shapes = ToggleButtonShapes(shape, shape, shape),
        colors = ToggleButtonDefaults.toggleButtonColors(
            containerColor = scheme.surfaceContainerHighest,
            contentColor = scheme.onSurfaceVariant,
            checkedContainerColor = scheme.primary,
            checkedContentColor = scheme.onPrimary,
        ),
        contentPadding = PaddingValues(0.dp),
    ) { content() }
}

/**
 * The toggle beside From / To: on, the ride comes back to From and the planner
 * shows the round-trip settings instead of To. It is an M3 toggle key
 * (`autorenew`, filled while on) with `Role.Switch` semantics and the same
 * "On, the ride comes back to From" state text as before.
 */
@Composable
internal fun RoundTripToggle(
    on: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = PLANNER_KEYS_BOTTOM_SHAPE,
) {
    PlannerToggleKey(
        checked = on,
        onCheckedChange = { onToggle() },
        shape = shape,
        modifier = modifier.semantics {
            role = Role.Switch
            contentDescription = ROUND_TRIP_TOGGLE_DESCRIPTION
            stateDescription = if (on) "On, the ride comes back to From" else "Off"
        },
    ) {
        Icon(
            painter = painterResource(if (on) R.drawable.ms_autorenew_fill else R.drawable.ms_autorenew),
            contentDescription = null,
        )
    }
}

/**
 * The round trip's settings card (shown under From while the toggle is on):
 * a segmented button for what the ride is measured by, the connected
 * − / value / + stepper, and preset chips for the measure in use. Every
 * control writes through [onChange].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoundTripSettings(
    wish: RoundTripWish,
    nowMillis: Long,
    use24Hour: Boolean,
    onChange: (RoundTripWish) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = roundTripPresets(wish)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            RoundTripMeasureSelector(
                wish = wish,
                onPick = { measure -> onChange(wish.withMeasure(measure, nowMillis)) },
            )
            RoundTripStepper(
                wish = wish,
                nowMillis = nowMillis,
                use24Hour = use24Hour,
                onStep = { direction -> onChange(wish.stepped(direction, nowMillis)) },
            )
            if (presets.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    presets.forEach { preset ->
                        val selected = wish.isPreset(preset)
                        FilterChip(
                            selected = selected,
                            onClick = { onChange(wish.withPreset(preset)) },
                            label = { Text(preset.label, style = MaterialTheme.typography.labelLarge) },
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        painter = painterResource(R.drawable.ms_check),
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Ride time / Distance / Back by: one segmented button. The selected option
 * shows M3's check while every label still fits beside it on one line; on a
 * narrow row or at large text the colour alone marks it, so the selected label
 * never wraps and leaves its neighbours a different height.
 */
@Composable
internal fun RoundTripMeasureSelector(
    wish: RoundTripWish,
    onPick: (RoundTripMeasure) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measures = RoundTripMeasure.entries
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val showCheck = maxWidth / measures.size >= SELECTOR_CHECK_MIN_WIDTH * LocalDensity.current.fontScale
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .semantics { contentDescription = ROUND_TRIP_MENU_TITLE },
        ) {
            measures.forEachIndexed { index, measure ->
                SegmentedButton(
                    selected = measure == wish.measure,
                    onClick = { onPick(measure) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = measures.size),
                    // As tall as the tallest option (the row measures to its tallest member).
                    modifier = Modifier.heightIn(min = 48.dp).fillMaxHeight(),
                    icon = if (showCheck) {
                        { SegmentedButtonDefaults.Icon(active = measure == wish.measure) }
                    } else {
                        { }
                    },
                    label = {
                        Text(
                            text = roundTripMeasureTitle(measure),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                        )
                    },
                )
            }
        }
    }
}

/** Width one option needs (at 1x text) for its label beside the check. */
private val SELECTOR_CHECK_MIN_WIDTH = 92.dp

/**
 * Connected − / value / +: a tonal button at each end (pill on the outside, 8 dp
 * where it meets the value) around the value card. The buttons move only the
 * measure in use and mute at the end of its range. The value card is a
 * status, not a control: its description is "Round trip length, 1 h" and it
 * carries the "≈ 60 km, back to From" caption (dropped at large font scales).
 */
@Composable
internal fun RoundTripStepper(
    wish: RoundTripWish,
    nowMillis: Long,
    use24Hour: Boolean,
    onStep: (direction: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val endRadius = PlannerKeys.StepperHeight / 2
    val innerRadius = 8.dp
    Row(
        horizontalArrangement = Arrangement.spacedBy(PlannerKeys.ConnectedGap),
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = PlannerKeys.StepperHeight),
    ) {
        StepperButton(
            plus = false,
            shape = RoundedCornerShape(
                topStart = endRadius, bottomStart = endRadius, topEnd = innerRadius, bottomEnd = innerRadius,
            ),
            enabled = !wish.atMinimum(nowMillis),
            description = ROUND_TRIP_SHORTER_DESCRIPTION,
            onClick = { onStep(-1) },
        )
        val description = roundTripLengthDescription(wish, nowMillis, use24Hour)
        Surface(
            shape = RoundedCornerShape(innerRadius),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clearAndSetSemantics {
                    contentDescription = description
                    stateDescription = "Measured by ${roundTripMeasureTitle(wish.measure).lowercase(Locale.US)}"
                },
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = roundTripValueLabel(wish, nowMillis, use24Hour),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (density.fontScale <= SELECTED_PLACE_SUBTITLE_MAX_FONT_SCALE) {
                    Text(
                        text = roundTripCaption(wish, nowMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        StepperButton(
            plus = true,
            shape = RoundedCornerShape(
                topStart = innerRadius, bottomStart = innerRadius, topEnd = endRadius, bottomEnd = endRadius,
            ),
            enabled = !wish.atMaximum(nowMillis),
            description = ROUND_TRIP_LONGER_DESCRIPTION,
            onClick = { onStep(1) },
        )
    }
}

@Composable
private fun StepperButton(
    plus: Boolean,
    shape: RoundedCornerShape,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        modifier = Modifier
            .width(PlannerKeys.StepperEndWidth)
            .fillMaxHeight()
            .semantics { contentDescription = description },
    ) {
        Icon(
            painter = painterResource(if (plus) R.drawable.ms_add else R.drawable.ms_remove),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
    }
}
