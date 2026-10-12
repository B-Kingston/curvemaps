package com.curvemaps.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.curvemaps.app.licences.LicenceCatalog
import com.curvemaps.app.licences.LicenceCatalogLoader
import com.curvemaps.app.licences.LicenceEntry
import com.curvemaps.app.licences.LicenceFilter
import com.curvemaps.app.licences.LicenceTextBlock
import com.curvemaps.app.licences.licenceBlocks
import com.curvemaps.app.licences.licenceParagraphs
import com.curvemaps.app.licences.listIcon
import com.curvemaps.app.licences.listSubtitle
import com.curvemaps.app.licences.sections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val OSS_LICENCES_TITLE = "Open-source licences"

/** Description of the scrollable list; tests and the fuzz oracle find the screen by it. */
internal const val OSS_LICENCES_LIST_DESCRIPTION = "Open-source licence list"

/** Prefix of the licence reader's description: "Licence text for <name>". */
internal const val OSS_LICENCE_TEXT_DESCRIPTION_PREFIX = "Licence text for "

private val ScreenGutter = 16.dp

private sealed interface LicenceLoad {
    data object Loading : LicenceLoad
    data class Ready(val catalog: LicenceCatalog) : LicenceLoad
    data class Failed(val message: String) : LicenceLoad
}

/**
 * Full-screen "Open-source licences" screen opened from the settings menu.
 *
 * Lists every map-data/font/style asset and every runtime library the APK
 * ships (generated at build time from the resolved dependency graph, see
 * `app/build.gradle.kts`), each with its licence; tapping one opens its full
 * licence text and notices, read from the APK — never fetched. The shared
 * header back button and system Back return from a licence text to the list,
 * then to the planner. [backEnabled] is false while a surface drawn above this
 * screen (the settings menu) owns system Back.
 */
