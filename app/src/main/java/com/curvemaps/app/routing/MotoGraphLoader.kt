package com.curvemaps.app.routing

import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.ev.UrbanDensity
import java.io.DataInputStream
import java.io.File
import java.io.IOException

/**
 * Loads a prebuilt motorcycle graph with the production profile, weighting
 * factory and path details. Shared by [GraphHopperRouter] and the desktop
 * probes, so they can never load a graph differently.
 *
 * GraphHopper refuses to load a graph whose dimension differs from its
 * configuration ("Configured dimension elevation=false is not equal to
 * dimension of loaded graph"), and graphs exist in both: ride-factor imports
 * are 3D, older map packages 2D. The dimension is therefore read from the
 * graph's own `nodes` header ([graphHasElevation]) instead of being assumed.
 * Elevation is enabled AFTER `init`, which resets it from the (absent)
 * elevation provider; no provider is needed to read a 3D graph.
 */
internal fun loadMotoGraphHopper(graphDir: File): GraphHopper {
    val config = GraphHopperConfig().apply {
        putObject("graph.location", graphDir.absolutePath)
        putObject("graph.dataaccess.default_type", "MMAP")
        putObject("import.osm.ignored_highways", "footway,steps,corridor,bridleway")
    }
    val elevation = graphHasElevation(graphDir)
    return object : GraphHopper() {
        // Filled lazily by curvy routes and kept for the graph's lifetime.
        private val smallLoops by lazy {
            SmallLoopEdges(baseGraph, encodingManager.getBooleanEncodedValue("car_access"))
        }

        // Curve factors and stop counts every request reads, kept per graph.
        private val edgeFactors by lazy { EdgeFactors(baseGraph) }

        // Builds the custom motorcycle weighting from the pre-compiled
        // MotorcycleWeightingHelper instead of Janino (which emits JVM
        // bytecode ART cannot load).
        override fun createWeightingFactory(): WeightingFactory =
            MotorcycleWeightingFactory(baseGraph, encodingManager, smallLoops, edgeFactors)
    }.apply {
        init(config)
        setElevation(elevation)
        setPathDetailsBuilderFactory(MotoPathDetailsBuilderFactory())
        setProfiles(motorcycleProfile())
        importOrLoad()
    }
}

/**
 * The ride-factor data a loaded graph in [graphDir] carries. KV keys are
 * registered on first use and persisted in `edgekv_keys` (GraphHopper keeps
 * the key list package-private), so a key missing there means no edge has
 * it, exactly as the preflight check reads it.
 */
internal fun GraphHopper.graphFeatures(graphDir: File): GraphFeatures = GraphFeatures(
    elevation = baseGraph.nodeAccess.is3D,
    stops = graphHasKvKey(graphDir, MOTO_STOPS_DETAIL),
    urbanDensity = encodingManager.hasEncodedValue(UrbanDensity.KEY),
)

internal fun graphHasKvKey(graphDir: File, key: String): Boolean {
    val keys = File(graphDir, "edgekv_keys")
    return try {
        keys.isFile && keys.readBytes().toString(Charsets.ISO_8859_1).contains(key)
    } catch (_: IOException) {
        false
    }
}

/**
 * Whether the graph in [graphDir] was imported with elevation: header int 7
 * of GraphHopper's `nodes` file (BaseGraphNodesAndEdges writes 1 for 3D).
 * The file starts with the "GH" marker (writeUTF), the data length (long)
 * and the segment size (int), followed by the header ints. A missing or
 * unreadable file reads as 2D and lets GraphHopper report the real problem.
 */
internal fun graphHasElevation(graphDir: File): Boolean {
    val nodes = File(graphDir, "nodes")
    if (!nodes.isFile) return false
    return try {
        DataInputStream(nodes.inputStream().buffered()).use { input ->
            if (input.readUTF() != "GH") return false
            input.readLong()
            input.readInt()
            repeat(NODES_ELEVATION_HEADER_INDEX) { input.readInt() }
            input.readInt() == 1
        }
    } catch (_: IOException) {
        false
    }
}

/** BaseGraphNodesAndEdges stores `withElevation` at header byte position 7 * 4. */
private const val NODES_ELEVATION_HEADER_INDEX = 7
