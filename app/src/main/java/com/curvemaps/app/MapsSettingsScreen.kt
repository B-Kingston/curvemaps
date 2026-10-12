@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.curvemaps.app

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.curvemaps.app.region.CatalogAction
import com.curvemaps.app.region.CatalogGroup
import com.curvemaps.app.region.CatalogGrouping
import com.curvemaps.app.region.DownloadPhase
import com.curvemaps.app.region.DownloadProgress
import com.curvemaps.app.region.InstalledRegion
import com.curvemaps.app.region.InstalledUpdateModel
import com.curvemaps.app.region.InterruptedDownload
import com.curvemaps.app.region.MapUpdateStatus
import com.curvemaps.app.region.MapsState
import com.curvemaps.app.region.RecoveredPackage
import com.curvemaps.app.region.RegionDatasets
import com.curvemaps.app.region.RegionUiModel
import com.curvemaps.app.region.SavedPackage
import com.curvemaps.app.region.ServerRegion
import com.curvemaps.app.ui.theme.CurveMapsTheme

// One spacing scale for the whole screen (Maps.dc.html): 16 dp screen gutter and
// gap between cards, 20 dp card padding, 12 dp between a card's own blocks, 8 dp
// between sibling buttons, 4 dp between stacked text lines.
private val ScreenGutter = 16.dp
private val CardGap = 16.dp
private val CardPadding = 20.dp
private val BlockGap = 12.dp
private val ButtonGap = 8.dp
private val LineGap = 4.dp

/** Every icon button, search pill and list action keeps this minimum target. */
private val Target = 48.dp

/** The leading circle of an installed-map row. */
private val LeadingCircle = 40.dp

/** Catalog and download cards are 20 dp rounded in the mockups (MapsCatalog.dc.html). */
private val CatalogShape = RoundedCornerShape(20.dp)

private val CardShape: RoundedCornerShape @Composable @ReadOnlyComposable get() = RoundedCornerShape(28.dp)
private val MutedInk: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Positive: Color @Composable @ReadOnlyComposable get() = CurveMapsTheme.colors.success
private val Warning: Color @Composable @ReadOnlyComposable get() = CurveMapsTheme.colors.warning
private val Danger: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error

/** The colour pair of a pill or banner: a container role and the matching on-container role. */
private enum class Tone { NEUTRAL, ACCENT, POSITIVE, WARNING, DANGER }

@Composable
@ReadOnlyComposable
private fun Tone.container(): Color = when (this) {
    Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh
    Tone.ACCENT -> MaterialTheme.colorScheme.primaryContainer
    Tone.POSITIVE -> CurveMapsTheme.colors.successContainer
    Tone.WARNING -> CurveMapsTheme.colors.warningContainer
    Tone.DANGER -> MaterialTheme.colorScheme.errorContainer
}

@Composable
@ReadOnlyComposable
private fun Tone.onContainer(): Color = when (this) {
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.ACCENT -> MaterialTheme.colorScheme.onPrimaryContainer
    Tone.POSITIVE -> CurveMapsTheme.colors.onSuccessContainer
    Tone.WARNING -> CurveMapsTheme.colors.onWarningContainer
    Tone.DANGER -> MaterialTheme.colorScheme.onErrorContainer
}

/**
 * Maps screen: browse the CurveMaps map service (fixed; riders cannot change it),
 * download and install complete region packages in one tap, find and build any
 * other region by name or location, import an exported package offline, and
 * manage the installed maps.
 *
 * Nothing contacts a server until the user taps Browse maps. Download & install
 * stages, verifies, installs and activates the package; "Save a copy" instead
 * writes a `.motomap` file to a location the user picks. Deleting an installed
 * map removes only the app-managed copy: a file in Files is user-managed.
 * Activation and active-map deletion are refused while a ride is active:
 * switching datasets mid-guidance would pull the graph out from under the
 * router.
 *
 * Layout (Maps.dc.html, MapsCatalog.dc.html, DeleteMap.dc.html): a header (back,
 * "Maps", one-line purpose, the active map) that clears the action rail, then
 * one Material 3 card per job: the map service, any transfer in progress, find a
 * region, the catalog (grouped by continent and country, collapsible,
 * searchable), installed maps, and the outlined offline import. The delete
 * confirmation is an `AlertDialog`, the "it installs itself" hint a `Snackbar`.
 */
