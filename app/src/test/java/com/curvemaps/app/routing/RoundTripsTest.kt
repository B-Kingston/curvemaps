package com.curvemaps.app.routing

import com.graphhopper.ResponsePath
import com.graphhopper.util.DistanceCalcEarth
import com.graphhopper.util.Instruction
import com.graphhopper.util.InstructionList
import com.graphhopper.util.PointList
import com.graphhopper.util.details.PathDetail
import com.graphhopper.util.shapes.GHPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class RoundTripsTest {
    private val start = GHPoint(-27.3727, 152.8864)

    @Test
    fun aLoopIsATriangleOfTwoCornersAroundItsBearing() {
        val shape = RoundTrips.shape(start, 90.0, 10_000.0)
        assertEquals(2, shape.vias.size)
        val half = RoundTrips.APEX_DEGREES / 2
        listOf(90.0 - half, 90.0 + half).zip(shape.vias).forEach { (bearing, via) ->
            assertEquals(10_000.0, DistanceCalcEarth.DIST_EARTH.calcDist(start.lat, start.lon, via.lat, via.lon), 15.0)
            assertEquals(bearing, bearingTo(via), 0.2)
        }
        // A corner may move up to 30% of the radius to reach a passable road, within bounds.
        assertEquals(3_000.0, shape.searchMeters, 1e-9)
        assertEquals(ThroughRoadSnap.RING_STEP_METRES, RoundTrips.shape(start, 0.0, 1_000.0).searchMeters, 1e-9)
        assertEquals(RoundTrips.MAX_CORNER_SEARCH_METERS, RoundTrips.shape(start, 0.0, 90_000.0).searchMeters, 1e-9)
    }

    @Test
    fun everyTierLooksBetweenTheDirectionsOfTheTiersBefore() {
        val bearings = RoundTrips.TIER_BEARING_OFFSETS.indices.map { tier ->
            RoundTrips.tier(start, tier, 10_000.0).map { it.bearing }
        }
        bearings.forEach { assertEquals(RoundTrips.BEARINGS, it.size) }
        assertEquals((0 until 8).map { it * 45.0 }, bearings.first())
        val all = bearings.flatten().map { (it % 360.0 + 360.0) % 360.0 }
        assertEquals("no tier repeats a direction", all.size, all.toSet().size)
    }

    @Test
    fun theFirstTierIsSizedFromTheWish() {
        val hour = RoundTrips.baseRadius(RoundTripLength.Time(60))
        assertEquals(55_000.0 / (RoundTrips.PERIMETER_FACTOR * RoundTrips.ROAD_FACTOR), hour, 1e-6)
        assertTrue(RoundTrips.baseRadius(RoundTripLength.Time(120)) > hour)
        assertTrue(RoundTrips.baseRadius(RoundTripLength.Distance(150)) > RoundTrips.baseRadius(RoundTripLength.Distance(50)))
        assertEquals(3.0, RoundTrips.PERIMETER_FACTOR, 1e-9)
        // A short wish keeps its corners clear of the start's own streets.
        assertEquals(RoundTrips.MIN_RADIUS_METERS, RoundTrips.baseRadius(RoundTripLength.Distance(1)), 1e-9)
    }

    @Test
    fun theNextTierIsRescaledByTheMedianSizeTheLastOneMeasured() {
        val base = 10_000.0
        fun loops(vararg ratios: Double) = ratios.map { ratio -> loopOf(ratio) }
        assertEquals(base / 1.5, RoundTrips.recalibrated(base, base, loops(1.4, 1.5, 3.0)), 1e-6)
        assertEquals(base / 0.6, RoundTrips.recalibrated(base, base, loops(0.6, 0.6)), 1e-6)
        // Close enough keeps the radius; nothing measured keeps it too.
        assertEquals(base, RoundTrips.recalibrated(base, base, loops(1.1, 0.95)), 0.0)
        assertEquals(base, RoundTrips.recalibrated(base, base, emptyList()), 0.0)
        // One step never more than halves or doubles, and the radius stays in bounds.
        assertEquals(base / 2.0, RoundTrips.recalibrated(base, base, loops(9.0)), 1e-6)
        assertEquals(base * RoundTrips.MAX_RADIUS_FACTOR, RoundTrips.recalibrated(base * 2.5, base, loops(0.3)), 1e-6)
    }

    @Test
    fun everyStopIsALoopOfItsOwnBackToTheStart() = runBlocking {
        val country = Country { Direction(kmh = 60.0) }
        val options = RoundTrips.resolve(start, RoundTripLength.Distance(50), country::loop)
        assertEquals(RideStyle.entries, options.keys.toList())
        options.values.forEach { option ->
            assertNull(option.repeats)
            assertNotNull(option.loop)
            assertEquals(DistinctRideStyles.Budget.STYLE, option.budget)
        }
        assertPairwiseDifferent(options.values.map { it.path })
    }

    @Test
    fun fastestTakesTheQuickLoopAndTheTwistiestStopACurvyOne() = runBlocking {
        // Quick straight roads north, slow curvy ones all over the south.
        val country = Country { bearing ->
            when {
                bearing == 0.0 -> Direction(kmh = 90.0, curvy = 0.0)
                bearing in 90.0..270.0 -> Direction(kmh = 45.0, curvy = 0.8)
                else -> Direction(kmh = 60.0, curvy = 0.2)
            }
        }
        val options = RoundTrips.resolve(start, RoundTripLength.Distance(50), country::loop)
        assertEquals(0.0, options.getValue(RideStyle.FASTEST).loop!!.bearing, 0.0)
        assertTrue(options.getValue(RideStyle.MAX_TWISTY).loop!!.bearing in 90.0..270.0)
        assertTrue(
            curviness(options.getValue(RideStyle.MAX_TWISTY).path) > curviness(options.getValue(RideStyle.FASTEST).path) + 0.3,
        )
    }

    @Test
    fun aStopIsNoStraighterThanTheStopBelowWhileACurvyLoopIsLeft() = runBlocking {
        // Balanced likes the quick, nearly straight loops best, but a slow
        // curvy one is still left after Mostly fast took the other.
        val country = Country { bearing ->
            when (bearing) {
                0.0 -> Direction(kmh = 90.0, curvy = 0.0)
                45.0 -> Direction(kmh = 60.0, curvy = 0.6)
                90.0 -> Direction(kmh = 30.0, curvy = 0.6)
                else -> Direction(kmh = 75.0, curvy = 0.1)
            }
        }
        val options = RoundTrips.resolve(start, RoundTripLength.Distance(50), country::loop)
        assertEquals(0.0, options.getValue(RideStyle.FASTEST).loop!!.bearing, 0.0)
        assertEquals(45.0, options.getValue(RideStyle.MOSTLY_FAST).loop!!.bearing, 0.0)
        assertEquals(90.0, options.getValue(RideStyle.BALANCED).loop!!.bearing, 0.0)
        // Without the floor the same scores pick a straight loop.
        val loops = RoundTrips.tier(start, 0, RoundTrips.baseRadius(RoundTripLength.Distance(50))).mapNotNull { shape ->
            RoundTrips.measure(shape, RoundTripLength.Distance(50), country.loop(RideStyle.BALANCED, shape))
        }
        val offered = listOf(RideStyle.FASTEST, RideStyle.MOSTLY_FAST).map { AlternativePolicy.cachedRoute(options.getValue(it).path) }
        assertTrue(RoundTrips.best(loops, offered, DistinctRideStyles.Budget.STYLE)!!.curviness < 0.3)
        // Once no curvy loop is left, a stop takes the curviest one there is.
        val curvy = options.getValue(RideStyle.CURVY)
        assertTrue(curviness(curvy.path) < curviness(options.getValue(RideStyle.BALANCED).path))
        assertEquals(DistinctRideStyles.Budget.STYLE, curvy.budget)
        assertPairwiseDifferent(options.values.map { it.path })
    }

    @Test
    fun loopsTooLongForTheWishSendTheStopToARescaledTier() = runBlocking {
        // Every road winds twice as much as the first tier assumed.
        val country = Country { Direction(kmh = 60.0, winding = 2.0 * RoundTrips.ROAD_FACTOR) }
        val length = RoundTripLength.Distance(50)
        val options = RoundTrips.resolve(start, length, country::loop)
        val fastest = options.getValue(RideStyle.FASTEST)
        assertEquals(DistinctRideStyles.Budget.STYLE, fastest.budget)
        assertEquals(RoundTrips.baseRadius(length) / 2.0, fastest.loop!!.radiusMeters, 1.0)
        assertTrue("tier 1 bearing", (fastest.loop!!.bearing - 22.5) % 45.0 == 0.0)
        assertEquals(1.0, length.ratio(fastest.path), 0.01)
    }

    @Test
    fun aDirectionWithoutALoopIsLeftOut() = runBlocking {
        val country = Country { bearing -> if (bearing < 180.0) null else Direction(kmh = 60.0) }
        val options = RoundTrips.resolve(start, RoundTripLength.Time(60), country::loop)
        options.values.forEach { assertTrue(it.loop!!.bearing >= 180.0) }
        assertTrue(options.values.all { it.repeats == null })
        assertPairwiseDifferent(options.values.map { it.path })
    }

    @Test
    fun aStartWithOnlyOneLoopShowsItOnEveryStop() = runBlocking {
        val country = Country(sharedEdges = true) { Direction(kmh = 60.0) }
        val options = RoundTrips.resolve(start, RoundTripLength.Distance(50), country::loop)
        val fastest = options.getValue(RideStyle.FASTEST)
        assertNull(fastest.repeats)
        RideStyle.entries.drop(1).forEach { style ->
            assertEquals(RideStyle.FASTEST, options.getValue(style).repeats)
            assertTrue(options.getValue(style).path === fastest.path)
        }
    }

    @Test
    fun aStartWithNoLoopAtAllSaysSo() {
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { RoundTrips.resolve(start, RoundTripLength.Time(60), { _, _ -> null }) }
        }
        assertEquals(RoundTrips.NO_ROUND_TRIP_MESSAGE, error.message)
    }

    @Test
    fun stopsSettleFastestFirst() = runBlocking {
        val settled = mutableListOf<RideStyle>()
        RoundTrips.resolve(start, RoundTripLength.Time(60), Country { Direction(kmh = 60.0) }::loop) { style, _ -> settled += style }
        assertEquals(RideStyle.entries, settled)
    }

    @Test
    fun aLoopThatTurnsAroundIsNoLoopAndRetracedRoadCountsAgainstIt() {
        val shape = RoundTrips.shape(start, 0.0, 10_000.0)
        val length = RoundTripLength.Distance(50)
        val uTurn = path((1..40).toList(), distance = 50_000.0, kmh = 60.0, weightFactor = 1.0).apply {
            // Halfway round: a U-turn where the start snapped would not count.
            instructions = InstructionList(null).apply {
                add(Instruction(Instruction.CONTINUE_ON_STREET, "", PointList()).setDistance(25_000.0))
                add(Instruction(Instruction.U_TURN_LEFT, "", PointList()).setDistance(25_000.0))
                add(Instruction(Instruction.FINISH, "", PointList()))
            }
        }
        assertNull(RoundTrips.measure(shape, length, uTurn))
        assertNull(RoundTrips.measure(shape, length, null))
        val outAndBack = RoundTrips.measure(shape, length, path((1..20) + (20 downTo 1), distance = 50_000.0, kmh = 60.0, weightFactor = 1.0))!!
        assertEquals(0.475, outAndBack.retracedShare, 0.03)
        assertEquals(DistinctRideStyles.Budget.NONE, outAndBack.budget)
        val loop = RoundTrips.measure(shape, length, path((1..40).toList(), distance = 50_000.0, kmh = 60.0, weightFactor = 1.0))!!
        assertEquals(0.0, loop.retracedShare, 0.0)
        assertEquals(DistinctRideStyles.Budget.STYLE, loop.budget)
        assertTrue(outAndBack.score > loop.score)
    }

    @Test
    fun theStreetHomeIsAStemButRidingARoadAgainMidLoopIsALasso() {
        val shape = RoundTrips.shape(start, 0.0, 10_000.0)
        val length = RoundTripLength.Distance(4)
        // Out along edges 1-3, round, and home along 3-1 the other way: a stem.
        val stem = RoundTrips.measure(
            shape, length,
            pathAlong(listOf(1, 2, 3).map { it to 0.0 } + (4..20).map { it to 90.0 } + (21..37).map { it to 270.0 } +
                listOf(3, 2, 1).map { it to 180.0 }),
        )!!
        assertEquals(300.0 / 4_000.0, stem.retracedShare, 1e-3)
        assertEquals(0.0, stem.lassoMetres, 0.0)
        assertEquals(DistinctRideStyles.Budget.STYLE, stem.budget)
        // Skyline Circuit's Max twisty: a road ridden west, a corner off it,
        // and the same road west again. Only 10% of the loop, but a lasso.
        val lasso = RoundTrips.measure(
            shape, length,
            pathAlong((1..12).map { it to 270.0 } + (13..16).map { it to 90.0 } + (9..12).map { it to 270.0 } +
                (17..36).map { it to 0.0 }),
        )!!
        assertEquals(400.0, lasso.lassoMetres, 1.0)
        assertTrue(lasso.retracedShare <= RoundTrips.MAX_RETRACED_SHARE)
        assertEquals(DistinctRideStyles.Budget.RELAXED, lasso.budget)
        // A start on a loop street leaves by one side of it and comes home
        // by the other: the road out and home is still the stem.
        val circuit = RoundTrips.measure(
            shape, length,
            pathAlong(listOf(1 to 90.0) + listOf(2, 3).map { it to 0.0 } + (4..35).map { it to 90.0 } +
                listOf(3, 2).map { it to 180.0 } + listOf(36 to 270.0)),
        )!!
        assertEquals(0.0, circuit.lassoMetres, 0.0)
        assertEquals(200.0 / 3_800.0, circuit.retracedShare, 1e-3)
        // The start's own edge, left across one half and ridden home along
        // the other, is one pass.
        val split = RoundTrips.measure(shape, length, pathAlong(listOf(1 to 0.0) + (2..39).map { it to 0.0 } + listOf(1 to 0.0)))!!
        assertEquals(0.0, split.lassoMetres, 0.0)
        assertEquals(0.0, split.retracedShare, 0.0)
        val (stemMetres, lassoMetres) = RoundTrips.retrace(AlternativePolicy.cachedRoute(stem.path))
        assertEquals(300.0, stemMetres, 0.01)
        assertEquals(0.0, lassoMetres, 0.0)
    }

    @Test
    fun aLassosCornerMovesToWhereTheTwiceRiddenRoadBegins() {
        // West along 1-12, off round a corner on 13-16, back onto 9-12 and on.
        val steps = (1..12).map { it to 270.0 } + (13..16).map { it to 90.0 } + (9..12).map { it to 270.0 } +
            (17..36).map { it to 0.0 }
        val path = pathAlong(steps).apply {
            val points = this.points
            // Corners: one on the first stretch, one inside the detour.
            setWaypoints(PointList().apply {
                listOf(0, 5, 14, points.size() - 1).forEach { add(points.getLat(it), points.getLon(it)) }
            })
        }
        val shape = RoundTrips.Shape(0.0, 10_000.0, listOf(GHPoint(-27.1, 153.1), GHPoint(-27.2, 153.2)))
        val loop = RoundTrips.measure(shape, RoundTripLength.Distance(4), path)!!
        val moved = RoundTrips.unlassoed(loop)!!
        // The first corner stays where it was asked for; the second goes to
        // where the lasso's road was first ridden (section 9 starts at point 8).
        assertEquals(shape.vias[0], moved.vias[0])
        assertEquals(path.points.getLat(8), moved.vias[1].lat, 1e-12)
        assertEquals(path.points.getLon(8), moved.vias[1].lon, 1e-12)
        assertEquals(shape.bearing, moved.bearing, 0.0)
        // A loop without a lasso needs no move.
        val clean = RoundTrips.measure(shape, RoundTripLength.Distance(4), pathAlong((1..40).map { it to 0.0 }))!!
        assertNull(RoundTrips.unlassoed(clean))
    }

    @Test
    fun aLongLoopMayRideAJunctionsWorthTwice() {
        assertEquals(RoundTrips.MAX_LASSO_METRES, RoundTrips.lassoAllowance(20_000.0), 0.0)
        assertEquals(750.0, RoundTrips.lassoAllowance(150_000.0), 1e-9)
        assertEquals(RoundTrips.RELAXED_LASSO_METRES, RoundTrips.lassoAllowance(20_000.0, relaxed = true), 0.0)
        assertEquals(3_000.0, RoundTrips.lassoAllowance(150_000.0, relaxed = true), 1e-9)
    }

    @Test
    fun budgetsFollowTheSizeBands() {
        assertEquals(DistinctRideStyles.Budget.STYLE, loopOf(1.2).budget)
        assertEquals(DistinctRideStyles.Budget.STYLE, loopOf(0.85).budget)
        assertEquals(DistinctRideStyles.Budget.RELAXED, loopOf(1.5).budget)
        assertEquals(DistinctRideStyles.Budget.RELAXED, loopOf(0.65).budget)
        assertEquals(DistinctRideStyles.Budget.NONE, loopOf(2.0).budget)
        assertEquals(DistinctRideStyles.Budget.NONE, loopOf(0.5).budget)
        // A closer fit scores better at the same weight.
        assertTrue(loopOf(1.0).score < loopOf(1.2).score)
        assertTrue(loopOf(1.0).score < loopOf(0.8).score)
    }

    @Test
    fun theCornersARideRodeThroughAreItsWaypointsBetweenStartAndFinish() {
        val path = ResponsePath().setWaypoints(
            PointList().apply {
                add(start.lat, start.lon)
                add(-27.30, 152.88)
                add(-27.35, 152.95)
                add(start.lat, start.lon)
            },
        )
        assertEquals(listOf(GHPoint(-27.30, 152.88), GHPoint(-27.35, 152.95)), RoundTrips.viaPointsOf(path))
        assertEquals(emptyList<GHPoint>(), RoundTrips.viaPointsOf(ResponsePath()))
    }

    /** One direction of the fake country: its speed, curve share, and how much its roads wind. */
    private data class Direction(val kmh: Double, val curvy: Double = 0.0, val winding: Double = RoundTrips.ROAD_FACTOR)

    /**
     * A fake road network: [directions] says what lies along each bearing
     * (null: no loop there). Every bearing has roads of its own unless
     * [sharedEdges]; a loop's size grows with its radius.
     */
    private class Country(val sharedEdges: Boolean = false, val directions: (Double) -> Direction?) {
        fun loop(style: RideStyle, shape: RoundTrips.Shape): ResponsePath? {
            val bearing = (shape.bearing % 360.0 + 360.0) % 360.0
            val direction = directions(bearing) ?: return null
            val id = if (sharedEdges) 1 else (bearing / 11.25).roundToInt() + 1
            val distance = shape.radiusMeters * RoundTrips.PERIMETER_FACTOR * direction.winding
            val straightness = 1.0 - direction.curvy
            return path(
                (id * 1_000 until id * 1_000 + EDGES).toList(),
                distance = distance,
                kmh = direction.kmh,
                weightFactor = 1.0 + RideStyle.STRAIGHT_ROAD_PENALTY * style.curveWeight * straightness * straightness,
                curvy = direction.curvy,
            )
        }
    }

    private fun loopOf(ratio: Double): RoundTrips.Loop {
        val length = RoundTripLength.Distance(50)
        return RoundTrips.measure(
            RoundTrips.shape(start, 0.0, 10_000.0),
            length,
            path((1..EDGES).toList(), distance = 50_000.0 * ratio, kmh = 60.0, weightFactor = 1.0),
        )!!
    }

    /** A 4 km loop of 100 m sections, each edge id ridden along its own heading. */
    private fun pathAlong(steps: List<Pair<Int, Double>>, distance: Double = 4_000.0): ResponsePath {
        val points = PointList()
        var lat = -27.0
        var lon = 153.0
        points.add(lat, lon)
        steps.forEach { (_, heading) ->
            val radians = Math.toRadians(heading)
            lat += 100.0 * cos(radians) / DistanceCalcEarth.METERS_PER_DEGREE
            lon += 100.0 * sin(radians) / (DistanceCalcEarth.METERS_PER_DEGREE * cos(Math.toRadians(lat)))
            points.add(lat, lon)
        }
        return ResponsePath()
            .setPoints(points)
            .setDistance(distance)
            .setTime((distance / (60.0 / 3.6) * 1_000).toLong())
            .setRouteWeight(distance / (60.0 / 3.6))
            .apply {
                addPathDetails(
                    mapOf("edge_id" to steps.mapIndexed { index, (id, _) -> PathDetail(id).apply { first = index; last = index + 1 } }),
                )
            }
    }

    private fun assertPairwiseDifferent(paths: List<ResponsePath>) {
        val routes = paths.map(AlternativePolicy::cachedRoute)
        for (i in routes.indices) for (j in 0 until i) {
            assertFalse("Stops $i and $j repeat", DistinctRideStyles.sameRide(routes[i], routes[j]))
        }
    }

    private fun curviness(path: ResponsePath) = curvyDistanceFraction(path.points)

    private fun bearingTo(point: GHPoint): Double {
        val north = point.lat - start.lat
        val east = (point.lon - start.lon) * cos(Math.toRadians(start.lat))
        return (Math.toDegrees(kotlin.math.atan2(east, north)) + 360.0) % 360.0
    }

    private companion object {
        /** Edges per fake loop: 100 m apiece, enough for a distinct stretch. */
        const val EDGES = 40

        /**
         * A loop over [edges] of 100 m each, the first [curvy] share of them
         * a tight zigzag, with the given size, speed and weight per second.
         */
        fun path(edges: List<Int>, distance: Double, kmh: Double, weightFactor: Double, curvy: Double = 0.0): ResponsePath {
            val points = PointList()
            var lat = -27.0
            var lon = 153.0
            points.add(lat, lon)
            val zigzag = (edges.size * curvy).roundToInt()
            edges.indices.forEach { index ->
                val heading = Math.toRadians(if (index < zigzag) (if (index % 2 == 0) 45.0 else -45.0) else 0.0)
                lat += 100.0 * cos(heading) / DistanceCalcEarth.METERS_PER_DEGREE
                lon += 100.0 * sin(heading) / (DistanceCalcEarth.METERS_PER_DEGREE * cos(Math.toRadians(lat)))
                points.add(lat, lon)
            }
            val seconds = distance / (kmh / 3.6)
            return ResponsePath()
                .setPoints(points)
                .setDistance(distance)
                .setTime((seconds * 1_000).toLong())
                .setRouteWeight(seconds * weightFactor)
                .apply {
                    addPathDetails(
                        mapOf("edge_id" to edges.mapIndexed { index, id -> PathDetail(id).apply { first = index; last = index + 1 } }),
                    )
                }
        }
    }

    init {
        // Bearings are compared exactly above; tiers must produce exact multiples.
        check(abs(RoundTrips.TIER_BEARING_OFFSETS[1] - 22.5) < 1e-12)
    }
}
