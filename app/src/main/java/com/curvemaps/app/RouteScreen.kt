package com.curvemaps.app

import com.curvemaps.app.ui.theme.CurveMapsDarkTheme
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.curvemaps.app.map.MapPerformanceProbe
import com.curvemaps.app.map.MapPerfProbeGate
import com.curvemaps.app.map.MapPerfSweepQueue
import com.curvemaps.app.map.MapPerfSweepRunner
import com.curvemaps.app.map.DARK_RIDE_BASEMAP_PROBE_LAYERS
import com.curvemaps.app.map.hideNavPosition
import com.curvemaps.app.map.updateGuidancePosition
import com.curvemaps.app.map.updateNavPosition
import com.curvemaps.app.routing.navigation.GpsFix
import com.curvemaps.app.routing.navigation.GpsFixSource
import com.curvemaps.app.routing.navigation.NavigationState
import com.curvemaps.app.routing.navigation.RebuildRequest
import com.curvemaps.app.routing.navigation.VoiceDistanceUnit
import com.curvemaps.app.routing.navigation.VoiceGuidanceSettings
import com.curvemaps.app.routing.suggestion.RideSuggestionInputs
import com.curvemaps.app.routing.suggestion.RideSuggestionState
import com.curvemaps.app.map.clearSuggestionPreview
import com.curvemaps.app.map.drawSuggestionPreview
import com.curvemaps.app.routing.RouteResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalFloatingToolbar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.graphhopper.util.shapes.GHPoint
import com.curvemaps.app.geocoding.GeocodeSearchController
import com.curvemaps.app.onboarding.WelcomeExit
import com.curvemaps.app.media.AndroidMediaKeyController
import com.curvemaps.app.media.AndroidMediaSessionGateway
import com.curvemaps.app.media.AndroidVolumeController
import com.curvemaps.app.media.AndroidMusicAppGateway
import com.curvemaps.app.media.GuidanceResumeWatch
import com.curvemaps.app.media.MediaCenterController
import com.curvemaps.app.media.MediaControlCenter
import com.curvemaps.app.media.MediaListenerBridge
import com.curvemaps.app.media.MediaPanelJoin
import com.curvemaps.app.media.VolumeTarget
import com.curvemaps.app.map.clearRouteGhosts
import com.curvemaps.app.map.clearRoutes
import com.curvemaps.app.map.drawRouteGhosts
import com.curvemaps.app.map.drawRoutes
import com.curvemaps.app.map.findRouteIndexAt
import com.curvemaps.app.map.fitBounds
import com.curvemaps.app.map.hideGpxPreviewMarker
import com.curvemaps.app.map.RouteGeometryCache
import com.curvemaps.app.map.RouteGhostGeometryCache
import com.curvemaps.app.map.RouteGhostHistory
import com.curvemaps.app.map.showGpxPreviewMarker
import com.curvemaps.app.routing.GraphHopperRouter
import com.curvemaps.app.routing.PointParser
import com.curvemaps.app.routing.RideFactors
import com.curvemaps.app.routing.DistinctRideStyles
import com.curvemaps.app.routing.RideStyle
import com.curvemaps.app.routing.RoundTripLength
import com.curvemaps.app.routing.RoundTripWish
import com.curvemaps.app.routing.RoundTrips
import com.curvemaps.app.region.MapFileKind
import com.curvemaps.app.region.RegionDatasets
import com.curvemaps.app.region.RegionManager
import com.curvemaps.app.storage.GeoPoint
import com.curvemaps.app.storage.GpxParser
import com.curvemaps.app.storage.GpxMilestone
import com.curvemaps.app.storage.GpxPreview
import com.curvemaps.app.storage.PolylineCodec
import com.curvemaps.app.storage.RouteSimilarity
import com.curvemaps.app.storage.SavedRoute
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import com.curvemaps.app.storage.SavedRouteDraft
import com.curvemaps.app.storage.SavedRouteRepository
import com.curvemaps.app.storage.GpxGeometry
import com.curvemaps.app.storage.GpxRideMode
import com.curvemaps.app.storage.GpxRidePlan
import com.curvemaps.app.storage.GpxTrackFollow
import com.curvemaps.app.storage.ImportedGpx
import com.curvemaps.app.storage.ImportedGpxStore
import com.curvemaps.app.storage.TrackFollowMeasure
import com.curvemaps.app.tiles.OfflineTileStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlin.coroutines.coroutineContext
import java.io.File
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.maps.MapView
import kotlin.coroutines.cancellation.CancellationException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "CurveMaps.RouteScreen"
private const val TAG_SAVED = "CurveMaps.SavedRoutes"
private const val TAG_MEDIA = "CurveMaps.Media"
private const val TAG_MAP_PERF = "CurveMaps.MapPerf"
private const val STYLE_ASSET = "style.json"
private const val DARK_RIDE_STYLE_ASSET = "ride-dark-style.json"
private const val TILES_PATH_PLACEHOLDER = "{tiles_path}"
internal const val ROUTE_PREFS = "route_preferences"
private const val BLOCK_UNPAVED_PREF = "block_unpaved_roads"
private const val AVOID_MOTORWAYS_PREF = "avoid_motorways"
private const val VOICE_ENABLED_PREF = "voice_guidance_enabled"
private const val VOICE_INTERVAL_METERS_PREF = "voice_guidance_interval_meters"
private const val VOICE_PREVIEW_COUNT_PREF = "voice_guidance_preview_count"
private const val DARK_RIDE_MAP_PREF = "dark_ride_map_enabled"
private const val RIDE_SUGGESTIONS_PREF = "ride_suggestions"
private const val INCLUDE_ELEVATION_PREF = "include_elevation"
private const val AVOID_STOPS_AND_TOWNS_PREF = "avoid_stops_and_towns"
internal const val ROUND_TRIP_LENGTH_PREF = "round_trip_length"

/** The To name a saved round trip carries. */
internal const val ROUND_TRIP_TO_NAME = "Round trip"

/** Keeps the round-trip setting across recreation ([RoundTripWish.storageKey]). */
private val RoundTripWishSaver = Saver<RoundTripWish, String>(
    save = { it.storageKey },
    restore = { RoundTripWish.parse(it) },
)

private data class NativeStyleLoadRequest(
    val json: String?,
    val map: MapLibreMap?,
    val screenStarted: Boolean,
    val revision: Int,
    val darkRideStyle: Boolean,
)

/**
 * What the system "Save to" dialog is being opened for. The region download,
 * an interrupted-download resume, and saving a recovered app-storage package
 * all use the same create-document picker.
 */
private sealed interface MapsDownloadAction {
    data class New(val regionId: String) : MapsDownloadAction
    data object Resume : MapsDownloadAction
    data object SaveRecovered : MapsDownloadAction
}

// While the media panel shows the local music stream, the app polls the real
// AudioManager readback at this interval so hardware volume presses (which are
// invisible to the app) still update the displayed level and limits.
private const val LOCAL_VOLUME_POLL_MILLIS = 1_000L

/**
 * True for debuggable (debug) builds. Release APKs are never debuggable, so
 * user-typed queries, source/destination text and coordinates are only logged
 * when this is true (debug builds), never in release.
 */
