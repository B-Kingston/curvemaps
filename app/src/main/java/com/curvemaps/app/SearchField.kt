package com.curvemaps.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.curvemaps.app.geocoding.GeocodeResult
import com.curvemaps.app.geocoding.GeocodeResultType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.curvemaps.app.geocoding.GeocodeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "CurveMaps.SearchField"
/**
 * Debounce window for the offline search-as-you-type fields. Internal (not
 * private) because SearchDebounceTest advances its virtual clock past exactly
 * this value — change it in one place and both stay honest.
 */
internal const val SEARCH_DEBOUNCE_MS = 300L
private const val MAX_DROPDOWN_ROWS = 6

/** Max dropdown height before the result list scrolls (keeps the overlay from sprawling). */
internal val MAX_DROPDOWN_HEIGHT = 320.dp

/**
 * A result row is at least a 40 dp leading circle plus 8 dp above and below
 * (the M3 two-line list item), which is also its 48 dp-plus touch target.
 */
internal val MIN_SEARCH_RESULT_TARGET_HEIGHT = 56.dp
private val SEARCH_RESULT_ROW_VERTICAL_PADDING = 8.dp
private val SEARCH_RESULT_ICON_CIRCLE = 40.dp
private val SEARCH_RESULT_START_PADDING = 16.dp
private val SEARCH_RESULT_END_PADDING = 24.dp
private val SEARCH_RESULT_ICON_GAP = 16.dp

/** Width the row's text loses to its padding and leading circle. */
private val SEARCH_RESULT_TEXT_INSET =
    SEARCH_RESULT_START_PADDING + SEARCH_RESULT_ICON_CIRCLE + SEARCH_RESULT_ICON_GAP + SEARCH_RESULT_END_PADDING

/** The chips row on top of the results: "Searching From" and "Use current location". */
internal val SEARCH_POPUP_HEADER_HEIGHT = 56.dp

/** A planner field is at least this tall (the M3 filled text field's 56 dp). */
internal val SEARCH_FIELD_MIN_HEIGHT = 56.dp

/** Result rows visible in the route-planning popup before scrolling. */
internal const val VISIBLE_RESULT_ROWS = 2.5f

/** The note under the results: where the search runs and the exact-point escape hatch. */
internal const val SEARCH_OFFLINE_NOTE = "Searched on this phone. Type lat,lon for an exact point."

/** True only for debuggable (debug) builds — raw user text is never logged in release. */
private fun isDebugBuild(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
/** Search-as-you-type state rendered in the dropdown below the field. */
internal sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Results(val results: List<GeocodeResult>) : SearchUiState
    data object Empty : SearchUiState
}

/**
 * Search state for one route-planning field and its anchored result popup.
 */
@Stable
class RouteFieldSearchState internal constructor() {
    internal var uiState by mutableStateOf<SearchUiState>(SearchUiState.Idle)
    internal var hasFocus by mutableStateOf(false)
    internal var onPick: (GeocodeResult) -> Unit = {}

    /** The text the shown results answer, so their matching part can be set in bold. */
    internal var query by mutableStateOf("")

    /** True while this field owns an active result popup. */
    val active: Boolean get() = hasFocus && uiState != SearchUiState.Idle
}

@Composable
fun rememberRouteFieldSearchState(): RouteFieldSearchState = remember { RouteFieldSearchState() }
/**
 * Route-planning field with 300 ms debounced offline search-as-you-type,
 * drawn as an M3 filled text field: a leading symbol, the label above the
 * value, a container whose corners [shape] sets (the planner groups From and
 * To by rounding only their outer corners). By default, results render in an
 * anchored popup above the focused field. A host can disable that popup and
 * render [RoutePlanSearchResultsPopup] from a shared anchor.
 */
/** Above this font scale the round-trip value drops its caption line. */
internal const val SELECTED_PLACE_SUBTITLE_MAX_FONT_SCALE = 1.3f

