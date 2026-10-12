package com.curvemaps.app.region

import java.util.Locale

/**
 * Pure decisions for the Maps screen. Keeping the catalog-row and installed-map
 * rules here (instead of inline in Compose) makes the retry/terminal-job
 * contract and the delete-confirmation wording JVM-testable: a terminal build
 * job must never hide the Download action, and the delete text must name the
 * dataset, its real installed size, and the fact that user Files are untouched.
 */

/** Primary action a catalog row offers. */
enum class CatalogAction { REQUEST_BUILD, RETRY_BUILD, DOWNLOAD, NONE }

/** Everything the catalog row renders, derived only from its inputs. */
data class CatalogRowModel(
    val action: CatalogAction,
    val actionLabel: String,
    val actionDescription: String,
    val actionEnabled: Boolean,
    val showProgress: Boolean,
    /** A build the user is following: offer "Stop waiting" (never a server cancel). */
    val showCancel: Boolean,
    val statusLine: String?,
    val errorLine: String?,
    /** A downloadable package also offers "Save a copy" to a user-chosen file. */
    val offerSaveCopy: Boolean = false,
    /** "Pro" badge for paid-tier maps (nothing is paid yet). */
    val pro: Boolean = false,
    /** The server's current package for this region is installed here. */
    val installed: Boolean = false,
    /** Short visible label for the compact catalog row's inline button. */
    val compactLabel: String = "",
)

/** Whether an installed map matches the map service's current package. */
enum class MapUpdateStatus {
    /** The catalog has not been loaded, so nothing is known yet. */
    UNCHECKED,

    /** This copy, or a newer one on the device, is the service's current package. */
    UP_TO_DATE,

    /** The service's package has a different fingerprint and data no older than this copy's. */
    AVAILABLE,

    /** The service does not list the region, or has no package ready for it. */
    UNKNOWN,

    /** A newer copy is already installed and takes over from (or replaced) this one. */
    REPLACED,
}

/** The update line (and Update button) under one installed map. */
data class InstalledUpdateModel(
    val status: MapUpdateStatus,
    val line: String?,
    val actionLabel: String = "",
    val actionDescription: String = "",
    val actionEnabled: Boolean = false,
) {
    val offersUpdate: Boolean get() = status == MapUpdateStatus.AVAILABLE
}

/** One place from the server's extract search. */
data class ExtractRowModel(
    val title: String,
    val detail: String,
    val actionLabel: String,
    val actionDescription: String,
    val actionEnabled: Boolean,
    /** Why it cannot be built here (too large, a continent), when not eligible. */
    val blockedReason: String?,
)

object RegionUiModel {

