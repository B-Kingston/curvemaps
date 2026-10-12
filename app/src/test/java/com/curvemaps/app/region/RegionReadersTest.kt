package com.curvemaps.app.region

import org.junit.Assert.*
import org.junit.Test

class RegionReadersTest {
    @Test fun deletionChecksTheCurrentGenerationOfReaders() {
        val readers = RegionReaders()
        val release = readers.retain("map")
        val oldDrain = readers.drained("map")
        release()
        assertTrue(oldDrain.isCompleted)
        val fresh = readers.retain("map")
        var removed = false
        assertFalse(readers.removeIfDrained("map") { removed = true })
        assertFalse(removed)
        fresh()
        assertTrue(readers.removeIfDrained("map") { removed = true })
        assertTrue(removed)
    }

    @Test fun deletionWaitsForEveryReaderAndReleaseIsIdempotent() {
        val readers = RegionReaders()
        val first = readers.retain("map")
        val second = readers.retain("map")
        val drained = readers.drained("map")
        first()
        first()
        assertFalse(drained.isCompleted)
        second()
        assertTrue(drained.isCompleted)
        assertTrue(readers.drained("map").isCompleted)
    }

    @Test fun releasedBeforeRemovalCannotLoseTheDrainSignal() {
        val readers = RegionReaders()
        readers.retain("map")()
        assertTrue(readers.drained("map").isCompleted)
    }

    @Test fun unrelatedReadersAndNewGenerationsHaveSeparateLifetimes() {
        val readers = RegionReaders()
        val old = readers.retain("map")
        val unrelated = readers.retain("other")
        val drained = readers.drained("map")
        unrelated()
        assertFalse(drained.isCompleted)
        old()
        val fresh = readers.retain("map")
        assertFalse(readers.drained("map").isCompleted)
        assertTrue(drained.isCompleted)
        fresh()
    }
}
