package com.curvemaps.app.rater

import com.curvemaps.app.geocoding.GeocoderIndex
import com.curvemaps.app.geocoding.SearchEngine
import com.curvemaps.app.routing.GraphFeatures
import com.curvemaps.app.routing.graphFeatures
import com.curvemaps.app.routing.loadMotoGraphHopper
import com.graphhopper.GraphHopper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** A dataset the rater can route on: the app's bundled graph or an installed `.motomap`. */
internal data class RegionEntry(
    val id: String,
    val name: String,
    val graphDir: File,
    val geocoderFile: File?,
    val source: String,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("source", source)
}

/** A loaded dataset. [id] plus [graphId] identify the graph a plan was routed on. */
internal class LoadedRegion(val entry: RegionEntry, val hopper: GraphHopper, geocoder: GeocoderIndex?) {
    val id: String get() = entry.id
    val features: GraphFeatures = hopper.graphFeatures(entry.graphDir)
    val edges = EdgeReader(hopper)
    val search: SearchEngine? = geocoder?.let { SearchEngine(it, clockMs = { System.currentTimeMillis() }, logTiming = {}) }
    val reverse: GeocoderIndex? = geocoder

    /** Changes whenever the graph is rebuilt, even under the same region id. */
    val graphId: String = sha256(File(entry.graphDir, "properties").readBytes().joinToString(",")).take(12)

    fun summary(): JSONObject = JSONObject().put("id", id).put("name", entry.name).put("graphId", graphId)
        .put("dataDate", hopper.properties.get("datareader.data.date") ?: JSONObject.NULL)

    fun info(): JSONObject {
        val bounds = hopper.baseGraph.bounds
        return summary()
            .put("source", entry.source)
            .put("geocoder", search != null)
            .put("bounds", JSONArray().put(bounds.minLat).put(bounds.minLon).put(bounds.maxLat).put(bounds.maxLon))
            .put("features", JSONObject().put("elevation", features.elevation).put("stops", features.stops).put("urbanDensity", features.urbanDensity))
    }

    fun close() {
        runCatching { hopper.close() }
    }
}

/** Download progress of one catalog region. */
internal class DownloadState(val regionId: String) {
    @Volatile var phase = "starting"
    @Volatile var bytes = 0L
    @Volatile var total = 0L
    @Volatile var error: String? = null

    fun toJson(): JSONObject = JSONObject().put("regionId", regionId).put("phase", phase).put("bytes", bytes)
        .put("total", total).put("error", error ?: JSONObject.NULL)
}

/**
 * Installs and loads datasets for the rater: the app's bundled graph, regions
 * downloaded from the map service, and local `.motomap` files. Installs keep
 * the graph and geocoder only (the rater draws OSM raster tiles instead).
 */
internal class RaterRegions(private val root: File, dataDir: File, val mapServer: String) {
    private val regionsDir = File(dataDir, "regions").apply { mkdirs() }
    private val downloadsDir = File(dataDir, "downloads").apply { mkdirs() }
    private val catalogArtifacts = ConcurrentHashMap<String, JSONObject>()
    val downloads = ConcurrentHashMap<String, DownloadState>()

    fun installed(): List<RegionEntry> {
        val bundled = listOf(File(root, "app/src/main/assets/graph-cache"), File(root, "data/graph-cache"))
            .firstOrNull { File(it, "properties").isFile }
            ?.let {
                RegionEntry(
                    "bundled", "Bundled graph (${it.relativeTo(root)})", it,
                    File(root, "app/src/main/assets/geocoder/geocoder.dat").takeIf { f -> f.isFile }, "bundled",
                )
            }
        val packages = regionsDir.listFiles().orEmpty().filter { !it.name.startsWith(".") && File(it, "graph/properties").isFile }.sortedBy { it.name }.map { dir ->
            val manifest = runCatching { JSONObject(File(dir, "manifest.json").readText()) }.getOrNull()
            val name = manifest?.optJSONObject("region")?.optString("name") ?: dir.name
            val date = manifest?.optJSONObject("source")?.optString("date").orEmpty()
            RegionEntry(dir.name, "$name ($date)", File(dir, "graph"), File(dir, "geocoder/geocoder.dat").takeIf { it.isFile }, "package")
        }
        return listOfNotNull(bundled) + packages
    }

