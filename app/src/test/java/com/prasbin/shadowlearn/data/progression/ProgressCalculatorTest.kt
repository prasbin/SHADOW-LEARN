package com.prasbin.shadowlearn.data.progression

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 12 pure formula tests: four binary milestones × 25 points,
 * integer-only, empty → 0. No Android, no database.
 */
class ProgressCalculatorTest {

    @Test
    fun emptyIsZero() {
        assertEquals(0, ProgressCalculator.progressOf(false, false, false, false))
        assertEquals("", ProgressCalculator.basisOf(false, false, false, false))
    }

    @Test
    fun importAloneIs25() {
        assertEquals(25, ProgressCalculator.progressOf(true, false, false, false))
    }

    @Test
    fun extractionAloneIs25() {
        assertEquals(25, ProgressCalculator.progressOf(false, true, false, false))
    }

    @Test
    fun quizAloneIs25() {
        assertEquals(25, ProgressCalculator.progressOf(false, false, true, false))
    }

    @Test
    fun reviewAloneIs25() {
        assertEquals(25, ProgressCalculator.progressOf(false, false, false, true))
    }

    @Test
    fun pairsAre50() {
        assertEquals(50, ProgressCalculator.progressOf(true, true, false, false))
        assertEquals(50, ProgressCalculator.progressOf(true, false, true, false))
        assertEquals(50, ProgressCalculator.progressOf(false, false, true, true))
    }

    @Test
    fun triplesAre75() {
        assertEquals(75, ProgressCalculator.progressOf(true, true, true, false))
        assertEquals(75, ProgressCalculator.progressOf(false, true, true, true))
    }

    @Test
    fun allMilestonesAre100() {
        assertEquals(100, ProgressCalculator.progressOf(true, true, true, true))
    }

    @Test
    fun basisListsReachedMilestonesWithCount() {
        assertEquals(
            "import · quiz (2 of 4)",
            ProgressCalculator.basisOf(true, false, true, false)
        )
        assertEquals(
            "import · extraction · quiz · review (4 of 4)",
            ProgressCalculator.basisOf(true, true, true, true)
        )
    }

    @Test
    fun basisIsEmptyWhenNothingReached() {
        assertEquals("", ProgressCalculator.basisOf(false, false, false, false))
    }
}
