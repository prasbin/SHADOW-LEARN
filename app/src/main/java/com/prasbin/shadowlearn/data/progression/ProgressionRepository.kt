package com.prasbin.shadowlearn.data.progression

import com.prasbin.shadowlearn.data.db.FlashcardDao
import com.prasbin.shadowlearn.data.db.QuizDao
import com.prasbin.shadowlearn.data.db.ReviewActivityRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/**
 * Phase 9 progression snapshot — all values DERIVED, never stored.
 *
 * @property totalXp quiz XP + flashcard review XP (existing persisted rows).
 * @property level 1-based level from [ProgressionCalculator.levelFromXp].
 * @property xpIntoLevel XP earned inside the current level.
 * @property xpForLevel total XP span of the current level (progress denominator).
 * @property xpToNextLevel XP remaining to reach the next level.
 * @property streak consecutive active calendar days ending today/yesterday.
 */
data class Progression(
    val totalXp: Long = 0L,
    val level: Int = 1,
    val xpIntoLevel: Long = 0L,
    val xpForLevel: Long = ProgressionCalculator.xpRequiredForNextLevel(0L),
    val xpToNextLevel: Long = ProgressionCalculator.xpRequiredForNextLevel(0L),
    val streak: Int = 0
) {
    /** Fractional progress toward the next level, clamped to [0, 1]. */
    val levelProgress: Float
        get() = if (xpForLevel <= 0L) 0f else (xpIntoLevel.toDouble() / xpForLevel).toFloat().coerceIn(0f, 1f)
}

/**
 * The ONE authoritative progression calculation path. Aggregates quiz XP
 * (Phase 6 `xpEarned`) with flashcard review XP (Phase 8 events) and derives
 * level + streak. The UI never queries Room for progression directly.
 *
 * No new tables and no migration: everything is derived from existing rows,
 * so nothing here can drift from the underlying history.
 */
class ProgressionRepository(
    private val quizDao: QuizDao,
    private val flashcardDao: FlashcardDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    /** Reactive snapshot that recomputes whenever quiz or review rows change. */
    fun observe(): Flow<Progression> = combine(
        quizDao.observeSummary(),
        quizDao.observeCompletionTimes(),
        flashcardDao.observeReviewActivity()
    ) { summary, quizTimes, activity ->
        compute(
            quizXp = summary.xp.toLong(),
            quizTimes = quizTimes,
            activity = activity,
            now = now()
        )
    }

    /** One-shot snapshot (used by tests and non-reactive callers). */
    suspend fun current(): Progression = withContext(dispatcher) {
        compute(
            quizXp = quizDao.totalXp().toLong(),
            quizTimes = quizDao.completionTimes(),
            activity = flashcardDao.reviewActivity(),
            now = now()
        )
    }

    private fun compute(
        quizXp: Long,
        quizTimes: List<Long>,
        activity: List<ReviewActivityRow>,
        now: Long
    ): Progression {
        val flashcardXp = ProgressionCalculator.flashcardXp(activity.map { it.rating }).toLong()
        val totalXp = quizXp + flashcardXp
        val activityTimes = quizTimes + activity.map { it.reviewedAt }
        return Progression(
            totalXp = totalXp,
            level = ProgressionCalculator.levelFromXp(totalXp),
            xpIntoLevel = ProgressionCalculator.xpIntoCurrentLevel(totalXp),
            xpForLevel = ProgressionCalculator.xpRequiredForNextLevel(totalXp),
            xpToNextLevel = ProgressionCalculator.xpToNextLevel(totalXp),
            streak = ProgressionCalculator.streakFrom(activityTimes, now)
        )
    }
}