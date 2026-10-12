package com.curvemaps.app.routing

import com.graphhopper.util.Parameters
import com.graphhopper.util.shapes.GHPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRequestsTest {

    @Test
    fun firstAttemptCarriesStyleTogglesAndNoSiblingPenalty() {
        val request = buildGhRequest(
            from = GHPoint(-27.0, 153.0),
            to = GHPoint(-28.0, 152.0),
            style = RideStyle.FASTEST,
            blockUnpaved = false,
            avoidMotorways = true,
            attempt = 0,
            siblingEdgeIds = emptySet(),
        )
        assertEquals(MOTORCYCLE_PROFILE, request.profile)
        assertEquals(listOf("edge_id", "road_class", SURFACE_DETAIL, MOTO_LANES_DETAIL, ROUNDABOUT_DETAIL, ROAD_CLASS_LINK_DETAIL), request.pathDetails)
        assertTrue(request.hints.getBool(Parameters.CH.DISABLE, false))
        assertEquals(0, request.hints.getInt(MOTO_RIDE_STYLE, -1))
        assertTrue(request.hints.getBool(AVOID_MOTORWAYS, false))
        assertFalse(request.hints.getBool(BLOCK_UNPAVED, true))
        // Every style fills the carousel, Fastest included.
        assertEquals(Parameters.Algorithms.ALT_ROUTE, request.algorithm)
        assertEquals(10, request.hints.getInt(Parameters.Algorithms.AltRoute.MAX_PATHS, -1))
        assertEquals(
            AlternativePolicy.SIBLING_MAX_SHARE,
            request.hints.getDouble(Parameters.Algorithms.AltRoute.MAX_SHARE, -1.0),
            0.0,
        )
        assertFalse(request.hints.has(MOTO_SIBLING_EDGES))
        assertFalse(request.hints.has(MOTO_SIBLING_EDGE_PENALTY))
    }

    @Test
    fun rideFactorHintsAppearOnlyWhenEnabled() {
        fun request(factors: RideFactors) = buildGhRequest(
            from = GHPoint(-27.0, 153.0),
            to = GHPoint(-28.0, 152.0),
            style = RideStyle.CURVY,
            blockUnpaved = false,
            avoidMotorways = false,
            attempt = 0,
            siblingEdgeIds = emptySet(),
            alternatives = false,
            factors = factors,
        )
        val plain = request(RideFactors.NONE)
        // Ordinary requests stay exactly what they were before the factors existed.
        assertFalse(plain.hints.has(MOTO_HILLS))
        assertFalse(plain.hints.has(MOTO_STOPS_AND_TOWNS))
        assertFalse(plain.hints.has(ELEVATION_WAY_POINT_MAX_DISTANCE))
        assertEquals(listOf("edge_id", "road_class", SURFACE_DETAIL, MOTO_LANES_DETAIL, ROUNDABOUT_DETAIL, ROAD_CLASS_LINK_DETAIL), plain.pathDetails)

        val hills = request(RideFactors(elevation = true))
        assertTrue(hills.hints.getBool(MOTO_HILLS, false))
        // Simplification must keep crests and dips the climb readout counts.
        val vertical = hills.hints.getDouble(ELEVATION_WAY_POINT_MAX_DISTANCE, Double.MAX_VALUE)
        assertTrue("got $vertical", vertical < CLIMB_HYSTERESIS_METERS)
        assertFalse(hills.hints.has(MOTO_STOPS_AND_TOWNS))
        assertEquals(listOf("edge_id", "road_class", SURFACE_DETAIL, MOTO_LANES_DETAIL, ROUNDABOUT_DETAIL, ROAD_CLASS_LINK_DETAIL), hills.pathDetails)

        val stops = request(RideFactors(stopsAndTowns = true))
        assertFalse(stops.hints.has(MOTO_HILLS))
        assertTrue(stops.hints.getBool(MOTO_STOPS_AND_TOWNS, false))
        // The card counts stops from the per-edge detail, and gravel from the surface detail on every request.
        assertEquals(
            listOf("edge_id", "road_class", SURFACE_DETAIL, MOTO_LANES_DETAIL, ROUNDABOUT_DETAIL, ROAD_CLASS_LINK_DETAIL, MOTO_STOPS_DETAIL),
            stops.pathDetails,
        )
    }

    @Test
    fun retryPenalisesSiblingRoadsAndWidensThePool() {
        val request = buildGhRequest(
            from = GHPoint(-27.0, 153.0),
            to = GHPoint(-28.0, 152.0),
            style = RideStyle.TWISTY,
            blockUnpaved = true,
            avoidMotorways = false,
            attempt = 1,
            siblingEdgeIds = setOf(3, 7),
        )
        assertEquals(RideStyle.TWISTY.level, request.hints.getInt(MOTO_RIDE_STYLE, -1))
        assertEquals(Parameters.Algorithms.ALT_ROUTE, request.algorithm)
        assertEquals(18, request.hints.getInt(Parameters.Algorithms.AltRoute.MAX_PATHS, -1))
        assertEquals(
            minOf(0.98, AlternativePolicy.SIBLING_MAX_SHARE + 0.20),
            request.hints.getDouble(Parameters.Algorithms.AltRoute.MAX_SHARE, -1.0),
            1e-12,
        )
        assertEquals(
            AlternativePolicy.maxAlternativeWeight(1),
            request.hints.getDouble(Parameters.Algorithms.AltRoute.MAX_WEIGHT, -1.0),
            0.0,
        )
        // Exploration never widens on retries (it is the long-corridor cost).
        assertEquals(
            AlternativePolicy.MAX_EXPLORATION_FACTOR,
            request.hints.getDouble("alternative_route.max_exploration_factor", -1.0),
            0.0,
        )
        assertEquals(
            0.10,
            request.hints.getDouble("alternative_route.min_plateau_factor", -1.0),
            0.0,
        )
        assertEquals(
            AlternativePolicy.siblingRoadPenalty(1),
            request.hints.getDouble(MOTO_SIBLING_EDGE_PENALTY, -1.0),
            0.0,
        )
        assertEquals(setOf(3, 7), request.hints.getObject(MOTO_SIBLING_EDGES, emptySet<Int>()))
        assertTrue(request.hints.getBool(BLOCK_UNPAVED, false))
        assertFalse(request.hints.getBool(AVOID_MOTORWAYS, true))
    }

    @Test
    fun requestDependsOnlyOnItsInputs() {
        // No hidden state: identical inputs give identical hints, which is
        // what makes a style's carousel independent of visiting order.
        fun build() = buildGhRequest(
            from = GHPoint(-27.0, 153.0),
            to = GHPoint(-28.0, 152.0),
            style = RideStyle.MAX_TWISTY,
            blockUnpaved = false,
            avoidMotorways = false,
            attempt = 2,
            siblingEdgeIds = setOf(1),
        )
        assertEquals(build().hints.toMap(), build().hints.toMap())
    }

    @Test
    fun viaPointRequestRoutesEveryLegWithoutAlternativeAlgorithm() {
        val via = GHPoint(-27.5, 152.5)
        val request = buildGhRequest(
            from = GHPoint(-27.0, 153.0),
            to = GHPoint(-28.0, 152.0),
            style = RideStyle.BALANCED,
            blockUnpaved = false,
            avoidMotorways = true,
            attempt = 0,
            siblingEdgeIds = emptySet(),
            viaPoints = listOf(via),
        )

        assertEquals(3, request.points.size)
        assertEquals(via, request.points[1])
        assertEquals(RideStyle.BALANCED.level, request.hints.getInt(MOTO_RIDE_STYLE, -1))
        assertTrue(request.hints.getBool(AVOID_MOTORWAYS, false))
        assertNotEquals(Parameters.Algorithms.ALT_ROUTE, request.algorithm)
    }

    @Test
    fun singleRouteRequestSkipsTheAlternativeAlgorithmAtEveryStop() {
        RideStyle.entries.forEach { style ->
            val request = buildGhRequest(
                from = GHPoint(-27.0, 153.0),
                to = GHPoint(-28.0, 152.0),
                style = style,
                blockUnpaved = true,
                avoidMotorways = false,
                attempt = 0,
                siblingEdgeIds = emptySet(),
                alternatives = false,
            )
            assertEquals(2, request.points.size)
            assertEquals(style.level, request.hints.getInt(MOTO_RIDE_STYLE, -1))
            assertTrue(request.hints.getBool(BLOCK_UNPAVED, false))
            assertNotEquals(style.label, Parameters.Algorithms.ALT_ROUTE, request.algorithm)
            assertFalse(request.hints.has(Parameters.Algorithms.AltRoute.MAX_PATHS))
        }
    }

    @Test
    fun aCurveWeightOverrideTravelsOnlyWhenGiven() {
        fun build(curveWeight: Double?) = buildGhRequest(
            from = GHPoint(-27.0, 153.0),
            to = GHPoint(-28.0, 152.0),
            style = RideStyle.MOSTLY_FAST,
            blockUnpaved = false,
            avoidMotorways = false,
            attempt = 0,
            siblingEdgeIds = emptySet(),
            alternatives = false,
            curveWeight = curveWeight,
        )
        assertFalse(build(null).hints.has(MOTO_CURVE_WEIGHT))
        val nudged = build(0.45)
        assertEquals(0.45, nudged.hints.getDouble(MOTO_CURVE_WEIGHT, -1.0), 0.0)
        // The stop stays the same; only its curve preference moves.
        assertEquals(RideStyle.MOSTLY_FAST.level, nudged.hints.getInt(MOTO_RIDE_STYLE, -1))
        assertNotEquals(Parameters.Algorithms.ALT_ROUTE, nudged.algorithm)
    }

    @Test
    fun passThroughTravelsOnlyWithALookAroundVia() {
        fun build(passThrough: Boolean) = buildGhRequest(
            from = GHPoint(-27.0, 153.0),
            to = GHPoint(-28.0, 152.0),
            style = RideStyle.BALANCED,
            blockUnpaved = false,
            avoidMotorways = false,
            attempt = 0,
            siblingEdgeIds = emptySet(),
            viaPoints = listOf(GHPoint(-27.5, 152.4)),
            alternatives = false,
            curveWeight = RideStyle.BALANCED.curveWeight,
            passThrough = passThrough,
        )
        // Imported GPX shaping points keep GraphHopper's default (a U-turn at
        // a via is allowed); the look-around search forbids it.
        assertFalse(build(false).hints.has(Parameters.Routing.PASS_THROUGH))
        assertTrue(build(true).hints.getBool(Parameters.Routing.PASS_THROUGH, false))
        assertEquals(3, build(true).points.size)
        assertNotEquals(Parameters.Algorithms.ALT_ROUTE, build(true).algorithm)
    }

    @Test
    fun followedTrackHeadingsTravelOnePerViaWithBareEnds() {
        val vias = listOf(GHPoint(-27.1, 153.0), GHPoint(-27.2, 153.0))
        fun build(headings: List<Double>) = buildGhRequest(
            from = GHPoint(-27.0, 153.0),
            to = GHPoint(-27.3, 153.0),
            style = RideStyle.FASTEST,
            blockUnpaved = false,
            avoidMotorways = false,
            attempt = 0,
            siblingEdgeIds = emptySet(),
            viaPoints = vias,
            viaHeadings = headings,
        )
        val headed = build(listOf(180.0, Double.NaN)).headings
        assertEquals(4, headed.size)
        assertTrue(headed[0].isNaN())
        assertEquals(180.0, headed[1], 0.0)
        assertTrue(headed[2].isNaN())
        assertTrue(headed[3].isNaN())
        // No heading at all, or only unknown ones: GraphHopper's default snap.
        assertTrue(build(emptyList()).headings.isEmpty())
        assertTrue(build(listOf(Double.NaN, Double.NaN)).headings.isEmpty())
        try {
            build(listOf(90.0))
            throw AssertionError("a heading list must match the via points")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun rebuildStartHeadingLeadsOneHeadingPerPoint() {
        fun build(vias: List<GHPoint>, viaHeadings: List<Double>, start: Double) = buildGhRequest(
            from = GHPoint(-27.0, 153.0),
            to = GHPoint(-27.3, 153.0),
            style = RideStyle.FASTEST,
            blockUnpaved = false,
            avoidMotorways = false,
            attempt = 0,
            siblingEdgeIds = emptySet(),
            viaPoints = vias,
            alternatives = false,
            viaHeadings = viaHeadings,
            startHeading = start,
        )
        val vias = listOf(GHPoint(-27.1, 153.0), GHPoint(-27.2, 153.0))
        // GraphHopper rejects a request whose heading count differs from its
        // point count, so a start heading alone fills the vias with NaN.
        val direct = build(emptyList(), emptyList(), 30.0)
        assertEquals(direct.points.size, direct.headings.size)
        assertEquals(30.0, direct.headings[0], 0.0)
        assertTrue(direct.headings[1].isNaN())
        val shaped = build(vias, emptyList(), 30.0)
        assertEquals(shaped.points.size, shaped.headings.size)
        assertEquals(30.0, shaped.headings[0], 0.0)
        assertTrue(shaped.headings.drop(1).all { it.isNaN() })
        val followed = build(vias, listOf(180.0, Double.NaN), 30.0).headings
        assertEquals(listOf(30.0, 180.0), followed.take(2))
        assertTrue(followed.drop(2).all { it.isNaN() })
        assertTrue("an unknown heading leaves the default snap", build(vias, emptyList(), Double.NaN).headings.isEmpty())
    }
}
