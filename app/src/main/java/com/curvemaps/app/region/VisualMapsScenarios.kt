package com.curvemaps.app.region

/**
 * Deterministic Maps-screen states used by `tools/test/visual.py` and by the
 * JVM model tests. They describe intermediate download/import/delete states so
 * those screens can be inspected and asserted without a live map server, a
 * network transfer, or a real SAF destination.
 *
 * This is debug visual tooling: nothing here is reachable from production UI
 * flows, and no scenario touches the registry or the network.
 */
object VisualMapsScenarios {

    const val CATALOG_READY = "catalog-ready"
    const val CATALOG_WORLD = "catalog-world"
    const val BUILD_FAILED = "build-failed"
    const val DOWNLOAD_PROGRESS = "download-progress"
    const val DOWNLOAD_INTERRUPTED = "download-interrupted"
    const val PACKAGE_SAVED = "package-saved"
    const val RECOVERED_PACKAGE = "recovered-package"
    const val INSTALLED = "installed"
    const val REMOVAL_RETRY = "removal-retry"
    const val IMPORT_DEFERRED = "import-deferred"
    const val REMOVAL_PENDING = "removal-pending"
    const val DOWNLOAD_RETRYING = "download-retrying"
    const val DOWNLOAD_VERIFYING = "download-verifying"
    const val SERVER_ERROR = "server-error"
    const val BUILD_FOLLOWING = "build-following"
    const val SEARCH_RESULTS = "search-results"
    const val INSTALLING = "installing"
    const val INSTALL_DEFERRED = "install-deferred"
    const val SERVICE_CONNECTED = "service-connected"
    const val UPDATE_CHECK = "update-check"
    const val UPDATE_AVAILABLE = "update-available"
    const val UPDATED = "updated"

    val names: List<String> = listOf(
        CATALOG_READY,
        CATALOG_WORLD,
        BUILD_FAILED,
        DOWNLOAD_PROGRESS,
        DOWNLOAD_INTERRUPTED,
        PACKAGE_SAVED,
        RECOVERED_PACKAGE,
        INSTALLED,
        REMOVAL_PENDING,
        REMOVAL_RETRY,
        IMPORT_DEFERRED,
        DOWNLOAD_RETRYING,
        DOWNLOAD_VERIFYING,
        SERVER_ERROR,
        BUILD_FOLLOWING,
        SEARCH_RESULTS,
        INSTALLING,
        INSTALL_DEFERRED,
        SERVICE_CONNECTED,
        UPDATE_CHECK,
        UPDATE_AVAILABLE,
        UPDATED,
    )

    private const val FINGERPRINT = "1ea03fcfc3100000000000000000000000000000000000000000000000000000"
    private const val PACKAGE_BYTES = 357_091_613L
    private const val SOURCE_DATE = "260815"

    /** The service's newer Queensland package in the update scenarios. */
    private const val UPDATE_FINGERPRINT = "2fb14d0e8a770000000000000000000000000000000000000000000000000000"
    private const val UPDATE_SOURCE_DATE = "261005"

