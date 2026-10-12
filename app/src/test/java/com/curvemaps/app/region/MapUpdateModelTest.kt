package com.curvemaps.app.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Map updates: an installed map is current when its package fingerprint is
 * the map service's, an update is offered only for a different fingerprint
 * whose data is not older, and the device keeps one copy per region — the
 * newest install (by install number, never the phone's clock) replaces the
 * others, never the active map, never one that is already being deleted, and
 * never copies from before the app kept one copy per region.
 */
class MapUpdateModelTest {

    private val serverFingerprint = "b".repeat(64)

    private fun install(
        fingerprint: String = "c".repeat(64),
        regionId: String = "queensland",
        sourceDate: String = "260815",
        installedAt: Long = 1L,
        installId: String = "$regionId-${fingerprint.take(12)}",
        sequence: Long = installedAt,
    ) = InstalledRegion(
        installId = installId,
        regionId = regionId,
        regionName = regionId.replaceFirstChar { it.uppercase() },
        coverage = null,
        sourceDate = sourceDate,
        sourceSha256 = "a".repeat(64),
        pipelineFingerprint = fingerprint,
        installedAtEpochMs = installedAt,
        bytes = 1L,
        dirName = installId,
        installSequence = sequence,
    )

    private fun region(
        sourceDate: String = "261005",
        artifact: ServerArtifact? = ServerArtifact(
            available = true,
            fingerprint = serverFingerprint,
            size = 357_091_613,
            sha256 = "e".repeat(64),
            generatedAt = "2026-10-05T00:00:00Z",
            downloadUrl = "/api/v1/artifacts/queensland/$serverFingerprint/queensland.motomap",
        ),
        disabled: Boolean = false,
    ) = ServerRegion(
        id = "queensland",
        name = "Queensland",
        coverage = "Queensland, Australia",
        disabled = disabled,
        maxZoom = 14,
        sourceDate = sourceDate,
        sourceSha256 = "a".repeat(64),
        sourcePinned = true,
        artifact = artifact,
        activeJobId = null,
    )

    private fun update(
        install: InstalledRegion,
        catalog: List<ServerRegion> = listOf(region()),
        installed: List<InstalledRegion> = listOf(install),
        serverChecked: Boolean = true,
        busy: Boolean = false,
        downloadActive: Boolean = false,
        pendingRemoval: Boolean = false,
        activeInstallId: String? = install.installId,
        pendingRemovalId: String? = null,
        pendingActivation: String? = null,
    ) = RegionUiModel.installedUpdate(
        install, installed, serverChecked, catalog, busy, downloadActive, pendingRemoval,
        activeInstallId, pendingRemovalId, pendingActivation,
    )

    @Test
    fun theSameFingerprintIsUpToDate() {
        val model = update(install(fingerprint = serverFingerprint, sourceDate = "261005"))
        assertEquals(MapUpdateStatus.UP_TO_DATE, model.status)
        assertEquals("Up to date", model.line)
        assertFalse(model.offersUpdate)
    }

    @Test
    fun aDifferentFingerprintWithNewerDataOffersUpdateWithDateAndSize() {
        val model = update(install())
        assertEquals(MapUpdateStatus.AVAILABLE, model.status)
        assertTrue(model.offersUpdate)
        assertTrue(model.actionEnabled)
        assertEquals("Update", model.actionLabel)
        assertEquals("Update Queensland map", model.actionDescription)
        assertEquals("Update available · map data 5 Oct 2026 · 340.5 MiB download", model.line)
    }

    @Test
    fun aPipelineChangeOnTheSameDataIsAnUpdate() {
        // Same OSM extract, different fingerprint: the routing graph or tiles changed.
        val model = update(install(sourceDate = "261005"))
        assertEquals(MapUpdateStatus.AVAILABLE, model.status)
    }

    @Test
    fun olderServiceDataIsNeverOfferedAsAnUpdate() {
        val model = update(install(sourceDate = "261101"))
        assertEquals(MapUpdateStatus.UP_TO_DATE, model.status)
        assertEquals("Up to date (newer than the map service's copy)", model.line)
        assertFalse(RegionUiModel.updateAvailable(install(sourceDate = "261101"), region()))
    }

    @Test
    fun unreadableDatesLeaveTheDecisionToTheFingerprint() {
        assertTrue(RegionUiModel.updateAvailable(install(sourceDate = "unknown"), region()))
        assertTrue(RegionUiModel.updateAvailable(install(), region(sourceDate = "")))
        assertFalse(RegionUiModel.updateAvailable(install(fingerprint = serverFingerprint, sourceDate = "?"), region()))
    }

    @Test
    fun busyTransfersAndDeletionsDisableTheUpdateButKeepItVisible() {
        listOf(
            update(install(), busy = true),
            update(install(), downloadActive = true),
            update(install(), pendingRemoval = true),
        ).forEach { model ->
            assertTrue(model.offersUpdate)
            assertFalse(model.actionEnabled)
        }
        // A ride does not block downloading (the new copy waits for the ride's end).
        assertTrue(update(install(), pendingActivation = "tasmania-x").actionEnabled)
    }

    @Test
    fun nothingIsClaimedBeforeTheCatalogIsLoaded() {
        val model = update(install(), serverChecked = false, catalog = emptyList())
        assertEquals(MapUpdateStatus.UNCHECKED, model.status)
        assertNull(model.line)
        assertFalse(model.offersUpdate)
    }

    @Test
    fun withoutAPackageToCompareTheRowSaysWhy() {
        val unlisted = update(install(regionId = "tasmania"))
        assertEquals(MapUpdateStatus.UNKNOWN, unlisted.status)
        assertEquals("The map service doesn't list this region", unlisted.line)

        val unbuilt = update(install(), catalog = listOf(region(artifact = null)))
        assertEquals(MapUpdateStatus.UNKNOWN, unbuilt.status)
        assertEquals("The map service has no package ready for this region", unbuilt.line)

        val unavailable = region().let { it.copy(artifact = it.artifact!!.copy(available = false)) }
        assertEquals(MapUpdateStatus.UNKNOWN, update(install(), catalog = listOf(unavailable)).status)

        val disabled = update(install(), catalog = listOf(region(disabled = true)))
        assertEquals(MapUpdateStatus.UNKNOWN, disabled.status)
        assertFalse(disabled.offersUpdate)
    }

    @Test
    fun aCopyReplacedByANewerInstallSaysSoInsteadOfOfferingUpdate() {
        val old = install(installedAt = 1L)
        val new = install(fingerprint = serverFingerprint, sourceDate = "261005", installedAt = 2L)
        val both = listOf(new, old)
        val replaced = update(old, installed = both)
        assertEquals(MapUpdateStatus.REPLACED, replaced.status)
        assertEquals("A newer copy is installed. Activate it to replace this one.", replaced.line)
        // The ride-end wording only when the newer copy really is waiting for it.
        assertEquals(
            "A newer copy is installed. It takes over when this ride ends.",
            update(old, installed = both, pendingActivation = new.installId).line,
        )
        assertEquals(
            "A newer copy is installed. Activate it to replace this one.",
            update(old, installed = both, pendingActivation = "tasmania-x").line,
        )
        // Listed although inactive only because its deletion failed.
        assertEquals(
            "A newer copy replaced this one. Delete it to free the space.",
            update(old, installed = both, activeInstallId = new.installId).line,
        )
        assertEquals(MapUpdateStatus.UP_TO_DATE, update(new, installed = both).status)
        // The service's package is already on the device, so nothing is downloaded again.
        assertFalse(RegionUiModel.updateAvailable(old, region(), both))
    }

    @Test
    fun aNewerCopyBeingDeletedReplacesNothing() {
        val old = install(installedAt = 1L)
        val new = install(fingerprint = serverFingerprint, installedAt = 2L)
        val both = listOf(new, old)
        val model = update(old, installed = both, pendingRemovalId = new.installId, pendingRemoval = true)
        assertTrue(model.status != MapUpdateStatus.REPLACED)
        // With the newer copy on its way out, the older one is kept and listed.
        assertTrue(RegionUiModel.supersededInstalls(both, activeInstallId = null, pendingRemovalId = new.installId).isEmpty())
        assertEquals(old, RegionUiModel.keptCopy("queensland", both, pendingRemovalId = new.installId))
    }

    @Test
    fun theInstallNumberDecidesWhichCopyIsKeptNotTheClock() {
        // The older copy was installed while the phone's clock ran a year ahead.
        val old = install(installedAt = 1_800_000_000_000L, sequence = 1L)
        val new = install(fingerprint = serverFingerprint, installedAt = 1_770_000_000_000L, sequence = 2L)
        assertEquals(listOf(old), RegionUiModel.supersededInstalls(listOf(old, new), activeInstallId = null))
        assertEquals(MapUpdateStatus.REPLACED, update(old, installed = listOf(old, new)).status)
    }

    @Test
    fun copiesFromBeforeOneCopyPerRegionAreNeverDeletedByThemselves() {
        val first = install(installedAt = 1L, sequence = 0L)
        val second = install(fingerprint = serverFingerprint, installedAt = 2L, sequence = 0L)
        val legacy = listOf(second, first)
        assertTrue(RegionUiModel.supersededInstalls(legacy, activeInstallId = null).isEmpty())
        assertEquals(legacy, RegionUiModel.visibleInstalls(legacy, null))
        assertTrue(update(first, installed = legacy).status != MapUpdateStatus.REPLACED)
        // Installing the region again (a numbered copy) replaces both.
        val fresh = install(fingerprint = "d".repeat(64), installedAt = 0L, sequence = 1L)
        assertEquals(setOf(first, second), RegionUiModel.supersededInstalls(legacy + fresh, null).toSet())
    }

    @Test
    fun aReplacedCopyThatFailedToDeleteIsListedAgain() {
        val old = install(installedAt = 1L)
        val new = install(fingerprint = serverFingerprint, installedAt = 2L)
        val both = listOf(new, old)
        assertEquals(listOf(new), RegionUiModel.visibleInstalls(both, new.installId))
        assertEquals(both, RegionUiModel.visibleInstalls(both, new.installId, failedRemovals = setOf(old.installId)))
    }

    @Test
    fun theCatalogRowOffersUpdateForAnOlderInstalledCopy() {
        val row = RegionUiModel.catalogRow(region(), null, false, false, false, listOf(install()))
        assertEquals(CatalogAction.DOWNLOAD, row.action)
        assertEquals("Update", row.actionLabel)
        assertEquals("Update", row.compactLabel)
        assertEquals("Update map queensland", row.actionDescription)
        assertEquals("Update for the copy installed on this device", row.statusLine)
        assertFalse(row.installed)

        val newerHere = RegionUiModel.catalogRow(region(), null, false, false, false, listOf(install(sourceDate = "261101")))
        assertEquals("Download and install queensland", newerHere.actionDescription)
    }

    @Test
    fun theNewestCopyOfEachRegionIsKept() {
        val oldQld = install(installedAt = 1L)
        val newQld = install(fingerprint = serverFingerprint, installedAt = 3L)
        val tas = install(regionId = "tasmania", installedAt = 2L)
        val superseded = RegionUiModel.supersededInstalls(listOf(newQld, tas, oldQld), activeInstallId = null)
        assertEquals(listOf(oldQld), superseded)
        assertEquals(listOf(newQld, tas), RegionUiModel.visibleInstalls(listOf(newQld, tas, oldQld), null))
    }

    @Test
    fun theActiveCopyAndAPendingDeletionAreNeverReplacedFromUnderTheMap() {
        val oldQld = install(installedAt = 1L)
        val newQld = install(fingerprint = serverFingerprint, installedAt = 3L)
        val all = listOf(newQld, oldQld)
        assertTrue(RegionUiModel.supersededInstalls(all, activeInstallId = oldQld.installId).isEmpty())
        assertEquals(all, RegionUiModel.visibleInstalls(all, activeInstallId = oldQld.installId))
        assertTrue(RegionUiModel.supersededInstalls(all, null, pendingRemovalId = oldQld.installId).isEmpty())
    }

    @Test
    fun replacementInvariantsHoldForRandomStores() {
        val random = Random(20261007)
        repeat(500) {
            val installs = (0 until random.nextInt(0, 9)).map { index ->
                install(
                    fingerprint = index.toString().padEnd(64, 'f'),
                    regionId = listOf("queensland", "tasmania", "wales").random(random),
                    installedAt = random.nextLong(0, 5),
                    installId = "install-$index",
                    // Unnumbered (older app) copies mixed with numbered ones.
                    sequence = if (random.nextInt(3) == 0) 0L else index + 1L,
                )
            }
            val active = installs.randomOrNull(random)?.takeIf { random.nextBoolean() }?.installId
            val pending = installs.randomOrNull(random)?.takeIf { random.nextBoolean() }?.installId
            val superseded = RegionUiModel.supersededInstalls(installs, active, pending)
            val visible = RegionUiModel.visibleInstalls(installs, active, pending)
            assertEquals(installs.toSet(), (superseded + visible).toSet())
            assertTrue(superseded.intersect(visible.toSet()).isEmpty())
            assertTrue(superseded.none { it.installId == active || it.installId == pending })
            installs.groupBy { it.regionId }.forEach { (region, copies) ->
                // Every region keeps at least one copy, and the kept copies include the newest one.
                val kept = visible.filter { it.regionId == region }
                assertTrue(kept.isNotEmpty())
                assertTrue(copies.maxWith(InstalledRegion.INSTALL_ORDER) in kept)
                // Nothing is deleted from a region whose newest copy is unnumbered.
                val newest = RegionUiModel.keptCopy(region, installs, pending)
                if (newest == null || !newest.numbered) assertTrue(superseded.none { it.regionId == region })
            }
        }
    }

    @Test
    fun sourceDatesOrderInBothServerFormats() {
        assertEquals("261005", RegionUiModel.sourceDateKey("261005"))
        assertEquals("261005", RegionUiModel.sourceDateKey("2026-10-05"))
        assertTrue(RegionUiModel.sourceDateKey("260815")!! < RegionUiModel.sourceDateKey("2026-10-05")!!)
        assertNull(RegionUiModel.sourceDateKey("261305"))
        assertNull(RegionUiModel.sourceDateKey("latest"))
    }
}
