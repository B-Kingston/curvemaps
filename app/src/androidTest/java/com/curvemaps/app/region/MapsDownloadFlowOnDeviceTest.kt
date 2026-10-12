package com.curvemaps.app.region

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device proof of the separated download/import/delete contract:
 * importing a user-picked file leaves that file in place, a recovered
 * app-storage package can be saved to a user-visible file without another
 * download, an interrupted partial keeps its bytes, and deleting the active map
 * switches to the bundled dataset and waits for the routing graph to drain
 * before unlinking anything.
 */
@RunWith(AndroidJUnit4::class)
class MapsDownloadFlowOnDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var manager: RegionManager? = null

    private val registry: RegionRegistry
        get() = RegionRegistry(File(context.filesDir, "regions"))

    private val regionsRoot: File
        get() = File(context.filesDir, "regions")

    @Before
    fun clean() {
        regionsRoot.deleteRecursively()
        // Tests point the debug-only server override at a loopback server;
        // reset it so each test starts on the fixed map service.
        RegionSettings(context).apply {
            serverAddress = ""
            allowInsecureLocal = false
            autoConnect = false
        }
    }

    @After
    fun tearDown() {
        manager?.close()
        manager = null
        regionsRoot.deleteRecursively()
    }

    private fun copyMonacoPackage(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File(context.cacheDir, "monaco-test-${System.nanoTime()}.motomap")
        instrumentation.context.assets.open("regions/monaco.motomap").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun newManager(): RegionManager = RegionManager(context).also { manager = it }

    private suspend fun awaitState(predicate: (MapsState) -> Boolean): MapsState =
        withTimeout(30_000) { (manager ?: newManager()).state.first(predicate) }

    private fun installAndActivateMonaco(): Pair<File, InstalledRegion> {
        val archive = copyMonacoPackage()
        val install = registry.install(archive)
        registry.activate(install.installId)
        return archive to install
    }

    /** The same Monaco package under another pipeline fingerprint: an older (or other) version. */
    private fun withFingerprint(archive: File, fingerprint: String): File {
        val target = File(context.cacheDir, "monaco-${fingerprint.take(12)}-${System.nanoTime()}.motomap")
        java.util.zip.ZipFile(archive).use { zip ->
            java.util.zip.ZipOutputStream(target.outputStream()).use { output ->
                for (entry in zip.entries().asSequence()) {
                    output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    zip.getInputStream(entry).use { input ->
                        if (entry.name == "manifest.json") {
                            val manifest = org.json.JSONObject(input.readBytes().decodeToString())
                            manifest.getJSONObject("generator").put("pipelineFingerprint", fingerprint)
                            output.write(manifest.toString().encodeToByteArray())
                        } else input.copyTo(output)
                    }
                    output.closeEntry()
                }
            }
        }
        return target
    }

    private fun manifestOf(archive: File): org.json.JSONObject = java.util.zip.ZipFile(archive).use { zip ->
        zip.getInputStream(zip.getEntry("manifest.json")).use { org.json.JSONObject(it.readBytes().decodeToString()) }
    }

    /**
     * An older copy of Monaco on the device (fingerprint `0…0`) and a loopback
     * map service whose catalog advertises the real package: an update.
     */
    private fun withMonacoUpdate(activateOld: Boolean, block: (server: LocalPackageServer, old: InstalledRegion, newFingerprint: String) -> Unit) {
        val current = copyMonacoPackage()
        val manifest = manifestOf(current)
        val newFingerprint = manifest.getJSONObject("generator").getString("pipelineFingerprint")
        val oldArchive = withFingerprint(current, "0".repeat(64))
        val old = registry.install(oldArchive)
        oldArchive.delete()
        if (activateOld) registry.activate(old.installId)
        LocalPackageServer(
            current.readBytes(),
            fingerprint = newFingerprint,
            sourceDate = manifest.optJSONObject("source")?.optString("date").orEmpty().ifBlank { "260928" },
        ).use { server ->
            RegionSettings(context).apply {
                serverAddress = "http://127.0.0.1:${server.port}"
                allowInsecureLocal = true
            }
            block(server, old, newFingerprint)
        }
    }

    @Test
    fun anUpdateReplacesTheActiveCopyAndDeletesTheOldOne() {
        withMonacoUpdate(activateOld = true) { _, old, newFingerprint ->
            val manager = newManager()
            manager.checkServer()
            val checked = runBlocking { awaitState { it.catalog.isNotEmpty() && !it.busy } }
            val offer = RegionUiModel.installedUpdate(
                old, checked.installed, checked.serverChecked, checked.catalog, false, false, false,
            )
            assertEquals("the fingerprints differ, so Update is offered", MapUpdateStatus.AVAILABLE, offer.status)
            val generationBefore = registry.active().generation

            manager.downloadAndInstall("monaco")
            val done = runBlocking {
                awaitState { state ->
                    state.download == null && state.installed.size == 1 &&
                        state.installed.single().pipelineFingerprint == newFingerprint
                }
            }
            val updated = done.installed.single()
            assertEquals("the update takes over as the active map", updated.installId, done.activeInstallId)
            assertTrue(registry.active().generation > generationBefore)
            assertFalse("the older copy is deleted", File(registry.installsDir, old.dirName).exists())
            assertTrue(File(registry.installsDir, updated.dirName).isDirectory)
            assertTrue(done.notice!!, done.notice!!.contains("updated and active"))
            assertEquals(
                MapUpdateStatus.UP_TO_DATE,
                RegionUiModel.installedUpdate(updated, done.installed, true, done.catalog, false, false, false).status,
            )
            assertTrue(File(regionsRoot, "downloads").listFiles().isNullOrEmpty())
        }
    }

    @Test
    fun anUpdateWaitsForTheOldCopysReadersBeforeDeletingIt() {
        withMonacoUpdate(activateOld = true) { _, old, newFingerprint ->
            val manager = newManager()
            // The map, router and search still read the old copy.
            val release = manager.retainDatasetReader(old.installId)
            try {
                manager.checkServer()
                runBlocking { awaitState { it.catalog.isNotEmpty() && !it.busy } }
                manager.downloadAndInstall("monaco")
                val switched = runBlocking {
                    awaitState { state ->
                        state.download == null &&
                            state.installed.any { it.pipelineFingerprint == newFingerprint && it.installId == state.activeInstallId }
                    }
                }
                assertTrue("live files are never deleted under a reader", File(registry.installsDir, old.dirName).isDirectory)
                assertEquals(
                    "the waiting copy is not listed",
                    listOf(switched.activeInstallId),
                    RegionUiModel.visibleInstalls(switched.installed, switched.activeInstallId).map { it.installId },
                )
            } finally {
                release()
            }
            runBlocking { awaitState { it.installed.size == 1 } }
            assertFalse(File(registry.installsDir, old.dirName).exists())
        }
    }

    @Test
    fun anInactiveCopyIsUpdatedInPlaceAndStaysInactive() {
        withMonacoUpdate(activateOld = false) { _, old, newFingerprint ->
            val activeBefore = registry.active().installId
            val manager = newManager()
            manager.checkServer()
            runBlocking { awaitState { it.catalog.isNotEmpty() && !it.busy } }
            manager.downloadAndInstall("monaco")
            val done = runBlocking {
                awaitState { state ->
                    state.download == null && state.installed.size == 1 &&
                        state.installed.single().pipelineFingerprint == newFingerprint
                }
            }
            assertEquals("an update does not switch maps", activeBefore, done.activeInstallId)
            assertNull(done.pendingActivation)
            assertFalse(File(registry.installsDir, old.dirName).exists())
            assertTrue(done.notice!!, done.notice!!.contains("updated"))
        }
    }

    @Test
    fun anUpdateDuringARideSwitchesAndDeletesTheOldCopyWhenTheRideEnds() {
        withMonacoUpdate(activateOld = true) { _, old, newFingerprint ->
            val manager = newManager()
            manager.checkServer()
            runBlocking { awaitState { it.catalog.isNotEmpty() && !it.busy } }
            manager.setRideActive(true)
            manager.downloadAndInstall("monaco")
            val waiting = runBlocking { awaitState { it.pendingActivation != null && it.download == null } }
            assertEquals("nothing switches mid-ride", old.installId, waiting.activeInstallId)
            assertTrue("the ride's map stays on disk", File(registry.installsDir, old.dirName).isDirectory)
            assertEquals(2, waiting.installed.size)
            assertTrue(waiting.notice!!, waiting.notice!!.contains("when this ride ends"))

            manager.setRideActive(false)
            val done = runBlocking {
                awaitState { state -> state.installed.size == 1 && state.activeInstallId == waiting.pendingActivation }
            }
            assertEquals(newFingerprint, done.installed.single().pipelineFingerprint)
            assertFalse(File(registry.installsDir, old.dirName).exists())
        }
    }

    @Test
    fun aReplacedCopyLeftBehindIsDeletedOnTheNextStart() {
        // An update installed, then the app stopped before the older copy went.
        val current = copyMonacoPackage()
        val oldArchive = withFingerprint(current, "0".repeat(64))
        val old = registry.install(oldArchive)
        val updated = registry.install(current)
        oldArchive.delete()
        val state = runBlocking { awaitState { it.installed.size == 1 } }
        assertEquals(updated.installId, state.installed.single().installId)
        assertFalse(File(registry.installsDir, old.dirName).exists())
    }

    @Test
    fun copiesFromBeforeOneCopyPerRegionAreNotDeletedOnStart() {
        val current = copyMonacoPackage()
        val oldArchive = withFingerprint(current, "0".repeat(64))
        registry.install(oldArchive)
        registry.install(current)
        oldArchive.delete()
        // The registry as the older app wrote it: no install numbers.
        val file = File(regionsRoot, "installs.json")
        val doc = org.json.JSONObject(file.readText())
        val installs = doc.getJSONArray("installs")
        for (i in 0 until installs.length()) installs.getJSONObject(i).remove("installSequence")
        file.writeText(doc.toString())
        newManager()
        runBlocking { awaitState { it.installed.size == 2 } }
        // Time for a wrongful start-up deletion to have happened.
        Thread.sleep(1_500)
        assertEquals("an app update never deletes a kept map by itself", 2, registry.list().size)
    }

    @Test
    fun downloadingARegionWhoseOnlyCopyIsBeingDeletedSwitchesTheNewCopyOn() {
        withMonacoUpdate(activateOld = true) { _, old, newFingerprint ->
            val manager = newManager()
            // The old copy is still read, so its deletion waits.
            val release = manager.retainDatasetReader(old.installId)
            try {
                manager.requestRemove(old.installId)
                runBlocking { awaitState { it.pendingRemoval?.installId == old.installId && it.activeInstallId == null } }
                manager.checkServer()
                runBlocking { awaitState { it.catalog.isNotEmpty() && !it.busy } }
                manager.downloadAndInstall("monaco")
                val done = runBlocking {
                    awaitState { state ->
                        state.download == null &&
                            state.installed.any { it.pipelineFingerprint == newFingerprint && it.installId == state.activeInstallId }
                    }
                }
                assertTrue(
                    "a copy being deleted is not one the download replaces: ${done.notice}",
                    done.notice!!.contains("installed and active"),
                )
            } finally {
                release()
            }
            runBlocking { awaitState { it.installed.size == 1 && it.pendingRemoval == null } }
            assertFalse(File(registry.installsDir, old.dirName).exists())
        }
    }

    @Test
    fun loadingTheCopyBeingDeletedCancelsItsDeletion() {
        val (archive, install) = installAndActivateMonaco()
        val manager = RegionManager(context, releaseTimeoutMs = 1_500).also { this.manager = it }
        val release = manager.retainDatasetReader(install.installId)
        try {
            manager.requestRemove(install.installId)
            runBlocking { awaitState { it.pendingRemoval?.installId == install.installId } }
            runBlocking { manager.loadPackageAndActivate(Uri.fromFile(archive)) }
            val state = manager.state.value
            assertEquals(install.installId, state.activeInstallId)
            assertNull("re-installing it cancels the deletion", state.pendingRemoval)
            assertNull(RegionPendingRemovalStore(File(regionsRoot, "pending-removal.json")).read())
            // Past the release timeout: no false "still in use" error and nothing deleted.
            Thread.sleep(2_500)
            assertNull(manager.state.value.error)
            assertTrue(File(registry.installsDir, install.dirName).isDirectory)
            assertEquals(install.installId, registry.active().installId)
        } finally {
            release()
        }
    }

    @Test
    fun anUpdateWaitingForTheRideToEndSurvivesAnAppRestart() {
        withMonacoUpdate(activateOld = true) { _, old, newFingerprint ->
            val first = newManager()
            first.checkServer()
            runBlocking { awaitState { it.catalog.isNotEmpty() && !it.busy } }
            first.setRideActive(true)
            first.downloadAndInstall("monaco")
            val waiting = runBlocking { awaitState { it.pendingActivation != null && it.download == null } }
            assertEquals(old.installId, waiting.activeInstallId)
            // The app dies mid-ride; the next start switches to the update.
            first.close()
            newManager()
            val done = runBlocking {
                awaitState { state -> state.installed.size == 1 && state.activeInstallId == waiting.pendingActivation }
            }
            assertEquals(newFingerprint, done.installed.single().pipelineFingerprint)
            assertNull(done.pendingActivation)
            assertFalse(File(registry.installsDir, old.dirName).exists())
        }
    }

    @Test
    fun importingANewerFileReplacesTheActiveCopy() {
        val current = copyMonacoPackage()
        val oldArchive = withFingerprint(current, "0".repeat(64))
        val old = registry.install(oldArchive)
        registry.activate(old.installId)
        oldArchive.delete()
        val manager = newManager()
        manager.importPackage(Uri.fromFile(current))
        val done = runBlocking {
            awaitState { state -> !state.busy && state.installed.size == 1 && state.installed.single().installId != old.installId }
        }
        assertEquals("replacing the active copy keeps a map active", done.installed.single().installId, done.activeInstallId)
        assertFalse(File(registry.installsDir, old.dirName).exists())
        assertTrue("the user's picked file must survive the import", current.isFile)
    }

    @Test
    fun importPackageLeavesTheUsersSourceFileInPlace() {
        val source = copyMonacoPackage()
        val original = source.readBytes()
        val manager = newManager()
        manager.importPackage(Uri.fromFile(source))
        val state = runBlocking {
            withTimeout(30_000) { manager.state.first { it.installed.isNotEmpty() && !it.busy } }
        }
        assertEquals("monaco", state.installed.single().regionId)
        assertTrue("the user's picked file must survive the import", source.isFile)
        assertEquals(original.size, source.readBytes().size)
        assertTrue(state.notice!!.contains("left where it was"))
    }

    @Test
    fun savingARecoveredPackageCopiesItToTheChosenFileWithoutDownloadingAgain() {
        val content = copyMonacoPackage().readBytes()
        val downloads = File(regionsRoot, "downloads").apply { mkdirs() }
        val staged = File(downloads, "monaco-staged.motomap")
        staged.writeBytes(content)
        RegionDownloadStore(File(regionsRoot, "download.json"), downloads).save(
            RegionDownloadRecord(
                jobId = "job-1",
                regionId = "monaco",
                regionName = "Monaco",
                fingerprint = "b".repeat(64),
                downloadUrl = "/api/v1/artifacts/monaco/${"b".repeat(64)}/monaco.motomap",
                etag = "\"v1\"",
                bytes = content.size.toLong(),
                total = content.size.toLong(),
                fileName = "monaco-staged.motomap",
                destinationUri = null,
            ),
        )
        val manager = newManager()
        val recovered = runBlocking { awaitState { it.recoveredPackage != null } }
        assertEquals(content.size.toLong(), recovered.recoveredPackage!!.bytes)

        val destination = File(context.cacheDir, "saved-${System.nanoTime()}.motomap")
        manager.saveRecoveredPackage(Uri.fromFile(destination))
        val saved = runBlocking { awaitState { it.savedPackage != null } }
        assertEquals(content.size.toLong(), saved.savedPackage!!.bytes)
        assertTrue(destination.isFile)
        assertEquals(content.size, destination.readBytes().size)
        assertFalse("the staged copy is app-managed and cleaned after a verified save", staged.isFile)
        assertEquals(null, saved.recoveredPackage)
    }

    @Test
    fun anInterruptedPartialIsOfferedForResumeAndItsBytesSurvive() {
        val downloads = File(regionsRoot, "downloads").apply { mkdirs() }
        val part = File(downloads, "monaco-staged.motomap.part")
        part.writeBytes(ByteArray(4096) { 7 })
        RegionDownloadStore(File(regionsRoot, "download.json"), downloads).save(
            RegionDownloadRecord(
                jobId = "job-1",
                regionId = "monaco",
                regionName = "Monaco",
                fingerprint = "b".repeat(64),
                downloadUrl = "/api/v1/artifacts/monaco/${"b".repeat(64)}/monaco.motomap",
                etag = "\"v1\"",
                bytes = 4096,
                total = 1_000_000,
                fileName = "monaco-staged.motomap",
                destinationUri = null,
            ),
        )
        LocalPackageServer(ByteArray(0), artifactsGone = true).use { server ->
            RegionSettings(context).apply {
                serverAddress = "http://127.0.0.1:${server.port}"
                allowInsecureLocal = true
            }
            val manager = newManager()
            val state = runBlocking { awaitState { it.interrupted != null } }
            assertEquals(4096L, state.interrupted!!.bytes)
            assertTrue("the partial bytes must survive", part.isFile)
            // A server that no longer has the package fails the resume
            // honestly and keeps the partial for a retry.
            manager.resumeDownload(Uri.fromFile(File(context.cacheDir, "unused.motomap")))
            val failed = runBlocking { awaitState { it.error != null } }
            assertTrue(part.isFile)
            assertNotNull(failed.interrupted)
        }
    }

    @Test
    fun aRealDownloadStagesPrivatelyAndSavesTheCompleteFile() {
        val content = copyMonacoPackage().readBytes()
        LocalPackageServer(content).use { server ->
            RegionSettings(context).apply {
                serverAddress = "http://127.0.0.1:${server.port}"
                allowInsecureLocal = true
            }
            val manager = newManager()
            manager.checkServer()
            val ready = runBlocking { awaitState { it.catalog.isNotEmpty() && !it.busy } }
            assertTrue(ready.catalog.single().hasCachedArtifact)
            val destination = File(context.cacheDir, "downloaded-${System.nanoTime()}.motomap")
            manager.startDownload("monaco", Uri.fromFile(destination))
            val saved = runBlocking { awaitState { it.savedPackage != null } }
            assertEquals(content.size.toLong(), saved.savedPackage!!.bytes)
            assertTrue(content.contentEquals(destination.readBytes()))
            assertTrue(
                "private staging must be cleaned after a verified save",
                File(regionsRoot, "downloads").listFiles().isNullOrEmpty(),
            )
            assertFalse(File(regionsRoot, "download.json").exists())
            destination.delete()
        }
    }

    @Test
    fun oneTapDownloadInstallsAndActivatesThePackage() {
        val content = copyMonacoPackage().readBytes()
        LocalPackageServer(content).use { server ->
            RegionSettings(context).apply {
                serverAddress = "http://127.0.0.1:${server.port}"
                allowInsecureLocal = true
            }
            val manager = newManager()
            manager.checkServer()
            runBlocking { awaitState { it.catalog.isNotEmpty() && !it.busy } }
            manager.downloadAndInstall("monaco")
            val done = runBlocking {
                awaitState { it.download == null && it.activeInstallId != null && it.installed.isNotEmpty() }
            }
            val install = done.installed.single()
            assertEquals("monaco", install.regionId)
            assertEquals(install.installId, done.activeInstallId)
            assertTrue(done.notice!!.contains("installed and active"))
            assertTrue(
                "private staging must be cleaned after the install",
                File(regionsRoot, "downloads").listFiles().isNullOrEmpty(),
            )
            assertFalse(File(regionsRoot, "download.json").exists())
            assertTrue("a connected server is refreshed when Maps opens next time", RegionSettings(context).autoConnect)
        }
    }

    @Test
    fun aOneTapInstallDuringARideActivatesWhenTheRideEnds() {
        val content = copyMonacoPackage().readBytes()
        LocalPackageServer(content).use { server ->
            RegionSettings(context).apply {
                serverAddress = "http://127.0.0.1:${server.port}"
                allowInsecureLocal = true
            }
            val manager = newManager()
            manager.checkServer()
            runBlocking { awaitState { it.catalog.isNotEmpty() && !it.busy } }
            manager.setRideActive(true)
            manager.downloadAndInstall("monaco")
            val waiting = runBlocking { awaitState { it.pendingActivation != null && it.download == null } }
            assertNull("nothing switches mid-ride", waiting.activeInstallId)
            assertTrue(waiting.notice!!.contains("when this ride ends"))
            manager.setRideActive(false)
            val active = runBlocking { awaitState { it.activeInstallId == waiting.pendingActivation } }
            assertNull(active.pendingActivation)
        }
    }

    @Test
    fun aDamagedOneTapDownloadIsDiscardedAndNeverInstalled() {
        val content = copyMonacoPackage().readBytes()
        LocalPackageServer(content, advertisedSha256 = "0".repeat(64)).use { server ->
            RegionSettings(context).apply {
                serverAddress = "http://127.0.0.1:${server.port}"
                allowInsecureLocal = true
            }
            val manager = newManager()
            manager.checkServer()
            runBlocking { awaitState { it.catalog.isNotEmpty() && !it.busy } }
            manager.downloadAndInstall("monaco")
            val failed = runBlocking { awaitState { it.error != null && it.download == null } }
            assertTrue(failed.error!!.contains("checksum"))
            assertTrue(failed.installed.isEmpty())
            assertNull(failed.activeInstallId)
        }
    }

    @Test
    fun theDefaultServiceIsNotContactedUntilTheUserBrowses() {
        RegionSettings(context).useDefaultServer()
        val manager = newManager()
        manager.onMapsOpened()
        val state = runBlocking { awaitState { it.usingDefaultServer || it.defaultServer.isBlank() } }
        assertFalse("opening Maps on a fresh install must not contact a server", state.serverChecked)
        assertFalse(state.busy)
        assertNull(state.error)
        assertFalse(RegionSettings(context).autoConnect)
    }

    @Test
    fun aPackageThatDoesNotMatchTheAdvertisedDigestIsDiscardedAndNeverSaved() {
        val content = copyMonacoPackage().readBytes()
        LocalPackageServer(content, advertisedSha256 = "d".repeat(64)).use { server ->
            RegionSettings(context).apply {
                serverAddress = "http://127.0.0.1:${server.port}"
                allowInsecureLocal = true
            }
            val manager = newManager()
            manager.checkServer()
            runBlocking { awaitState { it.catalog.isNotEmpty() && !it.busy } }
            val destination = File(context.cacheDir, "corrupt-${System.nanoTime()}.motomap")
            manager.startDownload("monaco", Uri.fromFile(destination))
            val failed = runBlocking { awaitState { it.download == null && it.error != null } }
            assertTrue(failed.error!!, failed.error!!.contains("checksum mismatch"))
            assertEquals(null, failed.savedPackage)
            assertTrue(
                "nothing may reach the user's file",
                !destination.exists() || destination.length() == 0L,
            )
            assertTrue(
                "damaged staged bytes must not be offered for resume",
                File(regionsRoot, "downloads").listFiles().orEmpty().none { it.length() > 0 },
            )
            destination.delete()
        }
    }

    @Test
    fun rapidDownloadReplacementsWaitForCleanupBeforeSavingTheLastSuccessor() {
        val content = copyMonacoPackage().readBytes()
        LocalPackageServer(content, pauseFirstArtifact = true).use { server ->
            RegionSettings(context).apply {
                serverAddress = "http://127.0.0.1:${server.port}"
                allowInsecureLocal = true
            }
            val manager = newManager()
            manager.checkServer()
            runBlocking { awaitState { it.catalog.isNotEmpty() && !it.busy } }
            val first = File(context.cacheDir, "replaced-${System.nanoTime()}.motomap")
            val skipped = File(context.cacheDir, "skipped-${System.nanoTime()}.motomap")
            val second = File(context.cacheDir, "replacement-${System.nanoTime()}.motomap")
            try {
                manager.startDownload("monaco", Uri.fromFile(first))
                assertTrue("first transfer must reach the server", server.firstArtifactStarted.await(10, TimeUnit.SECONDS))
                manager.startDownload("monaco", Uri.fromFile(skipped))
                manager.startDownload("monaco", Uri.fromFile(second))
                server.releaseFirstArtifact.countDown()
                val saved = runBlocking { awaitState { it.savedPackage?.uri == Uri.fromFile(second).toString() } }
                assertEquals(null, saved.error)
                assertEquals(null, saved.download)
                assertTrue(content.contentEquals(second.readBytes()))
                assertTrue(!first.exists() || first.length() == 0L)
                assertFalse(File(regionsRoot, "download.json").exists())
            } finally {
                server.releaseFirstArtifact.countDown()
                first.delete()
                skipped.delete()
                second.delete()
            }
        }
    }

    @Test
    fun failedRestartDeletionKeepsThePendingRecordAndReportsFailure() {
        val (_, install) = installAndActivateMonaco()
        registry.activateBundled()
        val pendingFile = File(regionsRoot, "pending-removal.json")
        RegionPendingRemovalStore(pendingFile).save(
            PendingRemoval(install.installId, install.regionId, install.regionName, install.bytes, install.installSequence),
        )
        val dir = File(registry.installsDir, install.dirName)
        assertTrue(dir.setReadable(false, false))
        assertTrue(dir.setExecutable(false, false))
        try {
            val manager = newManager()
            val failed = runBlocking { awaitState { it.error != null } }
            assertTrue(failed.error!!.contains("Could not delete"))
            assertEquals(null, failed.notice)
            assertTrue("failed recovery must remain retryable after restart", pendingFile.isFile)
            assertTrue(dir.isDirectory)
        } finally {
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
        }
    }

    @Test
    fun removingAnInactiveMapDeletesOnlyTheInstalledCopy() {
        val (archive, install) = installAndActivateMonaco()
        registry.activateBundled()
        val manager = newManager()
        runBlocking { awaitState { it.installed.isNotEmpty() } }
        manager.requestRemove(install.installId)
        val state = runBlocking { awaitState { it.installed.isEmpty() && !it.busy } }
        assertTrue(state.notice!!.contains("deleted from this device"))
        assertTrue(state.notice!!.contains("Files are not touched"))
        assertFalse(File(registry.installsDir, install.dirName).exists())
        assertTrue("the user's downloaded package file is untouched", archive.isFile)
    }

    @Test
    fun removingTheActiveMapSwitchesToBundledAndWaitsForTheGraphDrain() {
        val (_, install) = installAndActivateMonaco()
        val manager = newManager()
        runBlocking { awaitState { it.installed.isNotEmpty() } }
        val release = manager.retainDatasetReader(install.installId)
        manager.requestRemove(install.installId)

        val pending = runBlocking { awaitState { it.pendingRemoval != null && it.activeInstallId == null } }
        assertEquals(install.installId, pending.pendingRemoval!!.installId)
        assertEquals(null, pending.activeInstallId)
        assertTrue(
            "the directory must not be unlinked before the drain signal",
            File(registry.installsDir, install.dirName).isDirectory,
        )
        val dataset = runBlocking { withTimeout(30_000) { manager.dataset.first { it.isBundled } } }
        assertEquals(RegionKind.BUNDLED, dataset.kind)

        manager.retainDatasetReader("unrelated-install")()
        runBlocking { delay(16_000) }
        assertTrue(
            "a timeout or unrelated reader release must never unlink a live graph",
            File(registry.installsDir, install.dirName).isDirectory,
        )

        release()
        val removed = runBlocking { awaitState { it.pendingRemoval == null && it.installed.isEmpty() } }
        assertTrue(removed.notice!!.contains("deleted from this device"))
        assertFalse(File(registry.installsDir, install.dirName).exists())
    }

    @Test
    fun inactiveMapCanBeDeletedWhileAnotherMapWaitsForReaders() {
        val (archive, active) = installAndActivateMonaco()
        val otherArchive = withFingerprint(archive, "f".repeat(64))
        val other = registry.install(otherArchive)
        val current = newManager()
        runBlocking { awaitState { it.installed.size == 2 } }
        val release = current.retainDatasetReader(active.installId)
        try {
            current.requestRemove(active.installId)
            runBlocking { awaitState { it.pendingRemoval != null && it.activeInstallId == null } }
            current.requestRemove(other.installId)
            runBlocking { awaitState { it.installed.none { map -> map.installId == other.installId } } }
            assertEquals(active.installId, current.state.value.pendingRemoval!!.installId)
            assertTrue(File(registry.installsDir, active.dirName).isDirectory)
            assertFalse(File(registry.installsDir, other.dirName).exists())
        } finally {
            release()
            otherArchive.delete()
        }
        runBlocking { awaitState { it.installed.isEmpty() } }
    }

    @Test
    fun managerRecreationCannotDeleteWhileAnOldReaderIsDraining() {
        val (_, install) = installAndActivateMonaco()
        val old = newManager()
        runBlocking { awaitState { it.installed.isNotEmpty() } }
        val release = old.retainDatasetReader(install.installId)
        try {
            old.requestRemove(install.installId)
            runBlocking { awaitState { it.pendingRemoval != null && it.activeInstallId == null } }
            old.close()
            newManager()
            runBlocking { awaitState { it.error?.contains("still in use") == true } }
            assertTrue(File(registry.installsDir, install.dirName).isDirectory)
            release()
            manager!!.requestRemove(install.installId)
            runBlocking { awaitState { it.installed.isEmpty() } }
            assertFalse(File(registry.installsDir, install.dirName).exists())
        } finally { release() }
    }

    @Test
    fun releaseTimeoutKeepsLiveFilesAndAllowsRetry() {
        val (_, install) = installAndActivateMonaco()
        val current = RegionManager(context, releaseTimeoutMs = 100L).also { manager = it }
        runBlocking { awaitState { it.installed.isNotEmpty() } }
        val release = current.retainDatasetReader(install.installId)
        try {
            current.requestRemove(install.installId)
            runBlocking { awaitState { it.error?.contains("still in use") == true } }
            assertTrue(current.state.value.pendingRemoval == null)
            assertTrue(File(registry.installsDir, install.dirName).isDirectory)
            release()
            current.requestRemove(install.installId)
            runBlocking { awaitState { it.installed.isEmpty() } }
        } finally { release() }
    }

    @Test
    fun switchingMapsMidRideIsRefused() {
        val (_, install) = installAndActivateMonaco()
        val manager = newManager()
        runBlocking { awaitState { it.installed.isNotEmpty() } }
        val before = registry.active()
        manager.setRideActive(true)
        manager.activateBundled()
        assertTrue(manager.state.value.error!!.contains("Finish the ride"))
        assertEquals(before, registry.active())
        manager.clearMessages()
        manager.activate(install.installId)
        assertTrue(manager.state.value.error!!.contains("Finish the ride"))
        assertEquals(before, registry.active())
    }

    @Test
    fun removingTheActiveMapMidRideIsRefused() {
        val (_, install) = installAndActivateMonaco()
        val manager = newManager()
        runBlocking { awaitState { it.installed.isNotEmpty() } }
        manager.setRideActive(true)
        manager.requestRemove(install.installId)
        val state = runBlocking { awaitState { it.error != null } }
        assertTrue(state.error!!.contains("Stop the ride"))
        assertTrue(File(registry.installsDir, install.dirName).isDirectory)
        assertEquals(install.installId, registry.active().installId)
        assertTrue(state.pendingRemoval == null)
    }
}

