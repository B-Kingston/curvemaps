package com.curvemaps.app

import android.text.format.DateUtils
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.curvemaps.app.storage.SavedRoute
import com.curvemaps.app.storage.SavedRouteComment
import com.curvemaps.app.storage.SavedRouteSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Upper bound for one comment; pasted multi-MB text can ANR the cursor. */
private const val MAX_COMMENT_CHARS = 500
private const val TAG_SAVED = "CurveMaps.SavedRoutes"

/** The count beside the top bar title: "1 ride", "3 rides". */
internal fun rideCountLabel(count: Int): String = if (count == 1) "1 ride" else "$count rides"

/** A routing choice a saved ride was planned with; one metadata chip on its card. */
internal enum class SavedRouteFactor(val label: String) {
    AvoidMotorways("Avoid motorways"),
    BlockUnpaved("Block unpaved roads"),
    Hills(INCLUDE_ELEVATION_TITLE),
    AvoidStopsAndTowns(AVOID_STOPS_AND_TOWNS_TITLE),
}

/** The chips a saved [route] shows, in a fixed order; none when it was planned with no extras. */
internal fun savedRouteFactors(route: SavedRoute): List<SavedRouteFactor> = buildList {
    if (route.avoidMotorways) add(SavedRouteFactor.AvoidMotorways)
    if (route.blockUnpaved) add(SavedRouteFactor.BlockUnpaved)
    if (route.includeElevation) add(SavedRouteFactor.Hills)
    if (route.avoidStopsAndTowns) add(SavedRouteFactor.AvoidStopsAndTowns)
}

private fun SavedRouteFactor.iconRes(): Int = when (this) {
    SavedRouteFactor.AvoidMotorways -> R.drawable.ms_block
    SavedRouteFactor.BlockUnpaved -> R.drawable.ms_remove_road
    SavedRouteFactor.Hills -> R.drawable.ms_landscape
    SavedRouteFactor.AvoidStopsAndTowns -> R.drawable.ms_traffic
}

