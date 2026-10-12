package com.curvemaps.app.map

import com.curvemaps.app.routing.ComplexityWeighting
import com.curvemaps.app.routing.ROAD_CLASS_LINK_DETAIL
import com.curvemaps.app.routing.ROUNDABOUT_DETAIL
import com.curvemaps.app.routing.segmentCorners
import com.graphhopper.ResponsePath
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.util.Instruction
import org.maplibre.android.style.expressions.Expression
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The shown route's most curvy sections: the route line eases from its own
 * colour toward [CURVY_ROUTE_COLOR] where the road bends most, so a rider
 * moving the ride-style slider sees where each ride's curves are, not only
 * the card's "N% curvy".
 *
 * The measure is the card's corner definition graded by tightness
 * ([segmentCorners]), averaged over [CURVE_SHADE_HALF_WINDOW_M] either side
 * with a triangular weight so the colour changes gradually, then eased from
 * [CURVE_SHADE_START] (route colour) to [CURVE_SHADE_FULL] (curvy colour).
 * Bends that are not riding curves stay out: turns at junctions (every
 * manoeuvre GraphHopper announces), roundabouts, ramps, motorways and town
 * streets (the classes the router gives no curve credit). The thresholds come
 * from 300 rater plans (2,100 rides): the median Max twisty ride passes
 * [CURVE_SHADE_START] on 8% of its length (p90 24%), the median Fastest ride
 * on 2%, and a quarter of Fastest rides nowhere. With the softening below,
 * `CurveShadeProbe` measures Max twisty Brisbane → Kenilworth tinted (a
 * quarter of the way or more) on 47% of its length and fully coloured on 14%
 * (Mount Mee, Nonmus and Maleny–Kenilworth roads), its Fastest ride on 3% and
 * nowhere fully.
 *
 * MapLibre draws a line gradient from a [CURVE_SHADE_SAMPLES]-texel ramp
 * spread over the whole line by its Mercator length, so the profile is
 * sampled once per texel, each texel the strongest shade along its share of
 * the line (a short twisty stretch on a long ride still shows). Each shaded
 * stretch then fades out over [CURVE_SHADE_SOFTEN_TEXELS] x 2 texels beyond
 * it: the planner frames the whole route, so a fade measured in texels looks
 * the same length on screen for a 10 km hop and a 300 km ride, while the full
 * colour stays on the curvy road itself.
 */

/** The colour the route line eases toward on its most curvy sections. */
internal const val CURVY_ROUTE_COLOR = "#D6006F"

/** Half the stretch of road one point's shade averages, metres. */
internal const val CURVE_SHADE_HALF_WINDOW_M = 600.0

/** Averaged corner weight below which the line keeps its own colour. */
internal const val CURVE_SHADE_START = 0.2

/** Averaged corner weight from which the line is fully [CURVY_ROUTE_COLOR]. */
internal const val CURVE_SHADE_FULL = 0.4

/** Texels in MapLibre's line-gradient ramp. */
internal const val CURVE_SHADE_SAMPLES = 256

/** How far, in texels, each shaded stretch is widened and then blurred. */
internal const val CURVE_SHADE_SOFTEN_TEXELS = 2

/** Road classes whose bends are not riding curves: the router's town streets, and motorways. */
private val QUIET_ROAD_CLASSES: Set<String> =
    (ComplexityWeighting.TOWN_STREET_CLASSES + RoadClass.MOTORWAY).map { it.toString() }.toSet()

/** Manoeuvres that keep to the same road; every other announced sign is a junction. */
private val ROAD_FOLLOWING_SIGNS = setOf(Instruction.CONTINUE_ON_STREET, Instruction.REACHED_VIA, Instruction.FINISH)

/** One gradient stop: [progress] along the line (0..1) and the [shade] there (0 route colour, 1 curvy). */
internal data class CurveShadeStop(val progress: Double, val shade: Double)

/**
 * How curvy a route is along its length. [shadeAtMeters] reads it like the
 * rendered line does, so overlays drawn over the route (gravel blends) can
 * match the colour underneath.
 */
