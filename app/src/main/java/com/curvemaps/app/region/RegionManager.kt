package com.curvemaps.app.region

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.curvemaps.app.BuildConfig
import com.curvemaps.app.region.net.DownloadCancellation
import com.curvemaps.app.region.net.DownloadCancelledException
import com.curvemaps.app.region.net.RegionHttpClient
import com.curvemaps.app.region.net.RegionHttpException
import com.curvemaps.app.region.net.RegionServerClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

private const val TAG = "CurveMaps.Regions"

/** Which step of the download flow is running. */
enum class DownloadPhase { DOWNLOADING, VERIFYING, SAVING, INSTALLING }

/** Live progress of a package download (or the copy to the user's file). */
data class DownloadProgress(
    val jobId: String,
    val regionName: String,
    val bytes: Long,
    val total: Long?,
    val phase: DownloadPhase = DownloadPhase.DOWNLOADING,
    /** Non-null while the transfer waits to retry after a transient failure. */
    val retry: DownloadRetry? = null,
) {
    val fraction: Float?
        get() = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

/** A download that can be resumed after a restart. */
data class InterruptedDownload(
    val jobId: String,
    val regionId: String,
    val regionName: String,
    val fingerprint: String,
    val downloadUrl: String,
    val etag: String?,
    val bytes: Long,
    val total: Long?,
    /** True when the transfer installs on completion (no file was chosen). */
    val installMode: Boolean = false,
)

/** The place search on the Maps screen ("Find a region" / Near me). */
data class ExtractSearch(
    val query: String = "",
    val nearMe: Boolean = false,
    val busy: Boolean = false,
    val searched: Boolean = false,
    val results: List<ServerExtract> = emptyList(),
    val error: String? = null,
)

/** A package the user saved to a device-visible location (session grant). */
data class SavedPackage(
    val regionId: String,
    val regionName: String,
    val fileName: String,
    val bytes: Long,
    val uri: String,
)

/** A completed package kept in app storage, waiting to be saved or imported. */
data class RecoveredPackage(
    val regionId: String,
    val regionName: String,
    val fileName: String,
    val bytes: Long,
)

/** Everything the Maps settings screen renders. */
data class MapsState(
    val serverAddress: String = "",
    /** The build's default map service (empty when the build has none). */
    val defaultServer: String = "",
    val usingDefaultServer: Boolean = false,
    val serverChecked: Boolean = false,
    val serverVersion: String = "",
    val generationEnabled: Boolean = false,
    val generationMessage: String = "",
    val catalog: List<ServerRegion> = emptyList(),
    val activeJob: ServerJob? = null,
    val download: DownloadProgress? = null,
    val interrupted: InterruptedDownload? = null,
    val savedPackage: SavedPackage? = null,
    val recoveredPackage: RecoveredPackage? = null,
    val pendingRemoval: PendingRemoval? = null,
    val installed: List<InstalledRegion> = emptyList(),
    val activeInstallId: String? = null,
    val activeRegionName: String = RegionDatasets.fallbackDisplayName,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val search: ExtractSearch = ExtractSearch(),
    /** A map installed during a ride; it activates when the ride ends (persisted). */
    val pendingActivation: String? = null,
    /**
     * Replaced copies whose deletion failed. They are listed again
     * ([RegionUiModel.visibleInstalls]) so the rider can delete them.
     */
    val failedRemovals: Set<String> = emptySet(),
)

/** A resolved downloadable artifact: either the cached catalog entry or a completed job. */
private data class DownloadTarget(
    val jobId: String,
    val regionId: String,
    val regionName: String,
    val fingerprint: String,
    val size: Long,
    val sha256: String,
    val downloadUrl: String,
    val sourceDate: String?,
)

/**
 * App-scoped controller for regional packages from the map service
 * (`BuildConfig.DEFAULT_MAP_SERVER`; riders cannot change it, see [RegionSettings]):
 * catalog → (optional build request → polling) → resumable download into
 * private staging → SHA-256 check → **install and activate in one step**
 * ([downloadAndInstall]). "Save a copy to Files" ([startDownload]) keeps the
 * older path that writes a `.motomap` to a location the user chose. The active
 * [RegionDataset] flow is what the route screen keys its router, geocoder, and
 * map style on.
 *
 * Nothing contacts the server until the user taps Browse maps; after one
 * successful connection the catalog refreshes when Maps opens. A build the
 * user asked for (a catalog region or a searched place) installs itself when
 * it completes. Mid-ride, an install waits to activate until the ride ends.
 * Cancelling or failing leaves the staged partial in place and keeps every
 * control retryable; the UI never loses the Download action to a terminal
 * build job.
 *
 * Deleting the active map is a two-phase operation: the app switches to the
 * bundled dataset first (generation bump, so every reader is rebuilt), waits
 * for the old routing graph's reader leases to drain,
 * and only then removes the immutable install directory.
 */
class RegionManager(context: Context, private val releaseTimeoutMs: Long = 30_000L) {
    companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val MAX_POLL_INTERVAL_MS = 15_000L
        const val POLL_FAILURES_BEFORE_WARNING = 3
        const val NO_MAP_SERVICE_MESSAGE = "This build has no map service configured."
        const val POLL_LOST_MESSAGE =
            "Lost contact with the server while it builds — still retrying."

        private val managers = java.util.concurrent.ConcurrentHashMap<String, RegionManager>()
        private val readersByRoot = java.util.concurrent.ConcurrentHashMap<String, RegionReaders>()
        private val changesByRoot = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        fun get(context: Context): RegionManager = managers.computeIfAbsent(context.applicationContext.filesDir.canonicalPath) {
            RegionManager(context.applicationContext)
        }
    }
    private val storeKey = context.applicationContext.filesDir.canonicalPath
    private val readers = readersByRoot.computeIfAbsent(storeKey) { RegionReaders() }
    fun retainDatasetReader(installId: String?): () -> Unit = readers.retain(installId)

    private val appContext = context.applicationContext
    private val registry = RegionRegistry(File(appContext.filesDir, "regions"))
    private val settings = RegionSettings(appContext)
    private val downloadsDir = File(appContext.filesDir, "regions/downloads")
    private val downloadStore = RegionDownloadStore(
        File(appContext.filesDir, "regions/download.json"),
        downloadsDir,
    )
    private val pendingRemovalStore = RegionPendingRemovalStore(
        File(appContext.filesDir, "regions/pending-removal.json"),
    )
    private val pendingActivationStore = RegionPendingActivationStore(
        File(appContext.filesDir, "regions/pending-activation.json"),
    )
    private val client = RegionServerClient(
        RegionHttpClient(
            allowInsecure = { url ->
                BuildConfig.DEBUG && settings.allowInsecureLocal && LocalNetworkPolicy.isLocalAddress(url)
            },
        ),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(MapsState(pendingActivation = pendingActivationStore.read()))
    val state: StateFlow<MapsState> = _state.asStateFlow()

    private val _dataset = MutableStateFlow(registry.activeDataset(appContext))
    val dataset: StateFlow<RegionDataset> = _dataset.asStateFlow()

    private var pollJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadCancellation: DownloadCancellation? = null
    private var pendingRelease: CompletableDeferred<Unit>? = null
    private val mapChanges = changesByRoot.computeIfAbsent(storeKey) { Mutex() }
    private val downloadChanges = Mutex()

    /** Replaced copies with a deletion already waiting for their readers. */
    private val pruning = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var rideActive = false

    /** The build whose completion should download and install the map. */
    @Volatile
    private var autoInstallJobId: String? = null
    private var searchJob: Job? = null

    init {
        refresh()
        loadPersistedDownload()
        resumePendingRemoval()
        // A map installed during a ride the app did not see end switches on now.
        if (_state.value.pendingActivation != null) scope.launch { activatePending() }
        // Finishes an update the app stopped before the older copy was deleted.
        pruneSuperseded()
    }

    // --- server configuration -------------------------------------------------

    /**
     * Called when the Maps screen opens: refreshes the catalog only for a user
     * who already connected once. A fresh install makes no network request
     * until Browse maps is tapped.
     */
    fun onMapsOpened() {
        if (settings.autoConnect && !_state.value.serverChecked && !_state.value.busy) checkServer()
    }

    /**
     * The UI disables deletion of the active map mid-ride; the manager refuses
     * too. A map installed during the ride activates when it ends.
     */
    fun setRideActive(active: Boolean) {
        rideActive = active
        // Under mapChanges, so an install that saw the ride still running has
        // either recorded its pending activation already or sees it ended.
        if (!active) scope.launch { activatePending() }
    }

    /** Activates the map installed during a ride, unless a ride is (again) running. */
    private suspend fun activatePending() {
        try {
            val activated = mapChanges.withLock {
                if (rideActive) return
                val installId = _state.value.pendingActivation ?: return
                setPendingActivation(null)
                // Deleted or replaced meanwhile: nothing to switch to.
                val install = registry.find(installId) ?: return
                switchToLocked(installId)
                install
            }
            _state.update { it.copy(notice = "${activated.regionName} is now the active map.") }
            Log.i(TAG, "activated ${activated.installId} after the ride")
            pruneSuperseded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = friendly(e)) }
        }
    }

    /** Persists the map that switches on when the ride ends, so a process death cannot lose it. */
    private fun setPendingActivation(installId: String?) {
        if (installId == null) pendingActivationStore.clear() else pendingActivationStore.save(installId)
        _state.update { it.copy(pendingActivation = installId) }
    }

    /**
     * Under [mapChanges]: makes [installId] the active map (a generation bump,
     * so every reader reopens its files). Switching to a map that is waiting
     * to be deleted cancels the deletion before its files can be unlinked.
     */
    private suspend fun switchToLocked(installId: String) {
        if (pendingRemovalId() == installId) clearPendingRemoval()
        withContext(Dispatchers.IO) { registry.activate(installId) }
        refreshNow()
    }

    private fun pendingRemovalId(): String? =
        _state.value.pendingRemoval?.installId ?: pendingRemovalStore.read()?.installId

    /** Loads the map service's region catalog (the Maps screen's Browse maps). */
    fun checkServer() {
        val address = settings.serverAddress
        if (address.isBlank()) {
            _state.update { it.copy(error = NO_MAP_SERVICE_MESSAGE) }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                var lastError: Exception? = null
                for (base in client.candidateBaseUrls(address, ::plainHttpAllowed)) {
                    try {
                        connect(base)
                        return@launch
                    } catch (e: RegionHttpException) {
                        lastError = e
                        // An HTTP answer means this address reached a server:
                        // a different scheme would not be a better guess.
                        if (e.statusCode != 0) break
                    }
                }
                throw lastError ?: RegionHttpException("Could not reach the server")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, serverChecked = false, catalog = emptyList(), error = friendly(e))
                }
            }
        }
    }

    private fun plainHttpAllowed(url: String): Boolean =
        BuildConfig.DEBUG && settings.allowInsecureLocal && LocalNetworkPolicy.isLocalAddress(url)

    /**
     * Loads health + catalog from [base]. A build
     * the server is still running for a catalog region (for example one
     * requested before the app restarted) is picked up and followed again.
     */
    private suspend fun connect(base: String) {
        val health = try {
            client.health(base)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val catalog = client.catalog(base)
        val generation = health?.optJSONObject("generation")
        settings.autoConnect = true
        _state.update {
            it.copy(
                serverAddress = base,
                usingDefaultServer = settings.usingDefaultServer,
                serverChecked = true,
                serverVersion = health?.optString("version").orEmpty(),
                generationEnabled = generation?.optBoolean("enabled", false) ?: false,
                generationMessage = generation?.optString("message").orEmpty(),
                catalog = catalog,
                busy = false,
            )
        }
        val running = catalog.firstNotNullOfOrNull { it.activeJobId }
        if (running != null && _state.value.activeJob?.isActive != true) {
            runCatching { client.job(base, running) }.getOrNull()?.let { job ->
                _state.update { it.copy(activeJob = job) }
                if (job.isActive) pollJob(base, job.id)
            }
        }
    }

    /** Requests generation for a catalog region and follows the job. */
    fun requestBuild(regionId: String) {
        val address = settings.serverAddress
        if (address.isBlank()) {
            _state.update { it.copy(error = NO_MAP_SERVICE_MESSAGE) }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                val base = client.normalizeBaseUrl(address)
                val token = client.csrfToken(base)
                val job = client.requestBuild(base, token, regionId)
                followBuild(base, job)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    private suspend fun pollJob(base: String, jobId: String) {
        pollJob?.cancel()
        val job = scope.launch {
            var failures = 0
            while (isActive) {
                // Back off while the server is unreachable instead of hammering it.
                delay((POLL_INTERVAL_MS shl failures.coerceAtMost(3)).coerceAtMost(MAX_POLL_INTERVAL_MS))
                try {
                    val current = client.job(base, jobId)
                    failures = 0
                    _state.update {
                        it.copy(
                            activeJob = current,
                            error = it.error.takeUnless { message -> message == POLL_LOST_MESSAGE },
                        )
                    }
                    if (current.isCompleted || current.isFailed || current.isCanceled) {
                        if (current.isCompleted) {
                            refreshCatalogQuietly(base)
                            installIfRequested(current)
                        } else if (autoInstallJobId == current.id) {
                            autoInstallJobId = null
                        }
                        return@launch
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RegionHttpException) {
                    if (e.statusCode == 404) {
                        // The server forgot the job (state wiped): stop
                        // following it and say so instead of polling forever.
                        _state.update {
                            it.copy(
                                activeJob = null,
                                error = "The map service no longer has this build. Refresh the catalog and request it again.",
                            )
                        }
                        return@launch
                    }
                    // A transient poll failure must not lose the job; keep the
                    // last known state and try again.
                    failures++
                    Log.w(TAG, "job poll failed ($failures)", e)
                    if (failures == POLL_FAILURES_BEFORE_WARNING) {
                        _state.update { it.copy(error = POLL_LOST_MESSAGE) }
                    }
                } catch (e: Exception) {
                    failures++
                    Log.w(TAG, "job poll failed ($failures)", e)
                }
            }
        }
        pollJob = job
    }

    /** Re-reads the catalog so a finished build's artifact size/hash show up. */
    private suspend fun refreshCatalogQuietly(base: String) {
        runCatching { client.catalog(base) }.getOrNull()?.let { catalog ->
            _state.update { it.copy(catalog = catalog) }
        }
    }

    /**
     * Shows a build the user asked for and installs its map when it completes
     * (immediately for a cache hit).
     */
    private suspend fun followBuild(base: String, job: ServerJob) {
        autoInstallJobId = job.id
        _state.update { it.copy(activeJob = job, busy = false) }
        if (job.isActive) {
            pollJob(base, job.id)
        } else if (job.isCompleted) {
            refreshCatalogQuietly(base)
            installIfRequested(job)
        }
    }

    private fun installIfRequested(job: ServerJob) {
        if (autoInstallJobId != job.id) return
        autoInstallJobId = null
        if (job.downloadUrl.isBlank()) return
        downloadAndInstall(job.regionId)
    }

    /**
     * Stops following the current build without cancelling it: the server
     * keeps building (and caches the result for everyone); the region row
     * offers Download & install once it is ready.
     */
    fun stopWaiting() {
        pollJob?.cancel()
        autoInstallJobId = null
        _state.update {
            it.copy(
                activeJob = null,
                notice = "Stopped waiting. The map keeps building on the server; refresh later to install it.",
            )
        }
    }

    // --- place search -----------------------------------------------------------

    /** Searches places the server can build by name. */
    fun searchExtracts(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _state.update { it.copy(search = ExtractSearch(query = trimmed, error = "Type at least two letters")) }
            return
        }
        runSearch(ExtractSearch(query = trimmed, busy = true)) { base -> client.extracts(base, trimmed) }
    }

    /** Finds the buildable regions that contain a location (Near me). */
    fun searchNearMe(lat: Double, lon: Double) {
        runSearch(ExtractSearch(nearMe = true, busy = true)) { base -> client.extracts(base, null, lat, lon) }
    }

    /** Shows why a search could not run (for example, no location fix yet). */
    fun searchUnavailable(message: String) {
        _state.update { it.copy(search = ExtractSearch(nearMe = true, searched = true, error = message)) }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(search = ExtractSearch()) }
    }

    private fun runSearch(start: ExtractSearch, query: (String) -> List<ServerExtract>) {
        searchJob?.cancel()
        _state.update { it.copy(search = start) }
        searchJob = scope.launch {
            try {
                val base = client.normalizeBaseUrl(settings.serverAddress)
                val results = query(base)
                _state.update { it.copy(search = start.copy(busy = false, searched = true, results = results)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(search = start.copy(busy = false, searched = true, error = friendly(e))) }
            }
        }
    }

    /** Builds a searched place (or serves its cached package) and installs it. */
    fun requestExtractBuild(extractId: String) {
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                val base = client.normalizeBaseUrl(settings.serverAddress)
                val token = client.csrfToken(base)
                val job = client.requestExtractBuild(base, token, extractId)
                refreshCatalogQuietly(base)
                followBuild(base, job)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    fun cancelActiveJob() {
        val job = _state.value.activeJob ?: return
        val address = settings.serverAddress
        pollJob?.cancel()
        scope.launch {
            try {
                val base = client.normalizeBaseUrl(address)
                val token = client.csrfToken(base)
                val canceled = client.cancelBuild(base, token, job.id)
                _state.update { it.copy(activeJob = canceled, notice = "Build canceled") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = friendly(e)) }
            }
        }
    }

    // --- download and user-visible save ---------------------------------------

    /** Default file name offered by the system "Save to" dialog. */
    fun suggestedFileName(regionId: String): String {
        val target = downloadTarget(regionId)
        return RegionDownloadNaming.suggestedDisplayName(
            regionId = regionId,
            sourceDate = target?.sourceDate,
            fingerprint = target?.fingerprint.orEmpty(),
        )
    }

    fun suggestedFileNameForResume(): String {
        val record = downloadStore.read()
        return RegionDownloadNaming.suggestedDisplayName(
            regionId = record?.regionId.orEmpty().ifBlank { "region" },
            sourceDate = null,
            fingerprint = record?.fingerprint.orEmpty(),
        )
    }

    fun suggestedFileNameForRecovered(): String {
        val recovered = _state.value.recoveredPackage ?: return "package.motomap"
        val record = downloadStore.read()?.takeIf { it.regionId == recovered.regionId }
        return RegionDownloadNaming.suggestedDisplayName(
            regionId = recovered.regionId,
            sourceDate = null,
            fingerprint = record?.fingerprint.orEmpty(),
        )
    }

    /**
     * Downloads [regionId] into private staging and copies it to the
     * user-visible [destination] once the transfer is verified complete. The
     * file only appears at the destination in full; a failed or cancelled
     * transfer leaves the resumable partial in app storage.
     */
    fun startDownload(regionId: String, destination: Uri) {
        val target = downloadTarget(regionId)
        if (target == null) {
            _state.update {
                it.copy(error = "This region has no downloadable package — request a build first")
            }
            return
        }
        val record = RegionDownloadRecord(
            jobId = target.jobId,
            regionId = target.regionId,
            regionName = target.regionName,
            fingerprint = target.fingerprint,
            downloadUrl = target.downloadUrl,
            etag = null,
            bytes = 0,
            total = target.size.takeIf { it > 0 },
            fileName = RegionDownloadNaming.stagedFileName(target.regionId, target.fingerprint),
            destinationUri = destination.toString(),
            sha256 = PackageChecksum.expected(target.sha256),
        )
        launchDownload(record, destination)
    }

    /**
     * Downloads [regionId] into private staging, checks its SHA-256 and
     * installs and activates it — the one-tap path. Needs room for the
     * download and the extracted install side by side.
     */
    fun downloadAndInstall(regionId: String) {
        val target = downloadTarget(regionId)
        if (target == null) {
            _state.update {
                it.copy(error = "This region has no downloadable package yet — build it first")
            }
            return
        }
        val needed = RegionUiModel.installSpaceNeeded(target.size)
        val free = appContext.filesDir.usableSpace
        if (needed > 0 && free < needed) {
            _state.update {
                it.copy(error = RegionUiModel.notEnoughSpaceMessage(target.regionName, needed, free))
            }
            return
        }
        val record = RegionDownloadRecord(
            jobId = target.jobId,
            regionId = target.regionId,
            regionName = target.regionName,
            fingerprint = target.fingerprint,
            downloadUrl = target.downloadUrl,
            etag = null,
            bytes = 0,
            total = target.size.takeIf { it > 0 },
            fileName = RegionDownloadNaming.stagedFileName(target.regionId, target.fingerprint),
            destinationUri = null,
            sha256 = PackageChecksum.expected(target.sha256),
        )
        launchInstall(record)
    }

    /** Resumes an interrupted one-tap download (no file picker needed). */
    fun resumeInstall() {
        val record = downloadStore.read()
        if (record == null) {
            _state.update { it.copy(error = "There is no interrupted download to resume") }
            return
        }
        launchInstall(record.copy(destinationUri = null))
    }

    private fun launchInstall(record: RegionDownloadRecord) {
        val address = settings.serverAddress
        if (address.isBlank()) {
            _state.update { it.copy(error = NO_MAP_SERVICE_MESSAGE) }
            return
        }
        replaceDownload {
            downloadStore.save(record)
            publishDownload(record, record.bytes, record.total)
            _state.update { it.copy(interrupted = null, error = null, notice = null) }
            try {
                val base = client.normalizeBaseUrl(address)
                var current = record
                val staged = downloadStore.destinationFile(current)
                val complete = current.total?.let { staged.isFile && staged.length() >= it } ?: false
                if (!complete) {
                    current = transferWithRetries(base, current, staged)
                }
                verifyChecksum(current, staged)
                installStaged(current, staged)
            } catch (e: DownloadCancelledException) {
                loadPersistedDownload()
                _state.update { it.copy(download = null) }
            } catch (e: CancellationException) {
                loadPersistedDownload()
                _state.update { it.copy(download = null) }
                throw e
            } catch (e: Exception) {
                loadPersistedDownload()
                _state.update { it.copy(download = null, error = friendly(e)) }
            }
        }
    }

    /**
     * Installs a verified staged package. A region new to the device becomes
     * the active map (after the ride, if one is active); an update takes the
     * older copy's place and the older copy is deleted.
     */
    private suspend fun installStaged(record: RegionDownloadRecord, staged: File) {
        publishDownload(record, staged.length(), staged.length(), phase = DownloadPhase.INSTALLING)
        val install = withContext(Dispatchers.IO) { registry.install(staged) }
        staged.delete()
        downloadStore.partFile(record).delete()
        downloadStore.clear()
        Log.i(TAG, "downloaded and installed ${install.regionId} (${install.installId})")
        val settlement = settleInstall(install, Activation.NEW_REGION)
        _state.update {
            it.copy(
                download = null,
                recoveredPackage = null,
                notice = installedNotice(install.regionName, settlement, fromFile = false),
            )
        }
    }

    /** Where a freshly installed package left the active map. */
    private enum class Settled { ACTIVE, AFTER_RIDE, INACTIVE }

    private data class InstallSettlement(val state: Settled, val replacedOlderCopy: Boolean)

    /** When a freshly installed package becomes the active map. */
    private enum class Activation {
        /** Load map file: the rider picked it to use it now (re-activates an active copy too). */
        ALWAYS,

        /** One-tap download: a new region switches on; an update takes the older copy's place. */
        NEW_REGION,

        /** Offline import: only to take over from an active older copy. */
        REPLACING_ACTIVE,
    }

    /**
     * Settles a freshly installed package against the copies of its region
     * already on the device. The device keeps one copy per region, so a
     * package for an installed region **replaces** it: the new copy becomes
     * active whenever the older copy was active or was waiting to switch on
     * after the ride (when the ride ends, mid-ride), and the older copy is then
     * deleted once nothing reads it ([pruneSuperseded]). A copy that is being
     * deleted is not replaced (installing that same copy cancels its deletion),
     * and with no usable map at all the new copy always switches on. Otherwise
     * [activation] decides.
     */
    private suspend fun settleInstall(install: InstalledRegion, activation: Activation): InstallSettlement {
        val settlement = mapChanges.withLock {
            // Installing the very copy that is being deleted brings it back.
            val removing = pendingRemovalId()
            if (removing == install.installId) clearPendingRemoval()
            val activeId = registry.active().installId
            val pendingId = _state.value.pendingActivation
            // A copy on its way out (being deleted) is not one this install replaces.
            val older = registry.list(withSizes = false).filter {
                it.regionId == install.regionId && it.installId != install.installId && it.installId != removing
            }
            val replaced = older.isNotEmpty()
            val replacesActive = older.any { it.installId == activeId || it.installId == pendingId }
            // With no usable map at all (a map-less build, or the active map
            // deleted), any installed map beats none.
            val noMap = !withContext(Dispatchers.IO) { registry.activeDataset(appContext) }.dataPresent
            val activate = when (activation) {
                Activation.ALWAYS -> true
                Activation.NEW_REGION -> !replaced || replacesActive || noMap
                Activation.REPLACING_ACTIVE -> replacesActive || noMap
            }
            val alreadyActive = activeId == install.installId
            val state = when {
                !activate -> if (alreadyActive) Settled.ACTIVE else Settled.INACTIVE
                rideActive -> if (alreadyActive) {
                    Settled.ACTIVE
                } else {
                    setPendingActivation(install.installId)
                    Settled.AFTER_RIDE
                }
                // Re-activating bumps the generation, so readers reopen the files.
                else -> {
                    switchToLocked(install.installId)
                    Settled.ACTIVE
                }
            }
            refreshNow()
            InstallSettlement(state, replaced)
        }
        if (settlement.replacedOlderCopy) pruneSuperseded()
        return settlement
    }

    private fun installedNotice(name: String, settlement: InstallSettlement, fromFile: Boolean): String = buildString {
        val replaced = settlement.replacedOlderCopy
        append(
            when (settlement.state) {
                Settled.ACTIVE -> when {
                    replaced -> "$name is updated and active. The older copy is being deleted."
                    fromFile -> "$name installed and active."
                    else -> "$name is installed and active. It works offline from now on."
                }
                Settled.AFTER_RIDE -> if (replaced) {
                    "$name is updated. The new copy switches on when this ride ends."
                } else {
                    "$name is installed. It switches on when this ride ends."
                }
                Settled.INACTIVE -> if (replaced) {
                    "$name is updated. The older copy is being deleted."
                } else {
                    "$name installed — activate it to use it offline."
                }
            },
        )
        if (fromFile) append(" The selected file was left where it was.")
    }

    /**
     * Deletes the older copies a newer install replaced
     * ([RegionUiModel.supersededInstalls]). Each waits, as long as it takes,
     * until the map, router and search have let go of it, then is deleted only
     * if it is still that same replaced copy (a reinstall or switch meanwhile
     * keeps it). An older copy that is still the active map is left alone
     * until the new copy is activated. A deletion that fails lists the copy
     * again so the rider can delete it, and is retried on the next switch or
     * start.
     */
    private fun pruneSuperseded() {
        scope.launch {
            try {
                val targets = mapChanges.withLock { supersededNow() }
                targets.filter { pruning.add(it.installId) }.forEach { old -> scope.launch { pruneOne(old) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "finding replaced maps failed", e)
            }
        }
    }

    private suspend fun pruneOne(old: InstalledRegion) {
        try {
            while (true) {
                readers.drained(old.installId).await()
                val finished = mapChanges.withLock {
                    // A switch, reinstall or deletion while we waited changes what this copy is.
                    val still = supersededNow().any {
                        it.installId == old.installId && it.installSequence == old.installSequence
                    }
                    if (!still) return@withLock true
                    var removed = false
                    val drained = withContext(Dispatchers.IO) {
                        readers.removeIfDrained(old.installId) { removed = registry.removeIfUnchanged(old.installId, old.installSequence) }
                    }
                    // A reader attached again after the wait: wait for it too.
                    if (!drained) return@withLock false
                    if (removed) Log.i(TAG, "deleted ${old.installId}: a newer copy of ${old.regionId} replaced it")
                    refreshNow()
                    true
                }
                if (finished) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "deleting replaced map ${old.installId} failed", e)
            _state.update {
                it.copy(
                    failedRemovals = it.failedRemovals + old.installId,
                    error = "Couldn't delete the older copy of ${old.regionName} (${friendly(e)}). " +
                        "It is listed under Installed maps so you can delete it.",
                )
            }
            runCatching { mapChanges.withLock { refreshNow() } }
        } finally {
            pruning.remove(old.installId)
        }
    }

    private fun supersededNow(): List<InstalledRegion> = RegionUiModel.supersededInstalls(
        installed = registry.list(withSizes = false),
        activeInstallId = registry.active().installId,
        pendingRemovalId = pendingRemovalId(),
    )

    /** Resumes the persisted staged download and saves it to a chosen location. */
    fun resumeDownload(destination: Uri) {
        val record = downloadStore.read()
        if (record == null) {
            _state.update { it.copy(error = "There is no interrupted download to resume") }
            return
        }
        launchDownload(record.copy(destinationUri = destination.toString()), destination)
    }

    private fun launchDownload(record: RegionDownloadRecord, destination: Uri) {
        val address = settings.serverAddress
        if (address.isBlank()) {
            _state.update { it.copy(error = NO_MAP_SERVICE_MESSAGE) }
            return
        }
        replaceDownload {
            downloadStore.save(record)
            _state.update {
                it.copy(
                    download = DownloadProgress(
                        jobId = record.jobId,
                        regionName = record.regionName.ifBlank { record.regionId },
                        bytes = record.bytes,
                        total = record.total,
                        phase = DownloadPhase.DOWNLOADING,
                    ),
                    interrupted = null,
                    error = null,
                )
            }
            try {
                val base = client.normalizeBaseUrl(address)
                var current = record
                val staged = downloadStore.destinationFile(current)
                val complete = current.total?.let { staged.isFile && staged.length() >= it } ?: false
                if (!complete) {
                    current = transferWithRetries(base, current, staged)
                }
                verifyChecksum(current, staged)
                _state.update {
                    it.copy(
                        download = DownloadProgress(
                            jobId = current.jobId,
                            regionName = current.regionName.ifBlank { current.regionId },
                            bytes = staged.length(),
                            total = current.total ?: staged.length(),
                            phase = DownloadPhase.SAVING,
                        ),
                    )
                }
                val saved = saveToUserFile(current, staged, destination)
                _state.update {
                    it.copy(
                        download = null,
                        interrupted = null,
                        recoveredPackage = null,
                        savedPackage = saved,
                        notice = "Saved \"${saved.fileName}\" to your device — " +
                            "tap Import saved file to install it.",
                    )
                }
                Log.i(TAG, "saved ${current.regionId} to a user file: ${saved.bytes} bytes")
            } catch (e: DownloadCancelledException) {
                discardUntouchedDestination(destination)
                loadPersistedDownload()
                _state.update { it.copy(download = null) }
            } catch (e: CancellationException) {
                // A user cancel lands here when it hits a retry wait or the
                // checksum: keep the same truthful "interrupted" state.
                discardUntouchedDestination(destination)
                loadPersistedDownload()
                _state.update { it.copy(download = null) }
                throw e
            } catch (e: Exception) {
                discardUntouchedDestination(destination)
                loadPersistedDownload()
                _state.update { it.copy(download = null, error = friendly(e)) }
            }
        }
    }

    /**
     * Runs the transfer, retrying transient failures in place: each retry
     * resumes the staged partial (never restarts it), the ETag/size are
     * persisted the moment the server answers, and the row shows a countdown
     * while it waits. Permanent failures and exhausted retries propagate with
     * the partial kept for a manual Resume.
     */
    private suspend fun transferWithRetries(
        base: String,
        record: RegionDownloadRecord,
        staged: File,
    ): RegionDownloadRecord {
        val context = coroutineContext
        var current = record
        var failures = 0
        while (true) {
            val partFile = downloadStore.partFile(current)
            val before = if (partFile.isFile) partFile.length() else 0L
            try {
                val outcome = client.downloadArtifact(
                    baseUrl = base,
                    job = current.toServerJob(),
                    destination = staged,
                    etag = current.etag,
                    onProgress = { bytes, total ->
                        context.ensureActive()
                        publishDownload(current, bytes, total ?: current.total)
                    },
                    isCancelled = { !context.isActive },
                    cancellation = context[DownloadCancellation] ?: DownloadCancellation(),
                    onResponse = { etag, total ->
                        current = current.copy(etag = etag ?: current.etag, total = current.total ?: total)
                        downloadStore.save(current)
                    },
                )
                current = current.copy(
                    bytes = outcome.bytes,
                    etag = outcome.etag ?: current.etag,
                    total = current.total ?: outcome.bytes,
                )
                downloadStore.save(current)
                return current
            } catch (e: RegionHttpException) {
                val after = if (partFile.isFile) partFile.length() else 0L
                if (after > before) failures = 0
                failures++
                if (!DownloadRetryPolicy.shouldRetry(e, failures)) throw e
                Log.w(TAG, "download attempt failed ($failures), retrying: ${e.message}")
                var remaining = DownloadRetryPolicy.delayMs(failures, e.retryAfterMs)
                while (remaining > 0) {
                    publishDownload(
                        current,
                        after,
                        current.total,
                        retry = DownloadRetry(
                            attempt = failures + 1,
                            maxAttempts = DownloadRetryPolicy.MAX_ATTEMPTS,
                            secondsUntilRetry = ((remaining + 999) / 1000).toInt(),
                            reason = e.message ?: "Connection lost",
                        ),
                    )
                    val step = remaining.coerceAtMost(1_000L)
                    delay(step)
                    remaining -= step
                }
            }
        }
    }

    private fun publishDownload(
        record: RegionDownloadRecord,
        bytes: Long,
        total: Long?,
        phase: DownloadPhase = DownloadPhase.DOWNLOADING,
        retry: DownloadRetry? = null,
    ) {
        _state.update {
            it.copy(
                download = DownloadProgress(
                    jobId = record.jobId,
                    regionName = record.regionName.ifBlank { record.regionId },
                    bytes = bytes,
                    total = total,
                    phase = phase,
                    retry = retry,
                ),
            )
        }
    }

    /**
     * Compares the staged package with the digest the server advertised. A
     * mismatch discards the staged bytes (resuming them could only reproduce
     * the corruption) and asks for a fresh download; it is never saved to
     * the user's file.
     */
    private suspend fun verifyChecksum(record: RegionDownloadRecord, staged: File) {
        val expected = PackageChecksum.expected(record.sha256) ?: return
        publishDownload(record, staged.length(), staged.length(), phase = DownloadPhase.VERIFYING)
        val context = coroutineContext
        val actual = withContext(Dispatchers.IO) {
            PackageChecksum.sha256Hex(staged) { !context.isActive }
        }
        if (actual != expected) {
            staged.delete()
            downloadStore.partFile(record).delete()
            downloadStore.save(record.copy(bytes = 0, etag = null))
            Log.w(TAG, "checksum mismatch for ${record.regionId}: expected $expected, got $actual")
            throw RegionPackageException(
                "The downloaded package was damaged in transit (checksum mismatch) and was discarded. " +
                    "Download it again.",
            )
        }
    }

    /**
     * Copies a staged package to the user's destination and verifies the byte
     * count before reporting success. A short write throws and keeps the staged
     * file for a retry.
     */
    private suspend fun saveToUserFile(
        record: RegionDownloadRecord,
        staged: File,
        destination: Uri,
    ): SavedPackage = withContext(Dispatchers.IO) {
        val length = staged.length()
        val output = appContext.contentResolver.openOutputStream(destination, "w")
            ?: throw RegionPackageException("The selected location could not be opened for writing")
        val written = RegionPackageTransfer.copyTo(staged, output)
        if (written != length) {
            throw RegionPackageException(
                "The package was not fully saved (wrote $written of $length bytes)",
            )
        }
        val displayName = displayNameOf(destination) ?: record.fileName
        staged.delete()
        downloadStore.partFile(record).delete()
        downloadStore.clear()
        SavedPackage(
            regionId = record.regionId,
            regionName = record.regionName.ifBlank { record.regionId },
            fileName = displayName,
            bytes = written,
            uri = destination.toString(),
        )
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /**
     * Removes a file the system picker created for this download when the
     * transfer failed before any byte was written. A non-empty destination is
     * left alone (it may be a partial save worth inspecting), and the staged
     * copy in app storage is always kept for a retry.
     */
    private fun discardUntouchedDestination(uri: Uri) {
        runCatching {
            val size = appContext.contentResolver.openFileDescriptor(uri, "r")
                ?.use { it.statSize } ?: return
            if (size > 0L) return
            DocumentsContract.deleteDocument(appContext.contentResolver, uri)
        }
    }

    /** Saves the recovered app-storage package to a chosen user-visible file. */
    fun saveRecoveredPackage(destination: Uri) {
        val recovered = _state.value.recoveredPackage ?: return
        val record = downloadStore.read()?.takeIf { it.regionId == recovered.regionId }
        val staged = record?.let { downloadStore.destinationFile(it) }
        if (record == null || staged == null || !staged.isFile) {
            loadPersistedDownload()
            _state.update { it.copy(error = "The downloaded package is no longer in app storage") }
            return
        }
        scope.launch {
            _state.update {
                it.copy(
                    download = DownloadProgress(
                        jobId = record.jobId,
                        regionName = recovered.regionName,
                        bytes = staged.length(),
                        total = staged.length(),
                        phase = DownloadPhase.SAVING,
                    ),
                    error = null,
                )
            }
            try {
                val saved = saveToUserFile(record, staged, destination)
                _state.update {
                    it.copy(
                        download = null,
                        recoveredPackage = null,
                        savedPackage = saved,
                        notice = "Saved \"${saved.fileName}\" to your device — " +
                            "tap Import saved file to install it.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                discardUntouchedDestination(destination)
                loadPersistedDownload()
                _state.update { it.copy(download = null, error = friendly(e)) }
            }
        }
    }

    /** Installs the package recovered from the old private-only flow. */
    fun importRecoveredPackage() {
        val recovered = _state.value.recoveredPackage ?: return
        val record = downloadStore.read()?.takeIf { it.regionId == recovered.regionId }
        val staged = record?.let { downloadStore.destinationFile(it) }
        if (record == null || staged == null || !staged.isFile) {
            loadPersistedDownload()
            _state.update { it.copy(error = "The downloaded package is no longer in app storage") }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                val install = withContext(Dispatchers.IO) { registry.install(staged) }
                staged.delete()
                downloadStore.partFile(record).delete()
                downloadStore.clear()
                val settlement = settleInstall(install, Activation.REPLACING_ACTIVE)
                _state.update {
                    it.copy(
                        busy = false,
                        recoveredPackage = null,
                        notice = installedNotice(install.regionName, settlement, fromFile = false),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    /** Serializes transfer replacement through the previous transfer's complete cleanup. */
    private fun replaceDownload(action: suspend () -> Unit) {
        downloadJob?.cancel()
        val previous = downloadCancellation
        val cancellation = DownloadCancellation()
        downloadCancellation = cancellation
        scope.launch { previous?.cancel() }
        downloadJob = scope.launch(cancellation) {
            downloadChanges.withLock {
                coroutineContext.ensureActive()
                action()
            }
        }
    }

    fun cancelDownload() {
        _state.update { it.copy(download = null) }
        replaceDownload {
            loadPersistedDownload()
            _state.update { it.copy(download = null) }
        }
    }

    /** Installs a package the user picked with the system file picker. */
    fun importPackage(uri: Uri) {
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                val install = installFromUri(uri)
                val settlement = settleInstall(install, Activation.REPLACING_ACTIVE)
                _state.update {
                    it.copy(
                        busy = false,
                        // The saved file is now installed: drop its card so
                        // it cannot invite a second, duplicate import.
                        savedPackage = it.savedPackage?.takeUnless { saved -> saved.uri == uri.toString() },
                        notice = installedNotice(install.regionName, settlement, fromFile = true),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    /**
     * The single **Load map file** path for a `.motomap` package: verify and
     * install it exactly as [importPackage] does, then make it the active map,
     * because the person who picked a file from the main screen wants to use it
     * now. Throws (with a user-presentable message) instead of reporting through
     * [state], so the caller can show the failure where the action was started.
     * Refused mid-ride, like every other map switch.
     */
    suspend fun loadPackageAndActivate(uri: Uri): InstalledRegion {
        if (rideActive) {
            throw RegionPackageException("Finish the ride before switching maps")
        }
        val install = try {
            installFromUri(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RegionPackageException(friendly(e), e)
        }
        return activateImportedPackage(install)
    }

    /**
     * Installation succeeded; activates it (replacing an older copy of the
     * region). A ride starting during import defers only activation, to the
     * ride's end.
     */
    internal suspend fun activateImportedPackage(install: InstalledRegion): InstalledRegion {
        val settlement = try {
            settleInstall(install, Activation.ALWAYS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RegionPackageException(friendly(e), e)
        }
        _state.update {
            it.copy(error = null, notice = installedNotice(install.regionName, settlement, fromFile = true))
        }
        return install
    }

    /** Copies [uri] to a scratch file, verifies and installs it, and removes the scratch copy. */
    private suspend fun installFromUri(uri: Uri): InstalledRegion {
        val file = withContext(Dispatchers.IO) {
            val target = File(appContext.cacheDir, "import-${System.currentTimeMillis()}.motomap")
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw RegionPackageException("The selected file could not be opened")
            try {
                input.use { source -> target.outputStream().use { output -> source.copyTo(output) } }
            } catch (e: Exception) {
                target.delete()
                throw e
            }
            target
        }
        try {
            val install = withContext(Dispatchers.IO) { registry.install(file) }
            Log.i(TAG, "imported region ${install.regionId} (${install.installId})")
            return install
        } finally {
            file.delete()
        }
    }

    /** Re-imports the file saved in this session without another picker trip. */
    fun importSavedPackage() {
        val uri = _state.value.savedPackage?.uri?.takeIf { it.isNotBlank() } ?: return
        importPackage(Uri.parse(uri))
    }

    // --- activation and removal ----------------------------------------------

    /**
     * Activates an installed region. Callers must not do this mid-ride; the UI
     * disables the control while guidance is active.
     */
    fun activate(installId: String) {
        if (refuseSwitchDuringRide()) return
        scope.launch {
            try {
                mapChanges.withLock {
                    if (refuseSwitchDuringRide()) return@launch
                    _state.update { it.copy(busy = true, error = null, notice = null) }
                    switchToLocked(installId)
                }
                val name = _state.value.installed.firstOrNull { it.installId == installId }?.regionName
                _state.update {
                    it.copy(busy = false, notice = "${name ?: "The selected region"} is now the active map.")
                }
                Log.i(TAG, "activated region $installId")
                // Activating an update (for example when a ride ends) frees its older copy.
                pruneSuperseded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    fun activateBundled() {
        if (refuseSwitchDuringRide()) return
        if (!RegionDatasets.bundledAvailable) {
            _state.update { it.copy(error = "This app has no bundled map data. Install a map package instead.") }
            return
        }
        scope.launch {
            try {
                mapChanges.withLock {
                    if (refuseSwitchDuringRide()) return@launch
                    registry.activateBundled()
                    refreshNow()
                }
                _state.update { it.copy(notice = "Using the bundled Queensland data") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = friendly(e)) }
            }
        }
    }

    /**
     * Deletes an installed map. An inactive map is removed immediately; the
     * active map is switched to the bundled dataset first and deleted only
     * after the old routing graph reports it is drained, so no file is removed
     * from under a live reader. The downloaded `.motomap` the user may have
     * saved in Files is never touched.
     */
    fun requestRemove(installId: String) {
        scope.launch {
            try {
                val released = mapChanges.withLock {
                    if (_state.value.pendingRemoval?.installId == installId) {
                        _state.update { it.copy(notice = "This map is still releasing its readers") }
                        return@launch
                    }
                    val install = registry.find(installId, withSizes = true)
                    if (install == null) {
                        _state.update { it.copy(error = "That map is not installed") }
                        return@launch
                    }
                    val active = registry.active().installId == installId
                    if (active && rideActive) {
                        _state.update { it.copy(error = "Stop the ride before deleting the active map") }
                        return@launch
                    }
                    _state.update { it.copy(busy = true, error = null, notice = null) }
                    if (!active) {
                        // Only the install decided on here: a reinstall racing this keeps its copy.
                        var removed = false
                        if (!readers.removeIfDrained(installId) {
                                removed = registry.removeIfUnchanged(installId, install.installSequence)
                            }
                        ) {
                            _state.update { it.copy(busy = false, error = "This map is still in use. Try deleting it again shortly.") }
                            return@launch
                        }
                        if (!removed && registry.find(installId) != null) {
                            refreshNow()
                            _state.update { it.copy(busy = false, error = "This map was just installed again, so it was kept.") }
                            return@launch
                        }
                        if (pendingRemovalStore.read()?.installId == installId) pendingRemovalStore.clear()
                        refreshNow()
                        _state.update { it.copy(busy = false, notice = removalNotice(install)) }
                        return@launch
                    }
                    if (_state.value.pendingRemoval != null) {
                        _state.update { it.copy(busy = false, error = "Wait for the other map deletion to finish") }
                        return@launch
                    }
                    // Publish the waiter before the new dataset can release old readers.
                    val pending = PendingRemoval(
                        installId, install.regionId, install.regionName, install.bytes, install.installSequence,
                    )
                    pendingRemovalStore.save(pending)
                    val signal = readers.drained(installId)
                    pendingRelease = signal
                    _state.update { it.copy(pendingRemoval = pending) }
                    registry.activateBundled()
                    refreshNow()
                    _state.update {
                        it.copy(
                            busy = false,
                            notice = RegionUiModel.releasingActiveNotice(RegionDatasets.bundledAvailable),
                        )
                    }
                    signal
                }
                if (withTimeoutOrNull(releaseTimeoutMs) { released.await(); true } != true) {
                    mapChanges.withLock {
                        if (pendingRelease === released) {
                            clearPendingRemoval()
                            _state.update { it.copy(error = "The map is still in use. Try deleting it again shortly.") }
                        }
                    }
                    return@launch
                }
                mapChanges.withLock {
                    if (pendingRelease === released) deletePendingRemoval()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    private suspend fun deletePendingRemoval() {
        val pending = _state.value.pendingRemoval ?: return
        // The user may have re-activated the map while we waited.
        if (registry.active().installId == pending.installId) {
            clearPendingRemoval()
            return
        }
        try {
            var removed = false
            val drained = withContext(Dispatchers.IO) {
                readers.removeIfDrained(pending.installId) {
                    removed = registry.removeIfUnchanged(pending.installId, pending.installSequence)
                }
            }
            if (!drained) {
                clearPendingRemoval()
                _state.update { it.copy(error = "The map is still in use. Try deleting it again shortly.") }
                return
            }
            pendingRemovalStore.clear()
            pendingRelease = null
            _state.update { it.copy(pendingRemoval = null) }
            refreshNow()
            // Not removed but still installed: the rider installed it again meanwhile.
            if (removed || registry.find(pending.installId) == null) {
                _state.update { it.copy(notice = removalNotice(pending)) }
            }
            Log.i(TAG, "removed active region ${pending.installId} after draining readers")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            clearPendingRemoval()
            _state.update { it.copy(error = friendly(e)) }
        }
    }

    private fun clearPendingRemoval() {
        pendingRemovalStore.clear()
        pendingRelease = null
        _state.update { it.copy(pendingRemoval = null) }
    }

    private fun refuseSwitchDuringRide(): Boolean {
        if (!rideActive) return false
        _state.update { it.copy(error = "Finish the ride before switching maps") }
        return true
    }

    private fun removalNotice(install: InstalledRegion): String =
        removalNotice(install.regionId, install.regionName)

    private fun removalNotice(pending: PendingRemoval): String =
        removalNotice(pending.regionId, pending.regionName)

    private fun removalNotice(regionId: String, regionName: String): String = buildString {
        append("\"$regionName\" was deleted from this device.")
        append(" Downloaded .motomap files in Files are not touched.")
        _state.value.savedPackage
            ?.takeIf { it.regionId == regionId }
            ?.let { append(" Your saved file \"${it.fileName}\" is still in Files.") }
    }

    /**
     * Recovers a persisted deletion only when the process-wide leases prove
     * the install has no readers. Manager recreation is not process death.
     */
    private fun resumePendingRemoval() {
        val pending = pendingRemovalStore.read() ?: return
        if (registry.active().installId == pending.installId) {
            pendingRemovalStore.clear()
            return
        }
        scope.launch {
            try {
                mapChanges.withLock {
                    // Reactivation may have canceled recovery before this coroutine ran.
                    if (registry.active().installId == pending.installId) {
                        pendingRemovalStore.clear()
                        return@launch
                    }
                    if (!readers.removeIfDrained(pending.installId) {
                            registry.removeIfUnchanged(pending.installId, pending.installSequence)
                        }) {
                        _state.update { it.copy(error = "The map is still in use. Try deleting it again shortly.") }
                        return@launch
                    }
                    pendingRemovalStore.clear()
                    refreshNow()
                    // Still installed: it was installed again after the deletion began.
                    if (registry.find(pending.installId) == null) {
                        _state.update { it.copy(notice = removalNotice(pending)) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "pending removal failed", e)
                _state.update { it.copy(error = friendly(e)) }
            }
        }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    /** Re-reads the registry and active dataset (also called after installs). */
    fun refresh() {
        scope.launch { mapChanges.withLock { refreshNow() } }
    }

    /** Re-reads installs and the active dataset, and returns once both flows reflect them. */
    private suspend fun refreshNow() {
        val installs = withContext(Dispatchers.IO) { registry.list() }
        val record = withContext(Dispatchers.IO) { registry.active() }
        val dataset = withContext(Dispatchers.IO) { registry.activeDataset(appContext) }
        _state.update {
            it.copy(
                serverAddress = settings.serverAddress,
                defaultServer = settings.defaultServer,
                usingDefaultServer = settings.usingDefaultServer,
                installed = installs,
                activeInstallId = record.installId,
                activeRegionName = dataset.displayName,
                failedRemovals = it.failedRemovals.filterTo(mutableSetOf()) { id -> installs.any { i -> i.installId == id } },
            )
        }
        _dataset.value = dataset
    }

    fun close() {
        pollJob?.cancel()
        downloadJob?.cancel()
        downloadCancellation?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    // --- persistence ----------------------------------------------------------

    /**
     * Re-reads the staged download. A complete file from an older flow is
     * offered for save/import instead of being discarded; partial bytes stay
     * resumable; nothing on disk is deleted here.
     */
    private fun loadPersistedDownload() {
        val record = downloadStore.read() ?: return
        val part = downloadStore.partFile(record)
        val complete = downloadStore.destinationFile(record)
        val completeBytes = complete.takeIf { it.isFile }?.length() ?: 0L
        val isComplete = completeBytes > 0 && (record.total == null || completeBytes >= record.total)
        val interrupted = if (!isComplete && part.isFile && part.length() > 0) {
            InterruptedDownload(
                jobId = record.jobId,
                regionId = record.regionId,
                regionName = record.regionName.ifBlank { record.regionId },
                fingerprint = record.fingerprint,
                downloadUrl = record.downloadUrl,
                etag = record.etag,
                bytes = part.length(),
                total = record.total,
                installMode = record.destinationUri == null,
            )
        } else {
            null
        }
        val recovered = if (isComplete) {
            RecoveredPackage(
                regionId = record.regionId,
                regionName = record.regionName.ifBlank { record.regionId },
                fileName = record.fileName,
                bytes = completeBytes,
            )
        } else {
            null
        }
        _state.update { it.copy(interrupted = interrupted, recoveredPackage = recovered) }
    }

    private fun downloadTarget(regionId: String): DownloadTarget? {
        val state = _state.value
        val job = state.activeJob
            ?.takeIf { it.regionId == regionId && it.isCompleted && it.downloadUrl.isNotBlank() }
        if (job != null) {
            return DownloadTarget(
                jobId = job.id,
                regionId = job.regionId,
                regionName = job.regionName.ifBlank { regionId },
                fingerprint = fingerprintFrom(job.downloadUrl),
                size = job.artifactSize,
                sha256 = job.artifactSha256,
                downloadUrl = job.downloadUrl,
                sourceDate = state.catalog.firstOrNull { it.id == regionId }?.sourceDate,
            )
        }
        val region = state.catalog.firstOrNull { it.id == regionId } ?: return null
        val artifact = region.artifact
            ?.takeIf { it.available && it.downloadUrl.isNotBlank() }
            ?: return null
        return DownloadTarget(
            jobId = "",
            regionId = region.id,
            regionName = region.name,
            fingerprint = artifact.fingerprint.ifBlank { fingerprintFrom(artifact.downloadUrl) },
            size = artifact.size,
            sha256 = artifact.sha256,
            downloadUrl = artifact.downloadUrl,
            sourceDate = region.sourceDate,
        )
    }

    private fun RegionDownloadRecord.toServerJob(): ServerJob = ServerJob(
        id = jobId,
        regionId = regionId,
        regionName = regionName,
        state = "completed",
        step = "verify",
        progress = 1.0,
        message = "",
        error = "",
        cached = true,
        artifactSize = total ?: 0,
        artifactSha256 = sha256.orEmpty(),
        downloadUrl = downloadUrl,
    )

    private fun fingerprintFrom(url: String): String =
        Regex("/([0-9a-f]{64})/").find(url)?.groupValues?.get(1).orEmpty()

    private fun friendly(e: Exception): String = when (e) {
        is RegionHttpException -> e.message ?: "Server request failed"
        is RegionPackageException -> e.message ?: "The package could not be installed"
        else -> e.message ?: "Something went wrong"
    }


}
