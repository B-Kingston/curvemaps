package com.curvemaps.app.routing

/**
 * Optional ride factors from Route settings ("Include in route calculations"),
 * applied on top of the ride style and the road toggles.
 *
 * - [elevation]: hilly roads count as fun roads on every curvy stop (Mostly
 *   fast and above), so a climb over a range competes with a bend. Fastest
 *   reads no geometry and is unchanged.
 * - [stopsAndTowns]: every stop (traffic signals, stop and give-way signs,
 *   mini roundabouts) costs extra time and roads through built-up areas weigh
 *   more, at every stop including Fastest.
 *
 * Both are soft: a ride is never stranded because of them, and ETA stays the
 * physical travel time. A graph without the matching data (an older map
 * package) simply contributes nothing; [GraphFeatures] says what the loaded
 * graph carries.
 */
data class RideFactors(
    val elevation: Boolean = false,
    val stopsAndTowns: Boolean = false,
) {
    val any: Boolean get() = elevation || stopsAndTowns

    companion object {
        val NONE = RideFactors()
    }
}

/**
 * The ride-factor data a loaded graph carries. Graphs imported before the
 * factors existed are 2D, have no `moto_stops` KV entries and no
 * `urban_density` encoded value.
 */
data class GraphFeatures(
    val elevation: Boolean,
    val stops: Boolean,
    val urbanDensity: Boolean,
) {
    /** Whether [RideFactors.elevation] has data to work with. */
    val supportsElevation: Boolean get() = elevation

    /** Whether [RideFactors.stopsAndTowns] has data to work with (either half is useful). */
    val supportsStopsAndTowns: Boolean get() = stops || urbanDensity

    companion object {
        val ALL = GraphFeatures(elevation = true, stops = true, urbanDensity = true)
    }
}