@Composable
fun RoutePlanSearchField(
    label: String,
    hint: String,
    icon: @Composable () -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    onResultPicked: (GeocodeResult) -> Unit,
    controller: GeocodeController,
    searchState: RouteFieldSearchState,
    modifier: Modifier = Modifier,
    renderResultsPopup: Boolean = true,
    isResolved: Boolean = false,
    onEditResolved: () -> Unit = {},
    rowHeight: Dp = SEARCH_FIELD_MIN_HEIGHT,
    /** The rider left the field (tapped elsewhere, Done, or the keyboard closed it). */
    onFocusLost: () -> Unit = {},
    /** The label shown above the value, when it differs from the accessibility [label] ("From and back"). */
    visibleLabel: String = label,
    shape: Shape = RoundedCornerShape(16.dp),
) {
    val focusManager = LocalFocusManager.current
    val debugLogs = isDebugBuild(LocalContext.current)
    // The shared result popup (rendered by the host or by this field) invokes
    // searchState.onPick; without this wiring its default is a no-op lambda,
    // so taps on result rows would do nothing.
    SideEffect { searchState.onPick = onResultPicked }
    val density = LocalDensity.current
    var fieldWidthDp by remember { mutableStateOf(0.dp) }
    val focusRequester = remember { FocusRequester() }
    var focusAfterEdit by remember { mutableStateOf(false) }
    // The field owns its selection so a tap that focuses it can select the
    // whole entry: the rider replaces a place in one go instead of deleting it.
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val shownFieldValue = syncedFieldValue(fieldValue, value)
    val latestValue by rememberUpdatedState(value)
    var fieldFocused by remember { mutableStateOf(false) }
    var textAtFocus by remember { mutableStateOf("") }
    LaunchedEffect(fieldFocused) {
        // After the focusing tap has placed its caret (that happens in the
        // same input event), so the tap cannot undo the selection. Text typed
        // in the meantime is never selected out from under the rider.
        val current = syncedFieldValue(fieldValue, latestValue)
        if (fieldFocused && current.text == textAtFocus) {
            fieldValue = selectAllForReplacement(current)
        }
    }

    LaunchedEffect(isResolved) {
        if (!isResolved && focusAfterEdit) {
            focusRequester.requestFocus()
            focusAfterEdit = false
        }
    }

    LaunchedEffect(value, isResolved, controller) {
        if (isResolved || value.isBlank()) {
            searchState.uiState = SearchUiState.Idle
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        if (debugLogs) Log.d(TAG, "[$label] debounce elapsed — searching \"$value\"")
        searchState.uiState = SearchUiState.Loading
        val results = try {
            withContext(Dispatchers.IO) { controller.search(value) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Propagate cancellation so stale results never replace a newer search.
            throw e
        } catch (e: Exception) {
            // Index unavailable (e.g. assets missing): degrade to empty results
            // so the lat,lon PointParser fallback stays usable.
            if (debugLogs) Log.w(TAG, "[$label] search for \"$value\" failed — degrading to empty results", e)
            emptyList()
        }
        if (debugLogs) {
            if (results.isEmpty()) {
                Log.d(TAG, "[$label] \"$value\" -> no results")
            } else {
                Log.d(
                    TAG,
                    "[$label] \"$value\" -> ${results.size} result(s): " +
                        results.take(MAX_DROPDOWN_ROWS).joinToString(", ") { it.name }
                )
            }
        }
        searchState.query = value
        searchState.uiState = if (results.isEmpty()) SearchUiState.Empty else SearchUiState.Results(results)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val width = with(density) { coordinates.size.width.toDp() }
                if (width != fieldWidthDp) fieldWidthDp = width
            },
    ) {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // A minimum, not a fixed height: large font scales grow the
                    // row instead of clipping its text.
                    .heightIn(min = rowHeight)
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
                if (isResolved) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = rowHeight)
                            .clickable(
                                role = Role.Button,
                                onClick = {
                                    focusAfterEdit = true
                                    onEditResolved()
                                },
                            )
                            .semantics { contentDescription = "$label selected: $value" }
                            .padding(start = 12.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = visibleLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                // Large text gets a second line, so a place name
                                // is not cut to a few letters.
                                maxLines = if (density.fontScale > SELECTED_PLACE_SUBTITLE_MAX_FONT_SCALE) 2 else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // Tapping the picked place edits it.
                        Icon(
                            painter = painterResource(R.drawable.ms_edit),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(18.dp),
                        )
                    }
                } else {
                    BasicTextField(
                        value = shownFieldValue,
                        onValueChange = { edited ->
                            val changed = edited.text != shownFieldValue.text
                            fieldValue = edited
                            if (changed) {
                                if (debugLogs) Log.v(TAG, "[$label] text changed: \"${edited.text}\"")
                                onValueChange(edited.text)
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            // Commit the typed text and drop focus: dismissing the IME
                            // must also leave search-suggestion mode so the ride
                            // controls (dial/status) come back without any race.
                            onDone = { focusManager.clearFocus() }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = rowHeight)
                            .focusRequester(focusRequester)
                            .semantics { contentDescription = label }
                            .padding(start = 12.dp, end = 16.dp)
                            .onFocusChanged { focused ->
                                if (focused.isFocused && !fieldFocused) textAtFocus = shownFieldValue.text
                                fieldFocused = focused.isFocused
                                val wasFocused = searchState.hasFocus
                                searchState.hasFocus = focused.isFocused
                                if (wasFocused && !focused.isFocused) {
                                    searchState.uiState = SearchUiState.Idle
                                    onFocusLost()
                                }
                            },
                        decorationBox = { innerTextField ->
                            // The label sits above the value (a filled text
                            // field with its label floated); the whole row is
                            // the text field, so a tap anywhere on it focuses.
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = visibleLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (value.isEmpty()) {
                                        Text(
                                            text = hint,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        }
                    )
                }
            }
        }

        // Suggestions use a separate, non-focusable popup. The popup ignores
        // outside-window input so a soft-keyboard tap cannot clear field focus.
        if (renderResultsPopup && searchState.active && fieldWidthDp > 0.dp) {
            RoutePlanSearchResultsPopup(
                state = searchState,
                width = fieldWidthDp,
            )
        }
    }
}