internal class CurveShade private constructor(
    /** Cumulative ground metres at each vertex. */
    private val groundEnds: DoubleArray,
    /** Cumulative Mercator-scaled metres at each vertex: MapLibre's line progress. */
    private val mercatorEnds: DoubleArray,
    /** The shade at each texel centre, (k + 0.5) / samples of the Mercator length. */
    private val texels: DoubleArray,
) {
    /** The shade [meters] along the route (ground metres from the start), as MapLibre draws it. */
    fun shadeAtMeters(meters: Double): Double {
        if (texels.isEmpty()) return 0.0
        val total = mercatorEnds.last()
        if (total <= 0.0) return texels[0]
        val position = (convert(meters, groundEnds, mercatorEnds) / total) * texels.size - 0.5
        val below = position.toInt().coerceIn(0, texels.lastIndex)
        if (position <= 0.0) return texels[0]
        if (below >= texels.lastIndex) return texels.last()
        val fraction = position - below
        return texels[below] + (texels[below + 1] - texels[below]) * fraction
    }

    /**
     * The line-gradient stops: texel k's shade at k / samples (where MapLibre
     * evaluates it), the last repeated at 1, and runs of equal stops collapsed.
     */
    fun gradientStops(): List<CurveShadeStop> {
        if (texels.isEmpty()) return emptyList()
        val shades = texels.toList() + texels.last()
        return shades.indices
            .filter { k -> k == 0 || k == shades.lastIndex || shades[k] != shades[k - 1] || shades[k] != shades[k + 1] }
            .map { k -> CurveShadeStop(k.toDouble() / texels.size, shades[k]) }
    }

    companion object {
        /** No shading: a route too short to draw. */
        val NONE = CurveShade(DoubleArray(1), DoubleArray(1), DoubleArray(0))

        fun of(path: ResponsePath, samples: Int = CURVE_SHADE_SAMPLES): CurveShade {
            val points = path.points
            val count = points.size()
            if (count < 2) return NONE
            val latitudes = DoubleArray(count) { points.getLat(it) }
            val longitudes = DoubleArray(count) { points.getLon(it) }
            val quiet = quietSegments(path, count - 1)
            val junctions = junctionVertices(path, count)
            val corners = segmentCorners(latitudes, longitudes) { vertex ->
                junctions[vertex] || quiet[vertex - 1] || quiet[vertex]
            }
            val weights = DoubleArray(corners.weights.size) { if (quiet[it]) 0.0 else corners.weights[it] }
            val mercator = DoubleArray(corners.lengths.size) { segment ->
                corners.lengths[segment] / cos(Math.toRadians((latitudes[segment] + latitudes[segment + 1]) / 2.0))
            }
            return of(corners.lengths, mercator, weights, samples)
        }

        /**
         * The shade of segments [lengths] metres long ([mercatorLengths] as
         * MapLibre measures them) with corner [weights] (0..1).
         */
        fun of(lengths: DoubleArray, mercatorLengths: DoubleArray, weights: DoubleArray, samples: Int): CurveShade {
            val groundEnds = cumulative(lengths)
            val mercatorEnds = cumulative(mercatorLengths)
            val total = mercatorEnds.last()
            if (lengths.isEmpty() || total <= 0.0 || samples < 1) return NONE
            val profile = SmoothedCorners(groundEnds, weights, CURVE_SHADE_HALF_WINDOW_M)
            val texel = total / samples
            fun ground(mercator: Double) = convert(mercator, mercatorEnds, groundEnds)
            var segment = 0
            val strongest = DoubleArray(samples) { k ->
                val from = k * texel
                val to = from + texel
                var weight = maxOf(profile.at(ground(from)), profile.at(ground(from + texel / 2)), profile.at(ground(to)))
                // Every segment centred inside this texel: on a long ride a texel spans many bends.
                while (segment < lengths.size && (mercatorEnds[segment] + mercatorEnds[segment + 1]) / 2 < from) segment++
                var inside = segment
                while (inside < lengths.size && (mercatorEnds[inside] + mercatorEnds[inside + 1]) / 2 <= to) {
                    weight = max(weight, profile.at((groundEnds[inside] + groundEnds[inside + 1]) / 2))
                    inside++
                }
                curveShade(weight)
            }
            return CurveShade(groundEnds, mercatorEnds, softened(strongest))
        }

        /**
         * [shades] widened by [CURVE_SHADE_SOFTEN_TEXELS] (a running maximum)
         * and blurred as far again with a triangular kernel: every stretch
         * keeps its own shade and fades out beyond it, never into it. Rounded
         * to hundredths so equal stretches collapse into one gradient stop.
         */
        private fun softened(shades: DoubleArray): DoubleArray {
            val reach = CURVE_SHADE_SOFTEN_TEXELS
            fun at(array: DoubleArray, index: Int) = array[index.coerceIn(0, array.lastIndex)]
            val widened = DoubleArray(shades.size) { k -> (k - reach..k + reach).maxOf { at(shades, it) } }
            return DoubleArray(shades.size) { k ->
                var sum = 0.0
                var total = 0.0
                for (offset in -reach..reach) {
                    val weight = (reach + 1 - abs(offset)).toDouble()
                    sum += weight * at(widened, k + offset)
                    total += weight
                }
                (sum / total * 100.0).roundToInt() / 100.0
            }
        }

        private fun cumulative(lengths: DoubleArray): DoubleArray {
            val ends = DoubleArray(lengths.size + 1)
            for (index in lengths.indices) ends[index + 1] = ends[index] + lengths[index]
            return ends
        }

        /** [value] on the [from] axis mapped onto the [to] axis, linearly within each segment. */
        private fun convert(value: Double, from: DoubleArray, to: DoubleArray): Double {
            if (value <= from.first()) return to.first()
            if (value >= from.last()) return to.last()
            var low = 0
            var high = from.lastIndex
            while (high - low > 1) {
                val middle = (low + high) ushr 1
                if (from[middle] <= value) low = middle else high = middle
            }
            val span = from[high] - from[low]
            val fraction = if (span > 0.0) (value - from[low]) / span else 0.0
            return to[low] + (to[high] - to[low]) * fraction
        }
    }
}

