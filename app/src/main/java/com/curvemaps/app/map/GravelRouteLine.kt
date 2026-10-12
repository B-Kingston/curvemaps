package com.curvemaps.app.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.curvemaps.app.routing.SURFACE_DETAIL
import com.curvemaps.app.routing.isUnpavedSurface
import com.graphhopper.ResponsePath
import com.graphhopper.util.DistanceCalcEarth
import com.graphhopper.util.PointList
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Random
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * The shown route's gravel: every stretch of known unpaved road (the surfaces
 * the card's "4.7 km gravel" note counts and Block unpaved roads removes) is
 * painted over the route line as a grey line with light and dark speckles, so
 * a rider choosing between styles sees where the gravel is, not only how much.
 * Each surface change blends: the route line's colour fades out over the gravel
 * along a short ramp centred on the change (half on the sealed side, half on
 * the gravel), so the colour flows into the gravel instead of a hard edge. Each
 * ramp fades from the colour the line has where it starts, the route colour or
 * its curve shade (CurvyRouteShading.kt); a gradient's colour is fixed per
 * layer, so the ramps are grouped by that shade in [GRAVEL_BLEND_LEVELS] levels,
 * each level drawn by its own layer only while a ramp uses it. The ramp
 * should look the same length at any zoom, so the stretches are built once per
 * zoom band, each band with a ramp [GRAVEL_FADE_DP] long at its zoom, and each
 * band has its own layer. Colour map only: the B&W ride map stays one solid
 * white route.
 */

internal const val GRAVEL_SOURCE_ID = "route-gravel"
internal const val GRAVEL_PATTERN_IMAGE = "route-gravel-speckles"
internal const val GRAVEL_BASE_COLOR = "#8E8B85"
internal const val GRAVEL_LIGHT_SPECKLE_COLOR = "#B4B0A9"
internal const val GRAVEL_DARK_SPECKLE_COLOR = "#706D67"
internal const val GRAVEL_BAND_PROPERTY = "gravel_band"

/**
 * The pattern tile, in dp. MapLibre clips a patterned line to its pattern's
 * height and samples past the tile in the antialiased edge, so the tile is a
 * little taller than the line (the grains stay inside the line).
 */
internal const val GRAVEL_PATTERN_WIDTH_DP = 24f
internal const val GRAVEL_PATTERN_HEIGHT_DP = 12f
internal const val GRAVEL_LINE_WIDTH_DP = 8f
private const val GRAVEL_SPECKLES = 14
private const val GRAVEL_SPECKLE_SEED = 0x6A7E1L

/** On-screen length of one sealed ↔ gravel blend. */
internal const val GRAVEL_FADE_DP = 28.0

/**
 * Curve-shade levels a blend's colour snaps to (tenths): within 0.05 of the
 * line beside it, a few RGB steps at most.
 */
internal const val GRAVEL_BLEND_LEVELS = 11

/** MapLibre's metres per dp at zoom 0 on the equator (512 dp tiles). */
private const val METRES_PER_DP_Z0 = 40_075_016.686 / 512.0

/**
 * A zoom range sharing one ramp length, measured at [referenceZoom]; the
 * ramp's on-screen length stays within 2x of [GRAVEL_FADE_DP] inside the band.
 */
internal enum class GravelBand(val minZoom: Float, val maxZoom: Float, val referenceZoom: Double) {
    WIDE(0f, 11f, 10.0),
    REGION(11f, 13f, 12.0),
    LOCAL(13f, 15f, 14.0),
    STREET(15f, 24f, 16.0);

    val layerId: String get() = "route-gravel-line-${name.lowercase()}"

    /** The layer drawing this band's blends at curve-shade [level] (0..[GRAVEL_BLEND_LEVELS] - 1). */
    fun blendLayerId(level: Int): String = "route-gravel-blend-${name.lowercase()}-$level"

    fun fadeMeters(latitude: Double): Double =
        GRAVEL_FADE_DP * METRES_PER_DP_Z0 * cos(Math.toRadians(latitude)) / 2.0.pow(referenceZoom)
}

internal val GRAVEL_LAYER_IDS: List<String> = GravelBand.entries.flatMap { band ->
    (0 until GRAVEL_BLEND_LEVELS).map(band::blendLayerId) + band.layerId
}

/**
 * Vertex ranges of [path] on known unpaved road, adjacent unpaved details
 * merged into one stretch (path details share their boundary vertex); empty
 * without a [SURFACE_DETAIL] or with details that do not fit the points.
 */
