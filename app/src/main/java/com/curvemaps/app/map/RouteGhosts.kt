package com.curvemaps.app.map

import com.graphhopper.ResponsePath
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

internal const val GHOST_SOURCE_ID = "route-ghosts"
internal const val GHOST_LAYER_ID = "route-ghost-line"

/** Position among the ghosts (0 = most recently displayed). */
internal const val GHOST_INDEX_PROPERTY = "route_ghost_index"

/** Per-feature opacity, so one layer draws newer and older ghosts differently. */
internal const val GHOST_OPACITY_PROPERTY = "route_ghost_opacity"

/** Neutral grey that reads as a past route on the colour basemap and stays grayscale in B&W. */
internal const val GHOST_ROUTE_COLOR = "#9E9E9E"

/** Slimmer than the drawn route (8 dp focused, 5 dp plain), so history reads as history. */
internal const val GHOST_ROUTE_WIDTH = 4.5f

/** How many previously displayed routes stay visible behind the current one. */
internal const val GHOST_ROUTE_LIMIT = 2

/** Newest first: the older ghost is the faintest, both stay visible. */
private val GHOST_OPACITIES = listOf(0.55f, 0.34f)

/** The opacity a ghost at [index] (0 = most recently displayed) is drawn with. */
internal fun ghostOpacityFor(index: Int): Float =
    GHOST_OPACITIES[index.coerceIn(0, GHOST_OPACITIES.lastIndex)]

/**
 * Remembers the routes the screen actually displayed, newest first, so the map
 * can leave the previous [GHOST_ROUTE_LIMIT] ones as grey ghosts behind the
 * route on screen.
 *
 * History follows what the map drew, never the input that asked for it: a
 * re-render or a repeated display of the same route object records nothing, and
 * a transient blank (a slider move searching the next stop) keeps the history.
 * A different plan — endpoints, shaping points, road toggles, or a fresh
 * dataset — starts with no ghosts, because those routes no longer describe the
 * ride being planned.
 */
internal class RouteGhostHistory<K : Any> {
    private var ghosts: List<ResponsePath> = emptyList()
    private var current: ResponsePath? = null
    private var plan: K? = null

    /**
     * The previous displayed routes, newest first. The same list instance is
     * returned until a new route actually displaces one, so callers can key
     * geometry work on identity.
     */
    @Synchronized
    fun ghosts(): List<ResponsePath> = ghosts

    /** The plan the route on screen belongs to; null while no plan is displayed. */
    @Synchronized
    fun currentPlan(): K? = plan

    /**
     * Records the route the map displays now.
     *
     * [route] is null when nothing is displayed (searching, failed or idle):
     * the history must survive that, because the same plan's slider move is
     * transient. [plan] is null when the displayed route belongs to no
     * submitted plan (the rider is editing the endpoints): its old ghosts no
     * longer describe the plan being edited, so they go.
     *
     * A re-route that hands back the ride already on screen is not a displayed
     * change, so it neither records a ghost nor re-attributes the current ride
     * to a plan key that changed while it waits.
     */
    @Synchronized
    fun onDisplayed(plan: K?, route: ResponsePath?) {
        if (route == null) return
        val previous = current
        if (previous != null && sameRide(route, previous)) {
            if (plan != this.plan) ghosts = emptyList()
            if (plan == null) {
                this.plan = null
                ghosts = emptyList()
            }
            return
        }
        current = route
        ghosts = if (previous != null && plan != null && plan == this.plan) {
            (listOf(previous) + ghosts.filterNot { sameRide(it, route) }).take(GHOST_ROUTE_LIMIT)
        } else {
            emptyList()
        }
        this.plan = plan
    }

    /** Forgets every ghost: idle, guidance, or a fresh dataset. */
    @Synchronized
    fun clear() {
        ghosts = emptyList()
        current = null
        plan = null
    }

    /**
     * The same ride, not just the same object: the release re-route of the
     * style already shown comes back as an equal path, and a ghost for it would
     * only hide a real one behind the route.
     */
    private fun sameRide(a: ResponsePath, b: ResponsePath): Boolean {
        if (a === b) return true
        val points = a.points
        if (points.size() == 0 || points.size() != b.points.size()) return false
        for (index in 0 until points.size()) {
            if (points.getLat(index) != b.points.getLat(index) ||
                points.getLon(index) != b.points.getLon(index)
            ) return false
        }
        return true
    }
}

