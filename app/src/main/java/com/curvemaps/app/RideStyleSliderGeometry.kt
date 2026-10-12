package com.curvemaps.app

import com.curvemaps.app.routing.RideStyle
import kotlin.math.PI
import kotlin.math.cos

/** One complete wave per style, with each detent on its crest; the track runs from the first crest to the last. */
internal object RideStyleSliderGeometry {
    val humpCount = RideStyle.MAX_LEVEL + 1

    /**
     * Crest heights per stop. The four original stops keep their heights
     * (Fastest 0.3, Balanced 0.6, Twisty 0.8, Max twisty 1.0); each mid stop
     * sits halfway between its neighbours, so the climb reads as one curve.
     */
    private val HUMP_HEIGHTS = floatArrayOf(0.3f, 0.45f, 0.6f, 0.7f, 0.8f, 0.9f, 1f)

    /**
     * Hump height at a continuous level position, linear between crests. Below
     * Fastest, down to the track's left end (level -0.5), it eases in from zero
     * growth (matching the Fastest→Balanced slope), so the opening dip is a true
     * low point and the line rises from it immediately.
     */
    fun envelope(level: Float): Float {
        val clamped = level.coerceIn(-0.5f, RideStyle.MAX_LEVEL.toFloat())
        if (clamped < 0f) {
            val slope = HUMP_HEIGHTS[1] - HUMP_HEIGHTS[0]
            return HUMP_HEIGHTS[0] + slope * clamped + slope * clamped * clamped
        }
        val below = clamped.toInt().coerceAtMost(RideStyle.MAX_LEVEL - 1)
        val t = clamped - below
        return HUMP_HEIGHTS[below] + (HUMP_HEIGHTS[below + 1] - HUMP_HEIGHTS[below]) * t
    }

    /**
     * Line height about the track's centre line, in -0.5..0.5 of the tallest
     * hump's crest-to-dip span, at track position [u] (0..humpCount): crests
     * (one per detent) rise above it and dips sink below it. The line starts in
     * a shallow dip and rises gently into the Fastest crest; the amplitude grows
     * continuously, so the drag [phase] can travel the wave without seams.
     */
    fun waveHeight(u: Float, phase: Float = 0f): Float =
        -envelope(u - 0.5f) * cos(2f * PI.toFloat() * (u - phase)) / 2f

    /** Drag-wave period: 2.4 s per wavelength at Fastest down to 0.8 s at Max twisty. */
    fun wavePeriodMillis(level: Float): Float {
        val clamped = level.coerceIn(0f, RideStyle.MAX_LEVEL.toFloat())
        return 2400f - 1600f * clamped / RideStyle.MAX_LEVEL
    }

    /**
     * Share of the wave's height drawn at track position [u] (0..humpCount)
     * while the wave has grown out to [grown] stops (0..humpCount; animated
     * toward the number of ride styles whose search has finished). Behind the
     * front ([growthFront]) everything is up, everything ahead of it
     * is still the flat line, and each crest pops up with a small overshoot
     * as the front passes it. A hump's crest is fully up exactly when its
     * stop is ready (`grown = level + 1`).
     */
    fun growthHeight(grown: Float, u: Float): Float {
        val t = ((growthFront(grown) - u) * GROWTH_SHARPNESS).coerceIn(0f, 1f)
        val back = t - 1f
        return (1f + (GROWTH_OVERSHOOT + 1f) * back * back * back + GROWTH_OVERSHOOT * back * back).coerceAtLeast(0f)
    }

    /**
     * Track position of the growth front. It runs a little ahead of [grown]
     * (half a hump by the time every stop is ready), so the last hump's
     * falling edge is fully up with the rest when the search is done.
     */
    fun growthFront(grown: Float): Float = grown * (1f + 0.5f / humpCount)

    /** Time the front takes to cross one hump; a finished plan grows out in one sweep. */
    const val GROWTH_MILLIS_PER_STOP = 120

    /** Shortest growth step, so a single new hump still glides in. */
    const val MIN_GROWTH_MILLIS = 320

    /** The front's ramp is half a hump wide, so the crest is up when the hump's stop is. */
    private const val GROWTH_SHARPNESS = 2f

    /** Back-out easing strength: about 7% overshoot per hump. */
    private const val GROWTH_OVERSHOOT = 1.4f

    /** Where a stop sits along the track: Fastest at the start, Max twisty at the end. */
    fun fractionAtLevel(level: Float): Float = level / RideStyle.MAX_LEVEL

    fun levelAtFraction(fraction: Float): Float =
        RideStyleDetents.bounded(fraction * RideStyle.MAX_LEVEL)

    /**
     * Wave position (the `u` of [waveHeight] and [growthHeight], 0..humpCount)
     * under a track [fraction]: the track runs from the first crest (u = 0.5)
     * to the last (u = humpCount - 0.5).
     */
    fun positionAtFraction(fraction: Float): Float = 0.5f + fraction * RideStyle.MAX_LEVEL

    /** Track fraction (0..1) the growth front has reached once [grown] stops are ready; 0 while nothing is. */
    fun fractionAtGrowthFront(grown: Float): Float =
        ((growthFront(grown) - 0.5f) / RideStyle.MAX_LEVEL).coerceIn(0f, 1f)
}