@Composable
internal fun MapsSettingsScreen(
    state: MapsState,
    rideActive: Boolean,
    onDismiss: () -> Unit,
    onBrowseMaps: () -> Unit,
    onRequestBuild: (String) -> Unit,
    onCancelBuild: () -> Unit,
    onDownloadPackage: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onSaveRecoveredPackage: () -> Unit,
    onImportRecoveredPackage: () -> Unit,
    onImportSavedPackage: () -> Unit,
    onImportPackage: () -> Unit,
    onActivate: (String) -> Unit,
    onActivateBundled: () -> Unit,
    onRemove: (String) -> Unit,
    onDismissMessage: () -> Unit = {},
    onInstallPackage: (String) -> Unit = {},
    onSearchRegions: (String) -> Unit = {},
    onNearMe: (() -> Unit)? = null,
    onClearSearch: () -> Unit = {},
    onGetExtract: (String) -> Unit = {},
) {
    var deleteTarget by remember { mutableStateOf<InstalledRegion?>(null) }
    // A replaced copy waiting to be deleted is not listed: an update reads as
    // the one map changing in place.
    val visible = RegionUiModel.visibleInstalls(
        state.installed,
        state.activeInstallId,
        state.pendingRemoval?.installId,
        state.failedRemovals,
    )
    // The followed build whose row shows progress; the hint about it is a
    // snackbar the rider can dismiss, once per job.
    val followedJob = state.activeJob?.takeIf { job ->
        job.isActive && state.catalog.any { it.id == job.regionId && !it.disabled }
    }
    var dismissedHintFor by rememberSaveable { mutableStateOf<String?>(null) }
    val hintVisible = followedJob != null && dismissedHintFor != followedJob.id
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                this[ActiveRegionKey] = state.activeInstallId
                    ?: if (RegionDatasets.bundledAvailable) "bundled" else "none"
                this[InstalledRegionCountKey] = state.installed.size
                this[MapsServerCheckedKey] = state.serverChecked
                this[MapsActiveJobKey] = state.activeJob?.state ?: "none"
                this[MapsDownloadingKey] = state.download != null
                this[MapsRideActiveKey] = rideActive
                this[MapsSavedFileKey] = state.savedPackage?.fileName ?: "none"
                this[MapsInterruptedKey] = state.interrupted != null
                this[MapsPendingRemovalKey] = state.pendingRemoval?.installId ?: "none"
            },
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(CardGap),
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    // Room to scroll the last card clear of the hint snackbar.
                    .padding(bottom = if (hintVisible) 96.dp else 24.dp),
            ) {
                HeaderBlock(state = state, onDismiss = onDismiss)

                Column(
                    verticalArrangement = Arrangement.spacedBy(CardGap),
                    modifier = Modifier.padding(horizontal = ScreenGutter),
                ) {
                    state.error?.let { message ->
                        MessageBanner(message, Tone.DANGER, "Dismiss error", onDismissMessage)
                    }
                    state.notice?.let { message ->
                        MessageBanner(message, Tone.POSITIVE, "Dismiss message", onDismissMessage)
                    }

                    ServiceCard(
                        state = state,
                        offerUpdateCheck = visible.isNotEmpty() && !state.serverChecked &&
                            state.serverAddress.isNotBlank(),
                        onBrowseMaps = onBrowseMaps,
                    )

                    state.download?.let { progress ->
                        DownloadCard(progress = progress, onCancel = onCancelDownload)
                    }
                    state.savedPackage?.let { saved ->
                        MapsCard(title = "Saved to device") {
                            SavedPackageRow(saved = saved, busy = state.busy, onImportSavedPackage = onImportSavedPackage)
                        }
                    }
                    state.recoveredPackage?.let { recovered ->
                        MapsCard(title = "Downloaded package in app storage") {
                            RecoveredPackageRow(
                                recovered = recovered,
                                busy = state.busy || state.download != null,
                                onSaveRecoveredPackage = onSaveRecoveredPackage,
                                onImportRecoveredPackage = onImportRecoveredPackage,
                            )
                        }
                    }
                    state.interrupted?.let { interrupted ->
                        MapsCard(title = "Interrupted download") {
                            InterruptedRow(
                                interrupted = interrupted,
                                enabled = state.download == null,
                                onResumeDownload = onResumeDownload,
                            )
                        }
                    }

                    if (state.serverChecked && state.generationEnabled) {
                        FindRegionCard(
                            state = state,
                            onSearch = onSearchRegions,
                            onNearMe = onNearMe,
                            onClear = onClearSearch,
                            onGet = onGetExtract,
                        )
                    }

                    if (state.catalog.isNotEmpty()) {
                        MapsCard(title = "Available regions") {
                            CatalogBrowser(
                                state = state,
                                onRequestBuild = onRequestBuild,
                                onInstallPackage = onInstallPackage,
                                onSaveCopy = onDownloadPackage,
                                onStopWaiting = onCancelBuild,
                            )
                        }
                    }

                    InstalledMapsCard(
                        state = state,
                        visible = visible,
                        rideActive = rideActive,
                        onInstallPackage = onInstallPackage,
                        onActivate = onActivate,
                        onActivateBundled = onActivateBundled,
                        onDelete = { deleteTarget = it },
                    )

                    OfflineImportCard(busy = state.busy, onImportPackage = onImportPackage)

                    Text(
                        "A region package bundles the basemap, the routing graph, and offline place search. " +
                            "Download & install makes a map work offline right away; Save a copy also keeps " +
                            "a .motomap file in your Files. Map data © OpenStreetMap contributors.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedInk,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (hintVisible && followedJob != null) {
                Snackbar(
                    dismissAction = {
                        IconButton(
                            onClick = { dismissedHintFor = followedJob.id },
                            modifier = Modifier
                                .size(Target)
                                .semantics { contentDescription = "Dismiss hint" },
                        ) { MsIcon(R.drawable.ms_close, size = 20.dp) }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = ScreenGutter, end = ScreenGutter, bottom = 28.dp),
                ) { Text("It installs itself when it's ready.") }
            }
        }

        deleteTarget?.let { target ->
            DeleteMapDialog(
                target = target,
                active = state.activeInstallId == target.installId,
                onCancel = { deleteTarget = null },
                onConfirm = {
                    deleteTarget = null
                    onRemove(target.installId)
                },
            )
        }
    }
}

