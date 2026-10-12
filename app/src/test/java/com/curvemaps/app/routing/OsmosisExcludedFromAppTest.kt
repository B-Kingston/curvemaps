package com.curvemaps.app.routing

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * The LGPL-3.0 Osmosis OSM-binary (PBF) classes stay out of the app.
 *
 * GraphHopper declares `osmosis-osm-binary` (and through it protobuf-java) for
 * its OSM PBF reader. The app never imports a PBF on the device: graphs are
 * built on the desktop with `tools/gh/MotoGraphImport.java` and the phone only
 * loads them. `app/build.gradle.kts` therefore excludes the jar, and its
 * `checkNoOsmosisInApp` task (run before every APK build) rejects any
 * Osmosis/OSM-binary module on the debug or release runtime classpath. This
 * suite checks the classes themselves on the unit-test runtime classpath,
 * which extends the app's own: it catches the code coming back under any
 * coordinates, including a repackaged or shaded copy inside another jar.
 * Re-adding it needs a licensing decision first (THIRD_PARTY.md).
 */
class OsmosisExcludedFromAppTest {

    /** Package prefixes of the OSM-binary format code (old and new homes). */
    private val forbiddenPrefixes = listOf("org/openstreetmap/osmosis/", "crosby/binary/")

    @Test
    fun osmBinaryClassesCannotBeLoaded() {
        val loadable = listOf(
            "org.openstreetmap.osmosis.osmbinary.Fileformat",
            "org.openstreetmap.osmosis.osmbinary.Osmformat",
            "crosby.binary.Fileformat",
            "crosby.binary.Osmformat",
            "crosby.binary.osmosis.OsmosisReader",
        ).filter { name ->
            runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
        }
        assertTrue("LGPL OSM-binary classes are loadable: $loadable", loadable.isEmpty())
    }

    @Test
    fun noJarOnTheRuntimeClasspathCarriesOsmBinaryClasses() {
        val entries = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map(::File)
        // The scan is only meaningful if it sees the real app classpath, which
        // includes GraphHopper itself (the library that used to pull it in).
        assertTrue(
            "graphhopper-core is not on the scanned classpath; the scan would prove nothing",
            entries.any { it.name.startsWith("graphhopper-core") },
        )
        val violations = mutableListOf<String>()
        entries.forEach { entry ->
            when {
                entry.isDirectory -> forbiddenPrefixes
                    .filter { File(entry, it).exists() }
                    .forEach { violations += "$entry contains $it" }
                entry.isFile && entry.name.endsWith(".jar") -> ZipFile(entry).use { zip ->
                    zip.entries().asSequence()
                        .map { it.name }
                        .firstOrNull { name -> forbiddenPrefixes.any { name.startsWith(it) } }
                        ?.let { violations += "${entry.name} contains $it" }
                }
            }
        }
        if (violations.isNotEmpty()) {
            fail("LGPL OSM-binary classes on the app classpath:\n" + violations.joinToString("\n"))
        }
    }
}
