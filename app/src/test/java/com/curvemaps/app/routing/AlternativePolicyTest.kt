package com.curvemaps.app.routing

import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.graphhopper.util.details.PathDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternativePolicyTest {

    @Test
    fun `shared road fraction handles identical disjoint partial and empty maps`() {
        assertEquals(1.0, AlternativePolicy.sharedRoadFraction(mapOf(1 to 100.0), mapOf(1 to 100.0)), 0.0)
        assertEquals(0.0, AlternativePolicy.sharedRoadFraction(mapOf(1 to 100.0), mapOf(2 to 100.0)), 0.0)
        assertEquals(0.5, AlternativePolicy.sharedRoadFraction(mapOf(1 to 100.0, 2 to 100.0), mapOf(1 to 100.0, 3 to 100.0)), 0.0)
        assertEquals(1.0, AlternativePolicy.sharedRoadFraction(emptyMap(), emptyMap()), 0.0)
    }

    @Test
    fun `longest distinct stretch sums each consecutive run`() {
        val sections = listOf(
            AlternativePolicy.EdgeSection(1, true, 100.0),
            AlternativePolicy.EdgeSection(2, true, 200.0),
            AlternativePolicy.EdgeSection(9, true, 300.0),
            AlternativePolicy.EdgeSection(3, true, 400.0),
            AlternativePolicy.EdgeSection(4, true, 500.0),
        )
        assertEquals(500.0, AlternativePolicy.longestDistinctStretch(sections, setOf(1, 2, 3)), 0.0)
        assertEquals(0.0, AlternativePolicy.longestDistinctStretch(sections, setOf(1, 2, 9, 3, 4)), 0.0)
        assertEquals(1_500.0, AlternativePolicy.longestDistinctStretch(sections, emptySet()), 0.0)
    }

    @Test
    fun `meaningful stretch uses clamped five percent threshold`() {
        val previous = listOf(cached(20_000.0, 1_000L))
        val candidate = cached(10_000.0, 1_100L)
        assertFalse(
            AlternativePolicy.isMeaningfullyDifferent(
                candidate,
                AlternativePolicy.RouteDiversity(0.9, 749.99),
                previous,
            ),
        )
        assertTrue(
            AlternativePolicy.isMeaningfullyDifferent(
                candidate,
                AlternativePolicy.RouteDiversity(0.9, 750.0),
                previous,
            ),
        )

        val longCandidate = cached(100_000.0, 1_100L)
        assertFalse(
            AlternativePolicy.isMeaningfullyDifferent(
                longCandidate,
                AlternativePolicy.RouteDiversity(0.9, 2_999.99),
                previous,
            ),
        )
        assertTrue(
            AlternativePolicy.isMeaningfullyDifferent(
                longCandidate,
                AlternativePolicy.RouteDiversity(0.9, 3_000.0),
                previous,
            ),
        )
        assertTrue(
            AlternativePolicy.isMeaningfullyDifferent(
                candidate,
                AlternativePolicy.RouteDiversity(1.0, 0.0),
                emptyList(),
            ),
        )
    }

    @Test
    fun `global detour budget is relative to the style primary`() {
        val reference = path(10_000.0, 600_000L)
        val fastest = RideStyle.FASTEST
        assertTrue(AlternativePolicy.withinGlobalDetourBudget(path(16_500.0, 900_000L), reference, fastest))
        assertFalse(AlternativePolicy.withinGlobalDetourBudget(path(16_500.1, 900_000L), reference, fastest))
        assertFalse(AlternativePolicy.withinGlobalDetourBudget(path(10_000.0, 900_001L), reference, fastest))
        assertTrue(AlternativePolicy.withinGlobalDetourBudget(path(1_000.0, 1_000L), path(0.0, 0L), fastest))
    }

    @Test
    fun `a short hop is budgeted as if it were the reference ride`() {
        // Skyline Circuit to Palmwoods: 2.1 km / 3.1 min. The first different
        // ride (2.8 km / 5.7 min) is 1.81x the time, over every ratio below
        // Max twisty, yet only 2.6 minutes longer.
        val hop = path(2_100.0, 186_000L)
        val different = path(2_800.0, 342_000L)
        assertEquals(6L * 60_000L, AlternativePolicy.BUDGET_REFERENCE_TIME_MS)
        assertEquals(5_000.0, AlternativePolicy.BUDGET_REFERENCE_DISTANCE_M, 0.0)
        RideStyle.entries.forEach { style ->
            val extraTime = ((style.maxAlternativeTimeRatio - 1.0) * AlternativePolicy.BUDGET_REFERENCE_TIME_MS).toLong()
            val extraDistance = (style.maxAlternativeDistanceRatio - 1.0) * AlternativePolicy.BUDGET_REFERENCE_DISTANCE_M
            assertTrue(style.label, AlternativePolicy.withinGlobalDetourBudget(different, hop, style))
            assertTrue(style.label, AlternativePolicy.withinGlobalDetourBudget(path(2_100.0 + extraDistance - 0.5, 186_000L + extraTime), hop, style))
            assertFalse(style.label, AlternativePolicy.withinGlobalDetourBudget(path(2_100.0, 186_000L + extraTime + 1), hop, style))
            assertFalse(style.label, AlternativePolicy.withinGlobalDetourBudget(path(2_100.0 + extraDistance + 0.5, 186_000L), hop, style))
            // A widened search doubles what the budget adds.
            assertTrue(style.label, AlternativePolicy.withinGlobalDetourBudget(path(2_100.0 + 2 * extraDistance - 0.5, 186_000L + 2 * extraTime), hop, style, allowance = 2.0))
            assertFalse(style.label, AlternativePolicy.withinGlobalDetourBudget(path(2_100.0, 186_000L + 2 * extraTime + 1), hop, style, allowance = 2.0))
        }
        // Mostly fast may add 3 min and 3.25 km, Max twisty 6 min and 5.5 km.
        assertTrue(AlternativePolicy.withinGlobalDetourBudget(path(5_349.5, 366_000L), hop, RideStyle.MOSTLY_FAST))
        assertFalse(AlternativePolicy.withinGlobalDetourBudget(path(2_100.0, 366_001L), hop, RideStyle.MOSTLY_FAST))
        assertTrue(AlternativePolicy.withinGlobalDetourBudget(path(7_600.0, 546_000L), hop, RideStyle.MAX_TWISTY))
        // The two rules meet at the reference: a six-minute ride gets its
        // ratio either way, and a longer one is judged by the ratio alone.
        val atReference = path(5_000.0, 360_000L)
        assertTrue(AlternativePolicy.withinGlobalDetourBudget(path(8_250.0, 540_000L), atReference, RideStyle.MOSTLY_FAST))
        assertFalse(AlternativePolicy.withinGlobalDetourBudget(path(8_250.0, 540_001L), atReference, RideStyle.MOSTLY_FAST))
        val longer = path(50_000.0, 3_600_000L)
        assertTrue(AlternativePolicy.withinGlobalDetourBudget(path(82_500.0, 5_400_000L), longer, RideStyle.MOSTLY_FAST))
        assertFalse(AlternativePolicy.withinGlobalDetourBudget(path(82_500.0, 5_400_001L), longer, RideStyle.MOSTLY_FAST))
    }

    @Test
    fun `curvy styles accept longer loops than fast styles`() {
        val reference = path(10_000.0, 600_000L)
        RideStyle.entries.forEach { style ->
            val timeLimit = (600_000 * style.maxAlternativeTimeRatio).toLong()
            val distanceLimit = 10_000.0 * style.maxAlternativeDistanceRatio
            assertTrue(style.label, AlternativePolicy.withinGlobalDetourBudget(path(distanceLimit, timeLimit), reference, style))
            assertFalse(style.label, AlternativePolicy.withinGlobalDetourBudget(path(distanceLimit, timeLimit + 1), reference, style))
            assertFalse(style.label, AlternativePolicy.withinGlobalDetourBudget(path(distanceLimit + 0.1, timeLimit), reference, style))
        }
        RideStyle.entries.zipWithNext().forEach { (lower, higher) ->
            assertTrue(higher.maxAlternativeTimeRatio >= lower.maxAlternativeTimeRatio)
            assertTrue(higher.maxAlternativeDistanceRatio >= lower.maxAlternativeDistanceRatio)
        }
        // Fast stops keep the established budget; a road-trip loop is a curvy-stop card.
        assertEquals(1.50, RideStyle.BALANCED.maxAlternativeTimeRatio, 0.0)
        assertEquals(1.65, RideStyle.BALANCED.maxAlternativeDistanceRatio, 0.0)
        // Palmwoods -> Brisbane: the 158 min D'Aguilar loop vs a 100 min primary.
        assertTrue(AlternativePolicy.withinGlobalDetourBudget(path(139_700.0, 158L * 60_000), path(101_900.0, 100L * 60_000), RideStyle.MAX_TWISTY))
        assertFalse(AlternativePolicy.withinGlobalDetourBudget(path(139_700.0, 158L * 60_000), path(101_900.0, 100L * 60_000), RideStyle.BALANCED))
    }

    @Test
    fun `local detour rejects only pronounced bounded bubbles`() {
        // A long shared tail keeps each bubble well under half the ride.
        val tail = section(5, 5_000.0)
        val reference = listOf(
            section(1, 100.0),
            section(2, 250.0),
            section(3, 100.0),
            tail,
        )
        assertFalse(AlternativePolicy.hasExcessiveLocalDetour(listOf(section(1, 100.0)), reference))
        assertFalse(
            AlternativePolicy.hasExcessiveLocalDetour(
                listOf(section(1, 100.0), section(4, 2_000.0), section(3, 100.0), tail),
                listOf(section(1, 100.0), section(2, 16_000.0), section(3, 100.0), tail),
            ),
        )
        assertTrue(
            AlternativePolicy.hasExcessiveLocalDetour(
                listOf(section(1, 100.0), section(4, 1_750.0), section(3, 100.0), tail),
                reference,
            ),
        )
        assertTrue(
            AlternativePolicy.hasExcessiveLocalDetour(
                listOf(section(1, 100.0), section(4, 1_750.0), section(3, 100.0), tail),
                listOf(section(1, 100.0), section(2, 250.0), section(3, 100.0), tail),
            ),
        )
        assertFalse(
            AlternativePolicy.hasExcessiveLocalDetour(
                listOf(section(1, 100.0), section(4, 812.5), section(3, 100.0), tail),
                listOf(section(1, 100.0), section(2, 250.0), section(3, 100.0), tail),
            ),
        )
    }

    @Test
    fun `a bubble that is most of the ride is the ride, not a local detour`() {
        // The same 1.75 km wiggle replacing 250 m: a local bubble on a long
        // ride, but on a 2 km hop a loop around is the different ride itself
        // and only the global budget judges it.
        val reference = listOf(section(1, 100.0), section(2, 250.0), section(3, 100.0))
        val loop = listOf(section(1, 100.0), section(4, 1_750.0), section(3, 100.0))
        assertEquals(0.5, AlternativePolicy.LOCAL_DETOUR_IS_THE_RIDE_SHARE, 0.0)
        assertFalse(AlternativePolicy.hasExcessiveLocalDetour(loop, reference))
        // Just under half of the ride (1750 of 3501 m), it is a bubble again.
        assertTrue(
            AlternativePolicy.hasExcessiveLocalDetour(
                listOf(section(1, 100.0), section(4, 1_750.0), section(3, 100.0), section(5, 1_551.0)),
                reference + section(5, 1_551.0),
            ),
        )
        assertFalse(
            AlternativePolicy.hasExcessiveLocalDetour(
                listOf(section(1, 100.0), section(4, 1_750.0), section(3, 100.0), section(5, 1_549.0)),
                reference + section(5, 1_549.0),
            ),
        )
    }

    @Test
    fun `alternative request formulas stay stable`() {
        assertEquals(0.60, AlternativePolicy.SIBLING_MAX_SHARE, 0.0)
        assertEquals(2.0, AlternativePolicy.maxAlternativeWeight(0), 0.0)
        assertEquals(2.75, AlternativePolicy.maxAlternativeWeight(1), 0.0)
        assertEquals(4.0, AlternativePolicy.maxAlternativeWeight(99), 0.0)
        assertEquals((1 - 0.6) * 2.0, AlternativePolicy.siblingRoadPenalty(0), 1e-12)
        assertEquals((1 - 0.6) * 8.0, AlternativePolicy.siblingRoadPenalty(1), 1e-12)
        (0..5).forEach { attempt ->
            assertTrue(AlternativePolicy.siblingRoadPenalty(attempt) >= 0.0)
            assertTrue(AlternativePolicy.siblingRoadPenalty(attempt + 1) > AlternativePolicy.siblingRoadPenalty(attempt))
        }
    }

    @Test
    fun `carousel keeps the primary first and judges cards against it`() {
        val primary = edgePath(1..10, minutes = 100)
        val distinct = edgePath(listOf(1, 2) + (21..28), minutes = 140)
        val tooSlow = edgePath(listOf(1) + (31..39), minutes = 200)
        val overlapping = edgePath((1..8) + listOf(41, 42), minutes = 105)
        val searches = mutableListOf<Pair<Int, Set<Int>>>()
        val carousel = AlternativePolicy.selectCarousel(RideStyle.TWISTY, maxCards = 3, attempts = 0..2) { attempt, siblings ->
            searches += attempt to siblings
            if (attempt == 0) listOf(primary, tooSlow, overlapping, distinct) else emptyList()
        }
        assertEquals(listOf(primary, distinct), carousel.routes)
        assertEquals(1, carousel.rejectedAsDetour)
        // Retries run while the carousel is short and soft-penalise accepted roads.
        assertEquals(listOf(0, 1, 2), searches.map { it.first })
        assertEquals(emptySet<Int>(), searches[0].second)
        assertEquals(((1..10) + (21..28)).toSet(), searches[1].second)

        // Max twisty's wider budget keeps the slow loop as a card.
        val maxTwisty = AlternativePolicy.selectCarousel(RideStyle.MAX_TWISTY, maxCards = 3, attempts = 0..0) { _, _ ->
            listOf(primary, tooSlow, distinct)
        }
        assertEquals(listOf(primary, tooSlow, distinct), maxTwisty.routes)
    }

    @Test
    fun `a full carousel stops searching`() {
        var searches = 0
        val carousel = AlternativePolicy.selectCarousel(RideStyle.FASTEST, maxCards = 1, attempts = 0..2) { _, _ ->
            searches++
            listOf(edgePath(1..10, minutes = 100), edgePath(21..30, minutes = 100))
        }
        assertEquals(1, carousel.routes.size)
        assertEquals(1, searches)
    }

    /** A straight path of ~1.1 km edges, one edge per 0.01 degree of latitude. */
    private fun edgePath(edges: Iterable<Int>, minutes: Long): ResponsePath {
        val ids = edges.toList()
        val points = PointList().apply { (0..ids.size).forEach { add(it * 0.01, 0.0) } }
        val details = ids.mapIndexed { index, id ->
            PathDetail(id).apply {
                first = index
                last = index + 1
            }
        }
        return ResponsePath()
            .setPoints(points)
            .setDistance(ids.size * 1_112.0)
            .setTime(minutes * 60_000)
            .apply { addPathDetails(mapOf("edge_id" to details)) }
    }

    private fun section(id: Int, distance: Double) =
        AlternativePolicy.EdgeSection(id, true, distance)

    private fun cached(distance: Double, time: Long): AlternativePolicy.CachedRoute =
        AlternativePolicy.CachedRoute(path(distance, time), emptyMap(), emptyList())

    private fun path(distance: Double, time: Long): ResponsePath =
        ResponsePath()
            .setPoints(PointList().apply { add(0.0, 0.0); add(0.01, 0.01) })
            .setDistance(distance)
            .setTime(time)
}