    fun state(name: String): MapsState? = when (name) {
        CATALOG_READY -> base(catalog = listOf(catalogRegion()))
        CATALOG_WORLD -> base(catalog = worldCatalog(), installed = listOf(installedRegion()))
        BUILD_FAILED -> base(
            catalog = listOf(catalogRegion().copy(artifact = null)),
            activeJob = ServerJob(
                id = "a1575e4b6fd941d81c10ab05",
                regionId = "queensland",
                regionName = "Queensland",
                state = "failed",
                step = "import",
                progress = 0.4,
                message = "Importing the routing graph",
                error = "The extract download returned an HTML page instead of a PBF",
                cached = false,
                artifactSize = 0,
                artifactSha256 = "",
                downloadUrl = "",
            ),
        )
        DOWNLOAD_PROGRESS -> base(
            catalog = listOf(catalogRegion()),
            download = DownloadProgress(
                jobId = "a1575e4b6fd941d81c10ab05",
                regionName = "Queensland",
                bytes = 120_000_000,
                total = PACKAGE_BYTES,
                phase = DownloadPhase.DOWNLOADING,
            ),
        )
        DOWNLOAD_INTERRUPTED -> base(
            catalog = listOf(catalogRegion()),
            interrupted = InterruptedDownload(
                jobId = "a1575e4b6fd941d81c10ab05",
                regionId = "queensland",
                regionName = "Queensland",
                fingerprint = FINGERPRINT,
                downloadUrl = "/api/v1/artifacts/queensland/$FINGERPRINT/queensland.motomap",
                etag = "\"artifact-v1\"",
                bytes = 180_000_000,
                total = PACKAGE_BYTES,
            ),
        )
        PACKAGE_SAVED -> base(
            catalog = listOf(catalogRegion()),
            savedPackage = SavedPackage(
                regionId = "queensland",
                regionName = "Queensland",
                fileName = "queensland-260815.motomap",
                bytes = PACKAGE_BYTES,
                uri = "",
            ),
            notice = "Saved \"queensland-260815.motomap\" to your device — tap Import saved file to install it.",
        )
        RECOVERED_PACKAGE -> base(
            catalog = listOf(catalogRegion()),
            recoveredPackage = RecoveredPackage(
                regionId = "queensland",
                regionName = "Queensland",
                fileName = "queensland-$FINGERPRINT.motomap",
                bytes = PACKAGE_BYTES,
            ),
        )
        INSTALLED -> base(
            catalog = listOf(catalogRegion()),
            installed = listOf(installedRegion()),
        )
        REMOVAL_RETRY -> base(installed = listOf(installedRegion())).copy(
            error = "The map is still in use. Try deleting it again shortly.",
        )
        IMPORT_DEFERRED -> base(
            installed = listOf(installedRegion()),
            notice = "Queensland is installed. It switches on when this ride ends. The selected file was left where it was.",
        ).copy(pendingActivation = installId())
        REMOVAL_PENDING -> base(
            catalog = listOf(catalogRegion()),
            installed = listOf(installedRegion()),
            pendingRemoval = PendingRemoval(
                installId = installId(),
                regionId = "queensland",
                regionName = "Queensland",
                bytes = PACKAGE_BYTES,
            ),
            notice = "Switching to the bundled Queensland data…",
        )
        DOWNLOAD_RETRYING -> base(
            catalog = listOf(catalogRegion()),
            download = DownloadProgress(
                jobId = "a1575e4b6fd941d81c10ab05",
                regionName = "Queensland",
                bytes = 214_000_000,
                total = PACKAGE_BYTES,
                phase = DownloadPhase.DOWNLOADING,
                retry = DownloadRetry(
                    attempt = 2,
                    maxAttempts = DownloadRetryPolicy.MAX_ATTEMPTS,
                    secondsUntilRetry = 4,
                    reason = "The connection dropped",
                ),
            ),
        )
        DOWNLOAD_VERIFYING -> base(
            catalog = listOf(catalogRegion()),
            download = DownloadProgress(
                jobId = "a1575e4b6fd941d81c10ab05",
                regionName = "Queensland",
                bytes = PACKAGE_BYTES,
                total = PACKAGE_BYTES,
                phase = DownloadPhase.VERIFYING,
            ),
        )
        SERVER_ERROR -> base().copy(
            error = "Couldn't connect to \"maps.curvemaps.app\". Check that this device is online, then try again.",
        )
        BUILD_FOLLOWING -> base(
            catalog = listOf(catalogRegion(), tasmaniaRegion()),
            activeJob = ServerJob(
                id = "b2686f5c7ea0",
                regionId = "tasmania",
                regionName = "Tasmania",
                state = "running",
                step = "tiles",
                progress = 0.42,
                message = "generating vector tiles (Planetiler)",
                error = "",
                cached = false,
                artifactSize = 0,
                artifactSha256 = "",
                downloadUrl = "",
            ),
        )
        SEARCH_RESULTS -> base(catalog = listOf(catalogRegion())).copy(
            search = ExtractSearch(
                query = "tas",
                searched = true,
                results = listOf(
                    ServerExtract("tasmania", "Tasmania", listOf("Australia and Oceania", "Australia"), "tasmania", 53_952_987, true, ""),
                    ServerExtract(
                        "australia", "Australia", listOf("Australia and Oceania"), "australia", 966_467_067, false,
                        "Australia is 922 MiB; this server builds extracts up to 768 MiB. Smaller choices: Queensland, Tasmania.",
                    ),
                ),
            ),
        )
        INSTALLING -> base(
            catalog = listOf(catalogRegion()),
            download = DownloadProgress(
                jobId = "",
                regionName = "Queensland",
                bytes = PACKAGE_BYTES,
                total = PACKAGE_BYTES,
                phase = DownloadPhase.INSTALLING,
            ),
        )
        INSTALL_DEFERRED -> base(
            catalog = listOf(catalogRegion()),
            installed = listOf(installedRegion()),
            notice = "Queensland is installed. It switches on when this ride ends.",
        ).copy(pendingActivation = installId())
        SERVICE_CONNECTED -> base(catalog = listOf(catalogRegion(), tasmaniaRegion())).copy(
            serverAddress = DEFAULT_SERVICE_PREVIEW,
            defaultServer = DEFAULT_SERVICE_PREVIEW,
            usingDefaultServer = true,
            serverVersion = "0.1.0",
        )
        // Installed maps before the catalog is loaded: one Check for updates button.
        UPDATE_CHECK -> base(installed = listOf(installedRegion()))
        UPDATE_AVAILABLE -> base(
            catalog = listOf(updatedCatalogRegion()),
            installed = listOf(installedRegion()),
        ).copy(activeInstallId = installId())
        UPDATED -> base(
            catalog = listOf(updatedCatalogRegion()),
            installed = listOf(updatedInstalledRegion()),
            notice = "Queensland is updated and active. The older copy is being deleted.",
        ).copy(activeInstallId = installId(UPDATE_FINGERPRINT))
        else -> null
    }

