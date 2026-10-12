package com.curvemaps.app.licences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The Open-source licences screen against the RELEASE build's real data.
 *
 * `app/build.gradle.kts` makes every unit-test task depend on the release
 * AboutLibraries export and `collectReleaseLicenceNotices`, and passes their
 * outputs (plus the release runtime module list) as system properties. A
 * dependency added, bumped or swapped therefore fails here when the screen
 * would not credit it, would show it without licence text, would ship a
 * licence non-copyleft distribution cannot honour, or would drop a BSD/MIT
 * copyright notice.
 */
class ReleaseLicenceCoverageTest {

    private val catalog: LicenceCatalog by lazy {
        LicenceCatalogParser.parse(requiredFile(DEFINITIONS).readText(), requiredFile(NOTICES).readText())
    }

    private val releaseModules: Set<String> by lazy {
        requiredFile(MODULES).readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    @Test
    fun everyModuleTheReleaseApkShipsIsListed() {
        assertTrue("the release runtime classpath looks empty: $releaseModules", releaseModules.size > 20)
        val listed = catalog.libraries.map { it.id }.toSet()
        val missing = releaseModules - listed
        assertTrue("shipped in the release APK but missing from Open-source licences: $missing", missing.isEmpty())
    }

    @Test
    fun theListClaimsNothingTheReleaseApkDoesNotShip() {
        val extra = catalog.libraries.map { it.id }.toSet() - releaseModules - COMPILE_ONLY_METADATA
        assertTrue("listed but not in the release runtime classpath: $extra", extra.isEmpty())
    }

    @Test
    fun everyEntryHasALicenceWithItsFullTextBundled() {
        catalog.entries.forEach { entry ->
            assertTrue("${entry.id} has no licence", entry.licences.isNotEmpty())
            entry.licences.forEach { licence ->
                assertTrue(
                    "${entry.id}: licence '${licence.name}' (${licence.key}) has no bundled text; add it under app/licences/licenses",
                    licence.content.length > 200,
                )
            }
        }
    }

    @Test
    fun everyLicenceAllowsClosedDistribution() {
        catalog.entries.forEach { entry ->
            val labels = entry.licences.map { it.shortName }
            labels.forEach { label ->
                val allowed = label in PERMISSIVE ||
                    // JTS is dual-licensed; it is used under the EDL.
                    (label in EPL && labels.any { it in EDL })
                assertTrue("${entry.id} is licensed under '$label', which non-copyleft distribution cannot honour", allowed)
            }
        }
    }

    @Test
    fun mapDataFontsAndStyleAssetsAreCredited() {
        val expected = mapOf(
            LicenceCatalogParser.OPENSTREETMAP_ID to listOf("ODbL-1.0"),
            LicenceCatalogParser.OPENMAPTILES_ID to listOf("CC-BY-4.0", "BSD-3-Clause"),
            LicenceCatalogParser.OSM_BRIGHT_ID to listOf("CC-BY-4.0", "BSD-3-Clause"),
            LicenceCatalogParser.NOTO_SANS_ID to listOf("OFL-1.1"),
            LicenceCatalogParser.MATERIAL_SYMBOLS_ID to listOf("Apache-2.0"),
        )
        assertEquals(LicenceCatalogParser.DATA_ORDER, catalog.data.map { it.id })
        expected.forEach { (id, licences) ->
            val entry = catalog.entry(id) ?: return@forEach fail("missing data entry $id")
            assertEquals(LicenceEntryKind.DATA, entry.kind)
            assertEquals(id, licences, entry.licences.map { it.shortName })
        }
        assertTrue(catalog.entry(LicenceCatalogParser.OPENSTREETMAP_ID)!!.description
            .contains("© OpenStreetMap contributors"))
        assertTrue(catalog.entry(LicenceCatalogParser.OPENMAPTILES_ID)!!.description.contains("© OpenMapTiles.org"))
        assertTrue(catalog.entry(LicenceCatalogParser.OPENSTREETMAP_ID)!!.licences.single().content
            .contains("Open Database License"))
    }

    @Test
    fun keyLibrariesCarryTheirLicences() {
        mapOf(
            "com.graphhopper:graphhopper-core" to "Apache-2.0",
            "org.maplibre.gl:android-sdk" to "BSD-2-Clause",
            "org.maplibre.gl:maplibre-android-gestures" to "BSD-2-Clause",
            "androidx.compose.material3:material3" to "Apache-2.0",
            "org.jetbrains.kotlin:kotlin-stdlib" to "Apache-2.0",
            "org.slf4j:slf4j-android" to "MIT",
        ).forEach { (id, licence) ->
            val entry = catalog.entry(id) ?: return@forEach fail("missing $id")
            assertEquals(id, listOf(licence), entry.licences.map { it.shortName })
        }
    }

    @Test
    fun bsdMitAndEdlLibrariesShowTheirCopyrightNotice() {
        // These licences require the copyright notice itself in binary
        // distributions; the SPDX texts carry no holder, so each library needs
        // a notice from its jar or app/licences/notices.
        val needsNotice = catalog.libraries.filter { entry ->
            entry.licences.any { licence -> NOTICE_REQUIRED.any { licence.shortName.contains(it, ignoreCase = true) } }
        }
        assertTrue("expected BSD/MIT libraries in the release APK", needsNotice.size >= 5)
        needsNotice.forEach { entry ->
            assertTrue(
                "${entry.id} (${entry.licenceSummary}) has no copyright notice; add one under app/licences/notices",
                entry.notices.any { it.text.contains("Copyright", ignoreCase = true) },
            )
        }
    }

    @Test
    fun apacheNoticeFilesFromShippedJarsAreCollected() {
        val jackson = catalog.entry("com.fasterxml.jackson.core:jackson-core")!!
        assertTrue(jackson.notices.map { it.name }.toString(), jackson.notices.any { it.name == "META-INF/NOTICE" })
        val okhttp = catalog.entry("com.squareup.okhttp3:okhttp")!!
        assertTrue("OkHttp's public-suffix NOTICE", okhttp.notices.any { it.name.endsWith("publicsuffix/NOTICE") })
        catalog.entries.flatMap { it.notices }.forEach { notice ->
            assertTrue("blank notice ${notice.name}", notice.text.isNotBlank())
        }
    }

    private fun requiredFile(property: String): File {
        val path = System.getProperty(property)
            ?: throw AssertionError("System property $property is not set; run through Gradle (:app:testDebugUnitTest)")
        return File(path).also { assertTrue("$property points at a missing file: $path", it.isFile) }
    }

    private companion object {
        const val DEFINITIONS = "curvemaps.licences.releaseDefinitions"
        const val NOTICES = "curvemaps.licences.releaseNotices"
        const val MODULES = "curvemaps.licences.releaseModules"

        /**
         * On the release compile classpath only (the plugin also reads it):
         * Kotlin 2.x publishes the common stdlib as a metadata module with no
         * Android artifact.
         */
        val COMPILE_ONLY_METADATA = setOf("org.jetbrains.kotlin:kotlin-stdlib-common")

        /** Licence labels (SPDX id, or the POM's name) that allow non-copyleft distribution. */
        val PERMISSIVE = setOf(
            "Apache-2.0",
            "BSD-2-Clause",
            "The BSD 2-Clause License",
            "BSD-3-Clause",
            "BSD 3-Clause License",
            "MIT",
            // BSD-3-Clause in substance (JTS's chosen licence).
            "Eclipse Distribution License - v 1.0",
            "ODbL-1.0",
            "CC-BY-4.0",
            "OFL-1.1",
        )
        val EPL = setOf("EPL-2.0", "Eclipse Public License, Version 2.0")
        val EDL = setOf("Eclipse Distribution License - v 1.0")

        val NOTICE_REQUIRED = listOf("BSD", "MIT", "Distribution License")
    }
}
