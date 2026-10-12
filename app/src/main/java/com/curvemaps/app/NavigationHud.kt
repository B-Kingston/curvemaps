package com.curvemaps.app

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curvemaps.app.media.GloveGap
import com.curvemaps.app.media.GloveTarget
import com.curvemaps.app.media.MediaPanelJoin
import com.curvemaps.app.routing.navigation.LaneArrow
import com.curvemaps.app.routing.navigation.LaneGuidance
import com.curvemaps.app.routing.navigation.NavigationSnapshot
import com.curvemaps.app.routing.navigation.PositionSource
import com.curvemaps.app.ui.theme.CurveMapsDarkTheme
import com.curvemaps.app.ui.theme.CurveMapsTheme
import com.graphhopper.util.Instruction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The ride chrome in Material 3 (docs/agents/ride-guidance.md): the guidance
 * card, the voice / B&W-map toolbar and the data bar, all in the dark scheme
 * (CurveMapsDarkTheme) over the map. B&W ride mode (`monochrome`) swaps the
 * blue and red roles for neutral greys.
 */

/** Gap between the status bar and the guidance card; the ride toolbar aligns to it. */
internal val NavigationHudTopPadding = 8.dp

/**
 * Top-left guidance card, shown while a route is being followed: the next
 * manoeuvre's symbol over the distance to it and the street, plus the lane
 * strip when the road being left has several lanes. While there is no GPS fix
 * an error-container chip sits under it ([NoGpsBadge]). The riding metrics
 * live in [NavigationDataBar] at the bottom of the screen.
 */
@Composable
fun NavigationHud(
    snapshot: NavigationSnapshot,
    modifier: Modifier = Modifier,
    gpsLost: Boolean = false,
    trailingClearance: Dp = 0.dp,
    monochrome: Boolean = false,
) {
    CurveMapsDarkTheme {
        // The chip is measured within the same clearance as the card, so a long
        // street name ellipsizes rather than reaching the toolbar on the right.
        Column(
            modifier = modifier
                .statusBarsPadding()
                .padding(start = 12.dp, top = NavigationHudTopPadding, end = trailingClearance),
        ) {
            GuidanceCard(snapshot, gpsLost, monochrome, Modifier.width(
                NavigationHudFormat.guidanceCardWidthDp(LocalDensity.current.fontScale).dp,
            ))
            AnimatedVisibility(
                visible = gpsLost,
                enter = fadeIn(tween(150)) + scaleIn(
                    initialScale = 0.6f,
                    transformOrigin = TransformOrigin(0f, 0f),
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
                ),
                exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.8f, transformOrigin = TransformOrigin(0f, 0f)),
            ) {
                NoGpsBadge(snapshot.positionSource, Modifier.padding(top = 8.dp))
            }
        }
    }
}

/**
 * The ride's Picture-in-Picture window content over the map: the manoeuvre
 * symbol and the distance to it, without the street name or lanes, so the card
 * stays readable in a window a third of the screen wide. It has no controls:
 * one tap on the window is Android's own way back to the full ride.
 */
@Composable
fun NavigationPipHud(
    snapshot: NavigationSnapshot,
    modifier: Modifier = Modifier,
    gpsLost: Boolean = false,
    monochrome: Boolean = false,
) {
    CurveMapsDarkTheme {
        GuidanceCard(snapshot, gpsLost, monochrome, modifier.padding(6.dp), compact = true)
    }
}

/**
 * Pops up under the guidance card while there is no GPS fix: an error-container
 * chip with the crossed-out location symbol, "No GPS" and what guidance is doing
 * about it ("position estimated" / "position held"). The label is its own text
 * node and never fades.
 */