/**
 * The route-storage screen: a full-screen Material 3 page (top app bar with
 * the ride count, Back closes it) listing every saved ride as a card with its
 * mini-map banner, endpoint names, distance/time summary, planning-choice
 * chips and per-route comments.
 *
 * Data flows through suspend providers so this composable stays decoupled
 * from the repository instance; mutations call back into the providers to
 * refresh. Opening a card hands the stored [SavedRoute] to [onLoadRoute].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SavedRoutesSheet(
    summariesProvider: suspend () -> List<SavedRouteSummary>,
    commentsProvider: suspend (routeId: Long) -> List<SavedRouteComment>,
    onAddComment: suspend (routeId: Long, text: String) -> Unit,
    onLoadRoute: (SavedRoute) -> Unit,
    onDeleteRoute: suspend (SavedRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    var summaries by remember { mutableStateOf<List<SavedRouteSummary>?>(null) }
    var pendingDelete by remember { mutableStateOf<SavedRoute?>(null) }
    val scope = rememberCoroutineScope()
    // Serialized refresh: cancel-and-relaunch so an out-of-order query
    // completion can never overwrite newer state with a stale snapshot.
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    fun refreshSummaries() {
        refreshJob?.cancel()
        refreshJob = scope.launch { summaries = summariesProvider() }
    }

    LaunchedEffect(Unit) {
        refreshSummaries()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DialogStatusBarIcons()
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                topBar = {
                    TopAppBar(
                        title = { Text("Saved routes") },
                        navigationIcon = {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "Close saved routes" },
                            ) {
                                Icon(painterResource(R.drawable.ms_arrow_back), contentDescription = null)
                            }
                        },
                        actions = {
                            summaries?.let { list ->
                                Text(
                                    text = rideCountLabel(list.size),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 16.dp),
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
                },
            ) { padding ->
                when (val list = summaries) {
                    null -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        LoadingIndicator()
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp + padding.calculateStartPadding(LayoutDirection.Ltr),
                            top = 8.dp + padding.calculateTopPadding(),
                            end = 16.dp + padding.calculateEndPadding(LayoutDirection.Ltr),
                            bottom = 24.dp + padding.calculateBottomPadding(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (list.isEmpty()) {
                            item { EmptyState() }
                        }
                        items(list, key = { it.route.id }) { summary ->
                            SavedRouteItem(
                                summary = summary,
                                commentsProvider = commentsProvider,
                                onAddComment = { routeId, text ->
                                    onAddComment(routeId, text)
                                    refreshSummaries() // keep the comment count honest
                                },
                                onLoad = { onLoadRoute(summary.route) },
                                onDelete = { pendingDelete = summary.route },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { route ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(painterResource(R.drawable.ms_delete), contentDescription = null) },
            title = { Text("Delete saved route?") },
            text = {
                Text("\"${route.fromName} → ${route.toName}\" and its comments will be removed.")
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        pendingDelete = null
                        // Await the delete, then refresh: a fire-and-forget delete
                        // racing the refresh could leave the deleted row listed.
                        scope.launch {
                            try {
                                onDeleteRoute(route)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG_SAVED, "Deleting saved route failed", e)
                            } finally {
                                refreshSummaries()
                            }
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 64.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        ) {
            Icon(
                painter = painterResource(R.drawable.ms_bookmark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No saved routes yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Tap the bookmark on a route card to save it here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedRouteItem(
    summary: SavedRouteSummary,
    commentsProvider: suspend (routeId: Long) -> List<SavedRouteComment>,
    onAddComment: suspend (routeId: Long, text: String) -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    val route = summary.route
    var composing by remember { mutableStateOf(false) }
    var comments by remember(route.id) { mutableStateOf<List<SavedRouteComment>?>(null) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Comments show on the card, so a route with any loads them as it scrolls
    // into view; one without loads on opening the composer.
    LaunchedEffect(route.id, summary.commentCount, composing) {
        if (summary.commentCount > 0 || composing) {
            try {
                comments = commentsProvider(route.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG_SAVED, "Loading comments failed", e)
            }
        }
    }

    var submittingComment by remember { mutableStateOf(false) }
    fun submitDraft() {
        val text = draft.trim().take(MAX_COMMENT_CHARS)
        if (text.isEmpty() || submittingComment) return
        submittingComment = true
        scope.launch {
            try {
                onAddComment(route.id, text)
                // Clear only when the field still holds what was submitted;
                // text typed during the insert stays for the next send.
                if (draft.trim() == text) draft = ""
                comments = commentsProvider(route.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the draft so the note is not lost; the count only
                // changes after a confirmed insert.
            } finally {
                submittingComment = false
            }
        }
    }

    Card(
        onClick = onLoad,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Saved route: ${route.fromName} to ${route.toName}"
            },
    ) {
        Box {
            RouteMiniMap(
                geometry = route.geometry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MiniMapStyle.BANNER_HEIGHT_DP.dp),
            )
            Text(
                text = rideStyleLabel(route.complexity),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 12.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${route.fromName} → ${route.toName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatRouteDuration(route.durationMillis)} · ${formatRouteDistance(route.distanceMeters)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Delete saved route" },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ms_delete),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val factors = savedRouteFactors(route)
        if (factors.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                factors.forEach { FactorChip(it) }
            }
        }
        val loadedComments = comments.orEmpty()
        if (loadedComments.isEmpty()) {
            // A route that has comments says nothing until they arrive.
            if (summary.commentCount == 0 || comments != null) {
                Text(
                    text = "No comments yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
                )
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .padding(start = 16.dp, top = 12.dp, end = 16.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
                    .padding(12.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ms_chat_bubble),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    loadedComments.forEach { comment ->
                        Column {
                            Text(
                                text = comment.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = relativeCommentTime(comment.createdAtMillis),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = composing,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_COMMENT_CHARS) },
                    placeholder = { Text("Add a comment", style = MaterialTheme.typography.bodyMedium) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { submitDraft() },
                    enabled = draft.isNotBlank() && !submittingComment,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Add comment" },
                ) {
                    Icon(painterResource(R.drawable.ms_check), contentDescription = null)
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            TextButton(
                onClick = { composing = !composing },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = "Comments for saved route: ${route.fromName} to ${route.toName}"
                    },
            ) {
                Icon(
                    painter = painterResource(if (composing) R.drawable.ms_expand_more else R.drawable.ms_add_comment),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (composing) "Done" else "Add comment")
            }
            Button(onClick = onLoad, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ms_route),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Open")
            }
        }
    }
}

/** One planning choice the ride used: a static outlined label with a primary icon, not a control. */
@Composable
private fun FactorChip(factor: SavedRouteFactor) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(32.dp)
                .padding(start = 8.dp, end = 12.dp),
        ) {
            Icon(
                painter = painterResource(factor.iconRes()),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = factor.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}


/** How long ago a comment was written: "just now" under a minute, else the system's relative span. */
internal fun relativeCommentTime(createdAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String =
    if (nowMillis - createdAtMillis < DateUtils.MINUTE_IN_MILLIS) {
        "just now"
    } else {
        DateUtils.getRelativeTimeSpanString(createdAtMillis, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString()
    }
