package com.curvemaps.app.routing

import com.graphhopper.coll.GHIntHashSet
import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.storage.BaseGraph
import com.graphhopper.storage.index.LocationIndex
import com.graphhopper.storage.index.Snap
import com.graphhopper.util.DistanceCalcEarth
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.shapes.GHPoint
import kotlin.math.cos
import kotlin.math.sin

/**
 * Moves a look-around via point ([DistinctRideStyles.detourRings]) onto the
 * nearest through road. A bare coordinate snaps to whatever road is closest,
 * and around a town that is usually a cul-de-sac or a court in a
 * subdivision: the ride then goes in and comes straight back out, which
 * GraphHopper's pass-through rule cannot prevent on a dead end (it only
 * forbids turning onto the edge the via was reached by). A via on a road
 * built to be ridden through makes the ride a loop instead.
 *
 * A look-around via ([twoWay]) must also sit on a two-way road that is not
 * a motorway. A via on one carriageway of a divided road can only be passed
 * one way, so a ride that needs the other direction drives out to the next
 * turning place and back on the other carriageway, which the edge-based
 * "drives a road twice" check cannot see: Samford Valley to Lowood rode
 * 4.7 km east on the Warrego Highway and straight back for a via on its
 * interchange. Round-trip corners keep the plain rule: with it two-way,
 * Samford's 80 km Fastest loop shrank to 59 km (a corner moved off its
 * carriageway), and a mid-ride rebuild snaps a loop's corners again with
 * the plain [snap], which must find the same roads.
 */
internal object ThroughRoadSnap {
    /** Road classes a via point or round-trip corner may snap to: never residential, service or track. */
    val THROUGH_ROAD_CLASSES: Set<RoadClass> = setOf(
        RoadClass.MOTORWAY, RoadClass.TRUNK, RoadClass.PRIMARY, RoadClass.SECONDARY,
        RoadClass.TERTIARY, RoadClass.UNCLASSIFIED,
    )

    /**
     * A road of [THROUGH_ROAD_CLASSES] with car access; with [twoWay] (a
     * look-around via) only one that is not a motorway and can be passed in
     * either direction.
     */
    private fun viaRoad(
        edge: EdgeIteratorState,
        roadClassEnc: com.graphhopper.routing.ev.EnumEncodedValue<RoadClass>,
        accessEnc: BooleanEncodedValue,
        twoWay: Boolean,
    ): Boolean {
        val roadClass = edge.get(roadClassEnc)
        if (roadClass !in THROUGH_ROAD_CLASSES) return false
        return if (twoWay) roadClass != RoadClass.MOTORWAY && edge.get(accessEnc) && edge.getReverse(accessEnc) else carAccess(edge, accessEnc)
    }

    /**
     * The nearest through road with car access ([twoWay]: the nearest two-way,
     * non-motorway one, for a look-around via), or null when the index finds
     * none around [point].
     */
    fun snap(index: LocationIndex, encodingManager: EncodingManager, point: GHPoint, twoWay: Boolean = false): GHPoint? {
        val roadClassEnc = encodingManager.getEnumEncodedValue(RoadClass.KEY, RoadClass::class.java)
        val accessEnc = encodingManager.getBooleanEncodedValue("car_access")
        val snap = index.findClosest(point.lat, point.lon) { edge -> viaRoad(edge, roadClassEnc, accessEnc, twoWay) }
        if (!snap.isValid) return null
        return GHPoint(snap.snappedPoint.lat, snap.snappedPoint.lon)
    }

    /** Dead-end roads skipped around one search point before it gives up on that point. */
    const val MAX_DEAD_ENDS_PER_POINT = 6

    /** Junctions a pocket check explores on one side before it calls that side open country. */
    const val POCKET_NODE_BUDGET = 2_000

    /** Spacing of the rings [snapPassable] searches when nothing is near the point itself. */
    const val RING_STEP_METRES = 1_500.0
    const val RING_BEARINGS = 8

    /**
     * Like [snap], for a round-trip corner ([RoundTrips]) that the ride must
     * pass straight through: the nearest through road that does not lead
     * into a pocket. Classes alone do not tell: plenty of rural tertiary and
     * unclassified roads dead-end at a farm, and with GraphHopper's
     * pass-through rule a ride that reached one could never come back out
     * ("Connection between locations not found"). When neither the point nor
     * its own surroundings hold such a road (a corner in the bay or deep in a
     * forest) it searches rings every [RING_STEP_METRES] out to
     * [searchMetres] and takes the passable road nearest the point; null when
     * there is none.
     */
    fun snapPassable(
        graph: BaseGraph,
        index: LocationIndex,
        encodingManager: EncodingManager,
        point: GHPoint,
        searchMetres: Double,
    ): GHPoint? {
        val roadClassEnc = encodingManager.getEnumEncodedValue(RoadClass.KEY, RoadClass::class.java)
        val accessEnc = encodingManager.getBooleanEncodedValue("car_access")
        val rejected = GHIntHashSet()
        val ringCount = (searchMetres / RING_STEP_METRES).toInt().coerceAtLeast(0)
        for (ring in 0..ringCount) {
            val probes = if (ring == 0) listOf(point) else ringPoints(point, ring * RING_STEP_METRES)
            val nearest = probes
                .mapNotNull { probe -> nearestPassable(graph, index, roadClassEnc, accessEnc, probe, rejected) }
                .minByOrNull { DistanceCalcEarth.DIST_EARTH.calcDist(point.lat, point.lon, it.lat, it.lon) }
            if (nearest != null) return nearest
        }
        return null
    }

