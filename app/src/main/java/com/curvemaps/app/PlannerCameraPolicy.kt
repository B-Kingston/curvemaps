package com.curvemaps.app

/**
 * Who owns the planner camera. A shown route owns it until the rider taps
 * Centre on me: no late GPS fix and no earlier follow lock may fly the camera
 * away from the route the rider is looking at.
 */
internal object PlannerCameraPolicy {

    /**
     * The one-shot move to the first GPS fix. It runs only while nothing else
     * framed the map: never once a route has been shown (the fix can arrive
     * seconds after START on a cold GPS), never while a plan is searching, and
     * never after the rider moved the map themselves.
     */
    fun centerOnFirstFix(
        alreadySpent: Boolean,
        routeShown: Boolean,
        planSearching: Boolean,
        followMe: Boolean,
        guidance: Boolean,
    ): Boolean = !alreadySpent && !routeShown && !planSearching && !followMe && !guidance

    /**
     * Framing a new route releases the planner's follow lock, so the next fix
     * does not pull the camera back to the rider. Centre on me turns it on again.
     */
    fun followMeAfterRouteFit(followMe: Boolean, routeFitted: Boolean): Boolean =
        followMe && !routeFitted
}
