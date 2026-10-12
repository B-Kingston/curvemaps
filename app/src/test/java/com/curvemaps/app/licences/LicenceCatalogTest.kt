package com.curvemaps.app.licences

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Parsing and display order of the build-time licence data (fixtures, no build output). */
class LicenceCatalogTest {

    @Test
    fun parsesLibrariesLicencesHoldersAndNotices() {
        val catalog = LicenceCatalogParser.parse(DEFINITIONS, NOTICES)

        val graphHopper = catalog.entry("com.graphhopper:graphhopper-core")!!
        assertEquals("GraphHopper Core", graphHopper.name)
        assertEquals("11.0", graphHopper.version)
        assertEquals(LicenceEntryKind.LIBRARY, graphHopper.kind)
        assertEquals("Apache-2.0", graphHopper.licenceSummary)
        assertEquals("com.graphhopper:graphhopper-core 11.0", graphHopper.coordinates)
        assertTrue(graphHopper.licences.single().content.startsWith("Apache License"))
        assertEquals(listOf("Peter Karich", "GraphHopper GmbH"), graphHopper.holders)

        val jackson = catalog.entry("com.fasterxml.jackson.core:jackson-core")!!
        assertEquals(listOf("META-INF/NOTICE"), jackson.notices.map { it.name })
        assertTrue(jackson.notices.single().text.contains("Jackson"))
        assertEquals("POM whitespace collapses", "Core Jackson processing abstractions", jackson.description)

        val osm = catalog.entry(LicenceCatalogParser.OPENSTREETMAP_ID)!!
        assertEquals(LicenceEntryKind.DATA, osm.kind)
        assertEquals("ODbL-1.0", osm.licenceSummary)
        assertNull("data entries show no coordinates", osm.coordinates)
    }

    @Test
    fun pomNamedLicencesResolveByTheirMapKeyNotTheRewrittenInnerHash() {
        // The plugin keys a POM-named licence by an MD5 and rewrites the inner
        // `hash` once checked-in text is merged; libraries keep the map key.
        val stax = LicenceCatalogParser.parse(DEFINITIONS, NOTICES).entry("org.codehaus.woodstox:stax2-api")!!
        val licence = stax.licences.single()
        assertEquals("8de635fcf761e7332e0db053c5f20973", licence.key)
        assertEquals("The BSD 2-Clause License", licence.shortName)
        assertTrue(licence.content.contains("Redistribution"))
    }

    @Test
    fun dataComesFirstInItsFixedOrderThenLibrariesByName() {
        val catalog = LicenceCatalogParser.parse(DEFINITIONS, NOTICES)
        assertEquals(
            listOf(LicenceCatalogParser.OPENSTREETMAP_ID, LicenceCatalogParser.NOTO_SANS_ID),
            catalog.data.map { it.id },
        )
        assertEquals(
            listOf("GraphHopper Core", "Jackson-core", "okio", "Stax2 API"),
            catalog.libraries.map { it.name },
        )
        assertEquals(catalog.data + catalog.libraries, catalog.entries)
    }

    @Test
    fun missingLicenceTextAndNamesFallBackHonestly() {
        val definitions = JSONObject()
            .put("libraries", JSONArray()
                .put(library("a.b:no-name", name = "", licences = listOf("Unknown-1.0")))
                .put(library("a.b:no-licence", name = "No licence", licences = emptyList()))
                .put(library("a.b:no-licence", name = "Duplicate", licences = emptyList())))
            .put("licenses", JSONObject())
            .toString()
        val catalog = LicenceCatalogParser.parse(definitions, null)

        val unnamed = catalog.entry("a.b:no-name")!!
        assertEquals("the id stands in for a blank name", "a.b:no-name", unnamed.name)
        assertEquals("Unknown-1.0", unnamed.licenceSummary)
        assertEquals("", unnamed.licences.single().content)
        assertEquals(LicenceEntry.NO_LICENCE_LABEL, catalog.entry("a.b:no-licence")!!.licenceSummary)
        assertEquals("duplicate ids collapse to the first", 2, catalog.entries.size)
        assertTrue(unnamed.notices.isEmpty())
    }