    private fun updatedCatalogRegion(): ServerRegion = catalogRegion().let { region ->
        region.copy(
            sourceDate = UPDATE_SOURCE_DATE,
            artifact = region.artifact!!.copy(
                fingerprint = UPDATE_FINGERPRINT,
                size = 362_480_221L,
                generatedAt = "2026-10-05T00:00:00Z",
                downloadUrl = "/api/v1/artifacts/queensland/$UPDATE_FINGERPRINT/queensland.motomap",
            ),
        )
    }

    private fun updatedInstalledRegion(): InstalledRegion = installedRegion().copy(
        installId = installId(UPDATE_FINGERPRINT),
        dirName = installId(UPDATE_FINGERPRINT),
        sourceDate = UPDATE_SOURCE_DATE,
        pipelineFingerprint = UPDATE_FINGERPRINT,
        installedAtEpochMs = 1_791_000_000_000L,
    )

    /** Stands in for BuildConfig.DEFAULT_MAP_SERVER in previews (no scheme literal). */
    private const val DEFAULT_SERVICE_PREVIEW = "maps.curvemaps.app"

    private fun tasmaniaRegion(): ServerRegion = ServerRegion(
        id = "tasmania",
        name = "Tasmania",
        coverage = "Australia and Oceania › Australia › Tasmania",
        disabled = false,
        maxZoom = 14,
        sourceDate = "261005",
        sourceSha256 = "",
        sourcePinned = false,
        artifact = null,
        activeJobId = null,
        requested = true,
        extractId = "tasmania",
    )

    private fun base(
        catalog: List<ServerRegion> = emptyList(),
        activeJob: ServerJob? = null,
        download: DownloadProgress? = null,
        interrupted: InterruptedDownload? = null,
        savedPackage: SavedPackage? = null,
        recoveredPackage: RecoveredPackage? = null,
        pendingRemoval: PendingRemoval? = null,
        installed: List<InstalledRegion> = emptyList(),
        notice: String? = null,
    ): MapsState = MapsState(
        serverAddress = DEFAULT_SERVICE_PREVIEW,
        defaultServer = DEFAULT_SERVICE_PREVIEW,
        usingDefaultServer = true,
        serverChecked = catalog.isNotEmpty(),
        serverVersion = "dev",
        generationEnabled = true,
        catalog = catalog,
        activeJob = activeJob,
        download = download,
        interrupted = interrupted,
        savedPackage = savedPackage,
        recoveredPackage = recoveredPackage,
        pendingRemoval = pendingRemoval,
        installed = installed,
        notice = notice,
    )

