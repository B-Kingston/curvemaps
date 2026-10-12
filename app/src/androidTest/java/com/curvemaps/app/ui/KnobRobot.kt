package com.curvemaps.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import com.curvemaps.app.RideStyleSliderGeometry
import kotlin.math.abs

/** Shared four-stop slider driver. The historical name keeps persisted fuzz actions compatible. */
object KnobRobot {

    const val LEVEL_DESCRIPTION_PREFIX = "Ride style level"

    /** The last ride-style stop (Max twisty). */
    val MAX_LEVEL: Int = com.curvemaps.app.routing.RideStyle.MAX_LEVEL

    /** Level-verification window per click; generous for emulator hiccups. */
    private const val CLICK_SETTLE_MS = 8_000L

    private const val MAX_ATTEMPTS_PER_CLICK = 3

    const val STEPS_PER_CLICK = 16
    private const val EVENT_TIME_MS = 20L

    fun matcher(): SemanticsMatcher =
        hasContentDescription(LEVEL_DESCRIPTION_PREFIX, substring = true)

    /**
     * Slides [clicks] detents, verifying each individual click registers.
     *
     * [clampAtEnds] declares the slider's hard stops (Fastest and [MAX_LEVEL]):
     * targets past either end are considered satisfied once the slider reports
     * that end (the gesture is still injected every iteration, hammering the
     * stop).
     */
    fun performDetentClicks(rule: ComposeTestRule, clicks: Int, clampAtEnds: Boolean = false) {
        if (clicks == 0) return
        ensureIdleControlsVisible(rule)
        val direction = if (clicks > 0) 1 else -1
        var achieved = currentLevel(rule) ?: 0
        repeat(abs(clicks)) {
            val wanted = achieved + direction
            var attempts = 0
            while (true) {
                attempts++
                slideWithGesture(knobInteraction(rule), direction, STEPS_PER_CLICK)
                rule.waitForIdle()
                val reachedWanted = wanted in 0..MAX_LEVEL && expectLevelWithin(rule, wanted, CLICK_SETTLE_MS)
                val clampedAtEnd = clampAtEnds && wanted !in 0..MAX_LEVEL &&
                    currentLevel(rule) == wanted.coerceIn(0, MAX_LEVEL)
                if (reachedWanted || clampedAtEnd) break
                check(attempts < MAX_ATTEMPTS_PER_CLICK) {
                    "detent click $attempts toward $wanted failed; slider reports ${currentLevel(rule)}"
                }
            }
            // After a clamped click the slider sits exactly on the end stop no
            // matter how far past it the running target gets.
            achieved = if (clampAtEnds) wanted.coerceIn(0, MAX_LEVEL) else wanted
        }
    }

    /**
     * Crosses [detents] clicks inside one continuous drag (fuzz-executor
     * style). Best-effort by contract: the fuzz campaign treats unexpected
     * intermediate states as signal, so no per-click verification here.
     */
    fun performContinuousDrag(rule: ComposeTestRule, detents: Int) {
        if (detents == 0 || !present(rule)) return
        ensureIdleControlsVisible(rule)
        slideWithGesture(
            knobInteraction(rule),
            if (detents > 0) 1 else -1,
            abs(detents) * STEPS_PER_CLICK,
        )
        rule.waitForIdle()
    }

    /**
     * Fetches the slider interaction, tolerating the brief windows where the
     * release-spring settle or a row recomposition makes a one-shot strict
     * lookup miss. Verified semantics come afterwards via the level checks.
     */
    private fun knobInteraction(rule: ComposeTestRule): androidx.compose.ui.test.SemanticsNodeInteraction {
        val deadline = android.os.SystemClock.elapsedRealtime() + CLICK_SETTLE_MS
        var lastDump = "<none>"
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val found = rule.onAllNodes(matcher(), useUnmergedTree = true).fetchSemanticsNodes()
            if (found.isNotEmpty()) return rule.onNode(matcher())
            lastDump = describeRoot(rule)
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        error("knobInteraction: slider never appeared within ${CLICK_SETTLE_MS}ms; tree had:\n$lastDump")
    }

