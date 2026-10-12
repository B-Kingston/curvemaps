package com.curvemaps.app.routing

import com.graphhopper.routing.Dijkstra
import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.EnumEncodedValue
import com.graphhopper.routing.ev.RoadAccess
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.RoadClassLink
import com.graphhopper.routing.ev.Roundabout
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue
import com.graphhopper.routing.ev.Surface
import com.graphhopper.routing.querygraph.QueryGraph
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.routing.util.TraversalMode
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.storage.BaseGraph
import com.graphhopper.storage.index.Snap
import com.graphhopper.util.DistanceCalcEarth
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.PointList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue
import kotlin.math.hypot
import kotlin.random.Random

class SmallLoopEdgesTest {

    @Test
    fun flagsExactlyTheEdgesAnotherDrivablePathJoinsWithinThePerimeter() {
        repeat(40) { seed ->
            val net = randomNet(Random(seed))
            val loops = SmallLoopEdges(net.graph, net.access)
            for (edge in 0 until net.graph.edges) {
                val state = net.graph.getEdgeIteratorState(edge, Int.MIN_VALUE)
                val around = shortestDrivableDetour(net, edge)
                val expected = state.distance + around <= SmallLoopEdges.SMALL_LOOP_PERIMETER_METERS
                assertEquals("seed $seed edge $edge (${state.distance} m, other way round $around m)", expected, loops.isOnSmallLoop(edge))
            }
        }
    }

    @Test
    fun aCurvyStopNoLongerLeavesTheMainRoadThroughAJunctionTriangle() {
        val junction = taintonsJunction()
        RideStyle.entries.forEach { style ->
            assertEquals(style.label, junction.mainRoad, route(junction.net, style, junction.south, junction.north, smallLoops = true))
        }
        // Without the rule, the slightly kinked 18 m stub and the curved 46 m slip
        // lane scored as fully curvy and beat 39 m of straight main road from
        // Balanced up (they are 1.65x as long; Balanced charges straight road 1.9x).
        val jogging = RideStyle.entries.filter { route(junction.net, it, junction.south, junction.north, smallLoops = false) != junction.mainRoad }
        assertEquals(RideStyle.entries.filter { it.sameSpeedBreakEven > 1.7 }, jogging)
    }

    @Test
    fun theSideRoadBeyondTheJunctionKeepsItsCurveCredit() {
        val junction = taintonsJunction()
        val weighting = weighting(junction.net, RideStyle.MAX_TWISTY, smallLoops = true)
        val sideRoad = junction.net.graph.getEdgeIteratorState(junction.sideRoad, Int.MIN_VALUE)
        val stub = junction.net.graph.getEdgeIteratorState(junction.stub, Int.MIN_VALUE)
        val fastest = junction.net.fastest
        assertEquals(fastest.calcEdgeWeight(sideRoad, false), weighting.calcEdgeWeight(sideRoad, false), 1e-6)
        assertEquals(fastest.calcEdgeWeight(stub, false) * RideStyle.MAX_TWISTY.sameSpeedBreakEven, weighting.calcEdgeWeight(stub, false), 1e-6)
    }

    @Test
    fun aSplitPieceOfALoopEdgeEarnsNoCurveCreditEither() {
        val junction = taintonsJunction()
        val slip = junction.net.graph.getEdgeIteratorState(junction.slip, Int.MIN_VALUE)
        val points = slip.fetchWayGeometry(com.graphhopper.util.FetchMode.ALL)
        val middle = points.size() / 2
        val snap = Snap(points.getLat(middle), points.getLon(middle)).apply {
            closestEdge = slip
            wayIndex = middle
            snappedPosition = Snap.Position.PILLAR
            calcSnappedPoint(DistanceCalcEarth.DIST_EARTH)
        }
        val queryGraph = QueryGraph.create(junction.net.graph, snap)
        val weighting = weighting(junction.net, RideStyle.CURVY, smallLoops = true)
        val pieces = queryGraph.createEdgeExplorer().setBaseNode(junction.net.graph.nodes)
        var count = 0
        while (pieces.next()) {
            count++
            // Each half still bends: it would earn full credit off a loop.
            val piece = pieces.fetchWayGeometry(com.graphhopper.util.FetchMode.ALL)
            assertEquals(1.0, combinedCurveExposure(piece, pieces.distance), 1e-9)
            val time = junction.net.fastest.calcEdgeWeight(pieces, false)
            assertEquals(time * RideStyle.CURVY.sameSpeedBreakEven, weighting.calcEdgeWeight(pieces, false), 1e-6)
        }
        assertEquals(2, count)
    }

