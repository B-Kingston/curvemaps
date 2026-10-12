package com.curvemaps.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.curvemaps.app.ui.theme.CurveMapsTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.curvemaps.app.onboarding.WelcomeCarousel
import com.curvemaps.app.onboarding.WelcomeExit
import com.curvemaps.app.onboarding.WelcomeStore
import org.maplibre.android.MapLibre
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    /**
     * A GPX file another app opened with CurveMaps ([OpenGpxActivity]),
     * waiting for the planner (after the welcome carousel on a first run, and
     * after a ride in progress).
     */
    private var incomingGpx by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingGpx = if (savedInstanceState != null) {
            if (Build.VERSION.SDK_INT >= 33) {
                savedInstanceState.getParcelable(STATE_INCOMING_GPX, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                savedInstanceState.getParcelable(STATE_INCOMING_GPX)
            }
        } else {
            openedGpxOf(intent)
        }
        MapLibre.getInstance(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            CurveMapsTheme {
                // First run shows the welcome carousel instead of the planner;
                // it asks each permission on its own page, so the planner never
                // stacks startup dialogs.
                var welcomeDone by rememberSaveable { mutableStateOf(WelcomeStore.isCompleted(this)) }
                var welcomeExit by rememberSaveable { mutableStateOf(WelcomeExit.NONE) }
                if (welcomeDone) {
                    RouteScreen(
                        startupAction = welcomeExit,
                        onStartupActionHandled = { welcomeExit = WelcomeExit.NONE },
                        incomingGpx = incomingGpx,
                        onIncomingGpxHandled = { incomingGpx = null },
                    )
                } else {
                    // Dark surface: light status-bar icons.
                    SystemBarIcons(darkIcons = false)
                    WelcomeCarousel(onFinish = { exit ->
                        WelcomeStore.markCompleted(this)
                        welcomeExit = exit
                        welcomeDone = true
                    })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openedGpxOf(intent)?.let { incomingGpx = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(STATE_INCOMING_GPX, incomingGpx)
    }

    private fun openedGpxOf(intent: Intent?): Uri? =
        intent?.takeIf { it.action == ACTION_OPEN_GPX }?.data

    companion object {
        /** [OpenGpxActivity]'s hand-over: the intent's data is the GPX file. */
        const val ACTION_OPEN_GPX = "com.curvemaps.app.action.OPEN_GPX"
        private const val STATE_INCOMING_GPX = "incomingGpx"
    }
}

/**
 * Sets the status- and navigation-bar icon colour of the activity window:
 * [darkIcons] when the content behind the bars is light (the planner over the
 * colour map, the light pages), light icons over dark content (the welcome
 * carousel, the B&W ride map). Dialog windows have their own window and set
 * theirs separately.
 */
@Composable
internal fun SystemBarIcons(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val window = (context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = darkIcons
        controller.isAppearanceLightNavigationBars = darkIcons
    }
}
