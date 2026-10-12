package com.curvemaps.app.media

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.curvemaps.app.R
import com.curvemaps.app.ui.theme.CurveMapsDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

/**
 * Glove/riding ergonomics: every primary target is at least this size, so a
 * gloved thumb cannot miss it. The play/pause control is roomier still
 * ([PlayPauseTarget]) because it is the one control reached for mid-ride.
 */
internal val GloveTarget: Dp = 64.dp

/** The primary play/pause control, preferred larger than [GloveTarget]. */
internal val PlayPauseTarget: Dp = 80.dp

/** Visible separation between adjacent targets: no invisible overlapping zones. */
internal val GloveGap: Dp = 12.dp

/**
 * The panel's geometry as plain values, so the glove contract and the 320 dp
 * fit are pinned by `MediaPanelKeysTest` rather than by reading layout code.
 */
internal object MediaPanelLayout {
    /** Content inset on both sides. */
    val horizontalPadding: Dp = 16.dp

    /** The transport group's height: every transport key is this tall. */
    val transportHeight: Dp = 80.dp

    /** Gap between the connected transport keys (the group's 4 dp seam). */
    val transportGap: Dp = 4.dp

    /** Relative widths of Previous : play-or-pause : Next. */
    const val SkipWeight: Float = 1f
    const val PlayWeight: Float = 1.5f

    /** The round volume step buttons. */
    val volumeButton: Dp = 64.dp

    /** Space between a volume button and the level slider. */
    val volumeGap: Dp = 8.dp

    /** The close key's touch box; the 48 dp timeout ring is drawn inside it. */
    val closeTarget: Dp = 64.dp
    val timerRing: Dp = 48.dp

    /** Height of a music-app row or button. */
    val appRow: Dp = 64.dp

    /** Width of one Previous/Next key in the three-key group at [panelWidth]. */
    fun skipKeyWidth(panelWidth: Dp): Dp {
        val inner = panelWidth - horizontalPadding * 2 - transportGap * 2
        return inner * (SkipWeight / (SkipWeight * 2 + PlayWeight))
    }

    /** Width of the play-or-pause key in the three-key group. */
    fun playKeyWidth(panelWidth: Dp): Dp {
        val inner = panelWidth - horizontalPadding * 2 - transportGap * 2
        return inner * (PlayWeight / (SkipWeight * 2 + PlayWeight))
    }

    /** Width of one key in a two-key group (the unknown-state Pause|Play pair). */
    fun pairKeyWidth(panelWidth: Dp): Dp =
        (panelWidth - horizontalPadding * 2 - transportGap) / 2
}

/**
 * Separate media control center anchored above the ride data bar.
 *
 * It renders the selected player's track, transport, phone-or-session volume,
 * optional player selection, and a 15-second inactivity timer. Panel touch,
 * scroll, and control interactions restart it; media-session observations do
 * not. Every control follows [MediaCenterState] so an unsupported command is
 * honestly disabled rather than faked.
 *
 * When the playback state is genuinely unknown (no session, or a session that
 * reported no `PlaybackState`), the panel shows explicit Pause and Play
 * controls instead of a single toggle whose label would have to lie.
 *
 * [maxHeight], when provided, bounds the card (landscape ride layout) and the
 * content scrolls inside it, so the bar and END button are never covered.
 *
 * Material 3 on the dark scheme: a `surfaceContainer` slab with extra-large top
 * corners (the bar's, see [MediaPanelJoin]), an 80 dp connected transport group
 * (Previous and Next in `secondaryContainer`, the wider play-or-pause in
 * `primary`), 64 dp volume step buttons either side of a read-only slim-handle
 * slider that only ever shows the observed level, and an outlined Music apps
 * expander. Targets keep their glove sizes.
 */
