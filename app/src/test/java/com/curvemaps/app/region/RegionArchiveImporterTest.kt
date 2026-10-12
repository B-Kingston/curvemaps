package com.curvemaps.app.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class RegionArchiveImporterTest {

    private fun buildPackage(mutate: (SyntheticRegionPackage) -> Unit = {}): SyntheticRegionPackage.Built {
        val file = File.createTempFile("region", ".motomap").also { it.delete() }
        val builder = SyntheticRegionPackage(file)
        mutate(builder)
        return builder.build()
    }

    private fun extract(built: SyntheticRegionPackage.Built, freeSpace: (File) -> Long = { Long.MAX_VALUE }): RegionPackageManifest {
        val staging = File.createTempFile("staging", "").also { it.delete() }
        return RegionArchiveImporter.extract(built.file, staging, freeSpace = freeSpace)
    }

    @Test
    fun extractsAValidPackage() {
        val built = buildPackage()
        val staging = File.createTempFile("staging", "").also { it.delete() }
        try {
            val manifest = RegionArchiveImporter.extract(built.file, staging)
            assertEquals("monaco", manifest.regionId)
            assertEquals(
                built.files.getValue(RegionPackageContract.TILES_PATH).size.toLong(),
                File(staging, RegionPackageContract.TILES_PATH).length(),
            )
            assertEquals(
                ZipFile(built.file).use { zip ->
                    zip.getInputStream(zip.getEntry("manifest.json")).use { it.readBytes().decodeToString() }
                },
                File(staging, "manifest.json").readText(),
            )
            // The installed copy is structurally valid.
            RegionArchiveImporter.verifyCheap(staging, manifest)
            RegionArchiveImporter.verifyFull(staging, manifest)
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun rejectsInflatedManifestEvenWhenCentralDirectoryUnderstatesItsSize() {
        val built = buildPackage()
        val bytes = built.file.readBytes()
        // ZIP central directory: signature, 46-byte header, then entry name.
        val header = (0 until bytes.size - 46).first { offset ->
            bytes[offset] == 0x50.toByte() && bytes[offset + 1] == 0x4b.toByte() &&
                bytes[offset + 2] == 0x01.toByte() && bytes[offset + 3] == 0x02.toByte() &&
                bytes.copyOfRange(offset + 46, offset + 59).decodeToString() == "manifest.json"
        }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(header + 24, 1)
        built.file.writeBytes(bytes)
        val staging = File.createTempFile("staging", "").also { it.delete() }
        try {
            RegionArchiveImporter.extract(
                built.file, staging,
                limits = RegionArchiveImporter.Limits(maxManifestBytes = 32),
            )
            fail("inflated manifest accepted")
        } catch (expected: RegionPackageException) {
            assertEquals("The package manifest is unreasonably large", expected.message)
        } finally {
            built.file.delete()
            staging.deleteRecursively()
        }
    }

    @Test
    fun rejectsZipSlipEntries() {
        val built = buildPackage { it.extraEntries += "../../escape.txt" to "x".toByteArray() }
        assertExtractFails(built)
    }

    @Test
    fun rejectsAbsoluteAndBackslashEntries() {
        assertExtractFails(buildPackage { it.extraEntries += "/etc/passwd" to "x".toByteArray() })
        assertExtractFails(buildPackage { it.extraEntries += "tiles\\..\\x" to "x".toByteArray() })
    }

    @Test
    fun rejectsUnexpectedEntries() {
        assertExtractFails(buildPackage { it.extraEntries += "extras/notes.txt" to "x".toByteArray() })
    }

    @Test
    fun rejectsDuplicateEntries() {
        // A case-different duplicate exercises the importer's normalized-name
        // check (Java's ZipOutputStream refuses exact duplicates itself).
        assertExtractFails(buildPackage {
            it.duplicateEntries += "graph/NODES" to "other".toByteArray()
        })
    }

    @Test
    fun rejectsDirectoryEntries() {
        val file = File.createTempFile("region", ".motomap").also { it.delete() }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("graph/"))
            zip.closeEntry()
        }
        try {
            RegionArchiveImporter.extract(file, File.createTempFile("staging", "").also { it.delete() })
            fail("directory entry accepted")
        } catch (expected: RegionPackageException) {
        }
    }

    @Test
    fun rejectsMissingFilesTheManifestLists() {
        val built = buildPackage { builder ->
            // Claim a file that the archive does not contain.
            builder.mutateManifest = { manifest ->
                val graph = manifest.getJSONArray("components").getJSONObject(1)
                val files = graph.getJSONArray("files")
                val extra = org.json.JSONObject()
                    .put("path", "zz-missing-file")
                    .put("bytes", 10L)
                    .put("sha256", "c".repeat(64))
                files.put(extra)
                graph.put("bytes", graph.getLong("bytes") + 10)
            }
        }
        assertExtractFails(built)
    }

    @Test
    fun rejectsHashMismatch() {
        val built = buildPackage { builder ->
            builder.mutateManifest = { manifest ->
                val graph = manifest.getJSONArray("components").getJSONObject(1)
                graph.getJSONArray("files").getJSONObject(0).put("sha256", "d".repeat(64))
            }
        }
        assertExtractFails(built)
    }

    @Test
    fun rejectsLyingSizes() {
        // The manifest claims the geocoder is larger than it is; the streaming
        // extractor must catch the mismatch instead of trusting the header.
        val built = buildPackage { builder ->
            builder.mutateManifest = { manifest ->
                manifest.getJSONArray("components").getJSONObject(2).put("bytes", 172L)
            }
        }
        assertExtractFails(built)
    }

    @Test
    fun rejectsWrongPmtilesVersion() {
        assertExtractFails(buildPackage { it.pmtilesVersion = 2 })
    }

    @Test
    fun rejectsWrongGeocoderContent() {
        assertExtractFails(buildPackage {
            it.overrides[RegionPackageContract.GEOCODER_PATH] = "NOTGEO01\n".toByteArray()
        })
    }

    @Test
    fun rejectsGraphWithoutTheAppProfile() {
        assertExtractFails(buildPackage {
            it.overrides["${RegionPackageContract.GRAPH_PATH}/properties"] =
                "graph.profiles=car|1234\n".toByteArray()
        })
    }

    @Test
    fun rejectsWhenThereIsNotEnoughFreeSpace() {
        val built = buildPackage()
        assertExtractFails(built, freeSpace = { 1L })
    }

    @Test
    fun installedVerificationDetectsDeletionAndCorruption() {
        val built = buildPackage()
        val staging = File.createTempFile("staging", "").also { it.delete() }
        try {
            val manifest = RegionArchiveImporter.extract(built.file, staging)
            File(staging, "${RegionPackageContract.GRAPH_PATH}/nodes").delete()
            try {
                RegionArchiveImporter.verifyCheap(staging, manifest)
                fail("missing file accepted")
            } catch (expected: RegionPackageException) {
            }
            // Restore, then corrupt a file's bytes but keep its size.
            File(staging, "${RegionPackageContract.GRAPH_PATH}/nodes").writeText("nodes-content")
            File(staging, "${RegionPackageContract.GRAPH_PATH}/nodes").writeText("nodes-CONTENT")
            RegionArchiveImporter.verifyCheap(staging, manifest)
            try {
                RegionArchiveImporter.verifyFull(staging, manifest)
                fail("corrupted file accepted by the full check")
            } catch (expected: RegionPackageException) {
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun assertExtractFails(
        built: SyntheticRegionPackage.Built,
        freeSpace: (File) -> Long = { Long.MAX_VALUE },
    ) {
        try {
            extract(built, freeSpace)
            fail("hostile package was accepted")
        } catch (expected: RegionPackageException) {
        }
    }
}
