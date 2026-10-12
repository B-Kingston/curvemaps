package com.curvemaps.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [ImportedGpxStore] keeps the open import across recreation, or nothing. */
class ImportedGpxStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val ride = ImportedGpx(
        name = "Sunday loop",
        plan = GpxRidePlan(
            mode = GpxRideMode.FOLLOW_TRACK,
            line = List(500) { GeoPoint(-27.0 + it * 1e-4, 153.0 + it * 2e-4) },
            stops = listOf(GeoPoint(-26.99, 153.02)),
            ignoredWaypoints = 3,
        ),
        milestones = listOf(
            GpxMilestone(GeoPoint(-27.0, 153.0), 0.0, 0.0, "Samford", "Queensland"),
            GpxMilestone(GeoPoint(-26.95, 153.1), 12_000.0, 1.0),
        ),
    )

    @Test
    fun aSavedRideComesBackAsItWas() {
        val store = ImportedGpxStore(File(folder.root, "imported-gpx/current.json"))
        store.save(ride)
        val loaded = ImportedGpxStore(File(folder.root, "imported-gpx/current.json")).load()!!
        assertEquals(ride.name, loaded.name)
        assertEquals(ride.plan.mode, loaded.plan.mode)
        assertEquals(ride.plan.ignoredWaypoints, loaded.plan.ignoredWaypoints)
        // The line round-trips at the polyline's 1e-5° (about a metre) precision.
        assertEquals(ride.plan.line.size, loaded.plan.line.size)
        ride.plan.line.zip(loaded.plan.line).forEach { (a, b) ->
            assertEquals(a.lat, b.lat, 1e-5)
            assertEquals(a.lon, b.lon, 1e-5)
        }
        assertEquals(ride.plan.stops.size, loaded.plan.stops.size)
        assertEquals(ride.milestones, loaded.milestones)
    }

    @Test
    fun aNewImportReplacesTheOldAndClearLeavesNothing() {
        val file = File(folder.root, "current.json")
        val store = ImportedGpxStore(file)
        store.save(ride)
        store.save(ride.copy(name = "Second"))
        assertEquals("Second", store.load()?.name)
        assertFalse(File(folder.root, "current.json.tmp").exists())
        store.clear()
        assertNull(store.load())
        store.clear()
    }

    @Test
    fun anUnreadableFileIsNoRide() {
        val file = File(folder.root, "current.json")
        val store = ImportedGpxStore(file)
        assertNull(store.load())
        file.writeText("{ not json")
        assertNull(store.load())
        file.writeText("""{"version": 99, "name": "x"}""")
        assertNull(store.load())
        store.save(ride)
        file.writeText(file.readText().replace("FOLLOW_TRACK", "SOMETHING_ELSE"))
        assertNull(store.load())
    }
}