@Composable
fun MediaControlCenter(
    state: MediaCenterState,
    onCommand: (MediaCommand) -> Unit,
    onVolume: (VolumeDirection) -> Unit,
    onSelectPlayer: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onStartMusicApp: (String) -> Unit = {},
    onOpenMusicApp: (String) -> Unit = {},
    maxHeight: Dp? = null,
    inactivityTimeoutMillis: Long = MediaPanelTimeoutMillis,
) {
    val remaining = remember { mutableFloatStateOf(1f) }
    val pointerGestureActive = remember { mutableStateOf(false) }
    val timer = remember(inactivityTimeoutMillis) {
        MediaPanelInactivityTimer(
            openedAtMillis = SystemClock.elapsedRealtime(),
            timeoutMillis = inactivityTimeoutMillis,
        )
    }
    val close = rememberUpdatedState(onClose)
    val onPanelInteraction: () -> Unit = remember(timer) {
        {
            timer.recordInteraction(SystemClock.elapsedRealtime())
            remaining.floatValue = 1f
        }
    }
    val latestPanelInteraction = rememberUpdatedState(onPanelInteraction)
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollState) {
        var wasScrolling = scrollState.isScrollInProgress
        snapshotFlow { scrollState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !wasScrolling) latestPanelInteraction.value()
            wasScrolling = scrolling
        }
    }
    LaunchedEffect(timer) {
        while (true) {
            delay(16)
            val now = SystemClock.elapsedRealtime()
            remaining.floatValue = timer.remaining(now)
            if (timer.isExpired(now) && !pointerGestureActive.value) {
                close.value()
                return@LaunchedEffect
            }
        }
    }

    CurveMapsDarkTheme {
        Box(
            modifier = modifier
                .fillMaxWidth()
                // Inset exactly like the floating bar, beside any side navigation
                // bar as it is: any other inset shows as a step at the join and
                // breaks the single-silhouette read.
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                .padding(
                    start = MediaPanelJoin.leadingInset,
                    end = MediaPanelJoin.trailingInset,
                ),
            contentAlignment = Alignment.TopStart,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                // Rounded at the top like the bar, square where it fuses into the
                // bar's edge so there is no notch of map between them.
                shape = RoundedCornerShape(
                    topStart = MediaPanelJoin.panelTopCorner,
                    topEnd = MediaPanelJoin.panelTopCorner,
                ),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier,
                    )
                    .pointerInput(latestPanelInteraction) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.any { it.pressed }) {
                                    latestPanelInteraction.value()
                                    pointerGestureActive.value = true
                                } else if (pointerGestureActive.value) {
                                    latestPanelInteraction.value()
                                    pointerGestureActive.value = false
                                }
                            }
                        }
                    }
                    .semantics { contentDescription = "Media control center" },
            ) {
                Column(
                    Modifier
                        .scrollFades(scrollState, MaterialTheme.colorScheme.surfaceContainer)
                        .verticalScroll(scrollState)
                        .padding(
                            start = MediaPanelLayout.horizontalPadding,
                            end = MediaPanelLayout.horizontalPadding,
                            top = 20.dp,
                            // The panel reaches fuseDepth into the bar; keep the
                            // design's 16 dp of visible space below the last control.
                            bottom = 16.dp + MediaPanelJoin.fuseDepth,
                        ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Header(
                        state = state,
                        onClose = onClose,
                        remaining = { remaining.floatValue },
                    )
                    TransportControls(
                        state = state,
                        onCommand = { command ->
                            onPanelInteraction()
                            onCommand(command)
                        },
                    )
                    VolumeRow(
                        volume = state.volume,
                        onVolume = { direction ->
                            onPanelInteraction()
                            onVolume(direction)
                        },
                    )
                    if (state.canSelectPlayer) {
                        PlayerSelector(
                            state = state,
                            onSelectPlayer = { key ->
                                onPanelInteraction()
                                onSelectPlayer(key)
                            },
                            onPanelInteraction = onPanelInteraction,
                        )
                    }
                    if (state.musicApps.isNotEmpty()) {
                        MusicAppControls(
                            state = state,
                            onStartMusicApp = onStartMusicApp,
                            onOpenMusicApp = onOpenMusicApp,
                            onPanelInteraction = onPanelInteraction,
                        )
                    }
                    state.musicAppMessage?.let { StatusNote(it) }
                    StatusFooter(state = state)
                }
            }
        }
    }
}

@Composable
private fun Header(
    state: MediaCenterState,
    onClose: () -> Unit,
    remaining: () -> Float,
) {
    val session = state.selected
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Album tile: the art isn't read (no notification content), so the tile
        // is the design's tonal placeholder.
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.shapes.large,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ms_album_fill),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = headerOverline(state),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = session?.trackTitle ?: defaultTitle(state),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = session?.trackSubtitle ?: defaultSubtitle(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CloseWithTimer(onClose = onClose, remaining = remaining)
    }
}

/** "<app> · <state>" once the player has reported its playback state. */
internal fun headerOverline(state: MediaCenterState): String {
    val session = state.selected
    return when {
        session != null && state.transportStateKnown ->
            "${session.displayName} · ${if (state.isPlaying) "Playing" else "Paused"}"
        session != null -> session.displayName
        state.status == MediaCenterStatus.PERMISSION_NEEDED -> "Media keys"
        else -> "No player"
    }
}

