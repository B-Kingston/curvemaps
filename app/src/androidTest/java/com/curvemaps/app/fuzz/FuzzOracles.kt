package com.curvemaps.app.fuzz

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import com.curvemaps.app.AVOID_STOPS_AND_TOWNS_TITLE
import com.curvemaps.app.INCLUDE_ELEVATION_TITLE
import com.curvemaps.app.MapReadyKey
import com.curvemaps.app.MediaPanelOpenKey
import com.curvemaps.app.RouteUiStateKey
import com.curvemaps.app.ROUND_TRIP_BACK_BY_OPTION
import com.curvemaps.app.ROUND_TRIP_DISTANCE_OPTION
import com.curvemaps.app.ROUND_TRIP_LENGTH_DESCRIPTION
import com.curvemaps.app.ROUND_TRIP_MENU_TITLE
import com.curvemaps.app.ROUND_TRIP_TIME_OPTION
import com.curvemaps.app.ROUND_TRIP_TOGGLE_DESCRIPTION
import androidx.compose.ui.state.ToggleableState

/** Oracle checks for the route screen fuzz campaign. */
class FuzzOracles(private val rule: ComposeTestRule) {

    fun hardFailures(state: StateFingerprint, loadingSteps: Int, loadingGeneration: Int?): List<String> {
        if (!hasComposeHierarchy()) return listOf("NoComposeHierarchy: route screen disappeared")
        val failures = mutableListOf<String>()
        rule.onAllNodes(hasContentDescription("Ride style level", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().forEach { slider ->
                val range = if (slider.config.contains(SemanticsProperties.ProgressBarRangeInfo))
                    slider.config[SemanticsProperties.ProgressBarRangeInfo] else null
                val minimum = 48f * rule.density.density
                val top = com.curvemaps.app.routing.RideStyle.MAX_LEVEL.toFloat()
                if (range == null || range.range != 0f..top || range.steps != com.curvemaps.app.routing.RideStyle.MAX_LEVEL - 1 ||
                    range.current !in 0f..top || range.current % 1f != 0f ||
                    !slider.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetProgress)) {
                    failures += "RideStyleAdjustable: slider lost its seven-stop accessible range"
                }
                if (slider.boundsInRoot.width < minimum || slider.boundsInRoot.height < minimum) {
                    failures += "RideStyleTargetSize: slider smaller than 48 dp"
                }
            }
        if (state.stateName == "loading" && loadingSteps > 20 && loadingGeneration == state.generation) {
            failures += "NoPermanentLoading: loading persisted for $loadingSteps steps at generation ${state.generation}"
        }
        if (state.routeCount > 0) {
            if (state.selectedIndex != state.focusedRouteIndex) {
                failures += "CarouselMatchesMap: selected=${state.selectedIndex} focused=${state.focusedRouteIndex}"
            }
            if (state.routeCount != state.mapRouteCount) {
                failures += "CarouselMatchesMap: cards=${state.routeCount} map=${state.mapRouteCount}"
            }
        }
        val blockingDialogs = listOf(
            hasText("Delete saved route?"),
            hasText("Route settings"),
        ).sumOf { matcher ->
            // Count only nodes inside a dialog: the settings menu also shows a
            // "Route settings" row, which is not a blocking dialog.
            rule.onAllNodes(
                matcher.and(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size
        }
        if (blockingDialogs > 1) failures += "NoDuplicateBlockingDialogs: $blockingDialogs dialogs"

        // The planner's action button is intentionally disabled while a route
        // request is in flight (it still reads START and reports the loading
        // state), so reachability is only asserted once the planner settles.
        // With a route ready the same button reads RIDE, so either label
        // satisfies "the planner always offers its next action".
        if (state.stateName != "loading") {
            val startOrEnd = if (state.guidanceActive) {
                rule.onAllNodes(hasContentDescription("End navigation").and(hasClickAction()))
            } else {
                rule.onAllNodes(
                    hasText("START").or(hasText("RIDE")).and(hasClickAction()),
                )
            }
            if (startOrEnd.fetchSemanticsNodes().isEmpty()) {
                failures += if (state.guidanceActive) {
                    "EndNavigationReachable: END has no click action"
                } else {
                    "StartReachable: planner action (START/RIDE) has no click action"
                }
            }
        }
        val darkMapControls = rule.onAllNodes(
            hasContentDescription("Dark ride map", substring = true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        if (state.guidanceActive) {
            if (darkMapControls.size != 1) {
                failures += "DarkRideMapAccessible: expected one guidance toggle, found ${darkMapControls.size}"
            } else {
                val darkMapNode = darkMapControls.single()
                val clickableToggle = rule.onAllNodes(
                    hasContentDescription("Dark ride map", substring = true).and(hasClickAction()),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                if (clickableToggle.isEmpty()) {
                    failures += "DarkRideMapAccessible: toggle has no click action"
                }
                val minSizePx = 64f * rule.density.density
                if (darkMapNode.boundsInRoot.width < minSizePx ||
                    darkMapNode.boundsInRoot.height < minSizePx
                ) {
                    failures += "DarkRideMapAccessible: target smaller than 64 dp"
                }
                val expectedDescription = if (state.darkRideMapEnabled) {
                    "Dark ride map, on"
                } else {
                    "Dark ride map, off"
                }
                val descriptions = darkMapNode.config.getOrElse(
                    SemanticsProperties.ContentDescription,
                ) { emptyList() }
                if (expectedDescription !in descriptions) {
                    failures += "DarkRideMapStateVisible: expected $expectedDescription, found $descriptions"
                }
            }
        } else if (darkMapControls.isNotEmpty()) {
            failures += "DarkRideMapGuidanceOnly: toggle visible outside guidance"
        }
        if (state.guidanceActive) {
            val soundControls = rule.onAllNodes(
                hasContentDescription("Voice guidance settings", substring = true).and(hasClickAction()),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            val gloveSize = 64f * rule.density.density
            if (soundControls.size != 1) {
                failures += "RideSoundAccessible: expected one sound control, found ${soundControls.size}"
            } else {
                val sound = soundControls.single().boundsInRoot
                if (sound.width < gloveSize || sound.height < gloveSize) {
                    failures += "RideSoundTargetSize: target smaller than 64 dp"
                }
                darkMapControls.singleOrNull()?.boundsInRoot?.let { dark ->
                    if (sound.overlaps(dark)) {
                        failures += "RideSettingsTargetsSeparate: sound and B&W targets overlap"
                    }
                }
            }
            // A pending scenic diversion offers Take and Skip as separate
            // 64 dp glove targets (riding the turn accepts it too).
            val suggestionTargets = listOf("Take scenic diversion", "Skip scenic diversion").mapNotNull { description ->
                rule.onAllNodes(hasContentDescription(description).and(hasClickAction()), useUnmergedTree = true)
                    .fetchSemanticsNodes().singleOrNull()?.let { description to it.boundsInRoot }
            }
            suggestionTargets.forEach { (description, bounds) ->
                if (bounds.width < gloveSize || bounds.height < gloveSize) {
                    failures += "RideSuggestionTargetSize: $description is smaller than 64 dp"
                }
            }
            if (suggestionTargets.size == 2 && suggestionTargets[0].second.overlaps(suggestionTargets[1].second)) {
                failures += "RideSuggestionTargetsSeparate: Take and Skip overlap"
            }
            val recenterNodes = rule.onAllNodes(
                hasContentDescription("Rider lock", substring = true).and(hasClickAction()),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            if (state.mediaPanelOpen) {
                if (recenterNodes.isNotEmpty()) {
                    failures += "MediaPanelHidesMapControls: rider lock visible behind the media panel"
                }
            } else if (recenterNodes.isEmpty()) {
                failures += "RideCameraLockAccessible: lock action missing during guidance"
            } else if (recenterNodes.none {
                    it.boundsInRoot.width >= 48f * rule.density.density &&
                        it.boundsInRoot.height >= 48f * rule.density.density
                }
            ) {
                failures += "RideRecenterTargetSize: recenter action is smaller than 48 dp"
            }
        }
        if (state.settingsMenuOpen && rule.onAllNodes(hasText("OpenMapTiles.org", substring = true)).fetchSemanticsNodes().isEmpty()) {
            failures += "SettingsAttributionVisible: attribution is missing"
        }
        val mediaTargetPx = 64f * rule.density.density
        val mediaPanelOpen = rule.onAllNodes(
            SemanticsMatcher.expectValue(MediaPanelOpenKey, true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()
        if (state.guidanceActive) {
            val openers = rule.onAllNodes(
                hasContentDescription("Media controls").and(hasClickAction()),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            if (openers.isEmpty()) {
                failures += "MediaOpenerAccessible: media opener missing during guidance"
            } else if (openers.none {
                    it.boundsInRoot.width >= mediaTargetPx && it.boundsInRoot.height >= mediaTargetPx
                }
            ) {
                failures += "MediaOpenerTargetSize: media opener is smaller than the 64 dp glove target"
            }
        } else if (rule.onAllNodes(hasContentDescription("Media controls"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            failures += "MediaOpenerGuidanceOnly: media opener visible outside guidance"
        }
        if (mediaPanelOpen) {
            if (rule.onAllNodes(hasContentDescription("Enable notification access"))
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                failures += "MediaPanelNoPermissionPrompt: setup must stay outside the ride panel"
            }
            val timers = rule.onAllNodes(
                hasContentDescription("Media controls inactivity timer"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            if (timers.isEmpty()) {
                failures += "MediaInactivityTimerPresent: open panel has no timer"
            }
            if (!state.guidanceActive) {
                failures += "MediaPanelGuidanceOnly: panel visible outside guidance"
            }
            if (rule.onAllNodes(hasContentDescription("Media control center"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
            ) {
                failures += "MediaPanelVisible: panel state says open but the panel is missing"
            }
            // Every transport/volume target is a 64 dp glove target, and the
            // play/pause control is 80 dp. There is always at least one
            // play/pause direction: the unknown-state panel shows both.
            val gloveTargets = listOf(
                "Close media controls",
                "Previous track",
                "Next track",
            )
            gloveTargets.forEach { description ->
                val nodes = rule.onAllNodes(
                    hasContentDescription(description).and(hasClickAction()),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                if (nodes.isEmpty()) {
                    failures += "MediaPanelControlReachable: missing clickable $description"
                } else if (nodes.none {
                        it.boundsInRoot.width >= mediaTargetPx &&
                            it.boundsInRoot.height >= mediaTargetPx
                    }
                ) {
                    failures += "MediaPanelTargetSize: $description is smaller than 64 dp"
                }
            }
            // Volume steps are two separate glove-sized round buttons either side
            // of the level slider: each is at least 64 dp and they never touch. Layout
            // size and position, not clipped bounds: a bounded landscape panel
            // may have scrolled them partly out of view.
            val volumeSteps = listOf("Volume down, ", "Volume up, ").map { prefix ->
                rule.onAllNodes(
                    hasContentDescription(prefix, substring = true).and(hasClickAction()),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
            }
            volumeSteps.flatten().forEach { node ->
                if (node.size.height < mediaTargetPx || node.size.width < mediaTargetPx) {
                    failures += "MediaVolumeTargetSize: a volume step is smaller than 64 dp"
                }
            }
            val down = volumeSteps[0].singleOrNull()
            val up = volumeSteps[1].singleOrNull()
            if (down != null && up != null &&
                up.positionInRoot.x - (down.positionInRoot.x + down.size.width) < 12f * rule.density.density - 1f
            ) {
                failures += "MediaVolumeSeparation: the volume steps are closer than 12 dp"
            }
            // External app launches stay out of semantic fuzz actions. Check their
            // accessible glove targets whenever installed players are present.
            listOf("Start music in ", "Open music app ", "Choose music app ",
                "Show music apps", "Hide music apps", "Change music app").forEach { prefix ->
                rule.onAllNodes(hasContentDescription(prefix, substring = true), useUnmergedTree = true)
                    .fetchSemanticsNodes().forEach { node ->
                        if (node.boundsInRoot.width < mediaTargetPx || node.boundsInRoot.height < mediaTargetPx) {
                            failures += "MusicAppTargetSize: installed-player control is smaller than 64 dp"
                        }
                    }
            }
            val starts = rule.onAllNodes(hasContentDescription("Start music in ", substring = true),
                useUnmergedTree = true).fetchSemanticsNodes()
            val opens = rule.onAllNodes(hasContentDescription("Open music app ", substring = true),
                useUnmergedTree = true).fetchSemanticsNodes()
            if (starts.size > 1 || opens.size > 1) {
                failures += "MusicAppClutter: only one chosen app may expose Start/Open"
            }
            val collapsed = rule.onAllNodes(hasContentDescription("Show music apps"),
                useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            val choices = rule.onAllNodes(hasContentDescription("Choose music app ", substring = true),
                useUnmergedTree = true).fetchSemanticsNodes()
            if (collapsed && (starts.isNotEmpty() || opens.isNotEmpty() || choices.isNotEmpty())) {
                failures += "MusicAppDisclosure: collapsed picker must hide app names and actions"
            }
            val playPausePx = 80f * rule.density.density
            val directions = listOf("Play", "Pause").flatMap { description ->
                rule.onAllNodes(
                    hasContentDescription(description).and(hasClickAction()),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
            }
            if (directions.isEmpty()) {
                failures += "MediaPanelTransportReachable: no play/pause target"
            } else if (directions.none {
                    it.boundsInRoot.width >= playPausePx && it.boundsInRoot.height >= playPausePx
                }
            ) {
                failures += "MediaPanelPlayPauseTargetSize: play/pause is smaller than 80 dp"
            }
        }
        if (state.voiceSettingsOpen) {
            listOf("Spoken turn guidance", "Announcement interval", "Preview next").forEach { description ->
                if (rule.onAllNodes(
                        androidx.compose.ui.test.hasContentDescription(description, substring = true),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes().isEmpty()
                ) {
                    failures += "VoiceSettingsAccessible: missing $description control"
                }
            }
        }
        // The Maps screen must keep its catalog/import controls reachable and
        // glove-sized whenever the campaign opens it. The check uses the
        // node's layout size, not its clipped on-screen bounds: the screen
        // scrolls, so a control below the fold is still a full-size target.
        if (state.mapsSettingsOpen) {
            val mapsTargetPx = 48f * rule.density.density
            // The primary service button reads Browse maps until the user
            // connects once, then Refresh maps.
            val serviceButton = if (
                rule.onAllNodes(hasContentDescription("Refresh maps"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            ) "Refresh maps" else "Browse maps"
            listOf(serviceButton, "Import package file").forEach { description ->
                val nodes = rule.onAllNodes(
                    hasContentDescription(description),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                if (nodes.isEmpty()) {
                    failures += "MapsControlsVisible: missing $description"
                } else if (nodes.none {
                        it.size.width >= mapsTargetPx && it.size.height >= mapsTargetPx
                    }
                ) {
                    failures += "MapsControlsTargetSize: $description is smaller than 48 dp"
                }
            }
            // The bundled fallback is always listed and can never be deleted;
            // the campaign cannot install a regional package (no server, no SAF
            // picker), so the rubbish bin is covered by MapsSettingsScreenTest
            // and MapsDownloadFlowOnDeviceTest instead.
            val bundledRowListed = rule.onAllNodes(hasText("Queensland (bundled)"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
            if (com.curvemaps.app.region.RegionDatasets.bundledAvailable && !bundledRowListed) {
                failures += "MapsBundledRow: missing the bundled fallback entry"
            }
            // A build that ships no data must not advertise a bundled map.
            if (!com.curvemaps.app.region.RegionDatasets.bundledAvailable && bundledRowListed) {
                failures += "MapsBundledRow: a build without bundled data lists a bundled map"
            }
        }
        // Every screen opened from the settings menu exits through the shared
        // back button; it stays a full 48 dp target.
        val minimumTargetPx = 48f * rule.density.density
        val routeSettingsOpen = rule.onAllNodes(
            hasContentDescription("Avoid motorways"),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()
        // Open-source licences: the bundled list must load (never an error), and
        // every visible row stays a full 48 dp target naming its licence.
        if (state.licencesOpen) {
            if (state.licencesState == "error") {
                failures += "LicencesLoad: the bundled licence list failed to load"
            }
            if (state.licencesState == "list") {
                val rows = rule.onAllNodes(
                    hasClickAction().and(
                        androidx.compose.ui.test.hasAnyAncestor(hasContentDescription("Open-source licence list")),
                    ),
                ).fetchSemanticsNodes().filterNot { node ->
                    // The header's Back button scrolls with the list.
                    "Back" in node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                }
                if (rows.isEmpty()) failures += "LicencesListed: the licence list shows no rows"
                rows.forEach { row ->
                    if (row.size.width < minimumTargetPx || row.size.height < minimumTargetPx) {
                        failures += "LicencesTargetSize: a licence row is smaller than 48 dp"
                    }
                    val description = row.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                    if (description.none { it.contains(", ") }) {
                        failures += "LicencesRowLabelled: a licence row does not name its licence ($description)"
                    }
                }
            }
        }
        if (state.voiceSettingsOpen || routeSettingsOpen || state.mapsSettingsOpen || state.licencesOpen) {
            val backButtons = rule.onAllNodes(
                hasContentDescription("Back").and(hasClickAction()),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            if (backButtons.isEmpty()) {
                failures += "SettingsScreenBackReachable: missing clickable Back"
            } else if (backButtons.none {
                    // Layout size, not clipped bounds: the Maps screen scrolls,
                    // so its header can be partially out of view without the
                    // 48 dp target contract being broken.
                    it.size.width >= minimumTargetPx && it.size.height >= minimumTargetPx
                }
            ) {
                failures += "SettingsScreenBackTargetSize: Back is smaller than 48 dp"
            }
        }
        // Route settings' ride-factor tick boxes: whole-row toggles, labelled
        // and at least 48 dp tall (layout size: the dialog scrolls).
        if (routeSettingsOpen) {
            listOf(INCLUDE_ELEVATION_TITLE, AVOID_STOPS_AND_TOWNS_TITLE).forEach { title ->
                val rows = rule.onAllNodes(
                    hasContentDescription(title).and(hasClickAction()),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                if (rows.isEmpty()) {
                    failures += "RideFactorAccessible: missing clickable $title"
                } else if (rows.none {
                        SemanticsProperties.ToggleableState in it.config &&
                            it.size.height >= minimumTargetPx
                    }
                ) {
                    failures += "RideFactorTargetSize: $title is not a 48 dp tick box"
                }
            }
        }
        // Round trip: a labelled 48 dp toggle beside To, and To and the
        // length row never at once (the row only while the toggle is on).
        if (!state.guidanceActive) {
            val toggles = rule.onAllNodes(
                hasContentDescription(ROUND_TRIP_TOGGLE_DESCRIPTION).and(hasClickAction()),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            toggles.forEach { toggle ->
                if (SemanticsProperties.ToggleableState !in toggle.config) {
                    failures += "RoundTripToggleState: the round-trip toggle does not say whether it is on"
                }
                if (toggle.size.width < minimumTargetPx || toggle.size.height < minimumTargetPx) {
                    failures += "RoundTripTargetSize: the round-trip toggle is smaller than 48 dp"
                }
            }
            // The length is a status card (not a control) that shows with the
            // stepper and the measure selector, only while the toggle is on.
            val lengthRows = rule.onAllNodes(
                hasContentDescription(ROUND_TRIP_LENGTH_DESCRIPTION, substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            lengthRows.forEach { row ->
                if (row.size.height < minimumTargetPx) {
                    failures += "RoundTripTargetSize: the round-trip length is shorter than 48 dp"
                }
            }
            if (lengthRows.isNotEmpty()) {
                if (rule.onAllNodes(hasContentDescription("To"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
                    failures += "RoundTripExclusive: To and the round-trip length show at once"
                }
                if (toggles.none { it.config.getOrElse(SemanticsProperties.ToggleableState) { ToggleableState.Off } == ToggleableState.On }) {
                    failures += "RoundTripState: the round-trip length shows while the toggle is off"
                }
            }
            // The measure selector is part of the round trip's settings: it
            // never shows without the length, and each segment is a 48 dp target.
            val selector = rule.onAllNodes(hasContentDescription(ROUND_TRIP_MENU_TITLE)).fetchSemanticsNodes()
            if (selector.isNotEmpty() && lengthRows.isEmpty()) {
                failures += "RoundTripSelectorOrphan: the measure selector shows without the round-trip length"
            }
            if (lengthRows.isNotEmpty()) {
                listOf(ROUND_TRIP_TIME_OPTION, ROUND_TRIP_DISTANCE_OPTION, ROUND_TRIP_BACK_BY_OPTION).forEach { option ->
                    val nodes = rule.onAllNodes(hasText(option).and(hasClickAction())).fetchSemanticsNodes()
                    if (nodes.none { it.size.height >= minimumTargetPx }) {
                        failures += "RoundTripSelectorTargetSize: $option is not a 48 dp segment"
                    }
                }
            }
        }
        if (!state.guidanceActive) {
            // The Maps and Open-source licences pages are full-screen; the planner
            // toolbar is hidden while they are open (it would cover their headers).
            val fullScreenPage = state.mapsSettingsOpen || state.licencesOpen
            listOf("Planning settings", "Saved routes", "Use current location")
                .filterNot { fullScreenPage && it != "Use current location" }
                .forEach { description ->
                val railButtons = rule.onAllNodes(
                    hasContentDescription(description).and(hasClickAction()),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                if (railButtons.isEmpty()) {
                    failures += "PlannerActionsAccessible: missing clickable $description"
                } else if (railButtons.none {
                        it.boundsInRoot.width >= minimumTargetPx &&
                            it.boundsInRoot.height >= minimumTargetPx
                    }
                ) {
                    failures += "PlannerActionsTargetSize: $description is smaller than 48 dp"
                }
            }
            val settingsItems = listOf(
                "Route settings",
                "Load map file",
                "Import GPX route",
                "Sound settings",
                "Open-source licences",
            )
            if (state.settingsMenuOpen) {
                settingsItems.forEach { label ->
                    val nodes = rule.onAllNodes(
                        hasText(label).and(hasClickAction()),
                    ).fetchSemanticsNodes()
                    if (nodes.isEmpty()) {
                        failures += "SettingsMenuAccessible: missing clickable $label"
                    } else if (nodes.none {
                            it.boundsInRoot.width >= minimumTargetPx &&
                                it.boundsInRoot.height >= minimumTargetPx
                        }
                    ) {
                        failures += "SettingsMenuTargetSize: $label is smaller than 48 dp"
                    }
                }
            } else if (listOf("Load map file", "Import GPX route", "Sound settings").any { label ->
                    rule.onAllNodes(hasText(label), useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
            ) {
                failures += "SettingsMenuVisibility: menu items visible while menu is closed"
            }
        }
        return failures
    }

    private fun hasComposeHierarchy(): Boolean =
        runCatching {
            rule.onAllNodes(
                SemanticsMatcher.keyIsDefined(RouteUiStateKey),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)

    fun softWarnings(state: StateFingerprint): List<String> = buildList {
        if (state.stateName == "success" && state.mapInstalled && !state.mapReady) {
            add("MapReadyBeforeRouteRender: route success before map readiness")
        }
        // Only meaningful when a basemap is installed: without tiles there is
        // no style to load, so "not loaded yet" would be a false alarm.
        if (state.guidanceActive && state.darkRideMapEnabled && state.mapInstalled &&
            !state.darkGuidanceStyleReady
        ) {
            add("DarkGuidanceStyleLoading: selected dark style has not loaded yet")
        }
    }
}
