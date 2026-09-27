package com.prasbin.shadowlearn.data.progression

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.FlashcardDao
import com.prasbin.shadowlearn.data.db.QuizDao
import com.prasbin.shadowlearn.data.db.QuizSession
import com.prasbin.shadowlearn.data.db.ReviewEvent
import com.prasbin.shadowlearn.data.db.ReviewSession
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 9 repository tests: progression aggregation over REAL Room rows —
 * quiz XP + flashcard review XP derived once per persisted event, level
 * from the documented formula, and streak from combined activity dates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProgressionRepositoryTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var quizDao: QuizDao
    private lateinit var flashcardDao: FlashcardDao
    private lateinit var repo: ProgressionRepository

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_704_000_000_000L // fixed reference "noon"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ShadowLearnDatabase::class.java
        ).allowMainThreadQueries().build()
        quizDao = db.quizDao()
        flashcardDao = db.flashcardDao()
        repo = ProgressionRepository(quizDao, flashcardDao, Dispatchers.Unconfined, now = { now })
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun completedQuiz(xp: Int, completedAt: Long = now) {
        quizDao.insertSession(
            QuizSession(
                semesterId = 1, seed = 1, totalQuestions = 10,
                correctCount = xp / 10, xpEarned = xp, streak = 1,
                status = QuizSession.STATUS_COMPLETED, completedAt = completedAt
            )
        )
    }

    private suspend fun reviewEvent(rating: String, reviewedAt: Long = now) {
        val sessionId = flashcardDao.insertReviewSession(ReviewSession(deckId = 1))
        flashcardDao.insertEvent(
            ReviewEvent(
                sessionId = sessionId, flashcardId = 1, rating = rating,
                reviewedAt = reviewedAt, previousEaseFactor = 2.5, newEaseFactor = 2.5,
                previousIntervalDays = 0, newIntervalDays = 1, retained = rating != "AGAIN"
            )
        )
    }

    @Test
    fun emptyDatabaseIsHonestZeroProgression() = runBlocking {
        val p = repo.current()
        assertEquals(0L, p.totalXp)
        assertEquals(1, p.level)
        assertEquals(0, p.streak)
        assertEquals(0L, p.xpIntoLevel)
        assertEquals(100L, p.xpForLevel)
    }

    @Test
    fun quizXpIsAggregated() = runBlocking {
        completedQuiz(40)
        assertEquals(40L, repo.current().totalXp)
    }

    @Test
    fun incompleteQuizIsIgnored() = runBlocking {
        quizDao.insertSession(
            QuizSession(
                semesterId = 1, seed = 1, totalQuestions = 10, correctCount = 10,
                xpEarned = 100, status = QuizSession.STATUS_IN_PROGRESS, completedAt = null
            )
        )
        assertEquals(0L, repo.current().totalXp)
    }

    @Test
    fun flashcardXpCountsEachPersistedEventOnce() = runBlocking {
        reviewEvent("GOOD")
        reviewEvent("GOOD")
        reviewEvent("EASY")
        assertEquals(18L, repo.current().totalXp) // 5 + 5 + 8
    }

    @Test
    fun againAwardsNoXp() = runBlocking {
        reviewEvent("AGAIN")
        assertEquals(0L, repo.current().totalXp)
    }

    @Test
    fun mixedQuizAndFlashcardXpAggregate() = runBlocking {
        completedQuiz(40)
        reviewEvent("HARD")   // 2
        reviewEvent("GOOD")   // 5
        reviewEvent("EASY")   // 8
        assertEquals(55L, repo.current().totalXp)
    }

    @Test
    fun levelMatchesThresholdFormula() = runBlocking {
        completedQuiz(100)
        assertEquals(2, repo.current().level)
        completedQuiz(300) // total 400
        assertEquals(3, repo.current().level)
    }

    @Test
    fun readingProgressionTwiceDoesNotDoubleAward() = runBlocking {
        reviewEvent("GOOD")
        val first = repo.current().totalXp
        val second = repo.current().totalXp
        assertEquals(first, second)
        assertEquals(5L, second)
    }

    @Test
    fun streakCombinesQuizAndFlashcardDays() = runBlocking {
        completedQuiz(10, completedAt = now - day) // yesterday
        reviewEvent("GOOD", reviewedAt = now)      // today
        assertEquals(2, repo.current().streak)
    }

    @Test
    fun xpProgressFieldsAreConsistent() = runBlocking {
        completedQuiz(150)
        val p = repo.current()
        assertEquals(2, p.level)
        assertEquals(50L, p.xpIntoLevel)
        assertEquals(300L, p.xpForLevel)
        assertEquals(250L, p.xpToNextLevel)
        assertEquals(50f / 300f, p.levelProgress, 0.0001f)
    }
}