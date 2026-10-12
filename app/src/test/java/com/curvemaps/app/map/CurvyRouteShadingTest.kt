package com.curvemaps.app.map

import com.curvemaps.app.routing.ROAD_CLASS_LINK_DETAIL
import com.curvemaps.app.routing.ROUNDABOUT_DETAIL
import com.graphhopper.ResponsePath
import com.graphhopper.util.Instruction
import com.graphhopper.util.InstructionList
import com.graphhopper.util.PointList
import com.graphhopper.util.details.PathDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class CurvyRouteShadingTest {

    @Test
    fun aStraightRouteKeepsItsOwnColour() {
        val shade = CurveShade.of(route(listOf(Leg.Straight(8_000.0))))
        assertEquals(listOf(CurveShadeStop(0.0, 0.0), CurveShadeStop(1.0, 0.0)), shade.gradientStops())
        assertEquals(0.0, shade.shadeAtMeters(4_000.0), 0.0)
    }

    @Test
    fun aTwistyStretchTakesTheCurvyColourAndTheLineChangesGradually() {
        val path = route(listOf(Leg.Straight(5_000.0), Leg.Twisty(3_000.0), Leg.Straight(5_000.0)))
        val shade = CurveShade.of(path)
        assertEquals(1.0, shade.shadeAtMeters(6_500.0), 0.0)
        assertEquals(0.0, shade.shadeAtMeters(2_500.0), 0.0)
        assertEquals(0.0, shade.shadeAtMeters(10_500.0), 0.0)
        // Rising into the bends and falling after them, never a hard edge.
        val approach = (3_000..6_500 step 50).map { shade.shadeAtMeters(it.toDouble()) }
        approach.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", b >= a - 1e-9) }
        val firstColour = (3_000..6_500 step 10).first { shade.shadeAtMeters(it.toDouble()) > 0.0 }
        val fullColour = (3_000..6_500 step 10).first { shade.shadeAtMeters(it.toDouble()) >= 1.0 }
        assertTrue("ramp ${fullColour - firstColour} m", fullColour - firstColour >= 300)
        val leave = (6_500..10_000 step 50).map { shade.shadeAtMeters(it.toDouble()) }
        leave.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", b <= a + 1e-9) }
    }

    @Test
    fun theLineEasesFromTheRouteColourToTheCurvyColour() {
        assertEquals(rgbOf("#FF5500"), shadedRouteRgb("#FF5500", 0.0))
        assertEquals(rgbOf(CURVY_ROUTE_COLOR), shadedRouteRgb("#FF5500", 1.0))
        val half = shadedRouteRgb("#FF5500", 0.5)
        listOf(16, 8, 0).forEach { shift ->
            val channel = half shr shift and 0xFF
            val ends = listOf(0xFF5500 shr shift and 0xFF, rgbOf(CURVY_ROUTE_COLOR) shr shift and 0xFF)
            assertTrue(channel in ends.min()..ends.max())
        }
        assertEquals("#D6006F", rgbHex(rgbOf(CURVY_ROUTE_COLOR)))
    }

    @Test
    fun theShadeRampsSmoothlyBetweenItsThresholds() {
        assertEquals(0.0, curveShade(0.0), 0.0)
        assertEquals(0.0, curveShade(CURVE_SHADE_START), 0.0)
        assertEquals(1.0, curveShade(CURVE_SHADE_FULL), 0.0)
        assertEquals(1.0, curveShade(1.0), 0.0)
        assertEquals(0.5, curveShade((CURVE_SHADE_START + CURVE_SHADE_FULL) / 2), 1e-9)
        val ramp = (0..100).map { curveShade(it / 100.0) }
        ramp.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
    }

    /** Roundabouts, ramps, motorways and town streets bend without being curvy riding. */
    @Test
    fun quietRoadsAreNeverShaded() {
        for ((name, value) in listOf(
            "road_class" to "residential",
            "road_class" to "service",
            "road_class" to "motorway",
            ROUNDABOUT_DETAIL to true,
            ROAD_CLASS_LINK_DETAIL to true,
        )) {
            val path = route(listOf(Leg.Straight(5_000.0), Leg.Twisty(3_000.0), Leg.Straight(5_000.0)))
            path.addPathDetails(mapOf(name to listOf(PathDetail(value).apply { first = 0; last = path.points.size() - 1 })))
            assertEquals("$name=$value", listOf(0.0), CurveShade.of(path).gradientStops().map { it.shade }.distinct())
        }
        // The same twisty road as a sealed secondary is shaded.
        val secondary = route(listOf(Leg.Straight(5_000.0), Leg.Twisty(3_000.0), Leg.Straight(5_000.0)))
        secondary.addPathDetails(mapOf("road_class" to listOf(PathDetail("secondary").apply { first = 0; last = secondary.points.size() - 1 })))
        assertEquals(1.0, CurveShade.of(secondary).shadeAtMeters(6_500.0), 0.0)
    }

    /** A town grid turns at every junction; those turns are not bends in the road. */
    @Test
    fun junctionTurnsAreNotCurves() {
        val grid = staircase(steps = 40, legMeters = 150.0)
        assertEquals("without instructions the turns read as bends", 1.0, CurveShade.of(grid).shadeAtMeters(3_000.0), 0.0)
        grid.setInstructions(InstructionList(null).apply {
            add(Instruction(Instruction.CONTINUE_ON_STREET, "Start", single(grid, 0)))
            for (vertex in 1 until grid.points.size() - 1) {
                val sign = if (vertex % 2 == 0) Instruction.TURN_LEFT else Instruction.TURN_RIGHT
                add(Instruction(sign, "Street $vertex", single(grid, vertex)))
            }
            add(Instruction(Instruction.FINISH, "End", single(grid, grid.points.size() - 1)))
        })
        assertEquals(listOf(0.0), CurveShade.of(grid).gradientStops().map { it.shade }.distinct())
    }

    /** MapLibre spreads 256 texels over the whole line; a short twisty road on a long ride must still show. */
    @Test
    fun aShortTwistyRoadOnALongRideStillReachesFullColour() {
        val shade = CurveShade.of(route(listOf(Leg.Straight(200_000.0), Leg.Twisty(2_000.0), Leg.Straight(200_000.0))))
        val strongest = shade.gradientStops().maxBy { it.shade }
        assertEquals(1.0, strongest.shade, 0.0)
        assertEquals(0.5, strongest.progress, 2.0 / CURVE_SHADE_SAMPLES)
        assertEquals(0.0, shade.shadeAtMeters(100_000.0), 0.0)
    }

    /** MapLibre's line progress is Mercator length: far from the equator a ground midpoint is not progress 0.5. */
    @Test
    fun gradientStopsFollowMercatorLengthWhileShadeAtMetersFollowsTheGround() {
        // Due south from the equator to 60°S, with twisty road at the ground midpoint (30°S).
        val north = PointList()
        val south = 60.0
        val twistyAt = 30.0
        var latitude = 0.0
        while (latitude > -twistyAt) {
            north.add(latitude, 150.0)
            latitude -= 0.05
        }
        val twisty = route(listOf(Leg.Twisty(3_000.0)), originLat = -twistyAt, originLon = 150.0, bearingDeg = 180.0)
        for (index in 0 until twisty.points.size()) north.add(twisty.points.getLat(index), twisty.points.getLon(index))
        latitude = north.getLat(north.size() - 1) - 0.05
        while (latitude > -south) {
            north.add(latitude, north.getLon(north.size() - 1))
            latitude -= 0.05
        }
        val shade = CurveShade.of(ResponsePath().setPoints(north))
        val strongest = shade.gradientStops().maxBy { it.shade }
        // ∫sec from 0 to 30° over ∫sec from 0 to 60°: ln(sec + tan) gives 0.549 / 1.317.
        assertEquals(0.417, strongest.progress, 0.01)
        // Read back by ground metres, the peak sits on the twisty road (one texel here is ~30 km).
        val groundMid = 30.0 * METERS_PER_DEGREE
        val peak = (0..6_700).map { it * 1_000.0 }.maxBy { shade.shadeAtMeters(it) }
        assertEquals(groundMid, peak, 30_000.0)
    }

    @Test
    fun reversingTheRouteMirrorsItsShade() {
        val path = route(listOf(Leg.Straight(3_000.0), Leg.Twisty(1_500.0), Leg.Straight(1_200.0), Leg.Twisty(600.0), Leg.Straight(4_000.0)))
        val reversed = PointList().apply {
            for (index in path.points.size() - 1 downTo 0) add(path.points.getLat(index), path.points.getLon(index))
        }
        val forward = CurveShade.of(path)
        val backward = CurveShade.of(ResponsePath().setPoints(reversed))
        val length = groundMeters(path)
        (0..(length / 100.0).toInt()).map { it * 100.0 }.forEach { meters ->
            assertEquals("at $meters m", forward.shadeAtMeters(meters), backward.shadeAtMeters(length - meters), 0.06)
        }
    }

    @Test
    fun gradientStopsAreOrderedBoundedAndCollapsed() {
        repeat(40) { seed ->
            val random = Random(seed)
            val legs = List(random.nextInt(1, 7)) {
                if (random.nextBoolean()) Leg.Straight(random.nextDouble(50.0, 20_000.0))
                else Leg.Twisty(random.nextDouble(50.0, 4_000.0), amplitude = random.nextDouble(5.0, 60.0))
            }
            val stops = CurveShade.of(route(legs)).gradientStops()
            assertEquals("seed $seed", 0.0, stops.first().progress, 0.0)
            assertEquals("seed $seed", 1.0, stops.last().progress, 0.0)
            assertTrue("seed $seed", stops.size <= CURVE_SHADE_SAMPLES + 1)
            stops.zipWithNext().forEach { (a, b) -> assertTrue("seed $seed", b.progress > a.progress) }
            assertTrue("seed $seed", stops.all { it.shade in 0.0..1.0 })
            // A stop is kept only where the shade changes on one side of it.
            stops.windowed(3).forEach { (a, b, c) -> assertTrue("seed $seed", a.shade != b.shade || b.shade != c.shade) }
        }
    }

    @Test
    fun aRouteTooShortToDrawHasNoShade() {
        assertTrue(CurveShade.of(ResponsePath().setPoints(PointList().apply { add(-27.0, 153.0) })).gradientStops().isEmpty())
        assertEquals(0.0, CurveShade.NONE.shadeAtMeters(10.0), 0.0)
    }

    private sealed interface Leg {
        val meters: Double

        data class Straight(override val meters: Double) : Leg

        /** Tight snake bends: 40 m amplitude over 200 m wavelengths is about a 25 m radius at each crest. */
        data class Twisty(override val meters: Double, val amplitude: Double = 40.0) : Leg
    }

    /**
     * Legs laid end to end along [bearingDeg] from the origin; straight legs as
     * 500 m segments, twisty ones sampled every 10 m.
     */
    private fun route(
        legs: List<Leg>,
        originLat: Double = -27.0,
        originLon: Double = 153.0,
        bearingDeg: Double = 90.0,
    ): ResponsePath {
        val along = mutableListOf(0.0)
        val across = mutableListOf(0.0)
        var start = 0.0
        for (leg in legs) {
            when (leg) {
                is Leg.Straight -> {
                    val steps = maxOf(1, (leg.meters / 500.0).toInt())
                    for (step in 1..steps) {
                        along += start + leg.meters * step / steps
                        across += 0.0
                    }
                }
                is Leg.Twisty -> {
                    val steps = maxOf(2, (leg.meters / 10.0).toInt())
                    for (step in 1..steps) {
                        val x = leg.meters * step / steps
                        along += start + x
                        across += leg.amplitude * sin(2 * PI * x / 200.0)
                    }
                }
            }
            start += leg.meters
        }
        val bearing = Math.toRadians(bearingDeg)
        val points = PointList()
        along.indices.forEach { index ->
            val east = along[index] * sin(bearing) - across[index] * cos(bearing)
            val north = along[index] * cos(bearing) + across[index] * sin(bearing)
            val latitude = originLat + north / METERS_PER_DEGREE
            points.add(latitude, originLon + east / (METERS_PER_DEGREE * cos(Math.toRadians(latitude))))
        }
        return ResponsePath().setPoints(points)
    }

    /** A grid walk: alternating east and north legs of [legMeters], a 90° turn at every vertex. */
    private fun staircase(steps: Int, legMeters: Double): ResponsePath {
        val points = PointList()
        var east = 0.0
        var north = 0.0
        points.add(-27.0, 153.0)
        repeat(steps) { step ->
            if (step % 2 == 0) east += legMeters else north += legMeters
            points.add(-27.0 + north / METERS_PER_DEGREE, 153.0 + east / (METERS_PER_DEGREE * cos(Math.toRadians(-27.0))))
        }
        return ResponsePath().setPoints(points)
    }

    /** The route's length as the shading measures it (equirectangular per segment). */
    private fun groundMeters(path: ResponsePath): Double = (1 until path.points.size()).sumOf { index ->
        val points = path.points
        val scale = cos(Math.toRadians((points.getLat(index - 1) + points.getLat(index)) / 2.0))
        kotlin.math.hypot((points.getLon(index) - points.getLon(index - 1)) * scale, points.getLat(index) - points.getLat(index - 1)) *
            METERS_PER_DEGREE
    }

    private fun single(path: ResponsePath, vertex: Int): PointList =
        PointList().apply { add(path.points.getLat(vertex), path.points.getLon(vertex)) }

    private companion object {
        const val METERS_PER_DEGREE = 111_195.0
    }
}
