package com.curvemaps.app

import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.curvemaps.app.geocoding.GeocodeController
import com.curvemaps.app.geocoding.GeocodeResult
import com.curvemaps.app.routing.RideFactors
import com.curvemaps.app.routing.RideStyle
import com.curvemaps.app.routing.RoundTripWish
import com.curvemaps.app.routing.curvyDistanceFraction
import com.curvemaps.app.routing.rideFactorSummary
import com.curvemaps.app.ui.theme.CurveMapsTheme
import com.graphhopper.ResponsePath
import kotlin.math.roundToInt

/** Shared planner surface colour: the floating panel, and the search results that extend it. */
internal val ROUTE_PANEL_COLOR: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerLow

/** The planner panel floats over the map: every corner is M3's extra large (28 dp). */
internal val ROUTE_PANEL_CORNER_RADIUS = 28.dp

/** Map showing on each side of the planner panel, and below it above the navigation bar. */
internal val ROUTE_PANEL_MARGIN = 12.dp
internal val ROUTE_PANEL_BOTTOM_MARGIN = 16.dp

/** Extra gap between the panel and the keyboard above its usual bottom margin. */
private val ROUTE_PANEL_KEYBOARD_GAP = 8.dp

/** The panel's widest extent (without its side margins) on a large window. */
internal val ROUTE_PANEL_MAX_WIDTH = 560.dp

/** The panel's elevation (M3 level 2). */
private val ROUTE_PANEL_ELEVATION = 3.dp

/** The panel never takes more than this share of the height it is offered; it scrolls instead. */
private const val PANEL_MAX_HEIGHT_FRACTION = 0.92f

/** Measures this node with at most [fraction] of the bounded height it is offered. */
private fun Modifier.limitHeightFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val limited = if (constraints.hasBoundedHeight) {
        constraints.copy(maxHeight = (constraints.maxHeight * fraction).roundToInt().coerceAtLeast(constraints.minHeight))
    } else {
        constraints
    }
    val placeable = measurable.measure(limited)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** Inside padding of the panel's panes. */
private val PANEL_PADDING = 16.dp
private val PANEL_GAP = 12.dp

/** Test seam: the pane the search results anchor to (the whole endpoints pane, panel padding included). */
internal const val ROUTE_ENDPOINT_GROUP_TEST_TAG = "route-endpoint-group"

/** Gap between the stacked From and To fields (connected like a button group). */
private val FIELD_GAP = PlannerKeys.ConnectedGap

/** Outer / meeting corner radius of the grouped From and To fields. */
private val FIELD_OUTER_CORNER = 16.dp
private val FIELD_INNER_CORNER = 4.dp

/** Start and Ride are full 56 dp buttons. */
internal val ACTION_BUTTON_HEIGHT = 56.dp

/** Test seam: RIDE's height (it is as tall as START). */
internal val RIDE_BUTTON_HEIGHT = ACTION_BUTTON_HEIGHT

/** Above this font scale the ETA steps down from headline to title size. */
private const val LARGE_FONT_SCALE = SELECTED_PLACE_SUBTITLE_MAX_FONT_SCALE

/** The status row below the fields keeps a minimum height in every state. */
private val ROUTE_STATUS_HEIGHT = 30.dp

/** Route card and its Finding placeholder share this minimum height, so the pane never jumps between them. */
private val ROUTE_CARD_MIN_HEIGHT = 88.dp
private val ROUTE_CARD_SHAPE = RoundedCornerShape(20.dp)

/**
 * The ride-style label, its value and the captions line up with the slider's
 * first and last stops: past the 48 dp back button and its 12 dp gap, then in
 * by the slider's end inset (less the dot's 2 dp radius).
 */
private val STYLE_TEXT_START = 60.dp + RIDE_STYLE_SLIDER_END_INSET - 2.dp
private val STYLE_TEXT_END = RIDE_STYLE_SLIDER_END_INSET - 2.dp

/** Accessibility name of the route card's bookmark button (a different node from the save bubble's "Save route"). */
internal const val ROUTE_CARD_SAVE_DESCRIPTION = "Save to saved rides"

/**
 * Which pane the planner shows. The ride-style slider only exists once a
 * search has found all seven options; until then (and after Back) the panel
 * holds the From/To fields.
 */
internal enum class PlannerStage { Endpoints, Styles }