    /**
     * The catalog row decision.
     *
     * A job only owns the row while it is genuinely active. Completed, failed,
     * and canceled jobs are informational: the row keeps offering Download (for
     * a cached artifact or a completed job with a download URL) or Retry build,
     * so a stale terminal job can never trap the user without a retry.
     */
    fun catalogRow(
        region: ServerRegion,
        activeJob: ServerJob?,
        busy: Boolean,
        downloadActive: Boolean,
        pendingRemoval: Boolean,
        installed: List<InstalledRegion> = emptyList(),
    ): CatalogRowModel {
        if (region.disabled) {
            return CatalogRowModel(
                action = CatalogAction.NONE,
                actionLabel = "",
                actionDescription = "",
                actionEnabled = false,
                showProgress = false,
                showCancel = false,
                statusLine = "Disabled by the operator",
                errorLine = null,
            )
        }
        val job = activeJob?.takeIf { it.regionId == region.id }
        if (job != null && job.isActive) {
            return CatalogRowModel(
                action = CatalogAction.NONE,
                actionLabel = "",
                actionDescription = "",
                actionEnabled = false,
                showProgress = true,
                showCancel = true,
                statusLine = buildStatus(job),
                errorLine = job.error.takeIf { it.isNotBlank() },
                pro = region.isPro,
            )
        }
        val artifactDownloadable = region.hasCachedArtifact &&
            !region.artifact?.downloadUrl.isNullOrBlank()
        val jobDownloadable = job?.isCompleted == true && job.downloadUrl.isNotBlank()
        val canDownload = artifactDownloadable || jobDownloadable
        val action = when {
            canDownload -> CatalogAction.DOWNLOAD
            job?.isFailed == true || job?.isCanceled == true -> CatalogAction.RETRY_BUILD
            else -> CatalogAction.REQUEST_BUILD
        }
        // The server's current package is already installed here: say so, so
        // Download reads as optional (a new copy) rather than still needed.
        val alreadyInstalled = region.artifact?.fingerprint?.takeIf { it.isNotBlank() }?.let { fingerprint ->
            installed.any { it.regionId == region.id && it.pipelineFingerprint == fingerprint }
        } ?: false
        // An older copy is installed: downloading replaces it (see RegionManager).
        val updatesInstalled = !alreadyInstalled && artifactDownloadable &&
            installed.any { updateAvailable(it, region, installed) }
        val statusLine = when {
            job?.isCompleted == true && canDownload -> "Build complete — ready to install"
            job?.isCompleted == true -> "Build completed without a download link"
            job?.isFailed == true -> "Build failed"
            job?.isCanceled == true -> "Build canceled"
            alreadyInstalled -> "This version is installed on this device"
            updatesInstalled -> "Update for the copy installed on this device"
            else -> null
        }
        val (label, description) = when (action) {
            CatalogAction.DOWNLOAD -> when {
                alreadyInstalled -> "Install again" to "Install again ${region.id}"
                updatesInstalled -> "Update" to "Update map ${region.id}"
                else -> "Download & install" to "Download and install ${region.id}"
            }
            CatalogAction.RETRY_BUILD -> "Try the build again" to "Retry package ${region.id}"
            CatalogAction.REQUEST_BUILD -> "Build this map" to "Build map ${region.id}"
            CatalogAction.NONE -> "" to ""
        }
        return CatalogRowModel(
            action = action,
            actionLabel = label,
            actionDescription = description,
            actionEnabled = !busy && !downloadActive && !pendingRemoval,
            showProgress = false,
            showCancel = false,
            statusLine = statusLine,
            errorLine = job?.error?.takeIf { it.isNotBlank() },
            offerSaveCopy = action == CatalogAction.DOWNLOAD,
            pro = region.isPro,
            installed = alreadyInstalled,
            compactLabel = when (action) {
                CatalogAction.DOWNLOAD -> when {
                    alreadyInstalled -> "Reinstall"
                    updatesInstalled -> "Update"
                    else -> "Install"
                }
                CatalogAction.RETRY_BUILD -> "Retry"
                CatalogAction.REQUEST_BUILD -> "Build"
                CatalogAction.NONE -> ""
            },
        )
    }

    /**
     * Whether [region]'s current package updates [install]. The comparison is
     * the package fingerprint, which hashes every component, the OSM source
     * and the pinned pipeline tools, so a data refresh and a routing/pipeline
     * change both count. It is no update when another copy of the region on
     * the device ([installed]) already has that fingerprint, or when the
     * service's data is older than this copy's (a different fingerprint alone
     * can be a rollback or an older file). Unreadable dates leave the decision
     * to the fingerprint.
     */
    fun updateAvailable(
        install: InstalledRegion,
        region: ServerRegion,
        installed: List<InstalledRegion> = listOf(install),
    ): Boolean {
        if (region.id != install.regionId || region.disabled) return false
        val fingerprint = region.artifact
            ?.takeIf { it.available && it.downloadUrl.isNotBlank() }
            ?.fingerprint
            ?.takeIf { it.isNotBlank() }
            ?: return false
        if (fingerprint == install.pipelineFingerprint) return false
        if (installed.any { it.regionId == region.id && it.pipelineFingerprint == fingerprint }) return false
        val serverDate = sourceDateKey(region.sourceDate) ?: return true
        val localDate = sourceDateKey(install.sourceDate) ?: return true
        return serverDate >= localDate
    }

