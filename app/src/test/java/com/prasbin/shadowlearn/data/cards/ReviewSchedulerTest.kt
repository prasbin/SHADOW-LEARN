package com.prasbin.shadowlearn.data.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8 tests for the pure SM-2-lite scheduler. Every function is
 * deterministic: no wall-clock access, explicit `nowMs` parameter only.
 */
class ReviewSchedulerTest {

    private val day = ReviewScheduler.DAY_MS
    private val base = 1_700_000_000_000L // an arbitrary fixed instant

    // ---- day boundary -----------------------------------------------------

    @Test
    fun dayStart_truncatesToUtcMidnight() {
        val midday = 1_700_000_000_000L
        val start = ReviewScheduler.dayStart(midday)
        assertEquals(0L, start % day)
        assertTrue(start <= midday)
        assertTrue(start + day > midday)
    }

    @Test
    fun dayStart_isIdempotent() {
        val s = ReviewScheduler.dayStart(base)
        assertEquals(s, ReviewScheduler.dayStart(s))
    }

    @Test
    fun dueAt_landsOnDayStartPlusInterval() {
        val s = ReviewScheduler.schedule(Rating.GOOD, 2.5, 0, base)
        assertEquals(1, s.intervalDays)
        assertEquals(ReviewScheduler.dayStart(base) + day, s.dueAt)
    }

    // ---- AGAIN ------------------------------------------------------------

    @Test
    fun again_resetsIntervalToZero() {
        val s = ReviewScheduler.schedule(Rating.AGAIN, 2.5, 7, base)
        assertEquals(0, s.intervalDays)
        assertFalse(s.retained)
    }

    @Test
    fun again_reducesEaseBy020() {
        val s = ReviewScheduler.schedule(Rating.AGAIN, 2.5, 3, base)
        assertEquals(2.30, s.easeFactor, 0.001)
    }

    @Test
    fun again_easeNeverDropsBelowMinimum() {
        val s = ReviewScheduler.schedule(Rating.AGAIN, 1.3, 0, base)
        assertEquals(ReviewScheduler.MIN_EASE, s.easeFactor, 0.001)
    }

    @Test
    fun again_dueAtIsTodayStart() {
        val s = ReviewScheduler.schedule(Rating.AGAIN, 2.5, 7, base)
        assertEquals(ReviewScheduler.dayStart(base), s.dueAt)
    }

    // ---- HARD -------------------------------------------------------------

    @Test
    fun hard_reducesEaseBy015() {
        val s = ReviewScheduler.schedule(Rating.HARD, 2.5, 4, base)
        assertEquals(2.35, s.easeFactor, 0.001)
    }

    @Test
    fun hard_multipliesIntervalBy12() {
        val s = ReviewScheduler.schedule(Rating.HARD, 2.5, 5, base)
        assertEquals(6, s.intervalDays)
    }

    @Test
    fun hard_firstIntervalIsAtLeastOne() {
        val s = ReviewScheduler.schedule(Rating.HARD, 2.5, 0, base)
        assertEquals(1, s.intervalDays)
        assertTrue(s.retained)
    }

    // ---- GOOD -------------------------------------------------------------

    @Test
    fun good_keepsEaseUnchanged() {
        val s = ReviewScheduler.schedule(Rating.GOOD, 2.5, 3, base)
        assertEquals(2.5, s.easeFactor, 0.001)
    }

    @Test
    fun good_firstIntervalIsOne() {
        val s = ReviewScheduler.schedule(Rating.GOOD, 2.5, 0, base)
        assertEquals(1, s.intervalDays)
        assertTrue(s.retained)
    }

    @Test
    fun good_multipliesPreviousByEase() {
        // prev=4, ease=2.5 → round(4 * 2.5) = 10
        val s = ReviewScheduler.schedule(Rating.GOOD, 2.5, 4, base)
        assertEquals(10, s.intervalDays)
    }

    // ---- EASY -------------------------------------------------------------

    @Test
    fun easy_increasesEaseBy015() {
        val s = ReviewScheduler.schedule(Rating.EASY, 2.5, 3, base)
        assertEquals(2.65, s.easeFactor, 0.001)
    }

    @Test
    fun easy_firstIntervalIsFour() {
        val s = ReviewScheduler.schedule(Rating.EASY, 2.5, 0, base)
        assertEquals(4, s.intervalDays)
        assertTrue(s.retained)
    }

    @Test
    fun easy_multipliesByEaseAnd13() {
        // prev=4, ease=2.5 → round(4 * 2.5 * 1.3) = 13
        val s = ReviewScheduler.schedule(Rating.EASY, 2.5, 4, base)
        assertEquals(13, s.intervalDays)
    }

    @Test
    fun easy_easeNeverExceedsMaximum() {
        val s = ReviewScheduler.schedule(Rating.EASY, 2.8, 1, base)
        assertEquals(ReviewScheduler.MAX_EASE, s.easeFactor, 0.001)
    }

    // ---- ease bounds ------------------------------------------------------

    @Test
    fun easeIsClampedToMinimumOnInput() {
        val s = ReviewScheduler.schedule(Rating.GOOD, 0.5, 1, base)
        assertEquals(ReviewScheduler.MIN_EASE, s.easeFactor, 0.001)
    }

    @Test
    fun easeIsClampedToMaximumOnInput() {
        val s = ReviewScheduler.schedule(Rating.GOOD, 9.9, 1, base)
        assertEquals(ReviewScheduler.MAX_EASE, s.easeFactor, 0.001)
    }

    // ---- interval bounds --------------------------------------------------

    @Test
    fun intervalIsClampedToMaximum() {
        val s = ReviewScheduler.schedule(Rating.GOOD, 2.5, 999_999, base)
        assertTrue(s.intervalDays <= ReviewScheduler.MAX_INTERVAL_DAYS)
    }

    @Test
    fun negativeIntervalIsClampedToZero() {
        val s = ReviewScheduler.schedule(Rating.GOOD, 2.5, -5, base)
        assertEquals(1, s.intervalDays) // GOOD with prev 0 → 1
    }

    // ---- determinism ------------------------------------------------------

    @Test
    fun sameInputsProduceIdenticalSchedule() {
        val a = ReviewScheduler.schedule(Rating.GOOD, 2.5, 4, base)
        val b = ReviewScheduler.schedule(Rating.GOOD, 2.5, 4, base)
        assertEquals(a.easeFactor, b.easeFactor, 0.0)
        assertEquals(a.intervalDays, b.intervalDays)
        assertEquals(a.dueAt, b.dueAt)
    }

    @Test
    fun differentTimesProduceSameDayBoundaryForSameInterval() {
        val morning = ReviewScheduler.dayStart(base) + 1_000L
        val evening = ReviewScheduler.dayStart(base) + day - 1_000L
        val a = ReviewScheduler.schedule(Rating.GOOD, 2.5, 3, morning)
        val b = ReviewScheduler.schedule(Rating.GOOD, 2.5, 3, evening)
        assertEquals(a.dueAt, b.dueAt)
    }
}
