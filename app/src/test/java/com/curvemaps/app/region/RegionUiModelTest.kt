package com.curvemaps.app.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog-row decision is the fix for the stale-job trap: a completed,
 * failed, or canceled build job is informational only and must never hide the
 * Download/Retry action. It also pins the installed-map and delete-confirmation
 * wording (dataset name, real installed size, user Files untouched).
 */
class RegionUiModelTest {

    private fun region(
        id: String = "queensland",
        artifact: ServerArtifact? = artifact(),
        disabled: Boolean = false,
    ) = ServerRegion(
        id = id,
        name = "Queensland",
        coverage = "Queensland, Australia",
        disabled = disabled,
        maxZoom = 14,
        sourceDate = "260815",
        sourceSha256 = "a".repeat(64),
        sourcePinned = true,
        artifact = artifact,
        activeJobId = null,
    )

    private fun artifact(url: String = "/api/v1/artifacts/queensland/${"b".repeat(64)}/queensland.motomap") =
        ServerArtifact(
            available = true,
            fingerprint = "b".repeat(64),
            size = 357_091_613,
            sha256 = "b".repeat(64),
            generatedAt = "2026-08-15T00:00:00Z",
            downloadUrl = url,
        )

    private fun job(
        state: String,
        regionId: String = "queensland",
        downloadUrl: String = "/api/v1/artifacts/queensland/${"b".repeat(64)}/queensland.motomap",
        error: String = "",
    ) = ServerJob(
        id = "job-1",
        regionId = regionId,
        regionName = "Queensland",
        state = state,
        step = "import",
        progress = if (state == "completed") 1.0 else 0.5,
        message = "Importing",
        error = error,
        cached = false,
        artifactSize = 357_091_613,
        artifactSha256 = "b".repeat(64),
        downloadUrl = downloadUrl,
    )

    private fun model(
        region: ServerRegion = region(),
        activeJob: ServerJob? = null,
        busy: Boolean = false,
        downloadActive: Boolean = false,
        pendingRemoval: Boolean = false,
    ) = RegionUiModel.catalogRow(region, activeJob, busy, downloadActive, pendingRemoval)

    @Test
    fun deferredImportAndRemovalRetryRemainActionable() {
        val retry = VisualMapsScenarios.state(VisualMapsScenarios.REMOVAL_RETRY)!!
        assertTrue(retry.pendingRemoval == null)
        assertTrue(retry.installed.isNotEmpty())
        assertTrue(retry.error!!.contains("Try deleting"))
        val imported = VisualMapsScenarios.state(VisualMapsScenarios.IMPORT_DEFERRED)!!
        assertTrue(imported.error == null)
        assertTrue(imported.installed.isNotEmpty())
        assertTrue(imported.notice!!.contains("installed"))
    }

    @Test
    fun aTerminalCompletedJobStillOffersDownload() {
        val model = model(activeJob = job("completed"))
        assertEquals(CatalogAction.DOWNLOAD, model.action)
        assertEquals("Download & install", model.actionLabel)
        assertTrue(model.actionEnabled)
        assertFalse("a terminal job must not render the progress branch", model.showProgress)
        assertFalse(model.showCancel)
        assertEquals("Build complete — ready to install", model.statusLine)
    }

    @Test
    fun aTerminalFailedJobOffersRetry() {
        val model = model(
            region = region(artifact = null),
            activeJob = job("failed", downloadUrl = "", error = "Extract download returned HTML"),
        )
        assertEquals(CatalogAction.RETRY_BUILD, model.action)
        assertEquals("Try the build again", model.actionLabel)
        assertTrue(model.actionEnabled)
        assertFalse(model.showProgress)
        assertEquals("Build failed", model.statusLine)
        assertEquals("Extract download returned HTML", model.errorLine)
    }

    @Test
    fun aCanceledJobOffersRetry() {
        val model = model(region = region(artifact = null), activeJob = job("canceled", downloadUrl = ""))
        assertEquals(CatalogAction.RETRY_BUILD, model.action)
        assertEquals("Build canceled", model.statusLine)
    }

    @Test
    fun aCompletedJobWithoutACachedArtifactDownloadsFromTheJob() {
        val model = model(region = region(artifact = null), activeJob = job("completed"))
        assertEquals(CatalogAction.DOWNLOAD, model.action)
    }

    @Test
    fun anActiveJobOwnsTheRowWithProgressAndCancel() {
        val model = model(activeJob = job("running"))
        assertEquals(CatalogAction.NONE, model.action)
        assertTrue(model.showProgress)
        assertTrue(model.showCancel)
        assertEquals("Importing · 50%", model.statusLine)
    }

