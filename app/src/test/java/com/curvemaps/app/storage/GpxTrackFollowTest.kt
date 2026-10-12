package com.curvemaps.app.storage

import com.curvemaps.app.routing.navigation.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.random.Random

/**
 * Contract tests for [GpxTrackFollow]: how a file is classified, which
 * waypoints are stops, the first shaping points, how closely a route rides a
 * line, and the follow rounds that pin a route back onto the track.
 */
class GpxTrackFollowTest {

    private val originLat = -27.0
    private val originLon = 153.0
    private val metersPerDegLat = 111_132.0
    private val metersPerDegLon = cos(Math.toRadians(originLat)) * 111_320.0

    /** A point [east]/[north] metres from a fixed origin. */
    private fun at(east: Double, north: Double) =
        GeoPoint(originLat + north / metersPerDegLat, originLon + east / metersPerDegLon)

    /** A straight line east from [startEast] to [endEast] with a point every [spacing] m. */
    private fun eastward(startEast: Double, endEast: Double, spacing: Double = 20.0, north: Double = 0.0): List<GeoPoint> {
        val count = ((endEast - startEast) / spacing).toInt()
        return List(count + 1) { at(startEast + it * spacing, north) }
    }

    private fun track(points: List<GeoPoint>, waypoints: List<GeoPoint> = emptyList()) =
        GpxParser.GpxRoute("Test", points, waypoints, GpxParser.Source.TRACK)

    @Test
    fun aDetailedTrackIsFollowedAndASparseOneIsAListOfStops() {
        val detailed = GpxTrackFollow.plan(track(eastward(0.0, 20_000.0)))
        assertEquals(GpxRideMode.FOLLOW_TRACK, detailed.mode)

        val sparse = GpxTrackFollow.plan(
            GpxParser.GpxRoute("Plan", List(6) { at(it * 10_000.0, 0.0) }, emptyList(), GpxParser.Source.ROUTE),
        )
        assertEquals(GpxRideMode.STOPS, sparse.mode)
        assertEquals(6, GpxTrackFollow.stopPoints(sparse).size)

        // A handful of points cannot describe roads, however close they are.
        assertEquals(GpxRideMode.STOPS, GpxTrackFollow.plan(track(List(5) { at(it * 100.0, 0.0) })).mode)
    }

    @Test
    fun aWaypointListIsItsStopsAndADenseOneIsATrack() {
        val stops = List(4) { at(it * 8_000.0, it * 1_000.0) }
        val sparse = GpxTrackFollow.plan(GpxParser.GpxRoute(null, stops, stops, GpxParser.Source.WAYPOINTS))
        assertEquals(GpxRideMode.STOPS, sparse.mode)
        assertEquals(stops, GpxTrackFollow.stopPoints(sparse))

        val dense = eastward(0.0, 5_000.0, spacing = 50.0)
        val saved = GpxTrackFollow.plan(GpxParser.GpxRoute(null, dense, dense, GpxParser.Source.WAYPOINTS))
        assertEquals(GpxRideMode.FOLLOW_TRACK, saved.mode)
        // Its points are the line, not thousands of forced stops.
        assertTrue(saved.stops.isEmpty())
    }

    @Test
    fun onlyWaypointsOnTheTrackBecomeStops() {
        val line = eastward(0.0, 20_000.0)
        val onRoad = at(7_000.0, 40.0)
        val cafeOffTheRide = at(9_000.0, 2_000.0)
        val plan = GpxTrackFollow.plan(track(line, listOf(onRoad, cafeOffTheRide)))
        assertEquals(listOf(onRoad), plan.stops)
        assertEquals(1, plan.ignoredWaypoints)
        val shaping = GpxTrackFollow.shapingPoints(plan.line, plan.stops)
        assertTrue(shaping.any { it.point == onRoad && it.explicit })
        assertFalse(shaping.any { it.point == cafeOffTheRide })
    }

    @Test
    fun aFollowedLineHasNoUncheckedGaps() {
        val sparseBits = listOf(at(0.0, 0.0), at(800.0, 0.0)) + eastward(820.0, 3_000.0) + at(6_000.0, 500.0)
        val plan = GpxTrackFollow.plan(track(sparseBits))
        assertEquals(GpxRideMode.FOLLOW_TRACK, plan.mode)
        plan.line.zipWithNext().forEach { (a, b) ->
            assertTrue(GpxGeometry.haversineMeters(a, b) <= GpxTrackFollow.DENSIFY_METERS + 0.5)
        }
        assertEquals(sparseBits.first(), plan.line.first())
        assertEquals(sparseBits.last(), plan.line.last())
    }