    /**
     * The update line under one installed map. Nothing is known until the
     * catalog is loaded ([serverChecked]); after that each map says whether it
     * is current, offers Update, or says why it cannot tell. A copy that a
     * newer install replaces says so: the active one names what happens next
     * ([pendingActivation] is the copy waiting for the ride to end), any other
     * is one whose deletion failed and is listed so the rider can delete it.
     * A copy being deleted ([pendingRemovalId]) never counts as the newer one.
     */
    fun installedUpdate(
        install: InstalledRegion,
        installed: List<InstalledRegion>,
        serverChecked: Boolean,
        catalog: List<ServerRegion>,
        busy: Boolean,
        downloadActive: Boolean,
        pendingRemoval: Boolean,
        activeInstallId: String? = null,
        pendingRemovalId: String? = null,
        pendingActivation: String? = null,
    ): InstalledUpdateModel {
        val newest = keptCopy(install.regionId, installed, pendingRemovalId)
        if (newest != null && newest.installId != install.installId && newest.numbered) {
            return InstalledUpdateModel(
                status = MapUpdateStatus.REPLACED,
                line = when {
                    install.installId != activeInstallId ->
                        "A newer copy replaced this one. Delete it to free the space."
                    pendingActivation == newest.installId ->
                        "A newer copy is installed. It takes over when this ride ends."
                    else -> "A newer copy is installed. Activate it to replace this one."
                },
            )
        }
        if (!serverChecked) return InstalledUpdateModel(MapUpdateStatus.UNCHECKED, line = null)
        val region = catalog.firstOrNull { it.id == install.regionId }
            ?: return InstalledUpdateModel(MapUpdateStatus.UNKNOWN, "The map service doesn't list this region")
        if (region.disabled) {
            return InstalledUpdateModel(MapUpdateStatus.UNKNOWN, "The map service isn't offering this region right now")
        }
        if (updateAvailable(install, region, installed)) {
            val artifact = region.artifact!!
            val line = listOfNotNull(
                "Update available",
                region.sourceDate.takeIf { it.isNotBlank() }?.let { "map data ${sourceDateLabel(it)}" },
                artifact.size.takeIf { it > 0 }?.let { "${formatBytes(it)} download" },
            ).joinToString(" · ")
            return InstalledUpdateModel(
                status = MapUpdateStatus.AVAILABLE,
                line = line,
                actionLabel = "Update",
                actionDescription = "Update ${install.regionName} map",
                actionEnabled = !busy && !downloadActive && !pendingRemoval,
            )
        }
        val current = region.artifact?.takeIf { it.available }?.fingerprint?.takeIf { it.isNotBlank() }
        return when {
            current == install.pipelineFingerprint -> InstalledUpdateModel(MapUpdateStatus.UP_TO_DATE, "Up to date")
            // Different but not newer: this copy is newer than the service's package.
            current != null -> InstalledUpdateModel(MapUpdateStatus.UP_TO_DATE, "Up to date (newer than the map service's copy)")
            else -> InstalledUpdateModel(MapUpdateStatus.UNKNOWN, "The map service has no package ready for this region")
        }
    }

    /**
     * The copy of [regionId] the device keeps: the most recently installed
     * one by install number ([InstalledRegion.INSTALL_ORDER], never the
     * phone's clock), leaving out a copy that is being deleted.
     */
    fun keptCopy(
        regionId: String,
        installed: List<InstalledRegion>,
        pendingRemovalId: String? = null,
    ): InstalledRegion? = installed
        .filter { it.regionId == regionId && it.installId != pendingRemovalId }
        .maxWithOrNull(InstalledRegion.INSTALL_ORDER)

