package com.curvemaps.app.map

import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Native regression coverage for the planner's route ghosts: the previous two
 * displayed routes stay as grey lines *under* the route on screen, the newest
 * ghost clearer than the older one, and neither is a route — route hit testing
 * must not see them, and clearing them leaves the route layers alone.
 *
 * A synthetic style (one background layer, no tiles) hosts the real production
 * draw calls, so the assertions describe what MapLibre actually draws rather
 * than what the Kotlin built. The map runs in texture mode so
 * `queryRenderedFeatures` is safe once a real frame has finished; nothing needs
 * an installed PMTiles archive.
 */
@RunWith(AndroidJUnit4::class)
class RouteGhostsOnDeviceTest {

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
            // The view may only have reached part of its lifecycle when the
            // test failed; clean what exists without masking the failure.
            runCatching {
                mapView.onPause()
                mapView.onStop()
            }
            runCatching { mapView.onDestroy() }
        }
    }

    @Test(timeout = 180_000)
    fun ghostLinesDrawUnderTheRouteAndStayOutOfRouteHitTesting() {
        val map = startMap()
        val framesBeforeDraw = renderedFrames.get()
        onMain {
            map.drawRoutes(listOf(line(ROUTE_LON)), 0, RouteGeometryCache())
            map.drawRouteGhosts(
                listOf(line(NEWEST_GHOST_LON), line(OLDEST_GHOST_LON)),
                RouteGhostGeometryCache(),
            )
        }
        awaitLayer(GHOST_LAYER_ID)
        awaitRouteHit(map)
        awaitFrame(map, framesBeforeDraw)

        val width = onMain { mapViewRef.get()!!.width.toFloat() }
        val height = onMain { mapViewRef.get()!!.height.toFloat() }
        val rendered = onMain {
            map.queryRenderedFeatures(RectF(0f, 0f, width, height), GHOST_LAYER_ID)
        }
        assertEquals("both ghost lines must render", 2, rendered.size)
        assertEquals(
            listOf(ghostOpacityFor(1), ghostOpacityFor(0)).sorted(),
            rendered.map { it.getNumberProperty(GHOST_OPACITY_PROPERTY).toFloat() }.sorted(),
        )

        onMain {
            val style = styleRef.get()!!
            val ghost = style.getLayerAs<LineLayer>(GHOST_LAYER_ID)
                ?: error("$GHOST_LAYER_ID must parse as a native line layer")
            assertEquals("round", ghost.lineCap.value)
            assertEquals("round", ghost.lineJoin.value)
            assertEquals(android.graphics.Color.parseColor(GHOST_ROUTE_COLOR), ghost.lineColorAsInt)
            assertEquals(GHOST_ROUTE_WIDTH, ghost.lineWidth.value!!, 0.001f)
            val ghostIndex = style.layers.indexOfFirst { it.id == GHOST_LAYER_ID }
            val casingIndex = style.layers.indexOfFirst { it.id == ROUTE_CASING_ID }
            val focusIndex = style.layers.indexOfFirst { it.id == ROUTE_FOCUS_LINE_ID }
            assertTrue(
                "ghosts must sit under the route " +
                    "(ghost=$ghostIndex, casing=$casingIndex, focus=$focusIndex)",
                ghostIndex in 0 until casingIndex && casingIndex < focusIndex,
            )
            // Ghosts are not routes: the same hit query that answers for the
            // route's own line finds nothing on a ghost.
            val ghostPoint = onMain { map.projection.toScreenLocation(LatLng(ROUTE_LAT, NEWEST_GHOST_LON)) }
            assertNull(
                "a ghost route must not be selectable",
                map.findRouteIndexAt(ghostPoint, HIT_RADIUS_PX, 0),
            )
            val routePoint = onMain { map.projection.toScreenLocation(LatLng(ROUTE_LAT, ROUTE_LON)) }
            assertEquals(0, map.findRouteIndexAt(routePoint, HIT_RADIUS_PX, 0))
        }

        // Clearing the decoration leaves the route layers alone.
        onMain { map.clearRouteGhosts() }
        awaitLayer(GHOST_LAYER_ID, present = false)
        onMain {
            val style = styleRef.get()!!
            assertNull(style.getSourceAs<GeoJsonSource>(GHOST_SOURCE_ID))
            assertNotNull("clearing ghosts must not touch the route", style.getLayer(ROUTE_LINE_ID))
        }
    }

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
                        // Track the view before any lifecycle call so a failed
                        // initialization is still torn down by @After.
                        mapViewRef.set(mapView)
                        mapView.onCreate(null)
                        mapView.addOnDidFinishRenderingFrameListener { _, _, _ ->
                            renderedFrames.incrementAndGet()
                        }
                        mapView.onStart()
                        mapView.onResume()
                        mapView.getMapAsync { map ->
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(CENTRE, ZOOM))
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
        assertTrue(
            "the synthetic style did not finish loading into the test MapView",
            styleLoaded.await(60, TimeUnit.SECONDS),
        )
        val map = mapRef.get()
        assertNotNull("the MapView never reported a MapLibreMap", map)
        awaitFrame(map!!, renderedFrames.get())
        return map
    }

    /**
     * MapLibre's texture renderer creates its native actor only once the
     * texture surface is live; `queryRenderedFeatures` before the first
     * finished frame dereferences a null actor and kills the test process.
     */
    private fun awaitFrame(map: MapLibreMap, previous: Int) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (renderedFrames.get() > previous) return
            onMain { map.triggerRepaint() }
            SystemClock.sleep(100)
        }
        throw AssertionError("the MapView never finished a render frame")
    }

    private fun awaitLayer(layerId: String, present: Boolean = true) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain { styleRef.get()?.getLayer(layerId) != null } == present) return
            SystemClock.sleep(150)
        }
        throw AssertionError("timed out waiting for $layerId present=$present")
    }

    /** The route must be selectable on its own line for the ghost-negation check to mean anything. */
    private fun awaitRouteHit(map: MapLibreMap) {
        val point = onMain { map.projection.toScreenLocation(LatLng(ROUTE_LAT, ROUTE_LON)) }
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            onMain { map.triggerRepaint() }
            if (onMain { map.findRouteIndexAt(point, HIT_RADIUS_PX, 0) } == 0) return
            SystemClock.sleep(150)
        }
        throw AssertionError("the drawn test route never became hit-testable")
    }

    private fun line(lon: Double): ResponsePath =
        ResponsePath().setPoints(PointList().apply {
            add(ROUTE_LAT - 0.005, lon)
            add(ROUTE_LAT, lon)
            add(ROUTE_LAT + 0.005, lon)
        })

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
        const val ROUTE_LAT = -27.405
        const val ROUTE_LON = 153.000
        const val NEWEST_GHOST_LON = 153.010
        const val OLDEST_GHOST_LON = 153.020
        const val ZOOM = 13.0
        const val HIT_RADIUS_PX = 24f

        val CENTRE = LatLng(-27.405, 153.010)

        /** One background layer and no source: the production calls add their own. */
        val SYNTHETIC_STYLE = """
            {
              "version": 8,
              "name": "route-ghost-test",
              "layers": [
                {"id": "background", "type": "background", "paint": {"background-color": "#FFFFFF"}}
              ]
            }
        """.trimIndent()
    }
}
