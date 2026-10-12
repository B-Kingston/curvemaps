package com.curvemaps.app.routing

import com.graphhopper.util.PointList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class RouteCurvinessTest {
    private val metresPerDegree = 111_195.0

    /** 2 km straight, then 2 km of ~57 m-radius snake, then 2 km straight. */
    private val xs = (0..300).map { it * 20.0 }
    private val ys = xs.map { x -> if (x in 2_000.0..4_000.0) 40.0 * sin(2 * PI * (x - 2_000.0) / 300.0) else 0.0 }
    private val latitudes = ys.map { it / metresPerDegree }.toDoubleArray()
    private val longitudes = xs.map { it / metresPerDegree }.toDoubleArray()

    @Test
    fun curvyMetresOverTheWholeLineMatchTheCardFraction() {
        val points = PointList(latitudes.size, false)
        latitudes.indices.forEach { points.add(latitudes[it], longitudes[it]) }
        var total = 0.0
        for (i in 1 until latitudes.size) {
            total += kotlin.math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        }
        val curvy = curvyDistanceMeters(latitudes, longitudes, 0, latitudes.lastIndex)
        assertEquals(curvyDistanceFraction(points), curvy / total, 0.002)
    }

    @Test
    fun straightStretchesHaveNoCurvyMetresAndRangesAreAdditive() {
        assertEquals(0.0, curvyDistanceMeters(latitudes, longitudes, 0, 90), 0.0)
        assertEquals(0.0, curvyDistanceMeters(latitudes, longitudes, 210, 300), 0.0)
        val snake = curvyDistanceMeters(latitudes, longitudes, 95, 205)
        assertEquals(snake, curvyDistanceMeters(latitudes, longitudes, 0, 300), 1.0)
        assertEquals(0.0, curvyDistanceMeters(latitudes, longitudes, 5, 5), 0.0)
    }

    /** The graded corners the map's curve shading reads are the card's corners, weighed by tightness. */
    @Test
    fun segmentCornersGradeExactlyTheCardsCurvySegments() {
        val corners = segmentCorners(latitudes, longitudes)
        assertEquals(latitudes.size - 1, corners.weights.size)
        val curvy = corners.lengths.indices.filter { corners.weights[it] > 0.0 }.sumOf { corners.lengths[it] }
        assertEquals(curvyDistanceMeters(latitudes, longitudes, 0, latitudes.lastIndex), curvy, 1e-6)
        assertTrue(corners.weights.all { it in 0.0..1.0 })
        // Graded by the router's radius bands (175/100/60/30 m) over the tightest band's 2.0.
        assertTrue(corners.weights.all { weight -> listOf(0.0, 0.5, 0.65, 0.8, 1.0).any { abs(it - weight) < 1e-9 } })
        assertTrue(corners.weights.max() >= 0.8)

        // A junction vertex is no corner: its two segments keep only their other ends' corners.
        val bend = corners.weights.indices.first { corners.weights[it] > 0.0 } + 1
        val withJunction = segmentCorners(latitudes, longitudes) { it == bend }
        assertEquals(0.0, withJunction.weights[bend - 1], 0.0)
        corners.weights.indices.filter { it != bend - 1 && it != bend }.forEach {
            assertEquals(corners.weights[it], withJunction.weights[it], 0.0)
        }
    }
}