/**
 * Renders route suggestions above a shared endpoint-group anchor.
 *
 * The popup uses the anchor's top edge. RoutePlanPanel places that anchor
 * around the whole panel, so results never cover the fields. The sheet is an
 * M3 search-view list: a chips row ([searchingLabel] names the field being
 * searched, [onUseCurrentLocation] adds the current-location chip when it is
 * non-null), two-line rows with a leading symbol, and the offline note. The
 * chips row only shows when a whole result row fits under it.
 */
@Composable
internal fun RoutePlanSearchResultsPopup(
    state: RouteFieldSearchState,
    width: Dp,
    maxHeight: Dp = MAX_DROPDOWN_HEIGHT,
    centerHorizontally: Boolean = false,
    /** The sheet's measured height in pixels while shown; 0 once it closes. */
    onShownHeightPx: (Int) -> Unit = {},
    /** "From" / "To": the chip naming the searched field; null shows no chips row. */
    searchingLabel: String? = null,
    /** Puts the rider's location in From from the chips row; null leaves that chip out. */
    onUseCurrentLocation: (() -> Unit)? = null,
) {
    val cappedMaxHeight = maxHeight.coerceIn(0.dp, MAX_DROPDOWN_HEIGHT)
    val density = LocalDensity.current
    val minimumRowHeightPx = minimumSearchResultPopupHeightPx(
        state = state.uiState,
        width = width,
        titleStyle = MaterialTheme.typography.bodyLarge,
        subtitleStyle = MaterialTheme.typography.bodyMedium,
    )
    val popupMaxHeightPx = with(density) { cappedMaxHeight.roundToPx() }
    val headerPx = if (searchingLabel != null) with(density) { SEARCH_POPUP_HEADER_HEIGHT.roundToPx() } else 0
    // Show at most 2.5 rows (the half row signals that the rest scroll) under
    // the chips row, which gives way before a result row would.
    val popupHeight = searchPopupHeight(popupMaxHeightPx, minimumRowHeightPx, headerPx) ?: return
    val popupHeightDp = with(density) { popupHeight.heightPx.toDp() }

    val focusManager = LocalFocusManager.current
    val latestShownHeight by rememberUpdatedState(onShownHeightPx)
    DisposableEffect(Unit) { onDispose { latestShownHeight(0) } }
    Popup(
        popupPositionProvider = remember(centerHorizontally) {
            AboveAnchorPopupPositionProvider(centerHorizontally)
        },
        onDismissRequest = { state.uiState = SearchUiState.Idle },
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // Popup owns a separate Android window. Pin the measured density into
        // its content so font-scale metrics used for the fit check are exactly
        // the metrics used to render the result row.
        CompositionLocalProvider(LocalDensity provides density) {
            SearchDropdown(
                width = width,
                maxHeight = popupHeightDp,
                shape = RoundedCornerShape(
                    topStart = ROUTE_PANEL_CORNER_RADIUS,
                    topEnd = ROUTE_PANEL_CORNER_RADIUS,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp,
                ),
                color = ROUTE_PANEL_COLOR,
                shadowElevation = 0.dp,
                header = if (popupHeight.showsHeader && searchingLabel != null) {
                    { SearchSheetHeader(state, searchingLabel, onUseCurrentLocation) }
                } else {
                    null
                },
                modifier = Modifier
                    .onSizeChanged { latestShownHeight(it.height) }
                    .semantics { contentDescription = "Search results" },
            ) {
                SearchSheetRows(state)
            }
        }
    }
}

