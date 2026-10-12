package com.curvemaps.app

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.curvemaps.app.map.ROUTE_CASING_COLOR
import com.curvemaps.app.map.rgbOf
import com.curvemaps.app.map.routeColorHex
import com.curvemaps.app.storage.GeoPoint
import com.curvemaps.app.storage.PolylineCodec
import com.curvemaps.app.ui.theme.CurveMapsTheme

/**
 * Pure projection math behind the saved-route mini-map banner. Fits a
 * polyline into a pixel box, preserving shape: an equirectangular projection
 * whose longitude axis is compressed by cos(mean latitude) so east-west and
 * north-south metres occupy comparable space at riding latitudes.
 *
 * Returns one [Offset] per input point, in draw order, inside
 * `[0,width]x[0,height]` with [paddingPx] margins on the tight axes.
 */
object MiniMapLayout {

    /**
     * Degenerate inputs collapse gracefully: an empty list maps to an empty
     * list, and a zero-extent route (single point, or all points equal)
     * centres every point at the middle of the box. Padding larger than half
     * the box collapses the usable area the same way rather than throwing.
     */
    fun fit(
        points: List<GeoPoint>,
        widthPx: Float,
        heightPx: Float,
        paddingPx: Float,
    ): List<Offset> {
        if (points.isEmpty()) return emptyList()
        var latMin = Double.MAX_VALUE
        var latMax = -Double.MAX_VALUE
        var lonMin = Double.MAX_VALUE
        var lonMax = -Double.MAX_VALUE
        for (point in points) {
            if (point.lat < latMin) latMin = point.lat
            if (point.lat > latMax) latMax = point.lat
            if (point.lon < lonMin) lonMin = point.lon
            if (point.lon > lonMax) lonMax = point.lon
        }
        val latCenter = (latMin + latMax) / 2.0
        val lonCenter = (lonMin + lonMax) / 2.0
        // Longitude degrees shrink with latitude; clamp away from zero so a
        // hypothetical polar route degrades to a flat projection, not NaNs.
        val longitudeScale = Math.cos(Math.toRadians(latCenter)).coerceIn(0.05, 1.0)
        val xSpan = (lonMax - lonMin) * longitudeScale
        val ySpan = latMax - latMin
        val usableWidth = (widthPx - 2 * paddingPx).coerceAtLeast(0f)
        val usableHeight = (heightPx - 2 * paddingPx).coerceAtLeast(0f)

        var scale = Double.POSITIVE_INFINITY
        if (xSpan > 0.0) scale = minOf(scale, usableWidth / xSpan)
        if (ySpan > 0.0) scale = minOf(scale, usableHeight / ySpan)
        if (scale.isInfinite()) {
            // Nothing varies (or no room): every point sits dead centre.
            return List(points.size) { Offset(widthPx / 2f, heightPx / 2f) }
        }

        return points.map { point ->
            Offset(
                x = (widthPx / 2f + (point.lon - lonCenter) * longitudeScale * scale).toFloat(),
                y = (heightPx / 2f - (point.lat - latCenter) * scale).toFloat(),
            )
        }
    }
}


/** Dimensions of the mini-map banner, pinned by `MiniMapLayoutTest`. */
object MiniMapStyle {
    /** Banner height on a route card (the card supplies the width). */
    const val BANNER_HEIGHT_DP = 140f

    /** Margin between the banner edge and the fitted route, clear of the style badge. */
    const val PADDING_DP = 12f

    /** The route line, and the wider casing drawn under it so it reads on the panel. */
    const val ROUTE_STROKE_DP = 4.5f
    const val CASING_STROKE_DP = 8f

    /** Start and finish dot radii, and the ring around them. */
    const val MARKER_RADIUS_DP = 6f
    const val MARKER_RING_DP = 2.5f

    /**
     * The line and its casing are the map's own route colours (the first route
     * of the carousel, orange, on a white casing), as `#RRGGBB`. A stored route
     * has no per-segment curviness, so the magenta curvy stretches the live map
     * shades in are not drawn here.
     */
    internal val ROUTE_HEX: String get() = routeColorHex(0)
    internal val CASING_HEX: String get() = ROUTE_CASING_COLOR

    /** An opaque Compose colour for a map-layer `#RRGGBB` constant. */
    internal fun mapColor(hex: String): Color {
        val rgb = rgbOf(hex)
        return Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }
}

/**
 * Small static map banner for a saved route: the stored polyline stroked on
 * a tonal panel with start and finish dots. The line and its casing use the
 * map's route colours ([MiniMapStyle.ROUTE_HEX] on [MiniMapStyle.CASING_HEX]),
 * like the live route; the panel and the dots are scheme roles (primary
 * container for the start, primary for the finish). Draws entirely from the decoded [geometry]: no
 * tiles, no network, safe inside a scrolling list.
 */
@Composable
fun RouteMiniMap(geometry: String, modifier: Modifier = Modifier) {
    // A corrupt/truncated stored polyline must not throw during composition
    // and tear down the whole saved-routes screen; render it as an empty banner.
    val points = remember(geometry) {
        runCatching { PolylineCodec.decode(geometry) }.getOrDefault(emptyList())
    }
    val colors = MaterialTheme.colorScheme
    // A soft map-like ground from the scheme: surface tinted towards the success green,
    // with a few paler "streets" so the banner reads as a map, not a grey slab.
    val bannerBackground = lerp(colors.surfaceContainerLow, CurveMapsTheme.colors.successContainer, 0.4f)
    val streets = colors.surface.copy(alpha = 0.75f)
    val casing = remember { MiniMapStyle.mapColor(MiniMapStyle.CASING_HEX) }
    val routeStroke = remember { MiniMapStyle.mapColor(MiniMapStyle.ROUTE_HEX) }
    val startMarker = colors.primaryContainer
    val endMarker = colors.primary
    Canvas(modifier = modifier) {
        drawRect(color = bannerBackground)
        val street = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
        val ground = Path().apply {
            moveTo(0f, size.height * 0.28f); lineTo(size.width, size.height * 0.4f)
            moveTo(0f, size.height * 0.82f); lineTo(size.width * 0.55f, size.height * 0.7f); lineTo(size.width, size.height * 0.9f)
            moveTo(size.width * 0.22f, 0f); lineTo(size.width * 0.3f, size.height)
            moveTo(size.width * 0.72f, 0f); lineTo(size.width * 0.66f, size.height)
        }
        drawPath(ground, streets, style = street)
        if (points.isEmpty()) return@Canvas
        val radius = MiniMapStyle.MARKER_RADIUS_DP.dp.toPx()
        val ring = MiniMapStyle.MARKER_RING_DP.dp.toPx()
        val fitted = MiniMapLayout.fit(points, size.width, size.height, MiniMapStyle.PADDING_DP.dp.toPx())
        if (fitted.size == 1) {
            drawCircle(color = casing, radius = radius + ring, center = fitted.first())
            drawCircle(color = endMarker, radius = radius, center = fitted.first())
            return@Canvas
        }
        val path = Path()
        fitted.forEachIndexed { index, offset ->
            if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
        }
        drawPath(
            path = path,
            color = casing,
            style = Stroke(width = MiniMapStyle.CASING_STROKE_DP.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path = path,
            color = routeStroke,
            style = Stroke(width = MiniMapStyle.ROUTE_STROKE_DP.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(color = casing, radius = radius + ring, center = fitted.first())
        drawCircle(color = startMarker, radius = radius, center = fitted.first())
        drawCircle(color = casing, radius = radius + ring, center = fitted.last())
        drawCircle(color = endMarker, radius = radius, center = fitted.last())
    }
}
