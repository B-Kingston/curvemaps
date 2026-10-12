package com.curvemaps.app

import com.curvemaps.app.region.MapsState
import com.curvemaps.app.region.VisualMapsScenarios
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug-build-only preview of Maps-screen states for `tools/test/visual.py`.
 *
 * The debug [VisualRouteControlReceiver] shows one of [VisualMapsScenarios]
 * while the Maps screen is open, so intermediate download/import/delete states
 * can be screenshotted deterministically without a live server or a SAF picker.
 * Release builds never write this flow, and the screen falls back to the real
 * [com.curvemaps.app.region.RegionManager] state when it is null.
 */
object VisualMapsPreview {

    const val ACTION = "com.curvemaps.app.DEBUG_VISUAL_MAPS_STATE"

    private val _state = MutableStateFlow<MapsState?>(null)
    val state: StateFlow<MapsState?> = _state.asStateFlow()

    /** Returns false for an unknown scenario so the receiver can log it. */
    fun show(name: String): Boolean {
        val preview = VisualMapsScenarios.state(name) ?: return false
        _state.value = preview
        return true
    }

    fun clear() {
        _state.value = null
    }
}
