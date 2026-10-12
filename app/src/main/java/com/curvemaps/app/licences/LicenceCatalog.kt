package com.curvemaps.app.licences

import org.json.JSONArray
import org.json.JSONObject

/** What an entry on the Open-source licences screen is. */
enum class LicenceEntryKind {
    /** Map data, fonts and style assets: not Gradle dependencies, listed from app/licences/libraries. */
    DATA,

    /** A library on the variant's runtime classpath, found by the AboutLibraries plugin. */
    LIBRARY,
}

/** One licence text bundled in the APK. */
data class LicenceText(
    /** Key in the generated definitions (an SPDX id, or the plugin's hash for a POM-named licence). */
    val key: String,
    val name: String,
    val spdxId: String?,
    val url: String?,
    /** Full licence text; blank only when the build shipped none (ReleaseLicenceCoverageTest forbids it). */
    val content: String,
) {
    /** Short label for list rows: the SPDX id where there is one. */
    val shortName: String get() = spdxId?.takeIf { it.isNotBlank() } ?: name
}

/** A NOTICE/LICENSE file from a shipped artifact, or a checked-in upstream copyright notice. */
data class LicenceNotice(val name: String, val text: String)

/** One row on the screen: a library or a data/font/style asset with its licences. */
data class LicenceEntry(
    /** `group:artifact` for libraries, `curvemaps.data:<name>` for data entries. */
    val id: String,
    val name: String,
    val version: String?,
    val kind: LicenceEntryKind,
    val description: String,
    val website: String?,
    /** Developers and organisation from the POM (or the data entry), distinct, in order. */
    val holders: List<String>,
    val licences: List<LicenceText>,
    val notices: List<LicenceNotice>,
) {
    /** "Apache-2.0", "CC-BY-4.0, BSD-3-Clause", or a clear fallback. */
    val licenceSummary: String
        get() = licences.joinToString(", ") { it.shortName }.ifBlank { NO_LICENCE_LABEL }

    /** Secondary line: coordinates and version for libraries, nothing extra for data. */
    val coordinates: String?
        get() = if (kind == LicenceEntryKind.LIBRARY) listOfNotNull(id, version).joinToString(" ") else null

    companion object {
        const val NO_LICENCE_LABEL = "Licence not stated"
    }
}

/** Everything the Open-source licences screen shows, ordered for display. */
class LicenceCatalog(val entries: List<LicenceEntry>) {
    val data: List<LicenceEntry> get() = entries.filter { it.kind == LicenceEntryKind.DATA }
    val libraries: List<LicenceEntry> get() = entries.filter { it.kind == LicenceEntryKind.LIBRARY }

    fun entry(id: String): LicenceEntry? = entries.firstOrNull { it.id == id }
}

/**
 * Parses the build-time licence data:
 *
 * - `definitionsJson`: the AboutLibraries plugin's `aboutlibraries.json`
 *   (`libraries` array and `licenses` object). Licence objects are looked up
 *   by their map key, which is what libraries reference; the plugin rewrites
 *   the inner `hash` of a POM-named licence once its text is merged in.
 * - `noticesJson`: `licences/notices.json` from `collect<Variant>LicenceNotices`,
 *   `{"group:artifact": [{"name", "text"}]}`.
 *
 * Data entries (tag [DATA_TAG]) come first in [DATA_ORDER], then libraries by
 * name, case-insensitively (then case-sensitively, then by id, so the order
 * never depends on the input order).
 */
object LicenceCatalogParser {
    const val DATA_TAG = "data"

    const val OPENSTREETMAP_ID = "curvemaps.data:openstreetmap"
    const val OPENMAPTILES_ID = "curvemaps.data:openmaptiles"
    const val OSM_BRIGHT_ID = "curvemaps.data:osm-bright-sprites"
    const val NOTO_SANS_ID = "curvemaps.data:noto-sans"
    const val MATERIAL_SYMBOLS_ID = "curvemaps.data:material-symbols"

    /** The data, font and style entries the app must always credit, in display order. */
    val DATA_ORDER = listOf(
        OPENSTREETMAP_ID, OPENMAPTILES_ID, OSM_BRIGHT_ID, NOTO_SANS_ID, MATERIAL_SYMBOLS_ID,
    )

    fun parse(definitionsJson: String, noticesJson: String?): LicenceCatalog {
        val root = JSONObject(definitionsJson)
        val licences = parseLicences(root.optJSONObject("licenses") ?: JSONObject())
        val notices = noticesJson?.takeIf { it.isNotBlank() }?.let { parseNotices(JSONObject(it)) }.orEmpty()
        val libraries = root.optJSONArray("libraries") ?: JSONArray()
        val entries = (0 until libraries.length()).mapNotNull { index ->
            libraries.optJSONObject(index)?.let { parseEntry(it, licences, notices) }
        }.distinctBy { it.id }
        return LicenceCatalog(sortForDisplay(entries))
    }