@Composable
internal fun NoGpsBadge(source: PositionSource, modifier: Modifier = Modifier) {
    val detail = NavigationHudFormat.noGpsDetail(source)
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 4.dp,
        // As wide as the guidance card above it, so the chip does not resize
        // as the detail text changes ("estimated" / "held" / none).
        modifier = modifier
            .width(NavigationHudFormat.guidanceCardWidthDp(LocalDensity.current.fontScale).dp)
            .semantics(mergeDescendants = true) {
                contentDescription = NavigationHudFormat.noGpsDescription(source)
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .defaultMinSize(minHeight = 40.dp)
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(
                painterResource(R.drawable.ms_location_disabled),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            // Label over detail, as two nodes (the label never fades): the
            // chip is the card's width, so the detail gets its own line instead
            // of wrapping after a dot.
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    text = NavigationHudFormat.NO_GPS_LABEL,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuidanceCard(
    snapshot: NavigationSnapshot,
    gpsLost: Boolean,
    monochrome: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val staleAlpha = staleReadoutAlpha(gpsLost, NavigationHudFormat.GUIDANCE_STALE_ALPHA)
    val colors = MaterialTheme.colorScheme
    val container = if (monochrome) colors.surfaceContainerHighest else colors.primaryContainer
    val content = if (monochrome) colors.onSurface else colors.onPrimaryContainer
    val turn = snapshot.turn
    Surface(
        color = container,
        contentColor = content,
        shape = if (compact) MaterialTheme.shapes.large else MaterialTheme.shapes.extraLarge,
        shadowElevation = 6.dp,
        // The full card gets a fixed width from [NavigationHud] (the design's
        // 212 dp, growing with the font scale): the card and the chips under it
        // hold still from frame to frame instead of resizing to each street
        // name; long names ellipsise. The compact card wraps its content.
        modifier = modifier
            .semantics {
                contentDescription = "Navigation guidance: " +
                    NavigationHudFormat.turnDescription(snapshot.turn)
                if (gpsLost) stateDescription = NavigationHudFormat.STALE_STATE_DESCRIPTION
            },
    ) {
        val lanes = turn?.lanes?.takeUnless { compact }
        val pad = if (compact) 10.dp else 16.dp
        Column(if (compact) Modifier else Modifier.fillMaxWidth()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(
                    start = pad,
                    end = pad,
                    top = pad,
                    bottom = if (lanes != null) 12.dp else pad,
                ),
            ) {
                Box(Modifier.graphicsLayer { alpha = staleAlpha }) { ManeuverIcon(turn, content) }
                Text(
                    text = turn?.let { NavigationHudFormat.distanceText(it.distanceM) } ?: "—",
                    style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (!compact && !turn?.streetName.isNullOrBlank()) {
                    Text(
                        text = turn!!.streetName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            lanes?.let { lanes ->
                LaneGuidanceRow(
                    lanes = lanes,
                    container = container,
                    content = content,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}


/** The big manoeuvre symbol; the roundabout's exit number rides its corner. */
@Composable
private fun ManeuverIcon(turn: NavigationSnapshot.TurnInfo?, color: Color) {
    val glyph = NavigationHudFormat.maneuverGlyph(turn)
    Box(Modifier.size(ManeuverSymbolSize), contentAlignment = Alignment.Center) {
        val symbol = glyph.symbolRes()
        if (symbol != null) {
            // Material Symbols leave a quarter-ish of their 24 unit viewport
            // empty on the left; pull the ink back so its edge meets the text.
            Icon(
                painterResource(symbol),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(ManeuverSymbolSize).offset(x = -ManeuverSymbolInkInset),
            )
        } else {
            Canvas(Modifier.size(ManeuverSymbolSize)) { drawTurnArrow(turn, size.width, size.height, color) }
        }
        turn?.roundaboutExitNumber?.takeIf { it > 0 && glyph == ManeuverGlyph.ROUNDABOUT }?.let { exitNumber ->
            Text(
                text = exitNumber.toString(),
                color = MaterialTheme.colorScheme.surface,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(color)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

private val ManeuverSymbolSize: Dp = 52.dp

/** How far a symbol is shifted left so its ink, not its empty viewport edge, lines up with the card's text. */
private val ManeuverSymbolInkInset: Dp = 6.dp

/**
 * Material Symbols for every manoeuvre that has a faithful one; null where the
 * drawn glyph stays (roundabouts: the symbol cannot show the circulation
 * direction or the exit number; a U-turn with no known side).
 */
@androidx.annotation.DrawableRes
private fun ManeuverGlyph.symbolRes(): Int? = when (this) {
    ManeuverGlyph.STRAIGHT -> R.drawable.ms_straight
    ManeuverGlyph.TURN_LEFT -> R.drawable.ms_turn_left
    ManeuverGlyph.TURN_RIGHT -> R.drawable.ms_turn_right
    ManeuverGlyph.SHARP_LEFT -> R.drawable.ms_turn_sharp_left
    ManeuverGlyph.SHARP_RIGHT -> R.drawable.ms_turn_sharp_right
    ManeuverGlyph.SLIGHT_LEFT -> R.drawable.ms_turn_slight_left
    ManeuverGlyph.SLIGHT_RIGHT -> R.drawable.ms_turn_slight_right
    ManeuverGlyph.KEEP_LEFT -> R.drawable.ms_fork_left
    ManeuverGlyph.KEEP_RIGHT -> R.drawable.ms_fork_right
    ManeuverGlyph.U_TURN_LEFT -> R.drawable.ms_u_turn_left
    ManeuverGlyph.U_TURN_RIGHT -> R.drawable.ms_u_turn_right
    ManeuverGlyph.FINISH -> R.drawable.ms_sports_score
    ManeuverGlyph.VIA -> R.drawable.ms_flag
    ManeuverGlyph.U_TURN_UNKNOWN_SIDE, ManeuverGlyph.ROUNDABOUT -> null
}

/**
 * The data bar floats over the map as an island like the planner panel it
 * replaces in a ride: every corner rounds by the planner's radius. The media
 * panel fuses into the top edge with this same radius, and the bar's top
 * corners square off while it is open ([MediaPanelJoin]).
 */
internal val DataBarCorner: Dp = ROUTE_PANEL_CORNER_RADIUS

/**
 * Map showing on each side of the data bar: the planner panel's margin, so the
 * ride island sits where the planner's did.
 */
internal val DataBarMargin: Dp = ROUTE_PANEL_MARGIN

/** Map showing below the data bar, above the navigation bar: the planner panel's. */
internal val DataBarBottomMargin: Dp = ROUTE_PANEL_BOTTOM_MARGIN

/** Data bar leading content padding, inside the island. */
internal val DataBarLeadingPadding: Dp = 4.dp

/** Data bar trailing content padding. */
internal val DataBarTrailingPadding: Dp = 8.dp

/** Space between the readouts and END on the single row. */
internal val DataBarEndGap: Dp = 4.dp

/**
 * END's minimum width: wider than its [GloveTarget] height, so it reads as a
 * button and not a square (the design draws 76 dp; 72 keeps the single row
 * fitting 360 dp phones).
 */
internal val EndPillMinWidth: Dp = 72.dp

/** Gap above and below the ride row, inside the island. */
internal val DataBarRowGap: Dp = 8.dp

/**
 * How much of the top gap the media panel may reach into when it fuses onto
 * the bar ([MediaPanelJoin.fuseDepth]); always inside [DataBarRowGap].
 */
internal val DataBarVerticalPadding: Dp = 6.dp

/**
 * The bar's row stays this wide (centred) on tablets and desktop windows; the
 * island itself spans the width between its margins.
 */
internal val DataBarMaxContentWidth: Dp = 640.dp

/**
 * Bottom-of-screen ride bar that replaces the planner controls in ride mode:
 * the media button, five readouts (speed, time left, distance left, the arrival
 * clock and the pace delta) and END, on a `surfaceContainer` bar. Pace is the
 * one coloured readout: success green "−8 s" gained while the rider is ahead of
 * the model time, error red "+12 s" while behind, plain "0 s" on plan.
 * [monochrome] (B&W ride mode) drops that hue and turns END neutral, so the sign
 * alone carries the direction there. Media and END keep their glove-sized
 * targets ([GloveTarget]).
 *
 * Every readout is the same value-over-caption cell at one shared size. The
 * layout is chosen from the real width ([NavigationHudFormat.planDataBar],
 * measured with the real font): one row where five equal cells fit, otherwise
 * speed beside a 2 × 2 grid of the trip readouts between media and END, and
 * only when even that cannot hold a legible value (a 320 dp phone at a large
 * font) the same grid takes the full width above a media/END row. Values are
 * never clipped or ellipsised: all of them step down together instead. The bar
 * is an island like the planner panel ([DataBarMargin], [DataBarCorner]),
 * floating above the system navigation bar so its controls stay reachable.
 */
@Composable
fun NavigationDataBar(
    snapshot: NavigationSnapshot,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    mediaPanelOpen: Boolean = false,
    onMediaToggle: () -> Unit = {},
    monochrome: Boolean = false,
    gpsLost: Boolean = false,
) {
    CurveMapsDarkTheme {
        NavigationDataBarContent(snapshot, onEnd, modifier, mediaPanelOpen, onMediaToggle, monochrome, gpsLost)
    }
}

@Composable
private fun NavigationDataBarContent(
    snapshot: NavigationSnapshot,
    onEnd: () -> Unit,
    modifier: Modifier,
    mediaPanelOpen: Boolean,
    onMediaToggle: () -> Unit,
    monochrome: Boolean,
    gpsLost: Boolean,
) {
    val staleAlpha = staleReadoutAlpha(gpsLost)
    val speedValue = NavigationHudFormat.speedText(snapshot.speedMps, snapshot.positionSource)
    val speedLabel = NavigationHudFormat.speedLabel(snapshot.positionSource)
    val etaValue = NavigationHudFormat.etaCompactText(snapshot.remainingTimeS)
    val distanceValue = NavigationHudFormat.distanceText(snapshot.remainingDistanceM)
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    // Recomputed on every snapshot (each GPS fix), so the clock follows the ride.
    val arrivalValue = NavigationHudFormat.arrivalClockText(
        nowMillis = System.currentTimeMillis(),
        remainingS = snapshot.remainingTimeS,
        use24Hour = use24Hour,
    )
    val paceValue = NavigationHudFormat.deltaText(snapshot.paceDeltaS)
    val readouts = buildReadouts(
        speedValue = speedValue,
        speedLabel = speedLabel,
        etaValue = etaValue,
        arrivalValue = arrivalValue,
        distanceValue = distanceValue,
        paceDeltaS = snapshot.paceDeltaS,
        paceValue = paceValue,
        monochrome = monochrome || gpsLost,
        staleAlpha = staleAlpha,
    )
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val measurer = rememberTextMeasurer()
    val valueRef = readoutValueStyle(NavigationHudFormat.MEASURE_REFERENCE_SP)
    val captionStyle = MaterialTheme.typography.labelMedium

    // The island floats above the system navigation bar, so the layout choice
    // measures the width beside any side navigation bar, inside its margins.
    BoxWithConstraints(modifier.navigationBarsPadding()) {
        val contentWidthDp = minOf(maxWidth - DataBarMargin * 2, DataBarMaxContentWidth).value
        // Widths at the real font: one measurement per string at a reference
        // size, scaled linearly (text width is proportional to its size).
        val valueWidths = HashMap<String, Float>()
        val widthOfValue: (String, Float) -> Float = { text, sp ->
            val atReference = valueWidths.getOrPut(text) {
                measurer.measure(
                    text = text,
                    style = valueRef,
                    maxLines = 1,
                    softWrap = false,
                ).size.width / density.density
            }
            atReference * sp / NavigationHudFormat.MEASURE_REFERENCE_SP
        }
        val widthOfCaption: (String) -> Float = { text ->
            measurer.measure(text = text, style = captionStyle, maxLines = 1, softWrap = false)
                .size.width / density.density
        }
        val plan = NavigationHudFormat.planDataBar(
            availableWidthDp = contentWidthDp,
            fontScale = fontScale,
            values = readouts.map { it.value } + NavigationHudFormat.VALUE_TEMPLATES,
            captions = readouts.map { it.captionText } + NavigationHudFormat.CAPTION_TEMPLATES,
            valueWidthDp = widthOfValue,
            captionWidthDp = widthOfCaption,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // The panel supplies the top rounding when open; the join itself
            // must be square or its corners expose little wedges of map.
            shape = RoundedCornerShape(
                topStart = MediaPanelJoin.barTopCorner(mediaPanelOpen),
                topEnd = MediaPanelJoin.barTopCorner(mediaPanelOpen),
                bottomStart = DataBarCorner,
                bottomEnd = DataBarCorner,
            ),
            // The bar's own shadow would fall across the panel's bottom edge and
            // draw the very seam the join removes; the panel supplies the
            // elevation for the fused pair.
            shadowElevation = MediaPanelJoin.barShadow(mediaPanelOpen),
            modifier = Modifier
                .padding(start = DataBarMargin, end = DataBarMargin, bottom = DataBarBottomMargin)
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Ride data: " +
                        NavigationHudFormat.speedDescription(speedValue, snapshot.positionSource) + ", " +
                        "${NavigationHudFormat.etaText(snapshot.remainingTimeS)} remaining, " +
                        "$distanceValue left, " +
                        "arriving $arrivalValue, " +
                        NavigationHudFormat.deltaDescription(snapshot.paceDeltaS)
                    if (gpsLost) stateDescription = NavigationHudFormat.STALE_STATE_DESCRIPTION
                },
        ) {
            val seam = MaterialTheme.colorScheme.outlineVariant
            Box(
                Modifier
                    .fillMaxWidth()
                    // While the media panel is open the two share one container
                    // colour; a hairline along the bar's top edge (the panel's
                    // fused bottom edge) tells them apart without a gap.
                    .drawBehind {
                        if (mediaPanelOpen) {
                            drawRect(seam, size = Size(size.width, MediaPanelJoin.SEAM_DP.dp.toPx()))
                        }
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    Modifier
                        .widthIn(max = DataBarMaxContentWidth)
                        .fillMaxWidth()
                        .padding(
                            start = DataBarLeadingPadding,
                            end = DataBarTrailingPadding,
                            top = DataBarRowGap,
                            bottom = DataBarRowGap,
                        ),
                ) {
                    when (plan.layout) {
                        DataBarLayout.SINGLE_ROW -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            MediaOpenButton(open = mediaPanelOpen, onClick = onMediaToggle)
                            readouts.forEach { ReadoutCell(it, plan.valueSp, Modifier.weight(1f)) }
                            Spacer(Modifier.width(DataBarEndGap))
                            EndRideButton(onEnd = onEnd, monochrome = monochrome)
                        }
                        DataBarLayout.GRID -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            MediaOpenButton(open = mediaPanelOpen, onClick = onMediaToggle)
                            ReadoutGrid(readouts, plan.valueSp, Modifier.weight(1f))
                            Spacer(Modifier.width(DataBarEndGap))
                            EndRideButton(onEnd = onEnd, monochrome = monochrome)
                        }
                        DataBarLayout.STACKED -> {
                            // Large font on a narrow phone: the grid takes the
                            // full width and media/END share the row below it.
                            ReadoutGrid(readouts, plan.valueSp, Modifier.fillMaxWidth())
                            Spacer(Modifier.height(GloveGap))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                MediaOpenButton(open = mediaPanelOpen, onClick = onMediaToggle)
                                Spacer(Modifier.weight(1f))
                                EndRideButton(onEnd = onEnd, monochrome = monochrome)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Glove-sized media button: far-left, the whole [GloveTarget] box takes the tap.
 * While the panel it opened is showing it is a selected toggle
 * (`secondaryContainer`), as in the design.
 */
@Composable
private fun MediaOpenButton(open: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    IconToggleButton(
        checked = open,
        onCheckedChange = { onClick() },
        shape = MaterialTheme.shapes.large,
        colors = IconButtonDefaults.iconToggleButtonColors(
            contentColor = colors.onSurface,
            checkedContainerColor = colors.secondaryContainer,
            checkedContentColor = colors.onSecondaryContainer,
        ),
        modifier = Modifier
            .size(GloveTarget)
            .semantics {
                contentDescription = "Media controls"
                stateDescription = if (open) "open" else "closed"
                role = Role.Button
            },
    ) {
        Icon(
            painterResource(if (open) R.drawable.ms_music_note_fill else R.drawable.ms_music_note),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Distinct END, on the right: an error-container button (inverse surface in
 * B&W) at least [GloveTarget] tall and [EndPillMinWidth] wide.
 */
@Composable
private fun EndRideButton(onEnd: () -> Unit, monochrome: Boolean) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onEnd,
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (monochrome) colors.inverseSurface else colors.errorContainer,
            contentColor = if (monochrome) colors.inverseOnSurface else colors.onErrorContainer,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = EndPillMinWidth, minHeight = GloveTarget)
            .semantics { contentDescription = "End navigation" },
    ) {
        Text("End", style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Width the ride's voice and B&W toggles take from the right edge: their
 * glove-sized hit boxes ([RouteActionsPill] in ride mode) and the inset that
 * centres them on the zoom group's axis. The guidance card keeps clear of it.
 */
internal val RideToolbarWidth: Dp =
    MapControlsLayout.rideToggleEndPadding() + MapControlsLayout.RIDE_TOGGLE_TARGET

/** One readout of the data bar: its value, caption and colours. */
private class Readout(
    val value: String,
    val captionText: String,
    val valueColor: Color,
    val captionColor: Color,
    /** Fades with the GPS-derived values. */
    val valueAlpha: Float,
    val captionAlpha: Float,
)

/**
 * The five readouts in reading order: speed, time left, distance left, the
 * arrival clock, the pace delta. The grid layouts show the speed beside a 2 × 2
 * of the rest, so time and distance sit over arrival and pace.
 */
@Composable
private fun buildReadouts(
    speedValue: String,
    speedLabel: String,
    etaValue: String,
    arrivalValue: String,
    distanceValue: String,
    paceDeltaS: Double,
    paceValue: String,
    monochrome: Boolean,
    staleAlpha: Float,
): List<Readout> {
    val colors = MaterialTheme.colorScheme
    val muted = colors.onSurfaceVariant
    val ink = colors.onSurface
    val isUnit = speedLabel == NavigationHudFormat.SPEED_UNIT
    val trend = NavigationHudFormat.paceTrend(paceDeltaS)
    val paceColor = when (trend) {
        PaceTrend.UNKNOWN -> muted
        PaceTrend.ON_PLAN -> ink
        PaceTrend.AHEAD -> if (monochrome) ink else CurveMapsTheme.colors.success
        PaceTrend.BEHIND -> if (monochrome) ink else colors.error
    }
    return listOf(
        Readout(
            value = speedValue,
            // "No GPS" is a state the rider should notice at a glance, so it
            // never dims (and takes the error role); the ordinary unit stays
            // muted and fades with the number.
            captionText = speedLabel,
            valueColor = ink,
            captionColor = if (isUnit) muted else colors.error,
            valueAlpha = staleAlpha,
            captionAlpha = if (isUnit) staleAlpha else 1f,
        ),
        Readout(etaValue, NavigationHudFormat.TIME_CAPTION, ink, muted, staleAlpha, staleAlpha),
        Readout(distanceValue, NavigationHudFormat.DISTANCE_CAPTION, ink, muted, staleAlpha, staleAlpha),
        Readout(arrivalValue, NavigationHudFormat.ARRIVAL_CAPTION, ink, muted, staleAlpha, staleAlpha),
        Readout(paceValue, NavigationHudFormat.paceCaption(paceDeltaS), paceColor, muted, staleAlpha, staleAlpha),
    )
}

/** Value over caption: the design's readout cell. Each value is its own text node. */
@Composable
private fun ReadoutCell(readout: Readout, valueSp: Float, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = NavigationHudFormat.CELL_PADDING_DP.dp),
    ) {
        Text(
            text = readout.value,
            style = readoutValueStyle(valueSp),
            color = readout.valueColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            // Filling the cell makes the text's box the box it is centred in, so
            // its laid-out line never reaches past its own bounds.
            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = readout.valueAlpha },
        )
        Text(
            text = readout.captionText,
            style = MaterialTheme.typography.labelMedium,
            color = readout.captionColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = readout.captionAlpha },
        )
    }
}

/** `titleLarge`, semibold, tabular figures, at [sp] (one size shared by every cell). */
@Composable
private fun readoutValueStyle(sp: Float): TextStyle {
    val base = MaterialTheme.typography.titleLarge
    return base.copy(
        fontSize = sp.sp,
        lineHeight = (sp * base.lineHeight.value / base.fontSize.value).sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )
}

/**
 * The readouts as three equal columns: speed, centred over both rows, then a
 * 2 × 2 of time left, distance left, arrival and pace. Equal columns and one
 * shared value size keep every baseline on the grid.
 */
@Composable
private fun ReadoutGrid(readouts: List<Readout>, valueSp: Float, modifier: Modifier = Modifier) {
    Row(modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        ReadoutCell(readouts[0], valueSp, Modifier.weight(1f))
        Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(NavigationHudFormat.GRID_ROW_GAP_DP.dp)) {
            Row(Modifier.fillMaxWidth()) {
                ReadoutCell(readouts[1], valueSp, Modifier.weight(1f))
                ReadoutCell(readouts[2], valueSp, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                ReadoutCell(readouts[3], valueSp, Modifier.weight(1f))
                ReadoutCell(readouts[4], valueSp, Modifier.weight(1f))
            }
        }
    }
}

/**
 * Opacity of the GPS-derived readouts: full while fixes arrive, faded to
 * [NavigationHudFormat.STALE_READOUT_ALPHA] while there is none, so the rider
 * sees at a glance that the numbers are not current. Eased both ways.
 */
@Composable
private fun staleReadoutAlpha(
    gpsLost: Boolean,
    staleAlpha: Float = NavigationHudFormat.STALE_READOUT_ALPHA,
): Float {
    val alpha by animateFloatAsState(
        targetValue = if (gpsLost) staleAlpha else 1f,
        animationSpec = tween(400),
        label = "staleReadoutAlpha",
    )
    return alpha
}

/**
 * Drawn manoeuvre glyphs, for the manoeuvres without a faithful Material
 * Symbol: the forward arrow while waiting for an instruction, a U-turn whose
 * side is unknown (both ways), and roundabouts (circulation direction from
 * GraphHopper's geometry; the exit number is a badge on the card).
 */
internal fun DrawScope.drawTurnArrow(
    turn: NavigationSnapshot.TurnInfo?,
    w: Float,
    h: Float,
    paint: Color = Color.White,
) {
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (NavigationHudFormat.maneuverGlyph(turn)) {
        ManeuverGlyph.U_TURN_UNKNOWN_SIDE -> drawUTurnArrow(0, w, h, paint, stroke)
        ManeuverGlyph.ROUNDABOUT -> drawRoundaboutArrow(
            w,
            h,
            turn?.sign == Instruction.LEAVE_ROUNDABOUT,
            turn?.roundaboutClockwise,
            paint,
            stroke,
        )
        else -> {
            // Keep a recognizable forward arrow while waiting for the next instruction.
            val path = Path().apply {
                moveTo(w * 0.5f, h * 0.2f)
                lineTo(w * 0.5f, h * 0.85f)
            }
            drawPath(path, paint, style = stroke)
            drawArrowHead(w * 0.5f, h * 0.2f, 0f, -1f, w, paint, stroke)
        }
    }
}

/**
 * One cell per lane of the road being left, left to right as the rider sees
 * them, on a darker strip under the card's text and divided by dashed lane
 * markings. The recommended lane(s) are filled with the card's content colour
 * and draw their arrows in the strip colour; the others stay dim. Count-only
 * lanes (no painted arrows in OSM) show the turn arrow in the suggested lane
 * only. Not a touch target.
 */
@Composable
internal fun LaneGuidanceRow(
    lanes: LaneGuidance,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    val strip = lerp(container, Color.Black, 0.28f)
    BoxWithConstraints(modifier.background(strip).padding(horizontal = 8.dp, vertical = 8.dp)) {
        // The cells share the strip's real width, so any lane count fits the card.
        val cellWidth = NavigationHudFormat.laneCellWidthDp(
            lanes.lanes.size,
            if (constraints.hasBoundedWidth) maxWidth.value else null,
        ).dp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.Center)
                .semantics {
                    contentDescription = "Lane guidance: " + lanes.description()
                    this[RecommendedLanesKey] = NavigationHudFormat.recommendedLanesValue(lanes)
                },
        ) {
            lanes.lanes.forEachIndexed { index, lane ->
                if (index > 0) {
                    Canvas(Modifier.size(width = 2.dp, height = LaneCellHeight)) {
                        val dash = size.height / 7f
                        var y = 0f
                        while (y < size.height) {
                            drawLine(
                                content.copy(alpha = 0.35f),
                                Offset(size.width / 2f, y),
                                Offset(size.width / 2f, (y + dash).coerceAtMost(size.height)),
                                strokeWidth = size.width,
                            )
                            y += dash * 2f
                        }
                    }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = cellWidth, height = LaneCellHeight)
                        .clip(MaterialTheme.shapes.medium)
                        .then(if (lane.recommended) Modifier.background(content) else Modifier),
                ) {
                    val arrowWidth = minOf(cellWidth, LaneArrowWidth)
                    Canvas(Modifier.size(width = arrowWidth, height = LaneArrowHeight)) {
                        val color = if (lane.recommended) strip else content.copy(alpha = 0.45f)
                        // A lane with no marking data still shows a (dim, plain
                        // ahead) arrow, so every cell of the strip reads alike.
                        val arrows = lane.arrows.ifEmpty { setOf(LaneArrow.STRAIGHT) }
                        drawLaneArrows(arrows, lanes.leftHandTraffic, size.width, size.height, color)
                    }
                }
            }
        }
    }
}

private val LaneCellHeight: Dp = 40.dp
private val LaneArrowWidth: Dp = 28.dp
private val LaneArrowHeight: Dp = 32.dp

/** Lane arrows sharing one stem, like painted road markings. */
private fun DrawScope.drawLaneArrows(
    arrows: Set<LaneArrow>,
    leftHandTraffic: Boolean,
    w: Float,
    h: Float,
    color: Color,
) {
    if (arrows.isEmpty()) return
    val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val cx = w * 0.5f
    val fork = h * 0.52f
    drawLine(color, Offset(cx, h * 0.95f), Offset(cx, fork), strokeWidth = stroke.width, cap = StrokeCap.Round)
    if (LaneArrow.STRAIGHT in arrows) {
        drawLine(color, Offset(cx, fork), Offset(cx, h * 0.12f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawArrowHead(cx, h * 0.12f, 0f, -1f, w, color, stroke)
    }
    for ((arrow, direction) in listOf(LaneArrow.LEFT to -1f, LaneArrow.RIGHT to 1f)) {
        if (arrow !in arrows) continue
        val endX = cx + direction * w * 0.36f
        val endY = h * 0.26f
        drawPath(
            Path().apply {
                moveTo(cx, fork)
                quadraticTo(cx, endY, endX, endY)
            },
            color,
            style = stroke,
        )
        drawArrowHead(endX, endY, direction, 0f, w, color, stroke)
    }
    if (LaneArrow.UTURN in arrows) {
        // Hooks across the oncoming side: right in left-hand traffic.
        val direction = if (leftHandTraffic) 1f else -1f
        val far = cx + direction * w * 0.3f
        drawPath(
            Path().apply {
                moveTo(cx, fork)
                lineTo(cx, h * 0.3f)
                quadraticTo(cx, h * 0.1f, (cx + far) / 2f, h * 0.1f)
                quadraticTo(far, h * 0.1f, far, h * 0.3f)
                lineTo(far, h * 0.5f)
            },
            color,
            style = stroke,
        )
        drawArrowHead(far, h * 0.5f, 0f, 1f, w, color, stroke)
    }
}

/** A neutral U-turn when GraphHopper does not provide a side (both ways). */
private fun DrawScope.drawUTurnArrow(
    side: Int,
    w: Float,
    h: Float,
    color: Color,
    stroke: Stroke,
) {
    if (side == 0) {
        val centerX = w * 0.5f
        val leftX = w * 0.27f
        val rightX = w * 0.73f
        val turnY = h * 0.45f
        val returnY = h * 0.68f
        val path = Path().apply {
            moveTo(centerX, h * 0.9f)
            lineTo(centerX, turnY)
            cubicTo(centerX, h * 0.2f, leftX, h * 0.2f, leftX, turnY)
            lineTo(leftX, returnY)
            moveTo(centerX, turnY)
            cubicTo(centerX, h * 0.2f, rightX, h * 0.2f, rightX, turnY)
            lineTo(rightX, returnY)
        }
        drawPath(path, color, style = stroke)
        drawArrowHead(leftX, returnY, 0f, 1f, w, color, stroke)
        drawArrowHead(rightX, returnY, 0f, 1f, w, color, stroke)
        return
    }

    val sideX = w * if (side < 0) 0.28f else 0.72f
    val path = Path().apply {
        moveTo(w * 0.5f, h * 0.88f)
        lineTo(w * 0.5f, h * 0.38f)
        cubicTo(w * 0.5f, h * 0.15f, sideX, h * 0.15f, sideX, h * 0.4f)
    }
    drawPath(path, color, style = stroke)
    drawArrowHead(sideX, h * 0.4f, 0f, 1f, w, color, stroke)
}

/** Entry into a roundabout; the selected exit number is a badge on the card. */
private fun DrawScope.drawRoundaboutArrow(
    w: Float,
    h: Float,
    leaving: Boolean,
    clockwise: Boolean?,
    color: Color,
    stroke: Stroke,
) {
    val diameter = w * 0.58f
    val radius = diameter * 0.5f
    val left = (w - diameter) * 0.5f
    val top = h * 0.16f
    val center = Offset(w * 0.5f, top + radius)
    drawPath(
        Path().apply {
            moveTo(center.x, h * 0.94f)
            lineTo(center.x, center.y + radius)
        },
        color,
        style = stroke,
    )
    // The lower gap joins the approach to the loop. GraphHopper supplies the
    // actual circulation direction; omit the arrowhead if its geometry is
    // ambiguous instead of implying the wrong direction.
    val startAngle = if (clockwise == false) 40f else 140f
    val sweepAngle = if (clockwise == false) -280f else 280f
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = Offset(left, top),
        size = Size(diameter, diameter),
        style = stroke,
    )
    clockwise?.let { isClockwise ->
        val endAngle = Math.toRadians((startAngle + sweepAngle).toDouble())
        val endX = center.x + radius * kotlin.math.cos(endAngle).toFloat()
        val endY = center.y + radius * kotlin.math.sin(endAngle).toFloat()
        val rotationSign = if (isClockwise) 1f else -1f
        val tangentX = -kotlin.math.sin(endAngle).toFloat() * rotationSign
        val tangentY = kotlin.math.cos(endAngle).toFloat() * rotationSign
        drawArrowHead(endX, endY, tangentX, tangentY, w, color, stroke)
    }
    if (leaving) {
        val exitY = center.y
        val exit = Path().apply {
            moveTo(center.x + radius, exitY)
            lineTo(w * 0.94f, exitY)
        }
        drawPath(exit, color, style = stroke)
        drawArrowHead(w * 0.94f, exitY, 1f, 0f, w, color, stroke)
    }
}

/** Arrowhead aligned to a normalized direction vector. */
private fun DrawScope.drawArrowHead(
    tipX: Float,
    tipY: Float,
    directionX: Float,
    directionY: Float,
    w: Float,
    color: Color,
    stroke: Stroke,
) {
    val length = kotlin.math.sqrt(directionX * directionX + directionY * directionY).coerceAtLeast(1e-3f)
    val ux = directionX / length
    val uy = directionY / length
    val headLength = w * 0.15f
    val wing = w * 0.12f
    val baseX = tipX - ux * headLength
    val baseY = tipY - uy * headLength
    val head = Path().apply {
        moveTo(baseX - uy * wing, baseY + ux * wing)
        lineTo(tipX, tipY)
        lineTo(baseX + uy * wing, baseY - ux * wing)
    }
    drawPath(head, color, style = stroke)
}


/** Layout chosen for the ride data bar (see [NavigationHudFormat.planDataBar]). */
enum class DataBarLayout {
    /** Media opener, five equal readouts and END on one row. */
    SINGLE_ROW,

    /** Media opener, speed beside a 2 x 2 of the trip readouts, and END on one row. */
    GRID,

    /** The grid across the full width, media and END on a row below it. */
    STACKED,
}

/** What [NavigationHudFormat.planDataBar] decided: the layout and the one value size (sp) of every cell. */
class DataBarPlan(val layout: DataBarLayout, val valueSp: Float)

/** Direction of the live pace delta, after rounding to whole seconds. */
enum class PaceTrend { UNKNOWN, ON_PLAN, AHEAD, BEHIND }

/**
 * The glyph a manoeuvre is shown with on the guidance card. Every glyph except
 * [U_TURN_UNKNOWN_SIDE] and [ROUNDABOUT] is a Material Symbol; those two have
 * no faithful symbol and stay drawn ([drawTurnArrow]).
 */
enum class ManeuverGlyph {
    STRAIGHT,
    TURN_LEFT, TURN_RIGHT,
    SHARP_LEFT, SHARP_RIGHT,
    SLIGHT_LEFT, SLIGHT_RIGHT,
    KEEP_LEFT, KEEP_RIGHT,
    U_TURN_LEFT, U_TURN_RIGHT, U_TURN_UNKNOWN_SIDE,
    ROUNDABOUT,
    FINISH, VIA,
}


/** Pure formatting helpers, JVM-testable. */
object NavigationHudFormat {
    /** The readout values: `titleLarge`. */
    const val READOUT_SP: Float = 22f

    /**
     * Headroom on every width check: values are measured once and scaled
     * linearly, and glyph advances do not scale exactly, so a value that fits
     * on paper must still fit by this margin to avoid a clipped last glyph.
     */
    private const val FIT_SAFETY = 1.04f

    /** Smallest font the readout values step down to; below it the bar stacks. */
    const val MIN_READOUT_SP: Float = 13f

    /** Rendered-height cap (dp) that keeps large font scales on one screen. */
    private const val READOUT_MAX_DP = 30f

    /** Readout captions: `labelMedium`. */
    const val CAPTION_SP: Float = 12f

    /** Horizontal padding inside each readout cell, each side. */
    const val CELL_PADDING_DP: Int = 2

    /** Vertical gap between the grid's two readout rows. */
    const val GRID_ROW_GAP_DP: Int = 4

    /** Size the bar measures its values at before scaling them to the fitted size. */
    const val MEASURE_REFERENCE_SP: Float = 20f

    /** Average glyph width of a semibold tabular value, in em (the pure estimate). */
    private const val VALUE_EM = 0.56f
    private const val CAPTION_EM = 0.5f

    /**
     * Smallest value size the single row accepts; below it the grid (bigger
     * cells, two rows) reads better than a squeezed row.
     */
    const val SINGLE_ROW_MIN_SP: Float = 15f

    /**
     * Longest realistic values, always counted with the live ones so the layout
     * and the size do not jump as the ride's numbers grow ("9m" to "1h 19m").
     */
    val VALUE_TEMPLATES: List<String> = listOf("888", "8h 88m", "888 km", "88:88", "+88 s")

    /** Every caption a cell can show, for the same reason. */
    val CAPTION_TEMPLATES: List<String> = listOf("km/h", "No GPS", "left", "arrive", "on plan")

    /** Caption under the time left. */
    const val TIME_CAPTION = "left"

    /** Caption under the distance left. */
    const val DISTANCE_CAPTION = "left"

    /** Caption under the arrival clock. */
    const val ARRIVAL_CAPTION = "arrive"

    /** Readout value font in sp, capped so it renders at most [READOUT_MAX_DP]. */
    fun readoutSp(fontScale: Float): Float = minOf(READOUT_SP, READOUT_MAX_DP / fontScale.coerceAtLeast(0.5f))

    /** The pure width estimate (dp) of [text] at [sp], used where nothing is measured. */
    fun estimatedValueWidthDp(text: String, sp: Float, fontScale: Float): Float =
        text.length.coerceAtLeast(1) * VALUE_EM * sp * fontScale.coerceAtLeast(0.5f)

    /** The pure width estimate (dp) of a caption. */
    fun estimatedCaptionWidthDp(text: String, fontScale: Float): Float =
        text.length * CAPTION_EM * CAPTION_SP * fontScale.coerceAtLeast(0.5f)

    /**
     * The largest value size (sp), at most [readoutSp] and at least
     * [MIN_READOUT_SP], at which every value and caption fits a cell of
     * [cellWidthDp]; null when even the smallest does not fit. Sizes step by
     * half an sp so the result is the same for every cell, never per cell.
     */
    fun fitReadoutSp(
        cellWidthDp: Float,
        fontScale: Float,
        values: List<String>,
        captions: List<String>,
        valueWidthDp: (String, Float) -> Float,
        captionWidthDp: (String) -> Float,
    ): Float? {
        val inner = cellWidthDp - 2 * CELL_PADDING_DP
        if (inner <= 0f || captions.any { captionWidthDp(it) * FIT_SAFETY + 1f > inner }) return null
        val largest = readoutSp(fontScale)
        val smallest = minOf(MIN_READOUT_SP, largest)
        var sp = largest
        while (sp >= smallest - 1e-3f) {
            if (values.all { valueWidthDp(it, sp) * FIT_SAFETY + 1f <= inner }) return sp
            sp -= 0.5f
        }
        return null
    }

    /**
     * Chooses the data bar's layout and the one value size from the real
     * width. [valueWidthDp] is the rendered width of a value at a size in sp
     * (font scale included) and [captionWidthDp] a caption's; the defaults are
     * estimates for tests. Order: one row of five equal cells when their size
     * stays at or above [SINGLE_ROW_MIN_SP]; else the grid between media and END
     * (three equal columns); else the grid on the full width above a media/END
     * row, at whatever size fits (never below the cap at huge font scales).
     * The speed caption is sized as the widest of "km/h" and "No GPS" so the
     * layout does not flip when the caption changes.
     */
    fun planDataBar(
        availableWidthDp: Float,
        fontScale: Float,
        values: List<String>,
        captions: List<String>,
        valueWidthDp: (String, Float) -> Float = { text, sp -> estimatedValueWidthDp(text, sp, fontScale) },
        captionWidthDp: (String) -> Float = { text -> estimatedCaptionWidthDp(text, fontScale) },
    ): DataBarPlan {
        val chrome = GloveTargetDp + EndPillWidthDp + LeadingPaddingDp + TrailingPaddingDp + EndGapDp
        fun fit(cell: Float) =
            fitReadoutSp(cell, fontScale, values, captions, valueWidthDp, captionWidthDp)
        fit((availableWidthDp - chrome) / 5f)
            ?.takeIf { it >= minOf(SINGLE_ROW_MIN_SP, readoutSp(fontScale)) }
            ?.let { return DataBarPlan(DataBarLayout.SINGLE_ROW, it) }
        fit((availableWidthDp - chrome) / 3f)?.let { return DataBarPlan(DataBarLayout.GRID, it) }
        val full = availableWidthDp - LeadingPaddingDp - TrailingPaddingDp
        val sp = fit(full / 3f) ?: minOf(MIN_READOUT_SP, readoutSp(fontScale))
        return DataBarPlan(DataBarLayout.STACKED, sp)
    }

    /** Pace caption under the delta: what the sign means. */
    fun paceCaption(deltaS: Double): String = when (paceTrend(deltaS)) {
        PaceTrend.UNKNOWN -> "pace"
        PaceTrend.ON_PLAN -> "on plan"
        PaceTrend.AHEAD -> "gained"
        PaceTrend.BEHIND -> "lost"
    }

    /** Width (dp) of one lane cell on the guidance card's strip; many lanes narrow it. */
    fun laneCellWidthDp(laneCount: Int, rowWidthDp: Float? = null): Float {
        val n = laneCount.coerceAtLeast(1)
        return if (rowWidthDp == null) {
            (240f / n).coerceIn(22f, 56f)
        } else {
            // The cells and their 2 dp dividers share the row's real width.
            ((rowWidthDp - 2f * (n - 1)) / n).coerceIn(16f, 56f)
        }
    }

    /**
     * The guidance card's (and No GPS chip's) fixed width: the design's 212 dp,
     * growing with the font scale so the 36 sp distance still fits, never above
     * 300 dp. Fixed so the card holds still from frame to frame.
     */
    fun guidanceCardWidthDp(fontScale: Float): Float = (212f * fontScale.coerceAtLeast(1f)).coerceAtMost(300f)

    /**
     * Data-bar chrome constants, mirrored from the Compose layout
     * ([GloveTarget] media button, [EndPillMinWidth], [DataBarLeadingPadding],
     * [DataBarTrailingPadding], [DataBarEndGap]).
     */
    internal const val GloveTargetDp = 64f
    internal const val EndPillWidthDp = 72f
    internal const val LeadingPaddingDp = 4f
    internal const val TrailingPaddingDp = 8f
    internal const val EndGapDp = 4f

    fun darkRideMapDescription(enabled: Boolean): String =
        "Dark ride map, ${if (enabled) "on" else "off"}"

    fun voiceSettingsDescription(enabled: Boolean, statusDescription: String? = null): String {
        val base = if (enabled) "Voice guidance settings, enabled" else "Voice guidance settings, disabled"
        val status = statusDescription?.trim()?.takeIf { enabled && it.isNotEmpty() }
        return if (status == null) base else "$base, $status"
    }

    fun speedKmh(speedMps: Double): String =
        if (speedMps.isNaN()) "—" else "${(speedMps * 3.6).toInt()}"

    const val SPEED_UNIT = "km/h"
    const val NO_GPS_LABEL = "No GPS"

    /**
     * Opacity of readouts that depend on GPS while there is no fix. White at
     * this opacity still clears WCAG AA on the ride surface (`ColorThemeTest`);
     * the pace delta drops its hue meanwhile, because dimmed green/red would not.
     */
    const val STALE_READOUT_ALPHA = 0.55f

    /**
     * The guidance card's manoeuvre glyph fades to this while there is no fix
     * (a graphic, 3:1 on the card, `RideChromeContrastTest`). The distance and
     * street stay at full strength: on the blue primary container the text has
     * under 5:1 to spare, so staleness is shown by the glyph, the No GPS chip
     * and the state description instead.
     */
    const val GUIDANCE_STALE_ALPHA = 0.8f

    /** Accessibility state of the guidance card and data bar while there is no fix. */
    const val STALE_STATE_DESCRIPTION = "Not current, no GPS fix"

    /**
     * The bar's speed number. A GPS-gap estimate keeps the map moving, but
     * its speed is a guess, so the speedometer shows a dash instead of it.
     */
    fun speedText(speedMps: Double, source: PositionSource): String =
        if (source.isLive) speedKmh(speedMps) else "—"

    /** What guidance is doing without a fix, after the chip's "No GPS"; null when it just waits. */
    fun noGpsDetail(source: PositionSource): String? = when (source) {
        PositionSource.ESTIMATED -> "position estimated"
        PositionSource.LOST -> "position held"
        PositionSource.GPS, PositionSource.RECONCILING -> null
    }

    fun noGpsDescription(source: PositionSource): String = when (source) {
        PositionSource.ESTIMATED -> "No GPS fix, position estimated along the route"
        PositionSource.LOST -> "No GPS fix, position held"
        PositionSource.GPS, PositionSource.RECONCILING -> "No GPS fix"
    }

    /** The label under the speed: the unit, or why there is no speed. */
    fun speedLabel(source: PositionSource): String = if (source.isLive) SPEED_UNIT else NO_GPS_LABEL

    /** Spoken form of the speed readout. */
    fun speedDescription(speedText: String, source: PositionSource): String = when (source) {
        PositionSource.ESTIMATED -> "GPS signal lost, position estimated"
        PositionSource.LOST -> "GPS signal lost"
        PositionSource.GPS, PositionSource.RECONCILING -> "$speedText kilometres per hour"
    }

    fun distanceText(meters: Double): String = when {
        meters.isNaN() || meters < 0 -> "—"
        meters >= 10_000 -> "%,.0f km".format(java.util.Locale.US, meters / 1000.0)
        meters >= 1_000 -> "%.1f km".format(java.util.Locale.US, meters / 1000.0)
        else -> "${meters.toInt()} m"
    }

    fun etaText(seconds: Double): String {
        val safe = seconds.coerceAtLeast(0.0)
        val totalMinutes = (safe / 60.0).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 -> "$hours h $minutes min"
            totalMinutes > 0 -> "$totalMinutes min"
            else -> "<1 min"
        }
    }

    /**
     * Narrower duration for the data-bar cell, e.g. "1h 19m" / "45m" / "2h".
     * The shorter form keeps the metric row from wrapping in screenshots and
     * on small displays.
     */
    fun etaCompactText(seconds: Double): String {
        val safe = seconds.coerceAtLeast(0.0)
        val totalMinutes = (safe / 60.0).toInt()
        if (totalMinutes <= 0) return "<1m"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    /** [RecommendedLanesKey] value: zero-based recommended lane indices over the lane count, e.g. "1,2/3". */
    fun recommendedLanesValue(lanes: LaneGuidance): String =
        lanes.recommendedIndices.joinToString(",") + "/" + lanes.lanes.size +
            if (lanes.marked) "" else " suggested"

    /** Maneuver name for accessibility and logs, aligned with GraphHopper signs. */
    fun turnDescription(turn: NavigationSnapshot.TurnInfo?): String {
        turn ?: return "continue ahead"
        val action = when (turn.sign) {
            Instruction.U_TURN_UNKNOWN,
            Instruction.U_TURN_LEFT,
            Instruction.U_TURN_RIGHT,
            -> "make a U-turn"
            Instruction.TURN_SHARP_LEFT -> "turn sharp left"
            Instruction.TURN_LEFT -> "turn left"
            Instruction.TURN_SLIGHT_LEFT -> "veer slightly left"
            Instruction.KEEP_LEFT -> "keep left"
            Instruction.TURN_SHARP_RIGHT -> "turn sharp right"
            Instruction.TURN_RIGHT -> "turn right"
            Instruction.TURN_SLIGHT_RIGHT -> "veer slightly right"
            Instruction.KEEP_RIGHT -> "keep right"
            Instruction.USE_ROUNDABOUT -> "enter the roundabout"
            Instruction.LEAVE_ROUNDABOUT -> "exit the roundabout"
            Instruction.FERRY -> "take the ferry"
            else -> "continue ahead"
        }
        val description = buildString {
            append("$action in ${distanceText(turn.distanceM)}")
            if (turn.sign == Instruction.USE_ROUNDABOUT) {
                turn.roundaboutExitNumber?.takeIf { it > 0 }?.let {
                    append(", then take the ${ordinal(it)} exit")
                }
            }
        }
        return if (turn.streetName.isBlank()) description else {
            "$description onto ${turn.streetName.trim()}"
        }
    }

    /**
     * The glyph for [turn]: the forward arrow while waiting for an instruction
     * (null), and for the continue / ferry / unknown signs. A U-turn with no
     * side (GraphHopper's unknown sign and no unambiguous route angle) and the
     * roundabout signs keep drawn glyphs.
     */
    fun maneuverGlyph(turn: NavigationSnapshot.TurnInfo?): ManeuverGlyph {
        turn ?: return ManeuverGlyph.STRAIGHT
        return when (turn.sign) {
            Instruction.TURN_LEFT -> ManeuverGlyph.TURN_LEFT
            Instruction.TURN_RIGHT -> ManeuverGlyph.TURN_RIGHT
            Instruction.TURN_SHARP_LEFT -> ManeuverGlyph.SHARP_LEFT
            Instruction.TURN_SHARP_RIGHT -> ManeuverGlyph.SHARP_RIGHT
            Instruction.TURN_SLIGHT_LEFT -> ManeuverGlyph.SLIGHT_LEFT
            Instruction.TURN_SLIGHT_RIGHT -> ManeuverGlyph.SLIGHT_RIGHT
            Instruction.KEEP_LEFT -> ManeuverGlyph.KEEP_LEFT
            Instruction.KEEP_RIGHT -> ManeuverGlyph.KEEP_RIGHT
            Instruction.U_TURN_LEFT, Instruction.U_TURN_RIGHT, Instruction.U_TURN_UNKNOWN ->
                when (uTurnSide(turn.sign, turn.turnAngleDeg)) {
                    -1 -> ManeuverGlyph.U_TURN_LEFT
                    1 -> ManeuverGlyph.U_TURN_RIGHT
                    else -> ManeuverGlyph.U_TURN_UNKNOWN_SIDE
                }
            Instruction.USE_ROUNDABOUT, Instruction.LEAVE_ROUNDABOUT -> ManeuverGlyph.ROUNDABOUT
            Instruction.FINISH -> ManeuverGlyph.FINISH
            Instruction.REACHED_VIA -> ManeuverGlyph.VIA
            else -> ManeuverGlyph.STRAIGHT
        }
    }

    /** Resolves a U-turn side from its sign or unambiguous route geometry. */
    fun uTurnSide(sign: Int, routeAngleDeg: Double?): Int = when (sign) {
        Instruction.U_TURN_LEFT -> -1
        Instruction.U_TURN_RIGHT -> 1
        Instruction.U_TURN_UNKNOWN -> routeAngleDeg
            ?.takeIf { it.isFinite() && abs(it) in 30.0..160.0 }
            ?.let { if (it < 0.0) -1 else 1 }
            ?: 0
        else -> 0
    }

    private fun ordinal(number: Int): String {
        val suffix = if (number % 100 in 11..13) {
            "th"
        } else {
            when (number % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$number$suffix"
    }

    /** Whole seconds of the pace delta, rounded half up on the magnitude. */
    fun paceSeconds(deltaS: Double): Int =
        if (deltaS.isFinite()) abs(deltaS).roundToInt() else 0

    /**
     * Direction of the pace delta. The engine reports actual minus model time,
     * so a negative delta is time gained (ahead) and a positive one time lost
     * (behind); anything under half a second rounds to on plan.
     */
    fun paceTrend(deltaS: Double): PaceTrend = when {
        !deltaS.isFinite() -> PaceTrend.UNKNOWN
        paceSeconds(deltaS) == 0 -> PaceTrend.ON_PLAN
        deltaS < 0.0 -> PaceTrend.AHEAD
        else -> PaceTrend.BEHIND
    }

    /**
     * Rider-facing pace, read like a lap-timer delta: "−8 s" ahead (time
     * gained, true minus sign), "+12 s" behind (time lost), "0 s" on plan, a dash until the engine has
     * measured. The bar colours it green/red; the sign alone carries the
     * direction in B&W mode.
     */
    fun deltaText(deltaS: Double): String = when (paceTrend(deltaS)) {
        PaceTrend.UNKNOWN -> "—"
        PaceTrend.ON_PLAN -> "0 s"
        PaceTrend.AHEAD -> "\u2212${paceSeconds(deltaS)} s"
        PaceTrend.BEHIND -> "+${paceSeconds(deltaS)} s"
    }

    fun deltaDescription(deltaS: Double): String {
        val seconds = paceSeconds(deltaS)
        val unit = if (seconds == 1) "second" else "seconds"
        return when (paceTrend(deltaS)) {
            PaceTrend.UNKNOWN -> "pace delta unavailable"
            PaceTrend.ON_PLAN -> "pace on plan"
            PaceTrend.AHEAD -> "pace $seconds $unit ahead"
            PaceTrend.BEHIND -> "pace $seconds $unit behind"
        }
    }

    /**
     * Estimated arrival clock time ("8:24", or "20:24" with the phone's 24-hour
     * setting): now plus the remaining model time. A dash when unknown.
     */
    fun arrivalClockText(
        nowMillis: Long,
        remainingS: Double,
        use24Hour: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (!remainingS.isFinite() || remainingS < 0.0) return "—"
        val arrival = Instant.ofEpochMilli(nowMillis)
            .plusMillis((remainingS * 1000.0).toLong())
            .atZone(zone)
        return DateTimeFormatter.ofPattern(if (use24Hour) "H:mm" else "h:mm", Locale.US).format(arrival)
    }
}
