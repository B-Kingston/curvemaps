package com.curvemaps.app

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.curvemaps.app.storage.GpxMilestone
import com.curvemaps.app.ui.theme.CurveMapsTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Height of one milestone row; the snap list's item size. */
private val MILESTONE_ROW_HEIGHT = 56.dp

/**
 * Share of the screen the sheet's content takes. The planner camera pads the
 * map by [GPX_PREVIEW_MAP_PADDING_FRACTION] so the focused milestone sits in
 * the part of the map the sheet leaves visible.
 */
internal const val GPX_PREVIEW_SHEET_FRACTION = 0.6f
internal const val GPX_PREVIEW_MAP_PADDING_FRACTION = 0.55

/**
 * The title a milestone row shows: its place name, else Route start,
 * Waypoint n (numbered like the badge on its row) or Route finish.
 */
internal fun milestoneTitle(placeName: String?, index: Int, count: Int): String = placeName ?: when (index) {
    0 -> "Route start"
    count - 1 -> "Route finish"
    else -> "Waypoint $index"
}

/**
 * Distributes snap targets over the list's real scroll range. The eased
 * curve leaves Start and Finish flush with the list bounds while reserving
 * distinct scroll positions for the milestones next to them.
 */
private object GpxSelectionSnapPosition : SnapPosition {
    override fun position(
        layoutSize: Int,
        itemSize: Int,
        beforeContentPadding: Int,
        afterContentPadding: Int,
        itemIndex: Int,
        itemCount: Int,
    ): Int {
        if (itemCount <= 1) return beforeContentPadding
        val scrollRange = (
            beforeContentPadding + itemCount * itemSize + afterContentPadding - layoutSize
        ).coerceAtLeast(0)
        val progress = itemIndex.toFloat() / (itemCount - 1)
        val curvedProgress = progress * progress * (3f - 2f * progress)
        val targetScroll = (scrollRange * curvedProgress).roundToInt()
        return beforeContentPadding + itemIndex * itemSize - targetScroll
    }
}

/**
 * A route scrubber disguised as a readable milestone timeline in a Material 3
 * modal bottom sheet. The item nearest the curved selection slot snaps into
 * its detent and drives the map camera. [onEdit] and [onRide] add the Edit and
 * Ride buttons when given; each is expected to close the preview itself.
 * [following] says whether the file is ridden road for road (the success card)
 * or routed between its stops (a neutral card); [note] is the card's detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpxRoutePreviewSheet(
    routeName: String,
    milestones: List<GpxMilestone>,
    onFocusMilestone: (GpxMilestone) -> Unit,
    onDismiss: () -> Unit,
    note: String? = null,
    following: Boolean = true,
    onEdit: (() -> Unit)? = null,
    onRide: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val flingBehavior = rememberSnapFlingBehavior(listState, GpxSelectionSnapPosition)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val rowHeightPx = with(LocalDensity.current) { MILESTONE_ROW_HEIGHT.roundToPx() }
    var focusedIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(listState, milestones) {
        snapshotFlow {
            val info = listState.layoutInfo
            info.visibleItemsInfo.minByOrNull { item ->
                abs(
                    item.offset - GpxSelectionSnapPosition.position(
                        layoutSize = info.viewportSize.height,
                        itemSize = item.size,
                        beforeContentPadding = info.beforeContentPadding,
                        afterContentPadding = info.afterContentPadding,
                        itemIndex = item.index,
                        itemCount = info.totalItemsCount,
                    ),
                )
            }?.index
        }.distinctUntilChanged().collect { index ->
            if (index != null && index in milestones.indices) {
                if (index != focusedIndex) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                focusedIndex = index
                onFocusMilestone(milestones[index])
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // The sheet is as tall as its content, so it opens fully rather than
        // at the half-height anchor that would hide the buttons.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // The map stays visible behind the sheet: the focused milestone's
        // marker and camera are the point of scrubbing.
        scrimColor = Color.Transparent,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(GPX_PREVIEW_SHEET_FRACTION)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Preview route",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        routeName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Close route preview" },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ms_close),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // How the file is ridden (followed road for road, or routed
            // between its stops) and any waypoints left out.
            RideModeCard(following = following, note = note)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    state = listState,
                    flingBehavior = flingBehavior,
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "GPX route milestones" },
                ) {
                    itemsIndexed(milestones, key = { index, item -> "$index:${item.progress}" }) { index, item ->
                        MilestoneRow(
                            milestone = item,
                            index = index,
                            count = milestones.size,
                            focused = index == focusedIndex,
                            onClick = {
                                focusedIndex = index
                                scope.launch {
                                    val info = listState.layoutInfo
                                    val targetPosition = GpxSelectionSnapPosition.position(
                                        layoutSize = info.viewportSize.height,
                                        itemSize = info.visibleItemsInfo.firstOrNull()?.size ?: rowHeightPx,
                                        beforeContentPadding = info.beforeContentPadding,
                                        afterContentPadding = info.afterContentPadding,
                                        itemIndex = index,
                                        itemCount = milestones.size,
                                    )
                                    listState.animateScrollToItem(index, -targetPosition)
                                }
                                onFocusMilestone(item)
                            },
                        )
                    }
                }
            }
            if (onEdit != null || onRide != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    if (onEdit != null) {
                        OutlinedButton(
                            onClick = onEdit,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) {
                            Icon(painterResource(R.drawable.ms_edit), contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Edit", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (onRide != null) {
                        Button(
                            onClick = onRide,
                            modifier = Modifier
                                .weight(1.4f)
                                .height(56.dp),
                        ) {
                            Icon(painterResource(R.drawable.ms_navigation_fill), contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Ride", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The card under the sheet title: success-container "Following your track"
 * when the file is ridden road for road, a neutral tonal card when it is
 * routed between its stops. [note] is the detail line.
 */
@Composable
private fun RideModeCard(following: Boolean, note: String?) {
    val container = if (following) CurveMapsTheme.colors.successContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (following) CurveMapsTheme.colors.onSuccessContainer else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            painter = painterResource(if (following) R.drawable.ms_check_circle_fill else R.drawable.ms_route),
            contentDescription = null,
            tint = content,
        )
        Column {
            Text(
                text = if (following) "Following your track" else "Routing between your stops",
                style = MaterialTheme.typography.titleSmall,
                color = content,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = content,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MilestoneRow(
    milestone: GpxMilestone,
    index: Int,
    count: Int,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val title = milestoneTitle(milestone.placeName, index, count)
    val distance = if (index == 0) "Start" else "${formatRouteDistance(milestone.distanceMeters)} from start"
    val detail = listOfNotNull(milestone.placeDetail?.takeIf(String::isNotBlank), distance)
        .joinToString(" · ")
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(MILESTONE_ROW_HEIGHT)
            .padding(horizontal = 16.dp)
            .background(
                if (focused) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                MaterialTheme.shapes.medium,
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "$title, $distance${if (focused) ", focused on map" else ""}"
            }
            .padding(horizontal = 12.dp),
    ) {
        // The timeline: a connector above and below the marker, none past the ends.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(24.dp)
                .fillMaxHeight(),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .width(2.dp)
                    .background(if (index == 0) Color.Transparent else line),
            )
            when (index) {
                0 -> Icon(
                    painter = painterResource(R.drawable.ms_trip_origin_fill),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                count - 1 -> Icon(
                    painter = painterResource(R.drawable.ms_sports_score_fill),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                else -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(22.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                ) {
                    Text(
                        index.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .width(2.dp)
                    .background(if (index == count - 1) Color.Transparent else line),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (focused) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (focused) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${(milestone.progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
