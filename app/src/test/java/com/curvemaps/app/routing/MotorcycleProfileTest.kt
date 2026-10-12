package com.curvemaps.app.routing

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

class MotorcycleProfileTest {

    @Test
    fun profileVersionMatchesStoredGraph() {
        val profile = motorcycleProfile()
        assertEquals(MOTORCYCLE_PROFILE, profile.name)
        assertEquals(198752012, profile.version)
    }

    @Test
    fun hintsHaveCanonicalShape() {
        val profile = motorcycleProfile()
        val hints = profile.hints
        assertEquals(listOf("motorcycle.json"), hints.getObject("custom_model_files", emptyList<String>()))
        // Profile.setCustomModel stores the resolved model under this hint.
        // GraphHopper needs that value to load the imported graph.
        assertNotNull(profile.customModel)
        // Ride styles and road toggles are request-time logic. They must not
        // change the imported profile or its stored graph hash.
        assertFalse(hints.has(MOTO_RIDE_STYLE))
        assertFalse(hints.has(AVOID_MOTORWAYS))
        // So are the ride factors: Hills and Avoid stops and towns must not
        // touch the profile hash either (profileVersionMatchesStoredGraph).
        assertFalse(hints.has(MOTO_HILLS))
        assertFalse(hints.has(MOTO_STOPS_AND_TOWNS))
        assertFalse(hints.has(MOTO_CURVE_WEIGHT))
    }

    @Test
    fun customModelMatchesCanonicalReference() {
        val bundled = JSONObject(
            modelText(
                MotorcycleProfileTest::class.java
                    .getResourceAsStream("/com/graphhopper/custom_models/motorcycle.json")!!,
            ),
        )
        val reference = JSONObject(modelText(repoRoot().resolve("tools/gh/motorcycle.json").inputStream()))
        assertEquals(reference.getDouble("distance_influence"), bundled.getDouble("distance_influence"), 0.0)
        assertEquals(reference.getJSONArray("priority").toString(), bundled.getJSONArray("priority").toString())
        assertEquals(reference.getJSONArray("speed").toString(), bundled.getJSONArray("speed").toString())
    }

    @Test
    fun helperClassIsPresentAndInstantiable() {
        val helper = Class.forName("com.graphhopper.routing.weighting.custom.MotorcycleWeightingHelper")
        assertNotNull(helper)
        assertFalse(Modifier.isAbstract(helper.modifiers))
        assertNotNull(helper.getDeclaredConstructor().newInstance())
    }

    private fun modelText(input: java.io.InputStream): String =
        input.bufferedReader().use { reader ->
            reader.lineSequence()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
        }
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("Could not locate repository root")
    }
}
