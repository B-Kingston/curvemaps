package com.curvemaps.app.region

/** One region the server offers, with its cached-artifact status. */
data class ServerRegion(
    val id: String,
    val name: String,
    val coverage: String?,
    val disabled: Boolean,
    val maxZoom: Int,
    val sourceDate: String,
    val sourceSha256: String,
    val sourcePinned: Boolean,
    val artifact: ServerArtifact?,
    val activeJobId: String?,
    /** "free" or "pro" (paid-access hook; nothing is paid yet). */
    val tier: String = "free",
    /** True for regions built from a Geofabrik extract (curated or requested). */
    val requested: Boolean = false,
    val extractId: String? = null,
) {
    val hasCachedArtifact: Boolean get() = artifact?.available == true
    val isPro: Boolean get() = tier == "pro"
}

/** One place the server can build, from its Geofabrik extract search. */
data class ServerExtract(
    val id: String,
    val name: String,
    /** Ancestors, top level first ("Australia and Oceania", "Australia"). */
    val path: List<String>,
    val regionId: String,
    /** Current extract size in bytes (0 when unknown). */
    val bytes: Long,
    val eligible: Boolean,
    /** Why it is not buildable (too large, a continent), when not eligible. */
    val reason: String,
) {
    val parentLabel: String get() = path.lastOrNull().orEmpty()
}

/** A cached package the server can serve immediately. */
data class ServerArtifact(
    val available: Boolean,
    val fingerprint: String,
    val size: Long,
    val sha256: String,
    val generatedAt: String,
    val downloadUrl: String,
)

/** A build request's state as reported by the server. */
data class ServerJob(
    val id: String,
    val regionId: String,
    val regionName: String,
    val state: String,
    val step: String,
    val progress: Double,
    val message: String,
    val error: String,
    val cached: Boolean,
    val artifactSize: Long,
    val artifactSha256: String,
    val downloadUrl: String,
) {
    val isActive: Boolean get() = state == "queued" || state == "running"
    val isTerminal: Boolean get() = !isActive
    val isCompleted: Boolean get() = state == "completed"
    val isFailed: Boolean get() = state == "failed"
    val isCanceled: Boolean get() = state == "canceled"
}
