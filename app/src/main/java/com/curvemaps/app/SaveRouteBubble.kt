package com.curvemaps.app

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.curvemaps.app.ui.theme.CurveMapsTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Distance from the anchor card's top edge up to the bubble's bottom edge. */
private val BUBBLE_LIFT = 58.dp

/**
 * How long the "Saved" confirmation lingers before the bubble dismisses
 * itself. Internal (not private) because SaveBubbleTest advances the compose
 * clock past exactly this value — change it in one place and both stay honest.
 */
internal const val CONFIRM_LINGER_MS = 900L

/** Bubble lifecycle: prompt → saving → saved (auto-dismiss) or failed (retry). */
private enum class SaveState { PROMPT, SAVING, SAVED, FAILED }

/**
 * The save element for a route card: a floating M3 surface anchored just
 * above the card, showing the route metrics and a bookmark action.
 *
 * The bookmark reflects the REAL save outcome: [onSave] is suspending and
 * returns whether the row was persisted. Only a `true` result flips the bubble
 * to the "Saved" confirmation (success colours); a failure keeps the bubble
 * open with an error message so the user can retry instead of believing a save
 * happened that silently stored nothing. The action is disabled while a save
 * is in flight, which also prevents double-tap duplicate rows. Long-press
 * opens the bubble ([visible]); tapping anywhere outside dismisses
 * immediately.
 *
 * With [autoSave] the bubble opens already saving (the route card's bookmark
 * button is one tap): it runs the same save and shows the same Saved / retry
 * states.
 *
 * Stateless by design: the caller owns [visible] so recomposition of the
 * surrounding card never resurrects a dismissed bubble.
 */
@Composable
fun SaveRouteBubble(
    visible: Boolean,
    metrics: String,
    onSave: suspend () -> Boolean,
    onDismiss: () -> Unit,
    autoSave: Boolean = false,
) {
    if (!visible) return
    var state by remember { mutableStateOf(if (autoSave) SaveState.SAVING else SaveState.PROMPT) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(visible) {
        if (visible && autoSave) {
            state = SaveState.SAVING
            state = if (onSave()) SaveState.SAVED else SaveState.FAILED
        } else if (visible) {
            state = SaveState.PROMPT
        }
    }
    LaunchedEffect(state) {
        if (state == SaveState.SAVED) {
            delay(CONFIRM_LINGER_MS)
            onDismiss()
        }
    }

    val liftPx = with(LocalDensity.current) { BUBBLE_LIFT.roundToPx() }
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, -liftPx),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Crossfade(
                targetState = state,
                label = "saveRouteBubble",
            ) { current ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    when (current) {
                        SaveState.SAVED -> {
                            Icon(
                                painter = painterResource(R.drawable.ms_check_circle_fill),
                                contentDescription = null,
                                tint = CurveMapsTheme.colors.success,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Saved",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = CurveMapsTheme.colors.success,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        else -> {
                            Column {
                                Text(
                                    text = when (current) {
                                        SaveState.FAILED -> "Couldn't save this route"
                                        else -> "Save this route"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (current == SaveState.FAILED) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Text(
                                    text = metrics,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            SaveBookmarkButton(
                                enabled = current != SaveState.SAVING,
                                onClick = {
                                    state = SaveState.SAVING
                                    scope.launch {
                                        state = if (onSave()) SaveState.SAVED else SaveState.FAILED
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A 48 dp tonal icon button holding the bookmark symbol; a spinner while saving. */
@Composable
private fun SaveBookmarkButton(enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "Save route" },
    ) {
        if (enabled) {
            Icon(painter = painterResource(R.drawable.ms_bookmark_add), contentDescription = null)
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