    /**
     * Older copies of a region that a newer install replaces. One copy per
     * region is kept ([keptCopy]). An older copy that is still the active map
     * is never listed (it is replaced once the new copy is active), and neither
     * is one already being deleted. Copies installed before the app kept one
     * copy per region are left alone until the rider installs that region
     * again, so an app update never deletes a map by itself.
     */
    fun supersededInstalls(
        installed: List<InstalledRegion>,
        activeInstallId: String?,
        pendingRemovalId: String? = null,
    ): List<InstalledRegion> =
        installed.map { it.regionId }.distinct().flatMap { regionId ->
            val kept = keptCopy(regionId, installed, pendingRemovalId)
                ?.takeIf { it.numbered }
                ?: return@flatMap emptyList()
            installed.filter {
                it.regionId == regionId &&
                    it.installId != kept.installId &&
                    it.installId != activeInstallId &&
                    it.installId != pendingRemovalId
            }
        }

    /**
     * The installed maps the screen lists: a replaced copy waiting to be
     * deleted is hidden, so an update reads as one map changing in place. A
     * replaced copy whose deletion failed ([failedRemovals]) is listed again so
     * the rider can see and delete what still uses the space.
     */
    fun visibleInstalls(
        installed: List<InstalledRegion>,
        activeInstallId: String?,
        pendingRemovalId: String? = null,
        failedRemovals: Set<String> = emptySet(),
    ): List<InstalledRegion> {
        val hidden = supersededInstalls(installed, activeInstallId, pendingRemovalId)
            .map { it.installId }
            .filterNot { it in failedRemovals }
            .toSet()
        return installed.filterNot { it.installId in hidden }
    }

    /** "261005" or "2026-10-05" → "261005" for ordering; null when unreadable. */
    fun sourceDateKey(raw: String): String? {
        val match = SOURCE_DATE.find(raw.trim()) ?: return null
        val (yy, mm, dd) = match.destructured
        if (mm.toInt() !in 1..12 || dd.toInt() !in 1..31) return null
        return "$yy$mm$dd"
    }

    private val SOURCE_DATE = Regex("^(?:20)?(\\d{2})-?(\\d{2})-?(\\d{2})$")

    /** How the Maps screen names the configured server. */
    fun serviceLabel(address: String, usingDefault: Boolean): String = when {
        usingDefault -> "CurveMaps map service"
        address.isBlank() -> "No map server chosen"
        else -> address.replace(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "").trimEnd('/')
    }

    /** "261005" or "2026-10-05" → "5 Oct 2026"; anything else unchanged. */
    fun sourceDateLabel(raw: String): String {
        val match = SOURCE_DATE.find(raw.trim()) ?: return raw
        val (yy, mm, dd) = match.destructured
        val month = mm.toInt()
        if (month !in 1..12 || dd.toInt() !in 1..31) return raw
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return "${dd.toInt()} ${months[month - 1]} 20$yy"
    }

    /** The catalog row's one-line summary: download size and data date. */
    fun catalogSubtitle(region: ServerRegion): String = listOfNotNull(
        region.artifact?.takeIf { region.hasCachedArtifact }?.let { formatBytes(it.size) } ?: "Not built yet",
        region.sourceDate.takeIf { it.isNotBlank() }?.let { "map data ${sourceDateLabel(it)}" },
    ).joinToString(" · ")