@Composable
internal fun OpenSourceLicencesScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    loadCatalog: (suspend () -> LicenceCatalog)? = null,
) {
    val context = LocalContext.current
    val load by produceState<LicenceLoad>(LicenceLoad.Loading) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadCatalog?.invoke() ?: LicenceCatalogLoader.load(context.applicationContext) }
                .fold(
                    onSuccess = { LicenceLoad.Ready(it) },
                    onFailure = { LicenceLoad.Failed(it.message ?: it.javaClass.simpleName) },
                )
        }
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val selected = (load as? LicenceLoad.Ready)?.catalog?.let { catalog -> selectedId?.let(catalog::entry) }
    val closeOrBack = {
        if (selectedId != null) selectedId = null else onDismiss()
    }
    BackHandler(enabled = backEnabled, onBack = closeOrBack)
    val stateName = when (load) {
        LicenceLoad.Loading -> "loading"
        is LicenceLoad.Failed -> "error"
        is LicenceLoad.Ready -> selected?.let { "detail:${it.id}" } ?: "list"
    }
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxSize()
            .semantics { this[OssLicencesStateKey] = stateName },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // The header stays pinned so Back is always one tap away, however
            // far a list or a licence text has scrolled.
            HeaderBar(title = selected?.name ?: OSS_LICENCES_TITLE, onBack = closeOrBack)
            when (val current = load) {
                LicenceLoad.Loading -> IntroBlock {
                    BodyText("Reading the licences bundled with the app…")
                }
                is LicenceLoad.Failed -> IntroBlock {
                    BodyText("The bundled licence list could not be read: ${current.message}")
                }
                is LicenceLoad.Ready -> {
                    if (selected == null) {
                        LicenceList(
                            catalog = current.catalog,
                            onOpen = { selectedId = it.id },
                            listState = listState,
                        )
                    } else {
                        LicenceDetail(entry = selected)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderBar(title: String, onBack: () -> Unit) {
    SettingsScreenHeader(title = title, onBack = onBack)
}

/** The first block under the pinned header (and the loading and error messages). */
@Composable
private fun IntroBlock(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 12.dp),
    ) {
        content()
    }
}

@Composable
private fun BodyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LicenceList(
    catalog: LicenceCatalog,
    onOpen: (LicenceEntry) -> Unit,
    listState: LazyListState,
) {
    var filterIndex by rememberSaveable { mutableIntStateOf(LicenceFilter.All.ordinal) }
    val filter = LicenceFilter.entries[filterIndex.coerceIn(0, LicenceFilter.entries.lastIndex)]
    val sections = remember(catalog, filter) { catalog.sections(filter) }
    Column(Modifier.fillMaxSize()) {
        // The chips sit outside the list: they are filters, not licence rows.
        FilterRow(selected = filter, onSelect = { filterIndex = it.ordinal })
        // Rows scroll under the chips; a hairline marks where they are cut instead of a flat edge.
        HorizontalDivider(
            color = if (listState.canScrollBackward) MaterialTheme.colorScheme.outlineVariant else Color.Transparent,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = OSS_LICENCES_LIST_DESCRIPTION },
        ) {
            item(key = "intro") {
                IntroBlock {
                    BodyText(
                        "CurveMaps is built with open map data, fonts and open-source libraries. " +
                            "Tap one to read its licence. Every text is stored in the app.",
                    )
                }
            }
            section("data", filter.dataHeading, sections.data, onOpen)
            section("libraries", "Software libraries (${sections.libraries.size})", sections.libraries, onOpen)
            item(key = "footer") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** The filter chips: All, Map data, Fonts, Libraries (48 dp tall). Scrolls sideways on narrow windows. */
@Composable
private fun FilterRow(selected: LicenceFilter, onSelect: (LicenceFilter) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = ScreenGutter, end = ScreenGutter, top = 4.dp, bottom = 4.dp),
    ) {
        LicenceFilter.entries.forEach { filter ->
            val isSelected = filter == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(filter) },
                modifier = Modifier.heightIn(min = 48.dp),
                label = { Text(filter.label) },
                leadingIcon = if (isSelected) {
                    { Icon(painterResource(R.drawable.ms_check), contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null,
            )
        }
    }
}

private fun LazyListScope.section(
    key: String,
    title: String,
    entries: List<LicenceEntry>,
    onOpen: (LicenceEntry) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "section:$key") {
        SettingsSectionHeading(
            text = title,
            modifier = Modifier.padding(horizontal = ScreenGutter),
        )
    }
    itemsIndexed(entries, key = { _, entry -> "entry:${entry.id}" }) { index, entry ->
        Box(
            Modifier
                .padding(horizontal = ScreenGutter)
                .padding(top = if (index == 0) 0.dp else SettingsGroupShape.RowGap),
        ) {
            LicenceRow(
                entry = entry,
                shape = SettingsGroupShape.shape(index, entries.size),
                onClick = { onOpen(entry) },
            )
        }
    }
}

/**
 * One list row: symbol, name, coordinates/version (or the data entry's first
 * sentence), the licence tag under them and a chevron. The whole row is one
 * click target (at least 56 dp) whose description names the entry and its
 * licence. The tag sits under the text, not beside it, so the name and the
 * coordinates keep the row's width.
 */
@Composable
private fun LicenceRow(entry: LicenceEntry, shape: Shape, onClick: () -> Unit) {
    SettingsRow(
        headline = { Text(entry.name, fontWeight = FontWeight.Medium) },
        supporting = {
            Column {
                entry.listSubtitle()?.let { subtitle ->
                    if (entry.coordinates != null) {
                        // Group:artifact and version stay readable on one line: the middle gives way.
                        Text(subtitle, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    } else {
                        Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(8.dp))
                LicenceTag(entry.licenceSummary)
            }
        },
        leading = { SettingsRowIcon(entry.listIcon()) },
        trailing = { Icon(painterResource(R.drawable.ms_chevron_right), contentDescription = null) },
        modifier = Modifier
            .clip(shape)
            .clickable(onClickLabel = "Read licence", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${entry.name}, ${entry.licenceSummary}"
            },
    )
}

/** The licence tag: an assist-chip-sized label on `surfaceContainerHighest`, 6 dp corners. */
@Composable
private fun LicenceTag(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** The licence reader: who holds the copyright, then every licence and notice in full. */
@Composable
private fun LicenceDetail(entry: LicenceEntry) {
    val blocks = remember(entry) { detailBlocks(entry) }
    SelectionContainer {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = OSS_LICENCE_TEXT_DESCRIPTION_PREFIX + entry.name },
        ) {
            item(key = "intro") {
                IntroBlock {
                    entry.coordinates?.let { BodyText(it) }
                    if (entry.holders.isNotEmpty()) {
                        BodyText("By ${entry.holders.joinToString(", ")}")
                    }
                    if (entry.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        BodyText(entry.description)
                    }
                    entry.website?.let { website ->
                        Spacer(Modifier.height(4.dp))
                        BodyText(website)
                    }
                }
            }
            items(blocks.size, key = { "block:$it" }) { index ->
                when (val block = blocks[index]) {
                    is DetailBlock.Heading -> Text(
                        block.text,
                        style = when (block.level) {
                            1, 2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = ScreenGutter, end = ScreenGutter, top = 12.dp)
                            .semantics { heading() },
                    )
                    is DetailBlock.Paragraph -> Text(
                        block.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (block.monospace) FontFamily.Monospace else null,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ScreenGutter),
                    )
                }
            }
            item(key = "footer") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private sealed interface DetailBlock {
    data class Heading(val text: String, val level: Int = 2) : DetailBlock
    data class Paragraph(val text: String, val monospace: Boolean = false) : DetailBlock
}

private fun detailBlocks(entry: LicenceEntry): List<DetailBlock> = buildList {
    if (entry.notices.isNotEmpty()) {
        add(DetailBlock.Heading(if (entry.notices.size == 1) "Notice" else "Notices"))
        entry.notices.forEach { notice ->
            add(DetailBlock.Paragraph(notice.name))
            licenceParagraphs(notice.text).forEach { add(DetailBlock.Paragraph(it, monospace = true)) }
        }
    }
    if (entry.licences.isEmpty()) {
        add(DetailBlock.Heading(LicenceEntry.NO_LICENCE_LABEL))
    }
    entry.licences.forEach { licence ->
        add(DetailBlock.Heading(licence.name))
        licence.url?.let { add(DetailBlock.Paragraph(it)) }
        if (licence.content.isBlank()) {
            add(DetailBlock.Paragraph("The text of this licence is not bundled with the app."))
        } else {
            licenceBlocks(licence.content).forEach { block ->
                add(
                    when (block) {
                        is LicenceTextBlock.Heading -> DetailBlock.Heading(block.text, level = block.level + 1)
                        is LicenceTextBlock.Body -> DetailBlock.Paragraph(block.text)
                    },
                )
            }
        }
    }
}