    @Test
    fun aCachedArtifactOffersDownloadWithoutAJob() {
        val model = model()
        assertEquals(CatalogAction.DOWNLOAD, model.action)
        assertTrue(model.actionEnabled)
    }

    @Test
    fun anUncachedRegionWithoutAJobOffersRequestBuild() {
        val model = model(region = region(artifact = null))
        assertEquals(CatalogAction.REQUEST_BUILD, model.action)
        assertEquals("Build this map", model.actionLabel)
    }

    @Test
    fun aDisabledRegionHasNoAction() {
        val model = model(region = region(disabled = true))
        assertEquals(CatalogAction.NONE, model.action)
        assertEquals("Disabled by the operator", model.statusLine)
    }

    @Test
    fun busyAndConcurrentOperationsDisableTheActionButKeepItVisible() {
        assertFalse(model(busy = true).actionEnabled)
        assertFalse(model(downloadActive = true).actionEnabled)
        assertFalse(model(pendingRemoval = true).actionEnabled)
        // The action itself is still offered so a finished download cannot
        // strand the row without a retry.
        assertEquals(CatalogAction.DOWNLOAD, model(downloadActive = true).action)
    }

    @Test
    fun aJobForAnotherRegionDoesNotOwnThisRow() {
        val model = model(
            region = region(id = "tasmania"),
            activeJob = job("running", regionId = "queensland"),
        )
        assertEquals(CatalogAction.DOWNLOAD, model.action)
        assertFalse(model.showProgress)
    }

    @Test
    fun installedSummaryNamesTheSourceAndActualInstalledBytes() {
        val install = InstalledRegion(
            installId = "queensland-abc",
            regionId = "queensland",
            regionName = "Queensland",
            coverage = "Queensland, Australia",
            sourceDate = "260815",
            sourceSha256 = "a".repeat(64),
            pipelineFingerprint = "b".repeat(64),
            installedAtEpochMs = 1L,
            bytes = 12 * 1024 * 1024,
            dirName = "queensland-abc",
        )
        val summary = RegionUiModel.installedSummary(install)
        assertTrue(summary, summary.contains("source 260815"))
        assertTrue(summary, summary.contains("12.0 MiB installed"))
    }

    @Test
    fun deleteConfirmationNamesTheDatasetTheReclaimedSpaceAndUserFiles() {
        val install = InstalledRegion(
            installId = "queensland-abc",
            regionId = "queensland",
            regionName = "Queensland",
            coverage = null,
            sourceDate = "260815",
            sourceSha256 = "a".repeat(64),
            pipelineFingerprint = "b".repeat(64),
            installedAtEpochMs = 1L,
            bytes = 357_091_613,
            dirName = "queensland-abc",
        )
        val inactive = RegionUiModel.deleteConfirmation(install, active = false)
        assertTrue(inactive, inactive.contains("Queensland"))
        assertTrue(inactive, inactive.contains("source 260815"))
        assertTrue(inactive, inactive.contains(RegionUiModel.formatBytes(357_091_613)))
        assertTrue(inactive, inactive.contains("in Files is not removed"))
        assertFalse(inactive, inactive.contains("bundled"))

        val active = RegionUiModel.deleteConfirmation(install, active = true)
        assertTrue(active, active.contains("bundled"))
        assertTrue(active, active.contains("releases the routing graph"))
    }

    @Test
    fun aBuildWithoutBundledDataNeverPromisesAFallback() {
        val install = InstalledRegion(
            installId = "queensland-abc",
            regionId = "queensland",
            regionName = "Queensland",
            coverage = null,
            sourceDate = "260815",
            sourceSha256 = "a".repeat(64),
            pipelineFingerprint = "b".repeat(64),
            installedAtEpochMs = 1L,
            bytes = 1_000_000,
            dirName = "queensland-abc",
        )
        val active = RegionUiModel.deleteConfirmation(install, active = true, bundledAvailable = false)
        assertFalse(active, active.contains("bundled"))
        assertTrue(active, active.contains("releases the routing graph"))
        assertTrue(active, active.contains("unavailable"))

        val empty = RegionUiModel.installedEmptySummary(bundledAvailable = false)
        assertFalse(empty, empty.contains("always available"))
        assertTrue(empty, empty.contains(".motomap"))
        for (text in listOf(
            RegionUiModel.releasingActiveNotice(bundledAvailable = false),
            RegionUiModel.activeDeleteHint(bundledAvailable = false),
            RegionUiModel.releasingRowText(bundledAvailable = false),
        )) {
            assertFalse(text, text.contains("bundled", ignoreCase = true))
            assertFalse(text, text.contains("Queensland"))
        }
        // With bundled data the wording is unchanged.
        assertTrue(RegionUiModel.releasingActiveNotice().contains("bundled Queensland"))
        assertTrue(RegionUiModel.activeDeleteHint().contains("bundled Queensland"))
        assertTrue(RegionUiModel.installedEmptySummary().contains("always available"))
    }

