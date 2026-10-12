package com.curvemaps.app.geocoding

/** Asynchronous offline place-search boundary used by route-planning fields. */
interface GeocodeController {
    suspend fun search(query: String, limit: Int = 8): List<GeocodeResult>
}
