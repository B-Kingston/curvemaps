package com.curvemaps.app.map

import com.curvemaps.app.routing.SURFACE_DETAIL
import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.graphhopper.util.details.PathDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class GravelRouteLineTest {

    @Test
    fun onlyUnpavedDetailsBecomeStretchesAndNeighboursMerge() {
        val path = path(8).withSurfaces(
            "asphalt" to 0..2, "gravel" to 2..3, "dirt" to 3..5, "paved" to 5..6, "compacted" to 6..7,
        )
        assertEquals(listOf(2..5, 6..7), gravelStretches(path))
    }

    @Test
    fun noSurfaceDetailOrAllSealedDrawsNothing() {
        assertTrue(gravelStretches(path(4)).isEmpty())
        assertTrue(gravelStretches(path(4).withSurfaces("asphalt" to 0..3)).isEmpty())
        assertTrue(gravelFeatureCollection(null) { 0.0 }.features().orEmpty().isEmpty())
    }

    @Test
    fun detailsPastThePointsAreClampedNotThrown() {
        assertEquals(listOf(1..3), gravelStretches(path(4).withSurfaces("gravel" to 1..9)))
        assertTrue(gravelStretches(path(4).withSurfaces("gravel" to 3..9)).isEmpty())
    }

    @Test
    fun aBlendIsCentredOnTheSurfaceChangeAndKeepsTheGravelsLength() {
        val overlay = gravelOverlay(listOf(1_000.0..3_000.0), routeM = 5_000.0, fadeM = 400.0)
        assertEquals(listOf(800.0..3_200.0), overlay.gravel)
        // Each blend runs from the sealed side (route colour opaque) into the gravel (clear),
        // so the colour is half gone exactly at the surface change.
        assertEquals(listOf(GravelBlend(800.0, 1_200.0), GravelBlend(3_200.0, 2_800.0)), overlay.blends)
        assertEquals(2_000.0, greyMeters(overlay), 1e-9)
    }

    @Test
    fun gravelAtARouteEndStartsAtFullStrength() {
        val overlay = gravelOverlay(listOf(0.0..1_000.0), routeM = 1_000.0, fadeM = 400.0)
        assertEquals(GravelOverlay(listOf(0.0..1_000.0), emptyList()), overlay)
    }

    @Test
    fun stretchesTooCloseForTwoBlendsJoin() {
        val overlay = gravelOverlay(listOf(1_000.0..2_000.0, 2_100.0..3_000.0), routeM = 5_000.0, fadeM = 400.0)
        assertEquals(listOf(800.0..3_200.0), overlay.gravel)
        assertEquals(2, overlay.blends.size)
    }

    @Test
    fun aShortStretchBlendsOverItsOwnLength() {
        val overlay = gravelOverlay(listOf(1_000.0..1_050.0), routeM = 5_000.0, fadeM = 400.0)
        assertEquals(listOf(975.0..1_075.0), overlay.gravel)
        assertEquals(listOf(GravelBlend(975.0, 1_025.0), GravelBlend(1_075.0, 1_025.0)), overlay.blends)
        assertEquals(50.0, greyMeters(overlay), 1e-9)
    }

    /** Away from route ends and joins, the drawn gravel weighs exactly what the card counts. */
    @Test
    fun blendsNeverOverlapAndKeepEveryStretchsLengthOnRandomRoutes() {
        repeat(300) { seed ->
            val random = Random(seed)
            val fade = random.nextDouble(20.0, 2_000.0)
            var at = fade + random.nextDouble(0.0, 500.0)
            val stretches = List(random.nextInt(1, 6)) {
                val stretch = at..(at + random.nextDouble(1.0, 5_000.0))
                at = stretch.endInclusive + fade + random.nextDouble(0.0, 3_000.0)
                stretch
            }
            val overlay = gravelOverlay(stretches, routeM = at, fadeM = fade)
            overlay.gravel.zipWithNext().forEach { (a, b) -> assertTrue("seed $seed", b.start >= a.endInclusive - 1e-9) }
            overlay.blends.forEach { blend ->
                assertTrue("seed $seed", overlay.gravel.any { blend.sealedM in it && blend.gravelM in it })
                assertTrue("seed $seed", abs(blend.gravelM - blend.sealedM) <= fade + 1e-9)
            }
            assertEquals("seed $seed", stretches.sumOf { it.endInclusive - it.start }, greyMeters(overlay), 1e-6)
        }
    }

    @Test
    fun everyZoomBandDrawsTheGravelAndItsBlendsAndRampsShrinkAsTheMapZoomsIn() {
        val path = path(30).withSurfaces("asphalt" to 0..10, "gravel" to 10..20, "asphalt" to 20..29)
        val features = gravelFeatureCollection(path) { 0.0 }.features()!!
        GravelBand.entries.forEach { band ->
            val inBand = features.filter { it.getStringProperty(GRAVEL_BAND_PROPERTY) == band.name }
            assertEquals(1, inBand.count { it.getStringProperty(GRAVEL_KIND_PROPERTY) == GRAVEL_KIND_LINE })
            assertEquals(2, inBand.count { it.getStringProperty(GRAVEL_KIND_PROPERTY) == GRAVEL_KIND_BLEND })
        }
        val fades = GravelBand.entries.map { it.fadeMeters(-27.0) }
        assertEquals(fades.sortedDescending(), fades)
        GravelBand.entries.zipWithNext().forEach { (a, b) -> assertEquals(a.maxZoom, b.minZoom, 0f) }
    }

    /**
     * A blend fades from the colour the rider sees beside it: the line's curve
     * shade where the blend starts on sealed road, never the shade of the
     * gravel road it hides.
     */
    @Test
    fun eachBlendTakesTheLineShadeWhereItStartsOnSealedRoad() {
        val path = path(30).withSurfaces("asphalt" to 0..10, "gravel" to 10..20, "asphalt" to 20..29)
        val asked = mutableListOf<Double>()
        val features = gravelFeatureCollection(path) { meters ->
            asked += meters
            if (meters < 1_500.0) 0.0 else 1.0
        }.features()!!
        val blends = features.filter { it.getStringProperty(GRAVEL_KIND_PROPERTY) == GRAVEL_KIND_BLEND }
        assertEquals("one question per blend, at its sealed end", blends.size, asked.size)
        blends.zip(asked).forEach { (blend, meters) ->
            assertEquals(gravelBlendLevel(if (meters < 1_500.0) 0.0 else 1.0), blend.getNumberProperty(GRAVEL_BLEND_LEVEL_PROPERTY).toInt())
        }
        // The gravel runs from about 1.3 km to 2.6 km: blends entering it start before it, blends leaving it after it.
        val cumulative = (1 until 30).runningFold(0.0) { metres, index ->
            metres + com.graphhopper.util.DistanceCalcEarth.DIST_EARTH.calcDist(
                path.points.getLat(index - 1), path.points.getLon(index - 1), path.points.getLat(index), path.points.getLon(index),
            )
        }
        asked.forEach { assertTrue("$it m", it <= cumulative[10] || it >= cumulative[20]) }
        assertEquals(setOf(0, GRAVEL_BLEND_LEVELS - 1), blends.map { it.getNumberProperty(GRAVEL_BLEND_LEVEL_PROPERTY).toInt() }.toSet())
    }

    @Test
    fun blendLevelsSnapToTenthsOfTheCurveShade() {
        assertEquals(0, gravelBlendLevel(0.0))
        assertEquals(0, gravelBlendLevel(0.04))
        assertEquals(1, gravelBlendLevel(0.06))
        assertEquals(GRAVEL_BLEND_LEVELS - 1, gravelBlendLevel(1.0))
        assertEquals(0, gravelBlendLevel(-1.0))
        assertEquals(GRAVEL_BLEND_LEVELS - 1, gravelBlendLevel(2.0))
        (0..100).map { gravelBlendLevel(it / 100.0) }.zipWithNext().forEach { (a, b) -> assertTrue(b == a || b == a + 1) }
        assertEquals(GRAVEL_LAYER_IDS.size, GRAVEL_LAYER_IDS.toSet().size)
    }

    /** Grey weight of the overlay: the gravel lines less the route colour still showing over their blends. */
    private fun greyMeters(overlay: GravelOverlay) =
        overlay.gravel.sumOf { it.endInclusive - it.start } - overlay.blends.sumOf { abs(it.gravelM - it.sealedM) / 2.0 }

    private fun path(vertices: Int): ResponsePath =
        ResponsePath().setPoints(PointList().apply { repeat(vertices) { add(-27.0 + it * 0.001, 153.0 + (it % 3) * 0.0007) } })

    private fun ResponsePath.withSurfaces(vararg spans: Pair<String, IntRange>): ResponsePath = apply {
        addPathDetails(mapOf(SURFACE_DETAIL to spans.map { (surface, range) ->
            PathDetail(surface).apply { first = range.first; last = range.last }
        }))
    }
}
