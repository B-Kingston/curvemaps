package com.curvemaps.app.routing

import com.graphhopper.util.PointList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class RideStyleTest {

    @Test
    fun levelsAreBoundedAndOldUnboundedLevelsClamp() {
        assertEquals(RideStyle.FASTEST, RideStyle.fromLevel(0.0))
        assertEquals(RideStyle.FASTEST, RideStyle.fromLevel(-3.0))
        assertEquals(RideStyle.FASTEST, RideStyle.fromLevel(Double.NaN))
        assertEquals(RideStyle.MOSTLY_FAST, RideStyle.fromLevel(1.4f))
        assertEquals(RideStyle.BALANCED, RideStyle.fromLevel(2.0))
        assertEquals(RideStyle.TWISTY, RideStyle.fromLevel(4.0))
        assertEquals(RideStyle.MAX_TWISTY, RideStyle.fromLevel(6.0))
        // Saved rides from the old endless dial carry levels like 9 or 17.
        assertEquals(RideStyle.MAX_TWISTY, RideStyle.fromLevel(17.0))
        RideStyle.entries.forEach { assertEquals(it, RideStyle.fromLevel(it.level.toDouble())) }
        assertEquals(RideStyle.entries.lastIndex, RideStyle.MAX_LEVEL)
        assertEquals(6, RideStyle.MAX_LEVEL)
    }

    @Test
    fun midStopsSitHalfwayBetweenTheirCalibratedNeighbours() {
        val stops = RideStyle.entries
        assertEquals(
            listOf(RideStyle.FASTEST, RideStyle.BALANCED, RideStyle.TWISTY, RideStyle.MAX_TWISTY),
            stops.filter { it.ordinal % 2 == 0 },
        )
        listOf(RideStyle.MOSTLY_FAST, RideStyle.CURVY, RideStyle.VERY_TWISTY).forEach { mid ->
            val below = stops[mid.ordinal - 1]
            val above = stops[mid.ordinal + 1]
            assertEquals((below.curveWeight + above.curveWeight) / 2, mid.curveWeight, 1e-9)
            assertEquals((below.maxAlternativeTimeRatio + above.maxAlternativeTimeRatio) / 2, mid.maxAlternativeTimeRatio, 1e-9)
            assertEquals((below.maxAlternativeDistanceRatio + above.maxAlternativeDistanceRatio) / 2, mid.maxAlternativeDistanceRatio, 1e-9)
        }
    }

    @Test
    fun stopsHaveDistinctLabelsAndStrictlyGrowingCurveWeights() {
        assertEquals(RideStyle.entries.size, RideStyle.entries.map { it.label }.toSet().size)
        assertEquals(0.0, RideStyle.FASTEST.curveWeight, 0.0)
        RideStyle.entries.zipWithNext().forEach { (lower, higher) ->
            assertTrue(higher.curveWeight > lower.curveWeight)
        }
    }

    @Test
    fun straightRoutesAreNotCurvyAndHairpinsAre() {
        assertEquals(0.0, curvyDistanceFraction(polyline((0..20).map { it * 100.0 to 0.0 })), 0.0)
        // A 40 m-radius circle sampled every 10 degrees: all curve.
        val circle = (0..36).map { step ->
            val angle = Math.toRadians(step * 10.0)
            40.0 * cos(angle) to 40.0 * sin(angle)
        }
        assertEquals(1.0, curvyDistanceFraction(polyline(circle)), 1e-9)
        // Half straight, half circle: roughly half curvy.
        val straight = (0..10).map { -1_000.0 + it * 100.0 to -40.0 }
        val half = curvyDistanceFraction(polyline(straight + circle.drop(28) + circle.take(9)))
        assertTrue("got $half", half in 0.05..0.6)
        assertEquals(0.0, curvyDistanceFraction(polyline(listOf(0.0 to 0.0))), 0.0)
        assertEquals(0.0, curvyDistanceFraction(PointList()), 0.0)
    }

    @Test
    fun curvyFractionIsBoundedAndDirectionIndependentForRandomRoutes() {
        val random = Random(42)
        repeat(200) {
            var x = 0.0
            var y = 0.0
            var heading = 0.0
            val points = (0 until 40).map {
                heading += random.nextDouble(-1.2, 1.2)
                val step = random.nextDouble(1.0, 300.0)
                x += step * cos(heading)
                y += step * sin(heading)
                x to y
            }
            val forward = curvyDistanceFraction(polyline(points))
            val reverse = curvyDistanceFraction(polyline(points.reversed()))
            assertTrue(forward in 0.0..1.0)
            assertEquals(forward, reverse, 1e-9)
        }
    }

    /** Local metres east/north around Brisbane, so latitude scaling is exercised. */
    private fun polyline(metres: List<Pair<Double, Double>>) = PointList().apply {
        val originLat = -27.5
        val scale = cos(Math.toRadians(originLat))
        metres.forEach { (east, north) ->
            add(originLat + north / METERS_PER_DEGREE, 153.0 + east / (METERS_PER_DEGREE * scale))
        }
    }
}
