package com.curvemaps.app.routing

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Chooses the seven stops' rides together from every ride the searches
 * found ([DistinctRideStyles.resolve]), instead of settling each stop in turn
 * from Fastest up.
 *
 * Settling in turn made each stop's choice without knowing what the stops
 * above it would need. On a corridor with few curvy roads that only moved a
 * defect up the slider: a low stop that took the curvy road left the top
 * stops nothing curvier (they fell back to straighter rides), and a low stop
 * that played safe was a near copy of the stop below. A ladder is scored as
 * a whole instead, by the chance a reviewer calls none of its stops
 * nonsense (the sum of −ln(1 − p) over its stops, [stopCost]), and the
 * cheapest ladder wins: every stop a different ride ([sameRide] is a hard
 * rule), each ride valid for its stop ([budgets]). Summing the chances p
 * themselves let one stop absorb every compromise (it can cost at most one
 * nonsense stop however bad it gets): Palmwoods to Kenilworth Very twisty
 * became 7% curvy above a 28% Twisty.
 *
 * A ride only moves up the slider: a stop may show a ride its own search
 * or a lower stop's search found ([DistinctRideStyles.resolve] marks which
 * candidates fit which stop), never a higher stop's. Chosen without that,
 * the low stops took the top stops' forced detours (Leyburn to Karara
 * Mostly fast rode Max twisty's 3.7-times-Fastest loop) and the review
 * found Mostly fast, Balanced and Curvy worse by as much as Max twisty
 * got better (50 Queensland corridors: Max twisty 36 → 21 nonsense,
 * Mostly fast to Curvy 33 → 55).
 *
 * The score is fitted on 3,039 stops 2–7 that the agent review (Muse Spark
 * 1.3 Contributor, xhigh) judged on 220 corridors, from the features of the
 * plans it saw. Its slopes come from a conditional logit with one stratum
 * per reviewed plan (478 plans with both verdicts) and the stop's level as
 * a feature, so they measure how ladders on the same corridor differ, which
 * is what this choice compares; each feature group improved the held-out
 * (by corridor) likelihood. The intercept calibrates it to the observed
 * nonsense rate. Time over the stop's own optimum matters by stop: Mostly
 * fast and Balanced lose with every extra minute (17% nonsense within 5% of
 * their optimum, 85% beyond twice it), while a longer ride than the often
 * timid optimum of Very twisty and Max twisty is judged better.
 */
internal object RideLadder {
    /** A ladder entry for a stop that repeats the stop below (no ride of its own). */
    const val REPEAT = -1

    /** A stop's chance of being called nonsense is capped here, so a ride within a budget costs at most −ln(0.001) + [FAST_STOP_RELAXED_COST] ≈ 7.9. */
    const val MAX_NONSENSE_CHANCE = 0.999

    /**
     * A repeat costs more than any ladder of rides could (six stops at most
     * ~7.9 each within a budget, [LASSO_COST] or less beyond), so a stop
     * repeats only when no ride is left for it: every
     * stop must be a ride of its own. A little more the higher the stop
     * ([REPEAT_STEP] per stop): where a corridor really holds too few rides,
     * the low stops show the fast road again and the top stops keep their
     * own curvy rides, rather than Mostly fast borrowing Max twisty's ride
     * and Max twisty repeating.
     */
    const val REPEAT_COST = 500.0
    const val REPEAT_STEP = 0.5

    /**
     * A lasso (a look-around ride that [DistinctRideStyles.isLasso], or any
     * ride whose loops are all at least a lasso long,
     * [DistinctRideStyles.loopKind]) costs more than any ladder of ordinary
     * rides and less than a repeat, so it stands in only for one.
     */
    const val LASSO_COST = 50.0

    /** Partial ladders kept per stop. Seven stops and a few dozen rides keep this exact in practice. */
    const val BEAM_WIDTH = 512

