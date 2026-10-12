package com.curvemaps.app.media

import android.app.ActivityManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.service.media.MediaBrowserService
import com.curvemaps.app.BuildConfig

/** Uses public player entry points; no account credentials, SDK, or audio focus. */
class AndroidMusicAppGateway(
    context: Context,
    /** Whether the ride screen is the resumed, full-screen, front-most screen. */
    private val rideOnTop: () -> Boolean = { processOnTop() },
    /**
     * Runs just before a player's own screen opens over the ride. The ride
     * uses it to shrink to its Picture-in-Picture window, so the next turn
     * stays in view and the rider can tap back.
     */
    private val beforeLeavingRide: () -> Unit = {},
) : MusicAppGateway {
    private val context = context.applicationContext
    private val pm = this.context.packageManager
    private val handler = Handler(Looper.getMainLooper())
    private var transportController: MediaController? = null
    private var playbackCallback: MediaController.Callback? = null
    private var browser: MediaBrowser? = null
    private var generation = 0L
    private var timeout: Runnable? = null
    private var returnWatch: Runnable? = null
    private var buttonWatch: Runnable? = null

    override fun apps(): List<MusicApp> = runCatching {
        // A generic MediaBrowserService can belong to video, podcast or even
        // this navigation app. Only query actual music entry points, plus the
        // known players whose cold entry points vary between releases.
        val packages = KnownMusicApps.packages +
            pm.queryIntentActivities(Intent.makeMainSelectorActivity(
                Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC,
            ), 0).filter { it.activityInfo.exported }.map { it.activityInfo.packageName } +
            debugFixturePackages()
        packages.distinct().mapNotNull { packageName ->
            runCatching {
                val info = pm.getApplicationInfo(packageName, 0)
                if (!info.enabled || pm.getLaunchIntentForPackage(packageName) == null) null
                else MusicApp(packageName, pm.getApplicationLabel(info).toString())
            }.getOrNull()
        }.sortedWith(compareBy<MusicApp> { it.packageName != SPOTIFY }
            .thenBy { it.name }.thenBy { it.packageName })
    }.getOrDefault(emptyList())

    override fun start(packageName: String, onResult: (MusicStartResult) -> Unit) {
        cancel()
        if (apps().none { it.packageName == packageName }) {
            onResult(MusicStartResult.UNAVAILABLE)
            return
        }
        try {
            startRequest(packageName, onResult)
        } catch (_: Exception) {
            cancel()
            onResult(MusicStartResult.UNAVAILABLE)
        }
    }

    private fun startRequest(packageName: String, onResult: (MusicStartResult) -> Unit) {
        val operation = generation
        // The return window counts from the Start tap (which also restarts the
        // panel's inactivity timer), not from a fallback that may open up to
        // the browser timeout later; otherwise the panel closes mid-watch.
        val startedAt = SystemClock.elapsedRealtime()
        // Background first: the player's media service can start music without
        // leaving the map. Players that refuse unknown callers (Spotify, for
        // one) get one PLAY press on their media button next, and only then
        // fall through to their own foreground entry point.
        val service = pm.queryIntentServices(
            Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(packageName), 0,
        ).firstOrNull { it.serviceInfo.exported }?.serviceInfo
        if (service == null) {
            startByMediaButton(packageName, startedAt, onResult)
            return
        }
        var completed = false
        fun finish(result: () -> MusicStartResult) {
            if (completed || operation != generation) return
            completed = true
            cancel()
            onResult(result())
        }
        // The browser sent no command, so the media button is still the first request.
        fun fallBack() {
            if (completed || operation != generation) return
            completed = true
            cancel()
            startByMediaButton(packageName, startedAt, onResult)
        }
        val connection = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                if (operation != generation) return
                val connected = browser ?: return
                val requested = runCatching {
                    val controller = MediaController(context, connected.sessionToken)
                    val actions = controller.playbackState?.actions ?: 0L
                    val observer = object : MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            if (operation == generation && state?.state == PlaybackState.STATE_PLAYING) cancel()
                        }
                        override fun onSessionDestroyed() {
                            if (operation == generation) cancel()
                        }
                    }
                    transportController = controller
                    playbackCallback = observer
                    controller.registerCallback(observer, handler)
                    when {
                        actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L ->
                            controller.transportControls.playFromSearch("", null)
                        actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L ->
                            controller.transportControls.play()
                        else -> return@runCatching false
                    }
                    true
                }.getOrDefault(false)
                // Never issue a second playback command when the player ignores the first.
                if (requested) {
                    completed = true
                    // Play is a one-way Binder call. Keep the binding until the
                    // player processes it, instead of destroying a cold service
                    // before its session callback runs. No second command is sent.
                    timeout?.let(handler::removeCallbacks)
                    timeout = Runnable { if (operation == generation) cancel() }
                        .also { handler.postDelayed(it, 5_000) }
                    onResult(MusicStartResult.REQUESTED)
                } else fallBack()
            }
            override fun onConnectionFailed() = fallBack()
            override fun onConnectionSuspended() = finish { MusicStartResult.UNAVAILABLE }
        }
        browser = MediaBrowser(context, ComponentName(service.packageName, service.name), connection, null)
        // A browser that never answers must not strand the rider: no command
        // was sent, so the next entry point is still the first request.
        timeout = Runnable { fallBack() }.also { handler.postDelayed(it, MusicBrowserTimeoutMillis) }
        if (runCatching { browser?.connect() }.isFailure) fallBack()
    }

    /**
     * One PLAY press on the player's own media-button receiver, the signal a
     * headset's play key gives it. The ride stays in front. The press is sent
     * only while sessions can be observed, because only observed playback
     * counts as success; a player that stays silent falls back to its own
     * screen. The press is an explicit PLAY, never a toggle, so a late start
     * plus the fallback's own play request cannot pause the music again.
     */
    private fun startByMediaButton(packageName: String, startedAt: Long, onResult: (MusicStartResult) -> Unit) {
        val receiver = mediaButtonReceiver(packageName)
        if (receiver == null || isPlaying(packageName) == null || !pressPlay(receiver)) {
            onResult(startInForeground(packageName, startedAt))
            return
        }
        val operation = generation
        val pressedAt = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                if (operation != generation) return
                when (mediaButtonStep(SystemClock.elapsedRealtime() - pressedAt, isPlaying(packageName))) {
                    MediaButtonStep.WAIT -> handler.postDelayed(this, RETURN_POLL_MILLIS)
                    MediaButtonStep.PLAYING -> {
                        buttonWatch = null
                        onResult(MusicStartResult.REQUESTED)
                    }
                    MediaButtonStep.FALL_BACK -> {
                        buttonWatch = null
                        onResult(startInForeground(packageName, startedAt))
                    }
                }
            }
        }
        buttonWatch = tick
        handler.postDelayed(tick, RETURN_POLL_MILLIS)
    }

    /** The player's exported `ACTION_MEDIA_BUTTON` receiver, if it declares one. */
    private fun mediaButtonReceiver(packageName: String): ComponentName? = runCatching {
        pm.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(packageName), 0)
            .firstOrNull { it.activityInfo.exported }?.activityInfo
            ?.let { ComponentName(it.packageName, it.name) }
    }.getOrNull()

    /** One paired DOWN/UP `KEYCODE_MEDIA_PLAY`, addressed to [receiver] only. */
    private fun pressPlay(receiver: ComponentName): Boolean = runCatching {
        AndroidMediaKeyController.mediaKeyEvents(KeyEvent.KEYCODE_MEDIA_PLAY).forEach { event ->
            context.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver).putExtra(Intent.EXTRA_KEY_EVENT, event),
            )
        }
        true
    }.getOrDefault(false)

    /**
     * The player's "play any music" screen, else its launcher screen. A play
     * request is followed by a bounded watch that brings the ride back once
     * the player is observed playing; a plain open leaves the rider choosing.
     */
    private fun startInForeground(packageName: String, startedAt: Long): MusicStartResult {
        val intent = playIntent().setPackage(packageName)
        if (pm.resolveActivity(intent, 0) == null) {
            return if (open(packageName)) MusicStartResult.OPENED else MusicStartResult.UNAVAILABLE
        }
        if (!launch(intent)) return MusicStartResult.UNAVAILABLE
        watchForPlayback(packageName, startedAt)
        return MusicStartResult.REQUESTED
    }

    private fun watchForPlayback(packageName: String, startedAt: Long) {
        val operation = generation
        val watch = ReturnToRideWatch(startedAt)
        val tick = object : Runnable {
            override fun run() {
                if (operation != generation) return
                when (watch.step(SystemClock.elapsedRealtime(), rideOnTop(), isPlaying(packageName))) {
                    ReturnToRideWatch.Step.WAIT -> handler.postDelayed(this, RETURN_POLL_MILLIS)
                    ReturnToRideWatch.Step.RETURN -> { returnToRide(); returnWatch = null }
                    ReturnToRideWatch.Step.STOP -> returnWatch = null
                }
            }
        }
        returnWatch = tick
        handler.postDelayed(tick, RETURN_POLL_MILLIS)
    }

    /** Null without notification access: playback cannot be observed, so never return blindly. */
    private fun isPlaying(packageName: String): Boolean? = runCatching {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        manager.getActiveSessions(ComponentName(context, MediaNotificationListenerService::class.java))
            .any { it.packageName == packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING }
    }.getOrNull()

    /**
     * Brings the existing ride task forward; never creates a second planner.
     * Android may block it: an app whose only visible activity is the ride's
     * Picture-in-Picture window, or that has none, may not start an activity.
     */
    private fun returnToRide() {
        val component = pm.getLaunchIntentForPackage(context.packageName)?.component ?: return
        runCatching {
            context.startActivity(Intent().setComponent(component).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ))
        }
    }

    override fun open(packageName: String): Boolean = runCatching {
        if (apps().none { it.packageName == packageName }) false
        else pm.getLaunchIntentForPackage(packageName)?.let { launch(it) } ?: false
    }.getOrDefault(false)

    override fun cancel() {
        generation += 1
        timeout?.let(handler::removeCallbacks)
        timeout = null
        returnWatch?.let(handler::removeCallbacks)
        returnWatch = null
        buttonWatch?.let(handler::removeCallbacks)
        buttonWatch = null
        playbackCallback?.let { callback ->
            runCatching { transportController?.unregisterCallback(callback) }
        }
        playbackCallback = null
        transportController = null
        browser?.let { runCatching { it.disconnect() } }
        browser = null
    }

    private fun debugFixturePackages(): List<String> {
        if (!BuildConfig.DEBUG) return emptyList()
        // The query excludes disabled components and honours runtime overrides;
        // ServiceInfo.enabled itself can still reflect the manifest default.
        return pm.queryIntentServices(
            Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(context.packageName), 0,
        ).filter { it.serviceInfo.exported }
            .map { it.serviceInfo.packageName }
    }

    private fun launch(intent: Intent): Boolean = runCatching {
        runCatching(beforeLeavingRide)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    companion object {
        const val SPOTIFY = KnownMusicApps.SPOTIFY
        private const val RETURN_POLL_MILLIS = 250L

        private fun processOnTop(): Boolean = runCatching {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }.getOrDefault(true)
        internal fun playIntent(): Intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, "")
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
    }
}