// --- building blocks ------------------------------------------------------------

/** A Material Symbols Rounded icon; decorative unless a parent describes it. */
@Composable
private fun MsIcon(
    @DrawableRes id: Int,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** Names a control for accessibility and keeps its layout bounds at the 48 dp target. */
private fun Modifier.described(description: String): Modifier =
    // M3 buttons are 40 dp tall (the rest of the 48 dp is invisible touch padding);
    // the app measures visible bounds, so the button itself is 48 dp.
    sizeIn(minWidth = Target, minHeight = Target).semantics { contentDescription = description }

@Composable
private fun HeaderBlock(state: MapsState, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 4.dp),
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(Target)
                    .semantics { contentDescription = "Back" },
            ) { MsIcon(R.drawable.ms_arrow_back, tint = MaterialTheme.colorScheme.onSurface) }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Maps",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = LineGap, bottom = LineGap)
                    .semantics { heading() },
            )
            Text(
                "Offline region packages: map, routing and place search.",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedInk,
            )
            Spacer(Modifier.height(BlockGap))
            // One accessibility node: "Active map Queensland (bundled)".
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Active map ${state.activeRegionName}"
                },
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (state.pendingRemoval != null) Warning else Positive, CircleShape),
                )
                Spacer(Modifier.width(ButtonGap))
                Text("Active map", style = MaterialTheme.typography.labelLarge, color = MutedInk)
                Spacer(Modifier.width(ButtonGap))
                Text(
                    state.activeRegionName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(LineGap))
        }
    }
}

/** A filled card (surface container low, extra-large corners) with a heading. */
@Composable
private fun MapsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(BlockGap),
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardPadding),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun RowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = modifier)
}

/** A short status label: a container-role capsule with optional leading icon. */
@Composable
private fun StatusPill(
    text: String,
    tone: Tone,
    @DrawableRes icon: Int? = null,
    shape: Shape = MaterialTheme.shapes.small,
    container: Color = tone.container(),
    content: Color = tone.onContainer(),
) {
    Surface(shape = shape, color = container, contentColor = content) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LineGap),
            modifier = Modifier
                .heightIn(min = 20.dp)
                .padding(start = if (icon != null) 6.dp else 8.dp, end = if (icon != null) 10.dp else 8.dp),
        ) {
            if (icon != null) MsIcon(icon, size = 16.dp)
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The "Active" capsule on an installed-map row. */
@Composable
private fun ActivePill() {
    StatusPill(
        text = "Active",
        tone = Tone.ACCENT,
        shape = CircleShape,
        container = MaterialTheme.colorScheme.primary,
        content = MaterialTheme.colorScheme.onPrimary,
    )
}

@Composable
private fun MessageBanner(text: String, tone: Tone, dismissDescription: String, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = tone.container(),
        contentColor = tone.onContainer(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = ButtonGap),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(Target)
                    .semantics { contentDescription = dismissDescription },
            ) { MsIcon(R.drawable.ms_close, size = 20.dp) }
        }
    }
}

@Composable
private fun SecondaryText(text: String, color: Color = MutedInk) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

/** A button's text with an optional leading symbol, as in the mockups. */
@Composable
private fun ButtonLabel(text: String, @DrawableRes icon: Int? = null) {
    if (icon != null) {
        MsIcon(icon, size = ButtonDefaults.IconSize)
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    }
    Text(text)
}

private val WithIconPadding: PaddingValues get() = ButtonDefaults.ButtonWithIconContentPadding

/** The pill input of the mockups: 56 dp, full corners, a leading slot and an optional trailing action. */
@Composable
private fun PillField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    description: String,
    container: Color,
    leading: @Composable () -> Unit,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(container, CircleShape)
            .padding(horizontal = 4.dp),
    ) {
        Box(Modifier.size(Target), contentAlignment = Alignment.Center) { leading() }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .semantics { contentDescription = description },
            decorationBox = { inner ->
                Box(Modifier.heightIn(min = 56.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MutedInk)
                    }
                    inner()
                }
            },
        )
        trailing?.invoke()
    }
}

// --- cards ----------------------------------------------------------------------

/**
 * The CurveMaps map service. Its address is fixed by the build; nothing is
 * fetched until the rider taps Browse maps, and the catalog only refreshes on
 * its own after that first connection. Before the catalog is loaded, installed
 * maps get a Check for updates button here (it loads the same catalog).
 */
