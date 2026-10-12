package com.curvemaps.app.region

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** One installed package version on disk. */
data class InstalledRegion(
    val installId: String,
    val regionId: String,
    val regionName: String,
    val coverage: String?,
    val sourceDate: String,
    val sourceSha256: String,
    val pipelineFingerprint: String,
    val installedAtEpochMs: Long,
    val bytes: Long,
    val dirName: String,
    /**
     * Local install order: every install or reinstall takes the next number,
     * so which copy is newest never depends on the phone's clock. 0 marks a
     * copy installed before the app kept one copy per region; it is older than
     * any numbered copy.
     */
    val installSequence: Long = 0L,
) {
    /** Installed by the app that keeps one copy per region (see [installSequence]). */
    val numbered: Boolean get() = installSequence > 0

    companion object {
        /** Oldest first: install number, then (for unnumbered copies only) install time. */
        val INSTALL_ORDER: Comparator<InstalledRegion> =
            compareBy<InstalledRegion>({ it.installSequence }, { it.installedAtEpochMs }, { it.installId })
    }
}

/** The active-region pointer, with a generation that changes on every switch. */
data class ActiveRegionRecord(
    val installId: String?,
    val generation: Int,
) {
    val isBundled: Boolean get() = installId == null
}

/**
 * Owns the on-device region store under `{filesDir}/regions`:
 *
 * ```
 * regions/
 *   installs.json          registry of installed packages
 *   active.json            { installId, generation }
 *   installs/<id>/         immutable extracted package content
 *   staging-<uuid>/        in-progress extraction (deleted on failure)
 * ```
 *
 * Installation is transactional: the archive is fully extracted and verified
 * into a staging directory, fsynced, then renamed into place before the
 * registry records it. Activation only rewrites the small `active.json` record
 * (temp + rename), so live memory-mapped graph files are never overwritten.
 * Removal refuses to delete the active install.
 */
class RegionRegistry(private val root: File) {

