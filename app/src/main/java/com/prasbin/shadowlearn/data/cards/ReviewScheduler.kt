package com.prasbin.shadowlearn.data.cards

/**
 * Phase 8 SM-2-lite spaced-repetition scheduler — pure, deterministic, and
 * free of any clock: every function takes `nowMs` explicitly and the
 * repository injects time at the boundary.
 *
 * Formula (documented once, here):
 * ```
 * AGAIN: ease  = max(MIN_EASE, ease - 0.20); interval = 0;          retained = false
 * HARD:  ease  = max(MIN_EASE, ease - 0.15); interval = max(1, floor(prev * 1.2)); retained = true
 * GOOD:  ease  = ease;                       interval = 1 if prev == 0 else round(prev * ease); retained = true
 * EASY:  ease  = min(MAX_EASE, ease + 0.15); interval = 4 if prev == 0 else round(prev * ease * 1.3); retained = true
 *
 * interval is clamped to [0, MAX_INTERVAL_DAYS].
 * dueAt    = utcDayStart(nowMs) + interval * DAY_MS   (the canonical timestamp)
 * ```
 *
 * Day-boundary rule: `dueAt` always lands on a UTC day start, so a card
 * reviewed at 23:59 and one at 00:01 with the same interval share the same
 * due date — review days are stable and testable. An AGAIN card
 * (interval 0) is due at today's start, i.e. immediately re-due within the
 * same session when the queue is recomputed.
 */
enum class Rating { AGAIN, HARD, GOOD, EASY }

data class CardSchedule(
    val easeFactor: Double,
    val intervalDays: Int,
    val dueAt: Long,
    val retained: Boolean
)

object ReviewScheduler {

    const val INITIAL_EASE = 2.5
    const val MIN_EASE = 1.3
    const val MAX_EASE = 2.8
    const val MAX_INTERVAL_DAYS = 36500 // 100 years: effectively "learned"
    const val DAY_MS = 86_400_000L

    /** UTC day start containing [nowMs] (pure day-boundary rule). */
    fun dayStart(nowMs: Long): Long = (nowMs / DAY_MS) * DAY_MS

    /**
     * Next schedule for a card reviewed with [rating] at [nowMs].
     * [ease] should already be within bounds (out-of-range inputs are
     * clamped first, so legacy/corrupt rows can never explode intervals).
     */
    fun schedule(
        rating: Rating,
        ease: Double,
        intervalDays: Int,
        nowMs: Long
    ): CardSchedule {
        val safeEase = ease.coerceIn(MIN_EASE, MAX_EASE)
        val safeInterval = intervalDays.coerceIn(0, MAX_INTERVAL_DAYS)
        val (newEase, newInterval, retained) = when (rating) {
            Rating.AGAIN -> Triple((safeEase - 0.20).coerceAtLeast(MIN_EASE), 0, false)
            Rating.HARD -> Triple(
                (safeEase - 0.15).coerceAtLeast(MIN_EASE),
                (safeInterval * 1.2).toInt().coerceAtLeast(1),
                true
            )
            Rating.GOOD -> Triple(
                safeEase,
                if (safeInterval == 0) 1 else Math.round(safeInterval * safeEase).toInt(),
                true
            )
            Rating.EASY -> Triple(
                (safeEase + 0.15).coerceAtMost(MAX_EASE),
                if (safeInterval == 0) 4 else Math.round(safeInterval * safeEase * 1.3).toInt(),
                true
            )
        }
        val clampedInterval = newInterval.coerceIn(0, MAX_INTERVAL_DAYS)
        return CardSchedule(
            easeFactor = newEase,
            intervalDays = clampedInterval,
            dueAt = dayStart(nowMs) + clampedInterval * DAY_MS,
            retained = retained
        )
    }
}
