package com.curvemaps.app.map

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.curvemaps.app.routing.SURFACE_DETAIL
import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.graphhopper.util.details.PathDetail
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Native proof of the planner's curve shading: MapLibre really draws the
 * focused route in its own colour on straight road and in the curvy colour on
 * twisty road (the line-gradient needs the route source's line metrics, which
 * only the native renderer exercises), and a gravel stretch inside the twisty
 * road blends from the shaded colour, not from the plain route colour.
 *
 * Like [RouteGhostsOnDeviceTest], a synthetic style (one white background
 * layer, no tiles) hosts the production draw calls; the map runs in texture
 * mode so the rendered frame is read back with `TextureView.getBitmap()`.
 */
@RunWith(AndroidJUnit4::class)
class CurvyRouteShadingOnDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val mapRef = AtomicReference<MapLibreMap?>()
    private val styleRef = AtomicReference<Style?>()
    private val mapViewRef = AtomicReference<MapView?>()
    private val renderedFrames = AtomicInteger(0)
    private val styleLoaded = CountDownLatch(1)

    @After
    fun tearDownMapView() {
        val mapView = mapViewRef.getAndSet(null) ?: return
        onMain {
            runCatching {
                mapView.onPause()
                mapView.onStop()
            }
            runCatching { mapView.onDestroy() }
        }
    }

    @Test(timeout = 180_000)
    fun twistyRoadIsDrawnInTheCurvyColourAndGravelBlendsFromIt() {
        val map = startMap()
        val route = route()
        onMain { map.drawRoutes(listOf(route), 0, RouteGeometryCache()) }

        val straight = onMain { map.projection.toScreenLocation(at(STRAIGHT_SAMPLE_M)) }
        val twisty = onMain { map.projection.toScreenLocation(at(TWISTY_SAMPLE_M)) }
        val bitmap = awaitFrameWhere("the straight road in the route colour") { frame ->
            near(frame.pixelAt(straight.x, straight.y), ROUTE_RGB)
        }
        val twistyPixel = bitmap.pixelAt(twisty.x, twisty.y)
        assertTrue(
            "the twisty road must be drawn in the curvy colour, was ${rgbHex(twistyPixel)}",
            near(twistyPixel, rgbOf(CURVY_ROUTE_COLOR)),
        )

        onMain {
            // This gravel lies inside the bends, so both its blends fade from the curvy colour, never plain orange.
            val width = mapViewRef.get()!!.width.toFloat()
            val height = mapViewRef.get()!!.height.toFloat()
            val curvyLevel = GravelBand.REGION.blendLayerId(GRAVEL_BLEND_LEVELS - 1)
            val blends = map.queryRenderedFeatures(RectF(0f, 0f, width, height), curvyLevel)
            // A feature can come back once per tile it crosses; each is a blend at the curvy level.
            assertTrue("the gravel's blends render at the curvy level", blends.isNotEmpty())
            blends.forEach { assertEquals(GRAVEL_BLEND_LEVELS - 1, it.getNumberProperty(GRAVEL_BLEND_LEVEL_PROPERTY).toInt()) }
            assertEquals("no blend fades from the route colour", null, styleRef.get()!!.getLayer(GravelBand.REGION.blendLayerId(0)))
        }

        // Clearing the route clears the shading's gravel too.
        onMain { map.clearRoutes() }
        awaitLayerGone(GravelBand.REGION.blendLayerId(GRAVEL_BLEND_LEVELS - 1))
        onMain { assertEquals(null, styleRef.get()!!.getLayer(ROUTE_FOCUS_LINE_ID)) }
    }

    // --- the synthetic route ------------------------------------------------------

    /**
     * Due east along -27.4°: straight, then tight snake bends (40 m amplitude,
     * 200 m wavelength) from [TWISTY_FROM_M] to [TWISTY_TO_M], then straight.
     * Gravel covers [GRAVEL_FROM_M]..[GRAVEL_TO_M] inside the bends.
     */
    private fun route(): ResponsePath {
        val axis = mutableListOf<Double>()
        var x = 0.0
        while (x <= LENGTH_M) {
            axis += x
            x += if (x >= TWISTY_FROM_M && x < TWISTY_TO_M) 10.0 else 100.0
        }
        val points = PointList()
        axis.forEach { metres -> points.add(latAt(metres), lonAt(metres)) }
        val gravelFirst = axis.indexOfFirst { it >= GRAVEL_FROM_M }
        val gravelLast = axis.indexOfFirst { it >= GRAVEL_TO_M }
        return ResponsePath().setPoints(points).apply {
            addPathDetails(
                mapOf(
                    SURFACE_DETAIL to listOf(
                        PathDetail("asphalt").apply { first = 0; last = gravelFirst },
                        PathDetail("gravel").apply { first = gravelFirst; last = gravelLast },
                        PathDetail("asphalt").apply { first = gravelLast; last = axis.lastIndex },
                    ),
                ),
            )
        }
    }

    private fun offsetAt(metres: Double): Double =
        if (metres > TWISTY_FROM_M && metres < TWISTY_TO_M) 40.0 * sin(2 * PI * (metres - TWISTY_FROM_M) / 200.0) else 0.0

    private fun latAt(metres: Double): Double = ROUTE_LAT + offsetAt(metres) / METRES_PER_DEGREE

    private fun lonAt(metres: Double): Double =
        ROUTE_LON + metres / (METRES_PER_DEGREE * cos(Math.toRadians(ROUTE_LAT)))

    /** A point on the drawn line [metres] east along the axis (at a snake crossing, on the axis itself). */
    private fun at(metres: Double): LatLng = LatLng(latAt(metres), lonAt(metres))

    // --- map host -----------------------------------------------------------------

    private fun startMap(): MapLibreMap {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            MapLibre.getInstance(composeRule.activity.applicationContext)
        }
        composeRule.setContent {
            val context = LocalContext.current
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    val options = MapLibreMapOptions.createFromAttributes(context)
                        .textureMode(true)
                        .pixelRatio(viewContext.resources.displayMetrics.density)
                    MapView(viewContext, options).also { mapView ->
                        mapViewRef.set(mapView)
                        mapView.onCreate(null)
                        mapView.addOnDidFinishRenderingFrameListener { _, _, _ -> renderedFrames.incrementAndGet() }
                        mapView.onStart()
                        mapView.onResume()
                        mapView.getMapAsync { map ->
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(at(LENGTH_M / 2), ZOOM))
                            map.setStyle(Style.Builder().fromJson(SYNTHETIC_STYLE)) { style ->
                                styleRef.set(style)
                                styleLoaded.countDown()
                            }
                            mapRef.set(map)
                        }
                    }
                },
            )
        }
        assertTrue("the synthetic style did not load", styleLoaded.await(60, TimeUnit.SECONDS))
        val map = mapRef.get()
        assertNotNull("the MapView never reported a MapLibreMap", map)
        return map!!
    }

    /** Captures frames until [accept] holds twice in a row, so a stale frame is never measured. */
    private fun awaitFrameWhere(what: String, accept: (Bitmap) -> Boolean): Bitmap {
        val deadline = SystemClock.uptimeMillis() + 30_000
        var accepted = 0
        while (SystemClock.uptimeMillis() < deadline) {
            onMain { mapRef.get()!!.triggerRepaint() }
            SystemClock.sleep(250)
            val renderView = mapViewRef.get()!!.renderView
            assertTrue("the test MapView must render through a TextureView", renderView is TextureView)
            val frame = onMain { (renderView as TextureView).bitmap }
            if (frame != null && renderedFrames.get() > 0 && accept(frame)) {
                if (++accepted == 2) return frame
            } else {
                accepted = 0
            }
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun awaitLayerGone(layerId: String) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain { styleRef.get()?.getLayer(layerId) == null }) return
            SystemClock.sleep(150)
        }
        throw AssertionError("$layerId was never removed")
    }

    private fun Bitmap.pixelAt(x: Float, y: Float): Int =
        getPixel(x.roundToInt().coerceIn(0, width - 1), y.roundToInt().coerceIn(0, height - 1)) and 0xFFFFFF

    /** Within a small distance of [expected] in every channel (antialiasing and blending stay well inside it). */
    private fun near(rgb: Int, expected: Int): Boolean =
        listOf(16, 8, 0).all { shift -> abs((rgb shr shift and 0xFF) - (expected shr shift and 0xFF)) <= CHANNEL_TOLERANCE }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                result.set(block())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    private companion object {
        const val ROUTE_LAT = -27.4
        const val ROUTE_LON = 152.9
        const val METRES_PER_DEGREE = 111_195.0
        const val LENGTH_M = 7_000.0
        const val TWISTY_FROM_M = 2_000.0
        const val TWISTY_TO_M = 5_000.0
        const val GRAVEL_FROM_M = 3_600.0
        const val GRAVEL_TO_M = 3_900.0
        const val STRAIGHT_SAMPLE_M = 700.0

        /** A snake crossing (whole wavelengths from the start of the bends), so the axis point is on the line. */
        const val TWISTY_SAMPLE_M = 3_000.0

        /** Inside the REGION gravel band, with the whole route on a phone-width screen. */
        const val ZOOM = 11.7
        const val CHANNEL_TOLERANCE = 28

        val ROUTE_RGB = rgbOf(routeColorHex(0))

        val SYNTHETIC_STYLE = """
            {
              "version": 8,
              "name": "curvy-route-shading-test",
              "layers": [
                {"id": "background", "type": "background", "paint": {"background-color": "#FFFFFF"}}
              ]
            }
        """.trimIndent()
    }
}
