package com.curvemaps.app.routing

import com.graphhopper.ResponsePath
import com.graphhopper.util.DistanceCalcEarth
import com.graphhopper.util.Instruction
import com.graphhopper.util.InstructionList
import com.graphhopper.util.PointList
import com.graphhopper.util.TranslationMap
import com.graphhopper.util.shapes.GHPoint
import java.util.Locale
import com.graphhopper.util.details.PathDetail
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DistinctRideStylesTest {
    @Test
    fun distinctOptimaStayUnchanged() = runTest {
        val rides = RideStyle.entries.associateWith { edgePath(it.ordinal * 100 until it.ordinal * 100 + 20) }
        val options = resolveInTurn(
            optimum = { rides.getValue(it) },
            reweighted = { _, _ -> error("A distinct optimum needs no escalation") },
            diversified = { _, _ -> error("A distinct optimum needs no road penalty") },
        )
        RideStyle.entries.forEach { assertSame(rides.getValue(it), options.getValue(it).path) }
    }

    @Test
    fun theJointChoiceGivesTheCurvyRoadToTheTopStopInsteadOfTheFirstStopThatFindsIt() = runTest {
        fun ride(first: Int, curvyEdges: Int, minutesPerEdge: Double = 1.05) =
            edgePath(first until first + 20, minutesPerEdge, curvy = (first until first + curvyEdges).toSet())
        val fastest = ride(0, 0, 1.0)
        val mostlyFast = ride(50, 6) // 30% curvy
        // Balanced's first search finds a gentle ride (25%: straighter than Mostly fast, so it searches on),
        // its second the corridor's one curvy road (75%), which is also Max twisty's own optimum.
        val gentle = ride(80, 5)
        val curvy = ride(100, 15, 1.2)
        // Curvy to Very twisty each find a ride of their own, 35% to 50%; Max twisty's search finds a 55% one.
        val own = mapOf(RideStyle.CURVY to ride(300, 7), RideStyle.TWISTY to ride(400, 8), RideStyle.VERY_TWISTY to ride(500, 9), RideStyle.MAX_TWISTY to ride(600, 11))
        suspend fun plan(select: Boolean, onSelected: (DistinctRideStyles.Selection) -> Unit = {}): Map<RideStyle, DistinctRideStyles.Option> {
            var balancedSearches = 0
            return DistinctRideStyles.resolve(
                optimum = { style ->
                    when (style) {
                        RideStyle.FASTEST -> fastest
                        RideStyle.MOSTLY_FAST, RideStyle.BALANCED -> mostlyFast
                        else -> curvy
                    }
                },
                reweighted = { style, _ -> if (style == RideStyle.BALANCED) (if (++balancedSearches == 1) gentle else curvy) else own.getValue(style) },
                select = select,
                onSelected = onSelected,
            )
        }
        // Settled stop by stop, Balanced takes the curvy road and every stop above it is straighter.
        val inTurn = plan(select = false)
        assertSame(curvy, inTurn.getValue(RideStyle.BALANCED).path)
        assertSame(own.getValue(RideStyle.MAX_TWISTY), inTurn.getValue(RideStyle.MAX_TWISTY).path)
        // Chosen together, the curvy road moves up to Max twisty (its own optimum), Balanced falls back on the
        // gentle ride its own search found, and the stops between climb.
        var selection: DistinctRideStyles.Selection? = null
        val joint = plan(select = true) { selection = it }
        assertSame(curvy, joint.getValue(RideStyle.MAX_TWISTY).path)
        // It is Max twisty's own optimum, so it reads as Max twisty's own ride.
        assertEquals(null, joint.getValue(RideStyle.MAX_TWISTY).foundBy)
        assertEquals(0, joint.getValue(RideStyle.MAX_TWISTY).round)
        assertSame(gentle, joint.getValue(RideStyle.BALANCED).path)
        assertSame(mostlyFast, joint.getValue(RideStyle.MOSTLY_FAST).path)
        listOf(RideStyle.CURVY, RideStyle.TWISTY, RideStyle.VERY_TWISTY).forEach { assertSame(it.label, own.getValue(it), joint.getValue(it).path) }
        assertPairwiseDifferent(joint.values.map { it.path })
        assertTrue(joint.values.all { it.repeats == null })
        val chosen = checkNotNull(selection)
        assertTrue("${chosen.chosenCost} < ${chosen.settledCost}", chosen.chosenCost < chosen.settledCost)
        // The choice's whole input comes along for offline re-scoring: one candidate per pooled ride.
        assertEquals(chosen.poolSize, checkNotNull(chosen.problem).candidates.size)
        assertEquals(chosen.poolSize, chosen.paths.size)
        assertEquals(chosen.poolSize, chosen.foundBy.size)
        assertTrue(chosen.paths.any { it === curvy } && chosen.foundBy[chosen.paths.indexOfFirst { it === curvy }] == RideStyle.BALANCED)
    }

    @Test
    fun aRideNeverMovesDownTheSlider() = runTest {
        val fastest = edgePath(0 until 20)
        // Only Max twisty's search finds a second road: no stop below it may show it, so they repeat Fastest.
        val other = edgePath(100 until 120, minutesPerEdge = 1.1)
        val options = DistinctRideStyles.resolve(
            optimum = { if (it == RideStyle.MAX_TWISTY) other else fastest },
            reweighted = { _, _ -> fastest },
            diversified = { _, _ -> fastest },
        )
        assertSame(other, options.getValue(RideStyle.MAX_TWISTY).path)
        RideStyle.entries.drop(1).dropLast(1).forEach { assertEquals(it.label, RideStyle.FASTEST, options.getValue(it).repeats) }
    }

    @Test
    fun theJointChoiceKeepsALadderSettledStopByStopWhenNothingBeatsIt() = runTest {
        // Seven different own optima, climbing in curviness.
        val rides = RideStyle.entries.associateWith { edgePath(it.ordinal * 100 until it.ordinal * 100 + 20, curvy = (it.ordinal * 100 until it.ordinal * 100 + it.ordinal * 2).toSet()) }
        val settled = DistinctRideStyles.resolve(optimum = { rides.getValue(it) }, reweighted = { _, _ -> null }, select = false)
        val joint = DistinctRideStyles.resolve(optimum = { rides.getValue(it) }, reweighted = { _, _ -> null })
        assertEquals(settled, joint)
        RideStyle.entries.forEach { assertSame(rides.getValue(it), joint.getValue(it).path) }
    }

    @Test
    fun repeatedCurveSearchesContinueUntilAMeaningfulRideAppears() = runTest {
        val fastest = edgePath(0 until 20)
        val meaningful = edgePath((0 until 5) + (200 until 215))
        val tried = mutableListOf<Double>()
        val options = resolveInTurn(
            optimum = { if (it.ordinal < 2) fastest else edgePath(it.ordinal * 1000 until it.ordinal * 1000 + 20) },
            reweighted = { _, weight -> tried += weight; if (weight < 0.7) fastest else meaningful },
        )
        assertSame(meaningful, options.getValue(RideStyle.MOSTLY_FAST).path)
        assertEquals(0.7, options.getValue(RideStyle.MOSTLY_FAST).curveWeight, 1e-9)
        assertTrue(tried.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun roadPenaltyFindsSevenRidesWhenCurveWeightsKeepOneCorridor() = runTest {
        val rides = RideStyle.entries.map { edgePath(it.ordinal * 100 until it.ordinal * 100 + 20) }
        val searches = mutableListOf<Pair<RideStyle, RideStyleSearch>>()
        val options = resolveInTurn(
            optimum = { rides.first() },
            reweighted = { _, _ -> rides.first() },
            diversified = { style, search ->
                searches += style to search
                if (search.roadPenalty < 0.5) rides.first() else rides[style.ordinal]
            },
        )
        assertEquals(7, options.size)
        RideStyle.entries.drop(1).forEach { style ->
            val option = options.getValue(style)
            assertSame(rides[style.ordinal], option.path)
            assertEquals(style.curveWeight, option.curveWeight, 0.0)
            assertEquals(0.5, option.roadPenalty, 0.0)
            val roads = searches.first { it.first == style }.second.penalizedEdges
            assertEquals((0 until style.ordinal).flatMap { rides[it].pathDetails.getValue("edge_id").map { d -> d.value as Int } }.toSet(), roads)
        }
        assertPairwiseDifferent(options.values.map { it.path })
    }

    @Test
    fun aTinyRoadSwapDoesNotSettleAStop() = runTest {
        val fastest = edgePath(0 until 200)
        val tiny = edgePath((0 until 100) + 900 + (101 until 200))
        val different = edgePath((0 until 100) + (900 until 905) + (105 until 200))
        val options = resolveInTurn(
            optimum = { if (it.ordinal < 2) fastest else edgePath(it.ordinal * 1000 until it.ordinal * 1000 + 200) },
            reweighted = { _, _ -> tiny },
            diversified = { _, _ -> different },
        )
        assertSame(different, options.getValue(RideStyle.MOSTLY_FAST).path)
    }

    @Test
    fun aRejectedSearchDoesNotHideALaterBudgetSafeRide() = runTest {
        val fastest = edgePath(0 until 20)
        val tooSlow = edgePath(100 until 120, minutesPerEdge = 3.0)
        val safe = edgePath(200 until 220)
        val options = resolveInTurn(
            optimum = { if (it.ordinal < 2) fastest else edgePath(it.ordinal * 1000 until it.ordinal * 1000 + 20) },
            reweighted = { _, weight -> if (weight < 0.5) tooSlow else safe },
        )
        assertSame(safe, options.getValue(RideStyle.MOSTLY_FAST).path)
    }

    @Test
    fun intermediatePenaltiesFindARideBeforeTheSearchJumpsToAnExcessiveDetour() = runTest {
        val fastest = edgePath(0 until 20)
        val safe = edgePath(200 until 220)
        val tooSlow = edgePath(300 until 320, minutesPerEdge = 3.0)
        val options = resolveInTurn(
            optimum = { if (it.ordinal < 2) fastest else edgePath(it.ordinal * 1000 until it.ordinal * 1000 + 20) },
            reweighted = { _, _ -> fastest },
            diversified = { _, search ->
                when {
                    search.roadPenalty < 0.75 -> fastest
                    search.roadPenalty < 1.0 -> safe
                    else -> tooSlow
                }
            },
        )
        assertSame(safe, options.getValue(RideStyle.MOSTLY_FAST).path)
        assertEquals(0.75, options.getValue(RideStyle.MOSTLY_FAST).roadPenalty, 0.0)
    }

    @Test
    fun exhaustionRepeatsTheStopBelowInsteadOfFailingThePlan() = runTest {
        val only = edgePath(0 until 20)
        val settled = mutableListOf<RideStyle>()
        val options = DistinctRideStyles.resolve(
            optimum = { only }, reweighted = { _, _ -> only }, diversified = { _, _ -> only },
            onResolved = { style, _ -> settled += style },
        )
        assertEquals(RideStyle.entries, settled)
        assertEquals(DistinctRideStyles.Option(only, 0.0, 0), options.getValue(RideStyle.FASTEST))
        RideStyle.entries.drop(1).forEach { style ->
            val option = options.getValue(style)
            assertSame(style.label, only, option.path)
            // Every repeat names the stop that owns the ride, not its neighbour.
            assertEquals(style.label, RideStyle.FASTEST, option.repeats)
            assertEquals(style.label, 0.0, option.curveWeight, 0.0)
        }
    }

    @Test
    fun aRepeatedStopDoesNotStopTheHigherStopsFromFindingTheirOwnRides() = runTest {
        val fastest = edgePath(0 until 20)
        val curvier = edgePath(200 until 220)
        val twistier = edgePath(300 until 320)
        val options = resolveInTurn(
            optimum = {
                when (it) {
                    RideStyle.FASTEST, RideStyle.MOSTLY_FAST, RideStyle.CURVY -> fastest
                    RideStyle.BALANCED, RideStyle.TWISTY -> curvier
                    else -> twistier
                }
            },
            reweighted = { style, _ -> if (style == RideStyle.CURVY) curvier else fastest },
            diversified = { _, _ -> null },
        )
        assertSame(fastest, options.getValue(RideStyle.FASTEST).path)
        assertEquals(RideStyle.FASTEST, options.getValue(RideStyle.MOSTLY_FAST).repeats)
        // Balanced's own optimum is new, so it is a ride of its own again.
        assertSame(curvier, options.getValue(RideStyle.BALANCED).path)
        assertEquals(null, options.getValue(RideStyle.BALANCED).repeats)
        // Curvy repeats the stop below it (the curviest ride so far), never
        // its own less curvy optimum, so the slider never turns back.
        assertSame(curvier, options.getValue(RideStyle.CURVY).path)
        assertEquals(RideStyle.BALANCED, options.getValue(RideStyle.CURVY).repeats)
        assertEquals(RideStyle.BALANCED, options.getValue(RideStyle.TWISTY).repeats)
        assertSame(twistier, options.getValue(RideStyle.VERY_TWISTY).path)
        assertEquals(RideStyle.VERY_TWISTY, options.getValue(RideStyle.MAX_TWISTY).repeats)
    }

    @Test
    fun aRideBeyondTheBudgetBeatsARepeatWhenTheBudgetHoldsNone() = runTest {
        val only = edgePath(0 until 20)
        val tooSlow = edgePath(100 until 120, minutesPerEdge = 3.0)
        val options = resolveInTurn(
            optimum = { only }, reweighted = { _, _ -> null }, diversified = { _, _ -> tooSlow },
        )
        assertSame(only, options.getValue(RideStyle.FASTEST).path)
        // Mostly fast's only different ride is 3x its time: shown, and marked.
        val mostlyFast = options.getValue(RideStyle.MOSTLY_FAST)
        assertSame(tooSlow, mostlyFast.path)
        assertEquals(DistinctRideStyles.Budget.NONE, mostlyFast.budget)
        assertEquals(0.25, mostlyFast.roadPenalty, 0.0)
        assertEquals(null, mostlyFast.repeats)
        // Above it every search returns one of the two rides: no other road.
        RideStyle.entries.drop(2).forEach { style ->
            assertSame(style.label, tooSlow, options.getValue(style).path)
            assertEquals(style.label, RideStyle.MOSTLY_FAST, options.getValue(style).repeats)
        }
    }

    @Test
    fun aRideWithinBudgetWinsOverEarlierRidesBeyondIt() = runTest {
        val fastest = edgePath(0 until 20)
        val relaxed = edgePath(100 until 120, minutesPerEdge = 1.8)
        val unbounded = edgePath(200 until 220, minutesPerEdge = 3.0)
        val within = edgePath(300 until 320, minutesPerEdge = 1.4)
        val options = resolveInTurn(
            optimum = { if (it.ordinal < 2) fastest else edgePath(it.ordinal * 1000 until it.ordinal * 1000 + 20) },
            // Curvier weights only find rides beyond the budget ...
            reweighted = { _, weight -> if (weight < 0.6) unbounded else relaxed },
            // ... and the penalties find one inside it at their third rung.
            diversified = { _, search -> if (search.roadPenalty < 0.75) unbounded else within },
        )
        val option = options.getValue(RideStyle.MOSTLY_FAST)
        assertSame(within, option.path)
        assertEquals(DistinctRideStyles.Budget.STYLE, option.budget)
        assertEquals(0.75, option.roadPenalty, 0.0)
    }

    @Test
    fun theGentlestRideBeyondTheBudgetIsKeptInPreferenceOrder() = runTest {
        val fastest = edgePath(0 until 20)
        val relaxedLate = edgePath(100 until 120, minutesPerEdge = 1.9)
        val relaxedEarly = edgePath(150 until 170, minutesPerEdge = 1.8)
        val unbounded = edgePath(200 until 220, minutesPerEdge = 3.0)
        val searched = mutableListOf<RideStyleSearch>()
        val options = resolveInTurn(
            optimum = { if (it.ordinal < 2) fastest else edgePath(it.ordinal * 1000 until it.ordinal * 1000 + 20) },
            reweighted = { _, _ -> unbounded },
            diversified = { _, search ->
                searched += search
                when {
                    search.roadPenalty < 1.0 -> unbounded
                    search.roadPenalty < 2.0 -> relaxedEarly
                    else -> relaxedLate
                }
            },
        )
        val option = options.getValue(RideStyle.MOSTLY_FAST)
        // Within twice the allowance beats any-distance, and the first such
        // ride (gentlest penalty) beats a later one; the whole ladder was
        // still searched for a ride inside the budget.
        assertSame(relaxedEarly, option.path)
        assertEquals(DistinctRideStyles.Budget.RELAXED, option.budget)
        assertEquals(1.0, option.roadPenalty, 0.0)
        assertEquals(16.0, searched.filter { it.penalizedEdges.isNotEmpty() }.maxOf { it.roadPenalty }, 0.0)
    }

    @Test
    fun aLocalDetourBubbleCountsAsBeyondEveryBudget() = runTest {
        val fastest = edgePath(0 until 20)
        // Same length and time, but a 2 km bubble replacing a 100 m link.
        val bubble = ResponsePath().setPoints(fastest.points).setDistance(fastest.distance).setTime(fastest.time).apply {
            val ids = (0 until 10) + (900 until 905) + (11 until 20)
            addPathDetails(mapOf("edge_id" to ids.mapIndexed { index, id -> PathDetail(id).apply { first = index; last = index + 1 } }))
        }
        val options = resolveInTurn(
            optimum = { if (it.ordinal < 2) fastest else edgePath(it.ordinal * 1000 until it.ordinal * 1000 + 20) },
            reweighted = { _, _ -> fastest },
            diversified = { _, _ -> bubble },
        )
        val option = options.getValue(RideStyle.MOSTLY_FAST)
        assertSame(bubble, option.path)
        assertEquals(DistinctRideStyles.Budget.NONE, option.budget)
    }

    @Test
    fun continuousStretchHasAnExactBoundaryAndComparisonIsSymmetric() {
        fun cached(ids: List<Int>, metresPerEdge: Double) = AlternativePolicy.CachedRoute(
            ResponsePath().setDistance(ids.size * metresPerEdge),
            ids.associateWith { metresPerEdge },
            ids.map { AlternativePolicy.EdgeSection(it, true, metresPerEdge) },
        )
        val baseline = cached((0 until 20).toList(), 250.0)
        val below = cached((0 until 10).toList() + listOf(100, 101) + (12 until 20).toList(), 250.0)
        val boundary = cached((0 until 10).toList() + listOf(100, 101, 102) + (13 until 20).toList(), 250.0)
        assertTrue(DistinctRideStyles.sameRide(baseline, below))
        assertFalse(DistinctRideStyles.sameRide(baseline, boundary))
        assertFalse(DistinctRideStyles.sameRide(boundary, baseline))
        val scattered = cached((0 until 20).map { if (it % 5 == 0) it + 100 else it }, 250.0)
        assertTrue("Scattered tiny swaps do not make a meaningful stretch", DistinctRideStyles.sameRide(baseline, scattered))
        assertTrue(DistinctRideStyles.sameRide(baseline, baseline))
    }

    @Test
    fun absentRoadDataCannotProveDiversity() {
        val path = ResponsePath().setDistance(1000.0)
        assertTrue(DistinctRideStyles.sameRide(AlternativePolicy.cachedRoute(path), AlternativePolicy.cachedRoute(path)))
    }

    @Test
    fun randomizedPlansNeverPublishRepeatsAndStayDeterministic() = runTest {
        val random = Random(7)
        repeat(100) {
            val rides = List(7) { index -> edgePath(index * 100 until index * 100 + 20, random.nextDouble(0.9, 1.1)) }
            suspend fun resolve(): Map<RideStyle, DistinctRideStyles.Option> = resolveInTurn(
                optimum = { rides[if (it.ordinal % 2 == 0) 0 else it.ordinal] },
                reweighted = { _, _ -> rides[0] },
                diversified = { style, search -> if (search.roadPenalty < 1.0) rides[0] else rides[style.ordinal] },
            )
            val first = resolve()
            assertEquals(first, resolve())
            assertPairwiseDifferent(first.values.map { it.path })
            first.forEach { (style, option) ->
                assertTrue(AlternativePolicy.withinGlobalDetourBudget(option.path, rides[0], style))
            }
        }
    }

    @Test
    fun lookingAroundFindsTheRideThePenaltiesCannotReachAndTakesTheLightestOneThatAddsCurves() = runTest {
        val fastest = edgePath(0 until 20)
        val ring1 = listOf(GHPoint(1.0, 1.0), GHPoint(1.0, 2.0), GHPoint(1.0, 3.0), GHPoint(1.0, 4.0))
        val ring2 = listOf(GHPoint(2.0, 1.0))
        // Lowest route weight, but the detour adds no curves: a farm square.
        val square = edgePath(300 until 320, minutesPerEdge = 1.1).setRouteWeight(10.0)
        // More curves per added minute, but heavier at the stop's own preference
        // (the gravel loop a low stop must not grab).
        val curvy = edgePath(200 until 220, minutesPerEdge = 1.2, curvy = (200 until 204).toSet()).setRouteWeight(80.0)
        val lighter = edgePath(500 until 520, minutesPerEdge = 1.3, curvy = (500 until 504).toSet()).setRouteWeight(50.0)
        val farther = edgePath(400 until 420, curvy = (400 until 420).toSet()).setRouteWeight(10.0)
        val searched = mutableListOf<RideStyleSearch>()
        val options = resolveInTurn(
            optimum = { if (it.ordinal < 2) fastest else edgePath(it.ordinal * 1000 until it.ordinal * 1000 + 20) },
            reweighted = { _, _ -> fastest },
            diversified = { style, search ->
                // The stops above search too: their straight optima sit below Mostly fast's curvy floor.
                if (style == RideStyle.MOSTLY_FAST) searched += search
                when (search.via) {
                    null -> fastest // every penalty keeps the shared corridor
                    ring1[0] -> square
                    ring1[1] -> lighter
                    ring1[2] -> curvy
                    ring1[3] -> null // a via on a dead end fails to route
                    else -> farther
                }
            },
            rings = listOf(ring1, ring2),
        )
        val option = options.getValue(RideStyle.MOSTLY_FAST)
        assertSame(lighter, option.path)
        assertEquals(ring1[1], option.via)
        assertEquals(0.0, option.roadPenalty, 0.0)
        assertEquals(DistinctRideStyles.Budget.STYLE, option.budget)
        assertEquals(RideStyle.MOSTLY_FAST.curveWeight, option.curveWeight, 0.0)
        // The whole first ring was searched, the second ring not at all.
        assertEquals(ring1, searched.mapNotNull { it.via })
        assertTrue(searched.filter { it.via != null }.all { it.penalizedEdges.isEmpty() && it.roadPenalty == 0.0 })
    }

    @Test
    fun aLookAroundRideThatAddsNoCurvesOnlyStandsIn() = runTest {
        val fastest = edgePath(0 until 20)
        val ring1 = listOf(GHPoint(1.0, 1.0))
        val ring2 = listOf(GHPoint(2.0, 1.0))
        val square = edgePath(300 until 320, minutesPerEdge = 1.1)
        val curvy = edgePath(200 until 220, minutesPerEdge = 1.3, curvy = (200 until 205).toSet())
        suspend fun plan(secondRing: ResponsePath?) = resolveInTurn(
            optimum = { if (it.ordinal < 2) fastest else edgePath(it.ordinal * 1000 until it.ordinal * 1000 + 20) },
            reweighted = { _, _ -> fastest },
            diversified = { _, search -> if (search.via == null) fastest else if (search.via in ring1) square else secondRing },
            rings = listOf(ring1, ring2),
        ).getValue(RideStyle.MOSTLY_FAST)
        // The next ring's curvier ride wins over a square on straight roads ...
        assertSame(curvy, plan(curvy).path)
        // ... and with nothing better, the square still makes the stop a ride of its own.
        val standIn = plan(null)
        assertSame(square, standIn.path)
        assertEquals(DistinctRideStyles.Budget.STYLE, standIn.budget)
        assertEquals(null, standIn.repeats)
    }

    @Test
    fun theNextRingIsSearchedOnlyWhenARingHoldsNoRideWithinBudget() = runTest {
        val fastest = edgePath(0 until 20)
        val ring1 = listOf(GHPoint(1.0, 1.0), GHPoint(1.0, 2.0))
        val ring2 = listOf(GHPoint(2.0, 1.0), GHPoint(2.0, 2.0))
        val tooSlow = edgePath(200 until 220, minutesPerEdge = 3.0)
        val withinBudget = edgePath(300 until 320, minutesPerEdge = 1.3, curvy = (300 until 304).toSet())
        val options = resolveInTurn(
            optimum = { if (it.ordinal < 2) fastest else edgePath(it.ordinal * 1000 until it.ordinal * 1000 + 20) },
            reweighted = { _, _ -> fastest },
            diversified = { _, search -> if (search.via == null) fastest else if (search.via in ring1) tooSlow else withinBudget },
            rings = listOf(ring1, ring2),
        )
        val option = options.getValue(RideStyle.MOSTLY_FAST)
        assertSame(withinBudget, option.path)
        assertEquals(ring2[0], option.via)
        assertEquals(DistinctRideStyles.Budget.STYLE, option.budget)
    }

    @Test
    fun aRideFoundLookingAroundBeyondTheBudgetBeatsARepeat() = runTest {
        val fastest = edgePath(0 until 20)
        val slow = edgePath(200 until 220, minutesPerEdge = 1.8)
        val options = resolveInTurn(
            optimum = { fastest },
            reweighted = { _, _ -> fastest },
            diversified = { _, search -> if (search.via == null) fastest else slow },
            rings = listOf(listOf(GHPoint(1.0, 1.0))),
        )
        val option = options.getValue(RideStyle.MOSTLY_FAST)
        assertSame(slow, option.path)
        assertEquals(DistinctRideStyles.Budget.RELAXED, option.budget)
        assertEquals(null, option.repeats)
        // The same ride is all the ring ever yields: the stops above repeat it.
        assertEquals(RideStyle.MOSTLY_FAST, options.getValue(RideStyle.BALANCED).repeats)
    }

    @Test
    fun withoutRingsAStopWithNoOtherRoadRepeatsTheStopBelow() = runTest {
        val only = edgePath(0 until 20)
        val options = resolveInTurn(
            optimum = { only }, reweighted = { _, _ -> only },
            diversified = { _, search -> assertEquals(null, search.via); only },
        )
        assertEquals(RideStyle.FASTEST, options.getValue(RideStyle.MAX_TWISTY).repeats)
    }

    @Test
    fun aStopNoCurvierThanTheStopBelowSearchesOnForOneThatIs() = runTest {
        val fastest = edgePath(0 until 20)
        val curvy = edgePath(100 until 120, minutesPerEdge = 1.2, curvy = (100 until 110).toSet())
        // Different and within Balanced's budget, but straighter than Mostly fast.
        val straight = edgePath(200 until 220, minutesPerEdge = 1.1)
        val curvier = edgePath(300 until 320, minutesPerEdge = 1.3, curvy = (300 until 312).toSet())
        val penalties = mutableListOf<Double>()
        val options = resolveInTurn(
            optimum = { if (it == RideStyle.FASTEST) fastest else curvy },
            reweighted = { _, _ -> straight },
            diversified = { style, search ->
                if (style == RideStyle.BALANCED) penalties += search.roadPenalty
                if (search.roadPenalty < 0.75) straight else curvier
            },
        )
        assertSame(curvy, options.getValue(RideStyle.MOSTLY_FAST).path)
        val balanced = options.getValue(RideStyle.BALANCED)
        // The straight ride was found first (escalation, then the first two
        // penalties) but cannot settle the stop: the third penalty's can.
        assertSame(curvier, balanced.path)
        assertEquals(0.75, balanced.roadPenalty, 0.0)
        assertEquals(listOf(0.25, 0.5, 0.75), penalties)
    }

    @Test
    fun aRelaxedRideAtTheFloorBeatsAStraighterRideWithinBudget() = runTest {
        val fastest = edgePath(0 until 20)
        val curvy = edgePath(100 until 120, minutesPerEdge = 1.2, curvy = (100 until 115).toSet())
        val slightly = edgePath(200 until 220, minutesPerEdge = 1.1, curvy = (200 until 202).toSet())
        val more = edgePath(300 until 320, minutesPerEdge = 1.1, curvy = (300 until 306).toSet())
        // At the floor, but within twice the allowance only.
        val relaxedCurvy = edgePath(400 until 420, minutesPerEdge = 1.9, curvy = (400 until 420).toSet())
        suspend fun plan(relaxed: ResponsePath?) = resolveInTurn(
            optimum = { if (it == RideStyle.FASTEST) fastest else curvy },
            reweighted = { _, weight -> if (weight < 1.0) slightly else relaxed },
            diversified = { _, search -> if (search.roadPenalty < 1.0) more else slightly },
        ).getValue(RideStyle.BALANCED)
        // The straighter ride within budget is timid: the relaxed curvy one wins (Cowra to Yass Curvy).
        val withRelaxed = plan(relaxedCurvy)
        assertSame(relaxedCurvy, withRelaxed.path)
        assertEquals(DistinctRideStyles.Budget.RELAXED, withRelaxed.budget)
        // With nothing at the floor, the curviest ride within budget wins.
        val withoutRelaxed = plan(null)
        assertSame(more, withoutRelaxed.path)
        assertEquals(DistinctRideStyles.Budget.STYLE, withoutRelaxed.budget)
        assertEquals(null, withoutRelaxed.repeats)
    }

    @Test
    fun theLookAroundIsSkippedWhenTheFirstSearchesFoundARelaxedRideAtTheFloor() = runTest {
        val fastest = edgePath(0 until 20)
        val ring = listOf(GHPoint(1.0, 1.0))
        val curvyLookAround = edgePath(200 until 220, minutesPerEdge = 1.2, curvy = (200 until 210).toSet())
        suspend fun plan(firstSearches: ResponsePath): Pair<DistinctRideStyles.Option, Int> {
            var vias = 0
            val option = resolveInTurn(
                optimum = { if (it.ordinal < 2) fastest else edgePath(it.ordinal * 1000 until it.ordinal * 1000 + 20) },
                reweighted = { _, _ -> fastest },
                diversified = { style, search ->
                    if (search.via == null) firstSearches else { if (style == RideStyle.MOSTLY_FAST) vias++; curvyLookAround }
                },
                rings = listOf(ring),
            ).getValue(RideStyle.MOSTLY_FAST)
            return option to vias
        }
        // A ride of the first searches within twice the allowance: no look-around.
        val relaxed = edgePath(300 until 320, minutesPerEdge = 1.8)
        val (withRelaxed, viasWithRelaxed) = plan(relaxed)
        assertEquals(0, viasWithRelaxed)
        assertSame(relaxed, withRelaxed.path)
        // Only rides beyond every budget: the look-around runs and wins.
        val tooSlow = edgePath(400 until 420, minutesPerEdge = 3.0)
        val (withoutBudgetRide, viasWithout) = plan(tooSlow)
        assertEquals(ring.size, viasWithout)
        assertSame(curvyLookAround, withoutBudgetRide.path)
    }

    @Test
    fun aLookAroundLassoStandsInOnlyInsteadOfARepeat() = runTest {
        val fastest = edgePath(0 until 20)
        val lasso = lassoPath()
        val curvy = edgePath(200 until 220, minutesPerEdge = 1.3, curvy = (200 until 205).toSet())
        assertTrue(DistinctRideStyles.isLasso(lasso))
        assertFalse(DistinctRideStyles.isLasso(curvy))
        val ring = listOf(GHPoint(1.0, 1.0), GHPoint(1.0, 2.0))
        suspend fun plan(other: ResponsePath?) = resolveInTurn(
            optimum = { fastest },
            reweighted = { _, _ -> fastest },
            diversified = { _, search -> if (search.via == null) fastest else if (search.via == ring[0]) lasso else other },
            rings = listOf(ring),
        ).getValue(RideStyle.MOSTLY_FAST)
        // Any other ride, even a straight one beyond the budget, beats the lasso ...
        assertSame(curvy, plan(curvy).path)
        val slow = edgePath(300 until 320, minutesPerEdge = 3.0)
        assertSame(slow, plan(slow).path)
        // ... but a lasso still beats repeating the stop below.
        val standIn = plan(null)
        assertSame(lasso, standIn.path)
        assertEquals(null, standIn.repeats)
    }

    @Test
    fun aStopWithNoRideOfItsOwnTakesOneALowerStopFoundButDidNotShow() = runTest {
        val fastest = edgePath(0 until 20)
        val shown = edgePath(100 until 120, minutesPerEdge = 1.2, curvy = (100 until 110).toSet())
        // Mostly fast finds this one first (beyond its budget), then settles on [shown].
        val passedOver = edgePath(200 until 220, minutesPerEdge = 1.8)
        val mostlyFastSearches = mutableListOf<Double>()
        val options = resolveInTurn(
            optimum = { if (it.ordinal < 3) fastest else shown },
            reweighted = { style, weight ->
                if (style != RideStyle.MOSTLY_FAST) shown
                else { mostlyFastSearches += weight; if (mostlyFastSearches.size == 1) passedOver else shown }
            },
            diversified = { _, _ -> shown },
        )
        assertSame(shown, options.getValue(RideStyle.MOSTLY_FAST).path)
        // Balanced's own searches only meet rides already shown: it takes the passed-over ride.
        val balanced = options.getValue(RideStyle.BALANCED)
        assertSame(passedOver, balanced.path)
        assertEquals(null, balanced.repeats)
        // Nothing is left for the stops above.
        assertEquals(RideStyle.BALANCED, options.getValue(RideStyle.CURVY).repeats)
    }

    @Test
    fun aLassoIsALoopBackToAnEarlierPointAndASmallOneIsNot() {
        // North 10 km, round a 3 km square back onto the earlier road, north 5 km more.
        val big = lassoPath(sideKm = 3.0)
        assertEquals(12_000.0, DistinctRideStyles.lassoMeters(big, 3_000.0), 300.0)
        assertTrue(DistinctRideStyles.isLasso(big))
        // A 0.5 km interchange ramp loop is under the lasso minimum.
        val ramp = lassoPath(sideKm = 0.5)
        assertFalse(DistinctRideStyles.isLasso(ramp))
        // A straight ride never comes back.
        assertEquals(0.0, DistinctRideStyles.lassoMeters(edgePath(0 until 20), 500.0), 0.0)
    }

    @Test
    fun aRideThatComesBackToWhereItWasIsALoop() {
        val fastest = AlternativePolicy.cachedRoute(meridian())
        fun kind(path: ResponsePath) = DistinctRideStyles.loopKind(AlternativePolicy.cachedRoute(path), fastest)
        // Skyline Circuit to Palmwoods Max twisty: off the road at 2.5 km, round a block, back to the same corner.
        assertEquals(DistinctRideStyles.LoopKind.SMALL, kind(offTheMeridian(2_500, listOf(0.0 to 400.0, 400.0 to 200.0, 0.0 to 0.0), rejoinAt = 2_500)))
        // Ruatoria to Hicks Bay Max twisty: round a block, back on the road 100 m on (700 m for 100 m of road).
        assertEquals(DistinctRideStyles.LoopKind.SMALL, kind(offTheMeridian(2_500, listOf(0.0 to 300.0, 100.0 to 300.0, 100.0 to 0.0), rejoinAt = 2_600)))
        // A 13 km loop back to the same corner is a lasso: it may stand in for a repeat.
        assertEquals(DistinctRideStyles.LoopKind.LASSO, kind(offTheMeridian(2_500, listOf(0.0 to 4_000.0, 4_000.0 to 2_000.0, 0.0 to 0.0), rejoinAt = 2_500)))
        // A detour that replaces 1.5 km of the road is a ride, however long.
        assertEquals(DistinctRideStyles.LoopKind.NONE, kind(offTheMeridian(2_500, listOf(0.0 to 600.0, 1_500.0 to 600.0, 1_500.0 to 0.0), rejoinAt = 4_000)))
        // So is a short jab that makes progress: 700 m for 300 m of road (less than three times it).
        assertEquals(DistinctRideStyles.LoopKind.NONE, kind(offTheMeridian(2_500, listOf(0.0 to 200.0, 300.0 to 200.0, 300.0 to 0.0), rejoinAt = 2_800)))
        // A switchback road comes back within metres of itself but passes no point twice and leaves no road.
        val switchbacks = way(listOf(0.0 to 0.0, 1_000.0 to 0.0, 1_000.0 to 20.0, 0.0 to 20.0, 0.0 to 40.0, 5_000.0 to 40.0), listOf(300, 301, 302, 303, 304))
        assertEquals(DistinctRideStyles.LoopKind.NONE, kind(switchbacks))
        assertEquals(DistinctRideStyles.LoopKind.NONE, kind(meridian()))
        // Samford Valley to Brisbane Max twisty: from the start round a block, back onto the road 300 m on.
        val startLoop = way(listOf(0.0 to 0.0, 0.0 to -400.0, 300.0 to -400.0, 300.0 to 0.0) + (4..50).map { it * 100.0 to 0.0 }, listOf(800, 801, 802) + (3 until 50))
        assertEquals(DistinctRideStyles.LoopKind.SMALL, kind(startLoop))
        // Off the road 200 m before the finish, round a block to arrive from the other side.
        val finishLoop = way((0..48).map { it * 100.0 to 0.0 } + listOf(4_800.0 to 400.0, 5_200.0 to 400.0, 5_200.0 to 0.0, 5_000.0 to 0.0), (0 until 48) + listOf(810, 811, 812, 49))
        assertEquals(DistinctRideStyles.LoopKind.SMALL, kind(finishLoop))
        // A ride on other roads all the way is another way, not a loop.
        assertEquals(DistinctRideStyles.LoopKind.NONE, kind(way(listOf(0.0 to 0.0, 0.0 to 2_000.0, 5_000.0 to 2_000.0, 5_000.0 to 0.0), listOf(820, 821, 822))))
    }

    @Test
    fun aLoopFastestRidesTooIsForcedByTheRoads() {
        // The start is on a one-way street: every ride goes round the block first and passes the corner twice.
        val block = listOf(0.0 to 0.0, 0.0 to 300.0, 300.0 to 300.0, 300.0 to 0.0, 0.0 to 0.0)
        val fastest = AlternativePolicy.cachedRoute(way(block + (5_000.0 to 0.0), (400 until 405).toList()))
        val curvy = way(block + listOf(2_000.0 to 0.0, 3_000.0 to 1_000.0, 5_000.0 to 0.0), (400 until 404).toList() + listOf(500, 501, 502))
        assertEquals(DistinctRideStyles.LoopKind.NONE, DistinctRideStyles.loopKind(AlternativePolicy.cachedRoute(curvy), fastest))
        // The same loop anywhere else is the ride's own.
        val elsewhere = way(listOf(0.0 to 0.0, 2_000.0 to 0.0, 2_000.0 to 300.0, 2_300.0 to 300.0, 2_300.0 to 0.0, 2_000.0 to 0.0, 5_000.0 to 0.0), (600 until 606).toList())
        assertEquals(DistinctRideStyles.LoopKind.SMALL, DistinctRideStyles.loopKind(AlternativePolicy.cachedRoute(elsewhere), fastest))
    }

    @Test
    fun noStopShowsASmallLoopAndALassoOnlyStandsInForARepeat() = runTest {
        val fastest = meridian()
        val block = offTheMeridian(2_500, listOf(0.0 to 400.0, 400.0 to 200.0, 0.0 to 0.0), rejoinAt = 2_500, minutes = 6.0)
        val lasso = offTheMeridian(2_500, listOf(0.0 to 4_000.0, 4_000.0 to 2_000.0, 0.0 to 0.0), rejoinAt = 2_500, minutes = 20.0)
        val detour = offTheMeridian(2_500, listOf(0.0 to 600.0, 1_500.0 to 600.0, 1_500.0 to 0.0), rejoinAt = 4_000, minutes = 7.0)
        suspend fun plan(other: ResponsePath, select: Boolean) = DistinctRideStyles.resolve(
            optimum = { if (it == RideStyle.FASTEST) fastest else other },
            reweighted = { _, _ -> other },
            diversified = { _, _ -> other },
            select = select,
        )
        for (select in listOf(false, true)) {
            // Every search finds only the block loop: no stop shows it, the graph has no other ride.
            val blocked = plan(block, select)
            assertTrue(blocked.values.none { it.path === block })
            assertEquals(RideStyle.FASTEST, blocked.getValue(RideStyle.MOSTLY_FAST).repeats)
            // A lasso stands in for one stop rather than a repeat (settled in turn Mostly fast, chosen
            // together the top stop, the low stops repeating first); every other stop repeats.
            val looped = plan(lasso, select)
            val shown = looped.filterValues { it.path === lasso && it.repeats == null }.keys
            assertEquals(setOf(if (select) RideStyle.MAX_TWISTY else RideStyle.MOSTLY_FAST), shown)
            assertTrue(looped.filterKeys { it != RideStyle.FASTEST && it !in shown }.values.all { it.repeats != null })
        }
        // With a ride of its own as well, the lasso is never shown.
        val both = DistinctRideStyles.resolve(
            optimum = { if (it == RideStyle.FASTEST) fastest else block },
            reweighted = { style, _ -> if (style == RideStyle.MOSTLY_FAST) detour else lasso },
            diversified = { _, _ -> block },
        )
        assertSame(detour, both.getValue(RideStyle.MOSTLY_FAST).path)
        assertTrue(both.values.none { it.path === block })
    }

    @Test
    fun aStopsOwnOptimumStraighterThanTheStopBelowGivesWayToACurvierRide() = runTest {
        val fastest = edgePath(0 until 20)
        val curvy = edgePath(100 until 120, minutesPerEdge = 1.2, curvy = (100 until 112).toSet())
        val ownButStraight = edgePath(200 until 220, minutesPerEdge = 1.1)
        val curvier = edgePath(300 until 320, minutesPerEdge = 1.3, curvy = (300 until 314).toSet())
        suspend fun plan(escalation: ResponsePath) = resolveInTurn(
            optimum = {
                when (it) {
                    RideStyle.FASTEST -> fastest
                    RideStyle.MOSTLY_FAST -> curvy
                    else -> ownButStraight
                }
            },
            reweighted = { _, _ -> escalation },
            diversified = { _, _ -> null },
        ).getValue(RideStyle.BALANCED)
        assertSame(curvier, plan(curvier).path)
        // Nothing curvier exists: the stop's own optimum still beats a repeat.
        val own = plan(ownButStraight)
        assertSame(ownButStraight, own.path)
        assertEquals(0, own.round)
        assertEquals(null, own.repeats)
    }

    @Test
    fun theCurveGainCountsCurvyMetresPerAddedMinute() {
        val best = edgePath(0 until 20)
        val curvy = edgePath(0 until 20, minutesPerEdge = 1.25, curvy = (0 until 5).toSet())
        val quick = edgePath(0 until 20, minutesPerEdge = 0.9, curvy = (0 until 5).toSet())
        val curvyMetres = curvyDistanceFraction(curvy.points) * AlternativePolicy.edgeSections(curvy).sumOf { it.distance }
        assertEquals(curvyMetres / 5.0, DistinctRideStyles.curveGainPerMinute(curvy, best), curvyMetres * 0.02)
        // A quicker ride counts one minute: judged by its curves alone.
        assertEquals(curvyMetres, DistinctRideStyles.curveGainPerMinute(quick, best), curvyMetres * 0.02)
        assertEquals(0.0, DistinctRideStyles.curveGainPerMinute(best, best), 0.0)
        assertTrue(DistinctRideStyles.curveGainPerMinute(best, curvy) < 0.0)
    }

    @Test
    fun drivesARoadTwiceIgnoresTheViaSplitAndAllowsAShortRejoin() {
        fun route(vararg sections: Pair<Int, Double>) = AlternativePolicy.CachedRoute(
            ResponsePath(),
            sections.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() },
            sections.map { AlternativePolicy.EdgeSection(it.first, true, it.second) },
        )
        assertEquals(150.0, DistinctRideStyles.MAX_RETRACED_METRES, 0.0)
        // A via point splits its edge into two consecutive entries: one pass.
        assertFalse(DistinctRideStyles.drivesARoadTwice(route(1 to 100.0, 2 to 104.0, 2 to 111.0, 3 to 100.0)))
        // A loop around a block rejoins the road it left for 74 m.
        assertFalse(DistinctRideStyles.drivesARoadTwice(route(1 to 100.0, 2 to 59.0, 3 to 15.0, 4 to 500.0, 5 to 400.0, 2 to 59.0, 3 to 15.0, 6 to 100.0)))
        // Into a dead end and back out: an out-and-back spur.
        assertTrue(DistinctRideStyles.drivesARoadTwice(route(1 to 100.0, 2 to 176.0, 3 to 127.0, 4 to 121.0, 4 to 121.0, 3 to 127.0, 2 to 176.0, 5 to 100.0)))
        // Exactly the allowance passes; a metre more does not.
        assertFalse(DistinctRideStyles.drivesARoadTwice(route(1 to 100.0, 2 to 150.0, 3 to 500.0, 2 to 150.0)))
        assertTrue(DistinctRideStyles.drivesARoadTwice(route(1 to 100.0, 2 to 151.0, 3 to 500.0, 2 to 151.0)))
    }

    @Test
    fun aRideThatTurnsAroundIsNotARide() {
        assertFalse(DistinctRideStyles.turnsAround(ResponsePath()))
        assertFalse(DistinctRideStyles.turnsAround(path(Instruction.CONTINUE_ON_STREET to 5_000.0, Instruction.REACHED_VIA to 5_000.0, Instruction.TURN_LEFT to 5_000.0, Instruction.FINISH to 0.0)))
        // Mid-ride, every U-turn sign is a spur.
        listOf(Instruction.U_TURN_UNKNOWN, Instruction.U_TURN_LEFT, Instruction.U_TURN_RIGHT).forEach { sign ->
            assertTrue(DistinctRideStyles.turnsAround(path(Instruction.CONTINUE_ON_STREET to 5_000.0, sign to 5_000.0, Instruction.FINISH to 0.0)))
        }
    }

    @Test
    fun aUTurnWhereTheStartOrFinishSnappedIsNotASpur() {
        val limit = DistinctRideStyles.END_U_TURN_METRES
        assertEquals(300.0, limit, 0.0)
        // Toowoomba: the start snaps onto one carriageway of Ruthven Street, 20 m from where the ride can turn.
        assertFalse(DistinctRideStyles.turnsAround(path(Instruction.CONTINUE_ON_STREET to 20.0, Instruction.U_TURN_UNKNOWN to 36_000.0, Instruction.FINISH to 0.0)))
        // Arriving on the far carriageway: a U-turn in the last 300 m.
        assertFalse(DistinctRideStyles.turnsAround(path(Instruction.CONTINUE_ON_STREET to 36_000.0, Instruction.U_TURN_LEFT to 250.0, Instruction.FINISH to 0.0)))
        // Exactly the allowance from either end passes; a metre further in does not.
        assertFalse(DistinctRideStyles.turnsAround(path(Instruction.CONTINUE_ON_STREET to limit, Instruction.U_TURN_RIGHT to 10_000.0, Instruction.FINISH to 0.0)))
        assertTrue(DistinctRideStyles.turnsAround(path(Instruction.CONTINUE_ON_STREET to limit + 1.0, Instruction.U_TURN_RIGHT to 10_000.0, Instruction.FINISH to 0.0)))
        assertFalse(DistinctRideStyles.turnsAround(path(Instruction.CONTINUE_ON_STREET to 10_000.0, Instruction.U_TURN_RIGHT to limit, Instruction.FINISH to 0.0)))
        assertTrue(DistinctRideStyles.turnsAround(path(Instruction.CONTINUE_ON_STREET to 10_000.0, Instruction.U_TURN_RIGHT to limit + 1.0, Instruction.FINISH to 0.0)))
        // An end U-turn does not excuse a second one mid-ride.
        assertTrue(DistinctRideStyles.turnsAround(path(Instruction.U_TURN_LEFT to 4_000.0, Instruction.U_TURN_LEFT to 4_000.0, Instruction.FINISH to 0.0)))
    }

    @Test
    fun aUTurnTheStopsOwnOptimumMakesTooIsNotASpur() {
        // Sunshine Coast to Yarrabilba: every ride turns onto Pioneer Drive 2.8 km before the finish.
        val pioneerDrive = GHPoint(-27.78429, 153.09966)
        val optimum = path(Instruction.CONTINUE_ON_STREET to 150_000.0, Instruction.U_TURN_LEFT to 2_800.0, Instruction.FINISH to 0.0, at = mapOf(1 to pioneerDrive))
        val candidate = path(Instruction.CONTINUE_ON_STREET to 151_000.0, Instruction.U_TURN_LEFT to 2_800.0, Instruction.FINISH to 0.0, at = mapOf(1 to GHPoint(pioneerDrive.lat + 0.0003, pioneerDrive.lon)))
        assertTrue(DistinctRideStyles.turnsAround(candidate))
        assertFalse(DistinctRideStyles.turnsAround(candidate, excusedBy = optimum))
        // A U-turn somewhere else is still a spur.
        val elsewhere = path(Instruction.CONTINUE_ON_STREET to 100_000.0, Instruction.U_TURN_LEFT to 50_000.0, Instruction.FINISH to 0.0, at = mapOf(1 to GHPoint(-27.5, 153.0)))
        assertTrue(DistinctRideStyles.turnsAround(elsewhere, excusedBy = optimum))
    }

    /** A path whose instructions each run the given metres to the next one; [at] places an instruction. */
    private fun path(vararg signs: Pair<Int, Double>, at: Map<Int, GHPoint> = emptyMap()) = ResponsePath().apply {
        val translation = TranslationMap().doImport().getWithFallBack(Locale.ENGLISH)
        instructions = InstructionList(translation).apply {
            signs.forEachIndexed { index, (sign, metres) ->
                val points = PointList().apply { at[index]?.let { add(it.lat, it.lon) } }
                add(Instruction(sign, "Nicklin Road", points).setDistance(metres))
            }
        }
    }

    @Test
    fun detourRingsScaleWithTheCorridorAndClampTheirRadii() {
        fun radii(from: GHPoint, to: GHPoint): List<List<Double>> {
            val midLat = (from.lat + to.lat) / 2
            val midLon = (from.lon + to.lon) / 2
            return DistinctRideStyles.detourRings(from, to).map { ring ->
                ring.map { DistanceCalcEarth.DIST_EARTH.calcDist(midLat, midLon, it.lat, it.lon) }
            }
        }
        // Skyline Circuit to Palmwoods: a 1.32 km beeline looks 0.7, 1.0, 2.0 and 3.3 km around.
        val hop = radii(GHPoint(-26.6787181, 152.9698004), GHPoint(-26.6859705, 152.959253))
        assertEquals(4, hop.size)
        assertEquals(listOf(700.0, 992.0, 1_983.0, 3_306.0), hop.map { ring -> (ring.average()).let { Math.round(it).toDouble() } })
        hop.forEach { ring ->
            assertEquals(DistinctRideStyles.RING_BEARINGS, ring.size)
            ring.forEach { assertEquals(ring.first(), it, ring.first() * 0.01) }
        }
        assertEquals(DistinctRideStyles.RING_BEARINGS, DistinctRideStyles.detourRings(GHPoint(-26.6787181, 152.9698004), GHPoint(-26.6859705, 152.959253)).first().distinct().size)
        // A 200 m hop: every ring clamps to the minimum, searched once.
        val tiny = radii(GHPoint(-27.0, 153.0), GHPoint(-27.0018, 153.0))
        assertEquals(1, tiny.size)
        assertEquals(DistinctRideStyles.MIN_RING_RADIUS_M, tiny.single().average(), 1.0)
        // A 200 km corridor: the rings clamp to the maximum, searched once.
        val long = radii(GHPoint(-27.0, 153.0), GHPoint(-28.8, 153.0))
        assertEquals(1, long.size)
        assertEquals(DistinctRideStyles.MAX_RING_RADIUS_M, long.single().average(), 25.0)
    }

    /**
     * A ride through [corners] (metres north and east of 0° 0°), one edge
     * per leg with the given [ids] (a repeated id is the same road again).
     */
    private fun way(corners: List<Pair<Double, Double>>, ids: List<Int>, minutes: Double = 5.0): ResponsePath {
        require(ids.size == corners.size - 1)
        val points = PointList().apply { corners.forEach { (north, east) -> add(north / METRES_PER_DEGREE, east / METRES_PER_DEGREE) } }
        var metres = 0.0
        for (index in 1 until points.size()) {
            metres += DistanceCalcEarth.DIST_EARTH.calcDist(points.getLat(index - 1), points.getLon(index - 1), points.getLat(index), points.getLon(index))
        }
        val details = ids.mapIndexed { index, id -> PathDetail(id).apply { first = index; last = index + 1 } }
        return ResponsePath().setPoints(points).setDistance(metres).setTime((minutes * 60_000).toLong()).apply {
            addPathDetails(mapOf("edge_id" to details))
        }
    }

    /** 5 km due north in 100 m edges (ids 0–49). */
    private fun meridian(): ResponsePath = way((0..50).map { it * 100.0 to 0.0 }, (0 until 50).toList(), minutes = 5.0)

    /**
     * [meridian] to [leaveAt] metres, then through [legs] (metres north and
     * east of where it left) on roads of its own, and on along the meridian
     * from [rejoinAt] metres.
     */
    private fun offTheMeridian(leaveAt: Int, legs: List<Pair<Double, Double>>, rejoinAt: Int, minutes: Double = 6.0): ResponsePath {
        val before = (0..leaveAt / 100).map { it * 100.0 to 0.0 }
        val off = legs.map { (north, east) -> leaveAt + north to east }
        val after = (rejoinAt / 100 + 1..50).map { it * 100.0 to 0.0 }
        val ids = (0 until leaveAt / 100) + (700 until 700 + legs.size) + (rejoinAt / 100 until 50)
        return way(before + off + after, ids, minutes)
    }

    /** North 10 km, east, north, west and south round a square of [sideKm] back to the 10 km point, then north 5 km. */
    private fun lassoPath(sideKm: Double = 3.0): ResponsePath {
        val degree = 1.0 / 111.195
        val side = sideKm * degree
        val corners = listOf(
            0.0 to 0.0, 10 * degree to 0.0, 10 * degree to side, 10 * degree + side to side,
            10 * degree + side to 0.0, 10 * degree - 0.0001 to 0.0, 15 * degree to 0.0001,
        )
        val points = PointList().apply { corners.forEach { (lat, lon) -> add(lat, lon) } }
        val ids = (900 until 900 + corners.size - 1).toList()
        return ResponsePath().setPoints(points).setDistance((15 + 4 * sideKm) * 1000).setTime(20 * 60000L).apply {
            addPathDetails(mapOf("edge_id" to ids.mapIndexed { index, id -> PathDetail(id).apply { first = index; last = index + 1 } }))
        }
    }

    /**
     * The ladder settled stop by stop (the search the joint choice picks
     * from): these tests pin its mechanics. [RideLadderTest] and the joint
     * tests below cover the choice.
     */
    private suspend fun resolveInTurn(
        optimum: suspend (RideStyle) -> ResponsePath,
        reweighted: suspend (RideStyle, Double) -> ResponsePath?,
        diversified: suspend (RideStyle, RideStyleSearch) -> ResponsePath? = { _, _ -> null },
        rings: List<List<GHPoint>> = emptyList(),
    ) = DistinctRideStyles.resolve(optimum, reweighted, diversified, rings, select = false)

    private fun assertPairwiseDifferent(paths: List<ResponsePath>) {
        val routes = paths.map(AlternativePolicy::cachedRoute)
        for (i in routes.indices) for (j in 0 until i) {
            assertFalse("Stops $i and $j repeat", DistinctRideStyles.sameRide(routes[i], routes[j]))
        }
    }

    /**
     * A ride north along the meridian, 1.11 km per edge. A [curvy] edge
     * zig-zags 17 m either side every 30 m (a 35 m corner radius) instead of
     * running straight, so the card's curvy share counts it whole.
     */
    private fun edgePath(edges: Iterable<Int>, minutesPerEdge: Double = 1.0, curvy: Set<Int> = emptySet()): ResponsePath {
        val ids = edges.toList()
        val points = PointList()
        val details = ArrayList<PathDetail>()
        var lat = 0.0
        points.add(lat, 0.0)
        for (id in ids) {
            val first = points.size() - 1
            if (id in curvy) {
                repeat(CURVY_STEPS) { step ->
                    lat += 0.01 / CURVY_STEPS
                    points.add(lat, if (step % 2 == 0 && step < CURVY_STEPS - 1) 0.00015 else 0.0)
                }
            } else {
                lat += 0.01
                points.add(lat, 0.0)
            }
            details += PathDetail(id).apply { this.first = first; last = points.size() - 1 }
        }
        return ResponsePath().setPoints(points).setDistance(ids.size * 1112.0)
            .setTime((ids.size * minutesPerEdge * 60000).toLong()).apply {
                addPathDetails(mapOf("edge_id" to details))
            }
    }

    private companion object {
        const val CURVY_STEPS = 37
        const val METRES_PER_DEGREE = 111_195.0
    }
}