@Composable
private fun ServiceCard(state: MapsState, offerUpdateCheck: Boolean, onBrowseMaps: () -> Unit) {
    val connected = state.serverChecked
    val serviceName = RegionUiModel.serviceLabel(state.serverAddress, state.usingDefaultServer)
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(BlockGap),
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardPadding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BlockGap)) {
                Box(
                    Modifier
                        .size(LeadingCircle)
                        .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) { MsIcon(R.drawable.ms_cloud, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Map service",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        serviceName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedInk,
                        modifier = Modifier.semantics { contentDescription = "Map service $serviceName" },
                    )
                }
                if (connected) {
                    StatusPill("Connected", Tone.POSITIVE, icon = R.drawable.ms_check_circle_fill)
                } else {
                    StatusPill("Not connected", Tone.NEUTRAL)
                }
            }
            SecondaryText(
                if (connected) {
                    buildList {
                        add(
                            when (state.catalog.size) {
                                0 -> "no regions offered yet"
                                1 -> "1 region ready"
                                else -> "${state.catalog.size} regions"
                            },
                        )
                        if (state.serverVersion.isNotBlank() && state.serverVersion != "edge") add("v${state.serverVersion}")
                    }.joinToString(" · ")
                } else {
                    "Download maps for your region. Nothing is sent until you tap Browse maps."
                },
                if (connected) Positive else MutedInk,
            )
            if (connected && !state.generationEnabled) {
                SecondaryText(
                    state.generationMessage.ifBlank {
                        "New builds are paused; ready maps can still be downloaded."
                    },
                    Warning,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ButtonGap),
                verticalArrangement = Arrangement.spacedBy(ButtonGap),
            ) {
                Button(
                    onClick = onBrowseMaps,
                    enabled = !state.busy && state.serverAddress.isNotBlank(),
                    contentPadding = WithIconPadding,
                    modifier = Modifier.described(if (connected) "Refresh maps" else "Browse maps"),
                ) {
                    ButtonLabel(
                        text = when {
                            state.busy && !connected -> "Loading maps…"
                            connected -> "Refresh maps"
                            else -> "Browse maps"
                        },
                        icon = if (connected) R.drawable.ms_refresh else R.drawable.ms_public,
                    )
                }
                if (offerUpdateCheck) {
                    TextButton(
                        onClick = onBrowseMaps,
                        enabled = !state.busy,
                        contentPadding = WithIconPadding,
                        modifier = Modifier.described("Check for map updates"),
                    ) {
                        ButtonLabel(
                            text = if (state.busy) "Checking for updates…" else "Check for updates",
                            icon = R.drawable.ms_refresh,
                        )
                    }
                }
            }
        }
    }
}

/** The catalog gets a filter field once it is longer than this. */
private const val CATALOG_SEARCH_THRESHOLD = 8

/**
 * The loaded catalog, grouped by continent (collapsible) and by country inside
 * it, with a filter field for long lists. A continent starts closed unless it
 * holds an installed region or a build being followed.
 */
