package com.curvemaps.app.onboarding

import android.content.Context
import com.curvemaps.app.region.RegionDatasets
import com.curvemaps.app.region.RegionRegistry
import java.io.File

/** Remembers that the first-run carousel was finished or skipped. */
object WelcomeStore {
    private const val PREFS = "welcome"
    private const val KEY_COMPLETED = "completed"

    fun isCompleted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_COMPLETED, false)

    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    /** Reads the same active dataset the planner will open (small JSON files; call off the main thread). */
    fun mapStatus(context: Context): WelcomeMapStatus {
        val dataset = runCatching {
            RegionRegistry(File(context.applicationContext.filesDir, "regions"))
                .activeDataset(context.applicationContext)
        }.getOrNull()
        return if (dataset != null) {
            WelcomeMapStatus(
                dataPresent = dataset.dataPresent,
                regionName = if (dataset.isBundled) dataset.coverage ?: dataset.displayName else dataset.displayName,
                tilesInstalled = dataset.tilesFile?.isFile == true,
            )
        } else {
            WelcomeMapStatus(RegionDatasets.bundledAvailable, RegionDatasets.fallbackDisplayName, tilesInstalled = false)
        }
    }
}
