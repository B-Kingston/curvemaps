package com.curvemaps.app.map

import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val SUGGESTION_SOURCE_ID = "ride-suggestion"
private const val SUGGESTION_LINE_ID = "ride-suggestion-line"

/** Colour map: the carousel's second route colour, so it reads as "an alternative". */
internal const val SUGGESTION_COLOR = "#00897B"

/** B&W ride map: a mid grey, dimmer than the white route and grayscale like the style. */
internal const val SUGGESTION_DARK_COLOR = "#9E9E9E"
internal const val SUGGESTION_WIDTH = 6f

/**
 * Draws the pending scenic diversion (only its diverging stretch) under the
 * route being followed. A separate source keeps the route carousel, its
 * hit-testing, and `MapRouteCount` untouched.
 */
internal fun MapLibreMap.drawSuggestionPreview(
    latitudes: DoubleArray,
    longitudes: DoubleArray,
    darkGuidanceMode: Boolean,
) {
    if (latitudes.size < 2) {
        clearSuggestionPreview()
        return
    }
    val line = LineString.fromLngLats(latitudes.indices.map { Point.fromLngLat(longitudes[it], latitudes[it]) })
    getStyle { style ->
        val source = style.getSourceAs<GeoJsonSource>(SUGGESTION_SOURCE_ID)
        if (source != null) {
            source.setGeoJson(line)
        } else {
            style.addSource(GeoJsonSource(SUGGESTION_SOURCE_ID).apply { setGeoJson(line) })
        }
        style.removeLayer(SUGGESTION_LINE_ID)
        val layer = LineLayer(SUGGESTION_LINE_ID, SUGGESTION_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(if (darkGuidanceMode) SUGGESTION_DARK_COLOR else SUGGESTION_COLOR),
            PropertyFactory.lineWidth(SUGGESTION_WIDTH),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        if (style.getLayer(ROUTE_FOCUS_LINE_ID) != null) {
            style.addLayerBelow(layer, ROUTE_FOCUS_LINE_ID)
        } else {
            style.addLayer(layer)
        }
    }
}

internal fun MapLibreMap.clearSuggestionPreview() {
    getStyle { style ->
        style.removeLayer(SUGGESTION_LINE_ID)
        style.removeSource(SUGGESTION_SOURCE_ID)
    }
}