/**
 * The one route the selected ride style produced, on a `tertiaryContainer`
 * card: its style and curvy share, time and distance (plus what each enabled
 * ride factor measured), a bookmark button that saves it, and a meter of the
 * curvy share. There is no carousel; the slider is the only way to ask for a
 * different ride. Long-press opens the save bubble; the bookmark button opens
 * it already saving.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RouteCard(
    path: ResponsePath,
    style: RideStyle,
    label: String = style.label,
    onSelectRoute: (Int) -> Unit,
    onSaveRoute: suspend () -> Boolean,
    modifier: Modifier = Modifier,
    rideFactors: RideFactors = RideFactors.NONE,
    graphHasElevation: Boolean = false,
    graphHasStops: Boolean = false,
) {
    val routeIndex = 0
    val view = LocalView.current
    var saveBubbleShown by remember { mutableStateOf(false) }
    var saveBubbleAutoSave by remember { mutableStateOf(false) }
    val metrics = "${formatRouteDuration(path.time)} · ${formatRouteDistance(path.distance)}"
    // What a slower style actually bought: share of the distance in curves,
    // measured with the router's own corner definition.
    val curvyFraction = remember(path) { curvyDistanceFraction(path.points) }
    val curvyText = remember(path) { routeCurvinessText(path) }
    val factorText = remember(path, rideFactors, graphHasElevation, graphHasStops) {
        rideFactorSummary(path, rideFactors, graphHasElevation, graphHasStops)
    }
    // The words wrap between " · " items, never inside a number and its unit.
    val shownTitle = "$label · ${keepTogether(curvyText)}"
    val shownDetail = listOfNotNull(
        formatRouteDuration(path.time), formatRouteDistance(path.distance),
    ).plus(factorText?.split(" · ").orEmpty()).joinToString(" · ") { keepTogether(it) }
    val foreground = MaterialTheme.colorScheme.onTertiaryContainer
    Surface(
        shape = ROUTE_CARD_SHAPE,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = foreground,
        modifier = modifier,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onSelectRoute(routeIndex) },
                    onLongClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        saveBubbleAutoSave = false
                        saveBubbleShown = true
                    },
                )
                .semantics {
                    contentDescription = "Route ${routeIndex + 1}: $label, $metrics, $curvyText" +
                        (factorText?.let { ", $it" } ?: "")
                }
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Each stop's numbers glide in from the side the slider moved
                // toward, so sliding reads as moving along a row of rides.
                AnimatedContent(
                    targetState = RouteCardText(shownTitle, shownDetail, style.ordinal),
                    transitionSpec = {
                        val direction = if (targetState.order >= initialState.order) 1 else -1
                        (slideInHorizontally(PANE_SLIDE) { direction * it / 5 } + fadeIn(tween(200, delayMillis = 40)))
                            .togetherWith(slideOutHorizontally(PANE_SLIDE) { -direction * it / 5 } + fadeOut(tween(120)))
                            .using(SizeTransform(clip = false))
                    },
                    modifier = Modifier.weight(1f),
                    label = "routeCardText",
                ) { text ->
                    // At least the bookmark button's height, centred; a taller
                    // card (large text) keeps the button at the top.
                    Column(Modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
                        Text(
                            text = text.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = text.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = foreground.copy(alpha = 0.92f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(
                    onClick = {
                        saveBubbleAutoSave = true
                        saveBubbleShown = true
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = ROUTE_CARD_SAVE_DESCRIPTION },
                ) {
                    Icon(painter = painterResource(R.drawable.ms_bookmark_add), contentDescription = null)
                }
            }
            CurvyMeter(
                fraction = curvyFraction.toFloat(),
                color = foreground,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
            )
        }
        SaveRouteBubble(
            visible = saveBubbleShown,
            metrics = metrics,
            onSave = onSaveRoute,
            onDismiss = { saveBubbleShown = false },
            autoSave = saveBubbleAutoSave,
        )
    }
}

/** [text] with its spaces made non-breaking, so a number and its unit wrap as one word. */
internal fun keepTogether(text: String): String = text.replace(' ', '\u00A0')

/** A 4 dp meter of the curvy share of the route: [fraction] filled, the rest a 32% track. Decorative (the card says the number). */
@Composable
private fun CurvyMeter(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(4.dp).clearAndSetSemantics {}) {
        val width = size.width
        val height = size.height
        val gap = 4.dp.toPx()
        val radius = CornerRadius(height / 2f)
        val filled = (width * fraction.coerceIn(0f, 1f))
        when {
            filled <= 0f -> drawRoundRect(color.copy(alpha = 0.32f), Offset.Zero, Size(width, height), radius)
            filled >= width -> drawRoundRect(color, Offset.Zero, Size(width, height), radius)
            else -> {
                drawRoundRect(color, Offset.Zero, Size(filled, height), radius)
                val trackStart = (filled + gap).coerceAtMost(width)
                drawRoundRect(color.copy(alpha = 0.32f), Offset(trackStart, 0f), Size(width - trackStart, height), radius)
            }
        }
    }
}

/**
 * Bottom route-planning panel, in two panes inside one floating M3 panel.
 *
 * The endpoints pane holds the grouped From/To fields beside the Use current
 * location and Round trip toggle keys, the round trip's settings when it is
 * on, and the full-width Start button. A search computes all seven ride-style
 * options before anything else shows. Once they are ready the styles pane pops
 * in where the fields were: the route card, a back button left of the wavy
 * ride-style slider, the ETA and the Ride button. Back (the button or the
 * system gesture) returns to the fields with the route still on the map.
 * Everything hides in ride mode. The panel is anchored to the bottom and the
 * map viewport ends where it begins.
 */
