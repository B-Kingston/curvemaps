package com.curvemaps.app.media

import android.app.Activity
import android.app.Application
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.curvemaps.app.MainActivity
import com.curvemaps.app.RidePictureInPicture
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MusicAppOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val component = ComponentName(context, VisualMusicBrowserService::class.java)
    private val playerScreen = ComponentName(context, VisualMusicPlayerActivity::class.java)
    private val buttonReceiver = ComponentName(context, VisualMusicButtonReceiver::class.java)
    private val prefs = context.getSharedPreferences("visual_music", Context.MODE_PRIVATE)
    private lateinit var gateway: AndroidMusicAppGateway
    private var originalEnabled = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    private var originalPlayerScreen = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    private var originalButton = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    private var originalAccess = false
    @Volatile private var rideResumed = false
    @Volatile private var rideLeft = false
    @Volatile private var rideReturned = false
    private val rideTracker = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity !is MainActivity) return
            rideResumed = true
            if (rideLeft) rideReturned = true
        }
        override fun onActivityPaused(activity: Activity) {
            if (activity is MainActivity) { rideResumed = false; rideLeft = true }
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    @Before fun setUp() {
        originalEnabled = context.packageManager.getComponentEnabledSetting(component)
        originalPlayerScreen = context.packageManager.getComponentEnabledSetting(playerScreen)
        originalButton = context.packageManager.getComponentEnabledSetting(buttonReceiver)
        originalAccess = NotificationListenerAccess.isGranted(context)
        context.packageManager.setComponentEnabledSetting(component,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        prefs.edit().clear().commit()
        instrumentation.runOnMainSync { gateway = AndroidMusicAppGateway(context) }
    }
    @After fun tearDown() {
        instrumentation.runOnMainSync { gateway.cancel() }
        context.stopService(android.content.Intent().setComponent(component))
        context.packageManager.setComponentEnabledSetting(component, originalEnabled, PackageManager.DONT_KILL_APP)
        context.packageManager.setComponentEnabledSetting(playerScreen, originalPlayerScreen, PackageManager.DONT_KILL_APP)
        context.packageManager.setComponentEnabledSetting(buttonReceiver, originalButton, PackageManager.DONT_KILL_APP)
        (context.applicationContext as Application).unregisterActivityLifecycleCallbacks(rideTracker)
        NotificationListenerAccess.setGranted(context, originalAccess)
        prefs.edit().clear().commit()
    }
    @Test fun discoversStoppedPlayerAndActuallyDeliversColdPlayback() {
        assertTrue(gateway.apps().any { it.packageName == context.packageName })
        assertFalse(prefs.getBoolean("playing", false))
        val result = start()
        assertEquals(MusicStartResult.REQUESTED, result)
        val until = System.currentTimeMillis() + 5_000
        while (!prefs.getBoolean("playing", false) && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue("play must reach the cold service, not just return accepted", prefs.getBoolean("playing", false))
    }
    @Test fun rejectedBrowserOpensPlayerWithoutClaimingPlayback() {
        prefs.edit().putBoolean("reject", true).commit()
        assertEquals(MusicStartResult.OPENED, start())
        assertFalse(prefs.getBoolean("playing", false))
    }
    @Test fun backgroundStartIsTriedBeforeThePlayersOwnScreen() {
        enablePlayerScreen()
        assertEquals(MusicStartResult.REQUESTED, start())
        assertTrue(waitFor(5_000) { prefs.getBoolean("playing", false) })
        assertFalse("an accepting player must not take over the screen", prefs.getBoolean("foregroundPlay", false))
    }
    // Proves the return watch fires. Instrumentation and the same-UID fixture
    // player may start activities from the background, so it cannot prove
    // that Android lets the return through on a phone (it does not, there).
    @Test fun refusingPlayerStartsOnItsOwnScreenAndReturnsToTheRideOncePlaying() {
        assertTrue(NotificationListenerAccess.setGranted(context, true))
        val scenario = launchRide()
        try {
            assertEquals(MusicStartResult.REQUESTED, start())
            assertTrue("the player's screen must start playback",
                waitFor(5_000) { prefs.getBoolean("foregroundPlay", false) && prefs.getBoolean("playing", false) })
            assertTrue("the ride must actually leave the front", waitFor(5_000) { rideLeft })
            assertTrue("the ride must come back once playback is observed", waitFor(ReturnToRideWindowMillis) { rideReturned })
        } finally { scenario.close() }
    }
    @Test fun playerScreenOpensOverTheRideShrunkToPictureInPicture() {
        assertTrue(NotificationListenerAccess.setGranted(context, true))
        assertTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
        val scenario = launchRide()
        try {
            lateinit var ride: MainActivity
            scenario.onActivity { ride = it }
            // Playback starts 2 s after the player's screen opens. Until then
            // nothing may pull the ride back, so the window is observed
            // without a race. (Instrumentation may start activities from the
            // background, so this test cannot prove the return itself.)
            prefs.edit().putLong("playDelayMs", 2_000L).commit()
            instrumentation.runOnMainSync {
                gateway = AndroidMusicAppGateway(
                    context,
                    rideOnTop = { rideResumed && !ride.isInPictureInPictureMode },
                    beforeLeavingRide = { RidePictureInPicture.enter(ride) },
                )
            }
            assertEquals(MusicStartResult.REQUESTED, start())
            assertTrue("the ride must shrink to its window", waitFor(5_000) { onMain { ride.isInPictureInPictureMode } })
            assertTrue("the player's screen must open over it", waitFor(5_000) { prefs.getBoolean("foregroundPlay", false) })
            assertTrue("the ride stays pinned and alive under the player",
                onMain { ride.isInPictureInPictureMode && !ride.isFinishing } && !prefs.getBoolean("playing", false))
        } finally { scenario.close() }
    }
    @Test fun withoutSessionAccessTheRideIsNeverPulledBackBlindly() {
        assertTrue(NotificationListenerAccess.setGranted(context, false))
        val scenario = launchRide()
        try {
            assertEquals(MusicStartResult.REQUESTED, start())
            assertTrue(waitFor(5_000) { prefs.getBoolean("playing", false) && rideLeft })
            Thread.sleep(2_000)
            assertFalse(rideReturned)
        } finally { scenario.close() }
    }
    @Test fun refusingPlayerStartsFromOnePlayPressWithoutLeavingTheRide() {
        assertTrue(NotificationListenerAccess.setGranted(context, true))
        val scenario = launchRide()
        try {
            enableButtonReceiver()
            assertEquals(MusicStartResult.REQUESTED, start())
            assertTrue("the press must start playback", prefs.getBoolean("buttonPlay", false) && prefs.getBoolean("playing", false))
            assertEquals(1, prefs.getInt("buttonPresses", 0))
            Thread.sleep(1_000)
            assertFalse("a working press must not open the player's screen", prefs.getBoolean("foregroundPlay", false))
            assertFalse("the ride must stay in front", rideLeft)
        } finally { scenario.close() }
    }
    @Test fun silentMediaButtonFallsBackToThePlayersOwnScreen() {
        assertTrue(NotificationListenerAccess.setGranted(context, true))
        val scenario = launchRide()
        try {
            enableButtonReceiver()
            prefs.edit().putBoolean("ignoreButton", true).commit()
            assertEquals(MusicStartResult.REQUESTED, start())
            assertEquals("exactly one press, never a retry", 1, prefs.getInt("buttonPresses", 0))
            assertTrue(waitFor(5_000) { prefs.getBoolean("foregroundPlay", false) && prefs.getBoolean("playing", false) })
            assertTrue(waitFor(5_000) { rideLeft })
        } finally { scenario.close() }
    }
    @Test fun withoutSessionAccessNoMediaButtonPressIsSent() {
        assertTrue(NotificationListenerAccess.setGranted(context, false))
        val scenario = launchRide()
        try {
            enableButtonReceiver()
            assertEquals(MusicStartResult.REQUESTED, start())
            assertTrue(waitFor(5_000) { prefs.getBoolean("foregroundPlay", false) })
            assertEquals("an unobservable press could not be confirmed", 0, prefs.getInt("buttonPresses", 0))
        } finally { scenario.close() }
    }
    @Test fun cancellingStopsTheReturnWatch() {
        assertTrue(NotificationListenerAccess.setGranted(context, true))
        val scenario = launchRide()
        try {
            assertEquals(MusicStartResult.REQUESTED, start())
            // Before the first watch tick: closing the panel ends the watch.
            instrumentation.runOnMainSync { gateway.cancel() }
            assertTrue(waitFor(5_000) { prefs.getBoolean("playing", false) && rideLeft })
            Thread.sleep(2_000)
            assertFalse(rideReturned)
        } finally { scenario.close() }
    }
    @Test fun anyMusicIntentHasRequiredEmptyQueryAndFocus() {
        val intent = AndroidMusicAppGateway.playIntent()
        assertEquals(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, intent.action)
        assertEquals("", intent.getStringExtra(SearchManager.QUERY))
        assertEquals("vnd.android.cursor.item/*", intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS))
    }
    @Test fun cancellationPreventsLatePlaybackOrAppLaunch() {
        var callbacks = 0
        instrumentation.runOnMainSync {
            gateway.start(context.packageName) { callbacks += 1 }
            gateway.cancel()
        }
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        assertEquals(0, callbacks)
        assertFalse(prefs.getBoolean("playing", false))
    }
    @Test fun unavailableKnownAppsAreNotAdvertised() {
        val visible = gateway.apps().map { it.packageName }.toSet()
        KnownMusicApps.packages.forEach { packageName ->
            val installed = runCatching {
                context.packageManager.getApplicationInfo(packageName, 0).enabled &&
                    context.packageManager.getLaunchIntentForPackage(packageName) != null
            }.getOrDefault(false)
            assertEquals("installed-only visibility for $packageName", installed, packageName in visible)
        }
    }
    @Test fun genericBrowserFixtureIsHiddenWhenDisabledAndCannotBeLaunched() {
        context.packageManager.setComponentEnabledSetting(component,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        assertFalse(gateway.apps().any { it.packageName == context.packageName })
        assertEquals(MusicStartResult.UNAVAILABLE, start())
        assertFalse(gateway.open(context.packageName))
    }
    @Test fun missingPlayerReportsUnavailable() {
        assertEquals(MusicStartResult.UNAVAILABLE, start("com.curvemaps.missing.music"))
    }
    /** A refusing browser plus the player's own play screen: the Spotify shape. */
    private fun launchRide(): ActivityScenario<MainActivity> {
        enablePlayerScreen()
        prefs.edit().putBoolean("reject", true).commit()
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(rideTracker)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue(waitFor(5_000) { rideResumed })
        rideLeft = false
        rideReturned = false
        instrumentation.runOnMainSync { gateway = AndroidMusicAppGateway(context, rideOnTop = { rideResumed }) }
        return scenario
    }
    private fun enablePlayerScreen() {
        context.packageManager.setComponentEnabledSetting(playerScreen,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
    }
    private fun <T> onMain(read: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = read() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
    private fun enableButtonReceiver() {
        context.packageManager.setComponentEnabledSetting(buttonReceiver,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
    }
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(50)
        return condition()
    }
    private fun start(packageName: String = context.packageName): MusicStartResult? {
        val latch = CountDownLatch(1)
        var result: MusicStartResult? = null
        instrumentation.runOnMainSync {
            gateway.start(packageName) { result = it; latch.countDown() }
        }
        assertTrue("start must complete within the bounded connection window", latch.await(7, TimeUnit.SECONDS))
        return result
    }
}