/**
 * Builds the ghost GeoJSON, converting each ghost to a line once and keeping
 * the last few. A slider step moves one route out of the history and adds one,
 * so the older ghost's line is reused instead of rebuilt on the main thread;
 * the finished collection is reused while the ghost set itself is unchanged
 * ([RouteGhostHistory.ghosts] hands out a stable list instance).
 */
internal class RouteGhostGeometryCache(private val capacity: Int = GHOST_ROUTE_LIMIT + 1) {
    private val lines = LinkedHashMap<ResponsePath, LineString>()
    private var cachedGhosts: List<ResponsePath>? = null
    private var cachedCollection: FeatureCollection? = null

    @Synchronized
    fun collectionFor(ghosts: List<ResponsePath>): FeatureCollection {
        val cached = cachedCollection
        if (cached != null && cachedGhosts === ghosts) return cached
        // Oldest first so the newest ghost draws on top where two overlap.
        val features = ghosts.take(GHOST_ROUTE_LIMIT)
            .mapIndexedNotNull { index, path ->
                val line = lineFor(path) ?: return@mapIndexedNotNull null
                Feature.fromGeometry(line).apply {
                    addNumberProperty(GHOST_INDEX_PROPERTY, index)
                    addNumberProperty(GHOST_OPACITY_PROPERTY, ghostOpacityFor(index))
                }
            }
            .asReversed()
        val collection = FeatureCollection.fromFeatures(features)
        cachedGhosts = ghosts
        cachedCollection = collection
        return collection
    }

    @Synchronized
    fun needsRefresh(ghosts: List<ResponsePath>): Boolean = cachedGhosts !== ghosts

    @Synchronized
    fun invalidate() {
        lines.clear()
        cachedGhosts = null
        cachedCollection = null
    }

    private fun lineFor(path: ResponsePath): LineString? {
        lines[path]?.let { return it }
        val coordinates = path.points.map { Point.fromLngLat(it.lon, it.lat) }
        if (coordinates.size < 2) return null
        if (lines.size >= capacity) lines.remove(lines.keys.first())
        return LineString.fromLngLats(coordinates).also { lines[path] = it }
    }
}

/**
 * Draws the previous routes as grey ghosts under the route on screen. A
 * separate source and layer keep ghosts out of the route's hit testing, route
 * counting and camera fitting: they are decoration, not routes.
 */
internal fun MapLibreMap.drawRouteGhosts(
    ghosts: List<ResponsePath>,
    geometryCache: RouteGhostGeometryCache,
    requestIsCurrent: () -> Boolean = { true },
) {
    getStyle { style ->
        if (!requestIsCurrent()) return@getStyle
        if (ghosts.isEmpty()) {
            style.removeLayer(GHOST_LAYER_ID)
            style.removeSource(GHOST_SOURCE_ID)
            return@getStyle
        }
        val source = style.getSourceAs<GeoJsonSource>(GHOST_SOURCE_ID)
        if (source == null) {
            style.addSource(
                GeoJsonSource(GHOST_SOURCE_ID).apply {
                    setGeoJson(geometryCache.collectionFor(ghosts))
                },
            )
        } else if (geometryCache.needsRefresh(ghosts)) {
            source.setGeoJson(geometryCache.collectionFor(ghosts))
        }
        if (style.getLayer(GHOST_LAYER_ID) == null) {
            val layer = LineLayer(GHOST_LAYER_ID, GHOST_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(GHOST_ROUTE_COLOR),
                PropertyFactory.lineOpacity(Expression.get(GHOST_OPACITY_PROPERTY)),
                PropertyFactory.lineWidth(GHOST_ROUTE_WIDTH),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
            val anchor = ghostLayerAnchor(style)
            if (anchor != null) style.addLayerBelow(layer, anchor) else style.addLayer(layer)
        }
    }
}

/** Removes the ghost decoration; the route layers are untouched. */
internal fun MapLibreMap.clearRouteGhosts() {
    getStyle { style ->
        style.removeLayer(GHOST_LAYER_ID)
        style.removeSource(GHOST_SOURCE_ID)
    }
}

/**
 * The bottom-most route layer currently in the style, so ghosts land under the
 * route no matter which route layers a mode drew: colour mode stacks casing →
 * line → focus layers, B&W mode only the focus line.
 */
private fun ghostLayerAnchor(style: Style): String? =
    listOf(ROUTE_CASING_ID, ROUTE_LINE_ID, ROUTE_FOCUS_CASING_ID, ROUTE_FOCUS_LINE_ID)
        .firstOrNull { style.getLayer(it) != null }
