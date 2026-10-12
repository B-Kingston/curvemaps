package com.curvemaps.app.licences

import com.curvemaps.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Open-source licences list's filter chips, row subtitles and row symbols (pure, no Android). */
class LicenceListModelTest {

    private fun entry(
        id: String,
        kind: LicenceEntryKind,
        name: String = id,
        description: String = "",
        version: String? = null,
    ) = LicenceEntry(
        id = id,
        name = name,
        version = version,
        kind = kind,
        description = description,
        website = null,
        holders = emptyList(),
        licences = listOf(LicenceText("Apache-2.0", "Apache License 2.0", "Apache-2.0", null, "text")),
        notices = emptyList(),
    )

    private val data = LicenceCatalogParser.DATA_ORDER.map { entry(it, LicenceEntryKind.DATA) }
    private val libraries = listOf(
        entry("com.graphhopper:graphhopper-core", LicenceEntryKind.LIBRARY, version = "11.0"),
        entry("org.maplibre.gl:android-sdk-opengl", LicenceEntryKind.LIBRARY, version = "11.8.0"),
        entry("org.slf4j:slf4j-android", LicenceEntryKind.LIBRARY, version = "1.7.36"),
        entry("org.example:other", LicenceEntryKind.LIBRARY, version = "1"),
    )
    private val catalog = LicenceCatalog(data + libraries)

    @Test
    fun everyDataEntryIsInExactlyOneOfMapDataAndFonts() {
        data.forEach { entry ->
            val matching = listOf(LicenceFilter.MapData, LicenceFilter.Fonts).filter { it.matches(entry) }
            assertEquals("${entry.id} belongs to exactly one data chip", 1, matching.size)
            assertTrue(LicenceFilter.All.matches(entry))
            assertTrue("data is never a library", !LicenceFilter.Libraries.matches(entry))
        }
        assertEquals(
            listOf(LicenceCatalogParser.OPENSTREETMAP_ID, LicenceCatalogParser.OPENMAPTILES_ID, LicenceCatalogParser.OSM_BRIGHT_ID),
            catalog.sections(LicenceFilter.MapData).data.map { it.id },
        )
        assertEquals(
            listOf(LicenceCatalogParser.NOTO_SANS_ID, LicenceCatalogParser.MATERIAL_SYMBOLS_ID),
            catalog.sections(LicenceFilter.Fonts).data.map { it.id },
        )
    }

    @Test
    fun aNewDataEntryDefaultsToMapDataAndLibrariesToTheirChip() {
        val unknown = entry("curvemaps.data:new-source", LicenceEntryKind.DATA)
        assertTrue(LicenceFilter.MapData.matches(unknown))
        assertTrue(!LicenceFilter.Fonts.matches(unknown))
        val sections = catalog.sections(LicenceFilter.Libraries)
        assertTrue(sections.data.isEmpty())
        assertEquals(libraries, sections.libraries)
    }

    @Test
    fun allShowsEverythingInCatalogOrder() {
        val sections = catalog.sections(LicenceFilter.All)
        assertEquals(catalog.data, sections.data)
        assertEquals(catalog.libraries, sections.libraries)
        assertTrue(!sections.isEmpty)
        assertTrue(catalog.sections(LicenceFilter.Fonts).libraries.isEmpty())
    }

    @Test
    fun chipLabelsAndHeadingsAreStable() {
        assertEquals(
            listOf("All", "Map data", "Fonts", "Libraries"),
            LicenceFilter.entries.map { it.label },
        )
        assertEquals("Map data, fonts and icons", LicenceFilter.All.dataHeading)
        assertEquals("Map data and style", LicenceFilter.MapData.dataHeading)
        assertEquals("Fonts and icons", LicenceFilter.Fonts.dataHeading)
    }

    @Test
    fun subtitleIsCoordinatesForLibrariesAndTheFirstSentenceForData() {
        assertEquals(
            "com.graphhopper:graphhopper-core 11.0",
            libraries.first().listSubtitle(),
        )
        val osm = entry(
            LicenceCatalogParser.OPENSTREETMAP_ID,
            LicenceEntryKind.DATA,
            description = "Map data behind the routing graph and tiles. © OpenStreetMap contributors. Available under the ODbL.",
        )
        assertEquals("Map data behind the routing graph and tiles", osm.listSubtitle())
        assertEquals("No full stop", entry("d", LicenceEntryKind.DATA, description = "No full stop").listSubtitle())
        assertNull(entry("d", LicenceEntryKind.DATA).listSubtitle())
    }

    @Test
    fun everyDataEntryHasItsOwnSymbolAndLibrariesFallBackToAGenericOne() {
        val dataIcons = data.map { it.listIcon() }
        assertEquals("data entries do not share a symbol", dataIcons.size, dataIcons.toSet().size)
        assertEquals(R.drawable.ms_route, libraries[0].listIcon())
        assertEquals(R.drawable.ms_public, libraries[1].listIcon())
        assertEquals(R.drawable.ms_terminal, libraries[2].listIcon())
        assertEquals(R.drawable.ms_data_object, libraries[3].listIcon())
        assertNotEquals(libraries[3].listIcon(), dataIcons.first())
    }
}