    // Fitted weights (see the class comment). Curviness is the card's share
    // (curvyDistanceFraction); gains are per 10 points.
    internal const val INTERCEPT = -2.1642
    /** Added per stop (Mostly fast first): higher stops are judged more harshly. */
    internal val LEVEL = doubleArrayOf(0.0, -0.2892, 1.1656, 1.4458, 2.3869, 2.4187)
    internal const val GAIN = -1.5934 // curvier than the stop below, capped at 10 points
    internal const val LOSS = 1.1172 // straighter than the stop below, capped at 10 points
    internal const val STRAIGHTER = 1.5329 // straighter than the curviest lower stop, capped at 20 points
    internal const val OVERLAP = 0.6699 // largest share of roads shared with a lower stop
    internal const val NEAR_COPY = 1.3454 // that share at least NEAR_COPY_SHARE
    /** Per 100% of time over the stop's own optimum (capped at 150%): Mostly fast, Balanced. */
    internal const val SLOW_LOW = 1.8936
    /**
     * The same for Curvy to Max twisty: none. The fit rewards a longer ride
     * there (−0.94 for Curvy and Twisty, −2.11 for Very twisty and Max
     * twisty), but the reviewed ladders never held the long rides that
     * reward would pick: chosen by the fitted score, Palmwoods to Kenilworth
     * Max twisty became a 160-minute ride beyond every budget on a 51-minute
     * corridor, and the Skyline Circuit hop's Balanced 21 minutes against
     * Fastest's 3. The style's own budget decides how long a ride may be.
     */
    internal const val SLOW_UPPER = 0.0
    internal const val RELAXED = 0.3862
    internal const val BEYOND_BUDGET = 1.2455
    internal const val LOOK_AROUND = 0.6015
    internal const val GRAVEL = 0.6380 // per 10% of the ride on unpaved surfaces, capped at 50%
    internal const val OWN_OPTIMUM = -1.1151
    internal const val NEAR_COPY_SHARE = 0.95

    /**
     * Added to a ride beyond every budget ([DistinctRideStyles.Budget.NONE])
     * besides its fitted [BEYOND_BUDGET]: more than any ladder of rides
     * could save, so a stop takes one only when no ride within a budget is
     * left for it, as the stop-by-stop search did (such a ride is "any
     * different ride", for a stop whose budget holds none; reviewed, 67–91%
     * of them were nonsense). Less than a lasso or a repeat. A product rule,
     * not a fit: with one whole nonsense stop instead, Toowoomba to Warwick
     * Very twisty took a local detour bubble.
     */
    const val BEYOND_BUDGET_COST = 40.0

    /**
     * Mostly fast and Balanced pay [FAST_STOP_RELAXED_COST] (one whole
     * nonsense stop) for a ride beyond their own budget within twice its
     * allowance ([DistinctRideStyles.Budget.RELAXED]): their budget is what
     * makes them fast. Reviewed, 69% of their rides at 1.5–2 times their
     * optimum's time were nonsense, 85% beyond that; chosen without it, Gore
     * to Amiens (gravel blocked) Mostly fast took 190 minutes on a 102-minute
     * corridor so the stops above could climb by a point.
     */
    const val FAST_STOPS = 2
    const val FAST_STOP_RELAXED_COST = 1.0

    /**
     * One ride the searches found. [curviness] is the card's curvy share,
     * [gravelShare] the share of its distance on unpaved surfaces.
     */
    data class Candidate(
        val curviness: Double,
        val gravelShare: Double = 0.0,
        val lookAround: Boolean = false,
        val lasso: Boolean = false,
    )

    /**
     * The choice for one plan. [budgets] is per stop (Fastest first) and per
     * candidate: the budget class the candidate falls in for that stop, or
     * null when it is no ride for that stop (it turns around or drives a road
     * twice). [ownOptimum] is each stop's own optimum's candidate index.
     * [sameRide] and [overlap] are symmetric matrices over the candidates.
     * [timeOverOptimum] is per stop and candidate: how much longer than the
     * stop's own optimum the candidate takes (0.2 = 20% longer).
     */
    class Problem(
        val candidates: List<Candidate>,
        val budgets: List<List<DistinctRideStyles.Budget?>>,
        val ownOptimum: IntArray,
        val sameRide: Array<BooleanArray>,
        val overlap: Array<DoubleArray>,
        val timeOverOptimum: List<DoubleArray> = List(budgets.size) { DoubleArray(candidates.size) },
    ) {
        val stops: Int get() = budgets.size

        init {
            require(budgets.all { it.size == candidates.size }) { "One budget per candidate and stop" }
            require(timeOverOptimum.size == budgets.size && timeOverOptimum.all { it.size == candidates.size }) { "One time per candidate and stop" }
            require(ownOptimum.size == budgets.size) { "One optimum per stop" }
        }
    }

