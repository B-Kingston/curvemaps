package com.curvemaps.app.region

import android.content.Context
import com.curvemaps.app.BuildConfig
import com.curvemaps.app.tiles.OfflineTileStore
import java.io.File

/** Which dataset the app is currently using. */
enum class RegionKind { BUNDLED, INSTALLED }

/**
 * The resolved active dataset. Everything the app needs to route, search, and
 * render comes from here:
 *
 *  - [graphDir] + [copyGraphFromAssets]: the GraphHopper graph directory. The
 *    bundled fallback still copies from APK assets on first use; an installed
 *    region reads its immutable directory directly.
 *  - [geocoderFile]: an installed region's `geocoder.dat`, or null to use the
 *    packaged asset (bundled fallback).
 *  - [tilesFile]: the PMTiles basemap. Installed regions carry their own; the
 *    bundled fallback uses the legacy "Load map file" import.
 *
 * [dataPresent] is false only for the bundled fallback of a build that ships
 * without Queensland data and has no installed map yet: there is then nothing
 * to route, search or (usually) draw, and the UI asks for a `.motomap` package.
 *
 * [generation] increments on every activation. Screens key their router,
 * geocoder, map style, and route searches on it, so a switch cannot leave a
 * stale result from the previous dataset on screen.
 */
data class RegionDataset(
    val kind: RegionKind,
    val installId: String?,
    val regionId: String,
    val displayName: String,
    val coverage: String?,
    val sourceDate: String?,
    val generation: Int,
    val graphDir: File,
    val copyGraphFromAssets: Boolean,
    val geocoderFile: File?,
    val tilesFile: File?,
    val dataPresent: Boolean = true,
) {
    /** MapLibre URL for the basemap, or null when no tiles are installed. */
    val tilesUrl: String?
        get() = tilesFile?.let(OfflineTileStore::mapUrl)

    val isBundled: Boolean get() = kind == RegionKind.BUNDLED
}

/** Builds [RegionDataset]s for the bundled fallback and installed regions. */
object RegionDatasets {

    const val BUNDLED_REGION_ID = "bundled-queensland"
    const val BUNDLED_DISPLAY_NAME = "Queensland (bundled)"
    const val NO_MAP_DISPLAY_NAME = "No map installed"

    /**
     * Whether this build's APK carries the Queensland graph and geocoder
     * (`-Pmoto.bundleData`; see app/build.gradle.kts). Builds without it get
     * every map from a `.motomap` package.
     */
    val bundledAvailable: Boolean get() = BuildConfig.BUNDLED_DATA

    /** Name of the dataset the fallback currently resolves to. */
    val fallbackDisplayName: String
        get() = if (bundledAvailable) BUNDLED_DISPLAY_NAME else NO_MAP_DISPLAY_NAME

    /** The packaged Queensland fallback, always available. */
    fun bundled(context: Context, generation: Int): RegionDataset {
        val filesDir = context.applicationContext.filesDir
        val legacyTiles = OfflineTileStore.installedFileOrNull(context.applicationContext)
        return RegionDataset(
            kind = RegionKind.BUNDLED,
            installId = null,
            regionId = BUNDLED_REGION_ID,
            displayName = fallbackDisplayName,
            coverage = "Queensland, Australia".takeIf { bundledAvailable },
            sourceDate = null,
            generation = generation,
            graphDir = File(filesDir, "gh-cache"),
            copyGraphFromAssets = bundledAvailable,
            geocoderFile = null,
            tilesFile = legacyTiles,
            dataPresent = bundledAvailable,
        )
    }

    /** An installed package's immutable directory. */
    fun installed(context: Context, install: InstalledRegion, generation: Int): RegionDataset {
        val dir = File(File(context.applicationContext.filesDir, "regions/installs"), install.dirName)
        val tiles = File(dir, RegionPackageContract.TILES_PATH)
        return RegionDataset(
            kind = RegionKind.INSTALLED,
            installId = install.installId,
            regionId = install.regionId,
            displayName = install.regionName,
            coverage = install.coverage,
            sourceDate = install.sourceDate,
            generation = generation,
            graphDir = File(dir, RegionPackageContract.GRAPH_PATH),
            copyGraphFromAssets = false,
            geocoderFile = File(dir, RegionPackageContract.GEOCODER_PATH),
            tilesFile = tiles.takeIf { it.isFile },
        )
    }
}
