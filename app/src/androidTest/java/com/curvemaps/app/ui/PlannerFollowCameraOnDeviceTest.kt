package com.curvemaps.app.ui

import android.Manifest
import android.os.SystemClock
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.FollowCameraLockedKey
import com.curvemaps.app.FollowCameraSuspendedKey
import com.curvemaps.app.MainActivity
import com.curvemaps.app.MapLoadFailureKey
import com.curvemaps.app.MapStyleLoadErrorKey
import com.curvemaps.app.MapStyleMatchesRequestKey
import com.curvemaps.app.VisualRouteSnapshot
import com.curvemaps.app.map.OfflineBasemapRule
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Planner follow camera restores the map to its north-up, flat framing. */
@RunWith(AndroidJUnit4::class)
class PlannerFollowCameraOnDeviceTest {

    private val offlineBasemap = OfflineBasemapRule()
    private val permission = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(offlineBasemap)
        .around(permission)
        .around(compose)

    @Before
    fun dismissStartupSetup() = compose.dismissMediaStartupPrompt()

    @Test
    fun plannerFollowWaitsThenRestoresFlatNorthUpCamera() {
        awaitMapPrerequisites()
        val initialCamera = waitForCamera("initial planner camera finishes GPS centering") { probe ->
            !probe.optBoolean("guidance") && !probe.optBoolean("cameraLocked") &&
                probe.optDouble("lat", Double.NaN).isFinite() &&
                probe.optDouble("lon", Double.NaN).isFinite() &&
                abs(probe.optDouble("zoom", Double.NEGATIVE_INFINITY) - PLANNER_FOLLOW_ZOOM) <= 0.25
        }
        val rider = initialCamera.optDouble("lat") to initialCamera.optDouble("lon")
        compose.onNodeWithContentDescription("Centre on me").performClick()
        compose.waitUntil(10_000) { hasValue(FollowCameraLockedKey, true) }
        val centered = waitForCamera("planner camera at injected rider") { probe ->
            !probe.optBoolean("guidance") && probe.optBoolean("cameraLocked") &&
                cameraNear(probe, rider.first, rider.second) &&
                abs(probe.optDouble("zoom", Double.NEGATIVE_INFINITY) - initialCamera.optDouble("zoom")) <= 0.25 &&
                abs(probe.optDouble("tilt", Double.POSITIVE_INFINITY)) <= 1.0 &&
                abs(probe.optDouble("bearing", Double.POSITIVE_INFINITY)) <= 1.0
        }

        // Keep Compose's time controlled so the cooldown can be observed on
        // wall time without an idle synchronization consuming its deadline.
        val autoAdvanceBeforeGesture = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        try {
            var direction = true
            var tilted = performShove(direction, centered.optLong("createdAtMillis"))
            if (abs(tilted.optDouble("tilt", 0.0)) < 2.0) {
                direction = false
                tilted = performShove(direction, tilted.optLong("createdAtMillis"))
            }
            assertTrue(
                "two-finger shove must tilt the planner camera; before=$centered after=$tilted",
                abs(tilted.optDouble("tilt", 0.0)) >= 2.0,
            )
            assertTrue("the planner lock stays on during map inspection", hasValue(FollowCameraLockedKey, true))

            Thread.sleep(700L)
            val beforeExpiry = readCamera() ?: error("planner camera probe disappeared before cooldown expiry")
            assertTrue("follow stays suspended during the grace period", beforeExpiry.optBoolean("followSuspended"))
            assertTrue(
                "planner must retain the rider's inspection angle before expiry",
                abs(beforeExpiry.optDouble("tilt", 0.0)) >= 2.0,
            )

            compose.mainClock.advanceTimeBy(FOLLOW_RESUME_DELAY_MS + 100L)
            val restored = pollCamera(10_000L) { probe ->
                !probe.optBoolean("followSuspended") && !probe.optBoolean("guidance") &&
                    probe.optBoolean("cameraLocked") &&
                    abs(probe.optDouble("tilt", Double.POSITIVE_INFINITY)) <= 1.0 &&
                    abs(probe.optDouble("bearing", Double.POSITIVE_INFINITY)) <= 1.0
            }
            assertTrue(
                "planner follow must return north-up and flat after the grace period; " +
                    "before=$beforeExpiry latest=${readCamera()}",
                restored != null,
            )
        } finally {
            compose.mainClock.autoAdvance = autoAdvanceBeforeGesture
        }
    }

    /**
     * A shown route owns the planner camera: framing it releases the follow
     * lock, so fixes far from the route (here: Cairns) never fly the camera
     * back to the rider until Centre on me is tapped again.
     */
    @Test(timeout = 180_000)
    fun shownRouteKeepsTheCameraUntilCentreOnMe() {
        awaitMapPrerequisites()
        waitForCamera("initial planner camera settles") { probe ->
            !probe.optBoolean("guidance") && probe.optDouble("lat", Double.NaN).isFinite()
        }
        compose.onNodeWithContentDescription("Centre on me").performClick()
        compose.waitUntil(10_000) { hasValue(FollowCameraLockedKey, true) }

        PlannerFlow.searchRideStyles(compose)
        compose.waitUntil(10_000) { hasValue(FollowCameraLockedKey, false) }
        pollCamera(15_000L) { probe -> !probe.optBoolean("cameraLocked") && overRoute(probe) }
            ?: error("the camera never framed the route; latest=${readCamera()}")
        // The framing is an animation: compare against the camera once it has
        // come to rest, not a frame captured while it is still flying in.
        val framed = awaitSettled(15_000L)
            ?: error("the camera never settled on the route; latest=${readCamera()}")

        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val deadline = SystemClock.elapsedRealtime() + ROUTE_HOLD_MS
        var requestId = 9_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            VisualRouteSnapshot.queueFix(cache, requestId++, CAIRNS_LAT, CAIRNS_LON, speedMps = null)
            Thread.sleep(400L)
            val probe = readCamera() ?: continue
            assertTrue(
                "a fix away from the route must not move the camera off it; framed=$framed latest=$probe",
                overRoute(probe) && abs(probe.optDouble("zoom") - framed.optDouble("zoom")) <= 0.25,
            )
        }
        assertTrue("the lock stays off until the rider asks", hasValue(FollowCameraLockedKey, false))