private fun isDebugBuild(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/** Unwraps the host activity so the debug probe can read its window metrics. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// The rendering backend is chosen at build time by the MapLibre artifact
// (debug: OpenGL, release: Vulkan; see app/build.gradle.kts). A runtime switch
// is not possible with these single-backend artifacts: MapLibre.getInstance
// fixes RenderingEngine's type on first call, and setCurrentType rejects the
// other backend with UnsupportedOperationException.

/** Converts GraphHopper's point list into the storage package's vocabulary type. */
private fun PointList.toGeoPoints(): List<GeoPoint> =
    List(size()) { index -> GeoPoint(getLat(index), getLon(index)) }

private suspend fun loadOfflineStyle(
    context: Context,
    tilesUrl: String,
    darkRideMode: Boolean,
): String {
    val style = withContext(Dispatchers.IO) {
        val asset = if (darkRideMode) DARK_RIDE_STYLE_ASSET else STYLE_ASSET
        context.assets.open(asset).bufferedReader().use { it.readText() }
    }
    return style.replace(TILES_PATH_PLACEHOLDER, tilesUrl)
}


/**
 * Human-readable travel-time estimate for a routed path, e.g. "45 min",
 * "3 h 20 min". Minutes are floored so the estimate never overstates the
 * ride; sub-minute routes read "<1 min".
 *
 * The source value is [ResponsePath.getTime] — GraphHopper's summed edge
 * travel time (`weighting.calcEdgeMillis`). At every dial position this is
 * real-world travel time, not weighting units: the ride-style weighting and the
 * pure Moto path both use the motorcycle model's effective speeds, converted
 * correctly from km/h to seconds by the weighting implementation.
 */
internal fun formatRouteDuration(timeMillis: Long): String {
    val totalMinutes = timeMillis / 60_000
    val hours = totalMinutes / 60
    val minutes = (totalMinutes % 60).toInt()
    return when {
        hours > 0 -> "$hours h $minutes min"
        totalMinutes > 0 -> "$totalMinutes min"
        else -> "<1 min"
    }
}

/**
 * Human-readable route distance, e.g. "850 m", "4.7 km", "1,675 km".
 * Whole metres below 1 km, one decimal up to 10 km, grouped whole kilometres
 * above (one decimal on a 1,700 km ride is noise).
 */
internal fun formatRouteDistance(distanceMeters: Double): String = when {
    distanceMeters >= 10_000 ->
        "%,.0f km".format(Locale.US, distanceMeters / 1000.0)
    distanceMeters >= 1_000 ->
        "%.1f km".format(Locale.US, distanceMeters / 1000.0)
    else ->
        "${distanceMeters.toInt()} m"
}

/**
 * Resolves free-typed field text to a point: the top offline-geocoder hit
 * first (covers place names AND "lat,lon" — SearchEngine returns a COORDINATE
 * result for coordinates), then the lat,lon parser as a last resort.
 */
private suspend fun resolvePoint(
    text: String,
    controller: GeocodeSearchController,
    field: String,
    debugLogs: Boolean,
): GHPoint {
    val trimmed = text.trim()
    require(trimmed.isNotEmpty()) { "$field is empty — enter a place name or lat,lon" }
    if (debugLogs) Log.d(TAG, "[$field] resolving \"$trimmed\"")
    val top = try {
        controller.search(trimmed, limit = 1).firstOrNull()
    } catch (e: CancellationException) {
        throw e // never swallow coroutine cancellation (screen left / scope cancelled)
    } catch (e: Exception) {
        if (debugLogs) Log.w(TAG, "[$field] geocoder lookup failed for \"$trimmed\" — falling back to lat,lon parser", e)
        null // index unavailable: fall through to the coordinate parser
    }
    top?.let {
        if (debugLogs) Log.d(TAG, "[$field] geocoder hit: \"${it.name}\" (${it.type}) lat=${it.lat} lon=${it.lon}")
        return GHPoint(it.lat, it.lon)
    }
    return try {
        PointParser.parse(trimmed).also {
            if (debugLogs) Log.d(TAG, "[$field] parsed lat,lon fallback: lat=${it.lat} lon=${it.lon}")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException(
            "No match for \"$trimmed\" — pick a suggestion or enter lat,lon", e
        )
    }
}

/** Keep search generations stable while regional routing dependencies change. */
@Composable
internal fun rememberRouteSearchCoordinator(
    scope: kotlinx.coroutines.CoroutineScope,
    backend: suspend (RouteParams) -> RouteOutcome,
): RouteSearchCoordinator {
    val latestBackend = rememberUpdatedState(backend)
    return remember(scope) { RouteSearchCoordinator(scope, backend = { latestBackend.value(it) }) }
}

@Composable
fun RouteScreen(
    startupAction: WelcomeExit = WelcomeExit.NONE,
    onStartupActionHandled: () -> Unit = {},
    incomingGpx: Uri? = null,
    onIncomingGpxHandled: () -> Unit = {},
) {
    // OSM permits collapsing startup attribution after five seconds; credits
    // remain available in the planning settings menu.
    var startupAttributionVisible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(5_000)
        startupAttributionVisible = false
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var screenStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    // Regional datasets: the active package decides the graph, geocoder, and
    // basemap. The manager is app-scoped (it survives recomposition and keeps
    // polling/downloading while the settings screen is closed).
    val regionManager = remember { RegionManager.get(context.applicationContext) }
    val mapsState by regionManager.state.collectAsState()
    // Debug visual tooling (tools/test/visual.py) can preview intermediate
    // Maps states; release builds never set this flow.
    val visualMapsPreview by VisualMapsPreview.state.collectAsState()
    val activeDataset by regionManager.dataset.collectAsState()
    val router = remember(activeDataset.installId, activeDataset.generation) {
        GraphHopperRouter(
            context.applicationContext,
            activeDataset.graphDir,
            activeDataset.copyGraphFromAssets,
        )
    }
    // The coordinator captures its backend once; rememberUpdatedState keeps a
    // dataset switch from leaving it pointed at the closed router.
    val latestRouter = rememberUpdatedState(router)
    DisposableEffect(router) {
        val releaseDatasetReader = regionManager.retainDatasetReader(activeDataset.installId)
        // The dataset this router was built from: a pending active-map deletion
        // waits for exactly this reader to be released before unlinking files.
        onDispose {
            val drain = router.closeAsync()
            if (drain == null) {
                releaseDatasetReader()
            } else {
                // Join the drain off the UI thread, then let the manager know
                // the graph directory is no longer in use.
                Thread(
                    {
                        // Interrupted joins must not release a lease over a live MMAP.
                        var interrupted = false
                        while (drain.isAlive) {
                            try { drain.join() } catch (_: InterruptedException) { interrupted = true }
                        }
                        releaseDatasetReader()
                        if (interrupted) Thread.currentThread().interrupt()
                    },
                    "gh-drain",
                ).apply { isDaemon = true }.start()
            }
        }
    }
    val scope = rememberCoroutineScope()
    val debugLogs = remember { isDebugBuild(context.applicationContext) }
    // All seven ride-style options of the current plan, each a different ride,
    // computed before the slider appears so moving it is instant. One per
    // router: a dataset switch must not serve the old region's routes.
    val styleOptions = remember(router, scope) {
        RideStyleOptions(
            scope,
            onSettled = { style, option ->
                Log.i(
                    TAG,
                    "Ride style ${style.label} ready: ${formatRouteDuration(option.path.time)}, " +
                        "${option.path.distance.roundToInt()} m" +
                        when {
                            option.repeats != null ->
                                ", repeats ${option.repeats.label} (the graph has no other road)"
                            option.loop != null ->
                                ", round trip heading ${option.loop.bearing.roundToInt()}° (loop ${option.round})"
                            option.via != null ->
                                ", found looking around (round ${option.round})"
                            option.roadPenalty > 0.0 ->
                                ", diversified (road penalty ${option.roadPenalty}, round ${option.round})"
                            option.round > 0 ->
                                ", curvier (weight ${option.curveWeight}, round ${option.round})"
                            else -> ""
                        } + when (option.budget) {
                            DistinctRideStyles.Budget.STYLE -> ""
                            DistinctRideStyles.Budget.RELAXED -> ", beyond the style's budget (within twice its allowance)"
                            DistinctRideStyles.Budget.NONE -> ", beyond every budget (no closer different ride)"
                        },
                )
            },
        ) { params, style, search ->
            router.route(
                params.from,
                params.to,
                style.level.toDouble(),
                params.blockUnpaved,
                params.avoidMotorways,
                params.viaPoints + listOfNotNull(search.via) + search.loop?.vias.orEmpty(),
                curveWeight = search.curveWeight,
                penalizedEdges = search.penalizedEdges,
                roadPenalty = search.roadPenalty,
                factors = params.factors,
                passThrough = search.via != null || search.loop != null || params.passThrough,
                viaSearchMetres = search.loop?.searchMeters,
                twoWayVias = search.via != null,
                viaHeadings = if (params.viaHeadings.isEmpty()) emptyList() else {
                    params.viaHeadings +
                        List(listOfNotNull(search.via).size + search.loop?.vias.orEmpty().size) { Double.NaN }
                },
                startHeading = params.startHeading,
            )
        }
    }
    DisposableEffect(styleOptions) {
        onDispose { styleOptions.close() }
    }
    val latestStyleOptions = rememberUpdatedState(styleOptions)
    val routeBackend: suspend (RouteParams) -> RouteOutcome = { params ->
        val started = SystemClock.elapsedRealtime()
        try {
            if (!activeDataset.dataPresent) throw IllegalStateException(NO_MAP_INSTALLED_MESSAGE)
            val preferred = params.preferredGeometry
            val followed = params.followTrack
            // A saved ride must find its stored shape, which may have been an
            // alternative of an older carousel. It must not wait for seven
            // distinct rides: search the requested stop's own optimum (and its
            // alternatives) directly and keep the closest. A followed GPX
            // track is routed road for road along its line.
            val result = if (followed != null) {
                val job = coroutineContext.job
                withContext(Dispatchers.IO) {
                    followGpxTrack(latestRouter.value, params, followed) { job.ensureActive() }
                }
            } else if (preferred == null) {
                latestStyleOptions.value.route(params)
            } else {
                val candidates = withContext(Dispatchers.IO) {
                    latestRouter.value.route(
                        params.from,
                        params.to,
                        params.complexity,
                        params.blockUnpaved,
                        params.avoidMotorways,
                        params.viaPoints,
                        alternatives = true,
                        factors = params.factors,
                        viaHeadings = params.viaHeadings,
                    ).routes
                }
                val matched = RouteSimilarity.bestMatchIndex(
                    PolylineCodec.decode(preferred),
                    candidates.map { path -> path.points.toGeoPoints() },
                ) ?: 0
                if (matched != 0) Log.i(TAG_SAVED, "Restored route matched candidate ${matched + 1}")
                RouteResult(listOf(candidates[matched]))
            }
            val primary = result.routes.first()
            Log.i(
                TAG,
                "Route success in ${SystemClock.elapsedRealtime() - started} ms: " +
                    "${primary.distance}m, ${primary.time}ms, " +
                    "${primary.points.size()} points, eta=${formatRouteDuration(primary.time)}"
            )
            RouteOutcome.Success(result, 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (debugLogs) {
                Log.e(TAG, "Route request failed after ${SystemClock.elapsedRealtime() - started} ms", e)
            } else {
                Log.e(TAG, "Route request failed after ${SystemClock.elapsedRealtime() - started} ms")
            }
            RouteOutcome.Failure(describeRoutingFailure(e.message ?: "Routing failed"))
        }
    }
    val coordinator = rememberRouteSearchCoordinator(scope, routeBackend)
    val state by coordinator.state.collectAsState()
    // Progress is published from the search threads; collect it on Main so
    // the collector never resumes (and recomposes the map) on a worker.
    val styleSearchProgress by styleOptions.progress.collectAsState(context = Dispatchers.Main)
    val routeGeneration by coordinator.generations.collectAsState()
    // The ride-style slider pops in once a search has every option ready and
    // stays while the rider slides between stops; Back, a cleared plan or a
    // failed search return to the From/To fields.
    var plannerStage by remember { mutableStateOf(PlannerStage.Endpoints) }
    LaunchedEffect(state) {
        when (state) {
            is RouteUiState.Success -> plannerStage = PlannerStage.Styles
            RouteUiState.Idle, is RouteUiState.Error -> plannerStage = PlannerStage.Endpoints
            RouteUiState.Loading -> Unit
        }
    }
    // Editable plan inputs survive rotation; the computed route itself does
    // not and is simply re-runnable via START.
    var fromText by rememberSaveable { mutableStateOf("") }
    var toText by rememberSaveable { mutableStateOf("") }
    var fromPoint by remember { mutableStateOf<GHPoint?>(null) }
    var toPoint by remember { mutableStateOf<GHPoint?>(null) }
    var fromLocationState by remember { mutableStateOf(CurrentLocationSelectionState()) }
    // The current location the rider tapped into From to edit: put back if they
    // leave the field without writing another place (keepsCurrentLocationOnLeave).
    var heldCurrentLocation by remember { mutableStateOf<GHPoint?>(null) }
    fun cancelFromLocationRequest() {
        fromLocationState = fromLocationState.cancel()
    }
    // Ride-style dial level (RideStyle): 0 = Fastest .. RideStyle.MAX_LEVEL.
    // Road-type choices live in Route settings, never on the dial.
    var complexity by rememberSaveable { mutableStateOf(0f) }
    val voiceUnit = remember { VoiceDistanceUnit.forLocale(Locale.getDefault()) }
    val routePreferences = remember {
        context.applicationContext.getSharedPreferences(ROUTE_PREFS, Context.MODE_PRIVATE)
    }
    var blockUnpaved by rememberSaveable {
        mutableStateOf(routePreferences.getBoolean(BLOCK_UNPAVED_PREF, false))
    }
    var avoidMotorways by rememberSaveable {
        mutableStateOf(routePreferences.getBoolean(AVOID_MOTORWAYS_PREF, false))
    }
    // Ride factors (Route settings > Include in route calculations). Off by
    // default; like the road toggles they persist and re-route on Apply.
    var includeElevation by rememberSaveable {
        mutableStateOf(routePreferences.getBoolean(INCLUDE_ELEVATION_PREF, false))
    }
    var avoidStopsAndTowns by rememberSaveable {
        mutableStateOf(routePreferences.getBoolean(AVOID_STOPS_AND_TOWNS_PREF, false))
    }
    val rideFactors = RideFactors(elevation = includeElevation, stopsAndTowns = avoidStopsAndTowns)
    // Round trip (the loop toggle beside To): the ride comes back to From and
    // the To row gives way to the wished ride time, distance or back-by time.
    // The setting persists; whether a round trip is on belongs to the plan,
    // like the fields.
    var roundTripEnabled by rememberSaveable { mutableStateOf(false) }
    var roundTripWish by rememberSaveable(stateSaver = RoundTripWishSaver) {
        mutableStateOf(RoundTripWish.parse(routePreferences.getString(ROUND_TRIP_LENGTH_PREF, null)))
    }
    var voiceGuidanceEnabled by rememberSaveable {
        mutableStateOf(routePreferences.getBoolean(VOICE_ENABLED_PREF, true))
    }
    var darkRideMapEnabled by rememberSaveable {
        mutableStateOf(routePreferences.getBoolean(DARK_RIDE_MAP_PREF, false))
    }
    // Mid-ride scenic suggestions (Route settings). On by default; checks are
    // rare and pause on low battery, see SuggestionScheduler.
    var rideSuggestionsEnabled by rememberSaveable {
        mutableStateOf(routePreferences.getBoolean(RIDE_SUGGESTIONS_PREF, true))
    }
    var voiceIntervalMeters by rememberSaveable {
        val defaultInterval = VoiceGuidanceSettings.defaults(voiceUnit).intervalMeters
        val minimumInterval = voiceUnit.toMetres(voiceUnit.minimumInterval)
        val maximumInterval = voiceUnit.toMetres(voiceUnit.maximumInterval)
        val persistedInterval = routePreferences.getFloat(
            VOICE_INTERVAL_METERS_PREF,
            defaultInterval.toFloat(),
        ).toDouble()
        mutableStateOf(
            (persistedInterval.takeIf { it.isFinite() } ?: defaultInterval).coerceIn(
                minimumInterval,
                maximumInterval,
            ),
        )
    }
    var voicePreviewCount by rememberSaveable {
        mutableStateOf(routePreferences.getInt(VOICE_PREVIEW_COUNT_PREF, 2).coerceIn(1, 5))
    }
    var voiceSettingsOpen by remember { mutableStateOf(false) }
    var routeSettingsOpen by remember { mutableStateOf(false) }
    // The settings cog expands into a card over the whole screen; the tap
    // scrim and the BackHandler live here rather than inside the rail so the
    // dismissal covers the planner panel too. Not rememberSaveable: menus are
    // deliberately closed across recreation, like the other overlays.
    var settingsMenuOpen by remember { mutableStateOf(false) }
    // Complete inputs of the most recent submission attempt (START / dial
    // release / settings apply / saved-route load). GPX imports carry bounded
    // shaping points here as well as their endpoints; keeping the complete
    // immutable request is what lets a later control edit re-route the same
    // imported ride instead of silently falling back to an endpoint-only ride.
    // Nulled whenever either field changes, so a stale reroute can never
    // target edited-away points.
    var activePlan by remember { mutableStateOf<RouteParams?>(null) }
    // Bumped when a legacy basemap replacement is imported at the same private
    // path; the style reload effect keys on it.
    var mapRevision by remember { mutableIntStateOf(0) }
    val geocodeController = remember(activeDataset.installId, activeDataset.generation) {
        GeocodeSearchController(context.applicationContext, activeDataset.geocoderFile)
    }
    // A dataset switch invalidates everything computed from the previous
    // region: displayed routes, resolved points, and the map style all rebuild
    // from the new package. Saved rides are untouched (they are just data).
    // The first composition initializes the key and does nothing: bumping the
    // map revision there would start a second style load on every launch.
    var appliedDatasetKey by remember {
        mutableStateOf(activeDataset.installId to activeDataset.generation)
    }
    // Guidance: pure engine + Android glue. One controller per screen.
    val navigationController = remember { NavigationController(scope) }
    val navSnapshot by navigationController.snapshot.collectAsStateWithLifecycle()
    val rideSuggestion by navigationController.suggestionState.collectAsStateWithLifecycle()
    val gpsSignalLost by navigationController.gpsSignalLost.collectAsStateWithLifecycle()
    LaunchedEffect(rideSuggestion) {
        if (BuildConfig.DEBUG) withContext(Dispatchers.IO) {
            VisualRouteSnapshot.writeSuggestion(context.cacheDir, rideSuggestion)
        }
    }
    LaunchedEffect(navSnapshot) {
        if (BuildConfig.DEBUG && navSnapshot.state != NavigationState.Idle) withContext(Dispatchers.IO) {
            VisualRouteSnapshot.writeGuidance(context.cacheDir, navSnapshot)
        }
    }
    val rideSuggestionSearch = remember { RideSuggestionSearch() }
    val batteryStateCache = remember { BatteryStateCache(context.applicationContext) }
    DisposableEffect(rideSuggestionSearch) {
        onDispose { rideSuggestionSearch.close() }
    }
    val voiceGuidanceOutput = remember {
        OfflineVoiceGuidance(context.applicationContext, voiceUnit)
    }
    val voiceSpeechStatus by voiceGuidanceOutput.status.collectAsState()
    val voiceGuidanceSettings = VoiceGuidanceSettings(
        enabled = voiceGuidanceEnabled,
        intervalMeters = voiceIntervalMeters,
        previewCount = voicePreviewCount,
    )
    val latestVoiceGuidanceSettings = rememberUpdatedState(voiceGuidanceSettings)
    DisposableEffect(voiceGuidanceOutput) {
        onDispose { voiceGuidanceOutput.close() }
    }
    var guidanceRequested by remember { mutableStateOf(false) }
    var locationPermissionGranted by remember {
        mutableStateOf(LocationPermission.isGranted(context))
    }
    var pendingGuidanceStart by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Runtime permissions belong to Android, not app storage: a normal
        // grant is retained across launches until the user changes it in
        // Settings. Android may expire one-time grants by policy.
        val granted = LocationPermission.isGranted(context)
        val waitingForPermission =
            fromLocationState.status as? CurrentLocationStatus.WaitingForPermission
        if (waitingForPermission != null) {
            fromLocationState = fromLocationState.permissionResult(waitingForPermission.request.id, granted)
            if (!granted) {
                fromText = ""
                fromPoint = null
            }
        }
        locationPermissionGranted = granted
        if (pendingGuidanceStart) {
            guidanceRequested = granted
            pendingGuidanceStart = false
        }
        Log.i(TAG, "Location permission " + if (granted) "granted" else "denied")
    }
    // Location is first requested on the welcome carousel's location page;
    // after that only START and Current location ask, never app startup.
    // Refresh the cached UI state after returning from Settings, where a user
    // may grant or revoke location access without going through our launcher.
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val guidanceBearingTracker = remember { GuidanceBearingTracker() }
    var planningCameraBeforeGuidance by remember { mutableStateOf<CameraPosition?>(null) }
    var guidanceWasActive by remember { mutableStateOf(false) }
    // Saved-route storage: repository over device SQLite plus the menu flag.
    val savedRouteRepository = remember { SavedRouteRepository(context.applicationContext) }
    var savedRoutesOpen by remember { mutableStateOf(false) }
    var mapsSettingsOpen by remember { mutableStateOf(false) }
    var missingMapDialogDismissed by rememberSaveable { mutableStateOf(false) }
    var ossLicencesOpen by remember { mutableStateOf(false) }
    // The imported GPX ride the planner shows. Saved state only records that
    // one is open; the ride itself lives in a private file, so rotation and
    // process death bring it back (a long track is far too big for saved
    // state). One store dispatcher keeps every write and delete in order.
    val importedGpxStore = remember {
        ImportedGpxStore(File(context.filesDir, IMPORTED_GPX_FILE))
    }
    val importedGpxStoreDispatcher = remember { Dispatchers.IO.limitedParallelism(1) }
    var importedGpx by remember { mutableStateOf<ImportedGpx?>(null) }
    var importedGpxOpen by rememberSaveable { mutableStateOf(false) }
    val importedGpxName = importedGpx?.name
    var gpxPreviewOpen by remember { mutableStateOf(false) }
    var gpxImportJob by remember { mutableStateOf<Job?>(null) }
    fun clearImportedGpx() {
        gpxImportJob?.cancel()
        gpxImportJob = null
        gpxPreviewOpen = false
        if (importedGpx != null || importedGpxOpen) {
            importedGpx = null
            importedGpxOpen = false
            scope.launch(importedGpxStoreDispatcher) { importedGpxStore.clear() }
        }
    }
    LaunchedEffect(activeDataset.installId, activeDataset.generation) {
        val key = activeDataset.installId to activeDataset.generation
        if (key == appliedDatasetKey) return@LaunchedEffect
        appliedDatasetKey = key
        clearImportedGpx()
        coordinator.reset()
        activePlan = null
        fromPoint = null
        cancelFromLocationRequest()
        toPoint = null
        mapRevision++
        Log.i(
            TAG,
            "Active region: ${activeDataset.displayName} (${activeDataset.regionId}, " +
                "generation ${activeDataset.generation})",
        )
    }
    val requestCurrentLocation: () -> Unit = {
        fromLocationState = fromLocationState.beginRequest()
        val waiting = fromLocationState.status as CurrentLocationStatus.WaitingForPermission
        fromText = ""
        fromPoint = null
        clearImportedGpx()
        activePlan = null
        coordinator.invalidate()
        if (LocationPermission.isGranted(context)) {
            locationPermissionGranted = true
            fromLocationState = fromLocationState.permissionResult(waiting.request.id, granted = true)
        } else {
            permissionLauncher.launch(LocationPermission.requestedPermissions)
        }
    }

    // Ride media control center: native MediaSessionManager discovery plus a
    // local AudioManager volume path that needs no notification access. The
    // panel opens from the data bar; the controller reads the platform only
    // while the panel is visible. A player screen that the panel opens
    // shrinks the ride to its Picture-in-Picture window first.
    val hostActivity = remember(context) { context.findActivity() }
    val mediaController = remember {
        MediaCenterController(
            gateway = AndroidMediaSessionGateway(context.applicationContext),
            localVolume = AndroidVolumeController(context.applicationContext),
            mediaKeys = AndroidMediaKeyController(context.applicationContext),
            musicApps = AndroidMusicAppGateway(
                context.applicationContext,
                rideOnTop = {
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        hostActivity?.isInPictureInPictureMode != true
                },
                beforeLeavingRide = { hostActivity?.let(RidePictureInPicture::enter) },
            ),
            onLog = { message ->
                // Media state transitions are debug-build diagnostics only.
                if (debugLogs) Log.d(TAG_MEDIA, message)
            },
        )
    }
    val mediaState by mediaController.state.collectAsState()
    var mediaPanelOpen by remember { mutableStateOf(false) }
    DisposableEffect(mediaController) {
        mediaController.attach()
        onDispose {
            mediaController.setPanelOpen(false)
            mediaController.detach()
        }
    }
    // A player that paused for a spoken prompt (instead of ducking) and did
    // not restart by itself gets one PLAY after the grace window. Scoped to
    // guidance's own duck; see GuidanceResumeWatch.
    DisposableEffect(voiceGuidanceOutput, mediaController) {
        val main = Handler(Looper.getMainLooper())
        val resume = Runnable { mediaController.resumeAfterGuidance(SystemClock.elapsedRealtime()) }
        val onDuck: (GuidanceDuck) -> Unit = { duck ->
            when (duck) {
                GuidanceDuck.STARTED -> {
                    main.removeCallbacks(resume)
                    mediaController.onGuidanceDuckStarted(SystemClock.elapsedRealtime())
                }
                GuidanceDuck.ENDED -> {
                    mediaController.onGuidanceDuckEnded(SystemClock.elapsedRealtime())
                    main.removeCallbacks(resume)
                    main.postDelayed(resume, GuidanceResumeWatch.RESUME_GRACE_MS)
                }
                GuidanceDuck.INTERRUPTED -> {
                    main.removeCallbacks(resume)
                    mediaController.onGuidanceDuckInterrupted()
                }
            }
        }
        // STARTED must run before the focus request (the player is read while
        // still playing), so main-thread events run inline; TTS binder-thread
        // events hop to main to keep the controller single-threaded.
        voiceGuidanceOutput.duckListener = { duck ->
            if (Looper.myLooper() == Looper.getMainLooper()) onDuck(duck) else main.post { onDuck(duck) }
        }
        onDispose {
            voiceGuidanceOutput.duckListener = null
            main.removeCallbacks(resume)
            mediaController.onGuidanceDuckInterrupted()
        }
    }
    // The listener service connecting/disconnecting changes session
    // availability and can drop the platform callback: re-assert the listener
    // and refresh an open panel when that happens.
    DisposableEffect(mediaController) {
        val bridge = { mediaController.onListenerServiceChanged() }
        MediaListenerBridge.addListener(bridge)
        onDispose { MediaListenerBridge.removeListener(bridge) }
    }
    LaunchedEffect(mediaPanelOpen) {
        mediaController.setPanelOpen(mediaPanelOpen)
    }
    // Measured from the real bar so map controls and the media panel sit
    // exactly above it instead of at hard-coded ride offsets.
    var dataBarHeightPx by remember { mutableIntStateOf(0) }
    val dataBarHeightDp = with(LocalDensity.current) { dataBarHeightPx.toDp() }
    // Measured directions HUD so the media panel can never grow over it.
    var hudHeightPx by remember { mutableIntStateOf(0) }
    val hudHeightDp = with(LocalDensity.current) { hudHeightPx.toDp() }
    val rideActive = guidanceRequested && navSnapshot.state != NavigationState.Idle
    // Deleting the active map is refused mid-ride; the manager enforces the
    // same rule as the disabled trash control.
    LaunchedEffect(rideActive) {
        regionManager.setRideActive(rideActive)
    }
    // In the Picture-in-Picture window the ride shows only the map and the
    // compact turn card; every control waits for the full ride.
    val inPictureInPicture = rememberInPictureInPicture(hostActivity)
    val mapControlsHidden = (rideActive && mediaPanelOpen) || inPictureInPicture
    // The planner's search results open as a sheet in their own popup window
    // over the bottom of the map; the bottom-right map controls lift above it
    // (MapControlsLayout). Window pixels, measured from the real layout.
    var searchSheetTopPx by remember { mutableStateOf<Int?>(null) }
    var mapBoxTopPx by remember { mutableIntStateOf(0) }
    var mapBoxBottomPx by remember { mutableIntStateOf(0) }
    // The map fills the screen and the planner floats over its bottom: the
    // map below the map box is covered (panel, its margins, the keyboard).
    var mapViewBottomPx by remember { mutableIntStateOf(0) }
    val mapCoveredBelowPx = (mapViewBottomPx - mapBoxBottomPx).coerceAtLeast(0)
    // MapLibre's logo and attribution sit at the view's bottom: keep them
    // above the floating panel.
    val baseOrnamentMargins = remember { IntArray(2) { -1 } }
    // The planner / ride toolbar's measured extent (window pixels).
    var railBottomPx by remember { mutableIntStateOf(0) }
    var railTopPx by remember { mutableIntStateOf(0) }
    val ornamentDensity = LocalDensity.current
    // Route framing keeps the route's ends clear of the right-hand control column
    // (toolbar edge + 56 dp + a gap) and of the status bar.
    val controlsInsetPx = with(ornamentDensity) {
        (MapControlsLayout.PLANNER_END + MapControlsLayout.TOOLBAR_WIDTH + MapControlsLayout.CONTROL_GAP).roundToPx()
    }
    val statusBarTopPx = WindowInsets.statusBars.getTop(ornamentDensity)
    val fitTopInsetPx = statusBarTopPx + with(ornamentDensity) { 24.dp.roundToPx() }
    // The logo and attribution button keep clear of the panel's shadow too.
    val ornamentLiftPx = with(ornamentDensity) { 8.dp.roundToPx() }
    LaunchedEffect(mapRef.value, mapCoveredBelowPx) {
        val ui = mapRef.value?.uiSettings ?: return@LaunchedEffect
        if (baseOrnamentMargins[0] < 0) {
            baseOrnamentMargins[0] = ui.logoMarginBottom
            baseOrnamentMargins[1] = ui.attributionMarginBottom
        }
        ui.setLogoMargins(
            ui.logoMarginLeft, ui.logoMarginTop, ui.logoMarginRight,
            baseOrnamentMargins[0] + mapCoveredBelowPx + ornamentLiftPx,
        )
        ui.setAttributionMargins(
            ui.attributionMarginLeft, ui.attributionMarginTop, ui.attributionMarginRight,
            baseOrnamentMargins[1] + mapCoveredBelowPx + ornamentLiftPx,
        )
    }
    // MapLibre's native compass (a black disc in the map's top-right corner,
    // over the status bar) is replaced by our own compass button, drawn in the
    // toolbar column (MapCompassButton).
    LaunchedEffect(mapRef.value) {
        mapRef.value?.uiSettings?.isCompassEnabled = false
    }
    var mapBearing by remember { mutableFloatStateOf(0f) }
    DisposableEffect(mapRef.value) {
        val map = mapRef.value
        val listener = MapLibreMap.OnCameraMoveListener {
            mapBearing = map?.cameraPosition?.bearing?.toFloat() ?: 0f
        }
        if (map != null) {
            mapBearing = map.cameraPosition.bearing.toFloat()
            map.addOnCameraMoveListener(listener)
        }
        onDispose { map?.removeOnCameraMoveListener(listener) }
    }
    val searchSheetCoverPx = if (rideActive) {
        0
    } else {
        MapControlsLayout.coveredBottomPx(mapBoxBottomPx, searchSheetTopPx)
    }
    val searchSheetCoverDp = with(LocalDensity.current) { searchSheetCoverPx.toDp() }
    // Hardware volume buttons are invisible to the app; while the panel shows
    // the local music stream, poll the real AudioManager readback so the level
    // and its disabled limits follow the hardware. Remote sessions report
    // through their own onAudioInfoChanged callback instead.
    val localVolumeTargeted = mediaState.volume.target == VolumeTarget.PHONE
    LaunchedEffect(rideActive, mediaPanelOpen, localVolumeTargeted) {
        if (!rideActive || !mediaPanelOpen || !localVolumeTargeted) return@LaunchedEffect
        while (true) {
            delay(LOCAL_VOLUME_POLL_MILLIS)
            mediaController.pollLocalVolume()
        }
    }

    val selectRoute: (Int) -> Unit = coordinator::selectRoute
    val latestSelectRoute by rememberUpdatedState(selectRoute)
    val routeHitRadiusPx = with(LocalDensity.current) { 24.dp.toPx() }
    // Tapping the map or closing the keyboard leaves the From/To field, so a
    // field's "left" handling (keeping the current location) runs then too.
    val fieldFocusManager = LocalFocusManager.current
    val latestClearFieldFocus by rememberUpdatedState<() -> Unit>({ fieldFocusManager.clearFocus() })
    // Read the keyboard from the root view: the window's insets, not the
    // Compose ones (which this screen's own padding may already consume).
    val rootView = LocalView.current
    DisposableEffect(rootView) {
        var keyboardWasShown = false
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val shown = androidx.core.view.ViewCompat.getRootWindowInsets(rootView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            if (keyboardWasShown && !shown) latestClearFieldFocus()
            keyboardWasShown = shown
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { rootView.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }

    DisposableEffect(mapRef.value) {
        val map = mapRef.value ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnMapClickListener { point ->
            latestClearFieldFocus()
            val screenPoint = runCatching { map.projection.toScreenLocation(point) }.getOrNull()
            val current = coordinator.state.value
            val focusedIndex = (current as? RouteUiState.Success)?.selectedIndex
            val routeIndex = screenPoint?.let {
                map.findRouteIndexAt(it, routeHitRadiusPx, focusedIndex)
            }
            if (routeIndex != null && current is RouteUiState.Success &&
                routeIndex in current.result.routes.indices
            ) {
                latestSelectRoute(routeIndex)
                true
            } else {
                false
            }
        }
        map.addOnMapClickListener(listener)
        onDispose { map.removeOnMapClickListener(listener) }
    }
    // Planning only needs live location while the map is on screen. During
    // active guidance keep the existing stream alive when the activity stops
    // so background speech can continue within Android's location limits.
    // Keep background fixes out of Compose state; navigation and speech still
    // receive each fix, while the map and screen remain quiescent.
    var trackedFix by remember { mutableStateOf<GpsFix?>(null) }
    var trackingActive by remember { mutableStateOf(false) }
    val latestFix = remember { AtomicReference<GpsFix?>(null) }
    // Debug runner authority: while queued visual fixes are flowing, the live
    // GPS stream must not pull guidance back to the emulator's parked provider
    // position between injections.
    val lastDebugFixAt = remember { AtomicLong(0L) }
    // A visual runner simulating a GPS gap opts its fixes into estimation;
    // ordinary queued fixes keep the frame still between injections.
    val debugFixAllowsEstimate = remember { AtomicBoolean(false) }
    val latestGuidanceRequested = rememberUpdatedState(guidanceRequested)
    var initialLocationCentered by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    screenStarted = true
                    trackedFix = latestFix.get()
                }
                Lifecycle.Event.ON_STOP -> screenStarted = false
                Lifecycle.Event.ON_RESUME -> {
                    val granted = LocationPermission.isGranted(context)
                    locationPermissionGranted = granted
                    if (!granted) {
                        latestFix.set(null)
                        trackedFix = null
                        pendingGuidanceStart = false
                        guidanceRequested = false
                    }
                    // Notification access may have been granted or revoked
                    // while we were in Settings; re-read it immediately.
                    mediaController.onResume()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(locationPermissionGranted, guidanceRequested) {
        if (!guidanceRequested) lastDebugFixAt.set(0L)
        if (!locationPermissionGranted) {
            latestFix.set(null)
            trackedFix = null
            trackingActive = false
            initialLocationCentered = false
            return@LaunchedEffect
        }

        suspend fun collectFixes() {
            trackingActive = true
            var streamEnded = false
            try {
                GpsFixSource.fixes(
                    context.applicationContext,
                    rideActive = guidanceRequested,
                ).collect { fix ->
                    latestFix.set(fix)
                    val debugAgeMs = lastDebugFixAt.get().takeIf { it > 0L }?.let {
                        SystemClock.elapsedRealtime() - it
                    }
                    if (!debugFixOwnsGuidance(debugLogs, debugAgeMs)) {
                        navigationController.onFix(fix)
                    }
                    val snapshot = navigationController.snapshot.value
                    if (latestGuidanceRequested.value && snapshot.state != NavigationState.Idle) {
                        voiceGuidanceOutput.onSnapshot(
                            snapshot,
                            latestVoiceGuidanceSettings.value,
                        )
                    }
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        trackedFix = fix
                    }
                }
                streamEnded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                streamEnded = true
                Log.w(TAG, "Location fix stream ended", failure)
            } finally {
                // A guidance-mode effect replacement can briefly cancel this
                // collector; retain its last fix until the replacement starts.
                if (streamEnded || !latestGuidanceRequested.value) {
                    latestFix.set(null)
                    trackedFix = null
                    trackingActive = false
                }
            }
        }

        if (guidanceRequested) {
            collectFixes()
        } else {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                collectFixes()
            }
        }
    }
    LaunchedEffect(fromLocationState, locationPermissionGranted) {
        val waiting =
            fromLocationState.status as? CurrentLocationStatus.WaitingForFix ?: return@LaunchedEffect
        if (!locationPermissionGranted) {
            fromLocationState = fromLocationState.permissionLost(waiting.request.id)
            fromText = ""
            fromPoint = null
            return@LaunchedEffect
        }

        val fix = withTimeoutOrNull(CURRENT_LOCATION_FIX_TIMEOUT_MS) {
            snapshotFlow { trackedFix }.first { candidate ->
                fromLocationState.currentPoint(
                    waiting.request,
                    candidate,
                    SystemClock.elapsedRealtime(),
                ) != null
            }
        }
        if (fromLocationState.status != waiting) return@LaunchedEffect
        val endpoint = fromLocationState.currentEndpoint(
            waiting.request,
            fix,
            SystemClock.elapsedRealtime(),
        )
        if (endpoint == null) {
            fromLocationState = fromLocationState.noRecentFix(waiting.request.id)
            fromText = ""
            fromPoint = null
        } else {
            fromText = endpoint.label
            fromPoint = GHPoint(endpoint.point.lat, endpoint.point.lon)
            fromLocationState = fromLocationState.complete(waiting.request.id)
        }
    }
    // The debug visual runner uses an app-private queued fix to move the real
    // map rider deterministically in planning and NavigationSession in ride
    // mode. This seam is absent from release builds and avoids emulator GPS
    // provider throttling during camera checks and route jumps.
    LaunchedEffect(debugLogs) {
        if (!debugLogs) return@LaunchedEffect
        while (true) {
            val request = withContext(Dispatchers.IO) {
                VisualRouteSnapshot.takeQueuedFix(context.cacheDir)
            }
            if (request == null) {
                delay(40)
                continue
            }
            val fix = GpsFix(
                lat = request.lat,
                lon = request.lon,
                // A percentage jump is a test teleport, not a speed sample:
                // without a runner-requested speed it stays stationary so
                // pace-delta metrics stay truthful. The camera runner can
                // request a speed to capture a zoom band.
                speedMps = request.speedMps ?: 0.0,
                accuracyM = request.accuracyM,
                timestampMs = SystemClock.elapsedRealtime(),
            )
            latestFix.set(fix)
            val guidanceActive = latestGuidanceRequested.value
            if (guidanceActive) {
                lastDebugFixAt.set(SystemClock.elapsedRealtime())
                debugFixAllowsEstimate.set(request.allowEstimate)
                navigationController.onFix(fix.copy(timestampMs = request.timestampMs ?: fix.timestampMs),
                    nowMs = request.timestampMs ?: fix.timestampMs)
            }
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                trackedFix = fix
            }
            val snapshot = navigationController.snapshot.value
            if (guidanceActive && snapshot.state != NavigationState.Idle) {
                voiceGuidanceOutput.onSnapshot(snapshot, latestVoiceGuidanceSettings.value)
            }
            withContext(Dispatchers.IO) {
                VisualRouteSnapshot.acknowledgeFix(context.cacheDir, request.requestId, snapshot,
                    navigationController.suggestionCheckInFlight)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            latestFix.set(null)
            trackedFix = null
            trackingActive = false
        }
    }

    // The planner lock keeps the zoom the user selected. A new ride starts
    // with its own guidance camera lock, which keeps guidance framing.
    var followMe by remember { mutableStateOf(false) }
    var followZoom by remember { mutableStateOf(FOLLOW_ZOOM) }
    var guidanceCameraLocked by remember(guidanceRequested) { mutableStateOf(guidanceRequested) }
    var cameraFollowSuspension by remember { mutableStateOf(CameraFollowSuspension()) }

    var mapUrl by remember { mutableStateOf<String?>(null) }
    var mapFileChecked by remember { mutableStateOf(false) }
    var mapImporting by remember { mutableStateOf(false) }
    var mapImportError by remember { mutableStateOf<String?>(null) }
    var styleJson by remember { mutableStateOf<String?>(null) }
    val darkGuidanceStyle = guidanceRequested && darkRideMapEnabled
    // The active dataset owns the basemap: an installed region uses its own
    // PMTiles archive, the bundled fallback keeps the legacy "Load map file"
    // import. Re-resolving on dataset generation and map revision keeps the
    // URL honest after a switch or a replacement import.
    LaunchedEffect(activeDataset.installId, activeDataset.generation, mapRevision) {
        try {
            val resolvedUrl = activeDataset.tilesUrl
                ?: OfflineTileStore.installedUrl(context.applicationContext)
            withContext(Dispatchers.Main.immediate) { mapUrl = resolvedUrl }
        } catch (e: CancellationException) {
            // Leaving the composition cancels this effect; it is not a load
            // failure, and recording one would leave a stale modal error.
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main.immediate) {
                mapImportError = "The installed map file could not be opened"
            }
            Log.e(TAG, "Installed map failed to load", e)
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { mapFileChecked = true }
        }
    }
    LaunchedEffect(mapUrl, mapRevision, darkGuidanceStyle) {
        // Force a style reload when a replacement is imported at the same
        // private path; the URL string itself intentionally stays stable.
        withContext(Dispatchers.Main.immediate) { styleJson = null }
        val url = mapUrl ?: run {
            return@LaunchedEffect
        }
        try {
            val loadedJson = loadOfflineStyle(
                context.applicationContext,
                url,
                darkRideMode = darkGuidanceStyle,
            )
            // loadOfflineStyle reads the asset on Dispatchers.IO. Publish UI
            // state on Main so recomposition and the keyed native-style effect
            // are scheduled on the UI dispatcher after that context switch.
            withContext(Dispatchers.Main.immediate) { styleJson = loadedJson }
        } catch (e: CancellationException) {
            // A recomposition/recreation cancellation is not a style failure;
            // rethrowing keeps the map on its next successful load instead of
            // popping a false "Map file not loaded" dialog over it.
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main.immediate) {
                mapImportError = "The offline map style could not be loaded"
            }
            Log.e(TAG, "Offline map style failed to load", e)
        }
    }

    // One "Load map file" for both formats, decided by the file's own bytes.
    // A `.motomap` package (map + routing + search) is installed and activated;
    // a bare PMTiles basemap keeps the legacy behaviour of replacing only the
    // bundled dataset's picture — and is refused in a build with no bundled
    // routing data, where it would leave a map that cannot route or search.
    val mapPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            mapImporting = true
            mapImportError = null
            try {
                val kind = withContext(Dispatchers.IO) {
                    val input = context.applicationContext.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("The selected map file could not be opened")
                    MapFileKind.sniff(input)
                }
                when (kind) {
                    MapFileKind.PACKAGE -> regionManager.loadPackageAndActivate(uri)
                    MapFileKind.PMTILES -> {
                        if (!RegionDatasets.bundledAvailable) {
                            throw IllegalArgumentException(BASEMAP_ONLY_MESSAGE)
                        }
                        mapUrl = OfflineTileStore.import(context.applicationContext, uri)
                        mapRevision++
                        // The legacy basemap belongs to the bundled dataset; switch
                        // back to it so the import is actually visible.
                        regionManager.activateBundled()
                    }
                    MapFileKind.UNKNOWN -> throw IllegalArgumentException(UNKNOWN_MAP_FILE_MESSAGE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mapImportError = e.message ?: "Map file import failed"
                Log.e(TAG, "Map file import failed", e)
            } finally {
                mapImporting = false
                mapFileChecked = true
            }
        }
    }
    val chooseMapFile = {
        mapPicker.launch(arrayOf("application/octet-stream", "*/*"))
    }
    // The welcome carousel's map page can hand over "choose file" or "open Maps".
    LaunchedEffect(startupAction) {
        when (startupAction) {
            WelcomeExit.CHOOSE_MAP_FILE -> chooseMapFile()
            WelcomeExit.OPEN_MAPS -> mapsSettingsOpen = true
            WelcomeExit.NONE -> return@LaunchedEffect
        }
        onStartupActionHandled()
    }

    // Offline regional package import: the system picker supplies a `.motomap`
    // file; the manager copies, verifies, and installs it transactionally. The
    // user's file is left where it is.
    val mapsPackagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) regionManager.importPackage(uri)
    }

    // Downloads are saved as a user-visible file first: the system "Save to"
    // dialog picks the location (Downloads by default), then the resumable
    // transfer stages privately and copies into that file once it is complete.
    // The same picker also serves resuming an interrupted download and saving a
    // package recovered from the old private-only flow.
    var pendingMapsDownload by remember { mutableStateOf<MapsDownloadAction?>(null) }
    val mapsDownloadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val action = pendingMapsDownload
        pendingMapsDownload = null
        if (uri == null || action == null) return@rememberLauncherForActivityResult
        when (action) {
            is MapsDownloadAction.New -> regionManager.startDownload(action.regionId, uri)
            MapsDownloadAction.Resume -> regionManager.resumeDownload(uri)
            MapsDownloadAction.SaveRecovered -> regionManager.saveRecoveredPackage(uri)
        }
    }

    val mapView = rememberMapView(context) { map ->
        mapRef.value = map
    }

    var styledMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var appliedStyleJson by remember { mutableStateOf<String?>(null) }
    var appliedMapRevision by remember { mutableStateOf<Int?>(null) }
    var loadedStyleJson by remember { mutableStateOf<String?>(null) }
    var styleLoadStartedJson by remember { mutableStateOf<String?>(null) }
    var styleLoadError by remember { mutableStateOf("") }
    var styleLoadGeneration by remember { mutableIntStateOf(0) }
    // Debug-only, request-driven inspection. No production camera change and
    // no building queries per GPS update. The runner waits for actual rendered
    // basemap/rider buckets and installed route layers before capturing a
    // style transition. Pitched line queries alone are not reliable ink proof.
    LaunchedEffect(debugLogs, mapRef.value, loadedStyleJson, darkGuidanceStyle) {
        if (!debugLogs) return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        while (true) {
            val (camera, probeId) = withContext(Dispatchers.IO) {
                VisualRouteSnapshot.takeQueuedCamera(context.cacheDir) to
                    VisualRouteSnapshot.takeQueuedMapProbe(context.cacheDir)
            }
            camera?.let { map.moveCamera(CameraUpdateFactory.newCameraPosition(it.camera)) }
            if (probeId != null && loadedStyleJson != null) {
                val bounds = android.graphics.RectF(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat())
                val basemap = if (darkGuidanceStyle) {
                    DARK_RIDE_BASEMAP_PROBE_LAYERS.sumOf { layerId ->
                        map.queryRenderedFeatures(bounds, layerId).size
                    }
                } else {
                    map.queryRenderedFeatures(bounds, "building").size
                }
                val routeReady = map.style?.let { style ->
                    style.getSource("route") != null && style.getLayer("route-focus-line") != null
                } == true
                val rider = map.queryRenderedFeatures(bounds, "nav-position-dot").size
                withContext(Dispatchers.IO) {
                    VisualRouteSnapshot.writeMapProbe(context.cacheDir, probeId, darkGuidanceStyle, basemap, routeReady, rider)
                }
            }
            delay(40)
        }
    }
    // Debug-only native frame benchmark. The probe attaches to the live map
    // only in debug builds, and only while this screen owns it; the runner
    // animates host-queued camera sweeps with MapLibre's own easeCamera and
    // exports per-sweep frame JSON. Release builds never attach the listeners.
    // `screenStarted` and `loadedStyleJson` are keys so backgrounding the app
    // or reloading the style cancels an in-flight sweep and restores camera
    // and paint instead of leaving a half-driven camera behind.
    LaunchedEffect(debugLogs, mapView, mapRef.value, screenStarted, loadedStyleJson) {
        if (!debugLogs || !screenStarted) return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        val probe = MapPerformanceProbe(
            mapView = mapView,
            map = map,
            window = context.findActivity()?.window,
            log = { Log.d(TAG_MAP_PERF, it) },
        )
        probe.attach()
        val runner = MapPerfSweepRunner(
            mapView = mapView,
            map = map,
            probe = probe,
            cacheDir = context.cacheDir,
            log = { Log.d(TAG_MAP_PERF, it) },
        )
        try {
            while (true) {
                // A queued sweep must wait for a live style: animating the
                // camera before the style exists would measure nothing and
                // could be cancelled by the style load itself.
                if (map.style == null) {
                    delay(100)
                    continue
                }
                val request = withContext(Dispatchers.IO) {
                    MapPerfSweepQueue.take(context.cacheDir)
                }
                if (request != null) {
                    try {
                        runner.run(request)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.e(TAG_MAP_PERF, "sweep '${request.label}' failed", error)
                    }
                }
                delay(40)
            }
        } finally {
            // Leaving the screen cancels the sweep: the runner restores the
            // camera and building paint, and the gate always comes back down.
            probe.detach()
            MapPerfProbeGate.exit()
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow {
            NativeStyleLoadRequest(
                json = styleJson,
                map = mapRef.value,
                screenStarted = screenStarted,
                revision = mapRevision,
                darkRideStyle = guidanceRequested && darkRideMapEnabled,
            )
        }.collectLatest { request ->
            withContext(Dispatchers.Main.immediate) {
                if (debugLogs) {
                    Log.d(
                        TAG,
                        "Map style request observed json=${request.json != null} " +
                            "map=${request.map != null} screenStarted=${request.screenStarted} " +
                            "revision=${request.revision}",
                    )
                }
                if (!request.screenStarted) {
                    if (debugLogs) {
                        Log.d(TAG, "Map style load deferred: screenStarted=false revision=${request.revision}")
                    }
                    return@withContext
                }
                val json = request.json ?: run {
                    if (debugLogs) Log.d(TAG, "Map style load deferred: style JSON is unavailable")
                    return@withContext
                }
                val map = request.map ?: run {
                    if (debugLogs) Log.d(TAG, "Map style load deferred: native map is unavailable")
                    return@withContext
                }
                if (styledMap === map && appliedStyleJson == json && appliedMapRevision == request.revision) {
                    if (debugLogs) {
                        Log.d(
                            TAG,
                            "Map style load skipped: request already applied revision=${request.revision} " +
                                "generation=$styleLoadGeneration loaded=${loadedStyleJson == json}",
                        )
                    }
                    return@withContext
                }
                loadedStyleJson = null
                styleLoadStartedJson = null
                styleLoadError = ""
                // Check the live style state as well as the generation: a callback
                // can arrive after the desired style is cleared, before a new request.
                val generation = ++styleLoadGeneration
                if (debugLogs) {
                    Log.i(
                        TAG,
                        "Map style load start generation=$generation revision=${request.revision} " +
                            "screenStarted=${request.screenStarted} mapCurrent=${mapRef.value === map} " +
                            "sameMap=${styledMap === map} " +
                            "style=${if (request.darkRideStyle) "ride-dark" else "color"}",
                    )
                }
                try {
                    if (styledMap !== map) {
                        // Set the initial view once for each MapLibreMap: the
                        // rider's last-known position at the planner zoom, so
                        // the app opens on them at once, else the Queensland
                        // overview. The first live fix still recentres. Style
                        // changes on the same map retain the live camera.
                        val seed = GpsFixSource.lastKnownCameraSeed(context)
                        val opening = if (seed != null) {
                            CameraUpdateFactory.newLatLngZoom(LatLng(seed.lat, seed.lon), FOLLOW_ZOOM)
                        } else {
                            CameraUpdateFactory.newLatLngZoom(LatLng(-23.0, 145.5), 4.5)
                        }
                        map.moveCamera(opening)
                        if (seed != null) Log.i(TAG, "Initial map viewport opened on the last-known location")
                    }
                    map.setStyle(Style.Builder().fromJson(json)) {
                        val stillCurrent = mapRef.value === map &&
                            styleLoadGeneration == generation && styleJson == json
                        if (stillCurrent) {
                            loadedStyleJson = json
                            styleLoadError = ""
                            if (debugLogs) {
                                Log.i(
                                    TAG,
                                    "Map style load success generation=$generation revision=${request.revision} " +
                                        "screenStarted=${request.screenStarted} mapCurrent=${mapRef.value === map}",
                                )
                            }
                        } else if (debugLogs) {
                            Log.w(
                                TAG,
                                "Map style callback ignored generation=$generation currentGeneration=$styleLoadGeneration " +
                                    "screenStarted=${request.screenStarted} mapCurrent=${mapRef.value === map} " +
                                    "requestMatches=${styleJson == json}",
                            )
                        }
                    }
                    styleLoadStartedJson = json
                    styledMap = map
                    appliedStyleJson = json
                    appliedMapRevision = request.revision
                } catch (failure: Exception) {
                    styleLoadError = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
                    if (debugLogs) {
                        Log.e(TAG, "Map style load request failed generation=$generation revision=${request.revision}", failure)
                    }
                    throw failure
                }
            }
        }
    }

    val routeResult = (state as? RouteUiState.Success)?.result
    val selectedIndex = (state as? RouteUiState.Success)?.selectedIndex ?: 0
    val routeCount = routeResult?.routes?.size ?: 0
    LaunchedEffect(routeResult, selectedIndex, debugLogs) {
        if (!debugLogs) return@LaunchedEffect
        val paths = routeResult?.routes
        if (paths == null) {
            withContext(Dispatchers.IO) {
                VisualRouteSnapshot.clear(context.cacheDir)
            }
        } else {
            withContext(Dispatchers.IO) {
                VisualRouteSnapshot.write(context.cacheDir, paths, selectedIndex)
            }
        }
    }
    LaunchedEffect(guidanceRequested, mapRef.value) {
        val map = mapRef.value
        if (guidanceRequested && !guidanceWasActive && map != null) {
            planningCameraBeforeGuidance = map.cameraPosition
            guidanceBearingTracker.reset()
            guidanceWasActive = true
        } else if (!guidanceRequested && guidanceWasActive) {
            guidanceWasActive = false
            guidanceBearingTracker.reset()
            planningCameraBeforeGuidance?.let { camera ->
                map?.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 500)
            }
            planningCameraBeforeGuidance = null
        }
    }
    LaunchedEffect(guidanceRequested, routeResult, selectedIndex, voiceGuidanceEnabled) {
        if (guidanceRequested && voiceGuidanceEnabled &&
            routeResult?.routes?.getOrNull(selectedIndex) != null
        ) {
            voiceGuidanceOutput.ensureAvailability()
        }
    }
    val routeStateName = when (state) {
        RouteUiState.Idle -> "idle"
        RouteUiState.Loading -> "loading"
        is RouteUiState.Success -> "success"
        is RouteUiState.Error -> "error"
    }

    val routeGeometryCache = remember { RouteGeometryCache() }
    val routeGhostGeometryCache = remember { RouteGhostGeometryCache() }
    // The routes the planner actually displayed, newest first, so the map can
    // keep the previous two as grey ghosts behind the current one. The history
    // follows the plan (endpoints, shaping points, road toggles), never the
    // ride-style dial: a slider move is the same plan, so the stops the rider
    // passed through stay visible, and a re-render records nothing.
    val routeGhostHistory = remember { RouteGhostHistory<RidePlanKey>() }
    val routeLoading = state is RouteUiState.Loading
    var fittedRouteSet by remember { mutableStateOf<List<ResponsePath>?>(null) }
    var fittedMap by remember { mutableStateOf<MapLibreMap?>(null) }
    DisposableEffect(routeGeometryCache, routeGhostGeometryCache) {
        onDispose {
            routeGeometryCache.invalidatePendingDraws()
            routeGhostGeometryCache.invalidate()
        }
    }
    LaunchedEffect(
        routeResult,
        selectedIndex,
        styleJson,
        loadedStyleJson,
        darkGuidanceStyle,
        mapRef.value,
        screenStarted,
        activePlan,
        routeLoading,
        guidanceRequested,
    ) {
        val map = mapRef.value ?: return@LaunchedEffect
        val planKey = activePlan?.let(RidePlanKey::of)
        // A slider move onto a stop that has to be searched re-submits the same
        // plan; while it searches, keep the route and its ghosts on the map,
        // exactly like the card keeps the last shown route. Retaining draws
        // nothing, so it must not bump the draw generation: a draw of that very
        // route still pending must land.
        val retaining = routeResult == null &&
            routeLoading &&
            planKey != null &&
            fittedRouteSet != null &&
            routeGhostHistory.currentPlan() == planKey
        if (retaining) return@LaunchedEffect
        val generation = routeGeometryCache.beginDraw()
        if (!screenStarted) return@LaunchedEffect
        val requestIsCurrent = { routeGeometryCache.isCurrent(generation) }
        if (routeResult == null) {
            routeGhostHistory.clear()
            map.clearRouteGhosts()
            routeGeometryCache.clearRouteData()
            map.clearRoutes(requestIsCurrent)
            fittedRouteSet = null
            fittedMap = null
            return@LaunchedEffect
        }
        if (loadedStyleJson == null || loadedStyleJson != styleJson) return@LaunchedEffect
        // Riding shows the one route in the HUD: no planner ghosts under it,
        // and none come back when the rider returns to planning (clear()).
        if (guidanceRequested) {
            routeGhostHistory.clear()
        } else {
            routeGhostHistory.onDisplayed(planKey, routeResult.routes.getOrNull(selectedIndex))
        }
        Log.d(TAG, "Drawing ${routeResult.routes.size} route(s)")
        map.drawRoutes(
            routeResult.routes,
            selectedIndex,
            routeGeometryCache,
            requestIsCurrent,
            darkGuidanceMode = darkGuidanceStyle,
        )
        map.drawRouteGhosts(routeGhostHistory.ghosts(), routeGhostGeometryCache, requestIsCurrent)
        val needsInitialRouteFit = fittedRouteSet !== routeResult.routes || fittedMap !== map
        if (needsInitialRouteFit && !guidanceRequested) {
            map.fitBounds(routeResult.routes, bottomInsetPx = mapCoveredBelowPx, rightInsetPx = controlsInsetPx, topInsetPx = fitTopInsetPx)
            // The route now owns the camera until the rider taps Centre on me:
            // release the planner lock and spend the first-fix centring.
            followMe = PlannerCameraPolicy.followMeAfterRouteFit(followMe, routeFitted = true)
            cameraFollowSuspension = CameraFollowSuspension()
            initialLocationCentered = true
            Log.d(TAG, "Route drawn and camera fitted")
        }
        fittedRouteSet = routeResult.routes
        fittedMap = map
    }

    // Pending scenic diversion: draw only its diverging stretch under the
    // route being followed; cleared once it is taken, skipped or expired.
    LaunchedEffect(rideSuggestion, mapRef.value, loadedStyleJson, darkGuidanceStyle, screenStarted) {
        val map = mapRef.value ?: return@LaunchedEffect
        if (!screenStarted || loadedStyleJson == null) return@LaunchedEffect
        val proposal = (rideSuggestion as? RideSuggestionState.Pending)?.proposal
        if (proposal == null) {
            map.clearSuggestionPreview()
            return@LaunchedEffect
        }
        val track = proposal.replacement
        val from = proposal.span.divergeVertex
        val to = proposal.span.rejoinVertex
        map.drawSuggestionPreview(
            track.latitudes.copyOfRange(from, to + 1),
            track.longitudes.copyOfRange(from, to + 1),
            darkGuidanceMode = darkGuidanceStyle,
        )
    }

    // The ride-style stop the shown (or in-flight) route answers. It keeps a
    // release from submitting the stop the drag already asked for; every
    // submission sets it, so plans submitted elsewhere (START, saved/GPX
    // loads, road-toggle applies) cannot leave a stale stop deduplicated.
    var requestedStyleLevel by remember { mutableStateOf<Float?>(null) }

    /** Submits one route request through the generation-safe coordinator. */
    fun submitRoute(
        from: GHPoint,
        to: GHPoint,
        complexityValue: Float,
        blockUnpavedRoads: Boolean = blockUnpaved,
        avoidMotorwayRoads: Boolean = avoidMotorways,
        preferredGeometry: String? = null,
        viaPoints: List<GHPoint> = emptyList(),
        factors: RideFactors = rideFactors,
        roundTrip: RoundTripLength? = null,
        passThrough: Boolean = false,
        viaHeadings: List<Double> = emptyList(),
        followTrack: GpxRidePlan? = null,
        startHeading: Double = Double.NaN,
    ) {
        val params = RouteParams(
            from = from,
            to = to,
            complexity = complexityValue.toDouble(),
            blockUnpaved = blockUnpavedRoads,
            avoidMotorways = avoidMotorwayRoads,
            preferredGeometry = preferredGeometry,
            viaPoints = viaPoints.toList(),
            factors = factors,
            roundTrip = roundTrip,
            passThrough = passThrough,
            viaHeadings = viaHeadings,
            followTrack = followTrack,
            startHeading = startHeading,
        )
        activePlan = params
        requestedStyleLevel = RideStyle.fromLevel(complexityValue.toDouble()).level.toFloat()
        Log.i(TAG, "Route submitted (complexity $complexityValue)")
        if (debugLogs) {
            Log.i(TAG, "Route submitted: from=\"$fromText\" to=\"$toText\"")
        } else {
            Log.i(TAG, "Route submitted")
        }
        coordinator.submit(params)
    }

    /**
     * Routes the imported GPX ride. A detailed track is followed road for
     * road ([GpxTrackFollow]: no ride style applies); a list of stops is an
     * ordinary plan through them, so the slider's seven rides apply between
     * the stops. START calls this again while the import is open, so going
     * back to the fields or rotating the phone never drops the ride's shape.
     */
    fun submitImportedGpx(ride: ImportedGpx) {
        cancelFromLocationRequest()
        val plan = ride.plan
        // The imported ride says where it ends.
        roundTripEnabled = false
        fromPoint = GHPoint(plan.line.first().lat, plan.line.first().lon)
        toPoint = GHPoint(plan.line.last().lat, plan.line.last().lon)
        when (plan.mode) {
            GpxRideMode.FOLLOW_TRACK -> {
                val vias = GpxTrackFollow.shapingPoints(plan.line, plan.stops).drop(1).dropLast(1)
                submitRoute(
                    fromPoint!!,
                    toPoint!!,
                    complexity,
                    blockUnpaved,
                    avoidMotorways,
                    viaPoints = vias.map { GHPoint(it.point.lat, it.point.lon) },
                    viaHeadings = vias.map { it.headingDeg },
                    followTrack = plan,
                )
            }
            GpxRideMode.STOPS -> {
                val stops = GpxTrackFollow.stopPoints(plan)
                submitRoute(
                    GHPoint(stops.first().lat, stops.first().lon),
                    GHPoint(stops.last().lat, stops.last().lon),
                    complexity,
                    blockUnpaved,
                    avoidMotorways,
                    viaPoints = stops.drop(1).dropLast(1).map { GHPoint(it.lat, it.lon) },
                )
            }
        }
    }

    /**
     * Switches the shown ride style to [level]'s stop, returning whether a
     * request went out. A stop whose search has already finished is installed
     * straight from the finished plan (no loading flash, no new search); a
     * stop still being searched joins its plan through the normal coordinator
     * submission, so generations, cancellation, guidance and GPX shaping
     * points keep their rules. The slider calls this as it crosses each stop
     * and on release; a release on the stop the drag already asked for is a
     * no-op.
     */
    fun requestRideStyle(level: Float): Boolean {
        val plan = activePlan ?: return false
        val stop = RideStyle.fromLevel(level.toDouble())
        val stopLevel = stop.level.toFloat()
        if (stopLevel == requestedStyleLevel) return false
        requestedStyleLevel = stopLevel
        val params = RouteParams(
            from = plan.from,
            to = plan.to,
            complexity = stopLevel.toDouble(),
            blockUnpaved = blockUnpaved,
            avoidMotorways = avoidMotorways,
            // Keep imported GPX shaping points on every dial edit so the ride
            // style changes the ride along the imported plan.
            viaPoints = plan.viaPoints.toList(),
            viaHeadings = plan.viaHeadings,
            factors = rideFactors,
            // A round trip slides between its own loops.
            roundTrip = plan.roundTrip,
            passThrough = plan.passThrough,
        )
        activePlan = params
        val ready = styleOptions.readyOption(params)
        if (ready != null) {
            Log.i(TAG, "Ride style ${stop.label} answered from the finished plan")
            coordinator.install(ready)
        } else {
            Log.i(TAG, "Route submitted (complexity $stopLevel)")
            coordinator.submit(params)
        }
        return true
    }

    val onRoute: () -> Unit = onRoute@{
        // Slide straight over to the ride styles: the wave grows there as
        // each style's search finishes. A failed search slides back.
        plannerStage = PlannerStage.Styles
        // An open GPX import is the plan: its fields only name its ends.
        importedGpx?.let { ride ->
            submitImportedGpx(ride)
            return@onRoute
        }
        val gen = coordinator.generation
        // Snapshot the typed fields; if they change while resolution runs
        // offline, this attempt is stale and must not route or overwrite.
        val fromSnapshot = fromText
        val toSnapshot = toText
        // The setting, not its length: a back-by time's length moves with the clock.
        val roundTripSnapshot = roundTripWish.takeIf { roundTripEnabled }
        scope.launch {
            // Resolve the endpoints eagerly so a failure (no match / bad
            // input) surfaces as the usual Error state instead of crashing.
            val resolvedFrom: GHPoint
            val resolvedTo: GHPoint
            try {
                resolvedFrom = withContext(Dispatchers.IO) {
                    fromPoint ?: resolvePoint(fromText, geocodeController, "From", debugLogs)
                }
                // A round trip comes back to From: there is no To to find.
                resolvedTo = if (roundTripSnapshot != null) {
                    resolvedFrom
                } else {
                    withContext(Dispatchers.IO) {
                        toPoint ?: resolvePoint(toText, geocodeController, "To", debugLogs)
                    }
                }
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation (scope/screen gone)
            } catch (e: Exception) {
                // The throwable chain can embed user-typed text ("No match for
                // \"...\""), so log the full chain only in debug builds.
                if (debugLogs) {
                    Log.e(TAG, "Point resolution failed", e)
                } else {
                    Log.e(TAG, "Point resolution failed")
                }
                coordinator.publishError(gen, e.message ?: "Routing failed")
                // A repeat of the same error is not a new state, so slide
                // back here rather than waiting for the state to change.
                if (gen == coordinator.generation) plannerStage = PlannerStage.Endpoints
                return@launch
            }
            val roundTripNow = roundTripWish.takeIf { roundTripEnabled }
            if (fromText != fromSnapshot || toText != toSnapshot || roundTripNow != roundTripSnapshot || !isActive) {
                plannerStage = PlannerStage.Endpoints
                return@launch
            }
            if (roundTripSnapshot != null) {
                submitRoute(
                    resolvedFrom,
                    resolvedFrom,
                    complexity,
                    blockUnpaved,
                    avoidMotorways,
                    roundTrip = roundTripSnapshot.length(System.currentTimeMillis()),
                )
                return@launch
            }
            // Identical endpoints make GraphHopper return a zero-edge path
            // with a single geometry point, which cannot draw or fit as a
            // line; reject it up front.
            if (samePlace(resolvedFrom, resolvedTo)) {
                coordinator.publishError(gen, "From and To are the same place")
                if (gen == coordinator.generation) plannerStage = PlannerStage.Endpoints
                return@launch
            }
            submitRoute(resolvedFrom, resolvedTo, complexity, blockUnpaved, avoidMotorways)
        }
    }

    // Back to the From/To fields. The route stays on the map; START answers
    // from the finished plan, so going back and forth costs nothing. A slider
    // move still in flight is dropped so it cannot pop the styles back in.
    val backToEndpoints: () -> Unit = {
        if (state is RouteUiState.Loading) coordinator.invalidate()
        plannerStage = PlannerStage.Endpoints
        Log.i(TAG, "Planner back to From and To")
    }

    /**
     * Persists one proposed route card together with the live planning state.
     * Suspending and honest: returns whether the row actually landed in the
     * database, so the save bubble can never confirm a save that stored
     * nothing. The endpoints come from [activePlan] (the current submission),
     * never from a stale earlier route.
     */
    suspend fun saveProposedRoute(path: ResponsePath): Boolean {
        val plan = activePlan
        if (plan == null) {
            Log.w(TAG_SAVED, "Save ignored: no resolved endpoints for the current route")
            return false
        }
        return try {
            val saved = savedRouteRepository.save(
                SavedRouteDraft(
                    fromName = fromText.trim()
                        .ifBlank { "${plan.from.lat}, ${plan.from.lon}" },
                    from = GeoPoint(plan.from.lat, plan.from.lon),
                    toName = if (plan.roundTrip != null || roundTripEnabled) {
                        ROUND_TRIP_TO_NAME
                    } else {
                        toText.trim().ifBlank { "${plan.to.lat}, ${plan.to.lon}" }
                    },
                    to = GeoPoint(plan.to.lat, plan.to.lon),
                    distanceMeters = path.distance,
                    durationMillis = path.time,
                    complexity = complexity,
                    blockUnpaved = blockUnpaved,
                    avoidMotorways = avoidMotorways,
                    points = path.points.toGeoPoints(),
                    // The factors the shown route was planned with.
                    includeElevation = plan.factors.elevation,
                    avoidStopsAndTowns = plan.factors.stopsAndTowns,
                )
            )
            Log.i(TAG_SAVED, "Saved route ${saved.id} (${saved.distanceMeters} m)")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG_SAVED, "Saving route failed", e)
            false
        }
    }

    /**
     * Restores a saved route into the editable planning state and routes it.
     * The loaded route behaves like a fresh plan: every control reflects the
     * stored value and can be changed to steer the route differently.
     */
    fun loadSavedRoute(saved: SavedRoute) {
        cancelFromLocationRequest()
        savedRoutesOpen = false
        clearImportedGpx()
        val (from, to) = saved.endpoints
        val restoredFrom = GHPoint(from.lat, from.lon)
        val restoredTo = GHPoint(to.lat, to.lon)
        fromText = saved.fromName
        fromPoint = restoredFrom
        // A ride that ends where it started is a round trip: the planner says
        // so (and START plans new loops of about the same length from here)
        // instead of a To field that repeats From.
        roundTripEnabled = samePlace(restoredFrom, restoredTo)
        if (roundTripEnabled) {
            roundTripWish = roundTripWish.withRide(saved.durationMillis, saved.distanceMeters, System.currentTimeMillis())
            toText = ""
            toPoint = null
        } else {
            toText = saved.toName
            toPoint = restoredTo
        }
        // Older saves carry unbounded complexity levels; clamp onto a stop.
        complexity = RideStyle.fromLevel(saved.complexity).level.toFloat()
        blockUnpaved = saved.blockUnpaved
        avoidMotorways = saved.avoidMotorways
        // Like the road toggles, the saved factors steer this ride without
        // rewriting the persisted Route settings preferences.
        includeElevation = saved.includeElevation
        avoidStopsAndTowns = saved.avoidStopsAndTowns
        val savedFactors = RideFactors(saved.includeElevation, saved.avoidStopsAndTowns)
        val savedViaPoints = savedRouteViaPoints(saved)
        Log.i(TAG_SAVED, "Loading saved route ${saved.id}")
        submitRoute(
            restoredFrom,
            restoredTo,
            complexity,
            blockUnpaved,
            avoidMotorways,
            preferredGeometry = saved.geometry,
            viaPoints = savedViaPoints,
            factors = savedFactors,
        )
    }

    // A ride restored after rotation or process death: its file comes back,
    // the planner shows its rows, and START routes it again.
    LaunchedEffect(Unit) {
        if (!importedGpxOpen || importedGpx != null) return@LaunchedEffect
        val restored = withContext(importedGpxStoreDispatcher) { importedGpxStore.load() }
        if (!importedGpxOpen) return@LaunchedEffect
        if (restored == null) {
            importedGpxOpen = false
            return@LaunchedEffect
        }
        importedGpx = restored
        fromPoint = GHPoint(restored.plan.line.first().lat, restored.plan.line.first().lon)
        toPoint = GHPoint(restored.plan.line.last().lat, restored.plan.line.last().lon)
        Log.i(TAG, "Imported GPX ride restored")
    }

    // GPX import: parse off the UI thread, decide whether the file is a track
    // to follow or a list of stops, and route it (see submitImportedGpx).
    // Milestone place names fill in once routing has started.
    var gpxImportError by remember { mutableStateOf<String?>(null) }
    fun importGpx(uri: Uri) {
        gpxImportJob?.cancel()
        gpxImportJob = scope.launch {
            try {
                val gpx = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { GpxParser.parse(it) }
                } ?: throw GpxParser.GpxParseException(GpxParser.CANNOT_OPEN_MESSAGE)
                val (plan, milestones) = withContext(Dispatchers.Default) {
                    GpxTrackFollow.plan(gpx) to GpxPreview.milestones(gpx.points)
                }
                val name = gpx.name ?: "Imported GPX"
                Log.i(
                    TAG,
                    "GPX imported: ${plan.mode}, ${gpx.points.size} ${gpx.source} points " +
                        "(${gpx.skippedPoints} invalid skipped), ${GpxGeometry.lengthMeters(gpx.points).roundToInt()} m, " +
                        "line ${plan.line.size} points, ${plan.stops.size} stops, " +
                        "${plan.ignoredWaypoints} waypoints off the ride ignored",
                )
                gpxImportError = null
                gpxPreviewOpen = false
                var ride = ImportedGpx(name, plan, milestones)
                importedGpx = ride
                importedGpxOpen = true
                fromText = coordinateLabel(plan.line.first())
                toText = coordinateLabel(plan.line.last())
                withContext(importedGpxStoreDispatcher) { importedGpxStore.save(ride) }
                submitImportedGpx(ride)
                // Resolve every preview stop in one offline index scan. Routing
                // starts immediately; labels fill in independently when ready.
                val places = try {
                    geocodeController.reverseGeocode(
                        milestones.map { it.point.lat to it.point.lon },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A missing/corrupt search asset must not undo a route that
                    // already imported successfully. Coordinate labels remain
                    // an honest offline fallback.
                    Log.w(TAG, "GPX milestone reverse geocoding unavailable", e)
                    emptyList()
                }
                val located = milestones.mapIndexed { index, milestone ->
                    val place = places.getOrNull(index)
                    milestone.copy(placeName = place?.name, placeDetail = place?.detail)
                }
                if (importedGpx !== ride) return@launch
                ride = ride.copy(milestones = located)
                importedGpx = ride
                located.firstOrNull()?.let { fromText = milestoneEndpointLabel(it) }
                located.lastOrNull()?.let { toText = milestoneEndpointLabel(it) }
                withContext(importedGpxStoreDispatcher) { importedGpxStore.save(ride) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GpxParser.GpxParseException) {
                Log.e(TAG, "GPX import failed: ${e.message}", e)
                gpxImportError = e.message ?: GpxParser.DAMAGED_MESSAGE
            } catch (e: Exception) {
                // A revoked grant, a vanished file or a full disk: the
                // platform's wording is for the log, not the rider.
                Log.e(TAG, "GPX import failed", e)
                gpxImportError = GpxParser.CANNOT_OPEN_MESSAGE
            }
        }
    }
    val gpxPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importGpx(uri)
    }
    // A GPX file opened or shared from another app (Files, mail, a chat)
    // imports like a picked one. A ride in progress keeps its route: the file
    // waits until the ride ends.
    LaunchedEffect(incomingGpx, guidanceRequested) {
        val uri = incomingGpx ?: return@LaunchedEffect
        if (guidanceRequested) return@LaunchedEffect
        Log.i(TAG, "Opening a GPX file from another app")
        importGpx(uri)
        onIncomingGpxHandled()
    }


    // Guidance rebuild: reroute from the engine's last good position using
    // the SAME coordinator as planning, so generation/cancellation rules hold.
    val latestSubmitRoute by rememberUpdatedState(::submitRoute)
    // The loop corners of the round trip being ridden: a rebuild keeps riding
    // them and ride suggestions wait until they are passed, exactly like
    // imported shaping points (a suggestion routed straight to the finish
    // would cut the loop short).
    val latestFollowedPath = rememberUpdatedState(routeResult?.routes?.getOrNull(selectedIndex))
    // A followed GPX track's shaping points are the ones its last round
    // added, which only the shown path knows (like a round trip's corners).
    fun shapingPointsOf(plan: RouteParams): List<GHPoint> =
        if (plan.roundTrip != null || plan.followTrack != null) {
            latestFollowedPath.value?.let(RoundTrips::viaPointsOf).orEmpty()
        } else {
            plan.viaPoints
        }
    fun shapingHeadingsOf(plan: RouteParams, vias: List<GHPoint>): List<Double> = when {
        plan.followTrack != null -> latestFollowedPath.value?.let { path ->
            GpxTrackFollow.headingsAlong(
                path.points.toGeoPoints(),
                vias.map { GeoPoint(it.lat, it.lon) },
            )
        }.orEmpty()
        plan.viaHeadings.size == plan.viaPoints.size -> plan.viaHeadings
        else -> emptyList()
    }
    remember(navigationController) {
        navigationController.setEstimationHandlers(
            allowed = {
                val debugAgeMs = lastDebugFixAt.get().takeIf { it > 0L }?.let {
                    SystemClock.elapsedRealtime() - it
                }
                !debugFixOwnsGuidance(debugLogs, debugAgeMs) || debugFixAllowsEstimate.get()
            },
            onEstimated = { snapshot ->
                // Turns keep being announced through a tunnel; the estimate
                // is the best distance-to-turn there is.
                if (latestGuidanceRequested.value) {
                    voiceGuidanceOutput.onSnapshot(snapshot, latestVoiceGuidanceSettings.value)
                }
            },
        )
        navigationController.setRebuildHandler { request: RebuildRequest ->
            val plan = activePlan
            if (plan == null) {
                Log.w(TAG, "Rebuild skipped: no active plan")
                return@setRebuildHandler
            }
            // The rider's own fix, leaving in the rider's direction.
            val rebuildFrom = GHPoint(request.lat, request.lon)
            // Route from the rider to the ORIGINAL destination,
            // through any shaping points still ahead (GPX legs, an accepted
            // scenic diversion) so a stray never silently discards them.
            val shaping = shapingPointsOf(plan)
            val headings = shapingHeadingsOf(plan, shaping)
            val remaining = navigationController.remainingViaIndices(shaping)
            // A followed GPX track (or a rebuild of one, which keeps its
            // headings) rides Fastest between its dense shaping points: the
            // file chose the roads, not a ride style.
            val followedRide = plan.followTrack != null || plan.viaHeadings.isNotEmpty()
            latestSubmitRoute(
                rebuildFrom,
                plan.to,
                if (followedRide) RideStyle.FASTEST.level.toFloat() else complexity,
                blockUnpaved,
                avoidMotorways,
                null,
                remaining.map { shaping[it] },
                plan.factors,
                // The rebuild is an ordinary plan through the loop's corners.
                null,
                plan.passThrough || plan.roundTrip != null,
                if (headings.size == shaping.size) remaining.map { headings[it] } else emptyList(),
                null,
                request.headingDeg,
            )
        }
        navigationController.setSuggestionHandlers(
            search = search@{ request ->
                val plan = activePlan ?: return@search null
                rideSuggestionSearch.search(
                    latestRouter.value, request, plan.to, blockUnpaved, avoidMotorways, plan.factors,
                )
            },
            inputs = {
                val plan = activePlan
                RideSuggestionInputs(
                    enabled = rideSuggestionsEnabled && plan != null,
                    style = RideStyle.fromLevel(complexity),
                    battery = batteryStateCache.get(SystemClock.elapsedRealtime()),
                    unit = voiceUnit,
                    shapingPointsAhead = plan != null &&
                        navigationController.remainingViaPoints(shapingPointsOf(plan)).isNotEmpty(),
                )
            },
            speak = { text -> voiceGuidanceOutput.announce(text, latestVoiceGuidanceSettings.value) },
            onAccepted = { proposal ->
                val plan = activePlan
                val path = proposal.path
                if (plan != null && path != null) {
                    // The session already follows the replacement; show it as
                    // the plan, and keep its shaping points so a later
                    // rebuild stays on the scenic road.
                    activePlan = plan.copy(
                        from = GHPoint(path.points.getLat(0), path.points.getLon(0)),
                        viaPoints = proposal.viaPoints.map { (lat, lon) -> GHPoint(lat, lon) },
                        preferredGeometry = null,
                        // The rest of a round trip is now this ride home.
                        roundTrip = null,
                    )
                    coordinator.install(RouteResult(listOf(path)))
                }
            },
        )
    }

    // Riding never computes the other ride styles: rebuilds are one-off plans.
    LaunchedEffect(styleOptions, guidanceRequested) {
        styleOptions.enabled = !guidanceRequested
    }

    // Start guidance once a route exists and permission is granted; stop when
    // the plan is invalidated or the route disappears.
    LaunchedEffect(routeResult, selectedIndex, guidanceRequested, locationPermissionGranted) {
        if (!guidanceRequested || !locationPermissionGranted) {
            navigationController.stop()
            voiceGuidanceOutput.reset()
            return@LaunchedEffect
        }
        val path = routeResult?.routes?.getOrNull(selectedIndex)
        if (path != null) {
            navigationController.start(path)
        }
    }
    LaunchedEffect(state, navSnapshot.state, guidanceRequested) {
        if (guidanceRequested && state is RouteUiState.Error &&
            navSnapshot.state == NavigationState.Rebuilding
        ) {
            Log.w(TAG, "Guidance rebuild failed; returning to the planner")
            guidanceRequested = false
        }
    }
    DisposableEffect(Unit) {
        onDispose { navigationController.stop() }
    }

    // Follow the rider in planning mode only when the planner lock is on.
    // Guidance has its own lock and keeps its speed-based zoom and framing.
    LaunchedEffect(
        trackedFix,
        followMe,
        followZoom,
        guidanceRequested,
        screenStarted,
        cameraFollowSuspension,
    ) {
        withContext(Dispatchers.Main.immediate) {
            if (!screenStarted) return@withContext
            if (!followMe || guidanceRequested) return@withContext
            if (cameraFollowSuspension.isSuspended(SystemClock.elapsedRealtime())) {
                return@withContext
            }
            val map = mapRef.value ?: return@withContext
            val fix = trackedFix ?: return@withContext
            if (fix.lat.isNaN() || fix.lon.isNaN()) return@withContext
            val camera = map.cameraPosition
            val cameraTarget = camera.target ?: return@withContext
            val needsPositionOrZoom = shouldFollowCamera(
                cameraTarget.latitude,
                cameraTarget.longitude,
                camera.zoom,
                fix.lat,
                fix.lon,
                followZoom,
                followMovementThresholdMeters(fix.speedMps),
            )
            val needsOrientation = planningOrientationNeedsUpdate(camera.bearing, camera.tilt)
            if (needsPositionOrZoom || needsOrientation) {
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder(camera)
                            .target(LatLng(fix.lat, fix.lon))
                            .zoom(followZoom)
                            .bearing(0.0)
                            .tilt(0.0)
                            .build(),
                    ),
                    400,
                )
            }
        }
    }
    // Finish the grace period even when the rider is stationary and no new
    // location fix arrives. Resetting the state re-enables follow and gives
    // tests/visual tooling a settled `followSuspended=false` snapshot.
    LaunchedEffect(cameraFollowSuspension) {
        val pending = cameraFollowSuspension
        if (pending.gestureActive) return@LaunchedEffect
        val remainingMs = pending.remainingDelayMs(SystemClock.elapsedRealtime())
        if (remainingMs > 0L) delay(remainingMs)
        if (cameraFollowSuspension != pending) return@LaunchedEffect
        cameraFollowSuspension = pending.cooldownElapsed()
        if (debugLogs && !MapPerfProbeGate.sweepActive) {
            val camera = withContext(Dispatchers.Main.immediate) {
                mapRef.value?.cameraPosition
            }
            camera?.let {
                val locked = if (guidanceRequested) guidanceCameraLocked else followMe
                VisualRouteSnapshot.writeCamera(
                    context.cacheDir,
                    it,
                    guidanceRequested,
                    followSuspended = false,
                    cameraLocked = locked,
                )
            }
        }
    }
    // The debug camera probe also exposes lock transitions that do not move
    // the native camera (for example, turning follow on while already centred).
    LaunchedEffect(debugLogs, mapRef.value, followMe, guidanceRequested, guidanceCameraLocked) {
        if (!debugLogs || MapPerfProbeGate.sweepActive) return@LaunchedEffect
        val camera = withContext(Dispatchers.Main.immediate) {
            mapRef.value?.cameraPosition
        } ?: return@LaunchedEffect
        val locked = if (guidanceRequested) guidanceCameraLocked else followMe
        VisualRouteSnapshot.writeCamera(
            context.cacheDir,
            camera,
            guidanceRequested,
            followSuspended = locked &&
                cameraFollowSuspension.isSuspended(SystemClock.elapsedRealtime()),
            cameraLocked = locked,
        )
    }
    // On the first usable fix, move the initial map viewport to the rider.
    // This is intentionally one-shot: later fixes update the position marker,
    // while the rider can pan freely until they explicitly enable the lock.
    // A shown route or a map gesture spends it (PlannerCameraPolicy), so a
    // fix that arrives late never flies the camera off the rider's route.
    LaunchedEffect(
        trackedFix,
        styleJson,
        loadedStyleJson,
        mapRef.value,
        routeResult,
        routeLoading,
        fittedRouteSet,
        followMe,
        guidanceRequested,
        screenStarted,
    ) {
        if (!screenStarted) return@LaunchedEffect
        if (!PlannerCameraPolicy.centerOnFirstFix(
                alreadySpent = initialLocationCentered,
                routeShown = routeResult != null || fittedRouteSet != null,
                planSearching = routeLoading,
                followMe = followMe,
                guidance = guidanceRequested,
            )
        ) {
            return@LaunchedEffect
        }
        val map = mapRef.value ?: return@LaunchedEffect
        val fix = trackedFix ?: return@LaunchedEffect
        if (fix.lat.isNaN() || fix.lon.isNaN()) return@LaunchedEffect
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lon), FOLLOW_ZOOM),
            500,
        )
        initialLocationCentered = true
        Log.i(TAG, "Initial map viewport centered on the first location fix")
    }
    // In planning mode, manual zoom changes followZoom so the location lock
    // keeps the user's selected level.
    val latestFollowMe = rememberUpdatedState(followMe)
    val latestGuidanceCameraLocked = rememberUpdatedState(guidanceCameraLocked)
    DisposableEffect(mapRef.value) {
        val map = mapRef.value ?: return@DisposableEffect onDispose { }
        var gestureStartZoom: Double? = null
        var mapTouchActive = false
        var mapTouchWasLocked = false
        fun cameraLockActive(): Boolean = if (latestGuidanceRequested.value) {
            latestGuidanceCameraLocked.value
        } else {
            latestFollowMe.value
        }
        fun finishMapGesture() {
            if (!mapTouchActive && gestureStartZoom == null) return
            val lockedGesture = mapTouchWasLocked || gestureStartZoom != null
            mapTouchActive = false
            mapTouchWasLocked = false
            if (!lockedGesture) return
            if (latestFollowMe.value && !latestGuidanceRequested.value) {
                followZoom = map.cameraPosition.zoom
            }
            gestureStartZoom = null
            val endedAt = SystemClock.elapsedRealtime()
            cameraFollowSuspension = cameraFollowSuspension.gestureEnded(endedAt)
            if (debugLogs && !MapPerfProbeGate.sweepActive) {
                // Publish the camera at pointer-up as well as native idle. The
                // map can still be finishing its gesture animation, and the
                // debug runner needs an immediate snapshot of the real angle
                // plus the active follow pause.
                val guidanceActive = latestGuidanceRequested.value
                val locked = cameraLockActive()
                VisualRouteSnapshot.writeCamera(
                    context.cacheDir,
                    map.cameraPosition,
                    guidanceActive,
                    followSuspended = locked &&
                        cameraFollowSuspension.isSuspended(endedAt),
                    cameraLocked = locked,
                )
            }
        }
        val touchListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    mapTouchActive = true
                    // The rider is looking somewhere: never yank the camera
                    // to a first fix that arrives afterwards.
                    initialLocationCentered = true
                    mapTouchWasLocked = cameraLockActive()
                    if (mapTouchWasLocked) {
                        gestureStartZoom = map.cameraPosition.zoom
                        cameraFollowSuspension = cameraFollowSuspension.gestureStarted()
                    }
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> finishMapGesture()
            }
            // Observe the pointers while leaving every event for MapLibre's
            // normal pan, pinch, rotate, and two-finger tilt recognizers.
            false
        }
        mapView.setOnTouchListener(touchListener)
        val moveStarted = MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE &&
                cameraLockActive()
            ) {
                if (gestureStartZoom == null) gestureStartZoom = map.cameraPosition.zoom
                cameraFollowSuspension = cameraFollowSuspension.gestureStarted()
            }
        }
        val idle = MapLibreMap.OnCameraIdleListener {
            // Native camera idle may fire while a rider is holding a finger
            // still on the map. The raw touch observer owns the true end of
            // the gesture; this fallback covers native gestures without a
            // delivered touch sequence and never ends an active touch early.
            if (!mapTouchActive && gestureStartZoom != null) finishMapGesture()
            if (debugLogs && !MapPerfProbeGate.sweepActive) {
                // Debug seam for tools/test/visual.py: publish the settled
                // camera so the runner can check guidance framing and the
                // planning-camera restore after END. Suppressed while a perf
                // sweep drives the camera, so the benchmark measures the map
                // and not its own debug bookkeeping.
                val settled = map.cameraPosition
                val guidanceActive = latestGuidanceRequested.value
                val suspension = cameraFollowSuspension
                val locked = if (guidanceActive) {
                    latestGuidanceCameraLocked.value
                } else {
                    latestFollowMe.value
                }
                val suspended = locked && suspension.isSuspended(SystemClock.elapsedRealtime())
                scope.launch(Dispatchers.IO) {
                    VisualRouteSnapshot.writeCamera(
                        context.cacheDir,
                        settled,
                        guidanceActive,
                        followSuspended = suspended,
                        cameraLocked = locked,
                    )
                }
            }
        }
        map.addOnCameraMoveStartedListener(moveStarted)
        // Zoom-gated tile LOD: the coarse distant-tile shift only applies to
        // wide views (<= z15); close views keep pre-LOD rendering. The band
        // changes rarely, so this makes no native call while it is stable.
        map.addOnCameraIdleListener(idle)
        onDispose {
            map.removeOnCameraMoveStartedListener(moveStarted)
            map.removeOnCameraIdleListener(idle)
            mapView.setOnTouchListener(null)
        }
    }

    fun zoomFromPill(delta: Double) {
        val map = mapRef.value ?: return
        val camera = map.cameraPosition
        val target = camera.target ?: return
        val targetZoom = (camera.zoom + delta)
            .coerceIn(map.minZoomLevel, map.maxZoomLevel)
        if (followMe && !guidanceRequested) {
            followZoom = targetZoom
            // Let the follow effect combine the new zoom with the latest
            // rider position. Without a fix, apply the zoom at the current
            // target directly.
            if (trackedFix == null) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, targetZoom), 250)
            }
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, targetZoom), 250)
        }
    }
    // This is the only position-marker owner. It collects navigation
    // snapshots directly while started, so returning from background renders
    // the current position once without waking RouteScreen per fix.
    val markerDensity = LocalDensity.current.density
    LaunchedEffect(
        trackingActive,
        guidanceRequested,
        guidanceCameraLocked,
        loadedStyleJson,
        mapRef.value,
        screenStarted,
        markerDensity,
        darkGuidanceStyle,
        cameraFollowSuspension,
    ) {
        if (!screenStarted) return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        if (loadedStyleJson == null || loadedStyleJson != styleJson) return@LaunchedEffect
        if (!trackingActive) {
            map.hideNavPosition()
            return@LaunchedEffect
        }
        if (guidanceRequested) {
            navigationController.snapshot.collect { snap ->
                // MapLibre requires native updates on the Android UI thread,
                // including emissions resumed by snapshot/test dispatchers.
                withContext(Dispatchers.Main.immediate) {
                    if (snap.state == NavigationState.Idle || !snap.lat.isFinite() || !snap.lon.isFinite()) {
                        map.hideNavPosition()
                        return@withContext
                    }
                    val camera = map.cameraPosition
                    val bearing = guidanceBearingTracker.resolve(snap.bearingDeg, camera.bearing)
                    map.updateGuidancePosition(
                        snap.lat,
                        snap.lon,
                        bearing,
                        markerDensity,
                        monochrome = darkGuidanceStyle,
                    )
                    // Navigation and the position marker remain live while the
                    // rider inspects the map with the camera lock released.
                    if (!guidanceCameraLocked) return@withContext
                    if (cameraFollowSuspension.isSuspended(SystemClock.elapsedRealtime())) {
                        return@withContext
                    }
                    val zoom = guidanceZoomFor(snap.speedMps)
                    val cameraTarget = camera.target ?: return@withContext
                    val needsPositionOrZoom = shouldFollowCamera(
                        cameraTarget.latitude,
                        cameraTarget.longitude,
                        camera.zoom,
                        snap.lat,
                        snap.lon,
                        zoom,
                        followMovementThresholdMeters(snap.speedMps),
                    )
                    val needsOrientation = guidanceOrientationNeedsUpdate(
                        cameraBearing = camera.bearing,
                        targetBearing = bearing,
                        cameraTilt = camera.tilt,
                    )
                    if (needsPositionOrZoom || needsOrientation) {
                        val topPadding = (mapView.height.coerceAtLeast(0) * GUIDANCE_TOP_PADDING_FRACTION)
                        map.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(snap.lat, snap.lon))
                                    .zoom(zoom)
                                    .bearing(bearing)
                                    .tilt(GUIDANCE_TILT_DEGREES)
                                    .padding(0.0, topPadding, 0.0, 0.0)
                                    .build(),
                            ),
                            600,
                        )
                    }
                }
            }
        } else {
            snapshotFlow { trackedFix }.collect { fix ->
                withContext(Dispatchers.Main.immediate) {
                    if (fix == null || !fix.lat.isFinite() || !fix.lon.isFinite()) {
                        map.hideNavPosition()
                    } else {
                        map.updateNavPosition(fix.lat, fix.lon, fix.bearingDeg)
                    }
                }
            }
        }
    }

    // Status-bar icons read on what is behind them: dark over the light colour
    // map and the light pages, light only over the B&W ride map.
    SystemBarIcons(darkIcons = !darkGuidanceStyle)
    // Map above, panel below: the map is exactly the region the route menu
    // does not cover, so the two never overlap and the menu cannot slide
    // around over the map. The open settings menu and its scrim sit above
    // both regions, so a tap anywhere outside the menu closes it.
    Box(Modifier.fillMaxSize()) {
        // The whole screen is map; the planner panel floats over it as an
        // island and the map box below holds the controls above the panel.
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    mapViewBottomPx = coordinates.positionInWindow().y.roundToInt() + coordinates.size.height
                }
                .semantics { contentDescription = "Map" },
        )
        // A soft wash behind the status bar so map labels never collide with the
        // clock and icons (white over the colour map, black over the B&W map).
        StatusBarScrim(dark = darkGuidanceStyle, modifier = Modifier.align(Alignment.TopStart))
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .semantics {
                    this[RouteUiStateKey] = routeStateName
                    this[RouteGenerationKey] = routeGeneration
                    this[SelectedRouteKey] = selectedIndex
                    this[RouteCountKey] = routeCount
                    this[LocationPermissionKey] = locationPermissionGranted
                    this[MapInstalledKey] = mapUrl != null
                    this[MapStyleJsonReadyKey] = styleJson != null
                    this[MapNativeStyleReadyKey] = mapRef.value?.style != null
                    this[MapStyleLoadedKey] = loadedStyleJson != null
                    this[MapStyleMatchesRequestKey] = styleJson != null && loadedStyleJson == styleJson
                    this[MapScreenStartedKey] = screenStarted
                    this[MapStyleLoadStartedKey] = styleJson != null && styleLoadStartedJson == styleJson
                    this[MapStyleLoadGenerationKey] = styleLoadGeneration
                    this[MapAppliedStyleReadyKey] = appliedStyleJson != null
                    this[MapAppliedStyleMatchesRequestKey] =
                        styleJson != null && appliedStyleJson == styleJson && appliedMapRevision == mapRevision
                    this[MapStyleLoadErrorKey] = styleLoadError
                    this[MapLoadFailureKey] = mapImportError.orEmpty()
                    val cameraLocked = if (guidanceRequested) guidanceCameraLocked else followMe
                    this[FollowCameraLockedKey] = cameraLocked
                    this[FollowCameraSuspendedKey] = cameraLocked &&
                        cameraFollowSuspension.isSuspended(SystemClock.elapsedRealtime())
                    this[GuidanceActiveKey] = guidanceRequested && navSnapshot.state != NavigationState.Idle
                    this[DarkRideMapEnabledKey] = darkRideMapEnabled
                    this[RideSuggestionKey] = rideSuggestion.semanticsValue
                    this[PositionSourceKey] = navSnapshot.positionSource.name.lowercase()
                    this[GpsSignalLostKey] = gpsSignalLost
                    this[RideSuggestionsEnabledKey] = rideSuggestionsEnabled
                    this[DarkGuidanceStyleReadyKey] = darkGuidanceStyle &&
                        styleJson != null && loadedStyleJson == styleJson
                    this[MediaPanelOpenKey] = mediaPanelOpen
                    this[MediaCenterStatusKey] = mediaState.status.name.lowercase()
                    this[MediaAccessGrantedKey] = mediaState.access.name == "GRANTED"
                    this[ActiveRegionKey] = activeDataset.regionId
                    this[ActiveRegionGenerationKey] = activeDataset.generation
                    this[InstalledRegionCountKey] = mapsState.installed.size
                }
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned { coordinates ->
                        val top = coordinates.positionInWindow().y.roundToInt()
                        mapBoxTopPx = top
                        mapBoxBottomPx = top + coordinates.size.height
                    }
                    .semantics {
                        this[FocusedRouteIndexKey] = selectedIndex
                        this[MapRouteCountKey] = routeCount
                        this[MapReadyKey] = mapRef.value != null &&
                            styleJson != null && loadedStyleJson == styleJson
                    }
            ) {
                if (rideActive && inPictureInPicture) {
                    NavigationPipHud(
                        snapshot = navSnapshot,
                        gpsLost = gpsSignalLost,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                } else if (rideActive) {
                    NavigationHud(
                        snapshot = navSnapshot,
                        gpsLost = gpsSignalLost,
                        // The ride toolbar's width, plus a gap.
                        trailingClearance = RideToolbarWidth + 8.dp,
                        monochrome = darkRideMapEnabled,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .onGloballyPositioned { coordinates ->
                                hudHeightPx = coordinates.size.height
                            },
                    )
                }
                val pendingSuggestion = (rideSuggestion as? RideSuggestionState.Pending)?.proposal
                // Hidden while the media panel is open (it owns the space
                // between the HUD and the bar); voice and "ride it" still work.
                if (rideActive && pendingSuggestion != null && !mediaPanelOpen && !inPictureInPicture) {
                    RideSuggestionBanner(
                        measure = pendingSuggestion.measure,
                        unit = voiceUnit,
                        onTake = { navigationController.takeSuggestion() },
                        onSkip = { navigationController.skipSuggestion() },
                        monochrome = darkRideMapEnabled,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 12.dp, end = 12.dp, top = hudHeightDp + 8.dp),
                    )
                }
                // A modal dialog over a scrim (MapNeeded design). The Maps screen
                // is a full-screen surface above it, so the dialog steps aside
                // while Maps is open; Back or an outside tap dismisses it so the
                // settings menu (Maps, Load map file) stays reachable.
                if (mapFileChecked && mapUrl == null && !missingMapDialogDismissed &&
                    !mapsSettingsOpen && !ossLicencesOpen
                ) {
                    MissingMapDialog(
                        importing = mapImporting,
                        error = mapImportError,
                        dataMissing = !activeDataset.dataPresent,
                        onChooseFile = chooseMapFile,
                        onOpenMaps = { mapsSettingsOpen = true },
                        onDismiss = { missingMapDialogDismissed = true },
                    )
                }
                if (startupAttributionVisible) {
                    MapCreditsChip(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            // Right of MapLibre's logo and attribution button.
                            .padding(start = MAP_LOGO_CLEARANCE, end = 80.dp, bottom = 8.dp),
                    )
                }
                val controlsDensity = LocalDensity.current
                // Space the bottom-right controls cannot use: the ride data bar or
                // the open search-results sheet.
                val reservedBottomDp = if (rideActive) dataBarHeightDp else searchSheetCoverDp
                fun controlSlot(bottomOffset: Dp, height: Dp): MapControlsLayout.Slot =
                    with(controlsDensity) {
                        MapControlsLayout.slot(
                            mapTopPx = mapBoxTopPx,
                            mapBottomPx = mapBoxBottomPx,
                            bottomOffsetPx = bottomOffset.roundToPx(),
                            controlHeightPx = height.roundToPx(),
                            railTopPx = railTopPx,
                            railBottomPx = railBottomPx,
                            reservedBottomPx = reservedBottomDp.roundToPx(),
                            gapPx = 8.dp.roundToPx(),
                        )
                    }
                val centreBottom = MapControlsLayout.centreBottom(rideActive, dataBarHeightDp, searchSheetCoverDp)
                val zoomBottom = MapControlsLayout.zoomBottom(rideActive, dataBarHeightDp, searchSheetCoverDp)
                val controlsEnd = MapControlsLayout.endPadding(rideActive)
                // The map box is measured after the first layout; until then the
                // controls wait instead of flashing in the wrong place.
                val controlsMeasured = mapBoxBottomPx > 0 && railBottomPx > 0
                // A short window (phone landscape) has no room for the toolbar and
                // both controls on the right edge: the controls move left of the
                // toolbar, the locate button first, or hide; they never overlap it.
                val centreSlot = if (controlsMeasured) {
                    controlSlot(centreBottom, MapControlsLayout.CENTRE_BUTTON_SIZE)
                } else {
                    MapControlsLayout.Slot.COLUMN
                }
                val zoomSlot = if (controlsMeasured) {
                    controlSlot(zoomBottom, MapControlsLayout.ZOOM_HEIGHT)
                } else {
                    MapControlsLayout.Slot.COLUMN
                }
                val besideTopDp = with(controlsDensity) { (railTopPx - mapBoxTopPx).coerceAtLeast(0).toDp() }
                val centreBesideIndex = 0
                val zoomBesideIndex = if (centreSlot == MapControlsLayout.Slot.BESIDE) 1 else 0
                if (!mapControlsHidden && centreSlot != MapControlsLayout.Slot.HIDDEN) {
                    CentreOnMeButton(
                        rideMode = rideActive,
                        monochrome = rideActive && darkRideMapEnabled,
                        active = if (guidanceRequested) guidanceCameraLocked else followMe,
                        description = when {
                            guidanceRequested && guidanceCameraLocked -> "Rider lock on; tap to release"
                            guidanceRequested -> "Rider lock off; tap to follow"
                            followMe -> "Following your location; tap to release"
                            else -> "Centre on me"
                        },
                        onClick = {
                            if (guidanceRequested) {
                                val enableLock = !guidanceCameraLocked
                                if (!enableLock) {
                                    mapRef.value?.cancelTransitions()
                                }
                                cameraFollowSuspension = CameraFollowSuspension()
                                guidanceCameraLocked = enableLock
                            } else {
                                val enableFollow = !followMe
                                if (enableFollow) {
                                    mapRef.value?.cameraPosition?.zoom?.let { followZoom = it }
                                }
                                cameraFollowSuspension = CameraFollowSuspension()
                                followMe = enableFollow
                            }
                        },
                        modifier = if (centreSlot == MapControlsLayout.Slot.BESIDE) {
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(
                                    end = MapControlsLayout.besideEnd(rideActive, centreBesideIndex),
                                    top = besideTopDp,
                                )
                        } else {
                            Modifier
                                .align(Alignment.BottomEnd)
                                // Above the data bar in a ride, above the search
                                // results sheet while the planner searches.
                                .padding(end = controlsEnd, bottom = centreBottom)
                        },
                    )
                }
                // The media panel is drawn BEFORE the data bar on purpose. It
                // reaches MediaPanelJoin.fuseDepth down into the bar's empty top
                // padding, and letting the bar paint after it hides that overlap
                // under the bar's own opaque surface: the pair then reads as one
                // continuous silhouette instead of two cards with a seam.
                // The panel stays open (and its Start watch alive) behind the
                // Picture-in-Picture window; it shows again with the full ride.
                if (rideActive && mediaPanelOpen && !inPictureInPicture) {
                    // Bound the panel to the space between the directions HUD
                    // and the measured data bar; the card scrolls inside that
                    // bound, so landscape cannot cover the HUD or END.
                    BoxWithConstraints(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxSize()
                            .padding(bottom = dataBarHeightDp - MediaPanelJoin.fuseDepth),
                    ) {
                        val panelMaxHeight = (maxHeight - hudHeightDp - 12.dp)
                            .coerceAtLeast(96.dp)
                        MediaControlCenter(
                            state = mediaState,
                            onCommand = { mediaController.dispatch(it) },
                            onVolume = { mediaController.adjustVolume(it) },
                            onSelectPlayer = { mediaController.selectSession(it) },
                            onStartMusicApp = { mediaController.startMusicApp(it) },
                            onOpenMusicApp = { mediaController.openMusicApp(it) },
                            onClose = { mediaPanelOpen = false },
                            maxHeight = panelMaxHeight,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
                if (rideActive && !inPictureInPicture) {
                    NavigationDataBar(
                        snapshot = navSnapshot,
                        onEnd = {
                            mediaPanelOpen = false
                            guidanceRequested = false
                            // Return to planning: without this the button stays
                            // RIDE (state is still Success) and can never start
                            // a new route search.
                            coordinator.reset()
                            Log.i(TAG, "Guidance ended via END button")
                        },
                        mediaPanelOpen = mediaPanelOpen,
                        onMediaToggle = { mediaPanelOpen = !mediaPanelOpen },
                        monochrome = darkRideMapEnabled,
                        gpsLost = gpsSignalLost,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // Measure the real bar so the media panel and map
                            // controls anchor exactly above it.
                            .onGloballyPositioned { coordinates ->
                                dataBarHeightPx = coordinates.size.height
                            },
                    )
                }
                // The keyboard shrinks the map box; in that state the pill rides
                // up under the RouteActionsPill stack and steals its taps, so it
                // stays hidden while the IME is visible.
                if (WindowInsets.ime.getBottom(LocalDensity.current) == 0 && !mapControlsHidden &&
                    zoomSlot != MapControlsLayout.Slot.HIDDEN
                ) {
                    ZoomPill(
                        onZoomIn = { zoomFromPill(1.0) },
                        onZoomOut = { zoomFromPill(-1.0) },
                        rideMode = rideActive,
                        modifier = if (zoomSlot == MapControlsLayout.Slot.BESIDE) {
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(
                                    end = MapControlsLayout.besideEnd(rideActive, zoomBesideIndex),
                                    top = besideTopDp,
                                )
                        } else {
                            Modifier
                                .align(Alignment.BottomEnd)
                                // In ride mode the measured data bar occupies the
                                // bottom of the map box (in planning, an open search
                                // results sheet); lift the pill above it (and above
                                // the centre button) so they never overlap.
                                .padding(end = controlsEnd, bottom = zoomBottom)
                        },
                    )
                }
            }
            // How closely the shown route rides a followed GPX track, measured
            // off the main thread once per route.
            val followedRide = importedGpx?.takeIf { activePlan?.followTrack != null }
            val followedPath = routeResult?.routes?.firstOrNull()
            val followMeasure by produceState<TrackFollowMeasure?>(null, followedRide?.plan, followedPath) {
                value = null
                val plan = followedRide?.plan ?: return@produceState
                val path = followedPath ?: return@produceState
                value = withContext(Dispatchers.Default) {
                    GpxTrackFollow.measure(plan.line, path.points.toGeoPoints())
                }
            }
            val followedGpx = followedRide?.let { FollowedGpx(it.name, followMeasure?.offTrackMeters) }
            RoutePlanPanel(
                fromText = fromText,
                fromResolved = fromPoint != null,
                fromHint = when (val locationStatus = fromLocationState.status) {
                    CurrentLocationStatus.Idle -> "Route from"
                    is CurrentLocationStatus.WaitingForPermission -> "Waiting for location permission…"
                    is CurrentLocationStatus.WaitingForFix -> "Finding current location…"
                    is CurrentLocationStatus.Unavailable -> locationStatus.reason.hint
                },
                onFromChange = {
                    cancelFromLocationRequest()
                    clearImportedGpx()
                    fromText = it; fromPoint = null
                    activePlan = null; coordinator.invalidate()
                },
                onEditFrom = {
                    // Editing does not give up the current location: it is held
                    // until the rider writes or picks another place.
                    if (currentLocationInFrom(fromText, fromPoint != null)) heldCurrentLocation = fromPoint
                    clearImportedGpx()
                    cancelFromLocationRequest()
                    fromPoint = null
                    activePlan = null
                    coordinator.invalidate()
                },
                onFromFocusLost = {
                    val held = heldCurrentLocation
                    heldCurrentLocation = null
                    if (held != null && fromPoint == null && keepsCurrentLocationOnLeave(fromText)) {
                        fromText = CURRENT_LOCATION_LABEL
                        fromPoint = held
                        Log.i(TAG, "Current location kept in From")
                    }
                },
                onFromPicked = { result ->
                    heldCurrentLocation = null
                    clearImportedGpx()
                    cancelFromLocationRequest()
                    fromText =
                        if (result.subtitle.isNotBlank()) "${result.name}, ${result.subtitle}" else result.name
                    fromPoint = GHPoint(result.lat, result.lon)
                    activePlan = null; coordinator.invalidate()
                },
                // The key is a toggle: in use, a second tap clears From.
                onUseCurrentLocation = {
                    heldCurrentLocation = null
                    if (currentLocationInFrom(fromText, fromPoint != null)) {
                        cancelFromLocationRequest()
                        fromText = ""
                        fromPoint = null
                        activePlan = null
                        coordinator.invalidate()
                        Log.i(TAG, "Current location cleared from From")
                    } else {
                        requestCurrentLocation()
                    }
                },
                toText = toText,
                toResolved = toPoint != null,
                onToChange = {
                    clearImportedGpx()
                    toText = it; toPoint = null
                    activePlan = null; coordinator.invalidate()
                },
                onEditTo = {
                    clearImportedGpx()
                    toPoint = null
                    activePlan = null
                    coordinator.invalidate()
                },
                onToPicked = { result ->
                    clearImportedGpx()
                    toText =
                        if (result.subtitle.isNotBlank()) "${result.name}, ${result.subtitle}" else result.name
                    toPoint = GHPoint(result.lat, result.lon)
                    activePlan = null; coordinator.invalidate()
                },
                geocodeController = geocodeController,
                state = state,
                stage = plannerStage,
                // Ride styles of the current plan whose search has finished;
                // the slider grows one hump per finished style.
                readyStops = styleSearchProgress
                    ?.takeIf { progress -> activePlan?.let { RidePlanKey.of(it) } == progress.key }
                    ?.resolved ?: 0,
                onBackToEndpoints = backToEndpoints,
                onSearchSheetTopChange = { searchSheetTopPx = it },
                onSelectRoute = latestSelectRoute,
                onRoute = onRoute,
                complexity = complexity,
                onComplexityChange = { level ->
                    // The slider reports the rounded stop under the finger, so
                    // the shown ride switches at every stop the drag crosses;
                    // a stop still being searched keeps the old card until its
                    // search lands.
                    complexity = level
                    requestRideStyle(level)
                },
                gpxImportError = gpxImportError,
                onDismissGpxError = { gpxImportError = null },
                onSaveRoute = ::saveProposedRoute,
                onToggleGuidance = {
                    if (locationPermissionGranted) {
                        guidanceRequested = true
                    } else {
                        pendingGuidanceStart = true
                        permissionLauncher.launch(LocationPermission.requestedPermissions)
                    }
                    Log.i(TAG, "Guidance toggled: $guidanceRequested")
                },
                guidanceActive = guidanceRequested,
                importedGpxName = importedGpxName,
                importedGpxMilestoneCount = importedGpx?.milestones?.size ?: 0,
                followedGpx = followedGpx,
                onOpenGpxPreview = { gpxPreviewOpen = true },
                onEditImportedGpx = {
                    clearImportedGpx()
                    activePlan = null
                    coordinator.invalidate()
                },
                roundTrip = roundTripWish.takeIf { roundTripEnabled },
                onToggleRoundTrip = {
                    roundTripEnabled = !roundTripEnabled
                    // The other endpoint changed: the shown plan is stale.
                    activePlan = null
                    coordinator.invalidate()
                    val length = roundTripWish.length(System.currentTimeMillis())
                    Log.i(TAG, "Round trip ${if (roundTripEnabled) "on (${length.label})" else "off"}")
                },
                onRoundTripChange = { wish ->
                    roundTripWish = wish
                    routePreferences.edit().putString(ROUND_TRIP_LENGTH_PREF, wish.storageKey).apply()
                    activePlan = null
                    coordinator.invalidate()
                    Log.i(
                        TAG,
                        "Round trip ${wish.measure.storageName} (${wish.length(System.currentTimeMillis()).label})",
                    )
                },
                // The card measures the factors the shown plan was routed with.
                rideFactors = activePlan?.factors ?: rideFactors,
                graphHasElevation = router.features?.elevation == true,
                graphHasStops = router.features?.stops == true,
                onComplexityChangeFinished = { finalComplexity ->
                    // The drag already asked for every stop it crossed, so a
                    // release on the same stop is deliberately a no-op: the
                    // gesture's final value is used directly so the release
                    // cannot race the next Compose state frame.
                    complexity = finalComplexity
                    Log.i(TAG, "Ride style slider released: $finalComplexity")
                    requestRideStyle(finalComplexity)
                }
            )
        }
        // The compass sits in the toolbar column (Ride.dc.html): at the top of
        // it in a ride, below the toolbar in the planner (only while the map is
        // rotated). It is drawn under the full-screen pages and the menu scrim.
        if ((!guidanceRequested || rideActive) && railBottomPx > 0) {
            val compassDensity = LocalDensity.current
            val compassModifier = if (rideActive) {
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(
                        top = NavigationHudTopPadding,
                        end = MapControlsLayout.compassEnd(rideMode = true),
                    )
            } else {
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = with(compassDensity) { railBottomPx.toDp() } + MapControlsLayout.COMPASS_GAP,
                        end = MapControlsLayout.compassEnd(rideMode = false),
                    )
            }
            MapCompassButton(
                bearing = { mapBearing },
                rideMode = rideActive,
                alwaysVisible = rideActive,
                onClick = {
                    mapRef.value?.let { map ->
                        map.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder(map.cameraPosition).bearing(0.0).build(),
                            ),
                            300,
                        )
                    }
                },
                modifier = compassModifier,
            )
        }
        // Tapping anywhere outside the expanded card dismisses the settings
        // menu. The rail is drawn after this scrim, so its own 48 dp targets
        // stay reachable while the menu is open.
        if (mapsSettingsOpen) {
            MapsSettingsScreen(
                state = visualMapsPreview ?: mapsState,
                rideActive = rideActive,
                onDismiss = {
                    regionManager.clearMessages()
                    mapsSettingsOpen = false
                },
                onBrowseMaps = regionManager::checkServer,
                onRequestBuild = regionManager::requestBuild,
                // The hosted service never cancels a build for one client:
                // the app stops following it and the build stays cached.
                onCancelBuild = regionManager::stopWaiting,
                onDownloadPackage = { regionId ->
                    pendingMapsDownload = MapsDownloadAction.New(regionId)
                    mapsDownloadPicker.launch(regionManager.suggestedFileName(regionId))
                },
                onCancelDownload = regionManager::cancelDownload,
                onResumeDownload = {
                    if (mapsState.interrupted?.installMode == true) {
                        regionManager.resumeInstall()
                    } else {
                        pendingMapsDownload = MapsDownloadAction.Resume
                        mapsDownloadPicker.launch(regionManager.suggestedFileNameForResume())
                    }
                },
                onSaveRecoveredPackage = {
                    pendingMapsDownload = MapsDownloadAction.SaveRecovered
                    mapsDownloadPicker.launch(regionManager.suggestedFileNameForRecovered())
                },
                onImportRecoveredPackage = regionManager::importRecoveredPackage,
                onImportSavedPackage = regionManager::importSavedPackage,
                onImportPackage = {
                    mapsPackagePicker.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
                },
                onActivate = regionManager::activate,
                onActivateBundled = regionManager::activateBundled,
                onRemove = regionManager::requestRemove,
                onDismissMessage = regionManager::clearMessages,
                onInstallPackage = regionManager::downloadAndInstall,
                onSearchRegions = regionManager::searchExtracts,
                // Near me uses the location the app already has; it never
                // asks for permission from the Maps screen.
                onNearMe = if (locationPermissionGranted) {
                    {
                        val fix = trackedFix
                        if (fix != null) {
                            regionManager.searchNearMe(fix.lat, fix.lon)
                        } else {
                            regionManager.searchUnavailable("No location fix yet. Make sure location is on, then try again.")
                        }
                    }
                } else {
                    null
                },
                onClearSearch = regionManager::clearSearch,
                onGetExtract = regionManager::requestExtractBuild,
            )
        }
        LaunchedEffect(mapsSettingsOpen) {
            if (mapsSettingsOpen) regionManager.onMapsOpened()
        }
        if (ossLicencesOpen) {
            // Full-screen like Maps; the planner toolbar is hidden while it is open.
            OpenSourceLicencesScreen(
                onDismiss = { ossLicencesOpen = false },
                backEnabled = !settingsMenuOpen,
            )
        }
        if (settingsMenuOpen) {
            // Dismissal scrim over map and planner (Planner/SettingsMenu design: a
            // light 8 % dim); the toolbar and its menu draw above it.
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.08f))
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = { settingsMenuOpen = false },
                    )
            )
        }
        // The Maps and Open-source licences pages are full-screen (their mockups
        // show no toolbar): the planner toolbar steps aside instead of covering
        // their headers, chips and banners. Back returns to the planner.
        // The Picture-in-Picture window shows only the map and the turn card.
        if ((!guidanceRequested || rideActive) && !mapsSettingsOpen && !ossLicencesOpen && !inPictureInPicture) {
            RouteActionsPill(
                onLoadMap = chooseMapFile,
                onImportGpx = {
                    gpxPicker.launch(
                        arrayOf(
                            "application/gpx+xml",
                            "application/gpx",
                            "text/xml",
                            "application/xml",
                            "*/*",
                        )
                    )
                },
                onRouteSettings = {
                    // The settings dialogs share the "Apply" label; only one may
                    // be open at a time so the confirm action is unambiguous.
                    voiceSettingsOpen = false
                    routeSettingsOpen = true
                },
                onMapsSettings = {
                    regionManager.clearMessages()
                    routeSettingsOpen = false
                    voiceSettingsOpen = false
                    ossLicencesOpen = false
                    mapsSettingsOpen = true
                },
                onOpenSourceLicences = {
                    // One full-screen surface at a time, so one Back peels it.
                    routeSettingsOpen = false
                    voiceSettingsOpen = false
                    mapsSettingsOpen = false
                    ossLicencesOpen = true
                },
                onVoiceSettings = {
                    routeSettingsOpen = false
                    voiceGuidanceOutput.refreshAvailability()
                    voiceSettingsOpen = true
                },
                voiceGuidanceEnabled = voiceGuidanceEnabled,
                voiceSpeechStatus = voiceSpeechStatus,
                onOpenSavedRoutes = { savedRoutesOpen = true },
                settingsMenuExpanded = settingsMenuOpen,
                onSettingsMenuExpandedChange = { settingsMenuOpen = it },
                rideMode = rideActive,
                darkRideMapEnabled = darkRideMapEnabled,
                monochrome = darkRideMapEnabled,
                onDarkRideMapToggle = {
                    darkRideMapEnabled = !darkRideMapEnabled
                    routePreferences.edit()
                        .putBoolean(DARK_RIDE_MAP_PREF, darkRideMapEnabled)
                        .apply()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(
                        // In a ride the painted pill starts RideRailHitMargin
                        // inside its glove-sized hit box; level its visible top
                        // with the guidance card's. The hit box is wider than the
                        // painted toolbars, so it sits closer to the edge to share
                        // their vertical axis.
                        top = if (rideActive) {
                            // Below the compass (always shown in a ride).
                            NavigationHudTopPadding - RideRailHitMargin +
                                MapControlsLayout.COMPASS_SIZE + MapControlsLayout.COMPASS_GAP
                        } else {
                            16.dp
                        },
                        end = if (rideActive) MapControlsLayout.rideToggleEndPadding() else MapControlsLayout.PLANNER_END,
                    )
                    .onGloballyPositioned { coordinates ->
                        // The collapsed rail is what lifted map controls must
                        // clear; the open card has a scrim over everything.
                        if (!settingsMenuOpen) {
                            railTopPx = coordinates.positionInWindow().y.roundToInt()
                            railBottomPx = railTopPx + coordinates.size.height
                        }
                    },
            )
        }
    }
    BackHandler(enabled = settingsMenuOpen) { settingsMenuOpen = false }
    // System Back from the ride styles returns to the From/To fields, like the
    // back button left of the slider. Every surface drawn above the planner
    // keeps priority.
    BackHandler(
        enabled = plannerStage == PlannerStage.Styles && !guidanceRequested &&
            !settingsMenuOpen && !mapsSettingsOpen && !ossLicencesOpen,
        onBack = backToEndpoints,
    )
    // The Maps screen is a full-screen surface; system Back closes it like the
    // other screens opened from the settings menu. The menu keeps priority
    // when it is also open (it is drawn above the Maps screen).
    BackHandler(enabled = mapsSettingsOpen && !settingsMenuOpen) {
        regionManager.clearMessages()
        mapsSettingsOpen = false
    }
    // System Back closes the open media panel instead of leaving the app. The
    // settings menu keeps priority: this handler is disabled while it is open,
    // so one Back always peels exactly the top-most surface.
    BackHandler(enabled = rideActive && mediaPanelOpen && !settingsMenuOpen) {
        mediaPanelOpen = false
    }

    if (routeSettingsOpen) {
        RouteSettingsDialog(
            current = RouteSettingsValues(
                blockUnpaved = blockUnpaved,
                avoidMotorways = avoidMotorways,
                rideSuggestions = rideSuggestionsEnabled,
                factors = rideFactors,
            ),
            // Null until the first route loads the graph: then nothing is
            // claimed missing. A loaded map without a factor's data says so.
            graphFeatures = router.features,
            onDismiss = { routeSettingsOpen = false },
            onApply = { applied ->
                val roadsChanged = applied.blockUnpaved != blockUnpaved ||
                    applied.avoidMotorways != avoidMotorways ||
                    applied.factors != rideFactors
                blockUnpaved = applied.blockUnpaved
                avoidMotorways = applied.avoidMotorways
                includeElevation = applied.factors.elevation
                avoidStopsAndTowns = applied.factors.stopsAndTowns
                if (!applied.rideSuggestions && rideSuggestionsEnabled) navigationController.disableSuggestions()
                rideSuggestionsEnabled = applied.rideSuggestions
                routePreferences.edit()
                    .putBoolean(BLOCK_UNPAVED_PREF, applied.blockUnpaved)
                    .putBoolean(AVOID_MOTORWAYS_PREF, applied.avoidMotorways)
                    .putBoolean(RIDE_SUGGESTIONS_PREF, applied.rideSuggestions)
                    .putBoolean(INCLUDE_ELEVATION_PREF, applied.factors.elevation)
                    .putBoolean(AVOID_STOPS_AND_TOWNS_PREF, applied.factors.stopsAndTowns)
                    .apply()
                routeSettingsOpen = false
                // Only road-type and ride-factor changes alter the ride;
                // flipping suggestions mid-ride must never re-route.
                if (roadsChanged) activePlan?.let {
                    // Re-route the whole plan: GPX legs, round trip and
                    // pass-through stay, or Apply would silently discard them
                    // (a round trip without its length is a zero-length ride).
                    val params = it.withRouteSettings(
                        complexity = complexity.toDouble(),
                        blockUnpaved = applied.blockUnpaved,
                        avoidMotorways = applied.avoidMotorways,
                        factors = applied.factors,
                    )
                    submitRoute(
                        params.from,
                        params.to,
                        complexity,
                        params.blockUnpaved,
                        params.avoidMotorways,
                        viaPoints = params.viaPoints,
                        factors = params.factors,
                        roundTrip = params.roundTrip,
                        passThrough = params.passThrough,
                    )
                }
            },
        )
    }

    if (voiceSettingsOpen) {
        VoiceGuidanceSettingsDialog(
            current = voiceGuidanceSettings,
            unit = voiceUnit,
            speechStatus = voiceSpeechStatus,
            onDismiss = { voiceSettingsOpen = false },
            onApply = { settings ->
                voiceGuidanceEnabled = settings.enabled
                voiceIntervalMeters = settings.intervalMeters
                voicePreviewCount = settings.previewCount
                routePreferences.edit()
                    .putBoolean(VOICE_ENABLED_PREF, settings.enabled)
                    .putFloat(VOICE_INTERVAL_METERS_PREF, settings.intervalMeters.toFloat())
                    .putInt(VOICE_PREVIEW_COUNT_PREF, settings.previewCount)
                    .apply()
                // Applying while riding takes effect immediately; enabling
                // speech also previews the current next turns right away.
                if (guidanceRequested &&
                    (navSnapshot.state != NavigationState.Idle || !settings.enabled)
                ) {
                    voiceGuidanceOutput.onSnapshot(navSnapshot, settings)
                }
                voiceSettingsOpen = false
            },
        )
    }

    if (savedRoutesOpen) {
        SavedRoutesSheet(
            summariesProvider = { savedRouteRepository.summaries() },
            commentsProvider = { routeId -> savedRouteRepository.comments(routeId) },
            onAddComment = { routeId, text -> savedRouteRepository.addComment(routeId, text) },
            onLoadRoute = ::loadSavedRoute,
            onDeleteRoute = { saved ->
                savedRouteRepository.delete(saved.id)
                Log.i(TAG_SAVED, "Deleted saved route ${saved.id}")
            },
            onDismiss = { savedRoutesOpen = false },
        )
    }

    val previewRide = importedGpx
    if (gpxPreviewOpen && previewRide != null && previewRide.milestones.isNotEmpty()) {
        fun closeGpxPreview() {
            gpxPreviewOpen = false
            mapRef.value?.let { map ->
                map.hideGpxPreviewMarker()
                map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder(map.cameraPosition)
                            .padding(0.0, 0.0, 0.0, 0.0)
                            .build(),
                    ),
                )
                routeResult?.let {
                    map.fitBounds(it.routes, bottomInsetPx = mapCoveredBelowPx, rightInsetPx = controlsInsetPx, topInsetPx = fitTopInsetPx)
                }
            }
        }
        GpxRoutePreviewSheet(
            routeName = previewRide.name,
            milestones = previewRide.milestones,
            note = gpxPreviewNote(previewRide.plan),
            following = previewRide.plan.mode == GpxRideMode.FOLLOW_TRACK,
            onFocusMilestone = { milestone ->
                val map = mapRef.value ?: return@GpxRoutePreviewSheet
                map.showGpxPreviewMarker(milestone.point.lat, milestone.point.lon)
                val bottomPadding = mapView.height * GPX_PREVIEW_MAP_PADDING_FRACTION
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(milestone.point.lat, milestone.point.lon))
                            .zoom(14.5)
                            .padding(0.0, 0.0, 0.0, bottomPadding)
                            .build(),
                    ),
                    350,
                )
            },
            onDismiss = ::closeGpxPreview,
            // Edit is the planner's own EDIT (drop the import, back to From
            // and To); Ride starts guidance on the shown route, so it is
            // offered only once there is one.
            onEdit = {
                closeGpxPreview()
                clearImportedGpx()
                activePlan = null
                coordinator.invalidate()
            },
            onRide = if (routeResult != null && !guidanceRequested) {
                {
                    closeGpxPreview()
                    if (locationPermissionGranted) {
                        guidanceRequested = true
                    } else {
                        pendingGuidanceStart = true
                        permissionLauncher.launch(LocationPermission.requestedPermissions)
                    }
                    Log.i(TAG, "Guidance toggled: $guidanceRequested")
                }
            } else {
                null
            },
        )
    }

    if (mapUrl != null && mapImportError != null) {
        AlertDialog(
            onDismissRequest = { mapImportError = null },
            title = { Text("Map file not loaded") },
            text = { Text(mapImportError.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { mapImportError = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
            },
        )
    }
}

private fun coordinateLabel(point: GeoPoint): String =
    "%.5f, %.5f".format(Locale.US, point.lat, point.lon)

/**
 * Turns GraphHopper's routing errors into honest user-facing messages. Points
 * outside the active dataset are the expected failure when a saved ride or a
 * search result belongs to a region that is not installed; the message says so
 * instead of leaking the engine's internal wording.
 */
internal fun describeRoutingFailure(message: String): String {
    val lower = message.lowercase(Locale.US)
    return when {
        lower.contains("out of bounds") || lower.contains("outside of the") ->
            "That place is outside the active region. Install or activate a region that covers it."
        lower.contains("no route was found") ->
            "No route was found in the active region."
        lower.contains("no road") ->
            "No routable road was found near one of the points in the active region."
        else -> message
    }
}

private fun milestoneEndpointLabel(milestone: GpxMilestone): String =
    listOfNotNull(milestone.placeName, milestone.placeDetail?.takeIf(String::isNotBlank))
        .joinToString(", ")
        .ifBlank { coordinateLabel(milestone.point) }

/**
 * Camera zoom for the current riding speed, in discrete bands so the map
 * eases out as the rider speeds up and back in when they slow down. The
 * closer low-speed levels keep turns readable; the heading-up tilt shows a
 * useful distance ahead as the rider speeds up. Invalid or missing speed
 * (NaN, infinity, negative) reads as stationary, which is the closest band,
 * so a dropped speed sample can never zoom the rider out mid-corner.
 */
internal fun guidanceZoomFor(speedMps: Double): Double = when {
    !speedMps.isFinite() || speedMps < 7.0 -> 18.0
    speedMps < 14.0 -> 17.0
    speedMps < 22.0 -> 16.0
    speedMps < 31.0 -> 15.0
    else -> 14.0
}

/** Forward pitch the guidance view holds; the low rider stays at [GUIDANCE_RIDER_VERTICAL_FRACTION] of the map viewport. */
internal const val GUIDANCE_TILT_DEGREES = 58.0
internal const val GUIDANCE_RIDER_VERTICAL_FRACTION = 0.70
internal const val GUIDANCE_TOP_PADDING_FRACTION =
    2.0 * GUIDANCE_RIDER_VERTICAL_FRACTION - 1.0

/**
 * How long a queued debug fix owns guidance. The emulator's live provider
 * keeps replaying its parked position, so between runner injections it would
 * yank the camera off the injected path. Release builds never enable debug
 * logging and always follow live GPS.
 */
internal const val DEBUG_FIX_AUTHORITY_MS = 20_000L

internal fun debugFixOwnsGuidance(debugLogs: Boolean, sinceDebugFixMs: Long?): Boolean =
    debugLogs && sinceDebugFixMs != null && sinceDebugFixMs in 0..DEBUG_FIX_AUTHORITY_MS

/** Keeps the last usable course when GPS temporarily stops reporting heading. */
internal class GuidanceBearingTracker {
    private var lastReliableBearing: Double? = null

    fun resolve(incomingBearing: Double, cameraBearing: Double): Double {
        if (incomingBearing.isFinite()) {
            lastReliableBearing = normalizeBearing(incomingBearing)
        }
        return lastReliableBearing
            ?: normalizeBearing(cameraBearing.takeIf(Double::isFinite) ?: 0.0)
    }

    fun reset() {
        lastReliableBearing = null
    }
}

internal fun bearingDistanceDegrees(first: Double, second: Double): Double {
    if (!first.isFinite() || !second.isFinite()) return Double.POSITIVE_INFINITY
    val difference = (normalizeBearing(first) - normalizeBearing(second) + 540.0) % 360.0
    return kotlin.math.abs(difference - 180.0)
}

internal fun guidanceOrientationNeedsUpdate(
    cameraBearing: Double,
    targetBearing: Double,
    cameraTilt: Double,
): Boolean = bearingDistanceDegrees(cameraBearing, targetBearing) >= 6.0 ||
    !cameraTilt.isFinite() || kotlin.math.abs(cameraTilt - GUIDANCE_TILT_DEGREES) >= 1.0

/** Planner follow always returns a manually tilted/rotated map to north-up. */
internal fun planningOrientationNeedsUpdate(
    cameraBearing: Double,
    cameraTilt: Double,
): Boolean = bearingDistanceDegrees(cameraBearing, 0.0) >= 1.0 ||
    !cameraTilt.isFinite() || kotlin.math.abs(cameraTilt) >= 1.0

private fun normalizeBearing(bearing: Double): Double = ((bearing % 360.0) + 360.0) % 360.0

/**
 * Suppresses camera churn from stationary GPS jitter while retaining a
 * movement-dependent follow cadence. The distance is measured from the
 * camera's current target, so a moving camera does not restart an animation
 * for every small location update; [desiredZoom] still honors the caller's
 * current follow zoom or guidance speed band.
 */
internal fun shouldFollowCamera(
    cameraLat: Double,
    cameraLon: Double,
    cameraZoom: Double,
    targetLat: Double,
    targetLon: Double,
    desiredZoom: Double,
    minimumMovementMeters: Double,
): Boolean {
    if (!cameraLat.isFinite() || !cameraLon.isFinite() || !cameraZoom.isFinite() ||
        !targetLat.isFinite() || !targetLon.isFinite() || !desiredZoom.isFinite()
    ) {
        return true
    }
    val movement = RouteSimilarity.haversineMeters(
        GeoPoint(cameraLat, cameraLon),
        GeoPoint(targetLat, targetLon),
    )
    return movement >= minimumMovementMeters.coerceAtLeast(0.0) ||
        abs(cameraZoom - desiredZoom) >= CAMERA_ZOOM_FOLLOW_THRESHOLD
}

/** Stationary jitter needs to accumulate; actual motion gets a faster cadence. */
internal fun followMovementThresholdMeters(speedMps: Double): Double =
    if (speedMps.isFinite() && speedMps >= 1.0) {
        (speedMps * 0.5).coerceIn(MOVING_FOLLOW_MIN_METERS, MOVING_FOLLOW_MAX_METERS)
    } else {
        STATIONARY_FOLLOW_MIN_METERS
    }

private const val CAMERA_ZOOM_FOLLOW_THRESHOLD = 0.15
private const val STATIONARY_FOLLOW_MIN_METERS = 15.0
private const val MOVING_FOLLOW_MIN_METERS = 5.0
private const val MOVING_FOLLOW_MAX_METERS = 30.0

/** Fixed camera zoom the crosshair lock uses; 12.5 keeps several blocks of
 * street context visible while following. */
private const val FOLLOW_ZOOM = 12.5

/** Two resolved points within ~11 m count as the same place: routing them
 * yields a zero-edge path that cannot render as a line. */
private fun samePlace(a: GHPoint, b: GHPoint): Boolean =
    abs(a.lat - b.lat) < 1e-4 && abs(a.lon - b.lon) < 1e-4

/** Closed saved rides need shaping points or GraphHopper sees start-to-start. */
/** Where the imported GPX ride lives between launches (see [ImportedGpxStore]). */
internal const val IMPORTED_GPX_FILE = "imported-gpx/current.json"

/** What the preview sheet says about how the file is ridden, if anything. */
internal fun gpxPreviewNote(plan: GpxRidePlan): String? {
    val ignored = plan.ignoredWaypoints
    val how = when (plan.mode) {
        GpxRideMode.FOLLOW_TRACK -> "Follows the track road for road"
        GpxRideMode.STOPS -> "Routes between its ${GpxTrackFollow.stopPoints(plan).size} stops in your ride style"
    }
    return when {
        ignored == 1 -> "$how · 1 waypoint off the ride left out"
        ignored > 1 -> "$how · $ignored waypoints off the ride left out"
        else -> how
    }
}

internal fun savedRouteViaPoints(saved: SavedRoute): List<GHPoint> {
    val (from, to) = saved.endpoints
    if (!samePlace(GHPoint(from.lat, from.lon), GHPoint(to.lat, to.lon))) return emptyList()
    return GpxGeometry.routingPoints(saved.decodedPoints())
        .drop(1)
        .dropLast(1)
        .map { GHPoint(it.lat, it.lon) }
}

/**
 * Wraps the ride's map controls in the dark scheme (Ride/RideNight designs); the
 * planner's stay on the light one.
 */
@Composable
private fun MapControlsTheme(rideMode: Boolean, content: @Composable () -> Unit) {
    if (rideMode) CurveMapsDarkTheme(content) else content()
}

/**
 * Zoom in / zoom out as a connected vertical floating toolbar on the right edge
 * of the map: two 48 dp cells in a 56 dp wide pill (Planner/Ride designs). In a
 * ride it uses the dark scheme.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ZoomPill(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    rideMode: Boolean,
    modifier: Modifier = Modifier,
) {
    MapControlsTheme(rideMode) {
        val scheme = MaterialTheme.colorScheme
        val iconColor = if (rideMode) scheme.onSurface else scheme.onSurfaceVariant
        VerticalFloatingToolbar(
            expanded = true,
            modifier = modifier.width(MapControlsLayout.TOOLBAR_WIDTH),
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = scheme.surfaceContainer,
                toolbarContentColor = iconColor,
            ),
            contentPadding = PaddingValues(MapControlsLayout.TOOLBAR_PADDING),
            expandedShadowElevation = 6.dp,
        ) {
            IconButton(
                onClick = onZoomIn,
                modifier = Modifier
                    .requiredSize(MapControlsLayout.ZOOM_CELL)
                    .semantics { contentDescription = "Zoom in" },
            ) {
                Icon(painterResource(R.drawable.ms_add), contentDescription = null)
            }
            IconButton(
                onClick = onZoomOut,
                modifier = Modifier
                    .requiredSize(MapControlsLayout.ZOOM_CELL)
                    .semantics { contentDescription = "Zoom out" },
            ) {
                Icon(painterResource(R.drawable.ms_remove), contentDescription = null)
            }
        }
    }
}

/** The status bar's wash over the map: opaque-ish at the top edge, fading out below the bar. */
@Composable
private fun StatusBarScrim(dark: Boolean, modifier: Modifier = Modifier) {
    val height = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 14.dp
    val base = if (dark) Color.Black else Color.White
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(listOf(base.copy(alpha = if (dark) 0.8f else 0.6f), Color.Transparent)),
            ),
    )
}