/**
 * The search results as the top of the planner panel: the chips row, then the
 * rows (two and a half before they scroll, fading out at the cut) and the
 * offline note, in at most [maxHeight]. It composes nothing when not even one
 * whole result row fits, and [onTopChange] reports its window-pixel top while
 * it is shown (null once it closes).
 */
@Composable
internal fun RoutePlanSearchSheet(
    state: RouteFieldSearchState,
    maxHeight: Dp,
    onTopChange: (Int?) -> Unit,
    searchingLabel: String?,
    onUseCurrentLocation: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val minimumRowHeightPx = minimumSearchResultPopupHeightPx(
        state = state.uiState,
        width = SEARCH_SHEET_TEXT_MEASURE_WIDTH,
        titleStyle = MaterialTheme.typography.bodyLarge,
        subtitleStyle = MaterialTheme.typography.bodyMedium,
    )
    val headerPx = if (searchingLabel != null) with(density) { SEARCH_POPUP_HEADER_HEIGHT.roundToPx() } else 0
    val sheetHeight = searchPopupHeight(with(density) { maxHeight.roundToPx() }, minimumRowHeightPx, headerPx)
    val latestTopChange by rememberUpdatedState(onTopChange)
    val shown = sheetHeight != null
    DisposableEffect(shown) { onDispose { if (shown) latestTopChange(null) } }
    if (sheetHeight == null) return
    SearchDropdown(
        width = null,
        maxHeight = with(density) { sheetHeight.heightPx.toDp() },
        shape = RoundedCornerShape(0.dp),
        color = Color.Transparent,
        shadowElevation = 0.dp,
        header = if (sheetHeight.showsHeader && searchingLabel != null) {
            { SearchSheetHeader(state, searchingLabel, onUseCurrentLocation) }
        } else {
            null
        },
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { latestTopChange(it.positionInWindow().y.roundToInt()) }
            .semantics { contentDescription = "Search results" },
    ) {
        SearchSheetRows(state)
    }
}

