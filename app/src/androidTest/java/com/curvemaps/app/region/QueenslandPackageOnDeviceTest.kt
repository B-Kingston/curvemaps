package com.curvemaps.app.region

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.curvemaps.app.BuildConfig
import com.curvemaps.app.geocoding.GeocoderIndex
import com.curvemaps.app.geocoding.SearchEngine
import com.curvemaps.app.routing.GraphHopperRouter
import com.graphhopper.util.shapes.GHPoint
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real Queensland `.motomap` (tools/package/pack-queensland.sh) is the one
 * file that replaces the bundled graph, geocoder and basemap. Seed it first with
 * `tools/test/seed-package-fixture.sh`; a missing fixture is a hard failure, not
 * a skip (the full runner seeds it).
 *
 * It must route, search and draw from the package alone and — in a build that
 * still bundles Queensland — answer exactly like the bundled data, because the
 * two are built from the same bytes.
 */
@RunWith(AndroidJUnit4::class)
class QueenslandPackageOnDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val fixture: File
        get() = File(context.filesDir, "fixtures/queensland-package.motomap")

    private var manager: RegionManager? = null

    @Before
    fun requireFixtureAndStartClean() {
        check(fixture.isFile && fixture.length() > 0) {
            "Package prerequisite: $fixture is missing. Run " +
                "ANDROID_SERIAL=<serial> tools/test/seed-package-fixture.sh before this class."
        }
        File(context.filesDir, "regions").deleteRecursively()
    }

    @After
    fun restore() {
        manager?.close()
        File(context.filesDir, "regions").deleteRecursively()
    }

    @Test(timeout = 600_000)
    fun theSingleFileRoutesSearchesAndDrawsOnItsOwn() {
        val manager = RegionManager(context).also { manager = it }
        val install = runBlocking { manager.loadPackageAndActivate(Uri.fromFile(fixture)) }
        assertEquals("queensland", install.regionId)

        val dataset = manager.dataset.value
        assertEquals(install.installId, dataset.installId)
        assertTrue("package carries its own basemap", dataset.tilesUrl!!.startsWith("pmtiles://file://"))

        val router = GraphHopperRouter(context, dataset.graphDir, copyGraphFromAssets = false)
        val packaged = try {
            router.route(GHPoint(-27.4698, 153.0251), GHPoint(-27.3353, 152.7720)).routes.first()
        } finally {
            router.close()
        }
        assertTrue("distance ${packaged.distance}", packaged.distance in 20_000.0..60_000.0)
        assertTrue(packaged.points.size() > 100)

        val index = GeocoderIndex.loadFile(dataset.geocoderFile!!)
        val packagedTop = runBlocking { SearchEngine(index).search("brisbane", limit = 5) }
        assertTrue("package geocoder finds Brisbane", packagedTop.isNotEmpty())

        if (!BuildConfig.BUNDLED_DATA) return
        // Same data, two routes into the app: identical answers.
        val bundledRouter = GraphHopperRouter(context)
        val bundled = try {
            bundledRouter.route(GHPoint(-27.4698, 153.0251), GHPoint(-27.3353, 152.7720)).routes.first()
        } finally {
            bundledRouter.close()
        }
        assertEquals(bundled.distance, packaged.distance, 0.5)
        assertEquals(bundled.time, packaged.time)
        assertEquals(bundled.points.size(), packaged.points.size())

        val bundledTop = runBlocking { SearchEngine(GeocoderIndex.load(context)).search("brisbane", limit = 5) }
        assertEquals(bundledTop.map { it.name to it.type }, packagedTop.map { it.name to it.type })
    }
}