internal fun gravelStretches(path: ResponsePath): List<IntRange> {
    val lastVertex = path.points.size() - 1
    val details = path.pathDetails[SURFACE_DETAIL].orEmpty()
    val stretches = mutableListOf<IntRange>()
    for (detail in details) {
        if (!isUnpavedSurface(detail.value)) continue
        val first = detail.first.coerceAtLeast(0)
        val last = detail.last.coerceAtMost(lastVertex)
        if (last <= first) continue
        val previous = stretches.lastOrNull()
        if (previous != null && previous.last >= first) {
            stretches[stretches.lastIndex] = previous.first..maxOf(previous.last, last)
        } else {
            stretches += first..last
        }
    }
    return stretches
}

/**
 * One sealed ↔ gravel blend: the route's colour is drawn over the gravel from
 * [sealedM] (fully opaque) to [gravelM] (fully clear), metres along the route.
 */
internal data class GravelBlend(val sealedM: Double, val gravelM: Double)

/** The gravel line spans (metres along the route) and the blends over their ends. */
internal data class GravelOverlay(
    val gravel: List<ClosedFloatingPointRange<Double>>,
    val blends: List<GravelBlend>,
)

/**
 * The overlay for gravel at [stretches] (metres along a route [routeM] long)
 * with blends [fadeM] long. Each blend is centred on its surface change: the
 * gravel line reaches half a blend into the sealed road and the route colour
 * fades out over it, linearly, so the drawn gravel weighs exactly its real
 * length. A route end gets no blend (the gravel starts or ends there at full
 * strength), a stretch shorter than a blend blends over its own length, and
 * stretches whose sealed gap is too short for two blends join, so blends never
 * overlap.
 */
internal fun gravelOverlay(stretches: List<ClosedFloatingPointRange<Double>>, routeM: Double, fadeM: Double): GravelOverlay {
    val joined = mutableListOf<ClosedFloatingPointRange<Double>>()
    for (stretch in stretches.sortedBy { it.start }) {
        val previous = joined.lastOrNull()
        if (previous != null && stretch.start - previous.endInclusive < fadeM) {
            joined[joined.lastIndex] = previous.start..maxOf(previous.endInclusive, stretch.endInclusive)
        } else {
            joined += stretch
        }
    }
    val gravel = mutableListOf<ClosedFloatingPointRange<Double>>()
    val blends = mutableListOf<GravelBlend>()
    for (stretch in joined) {
        val half = minOf(fadeM, stretch.endInclusive - stretch.start) / 2.0
        val start = if (stretch.start > 0.0) stretch.start - half else stretch.start
        val end = if (stretch.endInclusive < routeM) stretch.endInclusive + half else stretch.endInclusive
        gravel += start..end
        if (stretch.start > 0.0) blends += GravelBlend(start, stretch.start + half)
        if (stretch.endInclusive < routeM) blends += GravelBlend(end, stretch.endInclusive - half)
    }
    return GravelOverlay(gravel, blends)
}

/** Cumulative metres at each vertex of [points]. */
private fun cumulativeMeters(points: PointList): DoubleArray {
    val cumulative = DoubleArray(points.size())
    for (index in 1 until points.size()) {
        cumulative[index] = cumulative[index - 1] + DistanceCalcEarth.DIST_EARTH.calcDist(
            points.getLat(index - 1), points.getLon(index - 1), points.getLat(index), points.getLon(index),
        )
    }
    return cumulative
}

/** The first vertex at or past [meters] along the route (the last vertex past the end). */
private fun vertexAtOrAfter(cumulative: DoubleArray, meters: Double): Int {
    var low = 0
    var high = cumulative.lastIndex
    while (low < high) {
        val middle = (low + high) ushr 1
        if (cumulative[middle] >= meters) high = middle else low = middle + 1
    }
    return low
}

/** The route between [fromM] and [toM] metres along it, interpolated at both ends. */
private fun slice(points: PointList, cumulative: DoubleArray, fromM: Double, toM: Double): List<Point> {
    fun at(meters: Double): Point {
        val index = vertexAtOrAfter(cumulative, meters)
        if (index == 0) return Point.fromLngLat(points.getLon(0), points.getLat(0))
        val span = cumulative[index] - cumulative[index - 1]
        val fraction = if (span > 0.0) ((meters - cumulative[index - 1]) / span).coerceIn(0.0, 1.0) else 1.0
        return Point.fromLngLat(
            points.getLon(index - 1) + fraction * (points.getLon(index) - points.getLon(index - 1)),
            points.getLat(index - 1) + fraction * (points.getLat(index) - points.getLat(index - 1)),
        )
    }
    val inner = mutableListOf<Point>()
    var index = vertexAtOrAfter(cumulative, fromM)
    while (index < cumulative.size && cumulative[index] < toM) {
        if (cumulative[index] > fromM) inner += Point.fromLngLat(points.getLon(index), points.getLat(index))
        index++
    }
    return listOf(at(fromM)) + inner + listOf(at(toM))
}

