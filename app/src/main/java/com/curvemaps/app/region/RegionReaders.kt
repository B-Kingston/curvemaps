package com.curvemaps.app.region

import kotlinx.coroutines.CompletableDeferred

/** Process-wide reader leases survive Activity and manager recreation. */
internal class RegionReaders {
    private data class Readers(var count: Int, val drained: CompletableDeferred<Unit>)
    private val readers = HashMap<String, Readers>()

    @Synchronized
    fun retain(installId: String?): () -> Unit {
        if (installId == null) return {}
        val entry = readers.getOrPut(installId) { Readers(0, CompletableDeferred()) }
        entry.count++
        var released = false
        return {
            synchronized(this) {
                if (!released) {
                    released = true
                    if (--entry.count == 0) {
                        readers.remove(installId)
                        entry.drained.complete(Unit)
                    }
                }
            }
        }
    }

    /** The final check and unlink are atomic with respect to new reader leases. */
    @Synchronized
    fun removeIfDrained(installId: String, remove: () -> Unit): Boolean {
        if (readers.containsKey(installId)) return false
        remove()
        return true
    }

    @Synchronized
    fun drained(installId: String): CompletableDeferred<Unit> =
        readers[installId]?.drained ?: CompletableDeferred(Unit)
}