    /**
     * The ladder (a candidate index or [REPEAT] per stop) with the lowest
     * [cost]. Fastest is always its own optimum. [seed] (the ladder settled
     * stop by stop) is kept when nothing beats it, so the choice is never
     * worse by this score. Ties keep the earlier-found candidates, so the
     * choice is deterministic.
     */
    fun choose(problem: Problem, seed: IntArray? = null): IntArray {
        val fastest = problem.ownOptimum[0]
        var beam = listOf(Partial(intArrayOf(fastest), 0.0))
        for (stop in 1 until problem.stops) {
            val next = ArrayList<Partial>()
            for (partial in beam) {
                for (candidate in problem.candidates.indices) {
                    if (problem.budgets[stop][candidate] == null) continue
                    if (partial.ladder.any { it != REPEAT && problem.sameRide[it][candidate] }) continue
                    next += Partial(partial.ladder + candidate, partial.cost + stopCost(problem, partial.ladder, candidate, stop))
                }
                next += Partial(partial.ladder + REPEAT, partial.cost + repeatCost(stop))
            }
            beam = next.sortedWith(PARTIAL_ORDER).take(BEAM_WIDTH)
        }
        val best = beam.first()
        if (seed != null && cost(problem, seed) <= best.cost + 1e-12) return seed
        return best.ladder
    }

    /** The ladder's score: −ln of the chance no stop 2–7 is called nonsense, plus repeats, lassos and rides beyond every budget. */
    fun cost(problem: Problem, ladder: IntArray): Double {
        var total = 0.0
        for (stop in 1 until ladder.size) {
            total += if (ladder[stop] == REPEAT) repeatCost(stop) else stopCost(problem, ladder.copyOfRange(0, stop), ladder[stop], stop)
        }
        return total
    }

    /**
     * −ln(1 − p), p the chance a reviewer calls [candidate] nonsense at
     * [stop] above the rides of [lower] (a [REPEAT] shows the ride of the
     * stop below it), or [LASSO_COST] for a lasso.
     */
    fun stopCost(problem: Problem, lower: IntArray, candidate: Int, stop: Int): Double {
        val ride = problem.candidates[candidate]
        if (ride.lasso) return LASSO_COST
        val shown = shownRides(lower)
        val below = problem.candidates[shown.last()].curviness
        val curviest = shown.maxOf { problem.candidates[it].curviness }
        val overlap = shown.maxOf { problem.overlap[it][candidate] }
        val c = ride.curviness
        val budget = problem.budgets[stop][candidate]
        var logit = INTERCEPT + LEVEL[(stop - 1).coerceIn(0, LEVEL.size - 1)]
        logit += GAIN * min(max(c - below, 0.0), 0.10) * 10
        logit += LOSS * min(max(below - c, 0.0), 0.10) * 10
        logit += STRAIGHTER * min(max(curviest - c, 0.0), 0.20) * 10
        logit += OVERLAP * overlap
        if (overlap >= NEAR_COPY_SHARE) logit += NEAR_COPY
        val slow = min(max(problem.timeOverOptimum[stop][candidate], 0.0), 1.5)
        logit += slow * if (stop <= 2) SLOW_LOW else SLOW_UPPER
        if (budget == DistinctRideStyles.Budget.RELAXED) logit += RELAXED
        if (budget == DistinctRideStyles.Budget.NONE) logit += BEYOND_BUDGET
        if (ride.lookAround) logit += LOOK_AROUND
        logit += GRAVEL * min(ride.gravelShare, 0.5) * 10
        if (problem.ownOptimum[stop] == candidate) logit += OWN_OPTIMUM
        val chance = min(1.0 / (1.0 + exp(-logit)), MAX_NONSENSE_CHANCE)
        return -ln(1.0 - chance) + when {
            budget == DistinctRideStyles.Budget.NONE -> BEYOND_BUDGET_COST
            budget == DistinctRideStyles.Budget.RELAXED && stop <= FAST_STOPS -> FAST_STOP_RELAXED_COST
            else -> 0.0
        }
    }

    fun repeatCost(stop: Int): Double = REPEAT_COST + REPEAT_STEP * stop

    /** The ride each of [lower] shows: a repeat shows the ride of the stop below it. */
    private fun shownRides(lower: IntArray): IntArray {
        val shown = IntArray(lower.size)
        for (index in lower.indices) shown[index] = if (lower[index] == REPEAT) shown[index - 1] else lower[index]
        return shown
    }

    private class Partial(val ladder: IntArray, val cost: Double)

    private val PARTIAL_ORDER = Comparator<Partial> { a, b ->
        val byCost = a.cost.compareTo(b.cost)
        if (byCost != 0) return@Comparator byCost
        for (index in a.ladder.indices) {
            // An earlier-found ride first; a repeat last.
            val x = a.ladder[index].let { if (it == REPEAT) Int.MAX_VALUE else it }
            val y = b.ladder[index].let { if (it == REPEAT) Int.MAX_VALUE else it }
            if (x != y) return@Comparator x.compareTo(y)
        }
        0
    }
}