/** Width used to measure a row's text for the whole-row fit check: a phone-sized sheet. */
private val SEARCH_SHEET_TEXT_MEASURE_WIDTH = 360.dp

@Composable
private fun SearchSheetHeader(
    state: RouteFieldSearchState,
    searchingLabel: String,
    onUseCurrentLocation: (() -> Unit)?,
) {
    val focusManager = LocalFocusManager.current
    SearchChipsRow(
        searchingLabel = searchingLabel,
        onUseCurrentLocation = onUseCurrentLocation?.let { useLocation ->
            {
                state.uiState = SearchUiState.Idle
                focusManager.clearFocus()
                useLocation()
            }
        },
    )
}

/** The rows of a results sheet for the state's current search. */
@Composable
private fun SearchSheetRows(state: RouteFieldSearchState) {
    val focusManager = LocalFocusManager.current
    when (val current = state.uiState) {
        SearchUiState.Idle -> Unit
        SearchUiState.Loading -> DropdownMessage("Searching…")
        SearchUiState.Empty -> {
            DropdownMessage("No results", dim = true)
            OfflineNote()
        }
        is SearchUiState.Results -> {
            current.results.forEach { result ->
                ResultRow(result, state.query) {
                    state.uiState = SearchUiState.Idle
                    focusManager.clearFocus()
                    state.onPick(result)
                }
            }
            OfflineNote()
        }
    }
}

/**
 * The popup's height rule, in pixels. Null while the space above the panel
 * cannot show one complete result row. Otherwise at most [VISIBLE_RESULT_ROWS]
 * rows, plus the chips row ([headerPx], 0 for none) only when a whole row
 * still fits under it.
 */
internal data class SearchPopupHeight(val showsHeader: Boolean, val heightPx: Int)

internal fun searchPopupHeight(maxHeightPx: Int, rowHeightPx: Int, headerPx: Int): SearchPopupHeight? {
    if (!hasRoomForCompleteSearchResultRow(maxHeightPx, rowHeightPx)) return null
    val showsHeader = headerPx > 0 && maxHeightPx >= headerPx + rowHeightPx
    val header = if (showsHeader) headerPx else 0
    val rows = visibleSearchResultsHeightPx(rowHeightPx, dividerPx = 0, maxHeightPx = maxHeightPx - header)
    return SearchPopupHeight(showsHeader, header + rows)
}

/** "Searching From" (a static tonal label) and, for From, an assist chip that uses the current location. */
@Composable
private fun SearchChipsRow(searchingLabel: String, onUseCurrentLocation: (() -> Unit)?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(SEARCH_POPUP_HEADER_HEIGHT)
            .padding(horizontal = 16.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.height(32.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 8.dp, end = 12.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (searchingLabel == "To") R.drawable.ms_sports_score else R.drawable.ms_trip_origin_fill,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Searching $searchingLabel",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
        if (onUseCurrentLocation != null) {
            AssistChip(
                onClick = onUseCurrentLocation,
                label = { Text("Use current location", style = MaterialTheme.typography.labelLarge, maxLines = 1) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ms_my_location),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

/** The offline note under the results. */
@Composable
private fun OfflineNote() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ms_offline_pin),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = SEARCH_OFFLINE_NOTE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The field's editing state for an external [text]: unchanged while it already
 * shows that text (keeping the rider's selection), otherwise the new text with
 * the caret at its end (a picked place, current location, a cleared field).
 */
internal fun syncedFieldValue(current: TextFieldValue, text: String): TextFieldValue =
    if (current.text == text) current else TextFieldValue(text, TextRange(text.length))

/** Focusing a From/To field selects its whole entry, so typing replaces it. */
internal fun selectAllForReplacement(value: TextFieldValue): TextFieldValue =
    value.copy(selection = TextRange(0, value.text.length))

/**
 * Result surface; route-planning fields override it to extend the panel. A
 * null [width] fills the width offered. The rows scroll under the chips row,
 * and fade into the surface at the bottom edge while more are hidden, so a
 * cut row reads as "scroll for more", not as clipped.
 */
@Composable
private fun SearchDropdown(
    width: Dp?,
    maxHeight: Dp = MAX_DROPDOWN_HEIGHT,
    shape: Shape = MaterialTheme.shapes.small,
    color: Color = MaterialTheme.colorScheme.surface,
    shadowElevation: Dp = 4.dp,
    header: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val fadeTo = if (color == Color.Transparent) ROUTE_PANEL_COLOR else color
    Surface(
        modifier = (if (width != null) modifier.width(width) else modifier)
            .heightIn(max = maxHeight),
        shape = shape,
        color = color,
        shadowElevation = shadowElevation,
    ) {
        Column(Modifier.fillMaxWidth()) {
            header?.invoke()
            Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll),
                    content = content,
                )
                if (scroll.canScrollForward) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(SEARCH_SCROLL_FADE_HEIGHT)
                            .background(Brush.verticalGradient(listOf(fadeTo.copy(alpha = 0f), fadeTo))),
                    )
                }
            }
        }
    }
}