internal const val GRAVEL_KIND_PROPERTY = "gravel_kind"
internal const val GRAVEL_KIND_LINE = "gravel"
internal const val GRAVEL_KIND_BLEND = "blend"
internal const val GRAVEL_BLEND_LEVEL_PROPERTY = "gravel_blend_level"

/** The curve-shade level (0..[GRAVEL_BLEND_LEVELS] - 1) nearest [shade] (0..1). */
internal fun gravelBlendLevel(shade: Double): Int =
    (shade.coerceIn(0.0, 1.0) * (GRAVEL_BLEND_LEVELS - 1)).roundToInt()

/**
 * The gravel lines and blends of [path]; [shadeAt] is the route line's curve
 * shade (0..1) a given number of metres along it, and each blend takes the
 * level where it starts on the sealed road.
 */
internal fun gravelFeatureCollection(path: ResponsePath?, shadeAt: (Double) -> Double): FeatureCollection {
    val points = path?.points
    if (path == null || points == null || points.size() < 2) return FeatureCollection.fromFeatures(emptyList())
    val cumulative = cumulativeMeters(points)
    val stretches = gravelStretches(path).map { cumulative[it.first]..cumulative[it.last] }
    if (stretches.isEmpty()) return FeatureCollection.fromFeatures(emptyList())
    val latitude = points.getLat(points.size() / 2)
    fun feature(line: List<Point>, band: GravelBand, kind: String) =
        Feature.fromGeometry(LineString.fromLngLats(line)).apply {
            addStringProperty(GRAVEL_BAND_PROPERTY, band.name)
            addStringProperty(GRAVEL_KIND_PROPERTY, kind)
        }
    val features = GravelBand.entries.flatMap { band ->
        val overlay = gravelOverlay(stretches, cumulative.last(), band.fadeMeters(latitude))
        overlay.gravel.filter { it.endInclusive > it.start }.map { span ->
            feature(slice(points, cumulative, span.start, span.endInclusive), band, GRAVEL_KIND_LINE)
        } + overlay.blends.filter { it.sealedM != it.gravelM }.map { blend ->
            // Every blend runs sealed → gravel, so one line-progress gradient fits them all.
            val line = slice(points, cumulative, minOf(blend.sealedM, blend.gravelM), maxOf(blend.sealedM, blend.gravelM))
            feature(if (blend.sealedM < blend.gravelM) line else line.reversed(), band, GRAVEL_KIND_BLEND).apply {
                addNumberProperty(GRAVEL_BLEND_LEVEL_PROPERTY, gravelBlendLevel(shadeAt(blend.sealedM)))
            }
        }
    }
    return FeatureCollection.fromFeatures(features)
}

private fun bandFilter(band: GravelBand, kind: String): Expression = Expression.all(
    Expression.eq(Expression.get(GRAVEL_BAND_PROPERTY), Expression.literal(band.name)),
    Expression.eq(Expression.get(GRAVEL_KIND_PROPERTY), Expression.literal(kind)),
)