    @Test
    fun bundledDataIsNeverClaimedAsReclaimable() {
        val summary = RegionUiModel.bundledSummary()
        assertTrue(summary, summary.contains("cannot be deleted"))
        assertFalse(summary, summary.contains("MiB"))
    }

    @Test
    fun formatBytesIsHumanReadable() {
        assertEquals("512 B", RegionUiModel.formatBytes(512))
        assertEquals("1.0 KiB", RegionUiModel.formatBytes(1024))
        assertEquals("340.5 MiB", RegionUiModel.formatBytes(357_091_613))
    }

    @Test
    fun unknownRegionsAreNotInvented() {
        assertNull(RegionUiModel.catalogRow(region(), null, false, false, false).errorLine)
    }

    @Test
    fun anAlreadyInstalledPackageSaysSoButKeepsDownloadAvailable() {
        fun install(fingerprint: String) = InstalledRegion(
            installId = "queensland-${fingerprint.take(12)}",
            regionId = "queensland",
            regionName = "Queensland",
            coverage = null,
            sourceDate = "260815",
            sourceSha256 = "a".repeat(64),
            pipelineFingerprint = fingerprint,
            installedAtEpochMs = 1L,
            bytes = 1L,
            dirName = "queensland-${fingerprint.take(12)}",
        )
        val same = RegionUiModel.catalogRow(region(), null, false, false, false, listOf(install("b".repeat(64))))
        assertEquals(CatalogAction.DOWNLOAD, same.action)
        assertTrue(same.actionEnabled)
        assertEquals("This version is installed on this device", same.statusLine)

        // An older install of the same region is not the server's version:
        // the row offers the server's package as its update.
        val older = RegionUiModel.catalogRow(region(), null, false, false, false, listOf(install("c".repeat(64))))
        assertFalse(older.installed)
        assertEquals("Update for the copy installed on this device", older.statusLine)
        assertEquals("Update map queensland", older.actionDescription)
    }

    @Test
    fun downloadableRegionsInstallInOneTapAndOfferASaveCopy() {
        val model = model()
        assertEquals(CatalogAction.DOWNLOAD, model.action)
        assertEquals("Download and install queensland", model.actionDescription)
        assertTrue(model.offerSaveCopy)
        val build = model(region = region(artifact = null))
        assertFalse(build.offerSaveCopy)
        assertEquals("Build map queensland", build.actionDescription)
    }

    @Test
    fun anInstalledVersionOffersInstallAgain() {
        val installed = InstalledRegion(
            installId = "queensland-bbbbbbbbbbbb",
            regionId = "queensland",
            regionName = "Queensland",
            coverage = null,
            sourceDate = "260815",
            sourceSha256 = "a".repeat(64),
            pipelineFingerprint = "b".repeat(64),
            installedAtEpochMs = 0,
            bytes = 1,
            dirName = "queensland-bbbbbbbbbbbb",
        )
        val model = RegionUiModel.catalogRow(region(), null, false, false, false, listOf(installed))
        assertEquals("Install again", model.actionLabel)
        assertEquals("This version is installed on this device", model.statusLine)
    }

    @Test
    fun aFollowedBuildShowsPlainProgressAndStopWaiting() {
        val model = model(activeJob = job("running"))
        assertTrue(model.showCancel)
        assertTrue(model.showProgress)
        assertEquals(CatalogAction.NONE, model.action)
        val tiles = job("running").copy(step = "tiles", progress = 0.42)
        assertEquals("Drawing the map · 42%", RegionUiModel.buildStatus(tiles))
        val queued = job("queued").copy(message = "")
        assertEquals("Waiting for the map service", RegionUiModel.buildStatus(queued))
        assertEquals("Publishing · 100%", RegionUiModel.buildStatus(job("running").copy(step = "publish", progress = 1.0)))
    }

    @Test
    fun proRegionsCarryABadge() {
        val pro = region().copy(tier = "pro")
        assertTrue(model(region = pro).pro)
        assertFalse(model().pro)
    }