    @Test
    fun firstShapingPointsKeepTheEndsTheSpacingAndTheDirection() {
        val line = GpxTrackFollow.densify(eastward(0.0, 30_000.0), GpxTrackFollow.DENSIFY_METERS)
        val shaping = GpxTrackFollow.shapingPoints(line)
        assertEquals(line.first(), shaping.first().point)
        assertEquals(line.last(), shaping.last().point)
        assertTrue(shaping.first().explicit && shaping.last().explicit)
        assertTrue(shaping.drop(1).dropLast(1).none { it.explicit })
        shaping.zipWithNext().forEach { (a, b) ->
            assertTrue(b.index > a.index)
            assertTrue(GpxGeometry.haversineMeters(a.point, b.point) <= GpxTrackFollow.SHAPING_SPACING_METERS + 30.0)
        }
        shaping.forEach { assertEquals(90.0, it.headingDeg, 1.0) }
    }

    @Test
    fun aClosedLoopGetsAPointOnItsFarSide() {
        val loop = eastward(0.0, 600.0) + eastward(0.0, 600.0, north = 400.0).reversed() + at(0.0, 0.0)
        val shaping = GpxTrackFollow.shapingPoints(GpxTrackFollow.densify(loop, 25.0))
        assertTrue(shaping.size >= 3)
        assertTrue(shaping.any { GpxGeometry.haversineMeters(it.point, loop.first()) > 300.0 })
    }

    @Test
    fun shapingNeverExceedsTheRequestBound() {
        val line = GpxTrackFollow.densify(eastward(0.0, 1_000_000.0, spacing = 1_000.0), 25.0)
        val stops = List(400) { at(it * 2_000.0 + 500.0, 0.0) }
        val shaping = GpxTrackFollow.shapingPoints(line, stops)
        assertTrue(shaping.size <= GpxTrackFollow.MAX_POINTS)
        assertEquals(line.first(), shaping.first().point)
        assertEquals(line.last(), shaping.last().point)
        assertEquals(shaping.sortedBy { it.index }, shaping)
    }

    @Test
    fun measureSeesSkippedTrackAndSpursButNotGpsNoise() {
        val line = GpxTrackFollow.densify(eastward(0.0, 10_000.0), 25.0)
        val random = Random(7)
        val noisy = line.map { GeoPoint(it.lat + random.nextDouble(-8.0, 8.0) / metersPerDegLat, it.lon) }
        assertTrue(GpxTrackFollow.measure(line, noisy).followsTrack)

        // A route that leaves the line at 3 km and rejoins at 5 km, 600 m north.
        val detour = eastward(0.0, 3_000.0) + eastward(3_000.0, 5_000.0, north = 600.0) + eastward(5_000.0, 10_000.0)
        val skipped = GpxTrackFollow.measure(line, detour)
        assertEquals(2_000.0, skipped.offTrackMeters, 200.0)
        assertTrue(skipped.extraMeters > 2_000.0)
        assertEquals(1, skipped.offTrackRuns.size)

        // A spur 500 m out and back: no track is skipped, a kilometre is extra.
        val spur = eastward(0.0, 4_000.0) + List(26) { at(4_000.0, it * 20.0) } +
            List(26) { at(4_000.0, 500.0 - it * 20.0) } + eastward(4_000.0, 10_000.0)
        val spurMeasure = GpxTrackFollow.measure(line, spur)
        assertEquals(0.0, spurMeasure.offTrackMeters, 0.0)
        assertTrue(spurMeasure.extraMeters in 400.0..1_100.0)
    }

    /**
     * A pretend road network: the router rides [line] exactly, except that it
     * cuts straight across [shortcut] (line indices) unless a shaping point
     * lies inside it, and a shaping point at [offRoad] snaps 200 m away and is
     * reached by a spur.
     */
    private class FakeRouter(
        val line: List<GeoPoint>,
        val shortcut: IntRange?,
        val offRoad: IntRange? = null,
        val failAfter: Int = Int.MAX_VALUE,
    ) {
        var calls = 0

        fun route(shaping: List<TrackShapingPoint>): FollowAttempt<Int> {
            calls++
            if (calls > failAfter) throw IllegalStateException("Cannot find point")
            val pinned = shortcut != null && shaping.any { it.index in (shortcut.first + 1) until shortcut.last }
            val points = ArrayList<GeoPoint>()
            val snapped = ArrayList<GeoPoint>()
            for (i in line.indices) {
                if (shortcut != null && !pinned && i in (shortcut.first + 1) until shortcut.last) continue
                points += line[i]
                shaping.firstOrNull { it.index == i }?.let { point ->
                    if (offRoad != null && i in offRoad && !point.explicit) {
                        val away = GeoPoint(line[i].lat + 200.0 / 111_132.0, line[i].lon)
                        for (step in 1..10) points += GeoPoint(line[i].lat + step * 20.0 / 111_132.0, line[i].lon)
                        for (step in 9 downTo 0) points += GeoPoint(line[i].lat + step * 20.0 / 111_132.0, line[i].lon)
                        snapped += away
                    } else {
                        snapped += point.point
                    }
                }
            }
            return FollowAttempt(calls, points, snapped)
        }
    }