@Composable
private fun CatalogBrowser(
    state: MapsState,
    onRequestBuild: (String) -> Unit,
    onInstallPackage: (String) -> Unit,
    onSaveCopy: (String) -> Unit,
    onStopWaiting: () -> Unit,
) {
    val searchable = state.catalog.size > CATALOG_SEARCH_THRESHOLD
    var typed by rememberSaveable { mutableStateOf("") }
    // A short catalog has no filter field, so a leftover query must not filter it.
    val query = if (searchable) typed else ""
    val searching = query.isNotBlank()
    val groupCount = remember(state.catalog) { CatalogGrouping.group(state.catalog).size }
    val groups = remember(state.catalog, query) { CatalogGrouping.group(state.catalog, query) }
    // Toggled groups; entering or leaving a search starts from the defaults again.
    val toggled = remember(searching) { mutableStateMapOf<String, Boolean>() }
    val followedJob = state.activeJob?.takeIf { it.isActive }?.regionId
    val installedIds = remember(state.installed) { state.installed.map { it.regionId }.toSet() }

    if (searchable) {
        PillField(
            value = typed,
            onValueChange = { typed = it },
            placeholder = "Filter regions",
            description = "Filter regions",
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            leading = { MsIcon(R.drawable.ms_filter_list, tint = MutedInk) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            trailing = if (searching) {
                {
                    IconButton(
                        onClick = { typed = "" },
                        modifier = Modifier
                            .size(Target)
                            .semantics { contentDescription = "Clear region filter" },
                    ) { MsIcon(R.drawable.ms_close, tint = MutedInk) }
                }
            } else {
                null
            },
        )
    }
    if (groups.isEmpty()) {
        SecondaryText("No regions match \"${query.trim()}\".")
    }
    Column {
        groups.forEachIndexed { index, group ->
            val open = toggled[group.name] ?: CatalogGrouping.initiallyExpanded(
                group = group,
                groupCount = groupCount,
                searching = searching,
                activeJobRegionId = followedJob,
                installedRegionIds = installedIds,
            )
            if (groupCount > 1) {
                if (index > 0) RowDivider()
                CatalogGroupHeader(group = group, open = open, onToggle = { toggled[group.name] = !open })
            }
            AnimatedVisibility(
                visible = open,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    Modifier
                        .padding(start = if (groupCount > 1) ButtonGap else 0.dp, end = if (groupCount > 1) ButtonGap else 0.dp)
                        .padding(bottom = if (groupCount > 1) ButtonGap else 0.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                ) {
                    group.sections.forEachIndexed { sectionIndex, section ->
                        section.title?.let { title ->
                            Text(
                                title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = 16.dp, end = 16.dp, top = if (sectionIndex > 0 || groupCount == 1) BlockGap else ButtonGap)
                                    .semantics { heading() },
                            )
                        }
                        section.regions.forEachIndexed { regionIndex, region ->
                            if (regionIndex > 0) RowDivider(Modifier.padding(horizontal = 16.dp))
                            CatalogRow(
                                region = region,
                                state = state,
                                onRequestBuild = onRequestBuild,
                                onInstallPackage = onInstallPackage,
                                onSaveCopy = onSaveCopy,
                                onStopWaiting = onStopWaiting,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogGroupHeader(group: CatalogGroup, open: Boolean, onToggle: () -> Unit) {
    val count = if (group.size == 1) "1 region" else "${group.size} regions"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onToggle)
            .padding(start = 8.dp, end = 4.dp)
            .clearAndSetSemantics {
                contentDescription = "${group.name}, $count"
                stateDescription = if (open) "Expanded" else "Collapsed"
                role = Role.Button
                onClick(label = if (open) "Collapse" else "Expand") {
                    onToggle()
                    true
                }
            },
    ) {
        MsIcon(R.drawable.ms_public, tint = MutedInk)
        Text(
            group.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(count, style = MaterialTheme.typography.labelMedium, color = MutedInk)
        Box(Modifier.size(Target), contentAlignment = Alignment.Center) {
            MsIcon(if (open) R.drawable.ms_expand_less else R.drawable.ms_expand_more, tint = MutedInk)
        }
    }
}

@Composable
private fun FindRegionCard(
    state: MapsState,
    onSearch: (String) -> Unit,
    onNearMe: (() -> Unit)?,
    onClear: () -> Unit,
    onGet: (String) -> Unit,
) {
    // Follows the query of the results on screen (a restored or previewed
    // search), and is free to edit afterwards.
    var query by remember(state.search.query) { mutableStateOf(state.search.query) }
    val search = state.search
    MapsCard(title = "Find a region") {
        SecondaryText("Any state-sized region on OpenStreetMap. A map that isn't ready yet is built for you, then installs itself.")
        PillField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Region or country",
            description = "Region search",
            container = MaterialTheme.colorScheme.surfaceContainerHighest,
            leading = {
                IconButton(
                    onClick = { onSearch(query) },
                    enabled = !search.busy && query.isNotBlank(),
                    modifier = Modifier
                        .size(Target)
                        .semantics { contentDescription = "Search regions" },
                ) { MsIcon(R.drawable.ms_search, tint = MutedInk) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (!search.busy) onSearch(query) }),
            trailing = onNearMe?.let { nearMe ->
                {
                    FilledTonalButton(
                        onClick = nearMe,
                        enabled = !search.busy,
                        contentPadding = WithIconPadding,
                        modifier = Modifier.described("Regions near me"),
                    ) { ButtonLabel("Near me", R.drawable.ms_near_me) }
                }
            },
        )
        if (search.busy) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        search.error?.let { SecondaryText(it, Danger) }
        if (search.searched && !search.busy && search.error == null && search.results.isEmpty()) {
            SecondaryText(if (search.nearMe) "No region found at your location." else "No region matches \"${search.query}\".")
        }
        search.results.forEach { extract ->
            RowDivider()
            val model = RegionUiModel.extractRow(
                extract = extract,
                busy = state.busy || state.activeJob?.isActive == true,
                inCatalog = state.catalog.any { it.id == extract.regionId && it.hasCachedArtifact },
            )
            Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
                Text(model.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (model.detail.isNotBlank()) SecondaryText(model.detail)
                model.blockedReason?.let { SecondaryText(it, Warning) }
                Button(
                    onClick = { onGet(extract.id) },
                    enabled = model.actionEnabled,
                    modifier = Modifier.described(model.actionDescription),
                ) { Text(model.actionLabel) }
            }
        }
        if (search.searched && !search.busy) {
            TextButton(
                onClick = {
                    query = ""
                    onClear()
                },
                modifier = Modifier.described("Clear region search"),
            ) { Text("Clear search") }
        }
    }
}

@Composable
private fun CatalogRow(
    region: ServerRegion,
    state: MapsState,
    onRequestBuild: (String) -> Unit,
    onInstallPackage: (String) -> Unit,
    onSaveCopy: (String) -> Unit,
    onStopWaiting: () -> Unit,
) {
    val model = RegionUiModel.catalogRow(
        region = region,
        activeJob = state.activeJob,
        busy = state.busy,
        downloadActive = state.download != null,
        pendingRemoval = state.pendingRemoval != null,
        installed = state.installed,
    )
    val onAction = {
        if (model.action == CatalogAction.DOWNLOAD) onInstallPackage(region.id) else onRequestBuild(region.id)
    }
    // Compact: name, size/date and installed state beside one inline action, so
    // an opened continent stays scannable instead of a column of buttons.
    Column(
        verticalArrangement = Arrangement.spacedBy(LineGap),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.heightIn(min = 56.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        region.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (model.pro) {
                        Spacer(Modifier.width(ButtonGap))
                        StatusPill("PRO", Tone.ACCENT)
                    }
                }
                SecondaryText(RegionUiModel.catalogSubtitle(region))
                if (model.installed) SecondaryText("Installed on this device", Positive)
            }
            if (!model.showCancel && model.action != CatalogAction.NONE) {
                CatalogActionButton(model.action, model.compactLabel, model.installed, model.actionEnabled, model.actionDescription, onAction)
            }
        }
        Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(LineGap)) {
            model.statusLine?.takeUnless { model.installed }?.let { status ->
                SecondaryText(status, if (model.errorLine != null) Danger else MutedInk)
            }
            if (model.showProgress) {
                val progress = state.activeJob?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f
                LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            model.errorLine?.let { error -> SecondaryText(error, Danger) }
            if (model.showCancel) {
                TextButton(
                    onClick = onStopWaiting,
                    modifier = Modifier.described("Stop waiting for ${region.id}"),
                ) { Text("Stop waiting") }
            } else if (model.offerSaveCopy) {
                TextButton(
                    onClick = { onSaveCopy(region.id) },
                    enabled = model.actionEnabled,
                    modifier = Modifier.described("Download package ${region.id}"),
                ) { Text("Save a copy to Files") }
            }
        }
    }
}

/**
 * A catalog row's one-tap action as in MapsCatalog.dc.html: a tonal download
 * button for a map that is ready, a plain cloud-download for one built on
 * request, refresh to retry a failed build. A current-version reinstall steps
 * back to a plain button, and an update says "Update" because its icon would
 * not.
 */
@Composable
private fun CatalogActionButton(
    action: CatalogAction,
    label: String,
    installed: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val modifier = Modifier
        .size(Target)
        .semantics { contentDescription = description }
    when {
        action == CatalogAction.DOWNLOAD && label == "Update" -> FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.described(description),
        ) { Text(label) }
        action == CatalogAction.DOWNLOAD && !installed -> FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            MsIcon(R.drawable.ms_download)
        }
        action == CatalogAction.DOWNLOAD -> IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            MsIcon(R.drawable.ms_download, tint = LocalContentColor.current)
        }
        action == CatalogAction.RETRY_BUILD -> IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            MsIcon(R.drawable.ms_refresh)
        }
        else -> IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            MsIcon(R.drawable.ms_cloud_download)
        }
    }
}

