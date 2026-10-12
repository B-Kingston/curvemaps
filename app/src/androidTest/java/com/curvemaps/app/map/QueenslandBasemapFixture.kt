package com.curvemaps.app.map

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Queensland rendering/POI assertions need real Queensland data, not the Monaco camera fixture. */
internal fun queenslandBasemapRule(): OfflineBasemapRule {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return OfflineBasemapRule(
        File(context.filesDir, "tiles/basemap.pmtiles"),
        { File(context.filesDir, "fixtures/queensland.motomap").inputStream() },
    )
}
