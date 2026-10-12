package com.curvemaps.app.routing

import com.curvemaps.app.routing.DistinctRideStyles.Budget
import com.curvemaps.app.routing.RideLadder.REPEAT
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RideLadderTest {
    /**
     * Seven stops; [curviness] lists the rides (index 0 is Fastest's optimum).
     * Every ride fits every stop within budget unless [budget] says otherwise,
     * no two are the same ride unless [same] pairs them, and rides share no
     * roads unless [overlap] says so. Each stop's own optimum is [optima].
     */
    private fun problem(
        curviness: List<Double>,
        optima: IntArray = IntArray(7) { 0 },
        budget: (stop: Int, ride: Int) -> Budget? = { _, _ -> Budget.STYLE },
        same: Set<Pair<Int, Int>> = emptySet(),
        overlap: Map<Pair<Int, Int>, Double> = emptyMap(),
        gravel: Map<Int, Double> = emptyMap(),
        lookAround: Set<Int> = emptySet(),
        lasso: Set<Int> = emptySet(),
    ): RideLadder.Problem {
        val n = curviness.size
        val candidates = curviness.mapIndexed { i, c -> RideLadder.Candidate(c, gravel[i] ?: 0.0, i in lookAround, i in lasso) }
        val budgets = List(7) { stop -> List(n) { ride -> if (stop == 0) (if (ride == optima[0]) Budget.STYLE else null) else budget(stop, ride) } }
        val sameRide = Array(n) { a -> BooleanArray(n) { b -> a == b || (a to b) in same || (b to a) in same } }
        val shared = Array(n) { a -> DoubleArray(n) { b -> if (a == b) 1.0 else overlap[a to b] ?: overlap[b to a] ?: 0.0 } }
        return RideLadder.Problem(candidates, budgets, optima, sameRide, shared)
    }

    private fun assertDistinct(p: RideLadder.Problem, ladder: IntArray) {
        val rides = ladder.filter { it != REPEAT }
        for (i in rides.indices) for (j in 0 until i) assertFalse("rides ${rides[i]} and ${rides[j]} are the same ride", p.sameRide[rides[i]][rides[j]])
    }

    @Test
    fun theCurviestRideGoesToTheTopStopAndTheLadderClimbs() {
        // Fastest 4%; one great curvy road (30%, ride 6) and gentler different roads.
        val p = problem(listOf(0.04, 0.30, 0.06, 0.08, 0.10, 0.12, 0.14))
        // Settled stop by stop, Mostly fast took the curvy road and the stops above it fell back to straighter rides.
        val settled = intArrayOf(0, 1, 2, 3, 4, 5, 6)
        val ladder = RideLadder.choose(p, settled)
        assertEquals(1, ladder[6])
        // Every stop from Curvy up climbs (the fit judges Mostly fast and Balanced on a gentler scale).
        val curviness = ladder.map { p.candidates[it].curviness }
        assertEquals(curviness.drop(3).sorted(), curviness.drop(3))
        assertTrue(RideLadder.cost(p, ladder) < RideLadder.cost(p, settled))
        assertDistinct(p, ladder)
    }

    @Test
    fun aStopStraighterThanALowerOneCostsMoreThanANearCopyBelowIt() {
        // Two curvy roads: ride 1 (20%) and its near copy, ride 2 (21%, 96% the same roads but a different ride).
        val p = problem(
            listOf(0.04, 0.20, 0.21, 0.05, 0.06, 0.07, 0.08),
            overlap = mapOf((1 to 2) to 0.96),
        )
        val ladder = RideLadder.choose(p)
        // The curvy pair sits at the top, the near copy above the road it copies, not a straight ride above them.
        assertEquals(listOf(1, 2), ladder.takeLast(2))
        assertDistinct(p, ladder)
        // Putting the curvy road low and a straight ride on top scores worse.
        assertTrue(RideLadder.cost(p, intArrayOf(0, 1, 2, 3, 4, 5, 6)) > RideLadder.cost(p, ladder))
    }

    @Test
    fun theSameRideNeverAppearsTwice() {
        val p = problem(
            listOf(0.04, 0.10, 0.11, 0.12, 0.13, 0.14, 0.15, 0.16),
            same = setOf(1 to 2, 3 to 4, 5 to 6, 6 to 7),
        )
        val ladder = RideLadder.choose(p)
        assertDistinct(p, ladder)
        // At most four different rides beside Fastest ({1|2}, {3|4}, 5, 7): two stops repeat, no more.
        assertEquals(2, ladder.count { it == REPEAT })
    }

    @Test
    fun aStopRepeatsOnlyWhenNoRideFitsIt() {
        // Only five rides beside Fastest: one stop must repeat the stop below.
        val short = problem(listOf(0.04, 0.06, 0.08, 0.10, 0.12, 0.14))
        val ladder = RideLadder.choose(short)
        assertEquals(1, ladder.count { it == REPEAT })
        assertDistinct(short, ladder)
        // Each stop's own optimum: the stop with nothing of its own is Mostly fast, and it repeats Fastest
        // rather than taking a higher stop's ride and leaving the top stop to repeat.
        val own = problem(listOf(0.04, 0.06, 0.08, 0.10, 0.12, 0.14), optima = intArrayOf(0, 0, 1, 2, 3, 4, 5))
        assertEquals(listOf(0, REPEAT, 1, 2, 3, 4, 5), RideLadder.choose(own).toList())
        // A sixth ride, even beyond every budget, beats the repeat.
        val enough = problem(listOf(0.04, 0.06, 0.08, 0.10, 0.12, 0.14, 0.16), budget = { _, ride -> if (ride == 6) Budget.NONE else Budget.STYLE })
        assertFalse(REPEAT in RideLadder.choose(enough).toList())
    }

    @Test
    fun aLassoStandsInOnlyForARepeat() {
        val withLasso = problem(listOf(0.04, 0.06, 0.08, 0.10, 0.12, 0.14, 0.40), lasso = setOf(6), lookAround = setOf(6))
        // Six rides beside Fastest and one of them a lasso: it fills the stop that would otherwise repeat.
        val ladder = RideLadder.choose(withLasso)
        assertTrue(6 in ladder.toList())
        // With one more ordinary ride, the lasso is left out however curvy it is.
        val more = problem(listOf(0.04, 0.06, 0.08, 0.10, 0.12, 0.14, 0.40, 0.16), lasso = setOf(6), lookAround = setOf(6))
        assertFalse(6 in RideLadder.choose(more).toList())
    }

    @Test
    fun aStopsOwnOptimumAndARideWithinBudgetArePreferred() {
        // Rides 1 and 2 are alike for Mostly fast; ride 2 is its own optimum.
        val own = problem(listOf(0.04, 0.06, 0.06, 0.20, 0.21, 0.22, 0.23, 0.24), optima = intArrayOf(0, 2, 3, 4, 5, 6, 7))
        assertEquals(2, RideLadder.choose(own)[1])
        // Ride 1 is beyond every budget for Mostly fast, ride 2 within it.
        val budget = problem(listOf(0.04, 0.06, 0.06, 0.20, 0.21, 0.22, 0.23, 0.24), budget = { stop, ride -> if (stop == 1 && ride == 1) Budget.NONE else Budget.STYLE })
        assertEquals(2, RideLadder.choose(budget)[1])
    }

    @Test
    fun gravelAndLookAroundCost() {
        // Rides 1 and 2 are the same ride by roads, alike but for gravel / a forced via: only one can be shown, the plain sealed one.
        val rides = listOf(0.04, 0.06, 0.06, 0.08, 0.10, 0.12, 0.14, 0.16)
        for (p in listOf(problem(rides, same = setOf(1 to 2), gravel = mapOf(1 to 0.3)), problem(rides, same = setOf(1 to 2), lookAround = setOf(1)))) {
            val ladder = RideLadder.choose(p).toList()
            assertTrue(2 in ladder)
            assertFalse(1 in ladder)
        }
    }

    @Test
    fun fastestIsAlwaysItsOwnOptimum() {
        val p = problem(listOf(0.30, 0.02, 0.04, 0.06, 0.08, 0.10, 0.12, 0.14))
        assertEquals(0, RideLadder.choose(p)[0])
    }

    @Test
    fun anEqualSettledLadderIsKept() {
        // Seven distinct rides, each stop's own optimum, climbing: nothing beats the settled ladder.
        val optima = intArrayOf(0, 1, 2, 3, 4, 5, 6)
        val p = problem(listOf(0.04, 0.06, 0.08, 0.10, 0.12, 0.14, 0.16), optima = optima)
        assertArrayEquals(optima, RideLadder.choose(p, optima.copyOf()))
    }

    @Test
    fun randomPlansNeverScoreWorseThanTheSettledLadderAndStayDeterministic() {
        val random = Random(20261008)
        repeat(200) {
            val n = random.nextInt(4, 14)
            val curviness = List(n) { random.nextDouble(0.0, 0.35) }
            val same = buildSet { repeat(random.nextInt(0, n)) { add(random.nextInt(n) to random.nextInt(n)) } }.filter { it.first != it.second }.toSet()
            val overlap = buildMap { repeat(random.nextInt(0, n)) { put(random.nextInt(n) to random.nextInt(n), random.nextDouble()) } }
            val budgets = Array(7) { Array(n) { listOf(Budget.STYLE, Budget.RELAXED, Budget.NONE, null)[random.nextInt(4)] } }
            val optima = IntArray(7) { if (it == 0) 0 else random.nextInt(n) }
            val p = problem(
                curviness, optima = optima, same = same, overlap = overlap,
                budget = { stop, ride -> if (ride == optima[stop]) Budget.STYLE else budgets[stop][ride] },
                gravel = buildMap { repeat(2) { put(random.nextInt(n), random.nextDouble(0.0, 0.5)) } },
                lookAround = buildSet { repeat(2) { add(random.nextInt(n)) } },
            )
            // A settled ladder: stop by stop, the first different ride that fits, else a repeat.
            val settled = IntArray(7) { REPEAT }.also { it[0] = 0 }
            for (stop in 1 until 7) settled[stop] = (0 until n).firstOrNull { ride ->
                p.budgets[stop][ride] != null && settled.take(stop).none { it != REPEAT && p.sameRide[it][ride] }
            } ?: REPEAT
            val ladder = RideLadder.choose(p, settled.copyOf())
            assertTrue(RideLadder.cost(p, ladder) <= RideLadder.cost(p, settled) + 1e-9)
            assertDistinct(p, ladder)
            assertEquals(0, ladder[0])
            ladder.forEachIndexed { stop, ride -> if (ride != REPEAT) assertNotEquals(null, p.budgets[stop][ride]) }
            assertArrayEquals(ladder, RideLadder.choose(p, settled.copyOf()))
        }
    }
}