    private fun nearestPassable(
        graph: BaseGraph,
        index: LocationIndex,
        roadClassEnc: com.graphhopper.routing.ev.EnumEncodedValue<RoadClass>,
        accessEnc: BooleanEncodedValue,
        probe: GHPoint,
        rejected: GHIntHashSet,
    ): GHPoint? {
        repeat(MAX_DEAD_ENDS_PER_POINT) {
            val snap = index.findClosest(probe.lat, probe.lon) { edge ->
                !rejected.contains(edge.edge) && viaRoad(edge, roadClassEnc, accessEnc, twoWay = false)
            }
            if (!snap.isValid) return null
            // A junction can be left along any of its roads.
            if (snap.snappedPosition == Snap.Position.TOWER) return GHPoint(snap.snappedPoint.lat, snap.snappedPoint.lon)
            val pocket = pocketBehind(graph, accessEnc, snap.closestEdge) ?: return GHPoint(snap.snappedPoint.lat, snap.snappedPoint.lon)
            // The road and everything behind it is one dead end: never try it again.
            rejected.add(snap.closestEdge.edge)
            pocket.forEach { rejected.add(it) }
        }
        return null
    }

    /**
     * The edges of the dead-end pocket [edge] leads into, or null when the
     * edge lies on a loop of the road network (a ride can come in one end
     * and leave by the other). Looks for a way round from each end that does
     * not use [edge]; a side that runs out of roads within
     * [POCKET_NODE_BUDGET] junctions is a pocket. Both sides too big to
     * exhaust count as passable.
     */
    fun pocketBehind(graph: BaseGraph, accessEnc: BooleanEncodedValue, edge: EdgeIteratorState): Set<Int>? {
        val base = edge.baseNode
        val adj = edge.adjNode
        if (base == adj) return null
        for ((from, to) in listOf(adj to base, base to adj)) {
            when (val search = searchAround(graph, accessEnc, edge.edge, from, to)) {
                is SideSearch.Reached -> return null
                is SideSearch.Pocket -> return search.edges
                SideSearch.Open -> Unit
            }
        }
        return null
    }

    private sealed interface SideSearch {
        data object Reached : SideSearch
        data object Open : SideSearch
        class Pocket(val edges: Set<Int>) : SideSearch
    }

    private fun searchAround(graph: BaseGraph, accessEnc: BooleanEncodedValue, skipEdge: Int, from: Int, target: Int): SideSearch {
        val explorer = graph.createEdgeExplorer()
        val visited = GHIntHashSet()
        val edges = HashSet<Int>()
        val queue = ArrayDeque<Int>()
        visited.add(from)
        queue.add(from)
        while (queue.isNotEmpty()) {
            if (visited.size() > POCKET_NODE_BUDGET) return SideSearch.Open
            val node = queue.removeFirst()
            val iterator = explorer.setBaseNode(node)
            while (iterator.next()) {
                if (iterator.edge == skipEdge || !carAccess(iterator, accessEnc)) continue
                edges.add(iterator.edge)
                val next = iterator.adjNode
                if (next == target) return SideSearch.Reached
                if (visited.add(next)) queue.add(next)
            }
        }
        return SideSearch.Pocket(edges)
    }

    private fun carAccess(edge: EdgeIteratorState, accessEnc: BooleanEncodedValue): Boolean =
        edge.get(accessEnc) || edge.getReverse(accessEnc)

    private fun ringPoints(center: GHPoint, radiusMetres: Double): List<GHPoint> {
        val metresPerDegreeLat = DistanceCalcEarth.METERS_PER_DEGREE
        val metresPerDegreeLon = metresPerDegreeLat * cos(Math.toRadians(center.lat))
        return List(RING_BEARINGS) { index ->
            val bearing = Math.toRadians(index * 360.0 / RING_BEARINGS)
            GHPoint(
                center.lat + radiusMetres * cos(bearing) / metresPerDegreeLat,
                center.lon + radiusMetres * sin(bearing) / metresPerDegreeLon,
            )
        }
    }
}