    private fun catalogRegion(): ServerRegion = ServerRegion(
        id = "queensland",
        name = "Queensland",
        coverage = "Queensland, Australia",
        disabled = false,
        maxZoom = 14,
        sourceDate = SOURCE_DATE,
        sourceSha256 = "b3e0b4cf98f8b9a4ef54a7c461be3b9471a64624ea3001229a585043942bd3fa",
        sourcePinned = true,
        artifact = ServerArtifact(
            available = true,
            fingerprint = FINGERPRINT,
            size = PACKAGE_BYTES,
            sha256 = "b3e0b4cf98f8b9a4ef54a7c461be3b9471a64624ea3001229a585043942bd3fa",
            generatedAt = "2026-08-15T00:00:00Z",
            downloadUrl = "/api/v1/artifacts/queensland/$FINGERPRINT/queensland.motomap",
        ),
        activeJobId = null,
    )

    /** The live map service's catalog shape: 22 regions on three continents. */
    private fun worldCatalog(): List<ServerRegion> = listOf(
        "queensland" to "Australia and Oceania › Australia › Queensland",
        "new-south-wales" to "Australia and Oceania › Australia › New South Wales",
        "victoria" to "Australia and Oceania › Australia › Victoria",
        "tasmania" to "Australia and Oceania › Australia › Tasmania",
        "south-australia" to "Australia and Oceania › Australia › South Australia",
        "western-australia" to "Australia and Oceania › Australia › Western Australia",
        "northern-territory" to "Australia and Oceania › Australia › Northern Territory",
        "act" to "Australia and Oceania › Australia › Australian Capital Territory",
        "new-zealand" to "Australia and Oceania › New Zealand",
        "switzerland" to "Europe › Switzerland",
        "austria" to "Europe › Austria",
        "isle-of-man" to "Europe › Isle of Man",
        "wales" to "Europe › United Kingdom › Wales",
        "scotland" to "Europe › United Kingdom › Scotland",
        "nord-est" to "Europe › Italy › Nord-Est",
        "us-tennessee" to "North America › Tennessee",
        "us-north-carolina" to "North America › North Carolina",
        "us-colorado" to "North America › Colorado",
        "us-utah" to "North America › Utah",
        "us-arizona" to "North America › Arizona",
        "socal" to "North America › California › Southern California",
        "norcal" to "North America › California › Northern California",
    ).mapIndexed { index, (id, coverage) ->
        val region = catalogRegion()
        if (id == region.id) {
            region.copy(coverage = coverage)
        } else {
            region.copy(
                id = id,
                name = coverage.substringAfterLast("› "),
                coverage = coverage,
                sourceDate = "261005",
                artifact = region.artifact!!.copy(
                    fingerprint = id.padEnd(64, '0'),
                    size = 60_000_000L + index * 31_000_000L,
                    downloadUrl = "/api/v1/artifacts/$id/${id.padEnd(64, '0')}/$id.motomap",
                ),
            )
        }
    }

    private fun installId(fingerprint: String = FINGERPRINT): String = "queensland-${fingerprint.take(12)}"

    private fun installedRegion(): InstalledRegion = InstalledRegion(
        installId = installId(),
        regionId = "queensland",
        regionName = "Queensland",
        coverage = "Queensland, Australia",
        sourceDate = SOURCE_DATE,
        sourceSha256 = "b3e0b4cf98f8b9a4ef54a7c461be3b9471a64624ea3001229a585043942bd3fa",
        pipelineFingerprint = FINGERPRINT,
        installedAtEpochMs = 1_780_000_000_000L,
        bytes = PACKAGE_BYTES,
        dirName = installId(),
    )
}