/**
 * The compass: a 48 dp disc in the toolbars' `surfaceContainer` with the
 * `navigation` arrow pointing at north (Ride.dc.html), on the toolbar column's
 * axis. Tapping it turns the map north-up. Always shown in a ride; in the
 * planner only while the map is rotated.
 */
@Composable
private fun MapCompassButton(
    // Read here, not in RouteScreen: the bearing changes every camera frame.
    bearing: () -> Float,
    rideMode: Boolean,
    alwaysVisible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bearingDegrees = bearing()
    val rotated = abs(((bearingDegrees + 180f) % 360f + 360f) % 360f - 180f) > 0.5f
    AnimatedVisibility(
        visible = alwaysVisible || rotated,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        MapControlsTheme(rideMode) {
            val scheme = MaterialTheme.colorScheme
            val description = if (rotated) {
                "Compass, map rotated ${bearingDegrees.roundToInt()} degrees; tap for north up"
            } else {
                "Compass, north up"
            }
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = scheme.surfaceContainer,
                contentColor = scheme.onSurface,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(MapControlsLayout.COMPASS_SIZE)
                    .semantics { contentDescription = description },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        painter = painterResource(R.drawable.ms_navigation_fill),
                        contentDescription = null,
                        tint = scheme.error,
                        modifier = Modifier.rotate(-bearingDegrees),
                    )
                }
            }
        }
    }
}

