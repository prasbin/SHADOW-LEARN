package com.prasbin.shadowlearn.data.quiz

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Planner tests (pure JVM): chunk selection must be honest about capacity,
 * never repeat a chunk inside a session, never exceed the requested total,
 * and always prefer recent chunk ids at equal capacity ("review what's due").
 */
class QuizPlannerTest {

    private fun eligible(count: Int, capacity: Int = 2): List<Pair<Long, Int>> =
        (1L..count.toLong()).map { it to capacity }

    @Test
    fun emptyInputsProduceEmptyPlan() {
        assertEquals(0, QuizPlanner.plan(emptyList(), 5, emptySet(), Random(1)).count())
        assertEquals(0, QuizPlanner.plan(eligible(3), 0, emptySet(), Random(1)).count())
    }

    @Test
    fun noChunkEverRepeatedInsideOneSession() {
        (0L..20L).forEach { seed ->
            repeat(25) {
                val plan = QuizPlanner.plan(eligible(10, 2), 8, (1L..4L).toSet(), Random(seed))
                val ids = plan.map { it.chunkId }
                assertEquals("no repeats expected for plan $plan", ids.distinct().size, ids.size)
                plan.forEach { a ->
                    assertTrue("count ${a.questionCount} must be within capacity", a.questionCount >= 1)
                }
            }
        }
    }

    @Test
    fun neverExceedsRequestedTotalOrPoolCapacity() {
        // pool capacity 6 < requested 10 => total must be 6
        val small = QuizPlanner.plan(eligible(3, 2), 10, emptySet(), Random(9))
        assertEquals(6, small.sumOf { it.questionCount })
        // requested 8, capacity >= 8 => exactly 8
        val normal = QuizPlanner.plan(eligible(10, 2), 8, emptySet(), Random(9))
        assertEquals(8, normal.sumOf { it.questionCount })
        // requested 1 => 1
        val one = QuizPlanner.plan(eligible(10, 2), 1, emptySet(), Random(9))
        assertEquals(1, one.sumOf { it.questionCount })
    }

    @Test
    fun capacityIsAlwaysRespected() {
        val pool = listOf(1L to 1, 2L to 3, 3L to 1, 4L to 2)
        (0L..20L).forEach { seed ->
            val plan = QuizPlanner.plan(pool, 6, emptySet(), Random(seed))
            plan.forEach { a ->
                val cap = pool.first { it.first == a.chunkId }.second
                assertTrue("${a.questionCount} must be ≤ capacity $cap", a.questionCount <= cap)
                assertTrue(a.questionCount >= 1)
            }
        }
    }

    @Test
    fun recentChunksArePickedFirstAtEqualCapacity() {
        val pool = (1L..8L).map { it to 2 }
        val recent = setOf(6L, 2L, 7L)
        val plan = QuizPlanner.plan(pool, 5, recent, Random(4))
        val chosen = plan.map { it.chunkId }
        assertTrue("recent {'6','2','7'} must be served first, got $chosen", chosen.take(3).toSet() == recent)
        assertTrue(chosen.toSet().containsAll(recent))
    }

    @Test
    fun requestWithinRecentCoverageStaysRecent() {
        val pool = (1L..8L).map { it to 1 }
        val recent = setOf(3L, 5L, 1L, 4L)
        val plan = QuizPlanner.plan(pool, 4, recent, Random(11))
        assertTrue(plan.map { it.chunkId }.toSet() == recent)
    }

    @Test
    fun ringNeverStarvesOtherChunks() {
        val pool = (1L..8L).map { it to 1 }
        val first = QuizPlanner.plan(pool, 4, emptySet(), Random(2)).map { it.chunkId }.toSet()
        assertEquals(4, first.size)
        // After covering `first`, a 4-question session must stay within it;
        // a 6-question session spills into the fresh remainder.
        val again = QuizPlanner.plan(pool, 4, first, Random(2))
        assertEquals(first, again.map { it.chunkId }.toSet())
        val wider = QuizPlanner.plan(pool, 6, first, Random(2))
        assertTrue(wider.map { it.chunkId }.toSet().containsAll(first))
        assertEquals(6, wider.map { it.chunkId }.distinct().size)
    }

    @Test
    fun requestedZeroAndNegativeAreSafelyEmpty() {
        assertEquals(0, QuizPlanner.plan(eligible(5), -3, emptySet(), Random(1)).count())
    }
}