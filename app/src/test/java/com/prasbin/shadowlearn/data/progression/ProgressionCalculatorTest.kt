package com.prasbin.shadowlearn.data.progression

import com.prasbin.shadowlearn.data.cards.Rating
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 9 pure progression tests: XP weights, the level formula
 * (`100*(N-1)^2`), current-level progress, and the calendar-day streak.
 * No Android, no clock — `now` is injected.
 */
class ProgressionCalculatorTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_704_000_000_000L // some Wednesday noon

    private fun atOffset(daysBefore: Long, hour: Int = 12) =
        now - daysBefore * day + hour * 3_600_000L

    // ---- level formula -----------------------------------------------------

    @Test
    fun zeroXpIsLevelOne() {
        assertEquals(1, ProgressionCalculator.levelFromXp(0))
    }

    @Test
    fun oneXpBelowThresholdStaysOnPreviousLevel() {
        assertEquals(1, ProgressionCalculator.levelFromXp(99))
        assertEquals(2, ProgressionCalculator.levelFromXp(399))
        assertEquals(3, ProgressionCalculator.levelFromXp(899))
    }

    @Test
    fun exactThresholdsMapToExpectedLevels() {
        assertEquals(2, ProgressionCalculator.levelFromXp(100))
        assertEquals(3, ProgressionCalculator.levelFromXp(400))
        assertEquals(4, ProgressionCalculator.levelFromXp(900))
        assertEquals(5, ProgressionCalculator.levelFromXp(1600))
    }

    @Test
    fun largeXpValueIsHandledExactly() {
        // 100*(N-1)^2 = 1_000_000 ⇒ (N-1)^2 = 10_000 ⇒ N-1 = 100 ⇒ N = 101.
        assertEquals(101, ProgressionCalculator.levelFromXp(1_000_000))
        assertEquals(100, ProgressionCalculator.levelFromXp(999_999))
    }

    @Test
    fun xpToReachLevelFollowsTheFormula() {
        assertEquals(0L, ProgressionCalculator.xpToReachLevel(1))
        assertEquals(100L, ProgressionCalculator.xpToReachLevel(2))
        assertEquals(400L, ProgressionCalculator.xpToReachLevel(3))
        assertEquals(900L, ProgressionCalculator.xpToReachLevel(4))
        assertEquals(1600L, ProgressionCalculator.xpToReachLevel(5))
    }

    @Test
    fun xpIntoCurrentLevelIsProgressWithinTheLevel() {
        assertEquals(0L, ProgressionCalculator.xpIntoCurrentLevel(0))
        assertEquals(0L, ProgressionCalculator.xpIntoCurrentLevel(100))
        assertEquals(50L, ProgressionCalculator.xpIntoCurrentLevel(150))
        assertEquals(0L, ProgressionCalculator.xpIntoCurrentLevel(400))
        assertEquals(50L, ProgressionCalculator.xpIntoCurrentLevel(450))
    }

    @Test
    fun xpRequiredForNextLevelIsTheLevelSpan() {
        assertEquals(100L, ProgressionCalculator.xpRequiredForNextLevel(0))
        assertEquals(300L, ProgressionCalculator.xpRequiredForNextLevel(100))
        assertEquals(500L, ProgressionCalculator.xpRequiredForNextLevel(400))
        assertEquals(700L, ProgressionCalculator.xpRequiredForNextLevel(900))
    }

    @Test
    fun xpToNextLevelIsRemainingToNextThreshold() {
        assertEquals(100L, ProgressionCalculator.xpToNextLevel(0))
        assertEquals(250L, ProgressionCalculator.xpToNextLevel(150))
        // Exactly on a threshold is the START of the next level, so the
        // remaining distance is that level's full span (300), not 0.
        assertEquals(300L, ProgressionCalculator.xpToNextLevel(100))
        assertEquals(450L, ProgressionCalculator.xpToNextLevel(450))
    }

    @Test
    fun integerSquareRootIsExact() {
        assertEquals(0, ProgressionCalculator.isqrt(0))
        assertEquals(1, ProgressionCalculator.isqrt(1))
        assertEquals(9, ProgressionCalculator.isqrt(99))
        assertEquals(10, ProgressionCalculator.isqrt(100))
        assertEquals(10, ProgressionCalculator.isqrt(120))
        assertEquals(100, ProgressionCalculator.isqrt(10_000))
    }

    @Test
    fun negativeXpIsClampedToLevelOne() {
        assertEquals(1, ProgressionCalculator.levelFromXp(-50))
        assertEquals(0L, ProgressionCalculator.xpIntoCurrentLevel(-50))
    }

    // ---- XP weights --------------------------------------------------------

    @Test
    fun quizXpPerCorrectIsTen() {
        assertEquals(10, ProgressionCalculator.QUIZ_XP_PER_CORRECT)
    }

    @Test
    fun ratingXpWeightsAreExact() {
        assertEquals(0, ProgressionCalculator.xpForRating(Rating.AGAIN))
        assertEquals(2, ProgressionCalculator.xpForRating(Rating.HARD))
        assertEquals(5, ProgressionCalculator.xpForRating(Rating.GOOD))
        assertEquals(8, ProgressionCalculator.xpForRating(Rating.EASY))
    }

    @Test
    fun ratingStringXpWeightsAreExact() {
        assertEquals(0, ProgressionCalculator.xpForRating("AGAIN"))
        assertEquals(2, ProgressionCalculator.xpForRating("HARD"))
        assertEquals(5, ProgressionCalculator.xpForRating("GOOD"))
        assertEquals(8, ProgressionCalculator.xpForRating("EASY"))
    }

    @Test
    fun unknownRatingStringAwardsZeroXp() {
        assertEquals(0, ProgressionCalculator.xpForRating("SUPER"))
        assertEquals(0, ProgressionCalculator.xpForRating(""))
    }

    @Test
    fun flashcardXpSumsPersistedEvents() {
        assertEquals(0, ProgressionCalculator.flashcardXp(listOf("AGAIN")))
        assertEquals(18, ProgressionCalculator.flashcardXp(listOf("GOOD", "GOOD", "EASY")))
        assertEquals(15, ProgressionCalculator.flashcardXp(listOf("HARD", "GOOD", "EASY", "AGAIN")))
    }

    @Test
    fun emptyActivityAwardsNoXp() {
        assertEquals(0, ProgressionCalculator.flashcardXp(emptyList()))
    }

    // ---- streak ------------------------------------------------------------

    @Test
    fun noActivityHasZeroStreak() {
        assertEquals(0, ProgressionCalculator.streakFrom(emptyList(), now))
        assertEquals(0, ProgressionCalculator.streakFrom(listOf(now - 10 * day), now))
    }

    @Test
    fun sameDayActivityCountsOnce() {
        assertEquals(1, ProgressionCalculator.streakFrom(listOf(atOffset(0, 9), atOffset(0, 14)), now))
    }

    @Test
    fun consecutiveDaysAccumulate() {
        assertEquals(3, ProgressionCalculator.streakFrom(listOf(atOffset(2), atOffset(1), atOffset(0)), now))
    }

    @Test
    fun missingDayResetsStreak() {
        assertEquals(1, ProgressionCalculator.streakFrom(listOf(atOffset(3), atOffset(2), atOffset(0)), now))
    }

    @Test
    fun yesterdayAloneKeepsOneDay() {
        assertEquals(1, ProgressionCalculator.streakFrom(listOf(atOffset(1)), now))
    }

    @Test
    fun todayWithoutYesterdayStartsFresh() {
        assertEquals(1, ProgressionCalculator.streakFrom(listOf(atOffset(0)), now))
    }

    @Test
    fun quizAndFlashcardActivityOnSameDayCountOnce() {
        val times = listOf(atOffset(0, 8), atOffset(0, 20))
        assertEquals(1, ProgressionCalculator.streakFrom(times, now))
    }

    @Test
    fun mixedActivityAcrossConsecutiveDaysAccumulates() {
        val times = listOf(atOffset(1, 9), atOffset(1, 20), atOffset(0, 10))
        assertEquals(2, ProgressionCalculator.streakFrom(times, now))
    }
}