package com.curvemaps.app.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Maps screen's catalog organisation: continents from the server's
 * Geofabrik coverage path, country sections inside them, stable alphabetical
 * order, word search, and which continents start open.
 */
class CatalogGroupingTest {

    private fun region(id: String, name: String, coverage: String?, available: Boolean = true) = ServerRegion(
        id = id,
        name = name,
        coverage = coverage,
        disabled = false,
        maxZoom = 14,
        sourceDate = "261005",
        sourceSha256 = "a".repeat(64),
        sourcePinned = true,
        artifact = ServerArtifact(
            available = available,
            fingerprint = "b".repeat(64),
            size = 357_996_650,
            sha256 = "c".repeat(64),
            generatedAt = "2026-10-06T15:09:43Z",
            downloadUrl = "/api/v1/artifacts/$id/x/$id.motomap",
        ),
        activeJobId = null,
    )

    // The live catalog's shape (2026-10-07), deliberately out of order.
    private val catalog = listOf(
        region("us-utah", "Utah", "North America › Utah"),
        region("wales", "Wales", "Europe › United Kingdom › Wales"),
        region("queensland", "Queensland", "Australia and Oceania › Australia › Queensland"),
        region("socal", "Southern California", "North America › California › Southern California"),
        region("new-zealand", "New Zealand", "Australia and Oceania › New Zealand"),
        region("scotland", "Scotland", "Europe › United Kingdom › Scotland"),
        region("act", "ACT", "Australia and Oceania › Australia › Australian Capital Territory"),
        region("austria", "Austria", "Europe › Austria"),
        region("monaco", "Monaco", "Monaco"),
        region("legacy", "Legacy", null),
        region("norcal", "Northern California", "North America › California › Northern California"),
    )

    @Test
    fun groupsByContinentAlphabeticallyWithOtherLast() {
        val groups = CatalogGrouping.group(catalog)
        assertEquals(
            listOf("Australia and Oceania", "Europe", "North America", CatalogGrouping.OTHER),
            groups.map { it.name },
        )
        assertEquals(catalog.size, groups.sumOf { it.size })
    }

    @Test
    fun standaloneRegionsComeBeforeCountrySectionsAndRegionsSortByName() {
        val australia = CatalogGrouping.group(catalog).first()
        assertEquals(listOf(null, "Australia"), australia.sections.map { it.title })
        assertEquals(listOf("New Zealand"), australia.sections[0].regions.map { it.name })
        assertEquals(listOf("ACT", "Queensland"), australia.sections[1].regions.map { it.name })

        val america = CatalogGrouping.group(catalog).first { it.name == "North America" }
        assertEquals(listOf(null, "California"), america.sections.map { it.title })
        assertEquals(
            listOf("Northern California", "Southern California"),
            america.sections[1].regions.map { it.name },
        )
    }

    @Test
    fun regionsWithoutAContinentPathGoToOther() {
        val other = CatalogGrouping.group(catalog).last()
        assertEquals(CatalogGrouping.OTHER, other.name)
        assertEquals(listOf("Legacy", "Monaco"), other.regions.map { it.name })
        assertNull(other.sections.single().title)
    }

    @Test
    fun deepPathsJoinTheirMiddleSegments() {
        val deep = region("x", "Nord-Est", "Europe › Italy › North › Nord-Est")
        val section = CatalogGrouping.group(listOf(deep)).single().sections.single()
        assertEquals("Italy › North", section.title)
    }

    @Test
    fun searchMatchesEveryWordAcrossNameIdAndCoverage() {
        assertEquals(
            listOf("Northern California", "Southern California"),
            CatalogGrouping.group(catalog, "cali").flatMap { it.regions }.map { it.name },
        )
        assertEquals(
            listOf("Scotland", "Wales"),
            CatalogGrouping.group(catalog, "  united   KINGDOM ").flatMap { it.regions }.map { it.name },
        )
        assertEquals(listOf("Southern California"), CatalogGrouping.group(catalog, "south cal").flatMap { it.regions }.map { it.name })
        assertEquals(listOf("Utah"), CatalogGrouping.group(catalog, "us-utah").flatMap { it.regions }.map { it.name })
        assertTrue(CatalogGrouping.group(catalog, "atlantis").isEmpty())
        assertEquals(catalog.size, CatalogGrouping.group(catalog, "   ").sumOf { it.size })
    }

    @Test
    fun groupingIsAPartitionWhateverTheInputOrder() {
        val random = java.util.Random(7)
        repeat(50) {
            val shuffled = catalog.shuffled(random)
            val groups = CatalogGrouping.group(shuffled)
            assertEquals(CatalogGrouping.group(catalog), groups)
            assertEquals(catalog.map { it.id }.toSet(), groups.flatMap { it.regions }.map { it.id }.toSet())
            assertEquals(catalog.size, groups.flatMap { it.regions }.size)
        }
    }

    @Test
    fun onlyFollowedContinentsStartOpen() {
        val groups = CatalogGrouping.group(catalog)
        fun open(name: String, job: String? = null, installed: Set<String> = emptySet(), searching: Boolean = false) =
            CatalogGrouping.initiallyExpanded(groups.first { it.name == name }, groups.size, searching, job, installed)

        assertFalse(open("Europe"))
        assertTrue(open("Europe", job = "wales"))
        assertTrue(open("Europe", installed = setOf("austria")))
        assertFalse(open("Europe", installed = setOf("queensland")))
        assertTrue(open("Europe", searching = true))
        val single = CatalogGrouping.group(catalog.take(1)).single()
        assertTrue(CatalogGrouping.initiallyExpanded(single, 1, false, null, emptySet()))
    }
}
