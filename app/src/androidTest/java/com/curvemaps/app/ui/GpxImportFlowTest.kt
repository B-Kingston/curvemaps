package com.curvemaps.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.curvemaps.app.FOLLOWED_GPX_NOTE_PREFIX
import com.curvemaps.app.IMPORTED_GPX_FILE
import com.curvemaps.app.MainActivity
import com.curvemaps.app.OpenGpxActivity
import com.curvemaps.app.storage.GpxParser
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * GPX files end to end, as a rider gets them: opened ("Open with") or shared
 * from another app through [OpenGpxActivity], followed road for road (a
 * detailed track) or routed between stops in the ride style (a stop list),
 * kept through Back → START and recreation, and refused with a plain message
 * when the file is not a GPX ride.
 */
@RunWith(AndroidJUnit4::class)
class GpxImportFlowTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val files = File(app.filesDir, "gpx-import-test")

    @Before
    fun setUp() {
        composeRule.dismissMediaStartupPrompt()
        files.mkdirs()
    }

    @After
    fun tearDown() {
        files.deleteRecursively()
        File(app.filesDir, IMPORTED_GPX_FILE).delete()
    }

    /** Copies a committed fixture into the app's files, as a file manager would hand it over. */
    private fun fixture(name: String): Uri {
        val target = File(files, name)
        instrumentation.context.assets.open("gpx/$name").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(target)
    }

    private fun openWith(uri: Uri) = handOver(
        Intent(app, OpenGpxActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/gpx+xml")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun shareTo(uri: Uri) = handOver(
        Intent(app, OpenGpxActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    // For the moment the hand-over activity is on top the planner is paused
    // and has no hierarchy to query: that is "not yet", not a failure.
    private fun handOver(intent: Intent) = app.startActivity(intent)

    private fun waitForSuccess() = composeRule.waitUntil(300_000) {
        present(androidx.compose.ui.test.SemanticsMatcher.expectValue(com.curvemaps.app.RouteUiStateKey, "success"))
    }

    private fun present(matcher: androidx.compose.ui.test.SemanticsMatcher) = try {
        composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    } catch (e: IllegalStateException) {
        false
    }

    private val followedCard = hasContentDescription("Route 1: Mount Glorious loop", substring = true)
    private val followNote = hasContentDescription("$FOLLOWED_GPX_NOTE_PREFIX:", substring = true)
    private val gpxRows = hasContentDescription("Preview GPX route", substring = true)

    private fun awaitFollowedTrack() {
        waitForSuccess()
        composeRule.waitUntil(60_000) { present(followedCard) }
        // The file chose the roads: no ride-style slider, a note in its place.
        composeRule.waitUntil(30_000) {
            present(hasContentDescription("$FOLLOWED_GPX_NOTE_PREFIX: Rides your track road for road", substring = true))
        }
        assertTrue(!present(KnobRobot.matcher()))
    }

    @Test(timeout = 300_000)
    fun anOpenedTrackIsFollowedAndSurvivesBackStartAndRecreation() {
        openWith(fixture("mount-glorious-loop.gpx"))
        awaitFollowedTrack()

        // Back → START used to route From → To directly: on a loop, "From
        // and To are the same place".
        PlannerFlow.backToEndpoints(composeRule)
        assertTrue(present(gpxRows))
        composeRule.onNodeWithText("START").performClick()
        awaitFollowedTrack()

        // Rotation or process death keeps the import, not just its labels.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(30_000) { present(gpxRows) }
        composeRule.onNodeWithText("START").performClick()
        awaitFollowedTrack()

        // Edit gives the planner back its fields and forgets the import.
        PlannerFlow.backToEndpoints(composeRule)
        composeRule.onNodeWithContentDescription("Edit imported route", substring = true).performClick()
        composeRule.waitUntil(10_000) { !present(gpxRows) }
        assertTrue(!File(app.filesDir, IMPORTED_GPX_FILE).exists() || waitForDeletion())
    }

    private fun waitForDeletion(): Boolean {
        repeat(50) {
            if (!File(app.filesDir, IMPORTED_GPX_FILE).exists()) return true
            Thread.sleep(100)
        }
        return false
    }

    @Test(timeout = 300_000)
    fun aSharedStopListKeepsTheRideStyles() {
        shareTo(fixture("nambour-stops.gpx"))
        waitForSuccess()
        composeRule.waitUntil(60_000) {
            present(KnobRobot.matcher()) && present(ROUTE_CARD)
        }
        assertTrue(!present(followNote))
        PlannerFlow.backToEndpoints(composeRule)
        composeRule.onNodeWithContentDescription("Preview GPX route", substring = true).performClick()
        composeRule.waitUntil(10_000) {
            present(hasText("Routes between its 5 stops in your ride style", substring = true))
        }
    }

    @Test(timeout = 120_000)
    fun aFileThatIsNotAGpxIsRefusedPlainly() {
        val notGpx = File(files, "notes.gpx").apply { writeText("Fuel at Woodford, lunch at Dayboro") }
        openWith(Uri.fromFile(notGpx))
        composeRule.waitUntil(30_000) { present(hasText(GpxParser.NOT_GPX_MESSAGE)) }
        composeRule.onNodeWithText("OK").performClick()
        assertTrue(!present(gpxRows))
    }
}
