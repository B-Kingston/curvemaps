package com.curvemaps.app.routing.suggestion

import com.graphhopper.util.Instruction
import com.curvemaps.app.routing.navigation.GpsFix
import com.curvemaps.app.routing.navigation.RouteTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Synthetic roads on the equator, built in local metres (x east, y north),
 * so suggestion tests control geometry, curviness and speed exactly.
 */
internal object SyntheticRoutes {
    const val METRES_PER_DEGREE = 111_195.0

    data class Vertex(val x: Double, val y: Double, val speedMps: Double)

    /** Straight road along y = [y] from [fromX] to [toX], vertices every [step] m. */
    fun straight(fromX: Double, toX: Double, speedMps: Double, y: Double = 0.0, step: Double = 100.0): List<Vertex> {
        val count = ((toX - fromX) / step).toInt()
        return (0..count).map { Vertex(fromX + it * step, y, speedMps) }
    }

    /**
     * A twisty ridge road: leaves the baseline at [fromX], climbs to [offsetY]
     * north over 300 m, snakes (sinusoid, ~57 m corner radius at the apexes)
     * until 300 m before [toX], and drops back to the baseline at [toX].
     */
    fun twisty(fromX: Double, toX: Double, speedMps: Double, offsetY: Double = 200.0): List<Vertex> {
        val step = 20.0
        val ramp = 300.0
        val amplitude = 40.0
        val wavelength = 300.0
        val vertices = mutableListOf<Vertex>()
        var x = fromX
        while (x <= toX + 1e-6) {
            val y = when {
                x < fromX + ramp -> offsetY * (x - fromX) / ramp
                x > toX - ramp -> offsetY * (toX - x) / ramp
                else -> offsetY + amplitude * sin(2 * PI * (x - fromX - ramp) / wavelength)
            }
            vertices += Vertex(x, y, speedMps)
            x += step
        }
        return vertices
    }

    /** Joins legs, dropping each leg's first vertex when it repeats the previous end. */
    fun join(vararg legs: List<Vertex>): List<Vertex> = legs.fold(emptyList()) { acc, leg ->
        if (acc.isEmpty()) leg else acc + leg.drop(if (same(acc.last(), leg.first())) 1 else 0)
    }

    /**
     * A [RouteTrack] over [vertices] with model times from each segment's
     * speed. [turnAtVertex] adds one maneuver (sign [turnSign], name
     * [turnName]) at that vertex.
     */
    fun track(
        vertices: List<Vertex>,
        turnAtVertex: Int? = null,
        turnSign: Int = Instruction.TURN_LEFT,
        turnName: String = "Ridge Road",
    ): RouteTrack {
        val n = vertices.size
        val latitudes = DoubleArray(n) { vertices[it].y / METRES_PER_DEGREE }
        val longitudes = DoubleArray(n) { vertices[it].x / METRES_PER_DEGREE }
        fun segmentTime(i: Int): Double {
            val a = vertices[i]
            val b = vertices[i + 1]
            return kotlin.math.hypot(b.x - a.x, b.y - a.y) / a.speedMps
        }
        val turn = turnAtVertex?.takeIf { it in 1 until n - 1 }
        return if (turn == null) {
            RouteTrack.fromPath(
                latitudes, longitudes,
                listOf(Instruction.CONTINUE_ON_STREET, Instruction.FINISH),
                listOf("", ""),
                listOf(n - 1, 1),
                listOf((0 until n - 1).sumOf(::segmentTime), 0.0),
            )
        } else {
            RouteTrack.fromPath(
                latitudes, longitudes,
                listOf(Instruction.CONTINUE_ON_STREET, turnSign, Instruction.FINISH),
                listOf("", turnName, ""),
                listOf(turn, n - 1 - turn, 1),
                listOf((0 until turn).sumOf(::segmentTime), (turn until n - 1).sumOf(::segmentTime), 0.0),
            )
        }
    }

    /** Index of the first vertex at or after x = [x] (for placing turns). */
    fun indexAtX(vertices: List<Vertex>, x: Double): Int = vertices.indexOfFirst { it.x >= x - 1e-6 }

    fun fix(xMetres: Double, yMetres: Double, speedMps: Double, timestampMs: Long, accuracyM: Double = 5.0) =
        GpsFix(
            lat = yMetres / METRES_PER_DEGREE,
            lon = xMetres / METRES_PER_DEGREE,
            speedMps = speedMps,
            accuracyM = accuracyM,
            timestampMs = timestampMs,
        )

    private fun same(a: Vertex, b: Vertex) = kotlin.math.abs(a.x - b.x) < 1e-6 && kotlin.math.abs(a.y - b.y) < 1e-6
}