    /** A build's progress in plain words ("Building tiles · 40%"). */
    fun buildStatus(job: ServerJob): String {
        if (job.state == "queued") return "Waiting for the map service" +
            (job.message.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
        val step = when (job.step) {
            "source" -> "Downloading OpenStreetMap data"
            "tiles" -> "Drawing the map"
            "graph" -> "Building the routing graph"
            "geocoder" -> "Indexing places for search"
            "package", "verify" -> "Packaging"
            "publish" -> "Publishing"
            else -> job.message.ifBlank { "Building" }
        }
        val percent = (job.progress.coerceIn(0.0, 1.0) * 100).toInt()
        return "$step · $percent%"
    }

    /** One extract search result. */
    fun extractRow(extract: ServerExtract, busy: Boolean, inCatalog: Boolean): ExtractRowModel {
        val detail = listOfNotNull(
            extract.parentLabel.takeIf { it.isNotBlank() },
            extract.bytes.takeIf { it > 0 }?.let { "about ${formatBytes(estimatedPackageBytes(it))} to download" },
        ).joinToString(" · ")
        return ExtractRowModel(
            title = extract.name,
            detail = detail,
            actionLabel = if (inCatalog) "Get this map" else "Build this map",
            actionDescription = "Get map ${extract.id}",
            actionEnabled = extract.eligible && !busy,
            blockedReason = extract.reason.takeIf { !extract.eligible && it.isNotBlank() }
                ?: if (!extract.eligible) "This region is too large to build here" else null,
        )
    }

    /** Package size estimate from an extract size (measured ratio, Queensland 1.8×). */
    fun estimatedPackageBytes(extractBytes: Long): Long = (extractBytes * PACKAGE_RATIO).toLong()

    /** Free space a one-tap install needs: the download plus its extracted copy. */
    fun installSpaceNeeded(packageBytes: Long): Long =
        if (packageBytes <= 0) 0 else (packageBytes * INSTALL_SPACE_FACTOR).toLong()

    fun notEnoughSpaceMessage(regionName: String, needed: Long, free: Long): String =
        "Not enough free space for $regionName: it needs about ${formatBytes(needed)} while installing, " +
            "and ${formatBytes(free)} is free. Delete other maps or files and try again."

    private const val PACKAGE_RATIO = 1.8
    private const val INSTALL_SPACE_FACTOR = 2.2

    /** Region, source date, and actual installed bytes — newest line first. */
    fun installedSummary(install: InstalledRegion): String = listOfNotNull(
        install.coverage,
        "source ${install.sourceDate}",
        "${formatBytes(install.bytes)} installed",
    ).joinToString(" · ")

    /**
     * Delete confirmation. It names the dataset and the space actually
     * reclaimed, and it never implies that a user-saved `.motomap` in Files is
     * deleted — that file is user-managed and stays where it is.
     */
    fun deleteConfirmation(
        install: InstalledRegion,
        active: Boolean,
        bundledAvailable: Boolean = true,
    ): String = buildString {
        append("Delete \"${install.regionName}\" (source ${install.sourceDate})?\n\n")
        append("This frees ${formatBytes(install.bytes)} on this device. ")
        append("Any .motomap package you downloaded or saved in Files is not removed.")
        if (active) {
            if (bundledAvailable) {
                append("\n\nThis is the active map, so the app switches to the bundled ")
                append("Queensland data first, releases the routing graph, and then deletes it.")
            } else {
                append("\n\nThis is the active map, so the app releases the routing graph and ")
                append("then deletes it. Until you install another map package, ")
                append("routing and search are unavailable.")
            }
        }
    }

    /** The bundled fallback is immutable APK data; it is never fake-reclaimed. */
    fun bundledSummary(): String =
        "Bundled with the app — always available and cannot be deleted."

    /** Honest empty state: what the app falls back to when no map is installed. */
    fun installedEmptySummary(bundledAvailable: Boolean = true): String =
        if (bundledAvailable) {
            "No downloaded maps are installed. The bundled Queensland data is always available."
        } else {
            "No maps are installed. This app carries no map data of its own: download a map " +
                "or load a .motomap package (map, routing and search in one file) to start."
        }

    /** Notice while an active map is being swapped out for deletion. */
    fun releasingActiveNotice(bundledAvailable: Boolean = true): String =
        if (bundledAvailable) "Switching to the bundled Queensland data…" else "Releasing the active map…"

    /** Row hint for the active map's delete behaviour. */
    fun activeDeleteHint(bundledAvailable: Boolean = true): String =
        if (bundledAvailable) {
            "Deleting the active map switches back to the bundled Queensland data first."
        } else {
            "Deleting the active map leaves no map installed until you add another."
        }

    /** Row text while a deletion waits for the routing graph to drain. */
    fun releasingRowText(bundledAvailable: Boolean = true): String =
        if (bundledAvailable) {
            "Switching to the bundled Queensland data and releasing this map…"
        } else {
            "Releasing this map…"
        }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KiB", "MiB", "GiB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
