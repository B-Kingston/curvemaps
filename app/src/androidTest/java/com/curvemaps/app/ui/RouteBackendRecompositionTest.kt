package com.curvemaps.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import com.curvemaps.app.RouteOutcome
import com.curvemaps.app.RouteParams
import com.curvemaps.app.RouteSearchCoordinator
import com.curvemaps.app.RouteUiState
import com.curvemaps.app.rememberRouteSearchCoordinator
import com.graphhopper.util.shapes.GHPoint
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class RouteBackendRecompositionTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun installedDatasetReplacesTheBackendWithoutResettingTheCoordinator() {
        val datasetPresent = mutableStateOf(false)
        lateinit var coordinator: RouteSearchCoordinator
        rule.setContent {
            val present = datasetPresent.value
            coordinator = rememberRouteSearchCoordinator(rememberCoroutineScope()) {
                RouteOutcome.Failure(if (present) "Installed region reached" else "No map installed")
            }
        }
        val params = RouteParams(GHPoint(43.73, 7.42), GHPoint(43.74, 7.43), 0.0, false)
        rule.runOnIdle { coordinator.submit(params) }
        rule.waitUntil(2_000) { coordinator.state.value == RouteUiState.Error("No map installed") }
        val initialCoordinator = coordinator
        rule.runOnIdle { datasetPresent.value = true }
        rule.runOnIdle {
            assertSame(initialCoordinator, coordinator)
            coordinator.submit(params)
        }
        rule.waitUntil(2_000) {
            coordinator.state.value == RouteUiState.Error("Installed region reached")
        }
    }
}