    /** A line east with a 3 km bulge north between 4 and 7 km that the router can cut. */
    private fun bulgingLine(): List<GeoPoint> = GpxTrackFollow.densify(
        eastward(0.0, 4_000.0) + listOf(at(4_500.0, 1_200.0), at(6_500.0, 1_200.0)) + eastward(7_000.0, 12_000.0),
        25.0,
    )

    @Test
    fun followPinsASkippedStretchBackOntoTheTrack() {
        val line = bulgingLine()
        val start = line.indexOfFirst { it.lon >= at(4_000.0, 0.0).lon }
        val end = line.indexOfFirst { it.lon >= at(7_000.0, 0.0).lon }
        // Shaping points only at the ends: the router cuts the bulge.
        val ends = GpxTrackFollow.shapingPoints(line).let { listOf(it.first(), it.last()) }
        val router = FakeRouter(line, start..end)
        val result = GpxTrackFollow.follow(line, ends, route = router::route)
        assertTrue(result.measure.followsTrack)
        assertTrue(result.attempts >= 2)
        assertTrue(result.shaping.any { it.index in start..end })
    }

    @Test
    fun aShapingPointWithNoRoadIsDroppedRatherThanRiddenAsASpur() {
        val line = GpxTrackFollow.densify(eastward(0.0, 10_000.0), 25.0)
        val shaping = GpxTrackFollow.shapingPoints(line)
        val bad = shaping[2].index
        val router = FakeRouter(line, shortcut = null, offRoad = bad..bad)
        val result = GpxTrackFollow.follow(line, shaping, route = router::route)
        assertTrue(result.measure.followsTrack)
        assertTrue(result.shaping.none { it.index == bad })
        assertEquals(2, result.attempts)
    }

    @Test
    fun aRoundThatFailsKeepsTheBestRouteAndAFirstFailureIsTheRidersError() {
        val line = bulgingLine()
        val start = line.indexOfFirst { it.lon >= at(4_000.0, 0.0).lon }
        val end = line.indexOfFirst { it.lon >= at(7_000.0, 0.0).lon }
        val ends = GpxTrackFollow.shapingPoints(line).let { listOf(it.first(), it.last()) }
        val result = GpxTrackFollow.follow(line, ends, route = FakeRouter(line, start..end, failAfter = 1)::route)
        assertEquals(1, result.value)
        assertTrue(result.measure.offTrackMeters > 1_000.0)

        assertThrows(IllegalStateException::class.java) {
            GpxTrackFollow.follow(line, ends, route = FakeRouter(line, null, failAfter = 0)::route)
        }
    }

    @Test
    fun followStopsAfterItsRoundsAndNeverDropsTheEnds() {
        val line = bulgingLine()
        val ends = GpxTrackFollow.shapingPoints(line).let { listOf(it.first(), it.last()) }
        var calls = 0
        // A router that never rides the line: every round is as bad as the last.
        val result = GpxTrackFollow.follow(line, ends) { shaping ->
            calls++
            FollowAttempt(calls, listOf(at(0.0, 3_000.0), at(12_000.0, 3_000.0)), shaping.map { it.point })
        }
        assertTrue(calls <= GpxTrackFollow.MAX_ROUNDS)
        assertEquals(line.first(), result.shaping.first().point)
        assertEquals(line.last(), result.shaping.last().point)
        assertFalse(result.measure.followsTrack)
    }

    @Test
    fun headingsAlongReadEachPassOfAnOutAndBack() {
        val out = eastward(0.0, 5_000.0)
        val line = out + out.reversed().drop(1)
        val turnaroundSide = at(2_000.0, 0.0)
        val headings = GpxTrackFollow.headingsAlong(line, listOf(turnaroundSide, at(4_000.0, 0.0), turnaroundSide))
        assertEquals(90.0, headings[0], 1.0)
        assertEquals(270.0, headings[2], 1.0)
        assertTrue(GpxTrackFollow.headingsAlong(line, listOf(at(2_000.0, 900.0))).single().isNaN())
    }