    @Test
    fun aCurvyRoadOffAnySmallLoopKeepsItsCredit() {
        val net = Net()
        net.node(0, 0.0, 0.0)
        net.node(1, 0.0, 300.0)
        net.node(2, 300.0, 300.0)
        net.node(3, 300.0, 0.0)
        // A 300 m square: every side is on a loop, but a 1.2 km one.
        val sides = listOf(net.road(0, 1, bend = 60.0), net.road(1, 2, bend = 60.0), net.road(2, 3, bend = 60.0), net.road(3, 0, bend = 60.0))
        val loops = SmallLoopEdges(net.graph, net.access)
        sides.forEach { assertFalse(loops.isOnSmallLoop(it)) }
        val weighting = weighting(net, RideStyle.MAX_TWISTY, smallLoops = true)
        sides.forEach { edge ->
            val state = net.graph.getEdgeIteratorState(edge, Int.MIN_VALUE)
            assertEquals(net.fastest.calcEdgeWeight(state, false), weighting.calcEdgeWeight(state, false), 1e-6)
        }
    }

    @Test
    fun aFootpathDoesNotCloseALoop() {
        val net = Net()
        net.node(0, 0.0, 0.0)
        net.node(1, 0.0, 60.0)
        net.node(2, 40.0, 30.0)
        val road = net.road(0, 1, bend = 10.0)
        net.road(1, 2, accessible = false)
        net.road(2, 0, accessible = false)
        assertFalse(SmallLoopEdges(net.graph, net.access).isOnSmallLoop(road))
    }

    /**
     * The invariant behind the rule: with every edge of a small loop paying the
     * same multiple of its travel time, no curvy stop leaves a road for a
     * detour that a faster way closes within the perimeter. Random grids with
     * diagonals (junction triangles), bends, one-way roads and footpaths. The
     * control proves the grids do provoke such jogs without the rule.
     */
    @Test
    fun curvyStopsTakeNoJogWithinThePerimeterOnRandomNetworks() {
        var withoutRule = 0
        var routes = 0
        repeat(60) { seed ->
            val random = Random(1_000 + seed)
            val net = randomNet(random)
            repeat(6) {
                val from = random.nextInt(net.graph.nodes)
                val to = random.nextInt(net.graph.nodes)
                if (from == to) return@repeat
                RideStyle.entries.drop(1).forEach { style ->
                    val edges = route(net, style, from, to, smallLoops = true) ?: return@forEach
                    routes++
                    val jogs = junctionJogs(net.graph, net.fastest, edges, minSavingSeconds = 1e-9)
                    assertTrue("seed $seed ${style.label} $from->$to jogs $jogs", jogs.isEmpty())
                    val control = route(net, style, from, to, smallLoops = false) ?: return@forEach
                    withoutRule += junctionJogs(net.graph, net.fastest, control, minSavingSeconds = 1e-9).size
                }
            }
        }
        assertTrue("too few routes: $routes", routes > 500)
        assertTrue("the networks never provoked a jog without the rule", withoutRule > 20)
    }

    private fun route(net: Net, style: RideStyle, from: Int, to: Int, smallLoops: Boolean): List<Int>? {
        val path = Dijkstra(net.graph, weighting(net, style, smallLoops), TraversalMode.NODE_BASED).calcPath(from, to)
        return if (path.isFound) path.calcEdges().map { it.edge } else null
    }

    private fun weighting(net: Net, style: RideStyle, smallLoops: Boolean) = ComplexityWeighting(
        net.fastest,
        { _, _ -> 1.0 },
        style,
        net.access,
        net.surface,
        net.roadClass,
        net.roadAccess,
        blockUnpaved = false,
        avoidMotorways = false,
        siblingEdgeIds = emptySet(),
        siblingEdgePenalty = 0.0,
        baseGraph = net.graph,
        roadClassLinkEnc = net.link,
        roundaboutEnc = net.roundabout,
        smallLoops = if (smallLoops) SmallLoopEdges(net.graph, net.access) else null,
    )

