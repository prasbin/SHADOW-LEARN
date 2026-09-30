package com.prasbin.shadowlearn.data.progression

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.DocumentChunk
import com.prasbin.shadowlearn.data.db.ExtractionDao
import com.prasbin.shadowlearn.data.db.FlashcardDao
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.QuizDao
import com.prasbin.shadowlearn.data.db.QuizSession
import com.prasbin.shadowlearn.data.db.ReviewEvent
import com.prasbin.shadowlearn.data.db.ReviewSession
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.db.Week
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 12 repository tests: Progress % aggregation over REAL Room rows.
 * Each milestone fires once no matter how many rows back it — volumes
 * never inflate the score (no double-counting).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AcademicProgressRepositoryTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var academicDao: AcademicDao
    private lateinit var extractionDao: ExtractionDao
    private lateinit var quizDao: QuizDao
    private lateinit var flashcardDao: FlashcardDao
    private lateinit var repo: AcademicProgressRepository

    private val now = 1_704_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ShadowLearnDatabase::class.java
        ).allowMainThreadQueries().build()
        academicDao = db.academicDao()
        extractionDao = db.extractionDao()
        quizDao = db.quizDao()
        flashcardDao = db.flashcardDao()
        repo = AcademicProgressRepository(
            academicDao, extractionDao, quizDao, flashcardDao, Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun seedFile(): Long {
        val yearId = academicDao.insertYear(AcademicYear(name = "Year 1", sortOrder = 0))
        val semId = academicDao.insertSemester(Semester(yearId = yearId, name = "S1", sortOrder = 0))
        val modId = academicDao.insertModule(Module(semesterId = semId, name = "M"))
        val weekId = academicDao.insertWeek(Week(moduleId = modId, weekNumber = 1, title = "W1"))
        return academicDao.insertFile(
            AcademicFile(
                weekId = weekId, fileName = "notes.pdf", filePath = "/tmp/notes.pdf",
                fileType = "pdf", sha256 = "abc", relativePath = "notes.pdf"
            )
        )
    }

    private suspend fun seedChunk(fileId: Long) {
        extractionDao.insertChunks(
            listOf(DocumentChunk(academicFileId = fileId, chunkIndex = 0, text = "some text", charCount = 9))
        )
    }

    private suspend fun completedQuiz() {
        quizDao.insertSession(
            QuizSession(
                semesterId = 1, seed = 1, totalQuestions = 10,
                correctCount = 4, xpEarned = 40, streak = 1,
                status = QuizSession.STATUS_COMPLETED, completedAt = now
            )
        )
    }

    private suspend fun reviewEvent() {
        val sessionId = flashcardDao.insertReviewSession(ReviewSession(deckId = 1))
        flashcardDao.insertEvent(
            ReviewEvent(
                sessionId = sessionId, flashcardId = 1, rating = "GOOD",
                reviewedAt = now, previousEaseFactor = 2.5, newEaseFactor = 2.5,
                previousIntervalDays = 0, newIntervalDays = 1, retained = true
            )
        )
    }

    @Test
    fun emptyDatabaseIsZeroWithEmptyBasis() = runBlocking {
        val p = repo.current()
        assertEquals(0, p.percent)
        assertEquals("", p.basis)
    }

    @Test
    fun filesOnlyIs25() = runBlocking {
        seedFile()
        val p = repo.current()
        assertEquals(25, p.percent)
        assertEquals("import (1 of 4)", p.basis)
    }

    @Test
    fun chunksPushImportTo50() = runBlocking {
        seedChunk(seedFile())
        val p = repo.current()
        assertEquals(50, p.percent)
        assertEquals("import · extraction (2 of 4)", p.basis)
    }

    @Test
    fun completedQuizAloneIs25() = runBlocking {
        completedQuiz()
        val p = repo.current()
        assertEquals(25, p.percent)
        assertEquals("quiz (1 of 4)", p.basis)
    }

    @Test
    fun reviewEventAloneIs25() = runBlocking {
        reviewEvent()
        val p = repo.current()
        assertEquals(25, p.percent)
        assertEquals("review (1 of 4)", p.basis)
    }

    @Test
    fun fullHistoryIs100() = runBlocking {
        seedChunk(seedFile())
        completedQuiz()
        reviewEvent()
        val p = repo.current()
        assertEquals(100, p.percent)
        assertEquals("import · extraction · quiz · review (4 of 4)", p.basis)
    }

    @Test
    fun volumesNeverDoubleCount() = runBlocking {
        val fileId = seedFile()
        seedFile()
        seedChunk(fileId)
        seedChunk(fileId)
        completedQuiz()
        completedQuiz()
        reviewEvent()
        reviewEvent()
        reviewEvent()
        assertEquals(100, repo.current().percent)
    }

    @Test
    fun inProgressQuizIsIgnored() = runBlocking {
        seedFile()
        quizDao.insertSession(
            QuizSession(
                semesterId = 1, seed = 1, totalQuestions = 10,
                status = QuizSession.STATUS_IN_PROGRESS, completedAt = null
            )
        )
        assertEquals(25, repo.current().percent)
    }

    @Test
    fun milestones_emptyDatabaseAllFalse() = runBlocking {
        val m = repo.observeMilestones().first()
        assertEquals(false, m.hasImport)
        assertEquals(false, m.hasExtraction)
        assertEquals(false, m.hasQuiz)
        assertEquals(false, m.hasReview)
    }

    @Test
    fun milestones_partialHistoryOnlyReachedFlags() = runBlocking {
        seedChunk(seedFile())
        val m = repo.observeMilestones().first()
        assertEquals(true, m.hasImport)
        assertEquals(true, m.hasExtraction)
        assertEquals(false, m.hasQuiz)
        assertEquals(false, m.hasReview)
    }

    @Test
    fun milestones_agreeWithPercentAndBasis() = runBlocking {
        seedChunk(seedFile())
        completedQuiz()
        reviewEvent()
        val m = repo.observeMilestones().first()
        assertEquals(true, m.hasImport)
        assertEquals(true, m.hasExtraction)
        assertEquals(true, m.hasQuiz)
        assertEquals(true, m.hasReview)
        val p = repo.current()
        assertEquals(100, p.percent)
        assertEquals("import · extraction · quiz · review (4 of 4)", p.basis)
    }
}
