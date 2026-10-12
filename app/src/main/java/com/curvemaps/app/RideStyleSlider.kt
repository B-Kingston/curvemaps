package com.curvemaps.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import kotlin.coroutines.coroutineContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.curvemaps.app.routing.RideStyle
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * M3's stationary wave with a slim pill handle and seven magnetic, haptic
 * stops. The wave grows out stop by stop as [readyStops] rises while a search
 * finishes each ride style; the handle can move anywhere meanwhile.
 */

@Composable
internal fun RideStyleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    readyStops: Int = RideStyle.entries.size,
) {
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    val latestFinished by rememberUpdatedState(onValueChangeFinished)
    val maxLevel = RideStyle.MAX_LEVEL.toFloat()
    val level = RideStyleDetents.bounded(value).roundToInt()
    val view = LocalView.current
    val haptics = remember(view) { RideStyleHaptics(view) }
    DisposableEffect(haptics) { onDispose { haptics.stop() } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    val settled = remember { Animatable(level.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    // Wave phase in wavelengths: drifts slowly while dragging, then settles on a
    // whole wavelength so every crest lands back on its detent.
    val wavePhase = remember { Animatable(0f) }
    var dragPosition by remember { mutableFloatStateOf(level.toFloat()) }
    LaunchedEffect(dragging) {
        if (dragging) {
            // Speed follows the level under the finger: Max twisty waves fastest.
            // Infinite-animation frames, so a test clock (which pauses them)
            // can still reach idle while a finger is held on the handle.
            var last = infiniteFrameNanos { it }
            while (true) {
                val now = infiniteFrameNanos { it }
                val periodMs = RideStyleSliderGeometry.wavePeriodMillis(dragPosition)
                wavePhase.snapTo(wavePhase.value + (now - last) / 1_000_000f / periodMs)
                last = now
            }
        } else {
            wavePhase.animateTo(kotlin.math.round(wavePhase.value), spring())
            wavePhase.snapTo(0f)
        }
    }
    // The wave grows out one hump per ride style whose search has finished
    // ([readyStops], Fastest first); a finished plan grows out in one sweep.
    val grown = remember { Animatable(0f) }
    LaunchedEffect(readyStops) {
        val target = readyStops.coerceIn(0, RideStyleSliderGeometry.humpCount).toFloat()
        val millis = maxOf(
            RideStyleSliderGeometry.MIN_GROWTH_MILLIS,
            (abs(target - grown.value) * RideStyleSliderGeometry.GROWTH_MILLIS_PER_STOP).roundToInt(),
        )
        grown.animateTo(target, tween(millis, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(level, dragging) {
        if (!dragging) settled.animateTo(level.toFloat(), spring(dampingRatio = 0.8f, stiffness = 600f))
    }
    fun select(target: Float): Boolean {
        if (!target.isFinite()) return false
        val stop = RideStyleDetents.bounded(target).roundToInt().toFloat()
        if (stop == RideStyleDetents.bounded(latestValue).roundToInt().toFloat()) return false
        haptics.click()
        latestChange(stop)
        latestFinished(stop)
        return true
    }
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier.fillMaxWidth().sizeIn(minWidth = 48.dp).height(64.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Ride style level $level"
                stateDescription = RideStyle.fromLevel(level.toDouble()).label
                progressBarRangeInfo = ProgressBarRangeInfo(level.toFloat(), 0f..maxLevel, RideStyle.MAX_LEVEL - 1)
                setProgress { select(it) }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionRight -> select(level + if (rtl) -1f else 1f)
                    Key.DirectionLeft -> select(level + if (rtl) 1f else -1f)
                    Key.MoveHome -> select(0f)
                    Key.MoveEnd -> select(maxLevel)
                    else -> false
                }
            }
            .focusable()
            .pointerInput(rtl) {
                fun position(x: Float): Float {
                    val inset = RIDE_STYLE_SLIDER_END_INSET.toPx()
                    val fraction = ((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    return RideStyleSliderGeometry.levelAtFraction(if (rtl) 1f - fraction else fraction)
                }
                var initial = 0f
                var raw = 0f
                var detents = RideStyleDetents(0f)
                fun move(x: Float) {
                    raw = position(x)
                    val feedback = detents.move(raw)
                    feedback.clickedLevels.forEach { haptics.click() }
                    if (feedback.approach > 0) haptics.approach(feedback.approach)
                    dragPosition = detents.paintedPosition(raw)
                    latestChange(raw.roundToInt().toFloat())
                }
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        initial = RideStyleDetents.bounded(latestValue).roundToInt().toFloat()
                        detents = RideStyleDetents(initial)
                        dragging = true
                        scope.launch { settled.stop() }
                        move(offset.x)
                    },
                    onHorizontalDrag = { change, _ -> move(change.position.x); change.consume() },
                    onDragEnd = {
                        val snapped = raw.roundToInt().toFloat()
                        // A release between stops also announces the final landing.
                        if (detents.needsReleaseClick(raw)) haptics.click() else haptics.stop()
                        latestChange(snapped)
                        scope.launch { settled.snapTo(dragPosition); dragging = false }
                        if (snapped != initial) latestFinished(snapped)
                    },
                    onDragCancel = {
                        haptics.stop()
                        latestChange(initial)
                        dragging = false
                    },
                )
            }
            .pointerInput(rtl) {
                detectTapGestures { offset ->
                    val inset = RIDE_STYLE_SLIDER_END_INSET.toPx()
                    val fraction = ((offset.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    select(RideStyleSliderGeometry.levelAtFraction(if (rtl) 1f - fraction else fraction))
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(64.dp).clearAndSetSemantics {}) {
            val inset = RIDE_STYLE_SLIDER_END_INSET.toPx()
            val span = (size.width - 2 * inset).coerceAtLeast(1f)
            fun x(stop: Float): Float {
                val fraction = RideStyleSliderGeometry.fractionAtLevel(stop)
                return inset + span * (if (rtl) 1f - fraction else fraction)
            }
            val cy = size.height / 2f
            // One wave swinging about the centre line, growing toward Max so the
            // track reads as "more twisty". It starts on the Fastest crest and
            // ends on the Max twisty crest, so the track, the dots and the
            // handle's two ends are the same two points. A continuous height
            // envelope lets the wave travel while dragging without seams.
            val stroke = 4.dp.toPx()
            val maxHump = 16.dp.toPx()
            val baseline = cy
            val wave = Path()
            val samples = 160
            val phase = wavePhase.value
            val front = grown.value
            fun risen(stop: Float) = RideStyleSliderGeometry.growthHeight(front, stop + 0.5f).coerceIn(0f, 1f)
            fun trackX(fraction: Float) = inset + span * (if (rtl) 1f - fraction else fraction)
            for (i in 0..samples) {
                val fraction = i.toFloat() / samples
                val u = RideStyleSliderGeometry.positionAtFraction(fraction)
                val y = baseline - maxHump * RideStyleSliderGeometry.waveHeight(u, phase) *
                    RideStyleSliderGeometry.growthHeight(front, u)
                val px = trackX(fraction)
                if (i == 0) wave.moveTo(px, y) else wave.lineTo(px, y)
            }
            // Styles still being searched are a flat secondaryContainer line of
            // the wave's own weight; the grown wave is drawn over the rest in
            // the primary colour, up to the front, so a finished track is one
            // wave end to end whichever stop the handle is on.
            val waveStroke = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val frontFraction = RideStyleSliderGeometry.fractionAtGrowthFront(front)
            if (frontFraction < 1f) {
                val trackGap = TRACK_GAP_DP.dp.toPx()
                val frontX = trackX(frontFraction)
                val trackStart = if (rtl) inset else if (frontFraction > 0f) frontX + trackGap else inset
                val trackEnd = if (rtl) (if (frontFraction > 0f) frontX - trackGap else inset + span) else inset + span
                if (trackEnd > trackStart) {
                    drawLine(
                        colors.secondaryContainer,
                        Offset(trackStart, cy), Offset(trackEnd, cy),
                        strokeWidth = TRACK_THICKNESS_DP.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
            }
            if (frontFraction > 0f) {
                val frontX = trackX(frontFraction)
                clipRect(
                    left = if (rtl && frontFraction < 1f) frontX else 0f,
                    right = if (!rtl && frontFraction < 1f) frontX else size.width,
                ) {
                    drawPath(wave, colors.primary, style = waveStroke)
                }
            }
            for (stop in 0..RideStyle.MAX_LEVEL) {
                drawCircle(
                    if (stop == level) colors.primary else colors.outlineVariant,
                    (if (stop == level) 2.5f else 2f).dp.toPx(),
                    Offset(x(stop.toFloat()), cy + DOT_OFFSET_DP.dp.toPx()),
                    alpha = UNREADY_DOT_ALPHA + (1f - UNREADY_DOT_ALPHA) * risen(stop.toFloat()),
                )
            }
            val painted = if (dragging) dragPosition else settled.value
            val handleX = x(painted)
            // No halo: the track shows through around the pill. The pill's foot
            // stops a small gap above the top of the level dots.
            val top = cy - 20.dp.toPx()
            val bottom = cy + (DOT_OFFSET_DP - 3f - 3f).dp.toPx()
            val pillWidth = (if (dragging) 6f else 4f).dp.toPx()
            drawRoundRect(colors.primary, Offset(handleX - pillWidth / 2, top),
                Size(pillWidth, bottom - top), CornerRadius(pillWidth / 2))
        }
    }
}

/**
 * [withFrameNanos] through the coroutine's [InfiniteAnimationPolicy]: the
 * endless wave drift while a finger is down is an infinite animation, which a
 * test clock pauses, so Compose still reaches idle during a held drag.
 */
private suspend fun <R> infiniteFrameNanos(onFrame: (Long) -> R): R {
    val policy = coroutineContext[InfiniteAnimationPolicy]
    return if (policy == null) withFrameNanos(onFrame) else policy.onInfiniteOperation { withFrameNanos(onFrame) }
}

private const val DOT_OFFSET_DP = 23f

/**
 * How far in from the slider's edges the first and last stops sit: the wave's
 * two ends, the first and last dots and the handle's end positions all lie on
 * these two points. The pane's captions and label start and end here too.
 */
internal val RIDE_STYLE_SLIDER_END_INSET = 8.dp

/** The flat track beyond the grown wave: its thickness and the gap kept after the wave's front. */
private const val TRACK_THICKNESS_DP = 6f
private const val TRACK_GAP_DP = 8f

/** Stops still being searched keep a faint dot, so all seven places stay visible. */
private const val UNREADY_DOT_ALPHA = 0.35f