    @Test
    fun sortingIsATotalDeterministicPermutation() {
        val random = Random(7)
        repeat(200) {
            val entries = List(random.nextInt(0, 40)) { index ->
                val data = random.nextInt(4) == 0
                val id = if (data && random.nextBoolean()) {
                    LicenceCatalogParser.DATA_ORDER.random(random)
                } else {
                    "g$index:a${random.nextInt(5)}"
                }
                entry(id, name = listOf("alpha", "Beta", "beta", "Gamma", "ägypten", "").random(random) + index % 3,
                    kind = if (data) LicenceEntryKind.DATA else LicenceEntryKind.LIBRARY)
            }
            val sorted = LicenceCatalogParser.sortForDisplay(entries)
            assertEquals("a permutation of the input", entries.groupingBy { it }.eachCount(),
                sorted.groupingBy { it }.eachCount())
            assertEquals("deterministic", sorted, LicenceCatalogParser.sortForDisplay(entries.shuffled(random)))
            val firstLibrary = sorted.indexOfFirst { it.kind == LicenceEntryKind.LIBRARY }
            if (firstLibrary >= 0) {
                assertTrue("data before libraries", sorted.drop(firstLibrary).all { it.kind == LicenceEntryKind.LIBRARY })
            }
            sorted.filter { it.kind == LicenceEntryKind.LIBRARY }.zipWithNext().forEach { (a, b) ->
                assertTrue("$a then $b", String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name) <= 0)
            }
            val knownData = sorted.filter { it.id in LicenceCatalogParser.DATA_ORDER }
            knownData.zipWithNext().forEach { (a, b) ->
                assertTrue(LicenceCatalogParser.DATA_ORDER.indexOf(a.id) <= LicenceCatalogParser.DATA_ORDER.indexOf(b.id))
            }
        }
    }

    @Test
    fun licenceParagraphsSplitOnBlankLinesWithoutLosingText() {
        val text = "Title\r\n\r\nFirst line\nsecond line\n  \n\nLast\n"
        val paragraphs = licenceParagraphs(text)
        assertEquals(listOf("Title", "First line\nsecond line", "Last"), paragraphs)
        val random = Random(11)
        repeat(100) {
            val words = List(random.nextInt(1, 60)) { "w${random.nextInt(1000)}" }
            val joined = words.joinToString("") { word -> word + listOf(" ", "\n", "\n\n", "\n \n").random(random) }
            assertEquals(words, licenceParagraphs(joined).flatMap { it.split(Regex("\\s+")) }.filter { it.isNotEmpty() })
        }
    }

    private fun entry(id: String, name: String, kind: LicenceEntryKind) = LicenceEntry(
        id = id, name = name, version = null, kind = kind, description = "", website = null,
        holders = emptyList(), licences = emptyList(), notices = emptyList(),
    )

    private companion object {
        fun library(
            id: String,
            name: String,
            licences: List<String>,
            version: String? = null,
            tag: String? = null,
            developers: List<String> = emptyList(),
            organization: String? = null,
        ): JSONObject = JSONObject().apply {
            put("uniqueId", id)
            put("name", name)
            version?.let { put("artifactVersion", it) }
            tag?.let { put("tag", it) }
            put("licenses", JSONArray(licences))
            put("developers", JSONArray(developers.map { JSONObject().put("name", it) }))
            organization?.let { put("organization", JSONObject().put("name", it)) }
        }

        val DEFINITIONS: String = JSONObject()
            .put("libraries", JSONArray()
                .put(library("org.codehaus.woodstox:stax2-api", "Stax2 API", listOf("8de635fcf761e7332e0db053c5f20973"), "4.2.2"))
                .put(library("com.squareup.okio:okio", "okio", listOf("Apache-2.0"), "3.6.0"))
                .put(library("curvemaps.data:noto-sans", "Noto Sans (map label glyphs)", listOf("OFL-1.1"), tag = "data"))
                .put(library("com.graphhopper:graphhopper-core", "GraphHopper Core", listOf("Apache-2.0"), "11.0",
                    developers = listOf("Peter Karich", "GraphHopper GmbH"), organization = "GraphHopper GmbH"))
                .put(library("com.fasterxml.jackson.core:jackson-core", "Jackson-core", listOf("Apache-2.0"), "2.19.2")
                    .put("description", "Core Jackson\n        processing abstractions"))
                .put(library("curvemaps.data:openstreetmap", "OpenStreetMap data", listOf("ODbL-1.0"), tag = "data")))
            .put("licenses", JSONObject()
                .put("Apache-2.0", JSONObject().put("name", "Apache License 2.0").put("spdxId", "Apache-2.0")
                    .put("hash", "Apache-2.0").put("content", "Apache License\nVersion 2.0, January 2004\n"))
                .put("8de635fcf761e7332e0db053c5f20973", JSONObject().put("name", "The BSD 2-Clause License")
                    .put("hash", "fd683a5a363968ed70c708b9e23fbb9c").put("content", "Redistribution and use ..."))
                .put("ODbL-1.0", JSONObject().put("name", "Open Database License").put("spdxId", "ODbL-1.0")
                    .put("content", "ODC Open Database License (ODbL)"))
                .put("OFL-1.1", JSONObject().put("name", "SIL Open Font License 1.1").put("spdxId", "OFL-1.1")
                    .put("content", "SIL OPEN FONT LICENSE")))
            .toString()

        val NOTICES: String = JSONObject()
            .put("com.fasterxml.jackson.core:jackson-core", JSONArray()
                .put(JSONObject().put("name", "META-INF/NOTICE").put("text", "# Jackson JSON processor"))
                .put(JSONObject().put("name", "META-INF/EMPTY").put("text", "  ")))
            .toString()
    }
}
