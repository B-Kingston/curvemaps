package com.curvemaps.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.curvemaps.app.NavigationController
import com.curvemaps.app.RideSuggestionSearch
import com.curvemaps.app.routing.GraphHopperRouter
import com.curvemaps.app.routing.ROUTE_CORPUS
import com.curvemaps.app.routing.RideStyle
import com.curvemaps.app.routing.navigation.GpsFix
import com.curvemaps.app.routing.navigation.RouteTrackFactory
import com.curvemaps.app.routing.suggestion.RideSuggestionInputs
import com.curvemaps.app.routing.suggestion.RideSuggestionState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android controller/search binding, with a deterministic monotonic GPS clock. */
@RunWith(AndroidJUnit4::class)
class RideSuggestionControllerTest {
    @Test(timeout = 300_000)
    fun scheduledOfferSurvivesTheControllerBindingAndTakeInstallsItsPath() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val router = GraphHopperRouter(context)
        val corridor = ROUTE_CORPUS.first { it.name == "Samford to southern Brisbane scenic suggestion corridor" }
        val path = router.route(corridor.from, corridor.to, com.curvemaps.app.routing.RideStyle.BALANCED.level.toDouble()).routes.first()
        val baseline = RouteTrackFactory.fromPath(path)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = NavigationController(scope)
        val search = RideSuggestionSearch()
        val speech = mutableListOf<String>()
        var accepted = false
        try {
            withContext(Dispatchers.Main) {
                controller.setSuggestionHandlers(
                    search = { request -> search.search(router, request, corridor.to, false, false) },
                    inputs = { RideSuggestionInputs(true, RideStyle.BALANCED) },
                    speak = { speech += it }, onAccepted = { accepted = true })
                controller.start(path)
            }
            for (offset in 0..16_000 step 25) {
                val position = baseline.positionAt(offset.toDouble())
                val now = 1_000L + (offset / 15.0 * 1000).toLong()
                withContext(Dispatchers.Main) {
                    controller.onFix(GpsFix(position.first, position.second, speedMps = 15.0,
                        accuracyM = 1.0, timestampMs = now), nowMs = now)
                }
                withTimeout(30_000) {
                    while (withContext(Dispatchers.Main) { controller.suggestionCheckInFlight }) delay(10)
                }
                if (controller.suggestionState.value is RideSuggestionState.Pending) break
            }
            assertTrue("Controller did not publish the real proposal: ${controller.suggestionState.value}",
                controller.suggestionState.value is RideSuggestionState.Pending)
            assertEquals(1, speech.count { it.startsWith("Twistier road ahead:") })
            val proposal = (controller.suggestionState.value as RideSuggestionState.Pending).proposal
            withContext(Dispatchers.Main) { controller.takeSuggestion() }
            assertTrue(accepted)
            assertEquals("accepted:${proposal.id}", controller.suggestionState.value.semanticsValue)
            assertEquals(proposal.replacement.totalDistanceM, controller.snapshot.value.remainingDistanceM, 100.0)
            assertEquals("Taking the scenic route.", speech.last())
        } finally {
            withContext(Dispatchers.Main) { controller.stop() }
            search.close(); scope.cancel()
        }
    }
}
