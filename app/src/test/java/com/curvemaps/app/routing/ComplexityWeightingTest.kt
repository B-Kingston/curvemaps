package com.curvemaps.app.routing

import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.EnumEncodedValue
import com.graphhopper.routing.ev.RoadAccess
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.RoadClassLink
import com.graphhopper.routing.ev.Roundabout
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue
import com.graphhopper.routing.ev.Surface
import com.graphhopper.routing.ev.UrbanDensity
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.search.KVStorage
import com.graphhopper.storage.BaseGraph
import com.graphhopper.util.GHUtility
import com.graphhopper.routing.querygraph.VirtualEdgeIteratorState
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.FetchMode
import com.graphhopper.util.PointList
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.graphhopper.routing.querygraph.QueryGraph
import com.graphhopper.storage.index.Snap
import com.graphhopper.util.DistanceCalcEarth
import org.junit.Test

class ComplexityWeightingTest {

    @Test
    fun accessIsAHardConstraintAtEveryStyleAndDirection() {
        val fixture = fixture()
        val edge = fixture.edge(forwardAccess = true, reverseAccess = false)
        val closed = fixture.edge(forwardAccess = false, reverseAccess = false)
        RideStyle.entries.forEach { style ->
            val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0, millis = 4000L), style)
            assertTrue(style.label, weighting.calcEdgeWeight(edge, false).isFinite())
            assertEquals(style.label, Double.POSITIVE_INFINITY, weighting.calcEdgeWeight(edge, true), 0.0)
            assertEquals(style.label, Double.POSITIVE_INFINITY, weighting.calcEdgeWeight(closed, false), 0.0)
        }
    }

    @Test
    fun customModelPriorityZeroIsAHardBlockAtEveryStyle() {
        val fixture = fixture()
        val edge = fixture.edge()
        RideStyle.entries.forEach { style ->
            val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0), style, priority = 0.0)
            assertEquals(style.label, Double.POSITIVE_INFINITY, weighting.calcEdgeWeight(edge, false), 0.0)
        }
    }

    @Test
    fun aPassThroughViaForbidsTurningBackOntoTheEdgeItWasReachedBy() {
        val fixture = fixture()
        val base = fixture.edge()
        // GraphHopper marks the virtual edge a pass-through via was reached
        // by as unfavoured; only virtual edges can carry that flag.
        val virtual = VirtualEdgeIteratorState(
            base.edgeKey, GHUtility.createEdgeKey(4_000, false), base.baseNode, base.adjNode, base.distance,
            base.flags, emptyMap(), base.fetchWayGeometry(FetchMode.ALL), false,
        )
        RideStyle.entries.forEach { style ->
            val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0), style)
            virtual.setUnfavored(false)
            assertTrue(style.label, weighting.calcEdgeWeight(virtual, false).isFinite())
            assertTrue(style.label, weighting.calcEdgeWeight(virtual, true).isFinite())
            virtual.setUnfavored(true)
            assertEquals(style.label, Double.POSITIVE_INFINITY, weighting.calcEdgeWeight(virtual, false), 0.0)
            assertEquals(style.label, Double.POSITIVE_INFINITY, weighting.calcEdgeWeight(virtual, true), 0.0)
        }
        // An ordinary edge never carries the flag.
        assertTrue(weighting(fixture, FakeWeighting(edgeWeight = 4.0), RideStyle.FASTEST).calcEdgeWeight(base, false).isFinite())
    }

    @Test
    fun blockUnpavedRejectsOnlyKnownUnpavedSurfaces() {
        val fixture = fixture()
        val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0), RideStyle.FASTEST, blockUnpaved = true)
        listOf(
            Surface.UNPAVED,
            Surface.COMPACTED,
            Surface.FINE_GRAVEL,
            Surface.GRAVEL,
            Surface.GROUND,
            Surface.DIRT,
            Surface.GRASS,
            Surface.SAND,
        ).forEach { surface ->
            val edge = fixture.edge(surface = surface)
            assertEquals(surface.toString(), Double.POSITIVE_INFINITY, weighting.calcEdgeWeight(edge, false), 0.0)
        }
        listOf(Surface.COBBLESTONE, Surface.PAVING_STONES).forEach { surface ->
            val edge = fixture.edge(surface = surface)
            assertTrue(weighting.calcEdgeWeight(edge, false).isFinite())
        }
    }

    @Test
    fun fastestIsPureTravelTimePlusSiblingPenalty() {
        val fixture = fixture()
        val edge = fixture.edge(roadAccess = RoadAccess.DESTINATION)
        val weighting = weighting(
            fixture,
            FakeWeighting(edgeWeight = 4.0),
            RideStyle.FASTEST,
            // A soft model priority must not leak into any stop.
            priority = 0.1,
            siblingEdgeIds = setOf(edge.edge),
            siblingEdgePenalty = 0.25,
        )
        assertEquals(5.0, weighting.calcEdgeWeight(edge, false), 0.0)
    }

    @Test
    fun softModelPrioritiesAreNeverScaledByTheDial() {
        // The old dial blended w_custom - w_fastest, which carried the model's
        // 90 s/km distance influence and its 0.1 motorway priority into every
        // click. A fully curvy edge must now cost exactly its travel time at
        // every style, whatever the model's soft priority says.
        val fixture = fixture()
        val curved = fixture.edge(distance = 120.0, geometry = TIGHT_CORNER)
        RideStyle.entries.forEach { style ->
            val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0), style, priority = 0.1)
            assertEquals(style.label, 4.0, weighting.calcEdgeWeight(curved, false), 1e-6)
        }
    }

    @Test
    fun straightRoadPenaltyFollowsEachStyleCurveWeight() {
        val fixture = fixture()
        val straight = fixture.edge(distance = 60.0, geometry = listOf(0.0 to 0.00027))
        val curved = fixture.edge(distance = 120.0, geometry = TIGHT_CORNER)
        RideStyle.entries.forEach { style ->
            val fastest = FakeWeighting(edgeWeight = 4.0, millis = 4321L, minWeight = 0.75)
            val weighting = weighting(fixture, fastest, style)
            val straightWeight = weighting.calcEdgeWeight(straight, false)
            assertEquals(style.label, 4.0 * style.sameSpeedBreakEven, straightWeight, 1e-9)
            assertEquals(style.label, 4.0, weighting.calcEdgeWeight(curved, false), 1e-6)
            assertEquals(4321L, weighting.calcEdgeMillis(straight, false))
            assertEquals(0.75, weighting.calcMinWeightPerDistance(), 0.0)
        }
    }

    @Test
    fun curveWeightOverrideScalesOnlyTheStraightRoadPenalty() {
        val fixture = fixture()
        val straight = fixture.edge(distance = 60.0, geometry = listOf(0.0 to 0.00027))
        val curved = fixture.edge(distance = 120.0, geometry = TIGHT_CORNER)
        val fastest = FakeWeighting(edgeWeight = 4.0, millis = 4321L, minWeight = 0.75)
        listOf(0.375, 0.9, 3.75, RideStyle.MAX_CURVE_WEIGHT).forEach { curveWeight ->
            val weighting = weighting(fixture, fastest, RideStyle.MOSTLY_FAST, curveWeight = curveWeight)
            assertEquals(
                4.0 * (1.0 + RideStyle.STRAIGHT_ROAD_PENALTY * curveWeight),
                weighting.calcEdgeWeight(straight, false),
                1e-9,
            )
            // Curves stay at travel time, ETA and the search bound never move.
            assertEquals(4.0, weighting.calcEdgeWeight(curved, false), 1e-6)
            assertEquals(4321L, weighting.calcEdgeMillis(straight, false))
            assertEquals(0.75, weighting.calcMinWeightPerDistance(), 0.0)
        }
    }

    @Test
    fun partlyCurvyRoadsKeepOnlyTheSquaredStraightnessPenalty() {
        val fixture = fixture()
        // Pillars between the fixture's towers at (0, 0) and (0, 0.00054):
        // gentle bends over a long edge score partial exposure.
        val bends = listOf(0.00002, 0.00005, 0.0001, 0.0002).map { offset ->
            fixture.edge(distance = 1_000.0, geometry = listOf(offset to 0.00027))
        }
        val exposures = bends.map { combinedCurveExposure(it.fetchWayGeometry(FetchMode.ALL), it.distance) }
        assertTrue("need partial exposures, got $exposures", exposures.any { it > 0.05 && it < 0.95 })
        RideStyle.entries.forEach { style ->
            val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0), style)
            bends.zip(exposures).forEach { (edge, exposure) ->
                val straightness = 1.0 - exposure
                val expected = 4.0 * (1.0 + RideStyle.STRAIGHT_ROAD_PENALTY * style.curveWeight * straightness * straightness)
                assertEquals("${style.label} e=$exposure", expected, weighting.calcEdgeWeight(edge, false), 1e-6)
            }
        }
    }

    @Test
    fun townStreetsEarnNoCurveCreditOnCurvyStops() {
        val fixture = fixture()
        ComplexityWeighting.TOWN_STREET_CLASSES.forEach { roadClass ->
            val reads = intArrayOf(0)
            val crescent = countingGeometryReads(fixture.edge(distance = 120.0, geometry = TIGHT_CORNER, roadClass = roadClass), reads)
            RideStyle.entries.forEach { style ->
                val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0), style)
                // However tight its bends, a town street weighs like a straight road ...
                assertEquals("$roadClass ${style.label}", 4.0 * style.sameSpeedBreakEven, weighting.calcEdgeWeight(crescent, false), 1e-9)
                // ... with hills too, and without reading its geometry.
                assertEquals("$roadClass ${style.label}", 4.0 * style.sameSpeedBreakEven, weighting(fixture, FakeWeighting(edgeWeight = 4.0), style, hills = true).calcEdgeWeight(crescent, false), 1e-9)
            }
            assertEquals("$roadClass needs no geometry", 0, reads[0])
        }
        // Roads built to be ridden keep their full credit.
        listOf(RoadClass.PRIMARY, RoadClass.SECONDARY, RoadClass.TERTIARY, RoadClass.UNCLASSIFIED, RoadClass.TRACK).forEach { roadClass ->
            val corner = fixture.edge(distance = 120.0, geometry = TIGHT_CORNER, roadClass = roadClass)
            RideStyle.entries.forEach { style ->
                assertEquals("$roadClass ${style.label}", 4.0, weighting(fixture, FakeWeighting(edgeWeight = 4.0), style).calcEdgeWeight(corner, false), 1e-6)
            }
        }
    }

    @Test
    fun junctionPiecesEarnNoCurveCreditOnCurvyStops() {
        val fixture = fixture()
        listOf(
            "slip lane" to fixture.edge(distance = 120.0, geometry = TIGHT_CORNER, roadClass = RoadClass.TERTIARY, link = true),
            "roundabout" to fixture.edge(distance = 120.0, geometry = TIGHT_CORNER, roadClass = RoadClass.PRIMARY, roundabout = true),
        ).forEach { (kind, edge) ->
            val reads = intArrayOf(0)
            val piece = countingGeometryReads(edge, reads)
            RideStyle.entries.forEach { style ->
                // However tight it bends, a junction piece weighs like a straight road ...
                assertEquals("$kind ${style.label}", 4.0 * style.sameSpeedBreakEven, weighting(fixture, FakeWeighting(edgeWeight = 4.0), style, junctionPieces = true).calcEdgeWeight(piece, false), 1e-9)
                // ... with hills too, and without reading its geometry.
                assertEquals("$kind ${style.label}", 4.0 * style.sameSpeedBreakEven, weighting(fixture, FakeWeighting(edgeWeight = 4.0), style, hills = true, junctionPieces = true).calcEdgeWeight(piece, false), 1e-9)
            }
            assertEquals("$kind needs no geometry", 0, reads[0])
        }
        // The same corner on the road itself keeps its full credit.
        val corner = fixture.edge(distance = 120.0, geometry = TIGHT_CORNER, roadClass = RoadClass.TERTIARY)
        RideStyle.entries.forEach { style ->
            assertEquals(style.label, 4.0, weighting(fixture, FakeWeighting(edgeWeight = 4.0), style, junctionPieces = true).calcEdgeWeight(corner, false), 1e-6)
        }
    }

    @Test
    fun unpavedRoadsEarnHalfTheirCurveCredit() {
        val fixture = fixture()
        val geometry = listOf(0.0001 to 0.00027)
        val exposure = fixture.edge(geometry = geometry).let { combinedCurveExposure(it.fetchWayGeometry(FetchMode.ALL), it.distance) }
        assertTrue("need a partial exposure, got $exposure", exposure > 0.05 && exposure < 0.95)
        ComplexityWeighting.UNPAVED_SURFACES.forEach { surface ->
            val gravel = fixture.edge(geometry = geometry, surface = surface)
            val sealed = fixture.edge(geometry = geometry, surface = Surface.ASPHALT)
            RideStyle.entries.forEach { style ->
                val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0), style)
                val straightness = 1.0 - ComplexityWeighting.UNPAVED_CURVE_CREDIT * exposure
                val expected = 4.0 * (1.0 + RideStyle.STRAIGHT_ROAD_PENALTY * style.curveWeight * straightness * straightness)
                assertEquals("$surface ${style.label}", expected, weighting.calcEdgeWeight(gravel, false), 1e-6)
                if (style == RideStyle.FASTEST) {
                    assertEquals(weighting.calcEdgeWeight(sealed, false), weighting.calcEdgeWeight(gravel, false), 0.0)
                } else {
                    // An equally curvy sealed road always weighs less.
                    assertTrue("$surface ${style.label}", weighting.calcEdgeWeight(sealed, false) < weighting.calcEdgeWeight(gravel, false))
                }
            }
        }
        // Surfaced roads and roads of unknown surface keep their full credit.
        listOf(Surface.PAVED, Surface.ASPHALT, Surface.CONCRETE, Surface.COBBLESTONE, Surface.PAVING_STONES, Surface.MISSING).forEach { surface ->
            val road = fixture.edge(geometry = geometry, surface = surface)
            val straightness = 1.0 - exposure
            val expected = 4.0 * (1.0 + RideStyle.STRAIGHT_ROAD_PENALTY * RideStyle.TWISTY.curveWeight * straightness * straightness)
            assertEquals(surface.toString(), expected, weighting(fixture, FakeWeighting(edgeWeight = 4.0), RideStyle.TWISTY).calcEdgeWeight(road, false), 1e-6)
        }
        // A curvier gravel road still beats a straighter sealed one, and a curvier gravel road never weighs more.
        val tightGravel = fixture.edge(distance = 120.0, geometry = TIGHT_CORNER, surface = Surface.GRAVEL)
        val straightSealed = fixture.edge(distance = 60.0, geometry = listOf(0.0 to 0.00027))
        RideStyle.entries.drop(1).forEach { style ->
            val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0), style)
            assertTrue(style.label, weighting.calcEdgeWeight(tightGravel, false) < weighting.calcEdgeWeight(straightSealed, false))
        }
    }

    @Test
    fun weightNeverRisesAsExposureGrowsAtAnyStop() {
        val fixture = fixture()
        val edges = (0..20).map { step ->
            fixture.edge(distance = 1_000.0, geometry = listOf(step * 0.00002 to 0.00027))
        }
        val byExposure = edges.sortedBy { combinedCurveExposure(it.fetchWayGeometry(FetchMode.ALL), it.distance) }
        RideStyle.entries.forEach { style ->
            val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0), style)
            val weights = byExposure.map { weighting.calcEdgeWeight(it, false) }
            weights.zipWithNext().forEach { (straighter, curvier) ->
                assertTrue("${style.label}: $weights", curvier <= straighter + 1e-9)
            }
            weights.forEach { assertTrue("${style.label}: $weights", it >= 4.0 - 1e-9) }
        }
    }

    @Test
    fun curvePreferenceGrowsAtEveryStopAndNeverSaturates() {
        val stops = RideStyle.entries
        assertEquals(1.0, RideStyle.FASTEST.sameSpeedBreakEven, 0.0)
        stops.zipWithNext().forEach { (lower, higher) ->
            assertTrue(
                "${higher.label} must prefer curves clearly more than ${lower.label}",
                higher.sameSpeedBreakEven - lower.sameSpeedBreakEven >= 0.4,
            )
        }
        // Versus a straight road 100/60 times faster, Max twisty must still
        // accept a curvy road well over 1.6x longer: the old dial's ceiling.
        val maxAgainstFastRoad = RideStyle.MAX_TWISTY.sameSpeedBreakEven * 60.0 / 100.0
        assertTrue("got $maxAgainstFastRoad", maxAgainstFastRoad > 1.6)
    }

    @Test
    fun avoidMotorwaysPenalisesOnlyMotorwayAndTrunkAtEveryStyle() {
        val fixture = fixture()
        val motorway = fixture.edge(roadClass = RoadClass.MOTORWAY, geometry = TIGHT_CORNER)
        val trunk = fixture.edge(roadClass = RoadClass.TRUNK, geometry = TIGHT_CORNER)
        val primary = fixture.edge(roadClass = RoadClass.PRIMARY, geometry = TIGHT_CORNER)
        RideStyle.entries.forEach { style ->
            val avoiding = weighting(fixture, FakeWeighting(edgeWeight = 4.0), style, avoidMotorways = true)
            val plain = weighting(fixture, FakeWeighting(edgeWeight = 4.0), style)
            assertEquals(style.label, 40.0, avoiding.calcEdgeWeight(motorway, false), 1e-6)
            assertEquals(style.label, 40.0, avoiding.calcEdgeWeight(trunk, true), 1e-6)
            assertEquals(style.label, 4.0, avoiding.calcEdgeWeight(primary, false), 1e-6)
            assertEquals(style.label, 4.0, plain.calcEdgeWeight(motorway, false), 1e-6)
        }
    }

    @Test
    fun curvyStylesDiscourageDestinationOnlyRoads() {
        val fixture = fixture()
        val destination = fixture.edge(roadAccess = RoadAccess.DESTINATION, geometry = TIGHT_CORNER)
        assertEquals(4.0, weighting(fixture, FakeWeighting(4.0), RideStyle.FASTEST).calcEdgeWeight(destination, false), 1e-6)
        RideStyle.entries.drop(1).forEach { style ->
            assertEquals(
                style.label,
                40.0,
                weighting(fixture, FakeWeighting(4.0), style).calcEdgeWeight(destination, false),
                1e-6,
            )
        }
    }

    @Test
    fun turnCostsAreTheFastestTurnCostsAtEveryStyle() {
        val fixture = fixture()
        RideStyle.entries.forEach { style ->
            val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0, turnWeight = 7.0), style)
            assertEquals(7.0, weighting.calcTurnWeight(1, 2, 3), 0.0)
        }
    }

    @Test
    fun repeatedDirectionalReadsReuseTheCachedCurveFactor() {
        val fixture = fixture()
        val geometryReads = intArrayOf(0)
        val edge = countingGeometryReads(fixture.edge(), geometryReads)
        val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0), RideStyle.TWISTY)

        val forward = weighting.calcEdgeWeight(edge, reverse = false)
        val repeatedForward = weighting.calcEdgeWeight(edge, reverse = false)
        val reverse = weighting.calcEdgeWeight(edge, reverse = true)

        assertEquals(forward, repeatedForward, 0.0)
        assertEquals(forward, reverse, 0.0)
        assertEquals("one geometry read for both directions of one edge", 1, geometryReads[0])
    }

    @Test
    fun laterRequestsReuseTheGraphsSharedEdgeFactors() {
        val fixture = fixture(threeD = true)
        val reads = mutableMapOf<String, Int>()
        val plain = fixture.edge(geometry = TIGHT_CORNER, elevations = listOf(5.0, 40.0, 5.0), stops = KVStorage.KValue("4", "2"))
        val edge = countingReads(plain, reads)
        val shared = EdgeFactors(fixture.graph)
        fun weight(style: RideStyle, reverse: Boolean, hills: Boolean = false, table: EdgeFactors? = shared) =
            weighting(fixture, FakeWeighting(edgeWeight = 4.0), style, hills = hills, stopsAndTowns = true, edgeFactors = table)
                .calcEdgeWeight(edge, reverse)

        // Each new weighting is a new request, as GraphHopper builds one per route.
        val first = listOf(weight(RideStyle.TWISTY, false), weight(RideStyle.TWISTY, true))
        assertEquals(1, reads.getValue("fetchWayGeometry"))
        val stopReads = reads.getOrDefault("getValue", 0) + reads.getOrDefault("getKeyValues", 0)
        assertEquals("one stop read per storage direction", 2, stopReads)
        assertEquals(first, listOf(weight(RideStyle.TWISTY, false), weight(RideStyle.TWISTY, true)))
        weight(RideStyle.MAX_TWISTY, true)
        weight(RideStyle.CURVY, false)
        assertEquals(1, reads.getValue("fetchWayGeometry"))
        assertEquals(stopReads, reads.getOrDefault("getValue", 0) + reads.getOrDefault("getKeyValues", 0))
        // Hills is its own factor: measured once more, then shared too.
        weight(RideStyle.TWISTY, false, hills = true)
        weight(RideStyle.CURVY, true, hills = true)
        assertEquals(2, reads.getValue("fetchWayGeometry"))
        // Exactly the weights a request without the shared table computes.
        RideStyle.entries.forEach { style ->
            listOf(false, true).forEach { hills ->
                listOf(false, true).forEach { reverse ->
                    assertEquals(
                        "${style.label} hills=$hills reverse=$reverse",
                        weight(style, reverse, hills, table = null), weight(style, reverse, hills), 0.0,
                    )
                }
            }
        }
    }

    @Test
    fun sharedCurveFactorsDoNotDependOnWhichWayAnEdgeWasFirstMet() {
        val fixture = fixture(threeD = true)
        val random = java.util.Random(7)
        repeat(200) { index ->
            val pillars = (1..2 + random.nextInt(10)).map { step ->
                step * 0.00004 + random.nextGaussian() * 0.00002 to 0.00054 * step / 12 + random.nextGaussian() * 0.00002
            }
            val forward = fixture.edge(geometry = pillars, elevations = pillars.map { random.nextDouble() * 60.0 })
            val reversed = fixture.graph.getEdgeIteratorState(forward.edge, forward.baseNode)
            listOf(false, true).forEach { hills ->
                val alongFirst = EdgeFactors(fixture.graph)
                val againstFirst = EdgeFactors(fixture.graph)
                fun weighting(table: EdgeFactors?) =
                    weighting(fixture, FakeWeighting(edgeWeight = 4.0), RideStyle.MAX_TWISTY, hills = hills, edgeFactors = table)
                val along = weighting(alongFirst).calcEdgeWeight(forward, false)
                val against = weighting(againstFirst).calcEdgeWeight(reversed, false)
                assertEquals("edge $index hills=$hills", along, against, 0.0)
                assertEquals(alongFirst.curveFactor(forward.edge, hills), againstFirst.curveFactor(forward.edge, hills), 0.0)
                // The storage-direction measure, as a request meeting it along storage first computes it.
                assertEquals(weighting(null).calcEdgeWeight(forward, false), along, 0.0)
            }
        }
    }

    @Test
    fun fastestNeverReadsEdgeGeometry() {
        val fixture = fixture()
        val geometryReads = intArrayOf(0)
        val edge = countingGeometryReads(fixture.edge(), geometryReads)
        weighting(fixture, FakeWeighting(edgeWeight = 4.0), RideStyle.FASTEST).calcEdgeWeight(edge, false)
        assertEquals(0, geometryReads[0])
    }

    @Test
    fun circumradiusBucketsWeightTighterCurvesMoreStrongly() {
        val straight = pointListMeters(0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0)
        val broad = pointListMeters(0.0 to 0.0, 150.0 to 0.0, 150.0 to 150.0)
        val medium = pointListMeters(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0)
        val sharp = pointListMeters(0.0 to 0.0, 50.0 to 0.0, 50.0 to 50.0)
        val tight = pointListMeters(0.0 to 0.0, 30.0 to 0.0, 30.0 to 30.0)

        assertEquals(0.0, radiusCurveExposure(straight), 0.0)
        assertEquals(0.5, radiusCurveExposure(broad), 1e-6)
        assertEquals(0.65, radiusCurveExposure(medium), 1e-6)
        assertEquals(0.8, radiusCurveExposure(sharp), 1e-6)
        assertEquals(1.0, radiusCurveExposure(tight), 1e-6)
    }

    @Test
    fun aSharedSegmentUsesTheTighterAdjacentRadius() {
        val geometry = pointListMeters(
            0.0 to 200.0,
            0.0 to 0.0,
            30.0 to 0.0,
            30.0 to 30.0,
        )
        assertEquals(320.0 / 520.0, radiusCurveExposure(geometry), 1e-5)
    }

    @Test
    fun curveExposureIsDirectionIndependentAndRejectsShortJiggles() {
        val curve = listOf(0.0 to 0.0, 30.0 to 0.0, 30.0 to 30.0, 60.0 to 30.0)
        val forward = radiusCurveExposure(pointListMeters(*curve.toTypedArray()))
        val reverse = radiusCurveExposure(pointListMeters(*curve.reversed().toTypedArray()))
        assertEquals(forward, reverse, 1e-9)

        val shortJiggle = pointListMeters(
            0.0 to 0.0,
            100.0 to 0.0,
            102.0 to 2.0,
            104.0 to 0.0,
            204.0 to 0.0,
        )
        assertEquals(0.0, radiusCurveExposure(shortJiggle), 0.0)

        val duplicate = pointListMeters(0.0 to 0.0, 100.0 to 0.0, 100.0 to 0.0)
        assertTrue(radiusCurveExposure(duplicate).isFinite())
        assertEquals(0.0, radiusCurveExposure(pointListMeters(0.0 to 0.0, 100.0 to 0.0)), 0.0)
    }

    @Test
    fun hillsCountAsFunRoadsOnCurvyStopsOnly() {
        val fixture = fixture(threeD = true)
        // Straight 1 km edge over a 60 m crest: 120 m relief per km, fully hilly.
        val hilly = fixture.edge(geometry = listOf(0.0 to 0.00027), elevations = listOf(60.0))
        val flat = fixture.edge(geometry = listOf(0.0 to 0.00027), elevations = listOf(0.0))
        RideStyle.entries.forEach { style ->
            val plain = weighting(fixture, FakeWeighting(edgeWeight = 4.0), style)
            val withHills = weighting(fixture, FakeWeighting(edgeWeight = 4.0), style, hills = true)
            // Flat roads never change; the factor only adds fun, never cost.
            assertEquals(style.label, plain.calcEdgeWeight(flat, false), withHills.calcEdgeWeight(flat, false), 1e-9)
            if (style == RideStyle.FASTEST) {
                assertEquals(plain.calcEdgeWeight(hilly, false), withHills.calcEdgeWeight(hilly, false), 0.0)
            } else {
                // A fully hilly straight road keeps (1 - 0.75)^2 of the straight-road penalty.
                val penalty = RideStyle.STRAIGHT_ROAD_PENALTY * style.curveWeight
                val kept = (1.0 - HILL_FUN_SHARE) * (1.0 - HILL_FUN_SHARE)
                assertEquals(style.label, 4.0 * (1.0 + penalty), plain.calcEdgeWeight(hilly, false), 1e-9)
                assertEquals(style.label, 4.0 * (1.0 + penalty * kept), withHills.calcEdgeWeight(hilly, false), 1e-9)
            }
        }
    }

    @Test
    fun hillsOnATwoDimensionalGraphChangeNothing() {
        val fixture = fixture()
        val straight = fixture.edge(geometry = listOf(0.0 to 0.00027))
        RideStyle.entries.forEach { style ->
            assertEquals(
                style.label,
                weighting(fixture, FakeWeighting(edgeWeight = 4.0), style).calcEdgeWeight(straight, false),
                weighting(fixture, FakeWeighting(edgeWeight = 4.0), style, hills = true).calcEdgeWeight(straight, false),
                0.0,
            )
        }
    }

    @Test
    fun stopsCostTwentySecondsEachInTheirTravelDirection() {
        val fixture = fixture()
        // Two full stops forward (4 halves), none backward.
        val edge = fixture.edge(stops = KVStorage.KValue("4", null))
        RideStyle.entries.forEach { style ->
            val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0, millis = 4000L), style, stopsAndTowns = true)
            val plain = weighting(fixture, FakeWeighting(edgeWeight = 4.0, millis = 4000L), style)
            val forward = weighting.calcEdgeWeight(edge, false)
            val reverse = weighting.calcEdgeWeight(edge, true)
            assertEquals(style.label, plain.calcEdgeWeight(edge, false) + 40.0, forward, 1e-9)
            assertEquals(style.label, plain.calcEdgeWeight(edge, true), reverse, 1e-9)
            // ETA stays the physical travel time.
            assertEquals(4000L, weighting.calcEdgeMillis(edge, false))
        }
        // A reversed edge state reads the other storage direction, also
        // through a weighting that already cached the edge via the other
        // orientation (bidirectional searches do exactly that).
        val reversedState = fixture.graph.getEdgeIteratorState(edge.edge, edge.baseNode)
        val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0), RideStyle.FASTEST, stopsAndTowns = true)
        assertEquals(44.0, weighting.calcEdgeWeight(edge, false), 1e-9)
        assertEquals(4.0, weighting.calcEdgeWeight(edge, true), 1e-9)
        assertEquals(4.0, weighting.calcEdgeWeight(reversedState, false), 1e-9)
        assertEquals(44.0, weighting.calcEdgeWeight(reversedState, true), 1e-9)
    }

    @Test
    fun splitEdgePiecesShareTheEdgesStopsByLength() {
        val fixture = fixture()
        // One interior signal each way (2 halves), split by a snapped via point.
        // QueryGraph measures pieces from geometry, so the edge length must match it.
        val length = DistanceCalcEarth.DIST_EARTH.calcDist(0.0, 0.0, 0.0, 0.00054)
        val edge = fixture.edge(distance = length, geometry = emptyList(), stops = KVStorage.KValue("2", "2"))
        val snap = Snap(0.0, 0.000135).apply {
            closestEdge = edge
            wayIndex = 0
            snappedPosition = Snap.Position.EDGE
            calcSnappedPoint(DistanceCalcEarth.DIST_EARTH)
        }
        val queryGraph = QueryGraph.create(fixture.graph, snap)
        val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0), RideStyle.FASTEST, stopsAndTowns = true)
        val pieces = buildList {
            val explorer = queryGraph.createEdgeExplorer()
            val iter = explorer.setBaseNode(snap.closestNode)
            while (iter.next()) add(iter.detach(false))
        }
        assertEquals(2, pieces.size)
        val shares = pieces.map { virtualEdgeShare(it, fixture.graph) }
        assertEquals(1.0, shares.sum(), 1e-4)
        // Both pieces together carry one stop (20 s), not one each.
        val added = pieces.sumOf { weighting.calcEdgeWeight(it, false) - 4.0 }
        assertEquals(20.0, added, 1e-2)
        // A real edge keeps its whole count.
        assertEquals(1.0, virtualEdgeShare(edge, fixture.graph), 0.0)

        // With the graph's shared table the pieces still take their share,
        // and never stand in for the whole edge in it.
        val shared = EdgeFactors(fixture.graph)
        val sharing = weighting(fixture, FakeWeighting(edgeWeight = 4.0), RideStyle.FASTEST, stopsAndTowns = true, edgeFactors = shared)
        assertEquals(20.0, pieces.sumOf { sharing.calcEdgeWeight(it, false) - 4.0 }, 1e-2)
        pieces.forEach { assertFalse(shared.covers(it.edge)) }
        assertEquals(4.0 + 20.0, sharing.calcEdgeWeight(edge, false), 1e-9)
        assertEquals(2, shared.halfStops(edge.edge, againstStorage = false))
    }

    @Test
    fun stopsAndTownsOffIgnoresTheirData() {
        val fixture = fixture()
        val edge = fixture.edge(stops = KVStorage.KValue("6"), urbanDensity = UrbanDensity.CITY)
        RideStyle.entries.forEach { style ->
            val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0), style)
            val curvyPenalty = RideStyle.STRAIGHT_ROAD_PENALTY * style.curveWeight
            assertEquals(style.label, 4.0 * (1.0 + curvyPenalty), weighting.calcEdgeWeight(edge, false), 1e-9)
        }
    }

    @Test
    fun townsMultiplyTravelTimeByTheirDensity() {
        val fixture = fixture()
        val rural = fixture.edge(urbanDensity = UrbanDensity.RURAL)
        val residential = fixture.edge(urbanDensity = UrbanDensity.RESIDENTIAL)
        val city = fixture.edge(urbanDensity = UrbanDensity.CITY)
        val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0), RideStyle.FASTEST, stopsAndTowns = true)
        assertEquals(4.0, weighting.calcEdgeWeight(rural, false), 1e-9)
        assertEquals(4.0 * 1.5, weighting.calcEdgeWeight(residential, false), 1e-9)
        assertEquals(4.0 * 2.5, weighting.calcEdgeWeight(city, false), 1e-9)
        // Older graphs without urban density: towns add nothing, routing still works.
        val withoutDensity = weighting(
            fixture, FakeWeighting(edgeWeight = 4.0), RideStyle.FASTEST, stopsAndTowns = true, withUrbanDensity = false,
        )
        assertEquals(4.0, withoutDensity.calcEdgeWeight(city, false), 1e-9)
    }

    @Test
    fun rideFactorsNeverLowerAWeightBelowTravelTimeOrBreakHardBlocks() {
        val fixture = fixture(threeD = true)
        val edges = listOf(
            fixture.edge(geometry = listOf(0.0 to 0.00027), elevations = listOf(90.0), stops = KVStorage.KValue("3", "1")),
            fixture.edge(geometry = TIGHT_CORNER, elevations = listOf(5.0, 40.0, 5.0), urbanDensity = UrbanDensity.CITY),
            fixture.edge(geometry = listOf(0.0 to 0.00027), elevations = listOf(0.0), stops = KVStorage.KValue("not a number")),
        )
        val closed = fixture.edge(forwardAccess = false, stops = KVStorage.KValue("2"))
        RideStyle.entries.forEach { style ->
            listOf(true to false, false to true, true to true).forEach { (hills, stopsAndTowns) ->
                val weighting = weighting(
                    fixture, FakeWeighting(edgeWeight = 4.0, minWeight = 0.5), style,
                    hills = hills, stopsAndTowns = stopsAndTowns,
                )
                edges.forEach { edge ->
                    listOf(false, true).forEach { reverse ->
                        val weight = weighting.calcEdgeWeight(edge, reverse)
                        assertTrue("${style.label} $hills $stopsAndTowns", weight.isFinite() && weight >= 4.0 - 1e-9)
                    }
                }
                assertEquals(Double.POSITIVE_INFINITY, weighting.calcEdgeWeight(closed, false), 0.0)
                // The admissible lower bound is still Fastest's.
                assertEquals(0.5, weighting.calcMinWeightPerDistance(), 0.0)
            }
        }
    }

    @Test
    fun fastestWithStopsAndTownsStillReadsNoGeometry() {
        val fixture = fixture()
        val geometryReads = intArrayOf(0)
        val edge = countingGeometryReads(fixture.edge(stops = KVStorage.KValue("2")), geometryReads)
        val weighting = weighting(
            fixture, FakeWeighting(edgeWeight = 4.0), RideStyle.FASTEST, hills = true, stopsAndTowns = true,
        )
        assertEquals(24.0, weighting.calcEdgeWeight(edge, false), 1e-9)
        assertEquals(0, geometryReads[0])
        assertFalse(RideFactors.NONE.any)
    }

    /**
     * Routing never hands the weighting a plain edge at the ends of a ride:
     * QueryGraph splits the snapped edge and delivers VirtualEdgeIterator
     * states, which reject `detach(true)`. Reverse reads must still work,
     * pick the right storage direction, and charge each piece its share.
     */
    @Test
    fun stopsOnASplitEdgeReadBothDirectionsThroughTheQueryGraph() {
        val fixture = fixture()
        val length = 0.00054 * 111_195.0
        val edge = fixture.edge(distance = length, stops = KVStorage.KValue("4", null))
        val snap = com.graphhopper.storage.index.Snap(0.0, 0.00027).apply {
            closestEdge = edge
            wayIndex = 1
            snappedPosition = com.graphhopper.storage.index.Snap.Position.PILLAR
            calcSnappedPoint(com.graphhopper.util.DistanceCalcEarth.DIST_EARTH)
        }
        val queryGraph = com.graphhopper.routing.querygraph.QueryGraph.create(fixture.graph, snap)
        val virtualNode = fixture.graph.nodes
        val weighting = weighting(fixture, FakeWeighting(edgeWeight = 4.0), RideStyle.FASTEST, stopsAndTowns = true)
        val seen = mutableMapOf<Int, Pair<Double, Double>>()
        val pieces = queryGraph.createEdgeExplorer().setBaseNode(virtualNode)
        while (pieces.next()) {
            // Both reads go through the iterator itself, exactly as the router does.
            seen[pieces.adjNode] = weighting.calcEdgeWeight(pieces, false) to weighting.calcEdgeWeight(pieces, true)
        }
        assertEquals(setOf(0, 1), seen.keys)
        // Each half carries half of the edge's 2 forward stops (one stop, 20 s),
        // only when travelled along storage: virtual -> 1 forward, 0 -> virtual reversed.
        assertEquals(4.0 + 20.0, seen.getValue(1).first, 1e-3)
        assertEquals(4.0, seen.getValue(1).second, 1e-3)
        assertEquals(4.0, seen.getValue(0).first, 1e-3)
        assertEquals(4.0 + 20.0, seen.getValue(0).second, 1e-3)
    }

    private fun pointListMeters(vararg points: Pair<Double, Double>) =
        PointList().apply {
            points.forEach { (x, y) -> add(y / METERS_PER_DEGREE, x / METERS_PER_DEGREE) }
        }

    private fun weighting(
        fixture: Fixture,
        fastest: Weighting,
        style: RideStyle,
        priority: Double = 1.0,
        blockUnpaved: Boolean = false,
        avoidMotorways: Boolean = false,
        siblingEdgeIds: Set<Int> = emptySet(),
        siblingEdgePenalty: Double = 0.0,
        curveWeight: Double = style.curveWeight,
        hills: Boolean = false,
        stopsAndTowns: Boolean = false,
        withUrbanDensity: Boolean = true,
        junctionPieces: Boolean = false,
        edgeFactors: EdgeFactors? = null,
    ) = ComplexityWeighting(
        fastest,
        { _, _ -> priority },
        style,
        fixture.access,
        fixture.surface,
        fixture.roadClass,
        fixture.roadAccess,
        blockUnpaved,
        avoidMotorways,
        siblingEdgeIds,
        siblingEdgePenalty,
        curveWeight,
        hills = hills,
        stopsAndTowns = stopsAndTowns,
        urbanDensityEnc = if (withUrbanDensity) fixture.urbanDensity else null,
        baseGraph = fixture.graph,
        roadClassLinkEnc = if (junctionPieces) fixture.roadClassLink else null,
        roundaboutEnc = if (junctionPieces) fixture.roundabout else null,
        edgeFactors = edgeFactors,
    )

    private fun fixture(threeD: Boolean = false): Fixture {
        val access = SimpleBooleanEncodedValue("car_access", true)
        val surface = EnumEncodedValue("surface", Surface::class.java, true)
        val roadClass = RoadClass.create()
        val roadAccess = RoadAccess.create()
        val urbanDensity = UrbanDensity.create()
        val roadClassLink = RoadClassLink.create()
        val roundabout = Roundabout.create()
        val encodingManager = EncodingManager.start()
            .add(access).add(surface).add(roadClass).add(roadAccess).add(urbanDensity)
            .add(roadClassLink).add(roundabout)
            .build()
        val graph = BaseGraph.Builder(encodingManager).set3D(threeD).create()
        if (threeD) {
            graph.nodeAccess.setNode(0, 0.0, 0.0, 0.0)
            graph.nodeAccess.setNode(1, 0.0, 0.00054, 0.0)
            graph.nodeAccess.setNode(2, 1.0, 1.0, 0.0)
            graph.nodeAccess.setNode(3, 1.0, 0.0, 0.0)
        } else {
            graph.nodeAccess.setNode(0, 0.0, 0.0)
            graph.nodeAccess.setNode(1, 0.0, 0.00054)
            graph.nodeAccess.setNode(2, 1.0, 1.0)
            graph.nodeAccess.setNode(3, 1.0, 0.0)
        }
        return Fixture(graph, access, surface, roadClass, roadAccess, urbanDensity, roadClassLink, roundabout)
    }

    private data class Fixture(
        val graph: BaseGraph,
        val access: BooleanEncodedValue,
        val surface: EnumEncodedValue<Surface>,
        val roadClass: EnumEncodedValue<RoadClass>,
        val roadAccess: EnumEncodedValue<RoadAccess>,
        val urbanDensity: EnumEncodedValue<UrbanDensity>,
        val roadClassLink: BooleanEncodedValue,
        val roundabout: BooleanEncodedValue,
    ) {
        fun edge(
            forwardAccess: Boolean = true,
            reverseAccess: Boolean = forwardAccess,
            surface: Surface = Surface.ASPHALT,
            roadClass: RoadClass = RoadClass.PRIMARY,
            roadAccess: RoadAccess = RoadAccess.YES,
            distance: Double = 1_000.0,
            geometry: List<Pair<Double, Double>> = listOf(0.0 to 0.00027),
            /** Pillar elevations for a 3D fixture, one per [geometry] point; towers sit at 0 m. */
            elevations: List<Double>? = null,
            urbanDensity: UrbanDensity = UrbanDensity.RURAL,
            stops: KVStorage.KValue? = null,
            link: Boolean = false,
            roundabout: Boolean = false,
        ): EdgeIteratorState {
            val pillars = if (graph.nodeAccess.is3D) {
                val heights = elevations ?: geometry.map { 0.0 }
                PointList(geometry.size, true).apply {
                    geometry.forEachIndexed { index, (lat, lon) -> add(lat, lon, heights[index]) }
                }
            } else {
                PointList().apply { geometry.forEach { (lat, lon) -> add(lat, lon) } }
            }
            val edge = graph.edge(0, 1)
                .setDistance(distance)
                .setWayGeometry(pillars)
                .set(access, forwardAccess)
                .setReverse(access, reverseAccess)
                .set(this.surface, surface)
                .set(this.roadClass, roadClass)
                .set(this.roadAccess, roadAccess)
                .set(this.urbanDensity, urbanDensity)
                .set(roadClassLink, link)
                .set(this.roundabout, roundabout)
            if (stops != null) edge.setKeyValues(mapOf(MOTO_STOPS_DETAIL to stops))
            return edge
        }
    }

    private class FakeWeighting(
        private val edgeWeight: Double,
        private val millis: Long = 0L,
        private val minWeight: Double = 0.0,
        private val turnWeight: Double = 0.0,
    ) : Weighting {
        override fun calcMinWeightPerDistance(): Double = minWeight
        override fun calcEdgeWeight(edgeState: EdgeIteratorState, reverse: Boolean): Double = edgeWeight
        override fun calcEdgeMillis(edgeState: EdgeIteratorState, reverse: Boolean): Long = millis
        override fun calcTurnWeight(inEdge: Int, viaNode: Int, outEdge: Int): Double = turnWeight
        override fun calcTurnMillis(inEdge: Int, viaNode: Int, outEdge: Int): Long = 0L
        override fun hasTurnCosts(): Boolean = false
        override fun getName(): String = "fake"
    }

    /** [edge] counting calls per method name into [reads]. */
    private fun countingReads(
        edge: EdgeIteratorState,
        reads: MutableMap<String, Int>,
    ): EdgeIteratorState = Proxy.newProxyInstance(
        EdgeIteratorState::class.java.classLoader,
        arrayOf(EdgeIteratorState::class.java),
    ) { _, method, args ->
        reads.merge(method.name, 1, Int::plus)
        try {
            method.invoke(edge, *(args ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    } as EdgeIteratorState

    private fun countingGeometryReads(
        edge: EdgeIteratorState,
        reads: IntArray,
    ): EdgeIteratorState = Proxy.newProxyInstance(
        EdgeIteratorState::class.java.classLoader,
        arrayOf(EdgeIteratorState::class.java),
    ) { _, method, args ->
        if (method.name == "fetchWayGeometry") reads[0]++
        try {
            method.invoke(edge, *(args ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    } as EdgeIteratorState

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0

        /** Two 30 m legs around a right angle: full curve exposure. */
        val TIGHT_CORNER = listOf(
            0.0 to 0.00027,
            0.00027 to 0.00027,
            0.00027 to 0.00054,
        )
    }
}
