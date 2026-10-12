package com.curvemaps.app.routing

import kotlin.math.roundToLong

/** How the rider sizes a round trip: by ride time, by distance, or by when they must be home. */
enum class RoundTripMeasure(val storageName: String) {
    TIME("time"),
    DISTANCE("distance"),
    BACK_BY("back_by"),
}

/**
 * The planner's round-trip setting: one value per [RoundTripMeasure], and
 * which one is in use. Each measure keeps its own value, so stepping the ride
 * time never moves the distance or the back-by time, and switching measure
 * brings back whatever that measure was last set to.
 *
 * [backByEpochMinute] is a wall-clock time (minutes since the epoch, so a
 * quarter hour of epoch time is a local quarter hour in every real time zone).
 * It is held only while it is still ahead: [backBy] falls back to "now plus
 * the ride time" once it has passed. [length] turns the setting into the
 * [RoundTripLength] the loop search takes; a back-by time becomes the ride
 * time left until then, floored to the 15 min grid so the wish (and the plan
 * it keys) only changes every quarter hour while the rider waits.
 */
data class RoundTripWish(
    val measure: RoundTripMeasure = RoundTripMeasure.TIME,
    val time: RoundTripLength.Time = RoundTripLength.DEFAULT as RoundTripLength.Time,
    val distance: RoundTripLength.Distance = RoundTripLength.DEFAULT_DISTANCE as RoundTripLength.Distance,
    val backByEpochMinute: Long? = null,
) {
    /** The back-by time in force at [nowMillis]: the held one while it is ahead, else now plus the ride time. */
    fun backBy(nowMillis: Long): Long {
        val earliest = earliestBackBy(nowMillis)
        val latest = latestBackBy(nowMillis)
        val held = backByEpochMinute
        return if (held == null || held < earliest) {
            ceilToStep(epochMinute(nowMillis) + time.minutes).coerceIn(earliest, latest)
        } else {
            held.coerceAtMost(latest)
        }
    }

    /** What the loop search is asked for at [nowMillis]. */
    fun length(nowMillis: Long): RoundTripLength = when (measure) {
        RoundTripMeasure.TIME -> time
        RoundTripMeasure.DISTANCE -> distance
        RoundTripMeasure.BACK_BY -> RoundTripLength.Time(rideMinutesUntilBackBy(nowMillis))
    }

    /** Whole ride minutes left before [backBy], on the 15 min grid. */
    fun rideMinutesUntilBackBy(nowMillis: Long): Int {
        val minutes = backBy(nowMillis) - epochMinute(nowMillis)
        return ((minutes / STEP_MINUTES) * STEP_MINUTES).toInt()
            .coerceIn(RoundTripLength.MIN_MINUTES, RoundTripLength.MAX_MINUTES)
    }

    /** One stepper press longer ([direction] > 0) or shorter, on the measure in use only. */
    fun stepped(direction: Int, nowMillis: Long): RoundTripWish = when (measure) {
        RoundTripMeasure.TIME -> copy(time = time.stepped(direction) as RoundTripLength.Time)
        RoundTripMeasure.DISTANCE -> copy(distance = distance.stepped(direction) as RoundTripLength.Distance)
        RoundTripMeasure.BACK_BY -> {
            val current = backBy(nowMillis)
            val next = if (direction > 0) floorToStep(current) + STEP_MINUTES else ceilToStep(current) - STEP_MINUTES
            copy(backByEpochMinute = next.coerceIn(earliestBackBy(nowMillis), latestBackBy(nowMillis)))
        }
    }

    /** Whether a stepper press shorter (or earlier) would leave the range. */
    fun atMinimum(nowMillis: Long): Boolean = when (measure) {
        RoundTripMeasure.TIME -> time.atMinimum
        RoundTripMeasure.DISTANCE -> distance.atMinimum
        RoundTripMeasure.BACK_BY -> backBy(nowMillis) <= earliestBackBy(nowMillis)
    }

    /** Whether a stepper press longer (or later) would leave the range. */
    fun atMaximum(nowMillis: Long): Boolean = when (measure) {
        RoundTripMeasure.TIME -> time.atMaximum
        RoundTripMeasure.DISTANCE -> distance.atMaximum
        RoundTripMeasure.BACK_BY -> backBy(nowMillis) >= latestBackBy(nowMillis)
    }

    /**
     * Switches to [next], leaving every value as it was. Choosing back-by
     * pins the time it shows, so it holds still while the clock moves on.
     */
    fun withMeasure(next: RoundTripMeasure, nowMillis: Long): RoundTripWish = when (next) {
        RoundTripMeasure.BACK_BY -> copy(measure = next, backByEpochMinute = backBy(nowMillis))
        else -> copy(measure = next)
    }

    /**
     * About the same ride as a loaded one ([durationMillis], [distanceMeters]),
     * in the measure in use; the other measures keep their values.
     */
    fun withRide(durationMillis: Long, distanceMeters: Double, nowMillis: Long): RoundTripWish = when (measure) {
        RoundTripMeasure.TIME ->
            copy(time = RoundTripLength.near(time, durationMillis, distanceMeters) as RoundTripLength.Time)
        RoundTripMeasure.DISTANCE ->
            copy(distance = RoundTripLength.near(distance, durationMillis, distanceMeters) as RoundTripLength.Distance)
        RoundTripMeasure.BACK_BY -> {
            val back = ceilToStep(epochMinute(nowMillis) + (durationMillis / 60_000.0).roundToLong())
            copy(backByEpochMinute = back.coerceIn(earliestBackBy(nowMillis), latestBackBy(nowMillis)))
        }
    }

    /** Persisted form, e.g. "measure:time,time:90,distance:80" (plus ",back_by:<epoch minute>" once set). */
    val storageKey: String
        get() = buildString {
            append("measure:${measure.storageName},time:${time.minutes},distance:${distance.kilometres}")
            backByEpochMinute?.let { append(",back_by:$it") }
        }

    companion object {
        /** The stepper grid for a back-by time, and the shortest ride it allows. */
        const val STEP_MINUTES = 15L

        /**
         * Reads [storageKey]. The older single-length form ("time:90",
         * "distance:120") still reads, with that length in use; anything
         * unreadable falls back to the default for that value.
         */
        fun parse(stored: String?): RoundTripWish {
            val fields = stored.orEmpty().split(',').mapNotNull { field ->
                val parts = field.split(':', limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()
            val defaults = RoundTripWish()
            val time = fields["time"]?.toIntOrNull()
                ?.let { RoundTripLength.Time(it).coerced() as RoundTripLength.Time } ?: defaults.time
            val distance = fields["distance"]?.toIntOrNull()
                ?.let { RoundTripLength.Distance(it).coerced() as RoundTripLength.Distance } ?: defaults.distance
            val measure = fields["measure"]?.let { name -> RoundTripMeasure.entries.firstOrNull { it.storageName == name } }
                ?: when {
                    // The older form held one length: that one is in use.
                    "distance" in fields && "time" !in fields -> RoundTripMeasure.DISTANCE
                    else -> RoundTripMeasure.TIME
                }
            return RoundTripWish(measure, time, distance, fields["back_by"]?.toLongOrNull())
        }

        fun epochMinute(nowMillis: Long): Long = Math.floorDiv(nowMillis, 60_000L)

        /** The soonest back-by time: a quarter-hour mark at least a shortest ride away. */
        fun earliestBackBy(nowMillis: Long): Long =
            ceilToStep(epochMinute(nowMillis) + RoundTripLength.MIN_MINUTES)

        /** The latest back-by time: the last quarter-hour mark within the longest ride. */
        fun latestBackBy(nowMillis: Long): Long =
            floorToStep(epochMinute(nowMillis) + RoundTripLength.MAX_MINUTES)

        private fun floorToStep(minute: Long): Long = Math.floorDiv(minute, STEP_MINUTES) * STEP_MINUTES
        private fun ceilToStep(minute: Long): Long = -Math.floorDiv(-minute, STEP_MINUTES) * STEP_MINUTES
    }
}