    @Test
    fun extractRowsExplainSizeAndEligibility() {
        val tasmania = ServerExtract("tasmania", "Tasmania", listOf("Australia and Oceania", "Australia"), "tasmania", 54_000_000, true, "")
        val row = RegionUiModel.extractRow(tasmania, busy = false, inCatalog = false)
        assertEquals("Tasmania", row.title)
        assertTrue(row.detail.startsWith("Australia · about 92.7 MiB"))
        assertEquals("Build this map", row.actionLabel)
        assertTrue(row.actionEnabled)
        assertNull(row.blockedReason)
        assertEquals("Get this map", RegionUiModel.extractRow(tasmania, false, inCatalog = true).actionLabel)
        assertFalse(RegionUiModel.extractRow(tasmania, busy = true, inCatalog = false).actionEnabled)
        val tooBig = tasmania.copy(id = "australia", name = "Australia", eligible = false, reason = "Australia is 922 MiB.")
        val blocked = RegionUiModel.extractRow(tooBig, busy = false, inCatalog = false)
        assertFalse(blocked.actionEnabled)
        assertEquals("Australia is 922 MiB.", blocked.blockedReason)
        assertEquals(
            "This region is too large to build here",
            RegionUiModel.extractRow(tooBig.copy(reason = ""), false, false).blockedReason,
        )
    }

    @Test
    fun installSpaceCoversTheDownloadAndItsExtractedCopy() {
        assertEquals(0L, RegionUiModel.installSpaceNeeded(0))
        assertEquals(220L, RegionUiModel.installSpaceNeeded(100))
        val message = RegionUiModel.notEnoughSpaceMessage("Queensland", 785_000_000, 100_000_000)
        assertTrue(message.contains("Queensland"))
        assertTrue(message.contains("748.6 MiB"))
        assertTrue(message.contains("95.4 MiB"))
    }

    @Test
    fun serviceAndDateLabelsReadNaturally() {
        assertEquals("CurveMaps map service", RegionUiModel.serviceLabel("ignored", usingDefault = true))
        assertEquals("maps.example.net:8443/base", RegionUiModel.serviceLabel("https://maps.example.net:8443/base/", false))
        assertEquals("No map server chosen", RegionUiModel.serviceLabel("", false))
        assertEquals("5 Oct 2026", RegionUiModel.sourceDateLabel("261005"))
        assertEquals("15 Aug 2026", RegionUiModel.sourceDateLabel("2026-08-15"))
        assertEquals("not-a-date", RegionUiModel.sourceDateLabel("not-a-date"))
        assertEquals("261305", RegionUiModel.sourceDateLabel("261305"))
    }

    @Test
    fun catalogSubtitleNamesSizeAndReadableDataDate() {
        assertEquals("340.5 MiB · map data 15 Aug 2026", RegionUiModel.catalogSubtitle(region()))
        assertEquals("Not built yet · map data 15 Aug 2026", RegionUiModel.catalogSubtitle(region(artifact = null)))
        assertEquals("5 Oct 2026", RegionUiModel.sourceDateLabel("261005"))
        assertEquals("261305", RegionUiModel.sourceDateLabel("261305"))
        assertEquals("261032", RegionUiModel.sourceDateLabel("261032"))
    }

    @Test
    fun theInstalledFlagFollowsTheServersCurrentFingerprint() {
        val install = InstalledRegion(
            installId = "queensland-bbbbbbbbbbbb",
            regionId = "queensland",
            regionName = "Queensland",
            coverage = null,
            sourceDate = "260815",
            sourceSha256 = "a".repeat(64),
            pipelineFingerprint = "b".repeat(64),
            installedAtEpochMs = 1L,
            bytes = 1L,
            dirName = "queensland-bbbbbbbbbbbb",
        )
        val current = RegionUiModel.catalogRow(region(), null, false, false, false, listOf(install))
        assertTrue(current.installed)
        assertEquals(CatalogAction.DOWNLOAD, current.action)
        val stale = RegionUiModel.catalogRow(region(), null, false, false, false, listOf(install.copy(pipelineFingerprint = "d".repeat(64))))
        assertFalse(stale.installed)
    }

    @Test
    fun compactRowsUseShortLabelsButKeepTheFullDescriptions() {
        assertEquals("Install", model().compactLabel)
        assertEquals("Download and install queensland", model().actionDescription)
        assertEquals("Build", model(region = region(artifact = null)).compactLabel)
        assertEquals("Retry", model(region = region(artifact = null), activeJob = job("failed")).compactLabel)
        assertEquals("", model(activeJob = job("running")).compactLabel)
    }
}
