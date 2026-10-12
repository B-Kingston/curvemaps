package com.curvemaps.app.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.curvemaps.app.R

/**
 * Keep installed-player controls out of the ride panel until explicitly opened.
 * The "Music apps" expander is an outlined, full-width pill row; opened, the
 * app list is M3 [ListItem]s in a tonal container and Start/Open are a filled
 * pill and a tonal pill. Every control is at least [GloveTarget] tall.
 */
@Composable
internal fun MusicAppControls(
    state: MediaCenterState,
    onStartMusicApp: (String) -> Unit,
    onOpenMusicApp: (String) -> Unit,
    onPanelInteraction: () -> Unit,
) {
    val expanded = remember { mutableStateOf(false) }
    val choosing = remember { mutableStateOf(false) }
    val selectedPackage = remember { mutableStateOf<String?>(null) }
    val app = selectMusicApp(state.musicApps, selectedPackage.value, state.selected?.packageName) ?: return
    val enabled = state.startingMusicApp == null
    Column(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                onPanelInteraction()
                expanded.value = !expanded.value
                choosing.value = false
            },
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = MediaPanelLayout.appRow).semantics {
                contentDescription = if (expanded.value) "Hide music apps" else "Show music apps"
                stateDescription = if (expanded.value) "Expanded" else "Collapsed"
            },
        ) {
            Icon(
                painterResource(R.drawable.ms_library_music),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (expanded.value) "Hide music apps" else "Music apps",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            Icon(
                painterResource(
                    if (expanded.value) R.drawable.ms_expand_less else R.drawable.ms_expand_more,
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!expanded.value) return

        if (state.musicApps.size > 1) {
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    AppListItem(
                        title = "${app.name} · Change",
                        description = "Change music app",
                        state = app.name,
                        enabled = enabled,
                        trailing = if (choosing.value) R.drawable.ms_expand_less else R.drawable.ms_expand_more,
                        onClick = { onPanelInteraction(); choosing.value = !choosing.value },
                    )
                    if (choosing.value) {
                        state.musicApps.forEach { choice ->
                            val isSelected = choice.packageName == app.packageName
                            AppListItem(
                                title = choice.name,
                                description = "Choose music app ${choice.name}",
                                state = if (isSelected) "Selected" else "Not selected",
                                enabled = enabled,
                                trailing = if (isSelected) R.drawable.ms_check else null,
                                onClick = {
                                    onPanelInteraction()
                                    selectedPackage.value = choice.packageName
                                    choosing.value = false
                                },
                            )
                        }
                    }
                }
            }
        }
        if (choosing.value) return

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GloveGap)) {
            Button(
                onClick = { onPanelInteraction(); onStartMusicApp(app.packageName) },
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = GloveTarget)
                    .semantics { contentDescription = "Start music in ${app.name}" },
            ) {
                Text("Start ${app.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalButton(
                onClick = { onPanelInteraction(); onOpenMusicApp(app.packageName) },
                enabled = enabled,
                modifier = Modifier.widthIn(min = GloveTarget).heightIn(min = GloveTarget)
                    .semantics { contentDescription = "Open music app ${app.name}" },
            ) { Text("Open") }
        }
    }
}

/** One tappable row of the music-app list: an M3 [ListItem], glove-tall. */
@Composable
private fun AppListItem(
    title: String,
    description: String,
    state: String,
    enabled: Boolean,
    trailing: Int?,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = trailing?.let {
            { Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(24.dp)) }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSurface,
            trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier
            .heightIn(min = GloveTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                stateDescription = state
            },
    )
}
