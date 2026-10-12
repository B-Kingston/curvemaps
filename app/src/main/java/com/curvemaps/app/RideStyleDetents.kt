package com.curvemaps.app

import com.curvemaps.app.routing.RideStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/** Spatial haptic ramp: only approaching a stop produces pulses, never dwelling on it. */
internal class RideStyleDetents(start: Float) {
    private var previous = bounded(start)
    private var latched: Int? = previous.roundToInt().takeIf { abs(previous - it) <= SNAP_DISTANCE }
    private var approachStop = -1
    private var approachBand = 0

    data class Feedback(val clickedLevels: List<Int> = emptyList(), val approach: Int = 0)

    fun move(position: Float): Feedback {
        val next = bounded(position)
        val nearest = next.roundToInt()
        if (latched != null && abs(next - latched!!) > RELEASE_DISTANCE) latched = null
        val clicks = (0..RideStyle.MAX_LEVEL).filter { stop ->
            stop != latched && (abs(next - stop) <= SNAP_DISTANCE ||
                (previous < stop && next > stop) || (previous > stop && next < stop))
        }.let { if (next < previous) it.reversed() else it }
        if (clicks.isNotEmpty()) {
            latched = clicks.last().takeIf { abs(next - it) <= RELEASE_DISTANCE }
            approachStop = -1
            approachBand = 0
            previous = next
            return Feedback(clickedLevels = clicks)
        }
        val distance = abs(next - nearest)
        val approaching = distance < abs(previous - nearest)
        val band = when {
            distance <= 0.16f -> 3
            distance <= 0.26f -> 2
            distance <= 0.38f -> 1
            else -> 0
        }
        if (nearest != approachStop || distance > 0.38f) {
            approachStop = nearest
            approachBand = 0
        }
        val pulse = if (approaching && latched != nearest && band > approachBand) band else 0
        if (pulse > 0) approachBand = band
        previous = next
        return Feedback(approach = pulse)
    }

    fun paintedPosition(position: Float): Float {
        val bounded = bounded(position)
        return latched?.toFloat()?.takeIf { abs(bounded - it) <= SNAP_DISTANCE } ?: bounded
    }

    fun needsReleaseClick(position: Float): Boolean = latched != bounded(position).roundToInt()

    companion object {
        const val SNAP_DISTANCE = 0.07f
        private const val RELEASE_DISTANCE = 0.18f
        fun bounded(value: Float): Float =
            if (value.isFinite()) value.coerceIn(0f, RideStyle.MAX_LEVEL.toFloat()) else 0f
    }
}
