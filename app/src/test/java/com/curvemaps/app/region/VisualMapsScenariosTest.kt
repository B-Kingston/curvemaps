package com.curvemaps.app.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visual-preview scenarios are the repeatable intermediate states that
 * `tools/test/visual.py` captures. They must keep describing the real UI
 * decisions: a preview that hides the Download action or invents an installed
 * size would make the screenshots lie.
 */
class VisualMapsScenariosTest {

    @Test
    fun everyNamedScenarioResolvesAndUnknownNamesDoNot() {
        assertTrue(VisualMapsScenarios.names.isNotEmpty())
        VisualMapsScenarios.names.forEach { name ->
            assertNotNull("scenario $name", VisualMapsScenarios.state(name))
        }
        assertNull(VisualMapsScenarios.state("no-such-scenario"))
    }

    @Test
    fun catalogReadyOffersDownloadWithTheTransferSize() {
        val state = VisualMapsScenarios.state(VisualMapsScenarios.CATALOG_READY)!!
        val region = state.catalog.single()
        assertTrue(region.hasCachedArtifact)
        val model = RegionUiModel.catalogRow(region, state.activeJob, false, false, false)
        assertEquals(CatalogAction.DOWNLOAD, model.action)
        assertEquals(357_091_613L, region.artifact!!.size)
    }

    @Test
    fun worldCatalogGroupsLikeTheLiveServiceAndOpensOnlyTheInstalledContinent() {
        val state = VisualMapsScenarios.state(VisualMapsScenarios.CATALOG_WORLD)!!
        assertEquals(22, state.catalog.size)
        assertEquals(state.catalog.size, state.catalog.map { it.id }.toSet().size)
        val groups = CatalogGrouping.group(state.catalog)
        assertEquals(listOf("Australia and Oceania", "Europe", "North America"), groups.map { it.name })
        val installed = state.installed.map { it.regionId }.toSet()
        assertEquals(
            listOf(true, false, false),
            groups.map { CatalogGrouping.initiallyExpanded(it, groups.size, false, null, installed) },
        )
        val queensland = RegionUiModel.catalogRow(
            state.catalog.first { it.id == "queensland" }, null, false, false, false, state.installed,
        )
        assertTrue(queensland.installed)
    }

    @Test
    fun buildFailedKeepsRetryVisibleWithTheServerError() {
        val state = VisualMapsScenarios.state(VisualMapsScenarios.BUILD_FAILED)!!
        val model = RegionUiModel.catalogRow(state.catalog.single(), state.activeJob, false, false, false)
        assertEquals(CatalogAction.RETRY_BUILD, model.action)
        assertTrue(model.errorLine!!.isNotBlank())
    }

    @Test
    fun downloadProgressAndInterruptedStatesCarryRealByteCounts() {
        val downloading = VisualMapsScenarios.state(VisualMapsScenarios.DOWNLOAD_PROGRESS)!!
        assertEquals(DownloadPhase.DOWNLOADING, downloading.download!!.phase)
        assertTrue(downloading.download!!.bytes > 0)
        assertEquals(357_091_613L, downloading.download!!.total)

        val interrupted = VisualMapsScenarios.state(VisualMapsScenarios.DOWNLOAD_INTERRUPTED)!!
        assertTrue(interrupted.interrupted!!.bytes > 0)
        assertEquals(357_091_613L, interrupted.interrupted!!.total)
    }

    @Test
    fun savedAndRecoveredStatesNameTheFileAndArchiveSize() {
        val saved = VisualMapsScenarios.state(VisualMapsScenarios.PACKAGE_SAVED)!!
        assertEquals("queensland-260815.motomap", saved.savedPackage!!.fileName)
        assertEquals(357_091_613L, saved.savedPackage!!.bytes)

        val recovered = VisualMapsScenarios.state(VisualMapsScenarios.RECOVERED_PACKAGE)!!
        assertEquals(357_091_613L, recovered.recoveredPackage!!.bytes)
    }

    @Test
    fun installedStateShowsAnActualInstalledSizeAndNoPendingRemoval() {
        val state = VisualMapsScenarios.state(VisualMapsScenarios.INSTALLED)!!
        val install = state.installed.single()
        assertEquals(357_091_613L, install.bytes)
        assertTrue(RegionUiModel.installedSummary(install).contains("installed"))
        assertNull(state.pendingRemoval)
    }