    fun load(entry: RegionEntry): LoadedRegion {
        val hopper = loadMotoGraphHopper(entry.graphDir)
        val geocoder = entry.geocoderFile?.let { file ->
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                GeocoderIndex.parse(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN))
            }
        }
        return LoadedRegion(entry, hopper, geocoder)
    }

    /** The map service's catalog, trimmed to what the rater shows. */
    fun catalog(): JSONArray {
        val connection = open(URL("$mapServer/api/v1/catalog"))
        val status = connection.responseCode
        check(status == 200) { "Catalog: HTTP $status" }
        val text = connection.inputStream.use { it.readBytes().decodeToString() }
        val installedIds = installed().map { it.id }
        val out = JSONArray()
        val regions = JSONObject(text).optJSONArray("regions") ?: JSONArray()
        for (i in 0 until regions.length()) {
            val region = regions.getJSONObject(i)
            val artifact = region.optJSONObject("artifact")
            val available = artifact?.optBoolean("available") == true && !region.optBoolean("disabled")
            if (available) catalogArtifacts[region.getString("id")] = artifact!!
            val prefix = "${region.getString("id")}-"
            out.put(
                JSONObject()
                    .put("id", region.getString("id"))
                    .put("name", region.optString("name"))
                    .put("coverage", region.optString("coverage"))
                    .put("sourceDate", region.optString("sourceDate"))
                    .put("tier", region.optString("tier", "free"))
                    .put("available", available)
                    .put("size", artifact?.optLong("size") ?: 0)
                    .put("installed", JSONArray(installedIds.filter { it.startsWith(prefix) })),
            )
        }
        return out
    }

    /** Starts a background download + install; [onInstalled] gets the new entry. */
    fun startDownload(regionId: String, onInstalled: (RegionEntry) -> Unit): DownloadState {
        downloads[regionId]?.let { if (it.phase !in setOf("done", "error")) return it }
        val artifact = catalogArtifacts[regionId] ?: throw IllegalArgumentException("Refresh the catalog first: no package for $regionId")
        val path = artifact.getString("downloadUrl")
        require(path.startsWith("/api/v1/artifacts/")) { "Unexpected download URL $path" }
        val state = DownloadState(regionId).also { downloads[regionId] = it }
        Thread({
            try {
                val fingerprint = artifact.getString("fingerprint").take(12)
                val part = File(downloadsDir, "$regionId-$fingerprint.motomap.part")
                state.total = artifact.optLong("size")
                state.phase = "downloading"
                download(URL(mapServer + path), part, state)
                state.phase = "verifying"
                val digest = fileSha256(part)
                if (digest != artifact.getString("sha256")) {
                    part.delete()
                    error("Package digest mismatch ($digest)")
                }
                state.phase = "installing"
                val entry = install(part)
                part.delete()
                state.phase = "done"
                onInstalled(entry)
            } catch (e: Exception) {
                state.error = e.message ?: e.javaClass.simpleName
                state.phase = "error"
            }
        }, "rater-download-$regionId").apply { isDaemon = true; start() }
        return state
    }

    private fun open(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = true
    }

    /** Resumes into [part] with Range when it already holds bytes. */
    private fun download(url: URL, part: File, state: DownloadState) {
        val have = if (part.isFile) part.length() else 0L
        val connection = open(url)
        if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
        val append = when (val status = connection.responseCode) {
            206 -> true
            200 -> false
            402 -> error("CurveMaps Pro required for this region")
            416 -> return // already complete
            else -> error("Download: HTTP $status")
        }
        state.bytes = if (append) have else 0L
        RandomAccessFile(part, "rw").use { out ->
            if (append) out.seek(have) else out.setLength(0)
            connection.inputStream.use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    state.bytes += n
                }
            }
        }
    }

    /**
     * Installs a `.motomap` into `regions/<packageId>`: only the manifest's
     * graph and geocoder files are extracted (never an unlisted path), each
     * checked against its manifest digest, staged and then renamed in place.
     */
    fun install(file: File): RegionEntry {
        val packageId: String
        ZipFile(file).use { zip ->
            val manifestText = zip.getInputStream(zip.getEntry("manifest.json") ?: error("Not a .motomap: no manifest.json")).use { it.readBytes().decodeToString() }
            val manifest = JSONObject(manifestText)
            val profile = manifest.optJSONObject("compatibility")?.optString("graphProfile")
            check(profile == EXPECTED_PROFILE) { "Graph profile $profile, expected $EXPECTED_PROFILE" }
            packageId = manifest.getString("packageId")
            require(SAFE_NAME.matches(packageId) && packageId != "bundled") { "Unsafe package id $packageId" }
            val target = File(regionsDir, packageId)
            if (File(target, "graph/properties").isFile) return installed().first { it.id == packageId }
            val staging = File(regionsDir, ".staging-$packageId").apply { deleteRecursively(); mkdirs() }
            try {
                stage(zip, manifest, manifestText, staging, target)
            } catch (e: Exception) {
                staging.deleteRecursively()
                throw e
            }
        }
        return installed().first { it.id == packageId }
    }

    private fun stage(zip: ZipFile, manifest: JSONObject, manifestText: String, staging: File, target: File) {
        val components = manifest.getJSONArray("components")
        for (i in 0 until components.length()) {
            val component = components.getJSONObject(i)
            val name = component.getString("name")
            if (name != "graph" && name != "geocoder") continue
            val base = component.getString("path")
            val files = if (component.getString("kind") == "directory") {
                val list = component.getJSONArray("files")
                (0 until list.length()).map { list.getJSONObject(it).let { f -> "$base/${f.getString("path")}" to f.getString("sha256") } }
            } else {
                listOf(base to component.getString("sha256"))
            }
            files.forEach { (path, sha) -> extract(zip, path, sha, staging) }
        }
        File(staging, "manifest.json").writeText(manifestText)
        check(File(staging, "graph/properties").isFile) { "Package has no graph" }
        check(staging.renameTo(target)) { "Could not move the install into place" }
    }

    private fun extract(zip: ZipFile, path: String, sha256: String, staging: File) {
        require(SAFE_PATH.matches(path) && ".." !in path.split('/')) { "Unsafe package path $path" }
        val entry = zip.getEntry(path) ?: error("Package is missing $path")
        val out = File(staging, path).apply { parentFile.mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
        zip.getInputStream(entry).use { input ->
            out.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == sha256) { "Digest mismatch for $path" }
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** The only graph profile the app's pre-compiled weighting loads. */
        const val EXPECTED_PROFILE = "motorcycle|198752012"
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")
        private val SAFE_PATH = Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")
    }
}