    /** Compact structural snapshot for diagnosing where the slider went. */
    private fun describeRoot(rule: ComposeTestRule): String {
        val root = runCatching {
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        }.getOrElse { return "<no root: ${it.message}>" }
        val descriptions = mutableListOf<String>()
        fun visit(node: androidx.compose.ui.semantics.SemanticsNode, depth: Int) {
            val desc = node.config.getOrElse(
                androidx.compose.ui.semantics.SemanticsProperties.ContentDescription,
            ) { emptyList() }.joinToString("|")
            val text = node.config.getOrElse(
                androidx.compose.ui.semantics.SemanticsProperties.Text,
            ) { emptyList() }.joinToString("|") { it.text }
            if (desc.isNotEmpty() || text.isNotEmpty()) {
                descriptions += " ".repeat(depth) + "[$desc][$text]"
            }
            if (descriptions.size < 80) node.children.forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        return descriptions.joinToString("\n")
    }

    /**
     * Waits until the slider reports exactly [level]. On timeout, fails with
     * every slider description currently in the tree so callers see the actual
     * level reached instead of an opaque timeout.
     */
    fun waitForLevel(rule: ComposeTestRule, level: Int, timeoutMs: Long = CLICK_SETTLE_MS) {
        check(expectLevelWithin(rule, level, timeoutMs)) {
            "waitForLevel($level) timed out; slider reported " +
                rule.onAllNodes(matcher(), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .mapNotNull(::describedLevel)
        }
    }

    private fun expectLevelWithin(rule: ComposeTestRule, level: Int, timeoutMs: Long): Boolean {
        // Manual interval probing instead of rule.waitUntil: the compose-idle
        // synchronization behind waitUntil can force measure/layout while a
        // map draw pass is in flight (framework-level IAE); a relaxed poll
        // never interleaves with traversal.
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var sawLevel = false
        while (android.os.SystemClock.elapsedRealtime() <= deadline) {
            if (!sawLevel && rule.onAllNodes(hasContentDescription("$LEVEL_DESCRIPTION_PREFIX $level"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                sawLevel = true
            }
            if (sawLevel) return true
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return sawLevel
    }

    /** Parses the numeric part of the first matching slider description, or null. */
    fun currentLevel(rule: ComposeTestRule): Int? {
        val node = rule.onAllNodes(matcher(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull() ?: return null
        return describedLevel(node)
    }

    fun describedLevel(node: SemanticsNode): Int? {
        val description = node.config.getOrElse(
            androidx.compose.ui.semantics.SemanticsProperties.ContentDescription,
        ) { emptyList() }.firstOrNull() ?: return null
        return description.substringAfterLast(' ').toIntOrNull()
    }

    /**
     * The slider remains in the ride-controls box while a planner field is
     * focused. Search suggestions render in a popup above the field, so
     * typing does not remove the slider. Dismissing the IME clears focus and
     * closes the popup.
     */
    private fun ensureIdleControlsVisible(rule: ComposeTestRule) {
        if (!present(rule)) {
            runCatching {
                rule.onNodeWithContentDescription("To").performImeAction()
            }.recoverCatching {
                rule.onNodeWithContentDescription("From").performImeAction()
            }
        }
        rule.waitForIdle()
        // Relaxed presence probe (no rule.waitUntil — see expectLevelWithin).
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        while (!present(rule)) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) {
                "slider never became visible; screen state=${describeRoot(rule)}"
            }
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                error("interrupted while waiting for the slider")
            }
        }
    }

    // No runCatching-swallow here: a throwing query must surface as itself,
    // not silently read as 'slider absent' forever.
    private fun present(rule: ComposeTestRule): Boolean =
        rule.onAllNodes(matcher(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun slideWithGesture(
        slider: androidx.compose.ui.test.SemanticsNodeInteraction,
        direction: Int,
        steps: Int,
    ) {
        moveSlide(slider, direction, steps)
        slider.performTouchInput { up() }
    }

    /**
     * Presses the handle and moves [steps] steps toward [direction], leaving
     * the finger down. Compose batches each block's events and continues an
     * unfinished gesture in the next block, so a caller can inspect the live
     * mid-drag state and then end the one continuous drag with [finishHeldDrag].
     */
    private fun moveSlide(
        slider: androidx.compose.ui.test.SemanticsNodeInteraction,
        direction: Int,
        steps: Int,
    ) {
        val node = slider.fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val level = describedLevel(node) ?: error("slider has no level")
        val inset = com.curvemaps.app.RIDE_STYLE_SLIDER_END_INSET.value * (bounds.height / 64f)
        val span = bounds.width - 2 * inset
        slider.performTouchInput {
            val start = Offset(inset + span * RideStyleSliderGeometry.fractionAtLevel(level.toFloat()), bounds.height / 2f)
            down(start)
            advanceEventTime(EVENT_TIME_MS)
            repeat(steps) { step ->
                moveTo(start + Offset(direction * span / MAX_LEVEL * (step + 1) / STEPS_PER_CLICK, 0f))
                advanceEventTime(EVENT_TIME_MS)
            }
        }
    }

    /**
     * Starts a detent drag and keeps the finger down, so the mid-drag route
     * selection can be observed before any release. End it with
     * [finishHeldDrag]; never wait for Compose idleness in between (the wave
     * animates while the drag is held).
     */
    fun startHeldDrag(rule: ComposeTestRule, detents: Int) {
        if (detents == 0) return
        ensureIdleControlsVisible(rule)
        moveSlide(knobInteraction(rule), if (detents > 0) 1 else -1, abs(detents) * STEPS_PER_CLICK)
    }

    /** Ends the gesture left open by [startHeldDrag], releasing or cancelling it. */
    fun finishHeldDrag(rule: ComposeTestRule, cancelGesture: Boolean = false) {
        val slider = knobInteraction(rule)
        slider.performTouchInput { if (cancelGesture) cancel() else up() }
        rule.waitForIdle()
    }

    /** Partial drag, optionally cancelled, for snap and interruption assertions. */
    fun performPartialDrag(rule: ComposeTestRule, target: Float, cancelGesture: Boolean = false) {
        val slider = knobInteraction(rule)
        val node = slider.fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val level = describedLevel(node) ?: error("slider has no level")
        val inset = com.curvemaps.app.RIDE_STYLE_SLIDER_END_INSET.value * (bounds.height / 64f)
        val span = bounds.width - 2 * inset
        slider.performTouchInput {
            val start = Offset(inset + span * RideStyleSliderGeometry.fractionAtLevel(level.toFloat()), bounds.height / 2f)
            val end = Offset(inset + span * RideStyleSliderGeometry.fractionAtLevel(target), bounds.height / 2f)
            down(start)
            repeat(STEPS_PER_CLICK) { step ->
                moveTo(start + (end - start) * ((step + 1f) / STEPS_PER_CLICK))
                advanceEventTime(EVENT_TIME_MS)
            }
            if (cancelGesture) cancel() else up()
        }
        rule.waitForIdle()
    }
}