/**
 * A minimal loopback HTTP server for the on-device download test: it serves the
 * health and catalog documents plus the artifact bytes. Loopback-only, test-only
 * (the production network boundary stays in `region/net`).
 */
private class LocalPackageServer(
    private val content: ByteArray,
    private val pauseFirstArtifact: Boolean = false,
    /** Answer every artifact request with 404, as for a package the server no longer has. */
    private val artifactsGone: Boolean = false,
    /** The digest the catalog advertises; defaults to the real one. */
    private val advertisedSha256: String = PackageChecksum.sha256Hex(
        java.io.File.createTempFile("advertised", ".bin").apply {
            writeBytes(content)
            deleteOnExit()
        },
    ),
    /** The package fingerprint the catalog advertises. */
    private val fingerprint: String = "c".repeat(64),
    private val sourceDate: String = "260928",
) : java.io.Closeable {

    val firstArtifactStarted = CountDownLatch(1)
    val releaseFirstArtifact = CountDownLatch(1)
    private var artifactRequests = 0

    private val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
    private val thread = Thread(::serve, "local-package-server").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = server.localPort

    private fun serve() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                return
            }
            socket.use { connection ->
                try {
                    val reader = connection.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return@use
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val path = requestLine.split(' ').getOrNull(1).orEmpty()
                    val (status, body) = when {
                        path.endsWith("/api/v1/health") -> "200 OK" to
                            """{"version":"test","generation":{"enabled":true,"message":""}}""".toByteArray()
                        path.endsWith("/api/v1/catalog") -> "200 OK" to catalogJson()
                        path.contains("/api/v1/artifacts/") && !artifactsGone -> "200 OK" to content
                        else -> "404 Not Found" to ByteArray(0)
                    }
                    val out = connection.getOutputStream()
                    out.write(
                        "HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                            .toByteArray(),
                    )
                    if (pauseFirstArtifact && path.contains("/api/v1/artifacts/") && artifactRequests++ == 0) {
                        val prefix = body.size.coerceAtMost(32 * 1024)
                        out.write(body, 0, prefix)
                        out.flush()
                        firstArtifactStarted.countDown()
                        check(releaseFirstArtifact.await(15, TimeUnit.SECONDS))
                        out.write(body, prefix, body.size - prefix)
                    } else {
                        out.write(body)
                    }
                    out.flush()
                } catch (e: Exception) {
                    // A client that cancels/disconnects mid-request is expected.
                }
            }
        }
    }

    private fun catalogJson(): ByteArray = """
        {"regions":[{"id":"monaco","name":"Monaco","coverage":"Monaco","disabled":false,
        "maxZoom":14,"sourceDate":"$sourceDate","sourceSha256":"${"a".repeat(64)}","sourcePinned":true,
        "artifact":{"available":true,"fingerprint":"$fingerprint","size":${content.size},
        "sha256":"$advertisedSha256","generatedAt":"2026-10-01T00:00:00Z",
        "downloadUrl":"/api/v1/artifacts/monaco/$fingerprint/monaco.motomap"}}]}
    """.trimIndent().encodeToByteArray()

    override fun close() {
        releaseFirstArtifact.countDown()
        runCatching { server.close() }
        runCatching { thread.interrupt() }
    }
}
