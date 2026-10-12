package com.curvemaps.app.region

import com.curvemaps.app.ui.theme.CurveMapsTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.curvemaps.app.MapsSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Maps screen decisions that protect the user's ability to retry:
 * a terminal build job keeps Download & install/Retry visible (the stale-job
 * regression), a followed build offers Stop waiting (never a server cancel),
 * the region search builds eligible places and explains blocked ones,
 * every download/import intermediate state offers its next explicit action, and
 * the installed-map delete flow has a glove-sized rubbish bin with an honest
 * confirmation. The screen is rendered directly from synthetic [MapsState]s, so
 * the assertions never depend on a server or a SAF picker.
 */
class MapsSettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var downloadedRegionId: String? = null
    private var installedRegionId: String? = null
    private var removedInstallId: String? = null
    private var stoppedWaiting = false
    private var searched: String? = null
    private var nearMeTapped = false
    private var gotExtract: String? = null
    private var catalogLoads = 0

    private fun show(state: MapsState, rideActive: Boolean = false, nearMeAvailable: Boolean = true) {
        composeRule.setContent {
            CurveMapsTheme {
                MapsSettingsScreen(
                    state = state,
                    rideActive = rideActive,
                    onDismiss = {},
                    onBrowseMaps = { catalogLoads++ },
                    onRequestBuild = {},
                    onCancelBuild = { stoppedWaiting = true },
                    onDownloadPackage = { downloadedRegionId = it },
                    onCancelDownload = {},
                    onResumeDownload = {},
                    onSaveRecoveredPackage = {},
                    onImportRecoveredPackage = {},
                    onImportSavedPackage = {},
                    onImportPackage = {},
                    onActivate = {},
                    onActivateBundled = {},
                    onRemove = { removedInstallId = it },
                    onInstallPackage = { installedRegionId = it },
                    onSearchRegions = { searched = it },
                    onNearMe = if (nearMeAvailable) ({ nearMeTapped = true }) else null,
                    onGetExtract = { gotExtract = it },
                )
            }
        }
    }

    private fun catalogRegion(artifactAvailable: Boolean = true) = ServerRegion(
        id = "queensland",
        name = "Queensland",
        coverage = "Queensland, Australia",
        disabled = false,
        maxZoom = 14,
        sourceDate = "260815",
        sourceSha256 = "a".repeat(64),
        sourcePinned = true,
        artifact = if (artifactAvailable) {
            ServerArtifact(
                available = true,
                fingerprint = "b".repeat(64),
                size = 357_091_613,
                sha256 = "b".repeat(64),
                generatedAt = "2026-08-15T00:00:00Z",
                downloadUrl = "/api/v1/artifacts/queensland/${"b".repeat(64)}/queensland.motomap",
            )
        } else {
            null
        },
        activeJobId = null,
    )

    private fun job(state: String, downloadUrl: String = "/api/v1/artifacts/queensland/${"b".repeat(64)}/queensland.motomap") =
        ServerJob(
            id = "job-1",
            regionId = "queensland",
            regionName = "Queensland",
            state = state,
            step = "import",
            progress = 0.5,
            message = "Importing",
            error = if (state == "failed") "The extract download returned HTML" else "",
            cached = false,
            artifactSize = 357_091_613,
            artifactSha256 = "b".repeat(64),
            downloadUrl = downloadUrl,
        )

    private fun installedRegion(
        installId: String = "monaco-1234567890ab",
        regionId: String = "monaco",
        name: String = "Monaco",
    ) = InstalledRegion(
        installId = installId,
        regionId = regionId,
        regionName = name,
        coverage = "Monaco",
        sourceDate = "260928",
        sourceSha256 = "a".repeat(64),
        pipelineFingerprint = "b".repeat(64),
        installedAtEpochMs = 1L,
        bytes = 12 * 1024 * 1024,
        dirName = installId,
        installSequence = 1L,
    )

    private fun region(id: String, name: String, coverage: String) =
        catalogRegion().copy(id = id, name = name, coverage = coverage)

    private val worldCatalog = listOf(
        region("queensland", "Queensland", "Australia and Oceania › Australia › Queensland"),
        region("victoria", "Victoria", "Australia and Oceania › Australia › Victoria"),
        region("new-zealand", "New Zealand", "Australia and Oceania › New Zealand"),
        region("wales", "Wales", "Europe › United Kingdom › Wales"),
        region("scotland", "Scotland", "Europe › United Kingdom › Scotland"),
        region("switzerland", "Switzerland", "Europe › Switzerland"),
        region("austria", "Austria", "Europe › Austria"),
        region("us-utah", "Utah", "North America › Utah"),
        region("socal", "Southern California", "North America › California › Southern California"),
    )

    @Test
    fun theCatalogLoadsOnlyWhenAskedFromTheFixedService() {
        // What RegionManager publishes before the first Browse: the build's service, unchecked.
        show(MapsState(serverAddress = "maps.curvemaps.app", defaultServer = "maps.curvemaps.app", usingDefaultServer = true))
        composeRule.onNodeWithContentDescription("Map service CurveMaps map service").assertExists()
        composeRule.onNodeWithContentDescription("Use a custom server").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Map server address").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Refresh maps").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Browse maps").assertIsEnabled().performClick()
        assertEquals(1, catalogLoads)
    }

    @Test
    fun aLongCatalogIsGroupedByContinentAndCollapsedUntilOpened() {
        show(
            MapsState(
                serverChecked = true,
                catalog = worldCatalog,
                installed = listOf(installedRegion().copy(regionId = "queensland")),
            ),
        )
        composeRule.onNodeWithContentDescription("Refresh maps").assertExists()
        // The continent holding an installed region starts open; the rest are collapsed.
        composeRule.onNodeWithContentDescription("Australia and Oceania, 3 regions").assertExists()
        composeRule.onNodeWithContentDescription("Download and install victoria").assertExists()
        composeRule.onNodeWithText("Australia").assertExists()
        composeRule.onNodeWithContentDescription("Download and install wales").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Europe, 4 regions").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Download and install wales").assertExists()
        composeRule.onNodeWithText("United Kingdom").assertExists()
        composeRule.onNodeWithContentDescription("Australia and Oceania, 3 regions").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Download and install victoria").assertDoesNotExist()
    }

    @Test
    fun filteringOpensEveryMatchAndHidesTheRest() {
        show(MapsState(serverChecked = true, catalog = worldCatalog))
        composeRule.onNodeWithContentDescription("Filter regions").performTextInput("cali")
        composeRule.onNodeWithContentDescription("Download and install socal").assertExists()
        composeRule.onNodeWithContentDescription("Europe, 4 regions").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Clear region filter").performClick()
        composeRule.onNodeWithContentDescription("Europe, 4 regions").assertExists()
        composeRule.onNodeWithContentDescription("Filter regions").performTextInput("zzz")
        composeRule.onNodeWithText("No regions match", substring = true).assertExists()
    }

    @Test
    fun aTerminalCompletedJobKeepsInstallAndSaveCopyVisibleAndClickable() {
        show(
            MapsState(
                serverChecked = true,
                catalog = listOf(catalogRegion()),
                activeJob = job("completed"),
            ),
        )
        val install = composeRule.onNodeWithContentDescription("Download and install queensland")
        install.assertExists()
        install.assertHasClickAction()
        install.assertIsEnabled()
        composeRule.onNodeWithText("Build complete — ready to install").assertExists()
        install.performClick()
        assertEquals("queensland", installedRegionId)
        // Save a copy keeps the file-picker path for a user who wants the file.
        val saveCopy = composeRule.onNodeWithContentDescription("Download package queensland")
        saveCopy.assertIsEnabled()
        saveCopy.performClick()
        assertEquals("queensland", downloadedRegionId)
        composeRule.onNodeWithContentDescription("Stop waiting for queensland").assertDoesNotExist()
    }

    @Test
    fun aTerminalFailedJobOffersRetryWithTheServerError() {
        show(
            MapsState(
                serverChecked = true,
                catalog = listOf(catalogRegion(artifactAvailable = false)),
                activeJob = job("failed", downloadUrl = ""),
            ),
        )
        composeRule.onNodeWithContentDescription("Retry package queensland")
            .assertExists()
            .assertIsEnabled()
        composeRule.onNodeWithText("Build failed").assertExists()
        composeRule.onNodeWithText("The extract download returned HTML").assertExists()
        composeRule.onNodeWithContentDescription("Download and install queensland").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Download package queensland").assertDoesNotExist()
    }

    @Test
    fun aFollowedBuildShowsProgressAndStopWaitingOnly() {
        show(
            MapsState(
                serverChecked = true,
                catalog = listOf(catalogRegion()),
                activeJob = job("running"),
            ),
        )
        composeRule.onNodeWithText("It installs itself when it's ready.").assertExists()
        val stop = composeRule.onNodeWithContentDescription("Stop waiting for queensland")
        stop.assertExists()
        stop.performClick()
        assertTrue(stoppedWaiting)
        composeRule.onNodeWithContentDescription("Download and install queensland").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Retry package queensland").assertDoesNotExist()
    }

    @Test
    fun findARegionSearchesAndBuildsAnEligiblePlace() {
        val tasmania = ServerExtract("tasmania", "Tasmania", listOf("Australia and Oceania", "Australia"), "tasmania", 54_000_000, true, "")
        val australia = ServerExtract("australia", "Australia", listOf("Australia and Oceania"), "australia", 966_000_000, false, "Australia is 922 MiB; this server builds extracts up to 768 MiB.")
        show(
            MapsState(
                serverChecked = true,
                generationEnabled = true,
                catalog = listOf(catalogRegion()),
                search = ExtractSearch(query = "tas", searched = true, results = listOf(tasmania, australia)),
            ),
        )
        composeRule.onNodeWithContentDescription("Region search").assertExists()
        composeRule.onNodeWithContentDescription("Get map tasmania").performScrollTo().assertIsEnabled().performClick()
        assertEquals("tasmania", gotExtract)
        composeRule.onNodeWithContentDescription("Get map australia").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("up to 768 MiB", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Regions near me").performScrollTo().performClick()
        assertTrue(nearMeTapped)
        val minimum = 48f * composeRule.density.density
        // Layout size, not on-screen bounds: the screen scrolls, and a control
        // below the fold is still a full-size target (the fuzz oracle's rule).
        listOf("Region search", "Search regions", "Regions near me", "Get map tasmania", "Clear region search").forEach { description ->
            val size = composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().size
            assertTrue("$description ${size.width} x ${size.height} px is smaller than 48 dp", size.width >= minimum && size.height >= minimum)
        }
    }

    @Test
    fun nearMeIsHiddenWithoutLocationAndSearchHidesWithoutGeneration() {
        show(
            MapsState(serverChecked = true, generationEnabled = true, catalog = listOf(catalogRegion())),
            nearMeAvailable = false,
        )
        composeRule.onNodeWithContentDescription("Region search").assertExists()
        composeRule.onNodeWithContentDescription("Regions near me").assertDoesNotExist()
    }

    @Test
    fun aServerThatCannotBuildHidesTheSearch() {
        show(MapsState(serverChecked = true, generationEnabled = false, generationMessage = "Building new regions is temporarily unavailable.", catalog = listOf(catalogRegion())))
        composeRule.onNodeWithContentDescription("Region search").assertDoesNotExist()
        composeRule.onNodeWithText("Building new regions is temporarily unavailable.").assertExists()
        // Ready maps still install.
        composeRule.onNodeWithContentDescription("Download and install queensland").assertIsEnabled()
    }

    @Test
    fun theInstallingPhaseIsShownWithoutCancel() {
        show(
            MapsState(
                download = DownloadProgress("job-1", "Queensland", 357_091_613, 357_091_613, DownloadPhase.INSTALLING),
            ),
        )
        composeRule.onNodeWithText("Installing Queensland…").assertExists()
        composeRule.onNodeWithContentDescription("Cancel download").assertDoesNotExist()
    }

    @Test
    fun aProRegionShowsItsBadge() {
        show(MapsState(serverChecked = true, catalog = listOf(catalogRegion().copy(tier = "pro"))))
        composeRule.onNodeWithText("PRO").assertExists()
    }

    @Test
    fun theInstalledMapRubbishBinIsGloveSizedAndConfirmsBeforeDeleting() {
        show(
            MapsState(
                installed = listOf(installedRegion()),
                activeInstallId = null,
            ),
        )
        val delete = composeRule.onNodeWithContentDescription("Delete Monaco map")
        delete.assertExists()
        delete.assertHasClickAction()
        val minimum = 48f * composeRule.density.density
        val bounds: Rect = delete.fetchSemanticsNode().boundsInRoot
        assertTrue("delete target ${bounds.width} x ${bounds.height}", bounds.width >= minimum && bounds.height >= minimum)
        delete.performClick()
        composeRule.onNodeWithText("Delete installed map?").assertExists()
        composeRule.onNodeWithText("Delete \"Monaco\" (source 260928)?", substring = true).assertExists()
        composeRule.onNodeWithText("This frees 12.0 MiB on this device.", substring = true).assertExists()
        composeRule.onNodeWithText("in Files is not removed", substring = true).assertExists()
        // Cancel keeps the map.
        composeRule.onNodeWithContentDescription("Cancel delete Monaco map").performClick()
        composeRule.onNodeWithText("Delete installed map?").assertDoesNotExist()
        assertEquals(null, removedInstallId)
        // Confirm reports the exact install.
        delete.performClick()
        composeRule.onNodeWithContentDescription("Confirm delete Monaco map").performClick()
        assertEquals("monaco-1234567890ab", removedInstallId)
    }

    /** The service's Monaco package: [fingerprint] "b…b" is the installed copy's. */
    private fun monacoCatalog(fingerprint: String = "d".repeat(64)) = catalogRegion().copy(
        id = "monaco",
        name = "Monaco",
        coverage = "Europe › Monaco",
        sourceDate = "261005",
        artifact = catalogRegion().artifact!!.copy(
            fingerprint = fingerprint,
            size = 25 * 1024 * 1024,
            downloadUrl = "/api/v1/artifacts/monaco/$fingerprint/monaco.motomap",
        ),
    )

    private fun assertGloveSized(description: String) {
        val minimum = 48f * composeRule.density.density
        val bounds: Rect = composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        assertTrue("$description ${bounds.width} x ${bounds.height}", bounds.height >= minimum)
    }

    @Test
    fun aNewerPackageOnTheServiceOffersUpdateOnTheInstalledMap() {
        show(
            MapsState(
                serverAddress = "https://maps.example",
                serverChecked = true,
                catalog = listOf(monacoCatalog()),
                installed = listOf(installedRegion()),
                activeInstallId = "monaco-1234567890ab",
            ),
        )
        val update = composeRule.onNodeWithContentDescription("Update Monaco map")
        update.performScrollTo().assertIsEnabled().assertHasClickAction()
        assertGloveSized("Update Monaco map")
        composeRule.onNodeWithText("Update available · map data 5 Oct 2026 · 25.0 MiB download").assertExists()
        update.performClick()
        assertEquals("monaco", installedRegionId)
        // The catalog row offers the same update, under its own description.
        composeRule.onNodeWithContentDescription("Update map monaco").assertExists()
    }

    @Test
    fun theServicesOwnPackageReadsUpToDateWithoutAnUpdateButton() {
        show(
            MapsState(
                serverAddress = "https://maps.example",
                serverChecked = true,
                catalog = listOf(monacoCatalog(fingerprint = "b".repeat(64))),
                installed = listOf(installedRegion()),
            ),
        )
        composeRule.onNodeWithText("Up to date").performScrollTo().assertExists()
        composeRule.onNodeWithContentDescription("Update Monaco map").assertDoesNotExist()
    }

    @Test
    fun anUpdateIsDisabledWhileAnotherTransferRuns() {
        show(
            MapsState(
                serverAddress = "https://maps.example",
                serverChecked = true,
                catalog = listOf(monacoCatalog()),
                installed = listOf(installedRegion()),
                download = DownloadProgress("job-1", "Monaco", 1_000, 25L * 1024 * 1024),
            ),
        )
        composeRule.onNodeWithContentDescription("Update Monaco map").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun beforeTheCatalogLoadsOneButtonChecksForUpdates() {
        show(
            MapsState(
                serverAddress = "https://maps.example",
                installed = listOf(installedRegion(), installedRegion("tasmania-1234567890ab", "tasmania", "Tasmania")),
            ),
        )
        composeRule.onNodeWithContentDescription("Update Monaco map").assertDoesNotExist()
        val check = composeRule.onNodeWithContentDescription("Check for map updates")
        check.performScrollTo().assertIsEnabled()
        assertGloveSized("Check for map updates")
        check.performClick()
        assertEquals(1, catalogLoads)
    }

    @Test
    fun aReplacedCopyWaitingToBeDeletedIsNotListed() {
        val old = installedRegion()
        val new = installedRegion("monaco-dddddddddddd").copy(
            pipelineFingerprint = "d".repeat(64),
            installedAtEpochMs = 2L,
            installSequence = 2L,
        )
        show(
            MapsState(
                installed = listOf(new, old),
                activeInstallId = new.installId,
            ),
        )
        composeRule.onAllNodes(hasContentDescription("Delete Monaco map")).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Activate region monaco").assertDoesNotExist()
    }

    @Test
    fun aReplacedCopyThatFailedToDeleteIsListedForTheRiderToDelete() {
        val old = installedRegion()
        val new = installedRegion("monaco-dddddddddddd").copy(
            pipelineFingerprint = "d".repeat(64),
            installSequence = 2L,
        )
        show(
            MapsState(
                installed = listOf(new, old),
                activeInstallId = new.installId,
                failedRemovals = setOf(old.installId),
            ),
        )
        composeRule.onAllNodes(hasContentDescription("Delete Monaco map")).assertCountEquals(2)
        composeRule.onNodeWithText("A newer copy replaced this one. Delete it to free the space.")
            .performScrollTo()
            .assertExists()
        // Switching back to the replaced copy is not offered; deleting it is.
        composeRule.onNodeWithContentDescription("Activate region monaco").assertDoesNotExist()
    }

    @Test
    fun theActiveMapDeleteExplainsTheBundledSwitchAndIsDisabledMidRide() {
        show(
            MapsState(
                installed = listOf(installedRegion()),
                activeInstallId = "monaco-1234567890ab",
            ),
            rideActive = true,
        )
        composeRule.onNodeWithContentDescription("Delete Monaco map").assertIsNotEnabled()
        composeRule.onNodeWithText("Stop the ride before deleting the active map.").assertExists()
    }

    @Test
    fun theActiveMapDeleteIsAllowedOffRideAndNamesTheSwitch() {
        show(
            MapsState(
                installed = listOf(installedRegion()),
                activeInstallId = "monaco-1234567890ab",
            ),
            rideActive = false,
        )
        composeRule.onNodeWithContentDescription("Delete Monaco map").assertIsEnabled().performClick()
        composeRule.onNodeWithText(
            if (RegionDatasets.bundledAvailable) "switches to the bundled" else "unavailable",
            substring = true,
        ).assertExists()
        composeRule.onNodeWithText("releases the routing graph", substring = true).assertExists()
    }

    @Test
    fun theSavingPhaseCannotBeCancelled() {
        show(
            MapsState(
                download = DownloadProgress(
                    jobId = "job-1",
                    regionName = "Queensland",
                    bytes = 357_091_613,
                    total = 357_091_613,
                    phase = DownloadPhase.SAVING,
                ),
            ),
        )
        composeRule.onNodeWithText("Saving Queensland to your file…").assertExists()
        composeRule.onNodeWithContentDescription("Cancel download").assertDoesNotExist()
    }

    @Test
    fun aSavedPackageNamesTheFileAndOffersImport() {
        show(
            MapsState(
                savedPackage = SavedPackage(
                    regionId = "queensland",
                    regionName = "Queensland",
                    fileName = "queensland-260815.motomap",
                    bytes = 357_091_613,
                    uri = "content://downloads/queensland-260815.motomap",
                ),
            ),
        )
        composeRule.onNodeWithText("queensland-260815.motomap", substring = true).assertExists()
        composeRule.onNodeWithText("stays in your Files", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Import saved file")
            .assertExists()
            .assertHasClickAction()
    }

    @Test
    fun anInterruptedDownloadOffersAResume() {
        show(
            MapsState(
                interrupted = InterruptedDownload(
                    jobId = "job-1",
                    regionId = "queensland",
                    regionName = "Queensland",
                    fingerprint = "b".repeat(64),
                    downloadUrl = "/api/v1/artifacts/queensland/${"b".repeat(64)}/queensland.motomap",
                    etag = null,
                    bytes = 180_000_000,
                    total = 357_091_613,
                ),
            ),
        )
        composeRule.onNodeWithText("171.7 MiB", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Resume download")
            .assertExists()
            .assertHasClickAction()
    }

    @Test
    fun fontScaleTwoKeepsTheDeleteTargetAndRowsOnScreen() {
        val density = composeRule.density
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
            ) {
                CurveMapsTheme {
                    MapsSettingsScreen(
                        state = MapsState(
                            installed = listOf(installedRegion()),
                            activeInstallId = "monaco-1234567890ab",
                        ),
                        rideActive = false,
                        onDismiss = {},
                        onBrowseMaps = {},
                        onRequestBuild = {},
                        onCancelBuild = {},
                        onDownloadPackage = {},
                        onCancelDownload = {},
                        onResumeDownload = {},
                        onSaveRecoveredPackage = {},
                        onImportRecoveredPackage = {},
                        onImportSavedPackage = {},
                        onImportPackage = {},
                        onActivate = {},
                        onActivateBundled = {},
                        onRemove = {},
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("Delete Monaco map").assertExists()
        composeRule.onNodeWithText("source 260928", substring = true).assertExists()
    }
}