/**
 * Close, with the inactivity timeout drawn as a ring around the glyph. The ring
 * is its own accessible progress node (the countdown); the 64 dp button is the
 * glove-sized target on top of it.
 */
@Composable
private fun CloseWithTimer(onClose: () -> Unit, remaining: () -> Float) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val progress = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier.size(MediaPanelLayout.closeTarget),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(MediaPanelLayout.timerRing)
                .semantics {
                    contentDescription = "Media controls inactivity timer"
                    progressBarRangeInfo = ProgressBarRangeInfo(remaining(), 0f..1f)
                },
        ) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke),
            )
            drawArc(
                progress,
                startAngle = -90f,
                sweepAngle = 360f * remaining(),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(MediaPanelLayout.closeTarget)
                .semantics { contentDescription = "Close media controls" },
        ) {
            Icon(painterResource(R.drawable.ms_close), contentDescription = null)
        }
    }
}

private fun defaultTitle(state: MediaCenterState): String = when (state.status) {
    MediaCenterStatus.PERMISSION_NEEDED -> "Limited media controls"
    MediaCenterStatus.LOADING -> "Checking players"
    else -> "Nothing playing"
}

private fun defaultSubtitle(state: MediaCenterState): String = when (state.status) {
    MediaCenterStatus.PERMISSION_NEEDED -> "Phone volume and system media keys"
    MediaCenterStatus.LOADING -> "Reading active media sessions"
    else -> "Start playback in a media app"
}

/**
 * Transport: one connected 80 dp group. Previous and Next are `secondaryContainer`
 * keys with a pill outer end and a 16 dp inner end; play-or-pause is the wider
 * `primary` key (24 dp corners) between them. With an unknown playback state it
 * offers both directions as separate explicit keys (never a toggle that would
 * silently invert), with the skips as their own group below so nothing shrinks
 * below [GloveTarget] on a 320 dp screen at 2x font scale.
 */
