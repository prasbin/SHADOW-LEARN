package com.prasbin.shadowlearn.data.intelligence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.DocumentChunk
import com.prasbin.shadowlearn.data.db.Flashcard
import com.prasbin.shadowlearn.data.db.FlashcardDeck
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.QuizQuestion
import com.prasbin.shadowlearn.data.db.QuizSession
import com.prasbin.shadowlearn.data.db.ReviewEvent
import com.prasbin.shadowlearn.data.db.ReviewSession
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.db.Week
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * I1 evidence aggregation over REAL Room rows: semester isolation,
 * file resolution (direct ids + chunk walk), and dangling-source caps.
 * Thresholds themselves are covered by [WeaknessEngineTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EvidenceRepositoryTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var repo: EvidenceRepository

    private val now = 1_704_000_000_000L
    private val day = 86_400_000L
    private var semId: Long = 0
    private var fileId: Long = 0
    private var chunkId: Long = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ShadowLearnDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = EvidenceRepository(
            db.academicDao(), db.extractionDao(), db.quizDao(), db.flashcardDao()
        )
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun seedScope(yearName: String = "Year 1", fileName: String = "notes.pdf"): Long {
        val academic = db.academicDao()
        val yearId = academic.insertYear(AcademicYear(name = yearName, sortOrder = 0))
        val sem = academic.insertSemester(Semester(yearId = yearId, name = "S1", sortOrder = 0))
        val modId = academic.insertModule(Module(semesterId = sem, name = "M"))
        val weekId = academic.insertWeek(Week(moduleId = modId, weekNumber = 3, title = "W3"))
        fileId = academic.insertFile(
            AcademicFile(
                weekId = weekId, fileName = fileName, filePath = "/tmp/$fileName",
                fileType = "pdf", sha256 = "abc-$fileName", relativePath = fileName
            )
        )
        db.extractionDao().insertChunks(
            listOf(DocumentChunk(academicFileId = fileId, chunkIndex = 0, text = "some text", charCount = 9))
        )
        chunkId = db.extractionDao().chunksForFile(fileId).first().id
        return sem
    }

    private suspend fun seedMistakes(sem: Long, file: Long, name: String, daysAgo: List<Long>) {
        val quiz = db.quizDao()
        var qid = 1L
        for ((i, d) in daysAgo.withIndex()) {
            val sid = quiz.insertSession(
                QuizSession(
                    semesterId = sem, seed = i.toLong(), totalQuestions = 1, correctCount = 0,
                    status = QuizSession.STATUS_COMPLETED,
                    startedAt = now - d * day - 1_000, completedAt = now - d * day
                )
            )
            quiz.insertQuestions(
                listOf(
                    QuizQuestion(
                        sessionId = sid, position = 0, chunkId = chunkId, academicFileId = file,
                        questionType = "MCQ", prompt = "Q$qid", optionsJson = "[\"a\",\"b\"]",
                        correctAnswer = "a", userAnswer = "b", isCorrect = false,
                        srcFileName = name, srcFileType = "pdf", srcExcerpt = "excerpt"
                    )
                )
            )
            qid++
        }
    }

    private suspend fun seedAgain(sem: Long, daysAgo: Long) {
        val cards = db.flashcardDao()
        val deckId = cards.insertDeck(FlashcardDeck(semesterId = sem, title = "D"))
        val cardIds = cards.insertCards(
            listOf(
                Flashcard(
                    deckId = deckId, front = "f", back = "b", sourceChunkId = chunkId,
                    sourceLabel = "notes.pdf · PDF · PAGE 1", contentKey = "k-$daysAgo"
                )
            )
        )
        val sessionId = cards.insertReviewSession(
            ReviewSession(deckId = deckId, startedAt = now - daysAgo * day, status = ReviewSession.STATUS_COMPLETED)
        )
        cards.insertEvent(
            ReviewEvent(
                sessionId = sessionId, flashcardId = cardIds.first(), rating = "AGAIN",
                reviewedAt = now - daysAgo * day,
                previousEaseFactor = 2.5, newEaseFactor = 2.3,
                previousIntervalDays = 1, newIntervalDays = 0, retained = false
            )
        )
    }

    @Test
    fun repeatedMistakes_resolveToObservedWithScope() = runBlocking {
        semId = seedScope()
        seedMistakes(semId, fileId, "notes.pdf", listOf(9, 5, 2))

        val out = repo.weaknessSignals(semId, now)

        assertEquals(1, out.size)
        assertEquals(WeaknessStatus.OBSERVED, out[0].status)
        assertEquals("notes.pdf", out[0].fileName)
        assertEquals("Week 3", out[0].weekLabel)
        assertEquals("M", out[0].moduleName)
        assertEquals(3, out[0].mistakeCount)
    }

    @Test
    fun otherSemesterEvidence_isIsolated() = runBlocking {
        semId = seedScope()
        seedMistakes(semId, fileId, "notes.pdf", listOf(9, 5, 2))
        val otherSem = seedScope(yearName = "Year 2", fileName = "other.pdf")
        seedMistakes(otherSem, fileId, "other.pdf", listOf(8, 4, 1))

        val out = repo.weaknessSignals(semId, now)

        assertEquals(1, out.size)
        assertEquals("notes.pdf", out[0].fileName)
    }

    @Test
    fun deletedFile_capsAtPossibleWithSnapshotName() = runBlocking {
        semId = seedScope()
        seedMistakes(semId, fileId, "notes.pdf", listOf(9, 5, 2, 1))
        db.academicDao().deleteFile(fileId)

        val out = repo.weaknessSignals(semId, now)

        assertEquals(1, out.size)
        assertEquals(WeaknessStatus.POSSIBLE, out[0].status)
        assertEquals("notes.pdf", out[0].fileName)
        assertTrue(out[0].dangling)
    }

    @Test
    fun agains_resolveThroughCardChunk() = runBlocking {
        semId = seedScope()
        seedAgain(semId, 3)
        seedAgain(semId, 2)
        seedAgain(semId, 1)

        val out = repo.weaknessSignals(semId, now)

        assertEquals(1, out.size)
        assertEquals(WeaknessStatus.OBSERVED, out[0].status)
        assertEquals("notes.pdf", out[0].fileName)
        assertEquals(3, out[0].againCount)
    }

    @Test
    fun emptySemester_yieldsNoSignals() = runBlocking {
        semId = seedScope()

        assertTrue(repo.weaknessSignals(semId, now).isEmpty())
    }
}
