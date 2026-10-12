package com.curvemaps.app.routing

import com.graphhopper.storage.Graph
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Per-edge inputs of [ComplexityWeighting] that depend only on the base-graph
 * edge, never on the request: the straight-road factor (once without and once
 * with hills) and the half stops in each storage direction. Filled lazily and
 * kept for the loaded graph's lifetime, like [SmallLoopEdges]: one instance
 * per [loadMotoGraphHopper], shared by every request.
 *
 * A seven-stop plan routes a median of 86 searches over largely the same
 * roads (up to ~400 on short hops, [DistinctRideStyles]). With a cache per
 * request every search fetched each explored edge's geometry and measured its
 * curves again: 29% of planning CPU, and stop counts read from the KV store
 * another 9%.
 *
 * Memory grows with the explored area, in blocks of [BLOCK_EDGES] edges
 * allocated on first touch: 8 bytes per edge for each curve-factor variant in
 * use and 2 for the stops, so at most ~19 MB on Queensland's ~1M edges, and
 * typically a few MB after a plan. Concurrent searches may race to fill an
 * entry; both compute the same value. Curve factors are 64-bit, so they sit
 * in [AtomicLongArray]s, which never tear a value on 32-bit ARM.
 */
class EdgeFactors internal constructor(private val graph: Graph) {

    // Sized on first use: GraphHopper builds a weighting factory (and with it
    // this instance) to check its profiles BEFORE the graph's edges load.
    private val blockCount by lazy { (graph.edges + BLOCK_EDGES - 1) / BLOCK_EDGES }
    private val plainCurves by lazy { AtomicReferenceArray<AtomicLongArray?>(blockCount) }
    private val hillyCurves by lazy { AtomicReferenceArray<AtomicLongArray?>(blockCount) }
    private val halfStops by lazy { AtomicReferenceArray<ByteArray?>(blockCount) }

    /** Whether [edge] is a base-graph edge this table can hold (QueryGraph's virtual edges are not). */
    internal fun covers(edge: Int): Boolean = edge in 0 until graph.edges

    /** The stored straight-road factor of [edge], or NaN before it is known. */
    internal fun curveFactor(edge: Int, hills: Boolean): Double {
        val block = curves(hills).get(edge / BLOCK_EDGES) ?: return Double.NaN
        val bits = block.get(edge % BLOCK_EDGES)
        return if (bits == UNKNOWN_CURVE) Double.NaN else Double.fromBits(bits)
    }

    internal fun putCurveFactor(edge: Int, hills: Boolean, factor: Double) {
        val blocks = curves(hills)
        val index = edge / BLOCK_EDGES
        val block = blocks.get(index) ?: AtomicLongArray(LongArray(BLOCK_EDGES) { UNKNOWN_CURVE }).let { fresh ->
            if (blocks.compareAndSet(index, null, fresh)) fresh else blocks.get(index)!!
        }
        block.lazySet(edge % BLOCK_EDGES, factor.toRawBits())
    }

    /** Half stops on [edge] against or along its storage direction, or -1 before they are known. */
    internal fun halfStops(edge: Int, againstStorage: Boolean): Int {
        val block = halfStops.get(edge / BLOCK_EDGES) ?: return UNKNOWN_STOPS
        return block[stopSlot(edge, againstStorage)].toInt()
    }

    internal fun putHalfStops(edge: Int, againstStorage: Boolean, halfStops: Int) {
        require(halfStops in 0..MAX_HALF_STOPS_PER_EDGE)
        val blocks = this.halfStops
        val index = edge / BLOCK_EDGES
        val block = blocks.get(index) ?: ByteArray(2 * BLOCK_EDGES) { UNKNOWN_STOPS.toByte() }.let { fresh ->
            if (blocks.compareAndSet(index, null, fresh)) fresh else blocks.get(index)!!
        }
        block[stopSlot(edge, againstStorage)] = halfStops.toByte()
    }

    private fun curves(hills: Boolean) = if (hills) hillyCurves else plainCurves

    private fun stopSlot(edge: Int, againstStorage: Boolean) = 2 * (edge % BLOCK_EDGES) + if (againstStorage) 1 else 0

    internal companion object {
        /** Edges per lazily allocated block (32 KB of curve factors). */
        const val BLOCK_EDGES = 4_096

        /** A NaN pattern no curve factor (always 0..1) can have. */
        private const val UNKNOWN_CURVE = -1L

        private const val UNKNOWN_STOPS = -1
    }
}
