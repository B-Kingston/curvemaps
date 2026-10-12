package com.curvemaps.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import com.curvemaps.app.RouteCountKey
import com.curvemaps.app.SelectedRouteKey

private val ROUTE_CARD_DESCRIPTION = Regex("^Route \\d+: ")

/**
 * The planner's route card. There is one route per ride-style stop, so the
 * card is always "Route 1"; the matcher stays prefix-based for old seeds.
 */
val ROUTE_CARD: SemanticsMatcher = SemanticsMatcher("is a route card") { node ->
    node.config.getOrNull(SemanticsProperties.ContentDescription)
        ?.any { ROUTE_CARD_DESCRIPTION.containsMatchIn(it) } == true
}

/** Content-description prefix of the currently selected card ("Route 1:"). */
fun selectedRouteCardPrefix(rule: AndroidComposeTestRule<*, *>): String {
    val root = rule.onNode(SemanticsMatcher.keyIsDefined(RouteCountKey)).fetchSemanticsNode()
    return "Route ${root.config[SelectedRouteKey] + 1}:"
}

/** The currently shown route card. */
fun selectedRouteCard(rule: AndroidComposeTestRule<*, *>): SemanticsMatcher =
    hasContentDescription(selectedRouteCardPrefix(rule), substring = true)
