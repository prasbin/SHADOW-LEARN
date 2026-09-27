package com.prasbin.shadowlearn.data.progression

import com.prasbin.shadowlearn.data.cards.Rating

/**
 * Phase 9 progression math — PURE, deterministic, no Android/clock.
 *
 * Callers pass `now` explicitly so every result is reproducible in tests.
 * This is the ONE authoritative place for XP weights, the level formula,
 * and the streak definition; the repository and UI never recompute them.
 *
 * ## XP sources (existing persisted activity only)
 * - Quiz: 10 XP per correct answer, snapshotted in `quiz_sessions.xpEarned`
 *   by Phase 6 (`QuizRepository.XP_PER_CORRECT`).
 * - Flashcards: awarded ONCE per persisted review event, by rating:
 *   AGAIN = 0, HARD = 2, GOOD = 5, EASY = 8. Merely displaying a card or
 *   resuming a session awards nothing — only rows in
 *   `flashcard_review_events` count.
 *
 * ## Level formula
 * Level 1 starts at 0 XP. Required total XP to reach level N is
 * `100 * (N - 1)^2`:
 *
 * ```
 * L1 = 0   L2 = 100   L3 = 400   L4 = 900   L5 = 1600   ...
 * ```
 *
 * There is no maximum level. All arithmetic is integer-only.
 *
 * ## Streak
 * Consecutive calendar days (UTC day index) ending today, or yesterday when
 * today has no activity yet. Same-day activity counts once; a missing day
 * resets. Derived from real quiz `completedAt` + review `reviewedAt` times.
 */
object ProgressionCalculator {

    /** Quiz XP rule (mirrors Phase 6). */
    const val QUIZ_XP_PER_CORRECT = 10

    const val XP_AGAIN = 0
    const val XP_HARD = 2
    const val XP_GOOD = 5
    const val XP_EASY = 8

    private const val DAY_MS = 86_400_000L

    /** Coefficient of `(N-1)^2` in the level threshold. */
    private const val LEVEL_STEP = 100L

    /** XP awarded for one flashcard [rating] (typed overload). */
    fun xpForRating(rating: Rating): Int = when (rating) {
        Rating.AGAIN -> XP_AGAIN
        Rating.HARD -> XP_HARD
        Rating.GOOD -> XP_GOOD
        Rating.EASY -> XP_EASY
    }

    /** XP awarded for one persisted review rating string (unknown ⇒ 0). */
    fun xpForRating(rating: String): Int = when (rating.uppercase()) {
        "AGAIN" -> XP_AGAIN
        "HARD" -> XP_HARD
        "GOOD" -> XP_GOOD
        "EASY" -> XP_EASY
        else -> 0
    }

    /** Total flashcard XP from persisted review-event ratings. */
    fun flashcardXp(ratings: Iterable<String>): Int =
        ratings.sumOf { xpForRating(it) }

    /** Total XP required to REACH [level] (`100*(N-1)^2`; level ≤ 1 ⇒ 0). */
    fun xpToReachLevel(level: Int): Long {
        if (level <= 1) return 0L
        val n = (level - 1).toLong()
        return LEVEL_STEP * n * n
    }

    /** Highest level whose threshold is ≤ [xp]. Level 1 at 0 XP, unbounded. */
    fun levelFromXp(xp: Long): Int {
        val safe = xp.coerceAtLeast(0L)
        return isqrt(safe / LEVEL_STEP) + 1
    }

    /** XP earned inside the current level (always ≥ 0). */
    fun xpIntoCurrentLevel(xp: Long): Long {
        val safe = xp.coerceAtLeast(0L)
        return safe - xpToReachLevel(levelFromXp(safe))
    }

    /**
     * Total XP span of the current level — the denominator of progress
     * toward the next level (`threshold(N+1) - threshold(N)`).
     */
    fun xpRequiredForNextLevel(xp: Long): Long {
        val level = levelFromXp(xp)
        return xpToReachLevel(level + 1) - xpToReachLevel(level)
    }

    /** XP still needed to reach the next level (always ≥ 0). */
    fun xpToNextLevel(xp: Long): Long =
        (xpRequiredForNextLevel(xp) - xpIntoCurrentLevel(xp)).coerceAtLeast(0L)

    /**
     * Consecutive calendar-day streak ending today, or yesterday when today
     * has no activity. Same day counts once; a gap resets the count.
     */
    fun streakFrom(activityTimes: List<Long>, now: Long): Int {
        if (activityTimes.isEmpty()) return 0
        val today = now / DAY_MS
        val days = activityTimes.map { it / DAY_MS }.toSet()
        var day = today
        if (day !in days) day -= 1 // a missed today keeps yesterday's streak
        var streak = 0
        while (day in days) {
            streak++
            day--
        }
        return streak
    }

    /** Integer floor square root (no floating point, exact for Long). */
    internal fun isqrt(n: Long): Int {
        if (n < 0L) return 0
        if (n < 2L) return n.toInt()
        var x = n
        var y = (x + 1L) / 2L
        while (y < x) {
            x = y
            y = (x + n / x) / 2L
        }
        return x.toInt()
    }
}