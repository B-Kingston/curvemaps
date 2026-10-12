package com.curvemaps.app.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RegionRegistryTest {

    private fun registry(): RegionRegistry = RegionRegistry(File(File.createTempFile("regions", "").also { it.delete() }, "regions"))

    private fun packageFor(regionId: String, fingerprint: String = "b".repeat(64)): File {
        val file = File.createTempFile("region", ".motomap").also { it.delete() }
        val builder = SyntheticRegionPackage(file)
        builder.regionId = regionId
        builder.regionName = regionId.replaceFirstChar { it.uppercase() }
        builder.packageId = "$regionId-260928-0123456789ab"
        builder.pipelineFingerprint = fingerprint
        return builder.build().file
    }

    @Test
    fun installsAndListsRegions() {
        val registry = registry()
        val install = registry.install(packageFor("monaco"))
        assertEquals("monaco", install.regionId)
        assertEquals("Monaco", install.regionName)
        assertEquals(1, registry.list().size)
        assertTrue(File(registry.installsDir, install.dirName).isDirectory)
        // Registry survives a fresh instance (persisted JSON).
        val reopened = RegionRegistry(registry.installsDir.parentFile!!)
        assertEquals(1, reopened.list().size)
        assertEquals(install.installId, reopened.list().first().installId)
    }

    @Test
    fun concurrentInstallsAndActivationsKeepEveryRecordAndGeneration() {
        val registry = registry()
        val packages = listOf(packageFor("monaco"), packageFor("tasmania", "c".repeat(64)))
        val workers = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val installs = packages.map { archive ->
                workers.submit<InstalledRegion> {
                    start.await()
                    RegionRegistry(registry.installsDir.parentFile!!).install(archive)
                }
            }
            start.countDown()
            val records = installs.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(2, registry.list().size)
            val generations = records.map { install ->
                workers.submit<Int> { RegionRegistry(registry.installsDir.parentFile!!).activate(install.installId) }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(setOf(1, 2), generations.toSet())
            assertEquals(2, registry.active().generation)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun installingTheSamePackageTwiceIsIdempotent() {
        val registry = registry()
        val first = registry.install(packageFor("monaco"))
        val second = registry.install(packageFor("monaco"))
        assertEquals(first.installId, second.installId)
        assertEquals(1, registry.list().size)
    }

    @Test
    fun reinstallingAPackageMakesItTheNewestCopyOfItsRegion() {
        val registry = registry()
        val first = registry.install(packageFor("monaco"))
        val other = registry.install(packageFor("monaco", "d".repeat(64)))
        val again = registry.install(packageFor("monaco"))
        assertEquals(first.installId, again.installId)
        assertTrue(again.installSequence > other.installSequence)
        assertEquals(listOf(again.installId, other.installId), registry.list().map { it.installId })
        assertEquals(again.installSequence, registry.find(again.installId)!!.installSequence)
    }

    @Test
    fun installOrderIsANumberNotThePhonesClock() {
        val registry = registry()
        val first = registry.install(packageFor("monaco"))
        // The phone's clock was ahead at the first install, then corrected.
        val root = registry.installsDir.parentFile!!
        val doc = org.json.JSONObject(File(root, "installs.json").readText())
        doc.getJSONArray("installs").getJSONObject(0).put("installedAtEpochMs", Long.MAX_VALUE / 2)
        File(root, "installs.json").writeText(doc.toString())
        val second = registry.install(packageFor("monaco", "d".repeat(64)))
        assertEquals(1L, first.installSequence)
        assertEquals(2L, second.installSequence)
        assertEquals(second.installId, registry.list().first().installId)
        assertEquals(second.installId, RegionUiModel.keptCopy("monaco", registry.list(withSizes = false))!!.installId)
    }

    @Test
    fun copiesFromBeforeInstallNumbersAreOlderThanAnyNumberedCopy() {
        val registry = registry()
        val legacy = registry.install(packageFor("monaco"))
        val root = registry.installsDir.parentFile!!
        val doc = org.json.JSONObject(File(root, "installs.json").readText())
        doc.getJSONArray("installs").getJSONObject(0).remove("installSequence")
        File(root, "installs.json").writeText(doc.toString())
        assertEquals(0L, registry.list().single().installSequence)
        assertFalse(registry.list().single().numbered)
        val updated = registry.install(packageFor("monaco", "d".repeat(64)))
        assertEquals(1L, updated.installSequence)
        assertEquals(listOf(updated.installId, legacy.installId), registry.list().map { it.installId })
    }

    @Test
    fun aDeletionDecidedBeforeAReinstallKeepsTheFreshInstall() {
        val registry = registry()
        val decided = registry.install(packageFor("monaco"))
        // The rider installs the same package again before the deletion runs.
        registry.install(packageFor("monaco"))
        assertFalse(registry.removeIfUnchanged(decided.installId, decided.installSequence))
        assertTrue(File(registry.installsDir, decided.dirName).isDirectory)
        assertEquals(1, registry.list().size)

        val current = registry.list().single()
        assertTrue(registry.removeIfUnchanged(current.installId, current.installSequence))
        assertTrue(registry.list().isEmpty())
        assertFalse(File(registry.installsDir, decided.dirName).exists())
    }

    @Test
    fun theActiveCopyIsNeverRemovedEvenWhenUnchanged() {
        val registry = registry()
        val install = registry.install(packageFor("monaco"))
        registry.activate(install.installId)
        assertFalse(registry.removeIfUnchanged(install.installId, install.installSequence))
        assertTrue(File(registry.installsDir, install.dirName).isDirectory)
    }

    @Test
    fun aFingerprintPrefixCollisionNeverDeletesTheInstalledCopy() {
        val registry = registry()
        val first = registry.install(packageFor("monaco", "0123456789ab" + "c".repeat(52)))
        registry.activate(first.installId)
        try {
            registry.install(packageFor("monaco", "0123456789ab" + "d".repeat(52)))
            fail("a package sharing the directory replaced the live copy")
        } catch (expected: RegionPackageException) {
        }
        assertTrue(File(registry.installsDir, first.dirName).isDirectory)
        assertEquals(first.pipelineFingerprint, registry.list().single().pipelineFingerprint)
    }

    @Test
    fun listingWithoutSizesSkipsTheDirectoryWalk() {
        val registry = registry()
        val install = registry.install(packageFor("monaco"))
        File(registry.installsDir, "${install.dirName}/extra.bin").writeBytes(ByteArray(4096))
        assertEquals(install.bytes, registry.list(withSizes = false).single().bytes)
        assertEquals(install.bytes + 4096, registry.list().single().bytes)
    }

    @Test
    fun installingAFailedPackageLeavesNoStagingDirectory() {
        val registry = registry()
        val file = File.createTempFile("broken", ".motomap").also { it.delete() }
        file.writeBytes("not a zip".toByteArray())
        try {
            registry.install(file)
            fail("broken package installed")
        } catch (expected: RegionPackageException) {
        }
        val leftovers = (registry.installsDir.parentFile?.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("staging-") }
        assertTrue("staging left behind: $leftovers", leftovers.isEmpty())
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun activationBumpsTheGenerationAndSwitchesTheActiveRecord() {
        val registry = registry()
        val install = registry.install(packageFor("monaco"))
        assertEquals(0, registry.active().generation)
        assertTrue(registry.active().isBundled)
        val generation = registry.activate(install.installId)
        assertEquals(1, generation)
        assertEquals(install.installId, registry.active().installId)
        val bundled = registry.activateBundled()
        assertEquals(2, bundled)
        assertNull(registry.active().installId)
        assertTrue(registry.active().generation > generation)
    }

    @Test
    fun activatingAnUnknownRegionFails() {
        val registry = registry()
        try {
            registry.activate("nope-1234567890ab")
            fail("unknown install activated")
        } catch (expected: RegionPackageException) {
        }
    }

    @Test
    fun removingAnInactiveRegionDeletesItAndKeepsOthers() {
        val registry = registry()
        val monaco = registry.install(packageFor("monaco"))
        val tasmania = registry.install(packageFor("tasmania", fingerprint = "c".repeat(64)))
        registry.remove(tasmania.installId)
        assertEquals(listOf(monaco.installId), registry.list().map { it.installId })
        assertFalse(File(registry.installsDir, tasmania.dirName).exists())
    }

    @Test
    fun removingTheActiveRegionIsRefused() {
        val registry = registry()
        val install = registry.install(packageFor("monaco"))
        registry.activate(install.installId)
        try {
            registry.remove(install.installId)
            fail("active region removed")
        } catch (expected: RegionPackageException) {
        }
        assertEquals(1, registry.list().size)
    }

    @Test
    fun corruptRegistryFallsBackToEmptyInsteadOfCrashing() {
        val registry = registry()
        registry.install(packageFor("monaco"))
        File(registry.installsDir.parentFile, "installs.json").writeText("{ not json")
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun installedBytesAreTheActualFootprintIncludingTheManifest() {
        val registry = registry()
        val install = registry.install(packageFor("monaco"))
        val dir = File(registry.installsDir, install.dirName)
        val actual = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertEquals(actual, install.bytes)
        assertTrue(File(dir, "manifest.json").isFile)
        // The manifest itself is part of the installed footprint; the
        // component sum alone would understate what deletion reclaims.
        val componentBytes = RegionArchiveImporter.readManifest(dir).components.sumOf { it.bytes }
        assertTrue("manifest must count toward installed bytes", install.bytes > componentBytes)
    }

    @Test
    fun listReportsBytesFromDiskEvenWhenTheStoredCountIsStale() {
        val registry = registry()
        val install = registry.install(packageFor("monaco"))
        val registryFile = File(registry.installsDir.parentFile, "installs.json")
        val doc = org.json.JSONObject(registryFile.readText())
        doc.getJSONArray("installs").getJSONObject(0).put("bytes", 1)
        registryFile.writeText(doc.toString())
        val listed = registry.list().single()
        val dir = File(registry.installsDir, install.dirName)
        assertEquals(dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }, listed.bytes)
    }

    @Test
    fun removingOneInstallDeletesOnlyItsOwnDirectory() {
        val registry = registry()
        val monaco = registry.install(packageFor("monaco"))
        val tasmania = registry.install(packageFor("tasmania", fingerprint = "c".repeat(64)))
        val monacoDir = File(registry.installsDir, monaco.dirName)
        val tasmaniaDir = File(registry.installsDir, tasmania.dirName)
        registry.remove(tasmania.installId)
        assertTrue(monacoDir.isDirectory)
        assertFalse(tasmaniaDir.exists())
    }
}