private fun gravelLayer(band: GravelBand): LineLayer =
    LineLayer(band.layerId, GRAVEL_SOURCE_ID)
        .withFilter(bandFilter(band, GRAVEL_KIND_LINE))
        .withProperties(
            PropertyFactory.linePattern(GRAVEL_PATTERN_IMAGE),
            PropertyFactory.lineWidth(GRAVEL_LINE_WIDTH_DP),
            // Butt caps: the gravel's ends sit under its blends, which must cover them exactly.
            PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        .also {
            it.minZoom = band.minZoom
            it.maxZoom = band.maxZoom
        }

/** [rgb] (0xRRGGBB), opaque on the sealed end of a blend and clear on the gravel end. */
private fun blendGradient(rgb: Int): Expression {
    val red = rgb shr 16 and 0xFF
    val green = rgb shr 8 and 0xFF
    val blue = rgb and 0xFF
    return Expression.interpolate(
        Expression.linear(),
        Expression.lineProgress(),
        Expression.stop(0, Expression.rgba(red, green, blue, 1)),
        Expression.stop(1, Expression.rgba(red, green, blue, 0)),
    )
}

private fun blendLayer(band: GravelBand, level: Int, rgb: Int): LineLayer =
    LineLayer(band.blendLayerId(level), GRAVEL_SOURCE_ID)
        .withFilter(
            Expression.all(
                bandFilter(band, GRAVEL_KIND_BLEND),
                Expression.eq(Expression.get(GRAVEL_BLEND_LEVEL_PROPERTY), Expression.literal(level)),
            ),
        )
        .withProperties(
            PropertyFactory.lineGradient(blendGradient(rgb)),
            PropertyFactory.lineWidth(GRAVEL_LINE_WIDTH_DP),
            PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        .also {
            it.minZoom = band.minZoom
            it.maxZoom = band.maxZoom
        }

/**
 * A seamless speckle tile: a grey ground with light and dark grains, each
 * drawn again one tile to either side so the tile's ends meet without a seam.
 */
internal fun buildGravelPatternBitmap(density: Float): Bitmap {
    val scale = if (density.isFinite() && density > 0f) density else 1f
    val width = (GRAVEL_PATTERN_WIDTH_DP * scale).roundToInt().coerceAtLeast(1)
    val height = (GRAVEL_PATTERN_HEIGHT_DP * scale).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.density = (scale * 160f).roundToInt().coerceAtLeast(1)
    val canvas = Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.parseColor(GRAVEL_BASE_COLOR))
    val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor(GRAVEL_LIGHT_SPECKLE_COLOR) }
    val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor(GRAVEL_DARK_SPECKLE_COLOR) }
    // A fixed seed: the same grains on every draw, so the route never shimmers.
    val random = Random(GRAVEL_SPECKLE_SEED)
    repeat(GRAVEL_SPECKLES) { index ->
        val x = random.nextFloat() * width
        val inset = (GRAVEL_PATTERN_HEIGHT_DP - GRAVEL_LINE_WIDTH_DP * 0.76f) / 2f
        val y = (inset + random.nextFloat() * GRAVEL_LINE_WIDTH_DP * 0.76f) * scale
        val radius = (0.45f + random.nextFloat() * 0.4f) * scale
        val paint = if (index % 2 == 0) light else dark
        for (shift in listOf(-width.toFloat(), 0f, width.toFloat())) canvas.drawCircle(x + shift, y, radius, paint)
    }
    return bitmap
}

/**
 * Draws [path]'s gravel over the route line, each blend fading from the line's
 * colour where it starts: [routeColorHex] eased toward the curvy colour by the
 * line's shade [shadeAt] metres along it (or clears it for null / no gravel).
 */
internal fun Style.drawRouteGravel(path: ResponsePath?, routeColorHex: String, shadeAt: (Double) -> Double) {
    val collection = gravelFeatureCollection(path, shadeAt)
    if (collection.features().isNullOrEmpty()) {
        clearRouteGravel()
        return
    }
    val source = getSourceAs<GeoJsonSource>(GRAVEL_SOURCE_ID)
    if (source != null) {
        source.setGeoJson(collection)
    } else {
        // Line metrics give each blend its line-progress gradient.
        addSource(GeoJsonSource(GRAVEL_SOURCE_ID, GeoJsonOptions().withLineMetrics(true)).apply { setGeoJson(collection) })
    }
    if (getImage(GRAVEL_PATTERN_IMAGE) == null) {
        addImage(GRAVEL_PATTERN_IMAGE, buildGravelPatternBitmap(Resources.getSystem().displayMetrics.density))
    }
    val levels = collection.features().orEmpty()
        .filter { it.getStringProperty(GRAVEL_KIND_PROPERTY) == GRAVEL_KIND_BLEND }
        .map { it.getStringProperty(GRAVEL_BAND_PROPERTY) to it.getNumberProperty(GRAVEL_BLEND_LEVEL_PROPERTY).toInt() }
        .toSet()
    // Gravel lines above the route, each band's blends above its gravel; a level no blend uses has no layer.
    var below = ROUTE_FOCUS_LINE_ID.takeIf { getLayer(it) != null }
    fun place(layer: LineLayer) {
        below?.let { addLayerAbove(layer, it) } ?: addLayer(layer)
    }
    GravelBand.entries.forEach { band ->
        if (getLayer(band.layerId) == null) place(gravelLayer(band))
        below = band.layerId
        for (level in 0 until GRAVEL_BLEND_LEVELS) {
            val id = band.blendLayerId(level)
            if ((band.name to level) !in levels) {
                removeLayer(id)
                continue
            }
            val rgb = shadedRouteRgb(routeColorHex, level.toDouble() / (GRAVEL_BLEND_LEVELS - 1))
            val existing = getLayerAs<LineLayer>(id)
            if (existing == null) place(blendLayer(band, level, rgb))
            else existing.setProperties(PropertyFactory.lineGradient(blendGradient(rgb)))
            below = id
        }
    }
}

internal fun Style.clearRouteGravel() {
    GRAVEL_LAYER_IDS.forEach(::removeLayer)
    removeSource(GRAVEL_SOURCE_ID)
}