    /** Undirected shortest path between [edge]'s ends over edges drivable either way, never using [edge]. */
    private fun shortestDrivableDetour(net: Net, edge: Int): Double {
        val state = net.graph.getEdgeIteratorState(edge, Int.MIN_VALUE)
        val best = HashMap<Int, Double>().apply { put(state.baseNode, 0.0) }
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first }).apply { add(0.0 to state.baseNode) }
        val explorer = net.graph.createEdgeExplorer()
        while (queue.isNotEmpty()) {
            val (distance, node) = queue.remove()
            if (node == state.adjNode) return distance
            if (distance > best.getValue(node)) continue
            val it = explorer.setBaseNode(node)
            while (it.next()) {
                if (it.edge == edge || (!it.get(net.access) && !it.getReverse(net.access))) continue
                val next = distance + it.distance
                if (next < (best[it.adjNode] ?: Double.POSITIVE_INFINITY)) {
                    best[it.adjNode] = next
                    queue.add(next to it.adjNode)
                }
            }
        }
        return Double.POSITIVE_INFINITY
    }

    private data class Junction(
        val net: Net,
        val south: Int,
        val north: Int,
        val mainRoad: List<Int>,
        val stub: Int,
        val slip: Int,
        val sideRoad: Int,
    )

    /**
     * Woombye Palmwoods Road at Taintons Road, as the Queensland graph has it:
     * the northbound main road passes the side road's 18 m stub, then 39 m on
     * meets the curved 46 m slip lane from the side road's end of the triangle.
     */
    private fun taintonsJunction(): Junction {
        val net = Net()
        net.node(0, 0.0, -115.0) // south on the main road
        net.node(1, 0.0, 0.0) // the stub meets the main road
        net.node(2, 0.0, 39.0) // the slip lane meets the main road
        net.node(3, 0.0, 200.0) // north on the main road
        net.node(4, -18.0, 0.0) // the side road's end of the triangle
        net.node(5, -150.0, 20.0) // along the side road
        val south = net.road(0, 1, oneWay = true)
        val middle = net.road(1, 2, oneWay = true)
        val north = net.road(2, 3, oneWay = true)
        val stub = net.road(4, 1, bend = 2.0)
        val slip = net.road(4, 2, bend = 8.0)
        val sideRoad = net.road(5, 4, bend = 60.0)
        return Junction(net, 0, 3, listOf(south, middle, north), stub, slip, sideRoad)
    }

    private fun randomNet(random: Random): Net {
        val net = Net()
        val size = 5
        val ids = Array(size) { IntArray(size) }
        var spacingX = 0.0
        for (i in 0 until size) {
            var y = 0.0
            for (j in 0 until size) {
                val id = i * size + j
                ids[i][j] = id
                net.node(id, spacingX + random.nextDouble(-10.0, 10.0), y + random.nextDouble(-10.0, 10.0))
                y += random.nextDouble(50.0, 170.0)
            }
            spacingX += random.nextDouble(50.0, 170.0)
        }
        fun randomRoad(a: Int, b: Int) {
            val bent = random.nextDouble() < 0.5
            net.road(
                a, b,
                bend = if (bent) random.nextDouble(-0.45, 0.45) * net.chord(a, b) else 0.0,
                oneWay = random.nextDouble() < 0.1,
                accessible = random.nextDouble() > 0.05,
                speedKmh = listOf(40.0, 60.0, 80.0, 100.0)[random.nextInt(4)],
                roadClass = listOf(RoadClass.PRIMARY, RoadClass.SECONDARY, RoadClass.TERTIARY, RoadClass.UNCLASSIFIED, RoadClass.RESIDENTIAL)[random.nextInt(5)],
                link = random.nextDouble() < 0.05,
            )
        }
        for (i in 0 until size) for (j in 0 until size) {
            if (i + 1 < size && random.nextDouble() < 0.85) randomRoad(ids[i][j], ids[i + 1][j])
            if (j + 1 < size && random.nextDouble() < 0.85) randomRoad(ids[i][j], ids[i][j + 1])
            if (i + 1 < size && j + 1 < size && random.nextDouble() < 0.3) randomRoad(ids[i][j], ids[i + 1][j + 1])
        }
        return net
    }

    /** A small metric road network (x east, y north, metres) with the weighting's encoded values. */
    private class Net {
        val access: BooleanEncodedValue = SimpleBooleanEncodedValue("car_access", true)
        val surface: EnumEncodedValue<Surface> = EnumEncodedValue("surface", Surface::class.java, true)
        val roadClass: EnumEncodedValue<RoadClass> = RoadClass.create()
        val roadAccess: EnumEncodedValue<RoadAccess> = RoadAccess.create()
        val link: BooleanEncodedValue = RoadClassLink.create()
        val roundabout: BooleanEncodedValue = Roundabout.create()
        val graph: BaseGraph = BaseGraph.Builder(
            EncodingManager.start().add(access).add(surface).add(roadClass).add(roadAccess).add(link).add(roundabout).build(),
        ).create()
        private val speeds = HashMap<Int, Double>()
        private val xs = HashMap<Int, Double>()
        private val ys = HashMap<Int, Double>()

        /** Physical travel time at each road's speed, like the app's FastestWeighting. */
        val fastest = object : Weighting {
            override fun calcMinWeightPerDistance() = 3.6 / 100.0
            override fun calcEdgeWeight(edgeState: EdgeIteratorState, reverse: Boolean): Double {
                val open = if (reverse) edgeState.getReverse(access) else edgeState.get(access)
                return if (open) edgeState.distance * 3.6 / (speeds[edgeState.edge] ?: 60.0) else Double.POSITIVE_INFINITY
            }
            override fun calcEdgeMillis(edgeState: EdgeIteratorState, reverse: Boolean) = (calcEdgeWeight(edgeState, reverse) * 1000).toLong()
            override fun calcTurnWeight(inEdge: Int, viaNode: Int, outEdge: Int) = 0.0
            override fun calcTurnMillis(inEdge: Int, viaNode: Int, outEdge: Int) = 0L
            override fun hasTurnCosts() = false
            override fun getName() = "test_fastest"
        }

        fun node(id: Int, x: Double, y: Double) {
            xs[id] = x
            ys[id] = y
            graph.nodeAccess.setNode(id, y / METERS_PER_DEGREE, x / METERS_PER_DEGREE)
        }

        fun chord(a: Int, b: Int) = hypot(xs.getValue(b) - xs.getValue(a), ys.getValue(b) - ys.getValue(a))

        /**
         * A road from [a] to [b]; [bend] bows it sideways into a parabola whose
         * middle sits that many metres off the chord, sampled at seven pillars.
         */
        fun road(
            a: Int,
            b: Int,
            bend: Double = 0.0,
            oneWay: Boolean = false,
            accessible: Boolean = true,
            speedKmh: Double = 60.0,
            roadClass: RoadClass = RoadClass.TERTIARY,
            link: Boolean = false,
        ): Int {
            val ax = xs.getValue(a)
            val ay = ys.getValue(a)
            val bx = xs.getValue(b)
            val by = ys.getValue(b)
            val chord = chord(a, b)
            val pillars = PointList()
            val samples = if (bend != 0.0) 8 else 1
            var length = 0.0
            var lastLat = ay / METERS_PER_DEGREE
            var lastLon = ax / METERS_PER_DEGREE
            for (step in 1..samples) {
                val t = step.toDouble() / samples
                val offset = bend * 4 * t * (1 - t)
                val lat = (ay + (by - ay) * t + (bx - ax) / chord * offset) / METERS_PER_DEGREE
                val lon = (ax + (bx - ax) * t - (by - ay) / chord * offset) / METERS_PER_DEGREE
                length += DistanceCalcEarth.DIST_EARTH.calcDist(lastLat, lastLon, lat, lon)
                if (step < samples) pillars.add(lat, lon)
                lastLat = lat
                lastLon = lon
            }
            val edge = graph.edge(a, b)
                .setDistance(length)
                .setWayGeometry(pillars)
                .set(access, accessible)
                .setReverse(access, accessible && !oneWay)
                .set(surface, Surface.ASPHALT)
                .set(this.roadClass, roadClass)
                .set(roadAccess, RoadAccess.YES)
                .set(this.link, link)
            speeds[edge.edge] = speedKmh
            return edge.edge
        }
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_195.0
    }
}
