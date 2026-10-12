package com.curvemaps.app.routing

import com.graphhopper.ResponsePath

/**
 * How big a round trip should be: a ride time or a distance, whichever the
 * rider thinks in. It is a wish, not a limit: [RoundTrips] looks for loops
 * whose measured time or distance lands near it ([ratio]).
 *
 * Values are whole minutes or kilometres within [MIN_MINUTES]..[MAX_MINUTES]
 * and [MIN_KILOMETRES]..[MAX_KILOMETRES]; [coerced] clamps typed input, and
 * [stepped] moves along the stepper grid (15 min; 5 km up to 50 km, then 10 km).
 */
sealed interface RoundTripLength {
    data class Time(val minutes: Int) : RoundTripLength
    data class Distance(val kilometres: Int) : RoundTripLength

    /** The value in its own unit (minutes or kilometres). */
    val amount: Int
        get() = when (this) {
            is Time -> minutes
            is Distance -> kilometres
        }

    /** Measured size of [path] over the wished size: 1.0 is spot on. */
    fun ratio(path: ResponsePath): Double = when (this) {
        is Time -> path.time / (minutes * 60_000.0)
        is Distance -> path.distance / (kilometres * 1_000.0)
    }

    /**
     * Road metres the first loop shapes aim for: a time wish is turned into a
     * distance at [ASSUMED_SPEED_KMH]; the search then recalibrates from the
     * loops it measured ([RoundTrips]).
     */
    val estimatedRoadMeters: Double
        get() = when (this) {
            is Time -> minutes / 60.0 * ASSUMED_SPEED_KMH * 1_000.0
            is Distance -> kilometres * 1_000.0
        }

    /** "45 min", "1 h", "1 h 30 min" or "120 km". */
    val label: String
        get() = when (this) {
            is Distance -> "$kilometres km"
            is Time -> when {
                minutes < 60 -> "$minutes min"
                minutes % 60 == 0 -> "${minutes / 60} h"
                else -> "${minutes / 60} h ${minutes % 60} min"
            }
        }

    /** The same length clamped into its unit's range. */
    fun coerced(): RoundTripLength = when (this) {
        is Time -> Time(minutes.coerceIn(MIN_MINUTES, MAX_MINUTES))
        is Distance -> Distance(kilometres.coerceIn(MIN_KILOMETRES, MAX_KILOMETRES))
    }

    /**
     * One stepper press longer ([direction] > 0) or shorter: the next value
     * on the unit's grid, so a typed 37 km steps to 40 km or 35 km.
     */
    fun stepped(direction: Int): RoundTripLength {
        val value = amount
        val next = when (this) {
            is Time -> gridStep(value, direction) { MINUTE_STEP }
            // Short rides need finer steps than long ones.
            is Distance -> gridStep(value, direction) { up -> if (up) (if (value < 50) 5 else 10) else (if (value <= 50) 5 else 10) }
        }
        return withAmount(next).coerced()
    }

    /** Whether a stepper press shorter or longer would leave the range. */
    val atMinimum: Boolean
        get() = when (this) {
            is Time -> minutes <= MIN_MINUTES
            is Distance -> kilometres <= MIN_KILOMETRES
        }
    val atMaximum: Boolean
        get() = when (this) {
            is Time -> minutes >= MAX_MINUTES
            is Distance -> kilometres >= MAX_KILOMETRES
        }

    /** About the same ride as a time, at [ASSUMED_SPEED_KMH]; a time stays as it is. */
    fun asTime(): RoundTripLength = when (this) {
        is Time -> this
        is Distance -> near(DEFAULT, (kilometres / ASSUMED_SPEED_KMH * 3_600_000.0).toLong(), 0.0)
    }

    /** About the same ride as a distance, at [ASSUMED_SPEED_KMH]; a distance stays as it is. */
    fun asDistance(): RoundTripLength = when (this) {
        is Distance -> this
        is Time -> near(DEFAULT_DISTANCE, 0L, minutes / 60.0 * ASSUMED_SPEED_KMH * 1_000.0)
    }

    /** This unit with [value] in it. */
    fun withAmount(value: Int): RoundTripLength = when (this) {
        is Time -> Time(value)
        is Distance -> Distance(value)
    }

    /** Persisted form, e.g. "time:90" or "distance:120". */
    val storageKey: String
        get() = when (this) {
            is Time -> "time:$minutes"
            is Distance -> "distance:$kilometres"
        }

    companion object {
        const val MIN_MINUTES = 15
        const val MAX_MINUTES = 8 * 60
        const val MINUTE_STEP = 15
        const val MIN_KILOMETRES = 10
        const val MAX_KILOMETRES = 400

        /** Average loop speed assumed before anything was measured, from probes around SEQ. */
        const val ASSUMED_SPEED_KMH = 55.0

        val DEFAULT: RoundTripLength = Time(60)
        val DEFAULT_DISTANCE: RoundTripLength = Distance(50)

        /** One-tap lengths in the length dialog. */
        val TIME_PRESETS: List<RoundTripLength> = listOf(30, 60, 120, 180, 240).map(::Time)
        val DISTANCE_PRESETS: List<RoundTripLength> = listOf(25, 50, 100, 150, 200).map(::Distance)

        /**
         * A ride's own length in [unit]'s unit, on the stepper grid: a loaded
         * round trip of 1 h 52 min reads "1 h 45 min" in time, "120 km" in
         * distance.
         */
        fun near(unit: RoundTripLength, timeMillis: Long, distanceMeters: Double): RoundTripLength = when (unit) {
            is Time -> Time(roundTo(timeMillis / 60_000.0, MINUTE_STEP))
            is Distance -> {
                val kilometres = distanceMeters / 1_000.0
                Distance(roundTo(kilometres, if (kilometres < 50.0) 5 else 10))
            }
        }.coerced()

        private fun roundTo(value: Double, step: Int): Int = (Math.round(value / step) * step).toInt()

        /** Reads [storageKey]; anything unreadable is [DEFAULT]. */
        fun parse(stored: String?): RoundTripLength {
            val (unit, value) = stored?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return DEFAULT
            val amount = value.toIntOrNull() ?: return DEFAULT
            return when (unit) {
                "time" -> Time(amount).coerced()
                "distance" -> Distance(amount).coerced()
                else -> DEFAULT
            }
        }

        private inline fun gridStep(value: Int, direction: Int, step: (up: Boolean) -> Int): Int {
            val up = direction > 0
            val size = step(up)
            return if (up) (value / size + 1) * size else ((value - 1) / size) * size
        }
    }
}