/** Height of the fade at the bottom of a scrolling results list. */
private val SEARCH_SCROLL_FADE_HEIGHT = 28.dp

/** Places a popup directly above its text-field anchor. */
internal class AboveAnchorPopupPositionProvider(
    private val centerHorizontally: Boolean = false,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val desiredX = if (centerHorizontally) {
            anchorBounds.left + (anchorBounds.right - anchorBounds.left - popupContentSize.width) / 2
        } else {
            when (layoutDirection) {
                LayoutDirection.Ltr -> anchorBounds.left
                LayoutDirection.Rtl -> anchorBounds.right - popupContentSize.width
            }
        }
        val x = desiredX.coerceIn(0, maxX)
        val y = (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** Limits popup height to the pixels above the visible window viewport and the configured cap. */
internal fun maxSearchDropdownHeightPx(
    availableAboveAnchorPx: Int,
    preferredMaxHeightPx: Int,
    visibleViewportTopPx: Int = 0,
): Int {
    val visibleHeight = (availableAboveAnchorPx - visibleViewportTopPx).coerceAtLeast(0)
    return minOf(visibleHeight, preferredMaxHeightPx.coerceAtLeast(0))
}

/**
 * Measures the first result with the same one-line styles and width as [ResultRow].
 * TextMeasurer uses Compose's actual platform font metrics, including nonlinear
 * Android font scaling and font padding that cannot be recovered from lineHeight.
 */
@Composable
internal fun minimumSearchResultPopupHeightPx(
    state: SearchUiState,
    width: Dp,
    titleStyle: TextStyle,
    subtitleStyle: TextStyle,
): Int {
    val firstResult = (state as? SearchUiState.Results)?.results?.firstOrNull()
    val density = LocalDensity.current
    val targetHeightPx = with(density) { MIN_SEARCH_RESULT_TARGET_HEIGHT.roundToPx() }
    if (firstResult == null) return targetHeightPx

    val textMeasurer = rememberTextMeasurer()
    val textWidthPx = with(density) {
        (width - SEARCH_RESULT_TEXT_INSET).coerceAtLeast(0.dp).roundToPx()
    }
    val constraints = Constraints(maxWidth = textWidthPx)
    val titleHeightPx = textMeasurer.measure(
        text = firstResult.name,
        style = titleStyle,
        overflow = TextOverflow.Ellipsis,
        softWrap = true,
        maxLines = 1,
        constraints = constraints,
    ).size.height
    val subtitleHeightPx = if (firstResult.subtitle.isNotBlank()) {
        textMeasurer.measure(
            text = firstResult.subtitle,
            style = subtitleStyle,
            overflow = TextOverflow.Ellipsis,
            softWrap = true,
            maxLines = 1,
            constraints = constraints,
        ).size.height
    } else {
        0
    }
    val paddingHeightPx = with(density) {
        (SEARCH_RESULT_ROW_VERTICAL_PADDING * 2).roundToPx()
    }
    return searchResultRowMinimumHeightPx(
        titleHeightPx = titleHeightPx,
        subtitleHeightPx = subtitleHeightPx,
        paddingHeightPx = paddingHeightPx,
        targetHeightPx = targetHeightPx,
    )
}

/** Pure size rule kept separate so row gating can be checked without Android text layout. */
internal fun searchResultRowMinimumHeightPx(
    titleHeightPx: Int,
    subtitleHeightPx: Int,
    paddingHeightPx: Int,
    targetHeightPx: Int,
): Int = maxOf(
    targetHeightPx.coerceAtLeast(0),
    titleHeightPx.coerceAtLeast(0) +
        subtitleHeightPx.coerceAtLeast(0) +
        paddingHeightPx.coerceAtLeast(0),
)

/** Height of [VISIBLE_RESULT_ROWS] rows plus the dividers between them, capped at [maxHeightPx]. */
internal fun visibleSearchResultsHeightPx(rowHeightPx: Int, dividerPx: Int, maxHeightPx: Int): Int {
    val fullRows = VISIBLE_RESULT_ROWS.toInt()
    val height = (rowHeightPx * VISIBLE_RESULT_ROWS).toInt() + dividerPx * fullRows
    return minOf(height, maxHeightPx.coerceAtLeast(0))
}

/** True only when the result surface can show one complete first result row. */
internal fun hasRoomForCompleteSearchResultRow(availableHeightPx: Int, rowHeightPx: Int): Boolean =
    rowHeightPx > 0 && availableHeightPx >= rowHeightPx

/** The Material Symbol that leads a result row of this kind. */
internal fun searchResultSymbol(type: GeocodeResultType): Int = when (type) {
    GeocodeResultType.LOCALITY -> R.drawable.ms_location_city
    GeocodeResultType.STREET -> R.drawable.ms_route
    GeocodeResultType.POI -> R.drawable.ms_location_on
    GeocodeResultType.COORDINATE -> R.drawable.ms_location_searching
}

/**
 * Where the words of [query] occur in [text] (case-insensitive, merged and
 * sorted): the part of a result the rider typed, which the row sets in bold.
 */
internal fun searchMatchRanges(text: String, query: String): List<IntRange> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val found = mutableListOf<IntRange>()
    words.forEach { word ->
        var from = 0
        while (true) {
            val at = text.indexOf(word, from, ignoreCase = true)
            if (at < 0) break
            found += at until at + word.length
            from = at + word.length
        }
    }
    val merged = mutableListOf<IntRange>()
    found.sortedBy { it.first }.forEach { range ->
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}

/** [text] with the parts that match [query] in semibold. */
internal fun highlightedSearchMatch(text: String, query: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    searchMatchRanges(text, query).forEach { range ->
        append(text.substring(cursor, range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(range.first, range.last + 1)) }
        cursor = range.last + 1
    }
    append(text.substring(cursor))
}

@Composable
private fun ResultRow(result: GeocodeResult, query: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SEARCH_RESULT_ICON_GAP),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = result.name }
            .heightIn(min = MIN_SEARCH_RESULT_TARGET_HEIGHT)
            .padding(
                start = SEARCH_RESULT_START_PADDING,
                end = SEARCH_RESULT_END_PADDING,
                top = SEARCH_RESULT_ROW_VERTICAL_PADDING,
                bottom = SEARCH_RESULT_ROW_VERTICAL_PADDING,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(SEARCH_RESULT_ICON_CIRCLE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Icon(
                painter = painterResource(searchResultSymbol(result.type)),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = highlightedSearchMatch(result.name, query),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.subtitle.isNotBlank()) {
                Text(
                    text = result.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DropdownMessage(text: String, dim: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}
