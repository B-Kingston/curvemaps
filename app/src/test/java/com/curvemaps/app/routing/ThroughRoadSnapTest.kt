package com.curvemaps.app.routing

import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.storage.BaseGraph
import com.graphhopper.storage.RAMDirectory
import com.graphhopper.storage.index.LocationIndexTree
import com.graphhopper.util.DistanceCalcEarth
import com.graphhopper.util.shapes.GHPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughRoadSnapTest {
    private val access = SimpleBooleanEncodedValue("car_access", true)
    private val roadClass = RoadClass.create()
    private val encodingManager = EncodingManager.start().add(access).add(roadClass).build()

    @Test
    fun aViaPointMovesPastTheNearestCulDeSacOntoAThroughRoad() {
        val graph = BaseGraph.Builder(encodingManager).create()
        // A residential street right at the via point, a tertiary road 300 m east.
        graph.nodeAccess.setNode(0, -27.0000, 153.0000)
        graph.nodeAccess.setNode(1, -27.0020, 153.0000)
        graph.nodeAccess.setNode(2, -27.0000, 153.0030)
        graph.nodeAccess.setNode(3, -27.0020, 153.0030)
        graph.edge(0, 1).setDistance(222.0).set(access, true).setReverse(access, true).set(roadClass, RoadClass.RESIDENTIAL)
        graph.edge(2, 3).setDistance(222.0).set(access, true).setReverse(access, true).set(roadClass, RoadClass.TERTIARY)
        val index = LocationIndexTree(graph, RAMDirectory()).prepareIndex()
        val via = GHPoint(-27.0010, 153.0001)
        val snapped = ThroughRoadSnap.snap(index, encodingManager, via)!!
        assertEquals(153.0030, snapped.lon, 1e-6)
        assertTrue(DistanceCalcEarth.DIST_EARTH.calcDist(via.lat, via.lon, snapped.lat, snapped.lon) > 250.0)
    }

    @Test
    fun aThroughRoadWithoutCarAccessDoesNotCount() {
        val graph = BaseGraph.Builder(encodingManager).create()
        graph.nodeAccess.setNode(0, -27.0000, 153.0000)
        graph.nodeAccess.setNode(1, -27.0020, 153.0000)
        graph.edge(0, 1).setDistance(222.0).set(access, false).setReverse(access, false).set(roadClass, RoadClass.PRIMARY)
        val index = LocationIndexTree(graph, RAMDirectory()).prepareIndex()
        assertNull(ThroughRoadSnap.snap(index, encodingManager, GHPoint(-27.0010, 153.0001)))
    }

    /**
     * A square of tertiary roads (a loop a ride can pass through) 600 m east
     * of a tertiary spur that dead-ends right at the corner point.
     */
    private fun squareWithASpur(): BaseGraph {
        val graph = BaseGraph.Builder(encodingManager).create()
        fun road(from: Int, to: Int, distance: Double, kind: RoadClass = RoadClass.TERTIARY) =
            graph.edge(from, to).setDistance(distance).set(access, true).setReverse(access, true).set(roadClass, kind)
        // The square: 0-1-2-3-0, about 1.1 km a side.
        graph.nodeAccess.setNode(0, -27.000, 153.006)
        graph.nodeAccess.setNode(1, -27.010, 153.006)
        graph.nodeAccess.setNode(2, -27.010, 153.017)
        graph.nodeAccess.setNode(3, -27.000, 153.017)
        road(0, 1, 1_112.0)
        road(1, 2, 1_090.0)
        road(2, 3, 1_112.0)
        road(3, 0, 1_090.0)
        // The spur: off the square at 0, west to a farm gate at 5 with a driveway to 6.
        graph.nodeAccess.setNode(4, -27.005, 153.006)
        graph.nodeAccess.setNode(5, -27.005, 153.000)
        graph.nodeAccess.setNode(6, -27.006, 152.999)
        // Split the square's west side at 4 so the spur joins it there.
        road(4, 5, 595.0)
        road(5, 6, 150.0, RoadClass.SERVICE)
        return graph
    }

    @Test
    fun aRoundTripCornerSkipsADeadEndRoadForOneItCanRideThrough() {
        val graph = squareWithASpur()
        val index = LocationIndexTree(graph, RAMDirectory()).prepareIndex()
        val corner = GHPoint(-27.0052, 153.0015)
        // The plain snap takes the dead end: a pass-through ride could never leave it.
        assertEquals(-27.005, ThroughRoadSnap.snap(index, encodingManager, corner)!!.lat, 1e-4)
        val passable = ThroughRoadSnap.snapPassable(graph, index, encodingManager, corner, searchMetres = 0.0)!!
        assertEquals(153.006, passable.lon, 1e-6)
    }

    @Test
    fun aRoadOnALoopIsPassableAndADeadEndIsAPocket() {
        val graph = squareWithASpur()
        val explorer = graph.createEdgeExplorer()
        fun edgeBetween(from: Int, to: Int) = explorer.setBaseNode(from).let { iterator ->
            generateSequence { if (iterator.next()) iterator.detach(false) else null }.first { it.adjNode == to }
        }
        assertNull(ThroughRoadSnap.pocketBehind(graph, access, edgeBetween(1, 2)))
        val pocket = ThroughRoadSnap.pocketBehind(graph, access, edgeBetween(4, 5))!!
        // The pocket is the driveway behind the spur, never the square.
        assertEquals(setOf(edgeBetween(5, 6).edge), pocket)
    }

    @Test
    fun aCornerWithNoRoadNearbySearchesRingsOutToItsLimit() {
        val graph = squareWithASpur()
        // A region as big as a real map, so the index's own search stays
        // within a few hundred metres of the point as it does on the phone.
        graph.nodeAccess.setNode(7, -27.400, 153.400)
        val index = LocationIndexTree(graph, RAMDirectory()).setMinResolutionInMeter(100).setMaxRegionSearch(2).prepareIndex()
        // 3 km south of the square, beyond the index's own search: the second
        // ring (3 km) reaches the square's south side.
        val corner = GHPoint(-27.010 - 3_000.0 / DistanceCalcEarth.METERS_PER_DEGREE, 153.011)
        assertNull(ThroughRoadSnap.snapPassable(graph, index, encodingManager, corner, searchMetres = 0.0))
        assertNull(ThroughRoadSnap.snapPassable(graph, index, encodingManager, corner, searchMetres = 1_500.0))
        val found = ThroughRoadSnap.snapPassable(graph, index, encodingManager, corner, searchMetres = 3_000.0)!!
        // The nearest passable road: the square's south side.
        assertEquals(-27.010, found.lat, 1e-4)
    }

    @Test
    fun onlyRoadsBuiltToBeRiddenThroughQualify() {
        assertEquals(
            setOf(RoadClass.MOTORWAY, RoadClass.TRUNK, RoadClass.PRIMARY, RoadClass.SECONDARY, RoadClass.TERTIARY, RoadClass.UNCLASSIFIED),
            ThroughRoadSnap.THROUGH_ROAD_CLASSES,
        )
    }

    /**
     * A motorway right at the point, a one-way trunk carriageway 100 m east
     * and a two-way tertiary road 400 m east: only the last can be passed
     * through either way.
     */
    private fun motorwayCarriagewayAndTwoWayRoad(withMotorway: Boolean = true): BaseGraph {
        val graph = BaseGraph.Builder(encodingManager).create()
        graph.nodeAccess.setNode(0, -27.0000, 153.0000)
        graph.nodeAccess.setNode(1, -27.0020, 153.0000)
        graph.nodeAccess.setNode(2, -27.0000, 153.0010)
        graph.nodeAccess.setNode(3, -27.0020, 153.0010)
        graph.nodeAccess.setNode(4, -27.0000, 153.0040)
        graph.nodeAccess.setNode(5, -27.0020, 153.0040)
        if (withMotorway) graph.edge(0, 1).setDistance(222.0).set(access, true).setReverse(access, true).set(roadClass, RoadClass.MOTORWAY)
        graph.edge(2, 3).setDistance(222.0).set(access, true).setReverse(access, false).set(roadClass, RoadClass.TRUNK)
        graph.edge(4, 5).setDistance(222.0).set(access, true).setReverse(access, true).set(roadClass, RoadClass.TERTIARY)
        return graph
    }

    @Test
    fun aLookAroundViaSkipsMotorwaysAndOneWayCarriagewaysForATwoWayRoad() {
        val graph = motorwayCarriagewayAndTwoWayRoad()
        val index = LocationIndexTree(graph, RAMDirectory()).prepareIndex()
        val point = GHPoint(-27.0010, 153.0001)
        assertEquals(153.0040, ThroughRoadSnap.snap(index, encodingManager, point, twoWay = true)!!.lon, 1e-6)
        // Round-trip corners and their rebuilds keep the plain rule: the nearest through road.
        assertEquals(153.0000, ThroughRoadSnap.snap(index, encodingManager, point)!!.lon, 1e-6)
        // Without the motorway, the plain rule takes the one-way carriageway.
        val again = LocationIndexTree(motorwayCarriagewayAndTwoWayRoad(withMotorway = false), RAMDirectory()).prepareIndex()
        assertEquals(153.0010, ThroughRoadSnap.snap(again, encodingManager, point)!!.lon, 1e-6)
        assertEquals(153.0040, ThroughRoadSnap.snap(again, encodingManager, point, twoWay = true)!!.lon, 1e-6)
    }
}