/**
 * The Centre on me / Follow rider control: an M3 FAB (56 dp, `surfaceContainerHigh`)
 * that toggles camera lock to the rider. Planning keeps its selected zoom;
 * guidance restores its speed-based framing when the lock returns. Locked, the
 * `my_location` glyph is filled and in the primary colour (neutral `onSurface`
 * over the B&W map); released, it is outlined in `onSurfaceVariant`. The
 * description carries the state in words as well.
 */
@Composable
private fun CentreOnMeButton(
    rideMode: Boolean,
    monochrome: Boolean,
    active: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MapControlsTheme(rideMode) {
        val scheme = MaterialTheme.colorScheme
        val lockedColor = if (monochrome) scheme.onSurface else scheme.primary
        FloatingActionButton(
            onClick = onClick,
            containerColor = scheme.surfaceContainerHigh,
            contentColor = if (active) lockedColor else scheme.onSurfaceVariant,
            modifier = modifier
                .size(MapControlsLayout.CENTRE_BUTTON_SIZE)
                .semantics { contentDescription = description },
        ) {
            Icon(
                painter = painterResource(if (active) R.drawable.ms_my_location_fill else R.drawable.ms_my_location),
                contentDescription = null,
            )
        }
    }
}

/**
 * The map credits as a compact one-line chip (Planner.dc.html), shown for the
 * first seconds after launch; the settings menu and MapLibre's own attribution
 * button keep the full credits. Non-interactive. Its accessibility text is the
 * full [MAP_CREDITS] line (the node tests and TalkBack read), the visible one
 * the short form.
 */
