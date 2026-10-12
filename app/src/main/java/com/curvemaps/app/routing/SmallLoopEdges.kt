package com.curvemaps.app.routing

import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.storage.Graph
import java.util.PriorityQueue

/**
 * Which base-graph edges lie on a small loop of drivable road: some other
 * car-accessible path joins the edge's two ends within [perimeterMeters] minus
 * the edge's own length.
 *
 * Small loops are junction structure, not road: the triangle where a side
 * road meets a main road through a slip lane, a carriageway split around a
 * median at an intersection, roundabouts, interchange pieces and town blocks.
 * Any detour a curvy stop could take instead of a stretch of road is such a
 * loop, so [ComplexityWeighting] gives these edges no curve credit and no
 * road penalty. Inside a small loop every way round then costs the same
 * multiple of its travel time, and the faster way wins, as on Fastest. With curve credit, a 46 m slip lane
 * that scored as curvy as a mountain road beat 39 m of straight main road at
 * every curvy stop (Woombye Palmwoods Road at Taintons Road), and a quarter
 * of Curvy rides took at least one such jog.
 *
 * The loop test ignores travel direction: a jog needs both ways round in one
 * direction, so this flags a few more edges than strictly needed (opposite
 * one-way carriageways), never fewer.
 *
 * Results are graph-wide and filled lazily, one bounded search per edge the
 * first time a curvy route asks; the graph never changes after loading, so a
 * loaded router keeps one instance for all requests. Concurrent searches may
 * race to fill the same entry; both compute the same answer.
 */
class SmallLoopEdges internal constructor(
    private val graph: Graph,
    private val accessEnc: BooleanEncodedValue,
    private val perimeterMeters: Double = SMALL_LOOP_PERIMETER_METERS,
) {
    /**
     * Per edge: [UNKNOWN], [NO_LOOP] or [ON_LOOP]. Sized on first use:
     * GraphHopper builds a weighting factory (and with it this instance) to
     * check its profiles BEFORE the graph's edges are loaded.
     */
    private val state by lazy { ByteArray(graph.edges) }

    fun isOnSmallLoop(edge: Int): Boolean {
        if (edge !in state.indices) return false
        when (state[edge]) {
            ON_LOOP -> return true
            NO_LOOP -> return false
        }
        val onLoop = searchLoop(edge)
        state[edge] = if (onLoop) ON_LOOP else NO_LOOP
        return onLoop
    }

    /**
     * Bounded Dijkstra by distance from one end of [edge] to the other over
     * edges drivable in either direction, never using [edge] itself.
     */
    private fun searchLoop(edge: Int): Boolean {
        val start = graph.getEdgeIteratorState(edge, Int.MIN_VALUE)
        val budget = perimeterMeters - start.distance
        if (budget <= 0.0) return false
        val from = start.baseNode
        val target = start.adjNode
        if (from == target) return true
        val best = HashMap<Int, Double>()
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        best[from] = 0.0
        queue.add(0.0 to from)
        val explorer = graph.createEdgeExplorer()
        var settled = 0
        while (queue.isNotEmpty()) {
            val (distance, node) = queue.remove()
            if (distance > (best[node] ?: Double.POSITIVE_INFINITY)) continue
            if (node == target) return true
            if (++settled > MAX_SETTLED_NODES) return false
            val it = explorer.setBaseNode(node)
            while (it.next()) {
                if (it.edge == edge) continue
                if (!it.get(accessEnc) && !it.getReverse(accessEnc)) continue
                val next = distance + it.distance
                if (next > budget || next >= (best[it.adjNode] ?: Double.POSITIVE_INFINITY)) continue
                best[it.adjNode] = next
                queue.add(next to it.adjNode)
            }
        }
        return false
    }

    internal companion object {
        /**
         * Longest loop that counts as junction structure. Measured on 151
         * Queensland rater corridors at all seven stops (jog = a detour of at
         * most 400 m with a faster one- or two-edge shortcut): without the
         * rule Curvy took 57 jogs and Max twisty 104 (Fastest 0); with links
         * and roundabouts alone 29 and 87; with loops up to 300/400/500/600 m
         * 5/3/2/2 and 9/7/2/2. The jogs left at 500 m are loops longer than
         * that (a 0.2 s roundabout tie, a motorway ramp). The cost is curve
         * credit on rural roads: 0.8/1.1/1.4/1.8% of it sits on such loops
         * (25-38% in residential-density areas, which are meant to lose it).
         * Routing time did not measurably change.
         */
        const val SMALL_LOOP_PERIMETER_METERS = 500.0

        /** Guard for dense street grids; within the perimeter it is never reached in practice. */
        private const val MAX_SETTLED_NODES = 4_000

        private const val UNKNOWN: Byte = 0
        private const val NO_LOOP: Byte = 1
        private const val ON_LOOP: Byte = 2
    }
}
