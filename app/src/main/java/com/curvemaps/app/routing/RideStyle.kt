package com.curvemaps.app.routing

import kotlin.math.roundToInt

/**
 * The bounded ride-style dial. Each stop is ONE calibrated curve preference;
 * road-type choices (motorways, unpaved surfaces) are separate Route settings
 * toggles, so the dial never silently changes them.
 *
 * [curveWeight] scales the straight-road penalty in [ComplexityWeighting]:
 * an edge costs `1 + STRAIGHT_ROAD_PENALTY * curveWeight * (1 - exposure)^2`
 * times its travel time, so a perfectly straight edge costs
 * `1 + STRAIGHT_ROAD_PENALTY * curveWeight` and a fully curvy edge its
 * travel time. A fully curvy road therefore beats an equally fast straight
 * road while it is less than [sameSpeedBreakEven] times longer:
 *
 * | stop         | curveWeight | same speed | curvy 60 vs straight 100 km/h |
 * |--------------|-------------|------------|-------------------------------|
 * | Fastest      | 0.0         | 1.00x      | 0.60x                         |
 * | Mostly fast  | 0.3         | 1.45x      | 0.87x                         |
 * | Balanced     | 0.6         | 1.90x      | 1.14x                         |
 * | Curvy        | 1.05        | 2.58x      | 1.55x                         |
 * | Twisty       | 1.5         | 3.25x      | 1.95x                         |
 * | Very twisty  | 2.25        | 4.38x      | 2.63x                         |
 * | Max twisty   | 3.0         | 5.50x      | 3.30x                         |
 *
 * The planner shows exactly one route per stop (there is no carousel), so
 * the in-between stops are the way to ask for "a little curvier": each one
 * sits halfway between its calibrated neighbours.
 *
 * Calibrated on the real Queensland graph (`CurvyCorridorProbe`): Palmwoods
 * to Brisbane at Max twisty used to return the 91 min, 12% curvy coastal
 * route and reject the D'Aguilar loop; it now leads with a 100 min, 16% curvy
 * ride, and the 119-158 min, 23-26% curvy range roads remain reachable.
 *
 * [maxAlternativeTimeRatio]/[maxAlternativeDistanceRatio] bound alternatives
 * against this style's own primary where the router still collects them (ride
 * suggestions, saved-ride shape matching). Curvy stops allow longer
 * detours, because a long loop through the ranges is exactly what they ask
 * for.
 *
 * Unlike the old unbounded dial, nothing here saturates: the custom model's
 * per-kilometre distance influence is no longer scaled into the preference.
 */
enum class RideStyle(
    val label: String,
    val curveWeight: Double,
    val maxAlternativeTimeRatio: Double,
    val maxAlternativeDistanceRatio: Double,
) {
    FASTEST("Fastest", 0.0, 1.50, 1.65),
    MOSTLY_FAST("Mostly fast", 0.3, 1.50, 1.65),
    BALANCED("Balanced", 0.6, 1.50, 1.65),
    CURVY("Curvy", 1.05, 1.625, 1.75),
    TWISTY("Twisty", 1.5, 1.75, 1.85),
    VERY_TWISTY("Very twisty", 2.25, 1.875, 1.975),
    MAX_TWISTY("Max twisty", 3.0, 2.00, 2.10);

    /** Slider stop (0 = Fastest); also the request-hint and stored value. */
    val level: Int get() = ordinal

    /** Length ratio up to which a fully curvy road beats an equally fast straight one. */
    val sameSpeedBreakEven: Double get() = 1.0 + STRAIGHT_ROAD_PENALTY * curveWeight

    companion object {
        /** Added multiplier for a perfectly straight edge at curveWeight 1. */
        const val STRAIGHT_ROAD_PENALTY = 1.5

        val MAX_LEVEL: Int = entries.lastIndex

        /**
         * Ceiling for a stop searched again a little curvier because it
         * repeated a lower stop's ride ([DistinctRideStyles]): twice Max
         * twisty. Higher weights mostly slow the search down.
         */
        const val MAX_CURVE_WEIGHT = 6.0

        /**
         * Maps any stored or requested level onto a stop. Saved rides from
         * the four-stop slider are rescaled by the store's schema v3 upgrade;
         * older unbounded levels clamp to [MAX_TWISTY].
         */
        fun fromLevel(level: Double): RideStyle =
            if (level.isNaN()) FASTEST else entries[level.roundToInt().coerceIn(0, MAX_LEVEL)]

        fun fromLevel(level: Float): RideStyle = fromLevel(level.toDouble())
    }
}
