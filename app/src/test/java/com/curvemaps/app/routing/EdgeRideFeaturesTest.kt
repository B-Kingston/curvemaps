package com.curvemaps.app.routing

import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.graphhopper.util.details.PathDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EdgeRideFeaturesTest {

    @Test
    fun reliefSumsEveryClimbAndDescentAndIgnoresTwoDimensionalLines() {
        val crest = line3D(0.0, 30.0, 60.0, 40.0, 50.0)
        assertEquals(30.0 + 30.0 + 20.0 + 10.0, reliefMeters(crest), 1e-9)
        assertEquals(0.0, reliefMeters(line3D(12.0, 12.0, 12.0)), 0.0)
        assertEquals(0.0, reliefMeters(PointList().apply { add(0.0, 0.0); add(0.0, 0.01) }), 0.0)
        assertEquals(0.0, reliefMeters(line3D(5.0)), 0.0)
    }

    @Test
    fun hillExposureRampsFromFlatToFullAndFloorsShortEdges() {
        // 1 km edges: relief per km equals the relief.
        assertEquals(0.0, hillExposure(line3D(0.0, HILL_FLAT_METERS_PER_KM, 0.0), 2_000.0), 1e-9)
        assertEquals(0.0, hillExposure(line3D(0.0, 5.0), 1_000.0), 0.0)
        // SRTM surface noise on flat roads (measured up to ~20 m/km) is flat.
        assertEquals(0.0, hillExposure(line3D(0.0, 9.0, 0.0, 9.0, 0.0), 2_000.0), 0.0)
        assertEquals(0.5, hillExposure(line3D(0.0, 45.0), 1_000.0), 1e-9)
        assertEquals(1.0, hillExposure(line3D(0.0, 70.0), 1_000.0), 1e-9)
        assertEquals(1.0, hillExposure(line3D(0.0, 300.0), 1_000.0), 0.0)
        // 10 m between junctions 20 m apart is not a 50% grade: it is measured
        // over the 250 m floor (40 m/km).
        assertEquals((40.0 - 20.0) / 50.0, hillExposure(line3D(0.0, 10.0), 20.0), 1e-9)
        assertEquals(0.0, hillExposure(PointList().apply { add(0.0, 0.0); add(0.0, 0.01) }, 1_000.0), 0.0)
    }

    @Test
    fun hillyFunExposureOnlyAddsFunAndStaysBounded() {
        assertEquals(0.0, hillyFunExposure(0.0, 0.0), 0.0)
        assertEquals(HILL_FUN_SHARE, hillyFunExposure(0.0, 1.0), 1e-12)
        assertEquals(1.0, hillyFunExposure(1.0, 1.0), 0.0)
        assertEquals(0.7, hillyFunExposure(0.7, 0.0), 1e-12)
        val random = Random(42)
        repeat(2_000) {
            val curve = random.nextDouble(-0.2, 1.2)
            val hill = random.nextDouble(-0.2, 1.2)
            val exposure = hillyFunExposure(curve, hill)
            assertTrue("$curve $hill", exposure in 0.0..1.0)
            assertTrue("never below the curve exposure", exposure >= curve.coerceIn(0.0, 1.0) - 1e-12)
            assertTrue("monotone in hills", hillyFunExposure(curve, hill + 0.1) >= exposure - 1e-12)
        }
    }

    @Test
    fun routeClimbIgnoresNoiseButKeepsRealClimbs() {
        // A 300 m climb with a 5 m wobble, a 15 m real dip, then on to 642 m.
        assertEquals(300.0 + (642.0 - 285.0), routeClimbMeters(line3D(0.0, 300.0, 295.0, 300.0, 285.0, 642.0)), 1e-9)
        // Metre-scale noise on a flat road sums to nothing.
        assertEquals(0.0, routeClimbMeters(line3D(20.0, 24.0, 19.0, 26.0, 21.0, 27.0, 22.0)), 0.0)
        // Pure descents climb nothing; 2D points climb nothing.
        assertEquals(0.0, routeClimbMeters(line3D(600.0, 400.0, 200.0, 10.0)), 0.0)
        assertEquals(0.0, routeClimbMeters(PointList().apply { add(0.0, 0.0); add(0.0, 0.01) }), 0.0)
        // A climb that ends the route counts once it clears the threshold.
        assertEquals(12.0, routeClimbMeters(line3D(0.0, 12.0)), 1e-9)
        assertEquals(0.0, routeClimbMeters(line3D(0.0, 8.0)), 0.0)
        val random = Random(7)
        repeat(500) {
            val heights = DoubleArray(random.nextInt(2, 40)) { random.nextDouble(0.0, 900.0) }
            val points = line3D(*heights)
            val climb = routeClimbMeters(points)
            val raw = (1 until heights.size).sumOf { maxOf(0.0, heights[it] - heights[it - 1]) }
            assertTrue("never more than the raw sum", climb <= raw + 1e-9)
            assertTrue("never negative", climb >= 0.0)
        }
    }

    @Test
    fun halfStopValuesAreParsedDefensively() {
        assertEquals(3, parseHalfStops("3"))
        assertEquals(3, parseHalfStops(" 3 "))
        assertEquals(0, parseHalfStops(null))
        assertEquals(0, parseHalfStops(""))
        assertEquals(0, parseHalfStops("two"))
        assertEquals(0, parseHalfStops(4))
        assertEquals(0, parseHalfStops("-5"))
        assertEquals(MAX_HALF_STOPS_PER_EDGE, parseHalfStops("100000"))
    }

    @Test
    fun routeStopsSumPerEdgeHalves() {
        // Edge into a signalled junction (1 half), out of it (1 half), then a
        // mid-edge stop sign (2 halves): two stops.
        val path = pathWithStops(1, 1, 0, 2)
        assertEquals(2, routeStopCount(path))
        assertNull(routeStopCount(ResponsePath()))
        assertEquals(0, routeStopCount(pathWithStops()))
    }

    @Test
    fun cardSummaryShowsOnlyTheEnabledFactorsWithData() {
        val path = pathWithStops(2, 2, 2).apply { points = line3D(0.0, 300.0, 295.0, 642.4) }
        assertNull(rideFactorSummary(path, RideFactors.NONE, hasElevation = true, hasStops = true))
        assertEquals("642 m climb", rideFactorSummary(path, RideFactors(elevation = true), hasElevation = true, hasStops = true))
        assertNull(rideFactorSummary(path, RideFactors(elevation = true), hasElevation = false, hasStops = true))
        assertEquals("3 stops", rideFactorSummary(path, RideFactors(stopsAndTowns = true), hasElevation = false, hasStops = true))
        assertEquals(
            "642 m climb · 3 stops",
            rideFactorSummary(path, RideFactors(elevation = true, stopsAndTowns = true), hasElevation = true, hasStops = true),
        )
        assertEquals("1 stop", rideFactorSummary(pathWithStops(2), RideFactors(stopsAndTowns = true), hasElevation = false, hasStops = true))
        // Requested without the stops detail (e.g. before Apply re-routed): nothing to claim.
        assertNull(rideFactorSummary(ResponsePath(), RideFactors(stopsAndTowns = true), hasElevation = false, hasStops = true))
        // A graph imported before moto_stops reads 0 everywhere: no false "0 stops".
        assertNull(rideFactorSummary(path, RideFactors(stopsAndTowns = true), hasElevation = false, hasStops = false))
        assertEquals(
            "642 m climb",
            rideFactorSummary(path, RideFactors(elevation = true, stopsAndTowns = true), hasElevation = true, hasStops = false),
        )
    }

    @Test
    fun gravelCountsOnlyTheSurfacesBlockUnpavedRemoves() {
        // Five 100 m legs along the equator: gravel, asphalt, missing, compacted, dirt.
        val path = pathWithSurfaces("gravel", "asphalt", "missing", "compacted", "dirt")
        assertEquals(300.0, routeGravelMeters(path)!!, 1.0)
        ComplexityWeighting.UNPAVED_SURFACES.forEach { surface ->
            assertEquals(surface.toString(), 100.0, routeGravelMeters(pathWithSurfaces(surface.toString()))!!, 1.0)
        }
        listOf("asphalt", "paved", "concrete", "paving_stones", "cobblestone", "wood", "missing", "other").forEach {
            assertEquals(it, 0.0, routeGravelMeters(pathWithSurfaces(it))!!, 0.0)
        }
        // Requested without the surface detail: nothing to claim.
        assertNull(routeGravelMeters(ResponsePath()))
    }

    @Test
    fun gravelTextRoundsLikeTheCardsDistances() {
        assertNull(gravelText(0.0))
        assertNull(gravelText(MIN_GRAVEL_NOTE_METERS - 1.0))
        assertEquals("100 m gravel", gravelText(MIN_GRAVEL_NOTE_METERS))
        assertEquals("400 m gravel", gravelText(437.0))
        assertEquals("900 m gravel", gravelText(949.0))
        assertEquals("1.0 km gravel", gravelText(951.0))
        assertEquals("4.7 km gravel", gravelText(4_730.0))
        assertEquals("9.9 km gravel", gravelText(9_940.0))
        assertEquals("10 km gravel", gravelText(9_960.0))
        assertEquals("23 km gravel", gravelText(23_400.0))
    }

    @Test
    fun cardSummaryLeadsWithTheGravelWhateverTheFactors() {
        val gravel = pathWithSurfaces("gravel", "gravel", "asphalt")
        assertEquals("200 m gravel", rideFactorSummary(gravel, RideFactors.NONE, hasElevation = false, hasStops = false))
        val withStops = gravel.apply { addPathDetails(mapOf(MOTO_STOPS_DETAIL to listOf(PathDetail(4).apply { first = 0; last = 3 }))) }
        assertEquals("200 m gravel · 2 stops", rideFactorSummary(withStops, RideFactors(stopsAndTowns = true), hasElevation = false, hasStops = true))
        // A gravel driveway is not worth a note; Block unpaved leaves none.
        assertNull(rideFactorSummary(pathWithSurfaces("asphalt", "asphalt"), RideFactors.NONE, hasElevation = false, hasStops = false))
    }

    @Test
    fun splitEdgePiecesSumTheirShareOfStops() {
        // A mid-edge via point splits one interior signal (2 halves) into two
        // pieces carrying 0.6 and 0.4 of it: still one stop, not two.
        val path = ResponsePath().apply {
            addPathDetails(mapOf(MOTO_STOPS_DETAIL to listOf(1.2, 0.8).mapIndexed { index, value ->
                PathDetail(value).apply { first = index; last = index + 1 }
            }))
        }
        assertEquals(1, routeStopCount(path))
    }

    private fun pathWithStops(vararg halves: Int) = ResponsePath().apply {
        addPathDetails(
            mapOf(
                MOTO_STOPS_DETAIL to halves.mapIndexed { index, value ->
                    PathDetail(value).apply {
                        first = index
                        last = index + 1
                    }
                },
            ),
        )
    }

    /** 100 m legs along the equator, one surface detail per leg. */
    private fun pathWithSurfaces(vararg surfaces: String) = ResponsePath().apply {
        points = PointList().apply { (0..surfaces.size).forEach { add(0.0, it * 0.0009) } }
        addPathDetails(mapOf(SURFACE_DETAIL to surfaces.mapIndexed { index, surface ->
            PathDetail(surface).apply { first = index; last = index + 1 }
        }))
    }

    /** Points 100 m apart along the equator with the given elevations. */
    private fun line3D(vararg elevations: Double) = PointList(elevations.size, true).apply {
        elevations.forEachIndexed { index, elevation -> add(0.0, index * 0.0009, elevation) }
    }
}