    @Test
    fun proximityMatchesABruteForceSearch() {
        val random = Random(42)
        repeat(20) {
            val polyline = List(random.nextInt(2, 60)) {
                at(random.nextDouble(-3_000.0, 3_000.0), random.nextDouble(-3_000.0, 3_000.0))
            }
            val index = PolylineProximity(polyline)
            repeat(50) {
                val p = at(random.nextDouble(-3_500.0, 3_500.0), random.nextDouble(-3_500.0, 3_500.0))
                val brute = polyline.zipWithNext().minOf { (a, b) ->
                    GeoMath.segmentDistanceM(p.lat, p.lon, a.lat, a.lon, b.lat, b.lon).first
                }
                val within = 200.0
                val found = index.distanceMeters(p, within)
                if (brute <= within) {
                    assertEquals(brute, found, 1e-6)
                } else {
                    assertTrue(found.isInfinite() || abs(found - brute) < 1e-6)
                }
            }
        }
    }

    @Test
    fun aTurnaroundGetsAnUnheadedShapingPointAndStraightRoadsDoNot() {
        val out = eastward(0.0, 6_000.0)
        val line = GpxTrackFollow.densify(out + out.reversed().drop(1), 25.0)
        val turns = GpxTrackFollow.turnarounds(line)
        assertEquals(1, turns.size)
        assertEquals(6_000.0, GpxGeometry.haversineMeters(line.first(), line[turns.single()]), 30.0)

        val shaping = GpxTrackFollow.shapingPoints(line)
        val apex = shaping.single { it.index == turns.single() }
        assertTrue(apex.explicit)
        assertTrue(apex.headingDeg.isNaN())
        // Either side of the turn the headings are the two directions ridden.
        val before = shaping.filter { it.index < apex.index && !it.headingDeg.isNaN() }.last()
        val after = shaping.filter { it.index > apex.index && !it.headingDeg.isNaN() }.first()
        assertEquals(90.0, before.headingDeg, 2.0)
        assertEquals(270.0, after.headingDeg, 2.0)

        // GPS noise on a straight road is not a turnaround.
        val random = Random(3)
        val noisy = GpxTrackFollow.densify(eastward(0.0, 6_000.0), 25.0)
            .map { GeoPoint(it.lat + random.nextDouble(-6.0, 6.0) / metersPerDegLat, it.lon) }
        assertTrue(GpxTrackFollow.turnarounds(noisy).isEmpty())
    }

    /**
     * A router that rides [line] exactly but takes [detourMeters] round the
     * network on any leg from a shaping point at [badIndex] (a point snapped
     * onto the other carriageway), and on any leg whose two ends both carry
     * headings that disagree by more than 90° (a turn it may not make there).
     */
    private fun detouringRouter(line: List<GeoPoint>, badIndex: Int?, detourMeters: Double = 9_000.0) =
        { shaping: List<TrackShapingPoint> ->
            val points = ArrayList<GeoPoint>()
            shaping.zipWithNext().forEach { (a, b) ->
                val leg = line.subList(a.index, b.index + 1)
                val reversed = !a.headingDeg.isNaN() && !b.headingDeg.isNaN() &&
                    abs(GeoMath.signedBearingDeltaDeg(a.headingDeg, b.headingDeg)) > 90.0
                if (a.index == badIndex || reversed) {
                    // Out along a road beside the line and back again.
                    val steps = (detourMeters / 2 / 20.0).toInt()
                    points += leg.first()
                    for (step in 1..steps) points += GeoPoint(leg.first().lat + step * 20.0 / 111_132.0, leg.first().lon)
                    for (step in steps downTo 1) points += GeoPoint(leg.first().lat + step * 20.0 / 111_132.0, leg.first().lon)
                }
                points += leg
            }
            FollowAttempt(Unit, points.zipWithNext().filter { (p, q) -> p != q }.map { it.first } + points.last(), shaping.map { it.point })
        }

    @Test
    fun aDetourLegLosesItsHeadingsAndGetsAMiddlePoint() {
        val out = eastward(0.0, 6_000.0)
        val line = GpxTrackFollow.densify(out + out.reversed().drop(1), 25.0)
        // Shaping without the turnaround point, as a file's own stops might be.
        val withoutApex = GpxTrackFollow.shapingPoints(line).filter { !it.explicit || it.index == 0 || it.index == line.lastIndex }
        val result = GpxTrackFollow.follow(line, withoutApex, route = detouringRouter(line, badIndex = null))
        assertTrue(result.measure.followsTrack)
        assertTrue(result.attempts >= 2)
    }

    @Test
    fun aPointThatKeepsStartingDetoursIsDropped() {
        val line = GpxTrackFollow.densify(eastward(0.0, 12_000.0), 25.0)
        val shaping = GpxTrackFollow.shapingPoints(line)
        val bad = shaping[3].index
        val result = GpxTrackFollow.follow(line, shaping, route = detouringRouter(line, badIndex = bad))
        assertTrue(result.measure.followsTrack)
        assertTrue(result.shaping.none { it.index == bad })
        assertTrue(result.attempts <= GpxTrackFollow.MAX_ROUNDS)
    }
}