/**
 * The transfer in progress, as the secondary-container card of
 * MapsCatalog.dc.html: icon, region, phase and bytes, the percentage, a wavy
 * progress bar and Cancel download. A dropped connection that is retrying turns
 * the card to the warning container and says when the next attempt comes.
 */
@Composable
private fun DownloadCard(progress: DownloadProgress, onCancel: () -> Unit) {
    val retrying = progress.retry != null
    val container = if (retrying) CurveMapsTheme.colors.warningContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (retrying) CurveMapsTheme.colors.onWarningContainer else MaterialTheme.colorScheme.onSecondaryContainer
    val accent = if (retrying) content else MaterialTheme.colorScheme.primary
    val track = content.copy(alpha = 0.24f)
    val cancelButton: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = accent),
                modifier = Modifier.described("Cancel download"),
            ) { Text("Cancel download") }
        }
    }
    Card(
        shape = CatalogShape,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ButtonGap),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BlockGap)) {
                MsIcon(R.drawable.ms_downloading)
                Column(Modifier.weight(1f)) {
                    Text(
                        progress.regionName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        when (progress.phase) {
                            DownloadPhase.DOWNLOADING -> buildString {
                                append("Downloading · ").append(RegionUiModel.formatBytes(progress.bytes))
                                progress.total?.let { append(" of ").append(RegionUiModel.formatBytes(it)) }
                            }
                            DownloadPhase.VERIFYING -> "Checking ${progress.regionName} for damage…"
                            DownloadPhase.SAVING -> "Saving ${progress.regionName} to your file…"
                            DownloadPhase.INSTALLING -> "Installing ${progress.regionName}…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (progress.phase == DownloadPhase.DOWNLOADING) {
                    progress.fraction?.let { Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.labelLarge) }
                }
            }
            when (progress.phase) {
                DownloadPhase.DOWNLOADING -> {
                    val fraction = progress.fraction
                    if (fraction != null) {
                        LinearWavyProgressIndicator(
                            progress = { fraction },
                            color = accent,
                            trackColor = track,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearWavyProgressIndicator(color = accent, trackColor = track, modifier = Modifier.fillMaxWidth())
                    }
                    progress.retry?.let { retry ->
                        Text(
                            "${retry.reason}. Retrying in ${retry.secondsUntilRetry}s " +
                                "(attempt ${retry.attempt} of ${retry.maxAttempts}) — downloaded bytes are kept.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    cancelButton()
                }
                DownloadPhase.VERIFYING -> {
                    LinearWavyProgressIndicator(color = accent, trackColor = track, modifier = Modifier.fillMaxWidth())
                    Text(
                        "The download finished; its SHA-256 is being compared with the server's.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    cancelButton()
                }
                DownloadPhase.SAVING -> Text(
                    "The download is complete; the file is being copied to the location you chose.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                DownloadPhase.INSTALLING -> {
                    LinearWavyProgressIndicator(color = accent, trackColor = track, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Checked and complete; unpacking the map, routing graph and place search.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedPackageRow(saved: SavedPackage, busy: Boolean, onImportSavedPackage: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
        Text(
            "Saved \"${saved.fileName}\" (${RegionUiModel.formatBytes(saved.bytes)}) to your device.",
            style = MaterialTheme.typography.bodyLarge,
        )
        SecondaryText("It stays in your Files. Import it to copy the package into app-managed storage.")
        Spacer(Modifier.height(LineGap))
        Button(
            onClick = onImportSavedPackage,
            enabled = !busy,
            modifier = Modifier.described("Import saved file"),
        ) { Text("Import saved file") }
    }
}

@Composable
private fun RecoveredPackageRow(
    recovered: RecoveredPackage,
    busy: Boolean,
    onSaveRecoveredPackage: () -> Unit,
    onImportRecoveredPackage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
        Text(
            "A complete package (${RegionUiModel.formatBytes(recovered.bytes)}) is kept in app storage.",
            style = MaterialTheme.typography.bodyLarge,
        )
        SecondaryText("Save it to a file you can keep, or import it directly.")
        Spacer(Modifier.height(LineGap))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ButtonGap),
            verticalArrangement = Arrangement.spacedBy(ButtonGap),
        ) {
            FilledTonalButton(
                onClick = onSaveRecoveredPackage,
                enabled = !busy,
                modifier = Modifier.described("Save downloaded package to device"),
            ) { Text("Save to device") }
            Button(
                onClick = onImportRecoveredPackage,
                enabled = !busy,
                modifier = Modifier.described("Import downloaded package"),
            ) { Text("Import") }
        }
    }
}

@Composable
private fun InterruptedRow(interrupted: InterruptedDownload, enabled: Boolean, onResumeDownload: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
        Text(
            "${interrupted.regionName}: ${RegionUiModel.formatBytes(interrupted.bytes)} downloaded",
            style = MaterialTheme.typography.bodyLarge,
        )
        interrupted.total?.let { total ->
            val fraction = (interrupted.bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
            LinearWavyProgressIndicator(
                progress = { fraction },
                color = Warning,
                trackColor = CurveMapsTheme.colors.warningContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = LineGap),
            )
        }
        SecondaryText("The partial bytes are kept in app storage. Resuming continues where it stopped.")
        Spacer(Modifier.height(LineGap))
        Button(
            onClick = onResumeDownload,
            enabled = enabled,
            modifier = Modifier.described("Resume download"),
        ) { Text("Resume download") }
    }
}

/**
 * Bundled and downloaded maps as Material 3 list items inside one card
 * (Maps.dc.html): a leading circle (filled primary when active), the name with
 * an Active capsule, supporting lines and a delete action, then Update and
 * Activate under the text.
 */
@Composable
private fun InstalledMapsCard(
    state: MapsState,
    visible: List<InstalledRegion>,
    rideActive: Boolean,
    onInstallPackage: (String) -> Unit,
    onActivate: (String) -> Unit,
    onActivateBundled: () -> Unit,
    onDelete: (InstalledRegion) -> Unit,
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = BlockGap)) {
            Text(
                "Installed maps",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(start = CardPadding, end = CardPadding, top = CardPadding, bottom = ButtonGap)
                    .semantics { heading() },
            )
            if (state.installed.isEmpty()) {
                Text(
                    RegionUiModel.installedEmptySummary(RegionDatasets.bundledAvailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk,
                    modifier = Modifier.padding(horizontal = CardPadding, vertical = ButtonGap),
                )
            }
            if (RegionDatasets.bundledAvailable) {
                BundledRow(
                    active = state.activeInstallId == null,
                    rideActive = rideActive,
                    onActivateBundled = onActivateBundled,
                )
            }
            visible.forEachIndexed { index, install ->
                if (RegionDatasets.bundledAvailable || index > 0) {
                    RowDivider(Modifier.padding(horizontal = CardPadding, vertical = LineGap))
                }
                InstalledRow(
                    install = install,
                    active = state.activeInstallId == install.installId,
                    rideActive = rideActive,
                    removing = state.pendingRemoval?.installId == install.installId,
                    busy = state.busy,
                    update = RegionUiModel.installedUpdate(
                        install = install,
                        installed = state.installed,
                        serverChecked = state.serverChecked,
                        catalog = state.catalog,
                        busy = state.busy,
                        downloadActive = state.download != null,
                        pendingRemoval = state.pendingRemoval != null,
                        activeInstallId = state.activeInstallId,
                        pendingRemovalId = state.pendingRemoval?.installId,
                        pendingActivation = state.pendingActivation,
                    ),
                    onUpdate = { onInstallPackage(install.regionId) },
                    onActivate = onActivate,
                    onDelete = { onDelete(install) },
                )
            }
        }
    }
}

/** The 40 dp circle that leads an installed-map row. */
@Composable
private fun MapCircle(active: Boolean) {
    Box(
        Modifier
            .size(LeadingCircle)
            .background(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MsIcon(
            if (active) R.drawable.ms_map_fill else R.drawable.ms_map,
            tint = if (active) MaterialTheme.colorScheme.onPrimary else MutedInk,
        )
    }
}

@Composable
private fun BundledRow(active: Boolean, rideActive: Boolean, onActivateBundled: () -> Unit) {
    Column {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.padding(horizontal = MAP_ROW_INSET),
            leadingContent = { MapCircle(active) },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ButtonGap)) {
                    Text(
                        "Queensland (bundled)",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (active) ActivePill()
                }
            },
            supportingContent = { SecondaryText(RegionUiModel.bundledSummary()) },
        )
        if (!active) {
            FilledTonalButton(
                onClick = onActivateBundled,
                enabled = !rideActive,
                modifier = Modifier
                    .padding(start = ACTION_INDENT, end = 16.dp)
                    .described("Use bundled Queensland data"),
            ) { Text("Use bundled Queensland data") }
        }
    }
}

