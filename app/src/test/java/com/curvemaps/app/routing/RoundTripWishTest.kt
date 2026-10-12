package com.curvemaps.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RoundTripWishTest {
    /** 15:20 UTC on some day: an epoch minute that is 5 past a quarter hour. */
    private val now = (1_791_360_000_000L / 3_600_000L) * 3_600_000L + 15 * 3_600_000L + 20 * 60_000L
    private val nowMinute = RoundTripWish.epochMinute(now)

    @Test
    fun steppingOneMeasureNeverMovesTheOthers() {
        val start = RoundTripWish(
            measure = RoundTripMeasure.TIME,
            time = RoundTripLength.Time(90),
            distance = RoundTripLength.Distance(80),
            backByEpochMinute = nowMinute + 130,
        )
        val longer = start.stepped(1, now)
        assertEquals(RoundTripLength.Time(105), longer.time)
        assertEquals(start.distance, longer.distance)
        assertEquals(start.backByEpochMinute, longer.backByEpochMinute)

        val further = start.withMeasure(RoundTripMeasure.DISTANCE, now).stepped(1, now)
        assertEquals(RoundTripLength.Distance(90), further.distance)
        assertEquals(start.time, further.time)
        assertEquals(start.backByEpochMinute, further.backByEpochMinute)

        val later = start.withMeasure(RoundTripMeasure.BACK_BY, now).stepped(1, now)
        assertEquals(nowMinute + 145, later.backByEpochMinute)
        assertEquals(start.time, later.time)
        assertEquals(start.distance, later.distance)
    }

    @Test
    fun switchingMeasureKeepsEveryValue() {
        val start = RoundTripWish(time = RoundTripLength.Time(45), distance = RoundTripLength.Distance(200))
        val distance = start.withMeasure(RoundTripMeasure.DISTANCE, now)
        assertEquals(RoundTripLength.Distance(200), distance.length(now))
        val back = distance.withMeasure(RoundTripMeasure.TIME, now)
        assertEquals(RoundTripLength.Time(45), back.length(now))
        assertEquals(start.copy(measure = RoundTripMeasure.TIME), back)
    }

    @Test
    fun backByStartsAtNowPlusTheRideTimeAndThenHoldsStill() {
        val wish = RoundTripWish(time = RoundTripLength.Time(90))
        assertNull(wish.backByEpochMinute)
        // 15:20 + 1 h 30 = 16:50, up to the 17:00 mark.
        val chosen = wish.withMeasure(RoundTripMeasure.BACK_BY, now)
        assertEquals(nowMinute + 100, chosen.backByEpochMinute)
        assertEquals(RoundTripLength.Time(90), chosen.length(now))
        // Twenty minutes later the time holds; the ride left shrinks.
        val later = now + 20 * 60_000L
        assertEquals(nowMinute + 100, chosen.backBy(later))
        assertEquals(RoundTripLength.Time(75), chosen.length(later))
    }

    @Test
    fun backByBecomesTheRideTimeLeftOnTheQuarterHourGrid() {
        val wish = RoundTripWish(measure = RoundTripMeasure.BACK_BY, backByEpochMinute = nowMinute + 130)
        // 2 h 10 min until then: the wish is the 2 h it surely fits.
        assertEquals(RoundTripLength.Time(120), wish.length(now))
        assertEquals(120, wish.rideMinutesUntilBackBy(now))
        // It changes only when a quarter hour passes.
        assertEquals(wish.length(now), wish.length(now + 9 * 60_000L))
        assertEquals(RoundTripLength.Time(105), wish.length(now + 11 * 60_000L))
    }

    @Test
    fun aPassedBackByFallsBackToNowPlusTheRideTime() {
        val wish = RoundTripWish(
            measure = RoundTripMeasure.BACK_BY,
            time = RoundTripLength.Time(60),
            backByEpochMinute = nowMinute - 24 * 60,
        )
        // 15:20 + 1 h = 16:20, rounded up to the next quarter hour.
        assertEquals(nowMinute + 70, wish.backBy(now))
        assertTrue(wish.length(now) is RoundTripLength.Time)
    }

    @Test
    fun backByStaysWithinTheShortestAndLongestRide() {
        var wish = RoundTripWish(measure = RoundTripMeasure.BACK_BY, backByEpochMinute = nowMinute + 60)
        repeat(40) { wish = wish.stepped(-1, now) }
        assertEquals(RoundTripWish.earliestBackBy(now), wish.backBy(now))
        assertTrue(wish.atMinimum(now) && !wish.atMaximum(now))
        assertEquals(nowMinute + 25, wish.backBy(now))
        assertEquals(RoundTripLength.Time(RoundTripLength.MIN_MINUTES), wish.length(now))
        repeat(80) { wish = wish.stepped(1, now) }
        assertEquals(RoundTripWish.latestBackBy(now), wish.backBy(now))
        assertTrue(wish.atMaximum(now) && !wish.atMinimum(now))
        assertTrue(wish.length(now).amount <= RoundTripLength.MAX_MINUTES)
    }

    @Test
    fun everyBackByStepIsAQuarterHourAndStepsBack() {
        var wish = RoundTripWish(measure = RoundTripMeasure.BACK_BY).withMeasure(RoundTripMeasure.BACK_BY, now)
        repeat(40) { wish = wish.stepped(-1, now) }
        var guard = 0
        while (!wish.atMaximum(now)) {
            val up = wish.stepped(1, now)
            assertEquals(wish.backBy(now) + 15, up.backBy(now))
            assertEquals(0L, Math.floorMod(up.backBy(now), 15L))
            assertEquals(wish.backBy(now), up.stepped(-1, now).backBy(now))
            wish = up
            assertTrue(++guard < 100)
        }
    }

    @Test
    fun theStoredFormRoundTripsAndOlderFormsStillRead() {
        val wishes = listOf(
            RoundTripWish(),
            RoundTripWish(RoundTripMeasure.DISTANCE, RoundTripLength.Time(30), RoundTripLength.Distance(150)),
            RoundTripWish(RoundTripMeasure.BACK_BY, RoundTripLength.Time(240), RoundTripLength.Distance(10), nowMinute + 45),
        )
        wishes.forEach { assertEquals(it, RoundTripWish.parse(it.storageKey)) }
        // The single-length form written before the measure menu.
        assertEquals(
            RoundTripWish(measure = RoundTripMeasure.TIME, time = RoundTripLength.Time(90)),
            RoundTripWish.parse("time:90"),
        )
        assertEquals(
            RoundTripWish(measure = RoundTripMeasure.DISTANCE, distance = RoundTripLength.Distance(120)),
            RoundTripWish.parse("distance:120"),
        )
        assertEquals(RoundTripWish(), RoundTripWish.parse(null))
        assertEquals(RoundTripWish(), RoundTripWish.parse("speed:9"))
        assertEquals(RoundTripWish(), RoundTripWish.parse("measure:sideways,time:lots"))
        assertEquals(RoundTripLength.Distance(RoundTripLength.MAX_KILOMETRES), RoundTripWish.parse("distance:5000").distance)
    }

    @Test
    fun aLoadedRideSetsOnlyTheMeasureInUse() {
        val start = RoundTripWish(time = RoundTripLength.Time(60), distance = RoundTripLength.Distance(50))
        val timed = start.withRide(112L * 60_000, 118_400.0, now)
        assertEquals(RoundTripLength.Time(105), timed.time)
        assertEquals(start.distance, timed.distance)
        val measured = start.withMeasure(RoundTripMeasure.DISTANCE, now).withRide(112L * 60_000, 118_400.0, now)
        assertEquals(RoundTripLength.Distance(120), measured.distance)
        assertEquals(start.time, measured.time)
        val home = start.withMeasure(RoundTripMeasure.BACK_BY, now).withRide(112L * 60_000, 118_400.0, now)
        // 15:20 + 1 h 52 = 17:12, up to 17:15.
        assertEquals(nowMinute + 115, home.backByEpochMinute)
    }

    @Test
    fun randomStepsKeepEveryLengthInRange() {
        val random = Random(42)
        var wish = RoundTripWish()
        var clock = now
        repeat(2_000) {
            when (random.nextInt(4)) {
                0 -> wish = wish.withMeasure(RoundTripMeasure.entries[random.nextInt(3)], clock)
                1 -> clock += random.nextLong(0, 90) * 60_000L
                else -> wish = wish.stepped(if (random.nextBoolean()) 1 else -1, clock)
            }
            val length = wish.length(clock)
            assertEquals(length, length.coerced())
            val back = wish.backBy(clock)
            assertTrue(back >= RoundTripWish.earliestBackBy(clock) && back <= RoundTripWish.latestBackBy(clock))
            assertEquals(wish, RoundTripWish.parse(wish.storageKey))
            assertFalse(wish.atMinimum(clock) && wish.atMaximum(clock))
        }
    }
}
