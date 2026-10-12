package com.curvemaps.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.curvemaps.app.routing.navigation.VoiceDistanceUnit
import com.curvemaps.app.routing.navigation.VoiceGuidanceSettings
import com.curvemaps.app.ui.theme.CurveMapsTheme

/** Radius of the Voice guidance cards (matches the grouped rows' outer corners). */
private val CardRadius = 20.dp

@Composable
internal fun VoiceGuidanceSettingsDialog(
    current: VoiceGuidanceSettings,
    unit: VoiceDistanceUnit,
    speechStatus: OfflineSpeechStatus,
    onDismiss: () -> Unit,
    onApply: (VoiceGuidanceSettings) -> Unit,
) {
    var draftEnabled by remember(current.enabled) { mutableStateOf(current.enabled) }
    var draftInterval by remember(current.intervalMeters, unit) {
        mutableStateOf(
            unit.fromMetres(current.intervalMeters)
                .coerceIn(unit.minimumInterval, unit.maximumInterval).toFloat(),
        )
    }
    var draftPreviewCount by remember(current.previewCount) {
        mutableStateOf(current.previewCount.coerceIn(1, 5).toFloat())
    }
    val intervalSteps = (unit.maximumInterval - unit.minimumInterval) / unit.intervalStep - 1
    val intervalLabel = unit.intervalLabel(unit.toMetres(draftInterval.toInt()))
    val previewCount = draftPreviewCount.toInt().coerceIn(1, 5)

    SettingsPageDialog(
        title = "Voice guidance",
        onDismiss = onDismiss,
        onApply = {
            onApply(
                VoiceGuidanceSettings(
                    enabled = draftEnabled,
                    intervalMeters = unit.toMetres(draftInterval.toInt()),
                    previewCount = previewCount,
                ),
            )
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        ) {
            SpokenGuidanceCard(enabled = draftEnabled, onEnabledChange = { draftEnabled = it })
            VoiceStatusRow(speechStatus)
            SliderCard(
                title = "Countdown",
                value = "every $intervalLabel",
                detail = "The next-turn countdown starts within ten intervals. Each prompt names the road.",
                sliderDescription = "Announcement interval every $intervalLabel",
            ) {
                Slider(
                    value = draftInterval,
                    onValueChange = { draftInterval = it },
                    valueRange = unit.minimumInterval.toFloat()..unit.maximumInterval.toFloat(),
                    steps = intervalSteps,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SliderCard(
                title = "Preview",
                value = "next $previewCount ${if (previewCount == 1) "turn" else "turns"}",
                detail = "The preview is repeated when the next maneuver changes.",
                sliderDescription = "Preview next $previewCount maneuvers",
            ) {
                Slider(
                    value = draftPreviewCount,
                    onValueChange = { draftPreviewCount = it },
                    valueRange = 1f..5f,
                    steps = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                ) {
                    Text("1", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("5", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * The main switch as a big `secondaryContainer` card. The card is one Switch
 * toggle described "Spoken turn guidance", at least 48 dp tall.
 */
@Composable
private fun SpokenGuidanceCard(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange)
                .semantics { contentDescription = "Spoken turn guidance" }
                .padding(start = 24.dp, end = 20.dp, top = 20.dp, bottom = 20.dp),
        ) {
            Text(
                "Spoken turn guidance",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(end = 16.dp),
            )
            SettingsSwitch(enabled)
        }
    }
}

/** The offline-voice readiness row: a status disc (success / error / neutral), the status and the usage note. */
@Composable
private fun VoiceStatusRow(status: OfflineSpeechStatus) {
    val container: Color
    val content: Color
    when (status) {
        is OfflineSpeechStatus.Ready -> {
            container = CurveMapsTheme.colors.successContainer
            content = CurveMapsTheme.colors.onSuccessContainer
        }
        is OfflineSpeechStatus.Unavailable -> {
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
        else -> {
            container = MaterialTheme.colorScheme.surfaceContainerHighest
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    SettingsRow(
        headline = { Text(status.description()) },
        supporting = {
            Text(
                (status as? OfflineSpeechStatus.Ready)?.let { "Voice: ${it.voiceName}. " }.orEmpty() +
                    "Uses an installed English offline speech voice while riding.",
            )
        },
        leading = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).background(container, CircleShape),
            ) {
                Icon(painterResource(R.drawable.ms_record_voice_over_fill), contentDescription = null, tint = content)
            }
        },
        modifier = Modifier.clip(RoundedCornerShape(CardRadius)),
    )
}

/** A Countdown / Preview card: title with the live value, a detail line, then the slider. */
@Composable
private fun SliderCard(
    title: String,
    value: String,
    detail: String,
    sliderDescription: String,
    slider: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(CardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Text(
                    value,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .requiredHeightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) { contentDescription = sliderDescription },
            ) { slider() }
        }
    }
}

private fun OfflineSpeechStatus.description(): String = when (this) {
    OfflineSpeechStatus.NotStarted -> "Voice availability has not been checked yet."
    OfflineSpeechStatus.Checking -> "Checking for an installed English offline voice…"
    is OfflineSpeechStatus.Ready -> "English offline voice ready: $voiceName"
    is OfflineSpeechStatus.Unavailable -> "Unavailable: $reason"
}