@Composable
internal fun RoutePlanPanel(
    fromText: String,
    fromResolved: Boolean,
    fromHint: String,
    onFromChange: (String) -> Unit,
    onEditFrom: () -> Unit,
    onFromPicked: (GeocodeResult) -> Unit,
    onUseCurrentLocation: () -> Unit,
    toText: String,
    toResolved: Boolean,
    onToChange: (String) -> Unit,
    onEditTo: () -> Unit,
    onToPicked: (GeocodeResult) -> Unit,
    geocodeController: GeocodeController,
    state: RouteUiState,
    stage: PlannerStage,
    readyStops: Int,
    onBackToEndpoints: () -> Unit,
    /** Window-pixel top of the open search-results sheet, or null when closed. */
    onSearchSheetTopChange: (Int?) -> Unit = {},
    onSelectRoute: (Int) -> Unit,
    onRoute: () -> Unit,
    complexity: Float,
    onComplexityChange: (Float) -> Unit,
    onComplexityChangeFinished: (Float) -> Unit,
    onSaveRoute: suspend (ResponsePath) -> Boolean,
    onToggleGuidance: () -> Unit,
    guidanceActive: Boolean,
    gpxImportError: String?,
    onDismissGpxError: () -> Unit,
    importedGpxName: String?,
    importedGpxMilestoneCount: Int,
    onOpenGpxPreview: () -> Unit,
    onEditImportedGpx: () -> Unit,
    followedGpx: FollowedGpx? = null,
    rideFactors: RideFactors = RideFactors.NONE,
    graphHasElevation: Boolean = false,
    graphHasStops: Boolean = false,
    /** The round-trip setting while a round trip is on (To gives way to it); null for A to B. */
    roundTrip: RoundTripWish? = null,
    onToggleRoundTrip: () -> Unit = {},
    onRoundTripChange: (RoundTripWish) -> Unit = {},
    /** The rider left the From field without picking a result. */
    onFromFocusLost: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val fromSearch = rememberRouteFieldSearchState()
    val toSearch = rememberRouteFieldSearchState()
    // A slider move onto a stop that is already settled passes through a
    // brief Loading state; keep the last route on the card so it never
    // blinks. A stop still being searched shows a placeholder instead.
    val success = state as? RouteUiState.Success
    val shown = success?.result?.routes?.getOrNull(success.selectedIndex)
    var lastShown by remember { mutableStateOf<ResponsePath?>(null) }
    if (shown != null) lastShown = shown
    val displayed = when {
        shown != null -> lastShown
        state is RouteUiState.Loading && RideStyle.fromLevel(complexity).ordinal < readyStops -> lastShown
        else -> null
    }
    val showStyles = stage == PlannerStage.Styles
    val nowMillis by rememberMinuteClock()
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    // The keyboard sits a little further from the panel than the navigation bar does.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // The panel is bottom-centred and never wider than a phone-sized sheet, so
    // a tablet or desktop window keeps the fields and the slider a usable length.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .widthIn(max = ROUTE_PANEL_MAX_WIDTH + ROUTE_PANEL_MARGIN * 2)
                .fillMaxWidth()
                // An island floating over the map, clear of the navigation bar;
                // in a ride the planner hides and takes no space.
                .then(
                    if (guidanceActive) {
                        Modifier
                    } else {
                        Modifier
                            .navigationBarsPadding()
                            .padding(
                                start = ROUTE_PANEL_MARGIN,
                                end = ROUTE_PANEL_MARGIN,
                                bottom = ROUTE_PANEL_BOTTOM_MARGIN + if (keyboardOpen) ROUTE_PANEL_KEYBOARD_GAP else 0.dp,
                            )
                    },
                )
        ) {
            // In ride mode the whole planner UI hides: the top HUD carries the
            // next turn and the NavigationDataBar carries speed, ETA, distance,
            // pace delta, and the END button. The rider cannot edit the plan
            // mid-ride, and the map gets the freed screen space.
            if (!guidanceActive) {
                Surface(
                    shape = RoundedCornerShape(ROUTE_PANEL_CORNER_RADIUS),
                    color = ROUTE_PANEL_COLOR,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = ROUTE_PANEL_ELEVATION,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            // A short window (landscape, large text) bounds the
                            // panel: each pane scrolls its body and keeps its
                            // primary action (Start, Ride) pinned below it.
                            .limitHeightFraction(PANEL_MAX_HEIGHT_FRACTION)
                            // Taps on the panel never reach the map, and the panel
                            // is one traversal group.
                            .pointerInput(Unit) {}
                            .semantics { isTraversalGroup = true }
                    ) {
                        AnimatedContent(
                            targetState = showStyles,
                            transitionSpec = { plannerPaneTransition(forward = targetState) },
                            label = "plannerPane",
                        ) { styles ->
                            if (styles) {
                                RideStylesPane(
                                    path = displayed,
                                    state = state,
                                    complexity = complexity,
                                    readyStops = readyStops,
                                    onBack = {
                                        focusManager.clearFocus()
                                        onBackToEndpoints()
                                    },
                                    onSelectRoute = onSelectRoute,
                                    onComplexityChange = onComplexityChange,
                                    onComplexityChangeFinished = onComplexityChangeFinished,
                                    onSaveRoute = onSaveRoute,
                                    rideFactors = rideFactors,
                                    graphHasElevation = graphHasElevation,
                                    graphHasStops = graphHasStops,
                                    onRide = onToggleGuidance,
                                    followedGpx = followedGpx,
                                )
                            } else {
                                EndpointsPane(
                                    fromText = fromText,
                                    fromResolved = fromResolved,
                                    fromHint = fromHint,
                                    onFromChange = onFromChange,
                                    onEditFrom = onEditFrom,
                                    onFromPicked = onFromPicked,
                                    onFromFocusLost = onFromFocusLost,
                                    onUseCurrentLocation = onUseCurrentLocation,
                                    toText = toText,
                                    toResolved = toResolved,
                                    onToChange = onToChange,
                                    onEditTo = onEditTo,
                                    onToPicked = onToPicked,
                                    geocodeController = geocodeController,
                                    fromSearch = fromSearch,
                                    toSearch = toSearch,
                                    state = state,
                                    onRoute = {
                                        focusManager.clearFocus()
                                        onRoute()
                                    },
                                    importedGpxName = importedGpxName,
                                    importedGpxMilestoneCount = importedGpxMilestoneCount,
                                    onOpenGpxPreview = onOpenGpxPreview,
                                    onEditImportedGpx = onEditImportedGpx,
                                    onSearchSheetTopChange = onSearchSheetTopChange,
                                    roundTrip = roundTrip,
                                    onToggleRoundTrip = {
                                        focusManager.clearFocus()
                                        onToggleRoundTrip()
                                    },
                                    nowMillis = nowMillis,
                                    use24Hour = use24Hour,
                                    onRoundTripChange = onRoundTripChange,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (gpxImportError != null) {
        AlertDialog(
            onDismissRequest = onDismissGpxError,
            title = { Text("Import failed") },
            text = { Text(gpxImportError) },
            confirmButton = {
                TextButton(onClick = onDismissGpxError) { Text("OK") }
            },
        )
    }
}

/** One spring for every planner slide, so panes and card text move as one. */
private val PANE_SLIDE = spring<IntOffset>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)

/**
 * Forward (styles popping in) slides the new pane in from the right and
 * scales it up from slightly small; Back reverses the direction. The panel's
 * height follows on a soft spring, so the map edge glides instead of jumping.
 */
private fun AnimatedContentTransitionScope<Boolean>.plannerPaneTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    val enter = slideInHorizontally(PANE_SLIDE) { direction * it / 4 } +
        fadeIn(tween(durationMillis = 260, delayMillis = 60, easing = LinearOutSlowInEasing)) +
        scaleIn(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.92f)
    val exit = slideOutHorizontally(PANE_SLIDE) { -direction * it / 4 } +
        fadeOut(tween(durationMillis = 140, easing = FastOutLinearInEasing)) +
        scaleOut(tween(durationMillis = 180), targetScale = 0.96f)
    return (enter togetherWith exit).using(
        SizeTransform(clip = false) { _, _ ->
            spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
        },
    )
}

/** The text of a route card; [order] is the stop, for the slide direction. */
private data class RouteCardText(val title: String, val detail: String, val order: Int)

/**
 * From/To fields (or the imported GPX rows), the toggle keys beside them, the
 * round trip's settings and the full-width Start button. The whole pane is
 * the anchor the search results sit on.
 */
@Composable
private fun EndpointsPane(
    fromText: String,
    fromResolved: Boolean,
    fromHint: String,
    onFromChange: (String) -> Unit,
    onEditFrom: () -> Unit,
    onFromPicked: (GeocodeResult) -> Unit,
    onFromFocusLost: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    toText: String,
    toResolved: Boolean,
    onToChange: (String) -> Unit,
    onEditTo: () -> Unit,
    onToPicked: (GeocodeResult) -> Unit,
    geocodeController: GeocodeController,
    fromSearch: RouteFieldSearchState,
    toSearch: RouteFieldSearchState,
    state: RouteUiState,
    onRoute: () -> Unit,
    importedGpxName: String?,
    importedGpxMilestoneCount: Int,
    onOpenGpxPreview: () -> Unit,
    onEditImportedGpx: () -> Unit,
    onSearchSheetTopChange: (Int?) -> Unit,
    roundTrip: RoundTripWish?,
    onToggleRoundTrip: () -> Unit,
    nowMillis: Long,
    use24Hour: Boolean,
    onRoundTripChange: (RoundTripWish) -> Unit,
) {
    val density = LocalDensity.current
    val latestSheetTopChange by rememberUpdatedState(onSearchSheetTopChange)
    DisposableEffect(Unit) { onDispose { latestSheetTopChange(null) } }
    // The search results are part of the panel: a sheet above the fields that
    // takes whatever height the panel has left after the fields and Start, so
    // it can never cover or cut them (it is left out when not even one whole
    // result row fits).
    val activeSearch = if (importedGpxName == null) {
        fromSearch.takeIf { it.active } ?: toSearch.takeIf { it.active }
    } else {
        null
    }
    PlannerSearchLayout(
        sheet = activeSearch?.let { search ->
            { maxHeightPx ->
                val searchingFrom = search === fromSearch
                RoutePlanSearchSheet(
                    state = search,
                    maxHeight = with(density) { maxHeightPx.toDp() },
                    onTopChange = { latestSheetTopChange(it) },
                    searchingLabel = if (searchingFrom) "From" else "To",
                    onUseCurrentLocation = if (searchingFrom) onUseCurrentLocation else null,
                )
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .testTag(ROUTE_ENDPOINT_GROUP_TEST_TAG),
        ) {
            // The fields scroll when the window is too short for them; Start
            // stays pinned under them.
            Column(
                verticalArrangement = Arrangement.spacedBy(PANEL_GAP),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = PANEL_PADDING, end = PANEL_PADDING, top = PANEL_PADDING, bottom = PANEL_GAP),
            ) {
                val locationInUse = currentLocationInFrom(fromText, fromResolved)
                val locationKeySemantics = Modifier.semantics {
                    contentDescription = "Use current location"
                    stateDescription = if (locationInUse) CURRENT_LOCATION_IN_USE_STATE else fromHint
                }
                if (importedGpxName != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
                        GpxEndpointRow(
                            label = "From",
                            value = fromText,
                            start = true,
                            actionLabel = "Edit",
                            onAction = onEditImportedGpx,
                            shape = FIELD_TOP_SHAPE,
                        )
                        GpxEndpointRow(
                            label = "To",
                            value = toText,
                            start = false,
                            milestoneCount = importedGpxMilestoneCount,
                            actionLabel = "Preview",
                            onAction = onOpenGpxPreview,
                            shape = FIELD_BOTTOM_SHAPE,
                        )
                    }
                } else if (roundTrip == null) {
                    // From over To (grouped, 16 dp outside and 4 dp where they
                    // meet), each with its toggle key beside it: the key is as
                    // tall as its field, so their edges line up at any text size.
                    Column(verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                        ) {
                            RoutePlanSearchField(
                                label = "From",
                                hint = fromHint,
                                icon = { FieldSymbol(R.drawable.ms_trip_origin_fill, MaterialTheme.colorScheme.primary) },
                                value = fromText,
                                onValueChange = onFromChange,
                                onResultPicked = onFromPicked,
                                controller = geocodeController,
                                searchState = fromSearch,
                                modifier = Modifier.weight(1f),
                                renderResultsPopup = false,
                                isResolved = fromResolved,
                                onEditResolved = onEditFrom,
                                onFocusLost = onFromFocusLost,
                                shape = FIELD_TOP_SHAPE,
                            )
                            // The key is a toggle: with a resolved current location
                            // in From it is on, and a second tap clears From.
                            CurrentLocationKey(
                                locationInUse, onUseCurrentLocation, PLANNER_KEYS_TOP_SHAPE,
                                locationKeySemantics.fillMaxHeight(),
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                        ) {
                            RoutePlanSearchField(
                                label = "To",
                                hint = "Route to",
                                icon = { FieldSymbol(R.drawable.ms_sports_score, MaterialTheme.colorScheme.onSurfaceVariant) },
                                value = toText,
                                onValueChange = onToChange,
                                onResultPicked = onToPicked,
                                controller = geocodeController,
                                searchState = toSearch,
                                modifier = Modifier.weight(1f),
                                renderResultsPopup = false,
                                isResolved = toResolved,
                                onEditResolved = onEditTo,
                                shape = FIELD_BOTTOM_SHAPE,
                            )
                            RoundTripToggle(
                                on = false, onToggle = onToggleRoundTrip,
                                modifier = Modifier.fillMaxHeight(), shape = PLANNER_KEYS_BOTTOM_SHAPE,
                            )
                        }
                    }
                } else {
                    // A round trip comes back to From: one field, the two keys
                    // side by side, and the settings card instead of To.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                    ) {
                        RoutePlanSearchField(
                            label = "From",
                            visibleLabel = "From and back",
                            hint = fromHint,
                            icon = { FieldSymbol(R.drawable.ms_trip_origin_fill, MaterialTheme.colorScheme.primary) },
                            value = fromText,
                            onValueChange = onFromChange,
                            onResultPicked = onFromPicked,
                            controller = geocodeController,
                            searchState = fromSearch,
                            modifier = Modifier.weight(1f),
                            renderResultsPopup = false,
                            isResolved = fromResolved,
                            onEditResolved = onEditFrom,
                            onFocusLost = onFromFocusLost,
                            shape = RoundedCornerShape(FIELD_OUTER_CORNER),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(PlannerKeys.ConnectedGap)) {
                            CurrentLocationKey(
                                locationInUse, onUseCurrentLocation, PLANNER_KEYS_START_SHAPE,
                                locationKeySemantics.fillMaxHeight(),
                            )
                            RoundTripToggle(
                                on = true, onToggle = onToggleRoundTrip,
                                modifier = Modifier.fillMaxHeight(), shape = PLANNER_KEYS_END_SHAPE,
                            )
                        }
                    }
                    RoundTripSettings(
                        wish = roundTrip,
                        nowMillis = nowMillis,
                        use24Hour = use24Hour,
                        onChange = onRoundTripChange,
                    )
                }
                SearchErrorStatus(state)
            }
            PlannerActionButton(
                label = "START",
                visibleLabel = "Start",
                icon = R.drawable.ms_route,
                enabled = state !is RouteUiState.Loading,
                onClick = onRoute,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = PANEL_PADDING, end = PANEL_PADDING, bottom = PANEL_PADDING),
            )
        }
    }
}