    fun sortForDisplay(entries: List<LicenceEntry>): List<LicenceEntry> {
        val data = entries.filter { it.kind == LicenceEntryKind.DATA }.sortedWith(
            compareBy<LicenceEntry> { entry ->
                DATA_ORDER.indexOf(entry.id).let { if (it < 0) Int.MAX_VALUE else it }
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name }.thenBy { it.id },
        )
        val libraries = entries.filter { it.kind == LicenceEntryKind.LIBRARY }.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { entry: LicenceEntry -> entry.name }
                .thenBy { it.name }
                .thenBy { it.id },
        )
        return data + libraries
    }

    private fun parseLicences(json: JSONObject): Map<String, LicenceText> = buildMap {
        json.keys().forEach { key ->
            val licence = json.optJSONObject(key) ?: return@forEach
            put(
                key,
                LicenceText(
                    key = key,
                    name = licence.optString("name").ifBlank { key },
                    spdxId = licence.optStringOrNull("spdxId"),
                    url = licence.optStringOrNull("url"),
                    content = licence.optString("content").trim(),
                ),
            )
        }
    }

    private fun parseNotices(json: JSONObject): Map<String, List<LicenceNotice>> = buildMap {
        json.keys().forEach { module ->
            val files = json.optJSONArray(module) ?: return@forEach
            put(
                module,
                (0 until files.length()).mapNotNull { index ->
                    val file = files.optJSONObject(index) ?: return@mapNotNull null
                    val text = file.optString("text").trim()
                    if (text.isEmpty()) null else LicenceNotice(file.optString("name").ifBlank { "NOTICE" }, text)
                },
            )
        }
    }

    private fun parseEntry(
        json: JSONObject,
        licences: Map<String, LicenceText>,
        notices: Map<String, List<LicenceNotice>>,
    ): LicenceEntry? {
        val id = json.optString("uniqueId").trim()
        if (id.isEmpty()) return null
        val licenceKeys = json.optJSONArray("licenses") ?: JSONArray()
        val entryLicences = (0 until licenceKeys.length()).mapNotNull { index ->
            licenceKeys.optString(index).takeIf { it.isNotBlank() }
        }.distinct().map { key ->
            licences[key] ?: LicenceText(key = key, name = key, spdxId = null, url = null, content = "")
        }
        val holders = buildList {
            val developers = json.optJSONArray("developers") ?: JSONArray()
            for (index in 0 until developers.length()) {
                developers.optJSONObject(index)?.optStringOrNull("name")?.let(::add)
            }
            json.optJSONObject("organization")?.optStringOrNull("name")?.let(::add)
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return LicenceEntry(
            id = id,
            name = json.optString("name").trim().ifBlank { id },
            version = json.optStringOrNull("artifactVersion"),
            kind = if (json.optString("tag") == DATA_TAG) LicenceEntryKind.DATA else LicenceEntryKind.LIBRARY,
            // POM descriptions often carry the XML's line breaks and indentation.
            description = json.optString("description").replace(Regex("\\s+"), " ").trim(),
            website = json.optStringOrNull("website"),
            holders = holders,
            licences = entryLicences,
            notices = notices[id].orEmpty(),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
}

/**
 * Splits a licence text into paragraphs for a lazily laid-out reader: long
 * texts (the LGPL with the GPL it incorporates is ~75 KB) stay scrollable
 * without laying out one huge Text.
 */
fun licenceParagraphs(text: String): List<String> =
    text.replace("\r\n", "\n")
        .split(Regex("\n(?:[ \t]*\n)+"))
        .map { it.trimStart('\n').trimEnd() }
        .filter { it.isNotBlank() }

/** One piece of a licence text for the reader: a heading or a reflowed paragraph. */
sealed interface LicenceTextBlock {
    data class Heading(val level: Int, val text: String) : LicenceTextBlock
    data class Body(val text: String) : LicenceTextBlock
}

private val MarkdownHeading = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
private val ListMarker = Regex("""^\s*(?:[-*•+]\s|\(?[0-9a-zA-Z]{1,3}[.)]\s|\([0-9a-zA-Z]{1,3}\)\s)""")

/**
 * Turns a licence text into reader blocks: markdown headings (`##`, `###`)
 * become headings instead of showing their markers, and a paragraph that was
 * hard-wrapped at ~80 columns is joined into one flowing paragraph so it wraps
 * to the screen instead of leaving orphan words. A line break survives where it
 * means something: before an indented line or a list item, and after a line much
 * shorter than the paragraph's longest (an address block, a signature).
 */
fun licenceBlocks(text: String): List<LicenceTextBlock> = buildList {
    for (paragraph in licenceParagraphs(text)) {
        val lines = paragraph.lines()
        var pending = ArrayList<String>()
        fun flush() {
            if (pending.isNotEmpty()) add(LicenceTextBlock.Body(reflowParagraph(pending)))
            pending = ArrayList()
        }
        for (line in lines) {
            val heading = MarkdownHeading.matchEntire(line)
            if (heading != null && heading.groupValues[2].isNotBlank()) {
                flush()
                add(LicenceTextBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]))
            } else {
                pending.add(line)
            }
        }
        flush()
    }
}

internal fun reflowParagraph(lines: List<String>): String {
    if (lines.size == 1) return lines[0].trim()
    val longest = lines.maxOf { it.trim().length }
    val out = StringBuilder()
    lines.forEachIndexed { i, raw ->
        val line = raw.trim()
        if (i > 0) {
            val previous = lines[i - 1].trim()
            val keepBreak = raw.startsWith(" ") || raw.startsWith("\t") ||
                ListMarker.containsMatchIn(raw) || previous.length < longest * 0.5
            out.append(if (keepBreak) '\n' else ' ')
        }
        out.append(line)
    }
    return out.toString()
}
