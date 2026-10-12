package com.curvemaps.app.region

import java.util.Locale

/** Regions that share one middle coverage segment (e.g. "Australia"), or none. */
data class CatalogSection(val title: String?, val regions: List<ServerRegion>)

/** One continent of the catalog: standalone regions first, then titled sections. */
data class CatalogGroup(val name: String, val sections: List<CatalogSection>) {
    val regions: List<ServerRegion> get() = sections.flatMap { it.regions }
    val size: Int get() = sections.sumOf { it.regions.size }
}

/**
 * Organizes the server catalog for the Maps screen. The server's `coverage` is
 * a Geofabrik path ("Europe › United Kingdom › Wales"): its first segment is
 * the continent group and anything between that and the region itself titles
 * a section ("United Kingdom"). Regions without such a path go to [OTHER].
 * Pure so the ordering and search rules are JVM-testable.
 */
object CatalogGrouping {

    const val OTHER = "Other regions"

    private const val SEPARATOR = '›'

    fun path(region: ServerRegion): List<String> =
        region.coverage.orEmpty().split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Groups [catalog] by continent, keeping only regions that match [query].
     * Groups are alphabetical with [OTHER] last; within a group the untitled
     * section comes first and titled sections follow alphabetically; regions
     * are alphabetical by name.
     */
    fun group(catalog: List<ServerRegion>, query: String = ""): List<CatalogGroup> {
        val byName = compareBy<ServerRegion, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        return catalog
            .filter { matches(it, query) }
            .groupBy { continentOf(it) }
            .map { (continent, regions) ->
                val sections = regions
                    .groupBy { sectionOf(it) }
                    .map { (title, members) -> CatalogSection(title, members.sortedWith(byName)) }
                    .sortedWith(compareBy<CatalogSection> { it.title != null }.thenBy { it.title?.lowercase(Locale.ROOT) })
                CatalogGroup(continent, sections)
            }
            .sortedWith(compareBy<CatalogGroup> { it.name == OTHER }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    /** Every whitespace-separated word of [query] appears in the name, id or coverage. */
    fun matches(region: ServerRegion, query: String): Boolean {
        val words = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = listOf(region.name, region.id, region.coverage.orEmpty())
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        return words.all { haystack.contains(it) }
    }

    /**
     * Whether a group starts open: always when it is the only group or a
     * search is active, otherwise when it holds something the rider is
     * following (a running build, or a region installed on this device).
     */
    fun initiallyExpanded(
        group: CatalogGroup,
        groupCount: Int,
        searching: Boolean,
        activeJobRegionId: String?,
        installedRegionIds: Set<String>,
    ): Boolean = groupCount == 1 || searching || group.regions.any {
        it.id == activeJobRegionId || it.id in installedRegionIds
    }

    private fun continentOf(region: ServerRegion): String {
        val path = path(region)
        return if (path.size >= 2) path.first() else OTHER
    }

    private fun sectionOf(region: ServerRegion): String? {
        val path = path(region)
        if (path.size < 3) return null
        return path.subList(1, path.size - 1).joinToString(" $SEPARATOR ")
    }
}