/** Gap between a field and the toggle key beside it. */
private val KEY_GAP = 8.dp

/** What [PlannerSearchLayout] places: the fields and Start, then the results sheet above them. */
private enum class PlannerSearchSlot { Body, Sheet }

/**
 * Lays [body] (the fields and Start) out first, within the height it is
 * offered, then gives [sheet] what is left of that height (never more than
 * [MAX_DROPDOWN_HEIGHT]) and stacks it above the body. The sheet composes
 * nothing when there is no [sheet], and whatever it composes is at most the
 * room it was given, so results cannot overlap or push the fields out.
 */
@Composable
private fun PlannerSearchLayout(
    sheet: (@Composable (maxHeightPx: Int) -> Unit)?,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val bodyConstraints = constraints.copy(minHeight = 0)
        val bodyPlaceables = subcompose(PlannerSearchSlot.Body, body).map { it.measure(bodyConstraints) }
        val width = bodyPlaceables.maxOfOrNull { it.width } ?: constraints.minWidth
        val bodyHeight = bodyPlaceables.sumOf { it.height }
        val sheetPlaceables = if (sheet == null) {
            emptyList()
        } else {
            val offered = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
            val room = (offered - bodyHeight).coerceIn(0, MAX_DROPDOWN_HEIGHT.roundToPx())
            subcompose(PlannerSearchSlot.Sheet) { sheet(room) }
                .map { it.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = room)) }
        }
        val sheetHeight = sheetPlaceables.sumOf { it.height }
        layout(width, sheetHeight + bodyHeight) {
            var y = 0
            sheetPlaceables.forEach { it.place(0, y); y += it.height }
            bodyPlaceables.forEach { it.place(0, y); y += it.height }
        }
    }
}