/** Under-text actions line up with the supporting text: 16 dp padding + 40 dp circle + 16 dp gap. */
private val ACTION_INDENT = 76.dp

/** The installed-map rows sit this far in so their icon lines up with the card's own titles. */
private val MAP_ROW_INSET = 4.dp

@Composable
private fun InstalledRow(
    install: InstalledRegion,
    active: Boolean,
    rideActive: Boolean,
    removing: Boolean,
    busy: Boolean,
    update: InstalledUpdateModel,
    onUpdate: () -> Unit,
    onActivate: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val deleteEnabled = !removing && !busy && !(active && rideActive)
    Column {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.padding(horizontal = MAP_ROW_INSET),
            leadingContent = { MapCircle(active) },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ButtonGap)) {
                    Text(
                        install.regionName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (active) {
                        ActivePill()
                    } else if (removing) {
                        StatusPill("Deleting", Tone.WARNING)
                    }
                }
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SecondaryText(RegionUiModel.installedSummary(install))
                    update.line?.takeUnless { removing }?.let { line ->
                        when (update.status) {
                            MapUpdateStatus.UP_TO_DATE -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(LineGap),
                            ) {
                                MsIcon(R.drawable.ms_check_circle_fill, tint = Positive, size = 16.dp)
                                Text(line, style = MaterialTheme.typography.labelLarge, color = Positive)
                            }
                            MapUpdateStatus.AVAILABLE -> SecondaryText(line, MaterialTheme.colorScheme.primary)
                            else -> SecondaryText(line)
                        }
                    }
                    when {
                        removing -> SecondaryText(RegionUiModel.releasingRowText(RegionDatasets.bundledAvailable), Warning)
                        active && rideActive -> SecondaryText("Stop the ride before deleting the active map.", Warning)
                        active -> SecondaryText(RegionUiModel.activeDeleteHint(RegionDatasets.bundledAvailable))
                        rideActive -> SecondaryText("Stop the ride before switching regions.", Warning)
                    }
                }
            },
            trailingContent = {
                IconButton(
                    onClick = onDelete,
                    enabled = deleteEnabled,
                    modifier = Modifier
                        .size(Target)
                        .semantics { contentDescription = "Delete ${install.regionName} map" },
                ) { MsIcon(R.drawable.ms_delete, tint = if (deleteEnabled) MutedInk else LocalContentColor.current.copy(alpha = 0.38f)) }
            },
        )
        val offersActivate = !active && !removing && update.status != MapUpdateStatus.REPLACED
        val offersUpdate = update.offersUpdate && !removing
        if (offersUpdate || offersActivate) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ButtonGap),
                verticalArrangement = Arrangement.spacedBy(ButtonGap),
                modifier = Modifier.padding(start = ACTION_INDENT, end = 16.dp),
            ) {
                if (offersUpdate) {
                    Button(
                        onClick = onUpdate,
                        enabled = update.actionEnabled,
                        modifier = Modifier.described(update.actionDescription),
                    ) { Text(update.actionLabel) }
                }
                if (offersActivate) {
                    FilledTonalButton(
                        onClick = { onActivate(install.installId) },
                        enabled = !rideActive && !busy,
                        modifier = Modifier.described("Activate region ${install.regionId}"),
                    ) { Text("Activate") }
                }
            }
        }
    }
}

