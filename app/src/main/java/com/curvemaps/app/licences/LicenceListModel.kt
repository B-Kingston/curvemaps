package com.curvemaps.app.licences

import androidx.annotation.DrawableRes
import com.curvemaps.app.R

/**
 * The filter chips above the Open-source licences list. Pure, so the grouping
 * is pinned by a JVM test: every data entry belongs to exactly one of
 * [MapData] and [Fonts], every library to [Libraries], and [All] shows all.
 */
enum class LicenceFilter(val label: String) {
    All("All"),
    MapData("Map data"),
    Fonts("Fonts"),
    Libraries("Libraries"),
    ;

    fun matches(entry: LicenceEntry): Boolean = when (this) {
        All -> true
        MapData -> entry.kind == LicenceEntryKind.DATA && entry.id !in FONT_AND_ICON_IDS
        Fonts -> entry.kind == LicenceEntryKind.DATA && entry.id in FONT_AND_ICON_IDS
        Libraries -> entry.kind == LicenceEntryKind.LIBRARY
    }

    /** Heading of the data section while this filter is selected. */
    val dataHeading: String
        get() = when (this) {
            MapData -> "Map data and style"
            Fonts -> "Fonts and icons"
            else -> "Map data, fonts and icons"
        }

    companion object {
        /** The data entries that are typefaces or icon sets, not map data or map style. */
        val FONT_AND_ICON_IDS = setOf(
            LicenceCatalogParser.NOTO_SANS_ID,
            LicenceCatalogParser.MATERIAL_SYMBOLS_ID,
        )
    }
}

/** The data and library entries a [LicenceFilter] shows, each in catalog order. */
data class LicenceSections(val data: List<LicenceEntry>, val libraries: List<LicenceEntry>) {
    val isEmpty: Boolean get() = data.isEmpty() && libraries.isEmpty()
}

fun LicenceCatalog.sections(filter: LicenceFilter): LicenceSections =
    LicenceSections(
        data = data.filter(filter::matches),
        libraries = libraries.filter(filter::matches),
    )

/**
 * The second line of a list row: coordinates and version for a library, the
 * first sentence of the description for a data entry (the full text opens in
 * the reader). Null when there is nothing to say.
 */
fun LicenceEntry.listSubtitle(): String? {
    coordinates?.let { return it }
    val text = description.trim()
    if (text.isEmpty()) return null
    val end = Regex("""[.!?](\s|$)""").find(text)?.range?.first
    return if (end == null) text else text.substring(0, end).trim().ifEmpty { text }
}

/** A row's leading symbol: one per data entry, a few well-known libraries, else a generic code symbol. */
@DrawableRes
fun LicenceEntry.listIcon(): Int = when {
    id == LicenceCatalogParser.OPENSTREETMAP_ID -> R.drawable.ms_map
    id == LicenceCatalogParser.OPENMAPTILES_ID -> R.drawable.ms_layers
    id == LicenceCatalogParser.OSM_BRIGHT_ID -> R.drawable.ms_location_on
    id == LicenceCatalogParser.NOTO_SANS_ID -> R.drawable.ms_font_download
    id == LicenceCatalogParser.MATERIAL_SYMBOLS_ID -> R.drawable.ms_license
    id.startsWith("com.graphhopper:") -> R.drawable.ms_route
    id.startsWith("org.maplibre") -> R.drawable.ms_public
    id.startsWith("org.slf4j:") -> R.drawable.ms_terminal
    else -> R.drawable.ms_data_object
}