@Composable
private fun TransportControls(
    state: MediaCenterState,
    onCommand: (MediaCommand) -> Unit,
) {
    if (state.transportStateKnown) {
        val playing = state.isPlaying
        val playOrPause = if (playing) MediaCommand.PAUSE else MediaCommand.PLAY
        Row(
            modifier = Modifier.fillMaxWidth().height(MediaPanelLayout.transportHeight),
            horizontalArrangement = Arrangement.spacedBy(MediaPanelLayout.transportGap),
        ) {
            SkipKey(
                MediaCommand.PREVIOUS, "Previous track", state, onCommand,
                shape = LeadingKeyShape,
                modifier = Modifier.weight(MediaPanelLayout.SkipWeight),
            )
            PlayPauseKey(
                description = if (playing) "Pause" else "Play",
                playing = playing,
                enabled = state.canIssue(playOrPause),
                shape = RoundedCornerShape(24.dp),
                onClick = { onCommand(playOrPause) },
                modifier = Modifier.weight(MediaPanelLayout.PlayWeight),
            )
            SkipKey(
                MediaCommand.NEXT, "Next track", state, onCommand,
                shape = TrailingKeyShape,
                modifier = Modifier.weight(MediaPanelLayout.SkipWeight),
            )
        }
        return
    }

    // Unknown playback state: explicit actions, each honest about what it sends.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MediaPanelLayout.transportGap)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(MediaPanelLayout.transportHeight),
            horizontalArrangement = Arrangement.spacedBy(MediaPanelLayout.transportGap),
        ) {
            PlayPauseKey(
                description = "Pause",
                playing = true,
                enabled = state.canIssue(MediaCommand.PAUSE),
                shape = LeadingKeyShape,
                onClick = { onCommand(MediaCommand.PAUSE) },
                modifier = Modifier.weight(1f),
                // Nothing is known to be playing: Play is the action to reach
                // for, so it alone is primary and Pause is tonal like Previous/Next.
                primary = false,
            )
            PlayPauseKey(
                description = "Play",
                playing = false,
                enabled = state.canIssue(MediaCommand.PLAY),
                shape = TrailingKeyShape,
                onClick = { onCommand(MediaCommand.PLAY) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(MediaPanelLayout.transportHeight),
            horizontalArrangement = Arrangement.spacedBy(MediaPanelLayout.transportGap),
        ) {
            SkipKey(
                MediaCommand.PREVIOUS, "Previous track", state, onCommand,
                shape = LeadingKeyShape, modifier = Modifier.weight(1f),
            )
            SkipKey(
                MediaCommand.NEXT, "Next track", state, onCommand,
                shape = TrailingKeyShape, modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Left key of a connected group: pill outer end, 16 dp inner end. */
private val LeadingKeyShape: Shape = RoundedCornerShape(
    topStart = CornerSize(50), topEnd = CornerSize(16.dp),
    bottomEnd = CornerSize(16.dp), bottomStart = CornerSize(50),
)

/** Right key of a connected group: 16 dp inner end, pill outer end. */
private val TrailingKeyShape: Shape = RoundedCornerShape(
    topStart = CornerSize(16.dp), topEnd = CornerSize(50),
    bottomEnd = CornerSize(50), bottomStart = CornerSize(16.dp),
)

private val KeyContentPadding = PaddingValues(0.dp)

@Composable
private fun SkipKey(
    command: MediaCommand,
    description: String,
    state: MediaCenterState,
    onCommand: (MediaCommand) -> Unit,
    shape: Shape,
    modifier: Modifier,
) {
    Button(
        onClick = { onCommand(command) },
        enabled = state.canIssue(command),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        contentPadding = KeyContentPadding,
        modifier = modifier
            .fillMaxHeight()
            .semantics { contentDescription = description },
    ) {
        Icon(
            painterResource(
                if (command == MediaCommand.NEXT) R.drawable.ms_skip_next_fill
                else R.drawable.ms_skip_previous_fill,
            ),
            contentDescription = null,
            modifier = Modifier.size(36.dp),
        )
    }
}

/**
 * The play-or-pause key: `primary`, or `secondaryContainer` when it is the
 * lesser of an explicit Pause | Play pair; disabled when its command is
 * unavailable.
 */
@Composable
private fun PlayPauseKey(
    description: String,
    playing: Boolean,
    enabled: Boolean,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier,
    primary: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) colors.primary else colors.secondaryContainer,
            contentColor = if (primary) colors.onPrimary else colors.onSecondaryContainer,
        ),
        contentPadding = KeyContentPadding,
        modifier = modifier
            .fillMaxHeight()
            .semantics { contentDescription = description },
    ) {
        Icon(
            painterResource(if (playing) R.drawable.ms_pause_fill else R.drawable.ms_play_arrow_fill),
            contentDescription = null,
            modifier = Modifier.size(44.dp),
        )
    }
}

/**
 * Volume: a caption line (player, readback), then 64 dp round step buttons
 * either side of a slim-handle slider. The slider is a read-only readout of the
 * observed level (one command per tap, no drag): stepping is the buttons' job.
 */
@Composable
private fun VolumeRow(
    volume: VolumeState,
    onVolume: (VolumeDirection) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        ) {
            Text(
                text = volume.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (volume.fixed) {
                    "Fixed volume"
                } else if (!volume.readable) {
                    "Adjustable"
                } else {
                    "${volume.current} / ${volume.max}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MediaPanelLayout.volumeGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            VolumeStepButton(VolumeDirection.DOWN, volume.label, volume.canLower, onVolume)
            VolumeLevel(volume, Modifier.weight(1f))
            VolumeStepButton(VolumeDirection.UP, volume.label, volume.canRaise, onVolume)
        }
    }
}

@Composable
private fun VolumeStepButton(
    direction: VolumeDirection,
    label: String,
    enabled: Boolean,
    onVolume: (VolumeDirection) -> Unit,
) {
    val up = direction == VolumeDirection.UP
    IconButton(
        onClick = { onVolume(direction) },
        enabled = enabled,
        shape = CircleShape,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .size(MediaPanelLayout.volumeButton)
            .semantics { contentDescription = "Volume ${if (up) "up" else "down"}, $label" },
    ) {
        Icon(
            painterResource(if (up) R.drawable.ms_volume_up else R.drawable.ms_volume_down),
            contentDescription = null,
        )
    }
}

/**
 * The slim-handle slider as a readout. It cannot be dragged (enabled = false);
 * its disabled colours are set to the live ones so it reads as a level, and it
 * dims only when the level is not observable. Its steps are the stream's steps.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VolumeLevel(volume: VolumeState, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val alpha = if (volume.readable) 1f else 0.38f
    val colors = SliderDefaults.colors(
        disabledThumbColor = scheme.primary.copy(alpha = alpha),
        disabledActiveTrackColor = scheme.primary.copy(alpha = alpha),
        disabledInactiveTrackColor = scheme.secondaryContainer.copy(alpha = alpha),
        disabledActiveTickColor = androidx.compose.ui.graphics.Color.Transparent,
        disabledInactiveTickColor = androidx.compose.ui.graphics.Color.Transparent,
    )
    val interaction = remember { MutableInteractionSource() }
    val steps = volume.steps
    val range = 0f..steps.coerceAtLeast(1).toFloat()
    val description = "${volume.label} volume level"
    val state = if (volume.fixed) "Fixed volume"
    else if (!volume.readable) "Adjustable, level unknown"
    else "${volume.current} of ${volume.max}"
    Box(
        modifier = modifier
            .height(44.dp)
            .clearAndSetSemantics {
                contentDescription = description
                stateDescription = state
                if (volume.readable && steps > 0) {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        volume.position.toFloat().coerceIn(range),
                        range,
                        (steps - 1).coerceAtLeast(0),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Slider(
            value = volume.position.toFloat().coerceIn(range),
            onValueChange = {},
            enabled = false,
            valueRange = range,
            steps = (steps - 1).coerceAtLeast(0),
            colors = colors,
            interactionSource = interaction,
            thumb = { sliderState ->
                SliderDefaults.Thumb(
                    interactionSource = interaction,
                    sliderState = sliderState,
                    colors = colors,
                    enabled = false,
                    thumbSize = DpSize(4.dp, 44.dp),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    colors = colors,
                    enabled = false,
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                )
            },
        )
    }
}

/**
 * The installed players. Each chip is [GloveTarget] tall; the chosen one is a
 * selected `FilterChip` (`secondaryContainer`, check mark) and exposes
 * `selected` for accessibility.
 */
@Composable
private fun PlayerSelector(
    state: MediaCenterState,
    onSelectPlayer: (String) -> Unit,
    onPanelInteraction: () -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollState, onPanelInteraction) {
        var wasScrolling = scrollState.isScrollInProgress
        snapshotFlow { scrollState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !wasScrolling) onPanelInteraction()
            wasScrolling = scrolling
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.sessions.forEach { session ->
            val selected = session.key == state.selectedKey
            FilterChip(
                selected = selected,
                onClick = { onSelectPlayer(session.key) },
                label = {
                    Text(
                        text = session.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                },
                leadingIcon = if (selected) {
                    {
                        Icon(
                            painterResource(R.drawable.ms_check),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    null
                },
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .padding(end = GloveGap)
                    .height(GloveTarget)
                    .semantics {
                        contentDescription = "Select player ${session.displayName}"
                        this.selected = selected
                    },
            )
        }
        if (state.sessions.isEmpty()) {
            Text(
                "No players",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A short status/notice line in a tonal container with an info glyph. */
@Composable
internal fun StatusNote(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                painterResource(R.drawable.ms_info),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The fallback explanations (system media keys, unknown playback state). The
 * player's Playing/Paused state lives in the header's "<app> · <state>" line.
 */
@Composable
private fun StatusFooter(state: MediaCenterState) {
    if (state.status == MediaCenterStatus.LOADING) return
    if (!state.usingFallback) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusNote("Using system media keys (limited control)")
        if (!state.transportStateKnown) {
            StatusNote("Playback state unknown — Play and Pause are explicit")
        }
    }
}

/** How tall the scroll-edge fades are. */
private val ScrollFadeHeight: Dp = 40.dp

/**
 * Fades the scrolling content into [container] at whichever edge has more to
 * scroll to, so a list cut by the panel's bound shows that it scrolls instead
 * of ending in a hard slice. The fade follows the scroll state, so it is gone
 * once the end is reached (where the content's own padding gives the space).
 */
private fun Modifier.scrollFades(state: androidx.compose.foundation.ScrollState, container: Color): Modifier =
    this.drawWithContent {
        drawContent()
        val fade = ScrollFadeHeight.toPx().coerceAtMost(size.height / 2f)
        if (state.canScrollForward) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, container),
                    startY = size.height - fade,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - fade),
                size = Size(size.width, fade),
            )
        }
        if (state.canScrollBackward) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(container, Color.Transparent),
                    startY = 0f,
                    endY = fade,
                ),
                topLeft = Offset.Zero,
                size = Size(size.width, fade),
            )
        }
    }
