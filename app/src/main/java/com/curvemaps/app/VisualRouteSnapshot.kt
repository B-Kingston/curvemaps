package com.curvemaps.app

import com.graphhopper.ResponsePath
import com.curvemaps.app.routing.navigation.NavigationSnapshot
import com.curvemaps.app.routing.navigation.RouteTrackFactory
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import java.io.File

/**
 * Exports the current debug-build route geometry for the ADB visual runner.
 * Release builds never write this cache file. Keeping all alternatives lets
 * the runner advance the same path the rider selected in the carousel.
 */
internal object VisualRouteSnapshot {

    const val FILE_NAME = "visual-route.json"
    const val CAMERA_FILE_NAME = "visual-camera.json"
    const val GUIDANCE_FILE_NAME = "visual-guidance.json"
    const val FIX_ACTION = "com.curvemaps.app.DEBUG_VISUAL_ROUTE_FIX"
    const val CAMERA_ACTION = "com.curvemaps.app.DEBUG_VISUAL_CAMERA"
    const val MAP_PROBE_ACTION = "com.curvemaps.app.DEBUG_VISUAL_MAP_PROBE"
    private const val PENDING_CAMERA_FILE = "visual-camera-request.json"
    private const val PENDING_MAP_PROBE_FILE = "visual-map-probe-request.json"

    fun queueMapProbe(cacheDir: File, id: Long) {
        writeAtomically(File(cacheDir, PENDING_MAP_PROBE_FILE), id.toString())
    }

    fun takeQueuedMapProbe(cacheDir: File): Long? {
        val file = File(cacheDir, PENDING_MAP_PROBE_FILE)
        if (!file.isFile) return null
        return runCatching { file.readText().toLongOrNull() }.getOrNull().also { file.delete() }
    }

    fun writeMapProbe(cacheDir: File, id: Long, dark: Boolean, basemap: Int, routeReady: Boolean, rider: Int) {
        writeAtomically(File(cacheDir, "visual-map-probe.json"), JSONObject()
            .put("requestId", id).put("dark", dark).put("basemapFeatures", basemap)
            .put("routeReady", routeReady).put("riderFeatures", rider).toString())
    }

    data class CameraRequest(val requestId: Long, val camera: CameraPosition)

    fun queueCamera(cacheDir: File, id: Long, lat: Double, lon: Double, zoom: Double, tilt: Double, bearing: Double) {
        writeAtomically(File(cacheDir, PENDING_CAMERA_FILE), JSONObject()
            .put("requestId", id).put("lat", lat).put("lon", lon)
            .put("zoom", zoom).put("tilt", tilt).put("bearing", bearing).toString())
    }

