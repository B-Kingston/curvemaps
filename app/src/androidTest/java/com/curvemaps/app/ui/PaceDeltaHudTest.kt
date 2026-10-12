package com.curvemaps.app.ui

import com.curvemaps.app.ui.theme.CurveMapsTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.curvemaps.app.NavigationDataBar
import com.curvemaps.app.routing.navigation.GpsFix
import com.curvemaps.app.routing.navigation.NavigationSession
import com.curvemaps.app.routing.navigation.RouteTrack
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real engine-to-HUD binding for pace across a model speed change. */
@RunWith(AndroidJUnit4::class)
class PaceDeltaHudTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun localModelPaceShowsOnPlanAcrossSpeedChange() {
        val session = NavigationSession()
        session.startRoute(RouteTrack.fromPath(
            latitudes = DoubleArray(21) { -27.0 - it * 0.0009 },
            longitudes = DoubleArray(21) { 153.0 },
            instructionSigns = listOf(0, 0, 4),
            instructionNames = listOf("Town", "Highway", "Finish"),
            instructionPointCounts = listOf(10, 10, 1),
            instructionTimesS = listOf(120.0, 40.0, 0.0),
        ))
        session.beginFollowing()
        compose.setContent {
            CurveMapsTheme {
                NavigationDataBar(snapshot = session.snapshot.collectAsState().value, onEnd = {})
            }
        }
        compose.onNodeWithContentDescription("pace delta unavailable", substring = true).assertIsDisplayed()
        for (position in 0..19) {
            val timeS = if (position <= 10) position * 12 else 120 + (position - 10) * 4
            compose.runOnIdle {
                session.onFix(GpsFix(-27.0 - position * 0.0009, 153.0,
                    speedMps = 10.0, timestampMs = 1_000L + timeS * 1_000L))
            }
            if (position >= 2) {
                compose.onNodeWithContentDescription("pace on plan", substring = true).assertIsDisplayed()
            }
        }
    }

}
