package com.curvemaps.app.routing

import com.graphhopper.routing.ev.SimpleBooleanEncodedValue
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.storage.BaseGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EdgeFactorsTest {

    @Test
    fun nothingIsKnownBeforeItIsStored() {
        val factors = EdgeFactors(graph(10))
        assertTrue(factors.curveFactor(3, hills = false).isNaN())
        assertTrue(factors.curveFactor(3, hills = true).isNaN())
        assertEquals(-1, factors.halfStops(3, againstStorage = false))
        assertEquals(-1, factors.halfStops(3, againstStorage = true))
    }

    @Test
    fun valuesRoundTripExactlyPerVariantAndDirectionAcrossBlocks() {
        val edges = 2 * EdgeFactors.BLOCK_EDGES + 7
        val factors = EdgeFactors(graph(edges))
        val probes = listOf(0, EdgeFactors.BLOCK_EDGES - 1, EdgeFactors.BLOCK_EDGES, edges - 1)
        probes.forEachIndexed { index, edge ->
            factors.putCurveFactor(edge, hills = false, factor = 0.1 * index + Math.ulp(0.5))
            factors.putCurveFactor(edge, hills = true, factor = 0.0)
            factors.putHalfStops(edge, againstStorage = false, halfStops = index)
            factors.putHalfStops(edge, againstStorage = true, halfStops = MAX_HALF_STOPS_PER_EDGE)
        }
        probes.forEachIndexed { index, edge ->
            assertEquals(0.1 * index + Math.ulp(0.5), factors.curveFactor(edge, hills = false), 0.0)
            assertEquals(0.0, factors.curveFactor(edge, hills = true), 0.0)
            assertEquals(index, factors.halfStops(edge, againstStorage = false))
            assertEquals(MAX_HALF_STOPS_PER_EDGE, factors.halfStops(edge, againstStorage = true))
        }
        // Neighbours stay unknown.
        assertTrue(factors.curveFactor(1, hills = false).isNaN())
        assertEquals(-1, factors.halfStops(EdgeFactors.BLOCK_EDGES + 1, againstStorage = false))
    }

    @Test
    fun onlyBaseGraphEdgesAreCovered() {
        val factors = EdgeFactors(graph(5))
        assertTrue(factors.covers(0))
        assertTrue(factors.covers(4))
        // QueryGraph numbers its virtual edges after the base graph's.
        assertFalse(factors.covers(5))
        assertFalse(factors.covers(-1))
    }

    @Test
    fun concurrentFillsAgreeAndNeverTear() {
        val edges = 3 * EdgeFactors.BLOCK_EDGES
        val factors = EdgeFactors(graph(edges))
        fun factorOf(edge: Int) = Math.sqrt(edge.toDouble()) / Math.sqrt(edges.toDouble())
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) { worker ->
            pool.execute {
                val random = java.util.Random(worker.toLong())
                repeat(50_000) {
                    val edge = random.nextInt(edges)
                    val known = factors.curveFactor(edge, hills = false)
                    if (known.isNaN()) factors.putCurveFactor(edge, false, factorOf(edge)) else check(known == factorOf(edge))
                    val stops = factors.halfStops(edge, againstStorage = true)
                    if (stops < 0) factors.putHalfStops(edge, true, edge % 7) else check(stops == edge % 7)
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        for (edge in 0 until edges) {
            val known = factors.curveFactor(edge, hills = false)
            assertTrue(known.isNaN() || known == factorOf(edge))
            val stops = factors.halfStops(edge, againstStorage = true)
            assertTrue(stops == -1 || stops == edge % 7)
        }
    }

    private fun graph(edges: Int): BaseGraph {
        val access = SimpleBooleanEncodedValue("car_access", true)
        val graph = BaseGraph.Builder(EncodingManager.start().add(access).build()).create()
        for (node in 0..edges) graph.nodeAccess.setNode(node, 0.0, node * 1e-4)
        for (edge in 0 until edges) graph.edge(edge, edge + 1).setDistance(10.0)
        return graph
    }
}