    // All registries for this store share the same publication lock.
    private val storeLock = locks.computeIfAbsent(root.canonicalPath) { Any() }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }

    val installsDir: File get() = File(root, "installs")
    private val registryFile: File get() = File(root, "installs.json")
    private val activeFile: File get() = File(root, "active.json")

    /**
     * Installed regions, newest first. [withSizes] measures each install's real
     * on-disk footprint (a walk over every file); callers that only decide
     * which copy is which pass false and get the stored size hint.
     */
    fun list(withSizes: Boolean = true): List<InstalledRegion> = synchronized(storeLock) {
        if (!registryFile.isFile) return emptyList()
        return try {
            val doc = JSONObject(registryFile.readText())
            val array = doc.optJSONArray("installs") ?: JSONArray()
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::parseInstall)
            }.filter { File(installsDir, it.dirName).isDirectory }
                // The stored byte count is only a hint: the displayed size must
                // be the real installed footprint (tiles + graph + geocoder +
                // manifest), never the compressed download size.
                .map { install ->
                    if (withSizes) install.copy(bytes = installedBytes(File(installsDir, install.dirName))) else install
                }
                .sortedWith(InstalledRegion.INSTALL_ORDER.reversed())
        } catch (e: Exception) {
            // A corrupt registry must not brick the app: the bundled region
            // stays available and the user can reinstall.
            emptyList()
        }
    }

    fun find(installId: String, withSizes: Boolean = false): InstalledRegion? = synchronized(storeLock) {
        list(withSizes).firstOrNull { it.installId == installId }
    }

    /** The current active pointer (generation 0, bundled, when absent). */
    fun active(): ActiveRegionRecord = synchronized(storeLock) {
        if (!activeFile.isFile) return ActiveRegionRecord(null, 0)
        return try {
            val doc = JSONObject(activeFile.readText())
            ActiveRegionRecord(
                installId = doc.optString("installId").takeIf { it.isNotBlank() },
                generation = doc.optInt("generation", 0),
            )
        } catch (e: Exception) {
            ActiveRegionRecord(null, 0)
        }
    }

    /** Resolves the active dataset, falling back to the bundled Queensland data. */
    fun activeDataset(context: android.content.Context): RegionDataset = synchronized(storeLock) {
        val record = active()
        val install = record.installId?.let(::find)
        if (install == null) {
            return RegionDatasets.bundled(context.applicationContext, record.generation)
        }
        return try {
            val manifest = RegionArchiveImporter.readManifest(installsDir.resolve(install.dirName))
            RegionArchiveImporter.verifyCheap(installsDir.resolve(install.dirName), manifest)
            RegionDatasets.installed(context.applicationContext, install, record.generation)
        } catch (e: Exception) {
            // The active install is missing or corrupt: fall back honestly to
            // the bundled region rather than failing to start.
            RegionDatasets.bundled(context.applicationContext, record.generation)
        }
    }

    /**
     * Installs a verified package archive. Returns the installed record;
     * installing the same package twice is idempotent apart from its install
     * number, which moves to the newest: the most recently installed copy of a
     * region is the one the device keeps (`RegionUiModel.supersededInstalls`).
     */
    fun install(archive: File): InstalledRegion {
        root.mkdirs()
        installsDir.mkdirs()
        val staging = File(root, "staging-${UUID.randomUUID()}")
        try {
            val manifest = RegionArchiveImporter.extract(archive, staging)
            return synchronized(storeLock) {
                val fingerprint = manifest.pipelineFingerprint
                val dirName = "${sanitize(manifest.regionId)}-${fingerprint.take(12)}"
                val target = File(installsDir, dirName)
                val installs = list(withSizes = false)
                val sequence = (installs.maxOfOrNull { it.installSequence } ?: 0L) + 1
                val existing = installs.firstOrNull { it.dirName == dirName }
                if (target.isDirectory && existing != null && existing.pipelineFingerprint == fingerprint) {
                    staging.deleteRecursively()
                    val reinstalled = existing.copy(
                        installedAtEpochMs = System.currentTimeMillis(),
                        installSequence = sequence,
                    )
                    writeRegistry(installs.map { if (it.dirName == dirName) reinstalled else it })
                    return reinstalled
                }
                if (existing != null) {
                    // Another package shares the 12-character fingerprint prefix; its
                    // files may be the live map, so they are never deleted here.
                    throw RegionPackageException("Another installed copy of this region uses the same directory")
                }
                if (target.exists() && !target.deleteRecursively()) {
                    throw RegionPackageException("Could not replace the existing region directory")
                }
                if (!staging.renameTo(target)) {
                    throw RegionPackageException("Could not move the installed region into place")
                }
                val record = InstalledRegion(
                    installId = dirName,
                    regionId = manifest.regionId,
                    regionName = manifest.regionName,
                    coverage = manifest.coverage,
                    sourceDate = manifest.sourceDate,
                    sourceSha256 = manifest.sourceSha256,
                    pipelineFingerprint = fingerprint,
                    installedAtEpochMs = System.currentTimeMillis(),
                    bytes = installedBytes(target),
                    dirName = dirName,
                    installSequence = sequence,
                )
                writeRegistry(installs + record)
                record
            }
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    /**
     * Switches the active region and returns the new dataset generation. A
     * generation bump is what invalidates in-flight routing/geocoding results
     * and forces the map/router/geocoder to be rebuilt from the new data.
     */
    fun activate(installId: String): Int = synchronized(storeLock) {
        val install = find(installId) ?: throw RegionPackageException("The region is not installed")
        val dir = installsDir.resolve(install.dirName)
        RegionArchiveImporter.verifyCheap(dir, RegionArchiveImporter.readManifest(dir))
        val next = active().generation + 1
        writeActive(ActiveRegionRecord(installId, next))
        return next
    }

    /** Switches back to the bundled Queensland data. */
    fun activateBundled(): Int = synchronized(storeLock) {
        val next = active().generation + 1
        writeActive(ActiveRegionRecord(null, next))
        return next
    }

    /** Removes an installed region. The active install cannot be removed. */
    fun remove(installId: String): Unit = synchronized(storeLock) {
        if (active().installId == installId) {
            throw RegionPackageException("Activate another region before removing this one")
        }
        val install = find(installId) ?: throw RegionPackageException("The region is not installed")
        delete(install)
    }

    /**
     * Removes [installId] only if the registry still holds that exact install
     * (install number [installSequence]) and it is not active. A reinstall of
     * the same package after the caller decided to delete it moves its number,
     * so the fresh install survives. Returns whether it was removed.
     */
    fun removeIfUnchanged(installId: String, installSequence: Long): Boolean = synchronized(storeLock) {
        if (active().installId == installId) return false
        val install = find(installId) ?: return false
        if (install.installSequence != installSequence) return false
        delete(install)
        true
    }

    private fun delete(install: InstalledRegion) {
        val dir = installsDir.resolve(install.dirName)
        if (dir.exists() && !dir.deleteRecursively()) {
            throw RegionPackageException("Could not delete the installed region")
        }
        writeRegistry(list(withSizes = false).filterNot { it.installId == install.installId })
    }

    private fun parseInstall(obj: JSONObject): InstalledRegion? {
        val dirName = obj.optString("dirName").takeIf { it.isNotBlank() } ?: return null
        val regionId = obj.optString("regionId").takeIf { it.isNotBlank() } ?: return null
        return InstalledRegion(
            installId = obj.optString("installId").takeIf { it.isNotBlank() } ?: dirName,
            regionId = regionId,
            regionName = obj.optString("regionName", regionId),
            coverage = obj.optString("coverage").takeIf { it.isNotBlank() },
            sourceDate = obj.optString("sourceDate"),
            sourceSha256 = obj.optString("sourceSha256"),
            pipelineFingerprint = obj.optString("pipelineFingerprint"),
            installedAtEpochMs = obj.optLong("installedAtEpochMs", 0L),
            bytes = obj.optLong("bytes", 0L),
            dirName = dirName,
            installSequence = obj.optLong("installSequence", 0L),
        )
    }

    private fun writeRegistry(installs: List<InstalledRegion>) {
        val array = JSONArray()
        installs.forEach { install ->
            array.put(JSONObject().apply {
                put("installId", install.installId)
                put("regionId", install.regionId)
                put("regionName", install.regionName)
                install.coverage?.let { put("coverage", it) }
                put("sourceDate", install.sourceDate)
                put("sourceSha256", install.sourceSha256)
                put("pipelineFingerprint", install.pipelineFingerprint)
                put("installedAtEpochMs", install.installedAtEpochMs)
                put("bytes", install.bytes)
                put("dirName", install.dirName)
                put("installSequence", install.installSequence)
            })
        }
        writeAtomically(registryFile, JSONObject().put("installs", array).toString(2))
    }

    private fun writeActive(record: ActiveRegionRecord) {
        val doc = JSONObject().apply {
            record.installId?.let { put("installId", it) }
            put("generation", record.generation)
        }
        writeAtomically(activeFile, doc.toString(2))
    }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        try {
            FileChannel.open(tmp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        } catch (_: IOException) {
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sanitize(regionId: String): String =
        regionId.lowercase().replace(Regex("[^a-z0-9._-]"), "-").take(64).ifBlank { "region" }

    /** Actual bytes on disk for one installed directory, manifest included. */
    private fun installedBytes(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