/** The offline import card: outlined, no server needed. */
@Composable
private fun OfflineImportCard(busy: Boolean, onImportPackage: () -> Unit) {
    OutlinedCard(
        shape = CardShape,
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(BlockGap),
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardPadding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BlockGap)) {
                MsIcon(R.drawable.ms_sd_card, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Offline import",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            SecondaryText(
                "Install a .motomap file you downloaded or were given — no server needed. " +
                    "The file is copied into app storage and left where it is.",
            )
            OutlinedButton(
                onClick = onImportPackage,
                enabled = !busy,
                contentPadding = WithIconPadding,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MutedInk),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.described("Import package file"),
            ) { ButtonLabel("Import package file", R.drawable.ms_file_open) }
        }
    }
}

/** Delete confirmation (DeleteMap.dc.html): icon, title, the model's own copy as paragraphs, Cancel and an error-coloured Delete. */
@Composable
private fun DeleteMapDialog(
    target: InstalledRegion,
    active: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { MsIcon(R.drawable.ms_delete) },
        title = { Text("Delete installed map?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BlockGap)) {
                RegionUiModel.deleteConfirmation(target, active, RegionDatasets.bundledAvailable)
                    .split("\n\n")
                    .forEach { Text(it) }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.described("Confirm delete ${target.regionName} map"),
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.described("Cancel delete ${target.regionName} map"),
            ) { Text("Cancel") }
        },
    )
}
