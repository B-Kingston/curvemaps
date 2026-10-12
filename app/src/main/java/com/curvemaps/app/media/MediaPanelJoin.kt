package com.curvemaps.app.media

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.curvemaps.app.DataBarCorner
import com.curvemaps.app.DataBarMargin
import com.curvemaps.app.DataBarVerticalPadding

/**
 * How the media control center joins onto the ride data bar.
 *
 * The settings cog one screen over solves the same problem: the pill does not
 * sit *beside* the card it opens, it becomes part of it, so the card reads as
 * having grown out of the button. The media panel does the same to the bar, and
 * the only way to get there is to make the two surfaces genuinely continuous
 * rather than merely adjacent:
 *
 *  - [leadingInset] and [trailingInset] are the bar's own [DataBarMargin], so
 *    the panel's left and right edges are exactly the floating bar's extent.
 *    Any other inset leaves a step at the join, and a step is what made this
 *    read as two stacked cards rather than one shape.
 *  - [fuseDepth] reaches the panel down into the bar's empty top padding, and
 *    the bar is drawn after the panel so it paints over that overlap. The seam
 *    is therefore covered twice over: the geometry overlaps, and the bar's own
 *    opaque surface hides the antialiased boundary.
 *  - [barShadow] drops the bar's shadow while the panel is open. Otherwise the
 *    bar casts its top-edge shadow onto the panel and that line is exactly the
 *    seam this is trying to remove. The panel keeps its own shadow so the
 *    fused slab still sits above the map.
 *  - [panelTopCorner] is the bar's own [DataBarCorner], so the fused
 *    silhouette is one consistently rounded island from the panel's top down
 *    to the bar's rounded bottom.
 *
 * Values are derived from the data bar's geometry rather than restated, so
 * retuning the bar cannot silently strand the panel above it.
 * [MediaPanelJoinTest] (JVM) pins these relationships and
 * [com.curvemaps.app.ui.MediaPanelJoinTest] (instrumented) proves the real
 * laid-out edges are flush on screen.
 */
internal object MediaPanelJoin {

    /** The panel is inset exactly like the floating bar: no step at the join. */
    val leadingInset: Dp get() = DataBarMargin

    /** The panel is inset exactly like the floating bar: no step at the join. */
    val trailingInset: Dp get() = DataBarMargin

    /**
     * The fused slab's top corner radius, identical to the bar's own so the
     * silhouette reads as one consistently shaped surface.
     */
    val panelTopCorner: Dp get() = DataBarCorner

    /**
     * The bar's top corners: square at the join, rounded only when the bar
     * stands alone. Its bottom corners always round by [DataBarCorner].
     */
    fun barTopCorner(open: Boolean): Dp = if (open) 0.dp else DataBarCorner

    /**
     * Shadow elevation of the data bar. Zero while the panel is open: the bar's
     * top-edge shadow would fall across the panel and reinstate the seam.
     */
    fun barShadow(open: Boolean): Dp = if (open) 0.dp else 6.dp

    /**
     * How far the panel reaches into the bar's empty top padding. This covers
     * its base vertical padding; an additional navigation-inset gap balances
     * the ride row vertically. The overlap never reaches a metric or a control.
     */
    val fuseDepth: Dp get() = DataBarVerticalPadding

    /**
     * Thickness (dp) of the `outlineVariant` hairline the open bar draws along
     * its top edge: panel and bar share one container colour, so without it the
     * fused pair reads as a single slab. It lies inside the bar (no gap), so the
     * panel's flush edges and the join geometry are unchanged.
     */
    const val SEAM_DP: Int = 1
}
