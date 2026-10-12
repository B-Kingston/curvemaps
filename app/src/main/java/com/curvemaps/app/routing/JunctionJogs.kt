package com.curvemaps.app.routing

import com.graphhopper.ResponsePath
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.storage.Graph

/**
 * A short detour a route takes between two of its own nodes where one or two
 * edges off the route join the same nodes faster: the route left a road for a
 * slip lane, a junction triangle or a carriageway split and came straight
 * back. [loopMeters] is the detour plus the shortcut.
 */
internal data class JunctionJog(
    val fromNode: Int,
    val toNode: Int,
    val routeMeters: Double,
    val routeSeconds: Double,
    val shortcutMeters: Double,
    val shortcutSeconds: Double,
) {
    val loopMeters: Double get() = routeMeters + shortcutMeters
}

/**
 * The [JunctionJog]s along a route given as base-graph edge IDs in travel
 * order. Checks every section between two route nodes against one- and
 * two-edge shortcuts that are drivable in the travel direction under
 * [fastest], are not part of the route, save more than [minSavingSeconds],
 * and close a loop of at most [maxLoopMeters]. Overlapping jogs report the
 * one reaching furthest along the route.
 *
 * A diagnostic for tests and probes. [SmallLoopEdges] guarantees a plain
 * search (no ride factors, road penalties or destination-only roads, whose
 * terms legitimately differ between two ways round) takes none within its
 * perimeter, and this is how that is checked.
 */
internal fun junctionJogs(
    graph: Graph,
    fastest: Weighting,
    edgeIds: List<Int>,
    maxLoopMeters: Double = SmallLoopEdges.SMALL_LOOP_PERIMETER_METERS,
    minSavingSeconds: Double = 0.0,
): List<JunctionJog> = connectedRuns(graph, edgeIds).flatMap { run ->
    runJogs(graph, fastest, run, maxLoopMeters, minSavingSeconds)
}

/**
 * [edgeIds] split where consecutive edges share no node, with repeats
 * collapsed: a via point inside an edge reports that edge once per leg.
 */
private fun connectedRuns(graph: Graph, edgeIds: List<Int>): List<List<Int>> {
    val edges = edgeIds.filter { it in 0 until graph.edges }.fold(mutableListOf<Int>()) { kept, edge ->
        if (kept.lastOrNull() != edge) kept += edge
        kept
    }
    val runs = mutableListOf<MutableList<Int>>()
    edges.forEach { edge ->
        val run = runs.lastOrNull()
        val state = graph.getEdgeIteratorState(edge, Int.MIN_VALUE)
        val previous = run?.last()?.let { graph.getEdgeIteratorState(it, Int.MIN_VALUE) }
        val touches = previous != null && (state.baseNode == previous.baseNode || state.baseNode == previous.adjNode ||
            state.adjNode == previous.baseNode || state.adjNode == previous.adjNode)
        if (touches) run!! += edge else runs += mutableListOf(edge)
    }
    return runs.filter { it.size >= 2 }
}

private fun runJogs(
    graph: Graph,
    fastest: Weighting,
    edgeIds: List<Int>,
    maxLoopMeters: Double,
    minSavingSeconds: Double,
): List<JunctionJog> {
    val nodes = ArrayList<Int>(edgeIds.size + 1)
    val metres = DoubleArray(edgeIds.size + 1)
    val seconds = DoubleArray(edgeIds.size + 1)
    val first = graph.getEdgeIteratorState(edgeIds[0], Int.MIN_VALUE)
    val second = graph.getEdgeIteratorState(edgeIds[1], Int.MIN_VALUE)
    var current = if (first.adjNode == second.baseNode || first.adjNode == second.adjNode) first.baseNode else first.adjNode
    nodes += current
    edgeIds.forEachIndexed { index, edge ->
        val state = graph.getEdgeIteratorState(edge, Int.MIN_VALUE)
        val forward = state.baseNode == current
        if (!forward && state.adjNode != current) return emptyList()
        current = if (forward) state.adjNode else state.baseNode
        nodes += current
        metres[index + 1] = metres[index] + state.distance
        seconds[index + 1] = seconds[index] + fastest.calcEdgeWeight(state, !forward)
    }
    val position = HashMap<Int, Int>(nodes.size * 2)
    nodes.forEachIndexed { index, node -> position.putIfAbsent(node, index) }
    val routeEdges = edgeIds.toHashSet()
    val firstHop = graph.createEdgeExplorer()
    val secondHop = graph.createEdgeExplorer()
    val jogs = mutableListOf<JunctionJog>()
    var start = 0
    while (start < nodes.size - 2) {
        var best: JunctionJog? = null
        var bestEnd = -1
        fun consider(end: Int?, shortcutMeters: Double, shortcutSeconds: Double) {
            if (end == null || end <= start + 1 || end <= bestEnd) return
            val routeMeters = metres[end] - metres[start]
            val routeSeconds = seconds[end] - seconds[start]
            if (routeMeters + shortcutMeters > maxLoopMeters) return
            if (shortcutSeconds >= routeSeconds - minSavingSeconds) return
            best = JunctionJog(nodes[start], nodes[end], routeMeters, routeSeconds, shortcutMeters, shortcutSeconds)
            bestEnd = end
        }
        val hop = firstHop.setBaseNode(nodes[start])
        while (hop.next()) {
            if (hop.edge in routeEdges) continue
            val hopSeconds = fastest.calcEdgeWeight(hop, false)
            if (!hopSeconds.isFinite() || hop.distance >= maxLoopMeters) continue
            consider(position[hop.adjNode], hop.distance, hopSeconds)
            val next = secondHop.setBaseNode(hop.adjNode)
            while (next.next()) {
                if (next.edge == hop.edge || next.edge in routeEdges) continue
                val nextSeconds = fastest.calcEdgeWeight(next, false)
                if (!nextSeconds.isFinite()) continue
                consider(position[next.adjNode], hop.distance + next.distance, hopSeconds + nextSeconds)
            }
        }
        val found = best
        if (found != null) {
            jogs += found
            start = bestEnd
        } else {
            start++
        }
    }
    return jogs
}

/**
 * The base-graph edges of a routed path in travel order, without its first
 * and last: GraphHopper reports a QueryGraph piece by its whole original
 * edge, whose far end the route never reaches.
 */
internal fun interiorEdgeIds(path: ResponsePath): List<Int> {
    val edges = path.pathDetails["edge_id"].orEmpty().map { (it.value as Number).toInt() }
    return if (edges.size > 2) edges.subList(1, edges.size - 1) else emptyList()
}
