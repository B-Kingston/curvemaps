package com.curvemaps.app.region

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.curvemaps.app.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The single "Load map file" path, on a real device: a `.motomap` package is
 * recognised by its bytes (not its name), installed, and made active in one
 * step with every reader (routing graph, geocoder, basemap) following it; bad
 * files and mid-ride attempts change nothing. Also pins the build contract for
 * bundled data, so the same suite proves both APK flavours.
 */
@RunWith(AndroidJUnit4::class)
class LoadMapFileOnDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val registry: RegionRegistry
        get() = RegionRegistry(File(context.filesDir, "regions"))

    private val scratch = mutableListOf<File>()
    private val managers = mutableListOf<RegionManager>()

    @Before
    fun clean() {
        File(context.filesDir, "regions").deleteRecursively()
    }

    @After
    fun restore() {
        managers.forEach { it.close() }
        scratch.forEach { it.delete() }
        File(context.filesDir, "regions").deleteRecursively()
    }

    private fun manager() = RegionManager(context).also { managers += it }

    /** The Monaco package under a misleading name: only the bytes identify it. */
    private fun monacoPackage(name: String = "download (3).pmtiles"): File {
        val target = File(context.cacheDir, name).also { scratch += it }
        InstrumentationRegistry.getInstrumentation().context.assets.open("regions/monaco.motomap").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun file(name: String, bytes: ByteArray): File =
        File(context.cacheDir, name).also {
            it.writeBytes(bytes)
            scratch += it
        }

    private fun sniff(file: File): MapFileKind =
        MapFileKind.sniff(context.contentResolver.openInputStream(Uri.fromFile(file))!!)

    @Test
    fun rideStartingDuringImportReportsInstalledAndDefersActivation() {
        val current = manager()
        val install = registry.install(monacoPackage())
        current.setRideActive(true)
        val result = runBlocking { current.activateImportedPackage(install) }
        assertEquals(install, result)
        assertNull(registry.active().installId)
        assertEquals(install.installId, current.state.value.installed.single().installId)
        assertNull(current.state.value.error)
        assertTrue(current.state.value.notice!!.contains("installed"))
        assertTrue(current.state.value.notice!!.contains("switches on when this ride ends"))
        assertEquals(install.installId, current.state.value.pendingActivation)
    }

    @Test
    fun aPackageIsRecognisedByItsBytesThroughTheContentResolver() {
        assertEquals(MapFileKind.PACKAGE, sniff(monacoPackage()))
        val basemap = file("basemap.motomap", "PMTiles".encodeToByteArray() + byteArrayOf(3) + ByteArray(119))
        assertEquals(MapFileKind.PMTILES, sniff(basemap))
        assertEquals(MapFileKind.UNKNOWN, sniff(file("notes.pmtiles", "hello".encodeToByteArray())))
    }

    @Test
    fun loadingAPackageInstallsAndActivatesItWithEveryReaderFollowing() {
        val manager = manager()
        val uri = Uri.fromFile(monacoPackage())

        val install = runBlocking { manager.loadPackageAndActivate(uri) }

        assertEquals("monaco", install.regionId)
        assertEquals(install.installId, registry.active().installId)
        // The flows are already current when the call returns: the screen keys
        // its router, geocoder and basemap on this dataset.
        val dataset = manager.dataset.value
        assertEquals(RegionKind.INSTALLED, dataset.kind)
        assertEquals(install.installId, dataset.installId)
        assertTrue(dataset.dataPresent)
        assertTrue(dataset.tilesUrl!!.startsWith("pmtiles://file://"))
        assertTrue(dataset.geocoderFile!!.isFile)
        assertTrue(dataset.graphDir.isDirectory)
        assertEquals(install.installId, manager.state.value.activeInstallId)
        assertEquals("Monaco", manager.state.value.activeRegionName)
        assertNull(manager.state.value.error)
    }

    @Test
    fun loadingTheSamePackageTwiceKeepsOneInstallAndStaysActive() {
        val manager = manager()
        val uri = Uri.fromFile(monacoPackage())
        val first = runBlocking { manager.loadPackageAndActivate(uri) }
        val firstGeneration = registry.active().generation
        val second = runBlocking { manager.loadPackageAndActivate(uri) }

        assertEquals(first.installId, second.installId)
        assertEquals(1, registry.list().size)
        assertEquals(second.installId, registry.active().installId)
        assertTrue("re-activation must bump the generation", registry.active().generation > firstGeneration)
    }

    @Test
    fun aRideInProgressRefusesTheSwitchAndChangesNothing() {
        val manager = manager()
        manager.setRideActive(true)
        try {
            runBlocking { manager.loadPackageAndActivate(Uri.fromFile(monacoPackage())) }
            fail("a map was switched mid-ride")
        } catch (expected: RegionPackageException) {
            assertTrue(expected.message, expected.message!!.contains("ride"))
        }
        assertTrue(registry.list().isEmpty())
        assertNull(registry.active().installId)
        assertEquals(0, registry.active().generation)
    }

    @Test
    fun filesThatAreNotPackagesAreRejectedWithoutTouchingTheInstalledMap() {
        val manager = manager()
        val good = runBlocking { manager.loadPackageAndActivate(Uri.fromFile(monacoPackage())) }
        val generation = registry.active().generation

        val badFiles = listOf(
            // A bare basemap is not an archive the package installer can open.
            file("bare.motomap", "PMTiles".encodeToByteArray() + byteArrayOf(3) + ByteArray(200)),
            file("empty.motomap", ByteArray(0)),
            // A ZIP header with garbage after it.
            file("corrupt.motomap", byteArrayOf(0x50, 0x4B, 3, 4) + ByteArray(300) { 7 }),
        )
        for (bad in badFiles) {
            try {
                runBlocking { manager.loadPackageAndActivate(Uri.fromFile(bad)) }
                fail("${bad.name} was accepted as a package")
            } catch (expected: RegionPackageException) {
                assertFalse(expected.message.isNullOrBlank())
            }
        }

        assertEquals(listOf(good.installId), registry.list().map { it.installId })
        assertEquals(good.installId, registry.active().installId)
        assertEquals(generation, registry.active().generation)
        assertEquals(good.installId, manager.dataset.value.installId)
    }

    @Test
    fun anUnreadableUriFailsCleanly() {
        val manager = manager()
        val missing = Uri.fromFile(File(context.cacheDir, "does-not-exist.motomap"))
        try {
            runBlocking { manager.loadPackageAndActivate(missing) }
            fail("a missing file was loaded")
        } catch (expected: RegionPackageException) {
            assertNotNull(expected.message)
        }
        assertTrue(registry.list().isEmpty())
    }

    /** The APK carries Queensland routing/search data exactly when the build says it does. */
    @Test
    fun bundledAssetsMatchTheBuildFlavour() {
        val assets = context.assets
        val graph = assets.list("graph-cache").orEmpty()
        val geocoder = assets.list("geocoder").orEmpty()
        assertEquals(BuildConfig.BUNDLED_DATA, RegionDatasets.bundledAvailable)
        assertEquals("graph-cache assets vs BUNDLED_DATA", BuildConfig.BUNDLED_DATA, graph.isNotEmpty())
        assertEquals("geocoder assets vs BUNDLED_DATA", BuildConfig.BUNDLED_DATA, geocoder.contains("geocoder.dat"))
        // Fonts, sprites and styles are small and always ship.
        assertTrue(assets.list("sprites").orEmpty().isNotEmpty())
        assertNotNull(assets.open("style.json").also { it.close() })
    }

    @Test
    fun theFallbackDatasetHonestlyReportsWhetherItHasData() {
        val fallback = registry.activeDataset(context)
        assertEquals(RegionKind.BUNDLED, fallback.kind)
        assertEquals(BuildConfig.BUNDLED_DATA, fallback.dataPresent)
        assertEquals(BuildConfig.BUNDLED_DATA, fallback.copyGraphFromAssets)
        assertEquals(
            if (BuildConfig.BUNDLED_DATA) RegionDatasets.BUNDLED_DISPLAY_NAME else RegionDatasets.NO_MAP_DISPLAY_NAME,
            fallback.displayName,
        )
    }
}
