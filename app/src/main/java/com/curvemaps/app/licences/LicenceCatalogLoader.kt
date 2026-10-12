package com.curvemaps.app.licences

import android.content.Context
import com.curvemaps.app.R
import java.io.FileNotFoundException

/**
 * Reads the licence data bundled in this APK: `res/raw/aboutlibraries.json`
 * (AboutLibraries plugin, per variant) and `assets/licences/notices.json`
 * (`collect<Variant>LicenceNotices`). Nothing is fetched; call off the main
 * thread.
 */
object LicenceCatalogLoader {
    const val NOTICES_ASSET = "licences/notices.json"

    fun load(context: Context): LicenceCatalog {
        val definitions = context.resources.openRawResource(R.raw.aboutlibraries)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val notices = try {
            context.assets.open(NOTICES_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        }
        return LicenceCatalogParser.parse(definitions, notices)
    }
}