@Composable
private fun MapCreditsChip(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.clearAndSetSemantics { text = AnnotatedString(MAP_CREDITS) },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Icon(painterResource(R.drawable.ms_info), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = MAP_CREDITS_SHORT,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * First-run state with no map installed (MapNeeded design): a modal M3
 * [AlertDialog] over a scrim. "Choose map file" is the primary action; "Open
 * Maps" (a text button) appears only when the build ships no data of its own.
 */
@Composable
private fun MissingMapDialog(
    importing: Boolean,
    error: String?,
    dataMissing: Boolean,
    onChooseFile: () -> Unit,
    onOpenMaps: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // MapNeeded.dc.html: 32 dp side margins (348 dp on a 412 dp phone), up to
        // the M3 dialog's 560 dp maximum, instead of the platform's narrow default.
        modifier = Modifier.padding(horizontal = 32.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        icon = { Icon(painterResource(R.drawable.ms_map), contentDescription = null) },
        title = { Text(if (dataMissing) "Map package needed" else "Offline map file needed") },
        text = { Text(error ?: if (dataMissing) MAP_PACKAGE_NEEDED_MESSAGE else MAP_FILE_NEEDED_MESSAGE) },
        confirmButton = {
            Button(
                onClick = onChooseFile,
                enabled = !importing,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (importing) "Importing…" else "Choose map file")
            }
        },
        dismissButton = if (dataMissing) {
            {
                TextButton(
                    onClick = onOpenMaps,
                    enabled = !importing,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Open Maps")
                }
            }
        } else {
            null
        },
    )
}

internal const val NO_MAP_INSTALLED_MESSAGE =
    "No map is installed. Load a .motomap package from the settings menu (Load map file or Maps)."
internal const val MAP_PACKAGE_NEEDED_MESSAGE =
    "This app doesn't include map data. Choose a .motomap package — map, routing and " +
        "place search in one file — or download one in Maps."
internal const val MAP_FILE_NEEDED_MESSAGE =
    "Choose a .motomap package, or a .pmtiles map file, stored on this phone."
internal const val BASEMAP_ONLY_MESSAGE =
    "That file is a map picture only (.pmtiles), with no routing or search data, and this app " +
        "has none of its own. Choose a .motomap package instead."
internal const val UNKNOWN_MAP_FILE_MESSAGE =
    "That isn't a map file this app can read. Choose a .motomap package or a .pmtiles map."

@Composable
private fun rememberMapView(
    context: Context,
    onMapReady: (MapLibreMap) -> Unit
): MapView {
    MapLibre.getInstance(context)

    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }

    DisposableEffect(lifecycleOwner, mapView) {
        var destroyedByLifecycle = false
        val observer = LifecycleEventObserver { _, event ->
            if (isDebugBuild(context.applicationContext)) {
                Log.d(TAG, "MapView lifecycle event=$event state=${lifecycleOwner.lifecycle.currentState}")
            }
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    destroyedByLifecycle = true
                    mapView.onDestroy()
                }
                else -> Unit
            }
        }
        if (isDebugBuild(context.applicationContext)) {
            Log.d(TAG, "Registering MapView lifecycle observer at ${lifecycleOwner.lifecycle.currentState}")
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.getMapAsync { map ->
            map.uiSettings.isTiltGesturesEnabled = true
            onMapReady(map)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!destroyedByLifecycle) mapView.onDestroy()
        }
    }
    return mapView
}

private fun OfflineSpeechStatus.hudDescription(): String = when (this) {
    OfflineSpeechStatus.NotStarted -> "offline voice not checked yet"
    OfflineSpeechStatus.Checking -> "checking for English offline voice"
    is OfflineSpeechStatus.Ready -> "English offline voice ready"
    is OfflineSpeechStatus.Unavailable -> "English offline voice unavailable"
}