/**
 * The shade for an averaged corner weight: none below [CURVE_SHADE_START],
 * full from [CURVE_SHADE_FULL], eased (smoothstep) in between.
 */
internal fun curveShade(averagedWeight: Double): Double {
    val linear = ((averagedWeight - CURVE_SHADE_START) / (CURVE_SHADE_FULL - CURVE_SHADE_START)).coerceIn(0.0, 1.0)
    return linear * linear * (3.0 - 2.0 * linear)
}

/**
 * Corner weights averaged around any point with a triangular kernel
 * [halfWindow] metres either side, normalised by the road inside the window
 * (so a route's ends average only the road they have).
 */
private class SmoothedCorners(private val ends: DoubleArray, private val weights: DoubleArray, private val halfWindow: Double) {
    fun at(meters: Double): Double {
        val from = meters - halfWindow
        val to = meters + halfWindow
        var segment = firstSegmentEndingAfter(from)
        var mass = 0.0
        var weighted = 0.0
        while (segment < weights.size && ends[segment] < to) {
            val start = max(ends[segment], from)
            val end = minOf(ends[segment + 1], to)
            if (end > start) {
                val share = kernel(end - meters) - kernel(start - meters)
                mass += share
                weighted += share * weights[segment]
            }
            segment++
        }
        return if (mass > 0.0) weighted / mass else 0.0
    }

    /** The integral of the triangle 1 - |u| / halfWindow from 0 to [offset]. */
    private fun kernel(offset: Double): Double = offset - offset * abs(offset) / (2.0 * halfWindow)

    private fun firstSegmentEndingAfter(meters: Double): Int {
        var low = 0
        var high = weights.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ends[middle + 1] > meters) high = middle else low = middle + 1
        }
        return low
    }
}

/** Segments whose bends are not riding curves: roundabouts, ramps, motorways and town streets. */
internal fun quietSegments(path: ResponsePath, segments: Int): BooleanArray {
    val quiet = BooleanArray(segments)
    fun mark(detailName: String, isQuiet: (Any?) -> Boolean) {
        for (detail in path.pathDetails[detailName].orEmpty()) {
            if (!isQuiet(detail.value)) continue
            for (segment in detail.first.coerceAtLeast(0) until detail.last.coerceAtMost(segments)) quiet[segment] = true
        }
    }
    mark("road_class") { it?.toString() in QUIET_ROAD_CLASSES }
    mark(ROUNDABOUT_DETAIL) { it == true }
    mark(ROAD_CLASS_LINK_DETAIL) { it == true }
    return quiet
}

/**
 * Vertices where the route turns from one road onto another: the first point
 * of every manoeuvre GraphHopper announces (instruction points are contiguous
 * slices of the path). None for a path without instructions.
 */
internal fun junctionVertices(path: ResponsePath, count: Int): BooleanArray {
    val junctions = BooleanArray(count)
    val instructions = runCatching { path.instructions }.getOrNull() ?: return junctions
    var cursor = 0
    instructions.forEachIndexed { index, instruction ->
        if (index > 0 && instruction.sign !in ROAD_FOLLOWING_SIGNS && cursor < count) junctions[cursor] = true
        cursor += instruction.points.size()
    }
    return junctions
}

/** [routeColorHex] eased toward [CURVY_ROUTE_COLOR] by [shade] (0..1), as 0xRRGGBB. */
internal fun shadedRouteRgb(routeColorHex: String, shade: Double): Int {
    val from = rgbOf(routeColorHex)
    val to = rgbOf(CURVY_ROUTE_COLOR)
    fun channel(shift: Int): Int {
        val start = from shr shift and 0xFF
        val end = to shr shift and 0xFF
        return (start + (end - start) * shade.coerceIn(0.0, 1.0)).roundToInt().coerceIn(0, 255)
    }
    return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

internal fun rgbOf(hex: String): Int = hex.removePrefix("#").toInt(16) and 0xFFFFFF

internal fun rgbHex(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

/** The focused route line's colour along its length (MapLibre's `line-progress`). */
internal fun curvyRouteGradient(shade: CurveShade, routeColorHex: String): Expression {
    val stops = shade.gradientStops().ifEmpty { listOf(CurveShadeStop(0.0, 0.0), CurveShadeStop(1.0, 0.0)) }
    return Expression.interpolate(
        Expression.linear(),
        Expression.lineProgress(),
        *stops.map { stop ->
            val rgb = shadedRouteRgb(routeColorHex, stop.shade)
            Expression.stop(stop.progress, Expression.rgb(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF))
        }.toTypedArray(),
    )
}
