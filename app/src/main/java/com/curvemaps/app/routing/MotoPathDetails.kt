package com.curvemaps.app.routing

import com.graphhopper.routing.Path
import com.graphhopper.routing.ev.EncodedValueLookup
import com.graphhopper.routing.querygraph.QueryGraph
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.storage.Graph
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.details.AbstractPathDetailsBuilder
import com.graphhopper.util.details.KVStringDetails
import com.graphhopper.util.details.PathDetailsBuilder
import com.graphhopper.util.details.PathDetailsBuilderFactory

/**
 * Edge KV key written by `tools/gh/MotoGraphImport.java`: per-direction lane
 * count and turn arrows of the road (format documented there and parsed by
 * [com.curvemaps.app.routing.navigation.LaneGuidance.parse]).
 */
const val MOTO_LANES_DETAIL = "moto_lanes"

/**
 * GraphHopper's factory only knows its built-in KV keys, so this adds
 * [MOTO_LANES_DETAIL] and [MOTO_STOPS_DETAIL]. Both read the edge in travel
 * direction, which is what makes the forward/backward values come out right.
 */
internal class MotoPathDetailsBuilderFactory : PathDetailsBuilderFactory() {
    override fun createPathDetailsBuilders(
        requestedPathDetails: List<String>,
        path: Path,
        evl: EncodedValueLookup,
        weighting: Weighting,
        graph: Graph,
    ): List<PathDetailsBuilder> {
        val builtIn = requestedPathDetails.filter { it != MOTO_LANES_DETAIL && it != MOTO_STOPS_DETAIL }
        val builders = super.createPathDetailsBuilders(builtIn, path, evl, weighting, graph).toMutableList()
        if (MOTO_LANES_DETAIL in requestedPathDetails) builders += KVStringDetails(MOTO_LANES_DETAIL)
        if (MOTO_STOPS_DETAIL in requestedPathDetails) builders += HalfStopDetails((graph as? QueryGraph)?.baseGraph ?: graph)
        return builders
    }
}

/**
 * One [MOTO_STOPS_DETAIL] entry per edge with its half-stop count (a Double:
 * a QueryGraph piece of an edge carries its [virtualEdgeShare] of the count).
 * Unlike [KVStringDetails] it never merges equal neighbours: two edges that
 * each end at a signalled junction carry one half each, and merging them
 * would lose a stop ([routeStopCount] sums the entries).
 */
internal class HalfStopDetails(private val baseGraph: Graph) : AbstractPathDetailsBuilder(MOTO_STOPS_DETAIL) {
    private var current = 0.0

    override fun isEdgeDifferentToLastEdge(edge: EdgeIteratorState): Boolean {
        current = parseHalfStops(edge.getValue(MOTO_STOPS_DETAIL)) * virtualEdgeShare(edge, baseGraph)
        return true
    }

    override fun getCurrentValue(): Any = current
}
