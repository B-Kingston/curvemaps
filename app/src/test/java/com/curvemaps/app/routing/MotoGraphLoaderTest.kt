package com.curvemaps.app.routing

import com.graphhopper.routing.ev.SimpleBooleanEncodedValue
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.storage.BaseGraph
import com.graphhopper.storage.GHDirectory
import com.graphhopper.storage.DAType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins the on-disk facts the loader reads without GraphHopper's help: the
 * dimension flag in the `nodes` header and the KV key list. Both are written
 * by real GraphHopper storage here, so a format change in a GraphHopper bump
 * fails this test instead of silently loading every graph as 2D.
 */
class MotoGraphLoaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun readsTheDimensionGraphHopperWrote() {
        val threeD = writeGraph(folder.newFolder("three-d"), elevation = true)
        val twoD = writeGraph(folder.newFolder("two-d"), elevation = false)
        assertTrue(graphHasElevation(threeD))
        assertFalse(graphHasElevation(twoD))
    }

    @Test
    fun missingOrForeignFilesReadAsTwoDimensional() {
        val empty = folder.newFolder("empty")
        assertFalse(graphHasElevation(empty))
        File(empty, "nodes").writeText("not a graph file")
        assertFalse(graphHasElevation(empty))
        File(empty, "nodes").writeBytes(ByteArray(0))
        assertFalse(graphHasElevation(empty))
    }

    @Test
    fun kvKeysFileTellsWhetherAnyEdgeCarriesStops() {
        val withStops = writeGraph(folder.newFolder("stops"), elevation = false, kvKey = MOTO_STOPS_DETAIL)
        val withoutStops = writeGraph(folder.newFolder("plain"), elevation = false, kvKey = "name")
        assertTrue(graphHasKvKey(withStops, MOTO_STOPS_DETAIL))
        assertFalse(graphHasKvKey(withoutStops, MOTO_STOPS_DETAIL))
        assertFalse(graphHasKvKey(folder.newFolder("none"), MOTO_STOPS_DETAIL))
    }

    private fun writeGraph(dir: File, elevation: Boolean, kvKey: String? = null): File {
        val encodingManager = EncodingManager.start().add(SimpleBooleanEncodedValue("car_access", true)).build()
        val graph = BaseGraph.Builder(encodingManager)
            .setDir(GHDirectory(dir.absolutePath, DAType.RAM_STORE))
            .set3D(elevation)
            .create()
        if (elevation) {
            graph.nodeAccess.setNode(0, -27.0, 153.0, 120.0)
            graph.nodeAccess.setNode(1, -27.01, 153.0, 80.0)
        } else {
            graph.nodeAccess.setNode(0, -27.0, 153.0)
            graph.nodeAccess.setNode(1, -27.01, 153.0)
        }
        val edge = graph.edge(0, 1).setDistance(1_100.0)
        if (kvKey != null) {
            edge.setKeyValues(mapOf(kvKey to com.graphhopper.search.KVStorage.KValue("2")))
        }
        graph.flush()
        graph.close()
        return dir
    }
}