    fun takeQueuedCamera(cacheDir: File): CameraRequest? {
        val file = File(cacheDir, PENDING_CAMERA_FILE)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            CameraRequest(json.getLong("requestId"), CameraPosition.Builder()
                .target(org.maplibre.android.geometry.LatLng(json.getDouble("lat"), json.getDouble("lon")))
                .zoom(json.getDouble("zoom")).tilt(json.getDouble("tilt"))
                .bearing(json.getDouble("bearing")).padding(0.0, 0.0, 0.0, 0.0).build())
        }.getOrNull().also { file.delete() }
    }

    private const val PENDING_FIX_FILE = "visual-fix.json"
    private const val FIX_ACK_FILE = "visual-fix-ack.json"

    data class FixRequest(
        val requestId: Long,
        val lat: Double,
        val lon: Double,
        /**
         * Speed the runner wants the guidance camera to see, or null for a
         * stationary teleport (the default that keeps progress runs honest).
         */
        val speedMps: Double?,
        /** Optional navigation clock for repeatable pace replay; never used by location tracking. */
        val timestampMs: Long? = null,
        /**
         * True when the runner is simulating a GPS gap after this fix: guidance
         * may then estimate along the route once injections stop. Ordinary
         * queued fixes keep estimation off so captures stay still.
         */
        val allowEstimate: Boolean = false,
        /** Horizontal accuracy for a precise or coarse location replay. */
        val accuracyM: Double = 1.0,
    )

    fun write(cacheDir: File, routes: List<ResponsePath>, selectedIndex: Int) {
        if (routes.isEmpty() || selectedIndex !in routes.indices) return
        val routeArray = JSONArray()
        routes.forEachIndexed { index, path ->
            val track = RouteTrackFactory.fromPath(path)
            val pointArray = JSONArray()
            for (vertex in track.latitudes.indices) {
                pointArray.put(JSONArray().put(track.latitudes[vertex]).put(track.longitudes[vertex]))
            }
            routeArray.put(
                JSONObject()
                    .put("index", index)
                    .put("points", pointArray)
                    .put("cumulativeDistanceM", JSONArray(track.cumulativeDistanceM.toList()))
                    .put("cumulativeTimeS", JSONArray(track.cumulativeTimeS.toList())),
            )
        }
        val snapshot = JSONObject()
            .put("version", 1)
            .put("selectedIndex", selectedIndex)
            .put("createdAtMillis", System.currentTimeMillis())
            .put("routes", routeArray)

        val output = File(cacheDir, FILE_NAME)
        val temporary = File(cacheDir, FILE_NAME + ".tmp")
        temporary.writeText(snapshot.toString())
        if (!temporary.renameTo(output)) {
            output.writeText(snapshot.toString())
            temporary.delete()
        }
    }

    fun clear(cacheDir: File) {
        File(cacheDir, FILE_NAME).delete()
        File(cacheDir, FILE_NAME + ".tmp").delete()
    }

    /** Called by the debug-only exported receiver used by tools/test/visual.py. */
    fun queueFix(
        cacheDir: File,
        requestId: Long,
        lat: Double,
        lon: Double,
        speedMps: Double?,
        timestampMs: Long? = null,
        allowEstimate: Boolean = false,
        accuracyM: Double = 1.0,
    ) {
        val json = JSONObject()
            .put("requestId", requestId)
            .put("lat", lat)
            .put("lon", lon)
            .put("allowEstimate", allowEstimate)
            .put("accuracyM", accuracyM)
        speedMps?.let { json.put("speedMps", it) }
        timestampMs?.let { json.put("timestampMs", it) }
        writeAtomically(File(cacheDir, PENDING_FIX_FILE), json.toString())
    }

    /** Takes at most one queued host request; the runner waits for its matching ack. */
    fun takeQueuedFix(cacheDir: File): FixRequest? {
        val file = File(cacheDir, PENDING_FIX_FILE)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            FixRequest(
                requestId = json.getLong("requestId"),
                lat = json.getDouble("lat"),
                lon = json.getDouble("lon"),
                speedMps = if (json.has("speedMps")) json.getDouble("speedMps") else null,
                timestampMs = if (json.has("timestampMs")) json.getLong("timestampMs") else null,
                allowEstimate = json.optBoolean("allowEstimate", false),
                accuracyM = json.optDouble("accuracyM", 1.0),
            )
        }.getOrNull().also {
            file.delete()
        }
    }

    /**
     * Publishes the settled camera for tools/test/visual.py so the guidance
     * framing (zoom band, forward tilt, heading) and the planning-camera
     * restore after END can be checked instead of only eyeballed.
     */
    fun writeCamera(
        cacheDir: File,
        camera: CameraPosition,
        guidance: Boolean,
        followSuspended: Boolean = false,
        cameraLocked: Boolean = false,
    ) {
        runCatching {
            val target = camera.target
            val json = JSONObject()
                .put("version", 1)
                .put("zoom", camera.zoom.takeIf { it.isFinite() } ?: 0.0)
                .put("tilt", camera.tilt.takeIf { it.isFinite() } ?: 0.0)
                .put("bearing", camera.bearing.takeIf { it.isFinite() } ?: 0.0)
                .put("guidance", guidance)
                .put("followSuspended", followSuspended)
                .put("cameraLocked", cameraLocked)
                .put("createdAtMillis", System.currentTimeMillis())
            if (target != null) {
                json.put("lat", target.latitude).put("lon", target.longitude)
            }
            camera.padding?.let { padding ->
                if (padding.size >= 4 && padding.all { it.isFinite() }) {
                    json.put("paddingTop", padding[1])
                        .put("paddingLeft", padding[0])
                        .put("paddingRight", padding[2])
                        .put("paddingBottom", padding[3])
                }
            }
            writeAtomically(File(cacheDir, CAMERA_FILE_NAME), json.toString())
        }
    }

    /**
     * Publishes the live guidance position for tools/test/visual.py, so a
     * GPS-gap run can watch the estimate advance and the recovery glide land
     * without injecting fixes of its own.
     */
    fun writeGuidance(cacheDir: File, snapshot: NavigationSnapshot) {
        runCatching {
            val json = JSONObject()
                .put("state", snapshot.state.name)
                .put("positionSource", snapshot.positionSource.name.lowercase())
                .put("lat", snapshot.lat.takeIf { it.isFinite() } ?: JSONObject.NULL)
                .put("lon", snapshot.lon.takeIf { it.isFinite() } ?: JSONObject.NULL)
                .put("remainingDistanceM", snapshot.remainingDistanceM)
                .put("turnDistanceM", snapshot.turn?.distanceM ?: JSONObject.NULL)
                .put("speedMps", snapshot.speedMps.takeIf { it.isFinite() } ?: JSONObject.NULL)
                .put("createdAtMillis", System.currentTimeMillis())
            writeAtomically(File(cacheDir, GUIDANCE_FILE_NAME), json.toString())
        }
    }

    fun writeSuggestion(cacheDir: File, state: com.curvemaps.app.routing.suggestion.RideSuggestionState) {
        val json = JSONObject().put("state", state.semanticsValue)
        if (state is com.curvemaps.app.routing.suggestion.RideSuggestionState.Pending) {
            val proposal = state.proposal
            val track = proposal.replacement
            val points = org.json.JSONArray()
            for (v in 0 until track.vertexCount) {
                points.put(org.json.JSONArray().put(track.latitudes[v]).put(track.longitudes[v]))
            }
            json.put("points", points)
                .put("cumulativeDistanceM", org.json.JSONArray(track.cumulativeDistanceM.toList()))
                .put("divergeM", proposal.span.divergeReplacementOffsetM)
                .put("baselineOffsetM", proposal.baselineOffsetAtProposalM)
                .put("extraTimeS", proposal.measure.extraTimeS)
                .put("extraDistanceM", proposal.measure.extraDistanceM)
                .put("curvyGainM", proposal.measure.curvyGainM)
        }
        writeAtomically(File(cacheDir, "visual-suggestion.json"), json.toString())
    }

    fun acknowledgeFix(cacheDir: File, requestId: Long, snapshot: NavigationSnapshot? = null, suggestionCheckInFlight: Boolean = false) {
        val json = JSONObject().put("requestId", requestId).put("suggestionCheckInFlight", suggestionCheckInFlight)
        snapshot?.let {
            json.put("paceDeltaS", it.paceDeltaS.takeIf { delta -> delta.isFinite() } ?: JSONObject.NULL)
                .put("state", it.state.name)
                .put("remainingDistanceM", it.remainingDistanceM)
                .put("positionSource", it.positionSource.name.lowercase())
        }
        writeAtomically(
            File(cacheDir, FIX_ACK_FILE),
            json.toString(),
        )
    }

    private fun writeAtomically(output: File, content: String) {
        val temporary = File(output.parentFile, output.name + ".tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(output)) {
            output.writeText(content)
            temporary.delete()
        }
    }
}