        compose.onNodeWithContentDescription("Centre on me").performClick()
        compose.waitUntil(10_000) { hasValue(FollowCameraLockedKey, true) }
    }

    @Test(timeout = 180_000)
    fun nativeMapStyleReloadsAfterActivityRecreation() {
        awaitMapPrerequisites()

        compose.activityRule.scenario.recreate()
        compose.dismissMediaStartupPrompt()
        CameraMapPrerequisites.awaitReady(compose) { readCamera()?.toString() }

        compose.onNode(
            SemanticsMatcher.expectValue(MapStyleMatchesRequestKey, true),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNode(
            SemanticsMatcher.expectValue(MapLoadFailureKey, ""),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNode(
            SemanticsMatcher.expectValue(MapStyleLoadErrorKey, ""),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithText("Map file not loaded").assertDoesNotExist()
    }

    private fun performShove(increase: Boolean, previousSnapshotAt: Long): JSONObject {
        MapGestureRobot.startTwoFingerTilt(compose, increase)
        compose.mainClock.advanceTimeByFrame()
        val lockWhileHeld = hasValue(FollowCameraLockedKey, true)
        val pauseWhileHeld = hasValue(FollowCameraSuspendedKey, true)
        MapGestureRobot.finishTwoFingerTilt(compose, increase)
        compose.mainClock.advanceTimeByFrame()
        val tilted = pollCamera(10_000L) { probe ->
            probe.optLong("createdAtMillis") > previousSnapshotAt && probe.optBoolean("followSuspended")
        }
        return tilted ?: error(
            "MapLibre did not publish a paused planner camera after the shove; " +
                "lockWhileHeld=$lockWhileHeld pauseWhileHeld=$pauseWhileHeld latest=${readCamera()}",
        )
    }

    private fun awaitMapPrerequisites() = CameraMapPrerequisites.awaitReady(compose) {
        readCamera()?.toString()
    }

    private fun waitForCamera(description: String, predicate: (JSONObject) -> Boolean): JSONObject {
        compose.waitUntil(15_000) { readCamera()?.let(predicate) == true }
        return readCamera() ?: error("$description: camera probe is missing")
    }

    private fun pollCamera(timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val probe = readCamera()
            if (probe != null && predicate(probe)) return probe
            Thread.sleep(40L)
        }
        return null
    }

    /** The camera once two probes 400 ms apart agree on target and zoom (an animation finished). */
    private fun awaitSettled(timeoutMs: Long): JSONObject? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var previous = readCamera()
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(400L)
            val current = readCamera() ?: continue
            val before = previous
            if (before != null &&
                abs(current.optDouble("zoom") - before.optDouble("zoom")) < 0.01 &&
                abs(current.optDouble("lat") - before.optDouble("lat")) < 1e-5 &&
                abs(current.optDouble("lon") - before.optDouble("lon")) < 1e-5 &&
                !current.optBoolean("cameraLocked") && overRoute(current)
            ) {
                return current
            }
            previous = current
        }
        return null
    }

    private fun readCamera(): JSONObject? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, VisualRouteSnapshot.CAMERA_FILE_NAME)
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun cameraNear(probe: JSONObject, lat: Double, lon: Double): Boolean {
        val cameraLat = probe.optDouble("lat", Double.NaN)
        val cameraLon = probe.optDouble("lon", Double.NaN)
        if (!cameraLat.isFinite() || !cameraLon.isFinite()) return false
        val northMeters = Math.toRadians(cameraLat - lat) * EARTH_RADIUS_METERS
        val eastMeters = Math.toRadians(cameraLon - lon) * EARTH_RADIUS_METERS *
            cos(Math.toRadians((cameraLat + lat) / 2.0))
        return hypot(northMeters, eastMeters) <= CAMERA_TARGET_TOLERANCE_METERS
    }

    /** Camera target inside the padded bounds of PlannerFlow's Brisbane → Samford route. */
    private fun overRoute(probe: JSONObject): Boolean {
        val lat = probe.optDouble("lat", Double.NaN)
        val lon = probe.optDouble("lon", Double.NaN)
        return lat in ROUTE_SOUTH..ROUTE_NORTH && lon in ROUTE_WEST..ROUTE_EAST
    }

    private fun hasValue(key: androidx.compose.ui.semantics.SemanticsPropertyKey<Boolean>, value: Boolean): Boolean =
        compose.onAllNodes(SemanticsMatcher.expectValue(key, value), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
        const val CAMERA_TARGET_TOLERANCE_METERS = 30.0
        const val PLANNER_FOLLOW_ZOOM = 12.5
        const val FOLLOW_RESUME_DELAY_MS = 1_500L
        const val ROUTE_HOLD_MS = 4_000L
        const val CAIRNS_LAT = -16.9203
        const val CAIRNS_LON = 145.7710
        const val ROUTE_SOUTH = -27.55
        const val ROUTE_NORTH = -27.25
        const val ROUTE_WEST = 152.70
        const val ROUTE_EAST = 153.10
    }
}
