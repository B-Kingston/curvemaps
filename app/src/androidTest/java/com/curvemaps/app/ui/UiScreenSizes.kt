package com.curvemaps.app.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * The single UI-size matrix for [CommonScreenSizeTest]: window sizes and font
 * scales are read from the androidTest asset [MATRIX_ASSET], never duplicated
 * in Kotlin. The optional instrumentation arguments let the visual driver run
 * one window/font subset and export PNG evidence per surface.
 *
 * Filters are strict: an unknown case id or font scale fails loudly at
 * parameterisation time instead of silently running the wrong matrix.
 */
object UiScreenSizes {

    /** Comma-separated window ids; empty/absent selects every matrix window. */
    const val ARG_CASES = "uiSizeCases"

    /** Comma-separated font scales; empty/absent selects every matrix scale. */
    const val ARG_FONT_SCALES = "uiSizeFontScales"

    /** `true` exports one PNG + JSON per case/surface under the target cache. */
    const val ARG_CAPTURES = "uiSizeCaptures"

    /** Logcat tag the driver parses for exported frames. */
    const val CAPTURE_LOG_TAG = "CurveMaps.UiSizes"

    /** Test tag of the box that measures exactly the forced virtual window. */
    const val VIEWPORT_TAG = "ui-size-viewport"

    const val MATRIX_ASSET = "ui-screen-sizes.json"

    /** Absolute path fragments of the capture directory contract. */
    const val CAPTURE_DIR = "ui-size-captures"

    data class Window(val id: String, val widthDp: Int, val heightDp: Int)

    data class Case(val window: Window, val fontScale: Float) {
        val id: String get() = window.id
        val widthDp: Int get() = window.widthDp
        val heightDp: Int get() = window.heightDp

        /** Matrix font scale as the label string the capture contract pins. */
        val fontString: String
            get() = if (fontScale == fontScale.toInt().toFloat()) {
                "${fontScale.toInt()}.0"
            } else {
                fontScale.toString()
            }

        /** Stable gallery label: ui-size-{id}-font-{font}-{surface}. */
        fun label(surface: String) = "ui-size-$id-font-$fontString-$surface"

        override fun toString(): String = "$id-font-$fontString"
    }

    fun loadCases(
        arguments: Bundle = InstrumentationRegistry.getArguments(),
    ): List<Case> {
        val (fontScales, windows) = readMatrix()
        val caseFilter = splitFilter(arguments.getString(ARG_CASES))
        val scaleFilter = splitFilter(arguments.getString(ARG_FONT_SCALES))

        val selectedWindows = caseFilter?.map { id ->
            windows.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException(
                    "Unknown $ARG_CASES id \"$id\"; matrix windows: " +
                        windows.joinToString { it.id },
                )
        } ?: windows

        val selectedScales = scaleFilter?.map { raw ->
            val value = raw.toFloatOrNull()
                ?: throw IllegalArgumentException("Invalid $ARG_FONT_SCALES value \"$raw\" (expected a float)")
            fontScales.firstOrNull { it == value }
                ?: throw IllegalArgumentException(
                    "Unknown $ARG_FONT_SCALES value \"$raw\"; matrix scales: $fontScales",
                )
        } ?: fontScales
        require(selectedScales.distinct().size == selectedScales.size) {
            "$ARG_FONT_SCALES contains duplicate scales"
        }

        return selectedScales.flatMap { scale ->
            selectedWindows.map { Case(it, scale) }
        }
    }

    fun capturesEnabled(
        arguments: Bundle = InstrumentationRegistry.getArguments(),
    ): Boolean = arguments.getString(ARG_CAPTURES)?.equals("true", ignoreCase = true) == true

    /** Writes one PNG and its metadata before the surface assertions run. */
    fun exportFrame(
        rule: ComposeTestRule,
        label: String,
        widthDp: Int,
        heightDp: Int,
        fontScale: Float,
        surface: String,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, CAPTURE_DIR)
        check(dir.mkdirs() || dir.isDirectory) { "Cannot create screenshot directory: $dir" }
        val png = File(dir, "$label.png")
        val bitmap = rule.onNodeWithTag(VIEWPORT_TAG).captureToImage().asAndroidBitmap()
        FileOutputStream(png).use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Cannot save screenshot: $png" }
        }
        val json = JSONObject()
            .put("label", label)
            .put("widthDp", widthDp)
            .put("heightDp", heightDp)
            .put("fontScale", fontScale.toDouble())
            .put("surface", surface)
            .put("path", png.absolutePath)
        File(dir, "$label.json").writeText(json.toString())
        Log.i(CAPTURE_LOG_TAG, json.toString())
    }

    private fun splitFilter(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        val values = raw.split(",").map { it.trim() }
        require(values.all { it.isNotEmpty() }) { "A UI size filter contains an empty value: $raw" }
        require(values.distinct().size == values.size) { "A UI size filter contains duplicates: $raw" }
        return values
    }

    private fun readMatrix(): Pair<List<Float>, List<Window>> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val root = JSONObject(assets.open(MATRIX_ASSET).bufferedReader().use { it.readText() })
        val fontScales = root.getJSONArray("fontScales").let { array ->
            List(array.length()) { array.getDouble(it).toFloat() }
        }
        val windows = root.getJSONArray("sizes").let { array ->
            List(array.length()) {
                val entry = array.getJSONObject(it)
                Window(
                    id = entry.getString("id"),
                    widthDp = entry.getInt("widthDp"),
                    heightDp = entry.getInt("heightDp"),
                )
            }
        }
        return fontScales to windows
    }
}