private val FIELD_TOP_SHAPE = RoundedCornerShape(
    topStart = FIELD_OUTER_CORNER, topEnd = FIELD_OUTER_CORNER,
    bottomStart = FIELD_INNER_CORNER, bottomEnd = FIELD_INNER_CORNER,
)
private val FIELD_BOTTOM_SHAPE = RoundedCornerShape(
    topStart = FIELD_INNER_CORNER, topEnd = FIELD_INNER_CORNER,
    bottomStart = FIELD_OUTER_CORNER, bottomEnd = FIELD_OUTER_CORNER,
)

/** A field's 22 dp leading symbol. */
@Composable
private fun FieldSymbol(drawable: Int, tint: Color) {
    Icon(
        painter = painterResource(drawable),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(22.dp),
    )
}

/** Use current location: an M3 toggle key, on while From holds the resolved current location. */
@Composable
private fun CurrentLocationKey(
    inUse: Boolean,
    onClick: () -> Unit,
    shape: RoundedCornerShape,
    semanticsModifier: Modifier,
) {
    PlannerToggleKey(
        checked = inUse,
        onCheckedChange = { onClick() },
        shape = shape,
        modifier = semanticsModifier,
    ) {
        Icon(
            painter = painterResource(if (inUse) R.drawable.ms_my_location_fill else R.drawable.ms_my_location),
            contentDescription = null,
        )
    }
}