    @Test
    fun removalPendingStateMarksTheMapBeingDeleted() {
        val state = VisualMapsScenarios.state(VisualMapsScenarios.REMOVAL_PENDING)!!
        val install = state.installed.single()
        assertEquals(install.installId, state.pendingRemoval!!.installId)
        assertTrue(state.notice!!.contains("bundled"))
    }

    @Test
    fun previewStatesNeverClaimABundledDelete() {
        VisualMapsScenarios.names.forEach { name ->
            val state = VisualMapsScenarios.state(name)!!
            state.pendingRemoval?.let { pending ->
                assertFalse(pending.installId.contains("bundled"))
            }
        }
    }

    @Test
    fun retryingAndVerifyingStatesKeepTheirBytesAndExplainTheWait() {
        val retrying = VisualMapsScenarios.state(VisualMapsScenarios.DOWNLOAD_RETRYING)!!.download!!
        assertEquals(DownloadPhase.DOWNLOADING, retrying.phase)
        assertTrue(retrying.bytes > 0)
        val retry = retrying.retry!!
        assertTrue(retry.attempt in 2..retry.maxAttempts)
        assertTrue(retry.secondsUntilRetry > 0)

        val verifying = VisualMapsScenarios.state(VisualMapsScenarios.DOWNLOAD_VERIFYING)!!.download!!
        assertEquals(DownloadPhase.VERIFYING, verifying.phase)
        assertEquals(verifying.total, verifying.bytes)
        assertNull(verifying.retry)
    }

    @Test
    fun serverErrorStateIsDisconnectedWithAnActionableMessage() {
        val state = VisualMapsScenarios.state(VisualMapsScenarios.SERVER_ERROR)!!
        assertFalse(state.serverChecked)
        assertTrue(state.catalog.isEmpty())
        assertTrue(state.error!!.contains("Check that this device is online"))
    }

    @Test
    fun hostedServiceScenariosDescribeTheRealDecisions() {
        val following = VisualMapsScenarios.state(VisualMapsScenarios.BUILD_FOLLOWING)!!
        val tasmania = following.catalog.first { it.id == "tasmania" }
        val row = RegionUiModel.catalogRow(tasmania, following.activeJob, false, false, false)
        assertTrue(row.showCancel)
        assertEquals("Drawing the map · 42%", row.statusLine)

        val search = VisualMapsScenarios.state(VisualMapsScenarios.SEARCH_RESULTS)!!
        assertEquals(listOf(true, false), search.search.results.map { it.eligible })

        val installing = VisualMapsScenarios.state(VisualMapsScenarios.INSTALLING)!!
        assertEquals(DownloadPhase.INSTALLING, installing.download!!.phase)

        val deferred = VisualMapsScenarios.state(VisualMapsScenarios.INSTALL_DEFERRED)!!
        assertEquals(deferred.installed.single().installId, deferred.pendingActivation)
        assertNull(deferred.activeInstallId)

        val connected = VisualMapsScenarios.state(VisualMapsScenarios.SERVICE_CONNECTED)!!
        assertTrue(connected.usingDefaultServer)
        assertTrue(connected.serverChecked)
    }

    private fun updateLine(state: MapsState): InstalledUpdateModel {
        val install = RegionUiModel.visibleInstalls(state.installed, state.activeInstallId).single()
        return RegionUiModel.installedUpdate(
            install, state.installed, state.serverChecked, state.catalog,
            state.busy, state.download != null, state.pendingRemoval != null,
        )
    }

    @Test
    fun updateScenariosShowEachUpdateDecision() {
        val unchecked = VisualMapsScenarios.state(VisualMapsScenarios.UPDATE_CHECK)!!
        assertFalse(unchecked.serverChecked)
        assertEquals(MapUpdateStatus.UNCHECKED, updateLine(unchecked).status)

        val available = VisualMapsScenarios.state(VisualMapsScenarios.UPDATE_AVAILABLE)!!
        val offer = updateLine(available)
        assertEquals(MapUpdateStatus.AVAILABLE, offer.status)
        assertTrue(offer.actionEnabled)
        assertEquals("Update Queensland map", offer.actionDescription)

        val updated = VisualMapsScenarios.state(VisualMapsScenarios.UPDATED)!!
        assertEquals(MapUpdateStatus.UP_TO_DATE, updateLine(updated).status)
        assertEquals(updated.installed.single().installId, updated.activeInstallId)
        assertTrue(updated.notice!!.contains("updated"))

        // The plain installed preview is the service's own package.
        val installed = VisualMapsScenarios.state(VisualMapsScenarios.INSTALLED)!!
        assertEquals(MapUpdateStatus.UP_TO_DATE, updateLine(installed).status)
    }
}