/** Above the Start button: why the last search failed, if it did. */
@Composable
private fun SearchErrorStatus(state: RouteUiState) {
    if (state !is RouteUiState.Error) return
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROUTE_STATUS_HEIGHT)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = state.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The seven ride styles, where the fields were: the route card, then the
 * ride-style label over a tonal back button and the wavy slider, then the ETA
 * and the Ride button. It opens the moment a search starts; the wave grows one
 * hump per finished ride style ([readyStops]) and the card shows the selected
 * style's ride once its search is done ([path] is null until then, and the
 * card is the Finding placeholder).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RideStylesPane(
    path: ResponsePath?,
    state: RouteUiState,
    complexity: Float,
    readyStops: Int,
    onBack: () -> Unit,
    onSelectRoute: (Int) -> Unit,
    onComplexityChange: (Float) -> Unit,
    onComplexityChangeFinished: (Float) -> Unit,
    onSaveRoute: suspend (ResponsePath) -> Boolean,
    onRide: () -> Unit,
    rideFactors: RideFactors = RideFactors.NONE,
    graphHasElevation: Boolean = false,
    graphHasStops: Boolean = false,
    followedGpx: FollowedGpx? = null,
) {
    val style = RideStyle.fromLevel(complexity)
    var lastPath by remember { mutableStateOf(path) }
    if (path != null) lastPath = path
    Column(Modifier.fillMaxWidth()) {
        // The card and the slider scroll when the window is too short for them
        // (large text, landscape); the ETA and Ride stay pinned below.
        Column(
            verticalArrangement = Arrangement.spacedBy(PANEL_GAP),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = PANEL_PADDING, end = PANEL_PADDING, top = 12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ROUTE_CARD_MIN_HEIGHT),
            ) {
                AnimatedContent(
                    targetState = path != null,
                    transitionSpec = {
                        (
                            fadeIn(tween(240, delayMillis = 60)) +
                                scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium), initialScale = 0.97f)
                            ).togetherWith(fadeOut(tween(160)))
                    },
                    label = "routeCardReady",
                ) { ready ->
                    val cardPath = lastPath
                    val cardModifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ROUTE_CARD_MIN_HEIGHT)
                    if (ready && cardPath != null) {
                        RouteCard(
                            path = cardPath,
                            style = style,
                            label = followedGpx?.name ?: style.label,
                            onSelectRoute = onSelectRoute,
                            onSaveRoute = { onSaveRoute(cardPath) },
                            modifier = cardModifier,
                            rideFactors = rideFactors,
                            graphHasElevation = graphHasElevation,
                            graphHasStops = graphHasStops,
                        )
                    } else {
                        PendingRouteCard(followedGpx?.name ?: style.label, cardModifier, followedGpx != null)
                    }
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The label and the style's name share a line while they fit and
                // stack (name below) when they do not, never overlapping.
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = STYLE_TEXT_START, end = STYLE_TEXT_END),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = if (followedGpx != null) "Imported GPX track" else "Ride style",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = false,
                    )
                    if (followedGpx != null) {
                        Text(
                            text = "Your roads",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else AnimatedContent(
                        targetState = style,
                        transitionSpec = {
                            val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                            (
                                slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) { direction * it / 2 } +
                                    fadeIn(tween(160))
                                ).togetherWith(slideOutVertically(tween(140)) { -direction * it / 2 } + fadeOut(tween(100)))
                                .using(SizeTransform(clip = false))
                        },
                        label = "rideStyleLabel",
                    ) { shownStyle ->
                        Text(
                            text = shownStyle.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            softWrap = false,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // A tonal round button (secondaryContainer), 48 dp.
                    FilledTonalIconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = BACK_TO_ENDPOINTS_DESCRIPTION },
                    ) {
                        Icon(painter = painterResource(R.drawable.ms_arrow_back), contentDescription = null)
                    }
                    if (followedGpx != null) {
                        // The file chose the roads: there is no style to slide
                        // between, so the slider's place says how closely the
                        // route rides the track instead.
                        FollowedGpxNote(followedGpx, routed = path != null, modifier = Modifier.weight(1f))
                    } else {
                        RideStyleSlider(
                            value = complexity,
                            onValueChange = onComplexityChange,
                            onValueChangeFinished = onComplexityChangeFinished,
                            readyStops = readyStops,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (followedGpx == null) {
                    // The end captions sit under the first and last stops. Each
                    // gets half the width, so they can wrap but never run together.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = STYLE_TEXT_START, end = STYLE_TEXT_END)
                            .clearAndSetSemantics {},
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val caption = MaterialTheme.typography.bodySmall
                        val captionColor = MaterialTheme.colorScheme.onSurfaceVariant
                        Text(
                            RideStyle.entries.first().label, style = caption, color = captionColor,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            RideStyle.entries.last().label, style = caption, color = captionColor,
                            textAlign = TextAlign.End, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        RideFooter(
            statusNeeds = if (path == null && state !is RouteUiState.Error) null else etaWidth(statusPath(state, path)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = PANEL_PADDING, end = PANEL_PADDING, top = PANEL_GAP, bottom = PANEL_PADDING),
            status = {
                if (path == null && state !is RouteUiState.Error) {
                    SearchingStatus(followingGpx = followedGpx != null)
                } else {
                    RouteStatusSlot(state, fallback = path)
                }
            },
            ride = { buttonModifier ->
                // A slider move onto a settled stop is answered at once, so Ride
                // only waits while the selected style is searched.
                PlannerActionButton(
                    label = "RIDE",
                    visibleLabel = "Ride",
                    icon = R.drawable.ms_navigation_fill,
                    enabled = path != null,
                    onClick = onRide,
                    modifier = buttonModifier,
                    elevated = true,
                )
            },
        )
    }
}

/**
 * The ETA (or the searching status) beside the Ride button. When the ETA
 * duration ([statusNeeds], null while there is none) cannot stay on one line next to
 * the button (large text on a narrow phone), the button drops below it and
 * takes the full width instead.
 */
@Composable
private fun RideFooter(
    statusNeeds: Dp?,
    status: @Composable () -> Unit,
    ride: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = 12.dp
    val rideNeeds = rideButtonWidth()
    BoxWithConstraints(modifier) {
        if (statusNeeds == null || statusNeeds + gap + rideNeeds <= maxWidth) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.weight(1f)) { status() }
                ride(Modifier)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
                status()
                ride(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Width of the filled Ride button: its padding, symbol, gap and the word. */
@Composable
private fun rideButtonWidth(): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.titleMedium
    return with(density) {
        measurer.measure("Ride", style).size.width.toDp() + RIDE_BUTTON_CHROME
    }
}

private val RIDE_BUTTON_CHROME = 20.dp + 24.dp + 8.dp + 28.dp

/** The route the ETA slot shows: the selected route of a success, else [fallback]. */
private fun statusPath(state: RouteUiState, fallback: ResponsePath?): ResponsePath? {
    val success = state as? RouteUiState.Success
    return success?.let { it.result.routes.getOrNull(it.selectedIndex) ?: it.result.routes.first() } ?: fallback
}

/** Width the ETA slot's duration needs on one line (the distance may wrap below it). */
@Composable
private fun etaWidth(path: ResponsePath?): Dp? {
    path ?: return null
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val duration = etaDurationStyle()
    return with(density) { measurer.measure(formatRouteDuration(path.time), duration).size.width.toDp() }
}

/** The ETA duration's style (large font scales step down a size so the pane still fits a short window). */
@Composable
private fun etaDurationStyle(): androidx.compose.ui.text.TextStyle =
    (if (LocalDensity.current.fontScale > LARGE_FONT_SCALE) {
        MaterialTheme.typography.titleLarge
    } else {
        MaterialTheme.typography.headlineMedium
    }).copy(fontWeight = FontWeight.Medium)

/**
 * Stands in for the route card while the selected ride style is still being
 * searched (the Finding card): M3 Expressive's loading indicator, what is
 * happening, and the style's name for screen readers.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PendingRouteCard(label: String, modifier: Modifier = Modifier, followingGpx: Boolean = false) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = ROUTE_CARD_SHAPE,
        modifier = modifier.semantics {
            contentDescription = if (followingGpx) "Following the $label track" else "Finding the $label ride"
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            LoadingIndicator(Modifier.size(44.dp))
            Column {
                Text(
                    text = if (followingGpx) "Following your track road for road…" else "Finding this ride…",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (followingGpx) label else "All seven styles are searched together",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Beside the disabled Ride button while a style is searched: M3 Expressive's wavy progress and a line of text. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchingStatus(followingGpx: Boolean) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .heightIn(min = ROUTE_STATUS_HEIGHT)
            .fillMaxWidth(),
    ) {
        LinearWavyProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Searching ride styles" },
        )
        Text(
            text = if (followingGpx) "Routing your track" else "Searching styles",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A followed GPX track on the styles pane: its [name], and how much of it the
 * shown route does not ride ([offTrackMeters], null until measured).
 */
internal data class FollowedGpx(val name: String, val offTrackMeters: Double?)

/** Test seam: the followed-track note's description starts with this. */
internal const val FOLLOWED_GPX_NOTE_PREFIX = "GPX track"

/**
 * Where the slider sits for a followed GPX track: the file chose the roads,
 * so this says whether the route rides all of them, or how much it leaves
 * (a road the map lacks, a blocked unpaved section, a ferry), never hiding a
 * departure behind a style name.
 */
@Composable
private fun FollowedGpxNote(gpx: FollowedGpx, routed: Boolean, modifier: Modifier = Modifier) {
    val offTrack = gpx.offTrackMeters
    val (title, detail) = when {
        !routed -> "Following your track" to "Routing along the file's roads…"
        offTrack == null -> "Following your track" to "Checking the route against the file…"
        offTrack < 1.0 -> "Rides your track road for road" to "No ride style applies to an imported track"
        else -> "Leaves your track for ${formatRouteDistance(offTrack)}" to
            "No road on the map, or blocked by Route settings, there"
    }
    val warning = routed && offTrack != null && offTrack >= 1.0
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = 64.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$FOLLOWED_GPX_NOTE_PREFIX: $title. $detail"
            },
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (warning) CurveMapsTheme.colors.warning else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

/** Accessibility name (and test seam) of the styles pane's back button. */
internal const val BACK_TO_ENDPOINTS_DESCRIPTION = "Back to From and To"

/**
 * The planner's two big actions: START (full width under the fields) and RIDE
 * (beside the ETA), M3 filled buttons 56 dp tall with a leading symbol and a
 * `titleMedium` label. The visible word is [visibleLabel] ("Start", "Ride");
 * the node's accessibility text stays [label] ("START", "RIDE") so screen
 * readers and test seams keep the same names. Disabled uses M3's disabled
 * colours.
 */
@Composable
private fun PlannerActionButton(
    label: String,
    visibleLabel: String,
    icon: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        elevation = if (elevated) ButtonDefaults.buttonElevation(defaultElevation = 1.dp) else ButtonDefaults.buttonElevation(),
        contentPadding = PaddingValues(start = 20.dp, end = 28.dp),
        modifier = modifier
            .heightIn(min = ACTION_BUTTON_HEIGHT)
            .semantics { text = AnnotatedString(label) },
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = visibleLabel,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** Read-only, geolocated endpoint row used while an imported GPX owns the plan. */
@Composable
private fun GpxEndpointRow(
    label: String,
    value: String,
    start: Boolean,
    shape: RoundedCornerShape,
    milestoneCount: Int = 0,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    height: Dp = SEARCH_FIELD_MIN_HEIGHT,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = height)
                .then(
                    if (onAction != null) Modifier.clickable(onClick = onAction)
                    else Modifier
                )
                .semantics {
                    contentDescription = if (actionLabel == "Preview") {
                        "$label: $value. Preview GPX route, $milestoneCount milestones"
                    } else {
                        "$label: $value${if (actionLabel != null) ". $actionLabel imported route" else ""}"
                    }
                }
                .padding(start = 12.dp, end = 16.dp),
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (start) {
                    FieldSymbol(R.drawable.ms_trip_origin_fill, MaterialTheme.colorScheme.primary)
                } else {
                    FieldSymbol(R.drawable.ms_sports_score, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (actionLabel != null) {
                // The whole row is the button; the word is its M3 text action.
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * ETA beside the Ride button. It keeps a minimum height in every state. On
 * narrow screens, the distance moves below the ETA. The row grows with
 * wrapped text and the user's font scale instead of clipping the selected
 * route's duration.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RouteStatusSlot(state: RouteUiState, fallback: ResponsePath? = null) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROUTE_STATUS_HEIGHT)
    ) {
        val success = state as? RouteUiState.Success
        (success?.let { it.result.routes.getOrNull(it.selectedIndex) ?: it.result.routes.first() } ?: fallback)?.let { selectedPath ->
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterStart)
            ) {
                Text(
                    text = formatRouteDuration(selectedPath.time),
                    style = etaDurationStyle(),
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                Text(
                    text = formatRouteDistance(selectedPath.distance),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
        (state as? RouteUiState.Error)?.let {
            Text(
                text = it.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterStart)
            )
        }
    }
}

/** Card text for a route's curvy-distance share, e.g. "42% curvy". */
internal fun routeCurvinessText(path: ResponsePath): String =
    "${(curvyDistanceFraction(path.points) * 100.0).roundToInt()}% curvy"
