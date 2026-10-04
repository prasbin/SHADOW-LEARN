package com.prasbin.shadowlearn.data.home

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.ExtractionDao
import com.prasbin.shadowlearn.data.db.Flashcard
import com.prasbin.shadowlearn.data.db.FlashcardDao
import com.prasbin.shadowlearn.data.db.FlashcardDeck
import com.prasbin.shadowlearn.data.db.ListenerDao
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.QuizDao
import com.prasbin.shadowlearn.data.db.QuizQuestion
import com.prasbin.shadowlearn.data.db.QuizSession
import com.prasbin.shadowlearn.data.db.ReviewSession
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.db.Week
import com.prasbin.shadowlearn.data.intelligence.EvidenceRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * System-home aggregation tests over REAL Room rows. Empty inputs must
 * yield honest empty states; every surfaced value must trace to a row
 * inserted below — nothing fabricated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemHomeRepositoryTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var repo: SystemHomeRepository

    private val now = 1_704_000_000_000L
    private var semId: Long = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ShadowLearnDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = SystemHomeRepository(
            db.academicDao(), db.extractionDao(), db.quizDao(),
            db.flashcardDao(), db.listenerDao(),
            EvidenceRepository(
                db.academicDao(), db.extractionDao(), db.quizDao(), db.flashcardDao()
            )
        )
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun seedScope(): Long {
        val academic: AcademicDao = db.academicDao()
        val yearId = academic.insertYear(AcademicYear(name = "Year 1", sortOrder = 0))
        semId = academic.insertSemester(Semester(yearId = yearId, name = "S1", sortOrder = 0))
        val modId = academic.insertModule(Module(semesterId = semId, name = "M"))
        val weekId = academic.insertWeek(Week(moduleId = modId, weekNumber = 1, title = "W1"))
        academic.insertFile(
            AcademicFile(
                weekId = weekId, fileName = "notes.pdf", filePath = "/tmp/notes.pdf",
                fileType = "pdf", sha256 = "abc", relativePath = "notes.pdf"
            )
        )
        return semId
    }

    @Test
    fun emptySemester_reportsHonestSetupState() = runBlocking {
        val academic: AcademicDao = db.academicDao()
        val yearId = academic.insertYear(AcademicYear(name = "Year 1", sortOrder = 0))
        val emptySem = academic.insertSemester(Semester(yearId = yearId, name = "S1", sortOrder = 0))

        val snap = repo.snapshot(emptySem, now)

        assertTrue(snap.objectives.isEmpty())
        assertTrue(snap.weakAreas.isEmpty())
        assertTrue(snap.activity.isEmpty())
        assertNull(snap.focus)
        assertEquals(HomeTarget.SETTINGS, snap.recommendation.target)
        assertEquals("Import your first semester ZIP.", snap.recommendation.text)
    }

    @Test
    fun importedFile_setsFocusAndContinue() = runBlocking {
        seedScope()

        val snap = repo.snapshot(semId, now)

        assertTrue(snap.focus != null)
        assertEquals("M", snap.focus!!.headline)
        assertTrue(snap.recommendation.text.startsWith("Continue:"))
    }

    @Test
    fun dueCard_drivesReviewObjectiveAndRecommendation() = runBlocking {
        seedScope()
        val cards: FlashcardDao = db.flashcardDao()
        val deckId = cards.insertDeck(FlashcardDeck(semesterId = semId, title = "D"))
        cards.insertCards(
            listOf(
                Flashcard(
                    deckId = deckId, front = "f", back = "b",
                    sourceLabel = "notes.pdf", contentKey = "k1", dueAt = now - 1_000
                )
            )
        )

        val snap = repo.snapshot(semId, now)

        val due = snap.objectives.firstOrNull { it.kind == ObjectiveKind.DUE_REVIEW }
        assertTrue(due != null)
        assertEquals(1, due!!.count)
        assertEquals("Review 1 due card first.", snap.recommendation.text)
        assertEquals(HomeTarget.CARDS, snap.recommendation.target)
    }

    @Test
    fun mistakeQuestion_surfacesWeakAreaAndActivity() = runBlocking {
        seedScope()
        val quiz: QuizDao = db.quizDao()
        val sessionId = quiz.insertSession(
            QuizSession(
                semesterId = semId, seed = 1, totalQuestions = 1, correctCount = 0,
                status = QuizSession.STATUS_COMPLETED,
                startedAt = now - 3_000, completedAt = now - 2_000
            )
        )
        quiz.insertQuestions(
            listOf(
                QuizQuestion(
                    sessionId = sessionId, position = 0, chunkId = 0, academicFileId = 1,
                    questionType = "MCQ", prompt = "What?", optionsJson = "[\"a\",\"b\"]",
                    correctAnswer = "a", userAnswer = "b", isCorrect = false,
                    srcFileName = "notes.pdf", srcFileType = "pdf", srcExcerpt = "excerpt"
                )
            )
        )
        val cards: FlashcardDao = db.flashcardDao()
        val deckId = cards.insertDeck(FlashcardDeck(semesterId = semId, title = "D"))
        cards.insertReviewSession(
            ReviewSession(
                deckId = deckId, startedAt = now - 5_000, completedAt = now - 4_000,
                reviewedCount = 5, retainedCount = 4,
                status = ReviewSession.STATUS_COMPLETED
            )
        )

        val snap = repo.snapshot(semId, now)

        assertTrue(snap.weakAreas.any { it.label == "notes.pdf" })
        assertEquals(HomeTarget.CARDS, snap.recommendation.target)
        assertTrue(snap.activity.any { it.text == "Quiz completed 0/1" })
        assertTrue(snap.activity.any { it.text == "Reviewed 5 cards" })
    }

    @Test
    fun recommend_prefersDueOverEverythingElse() {
        val rec = repo.recommend(
            dueTotal = 2,
            hasInProgressReview = true,
            hasInProgressQuiz = true,
            mistakeCount = 3,
            pendingTranscripts = 4,
            focus = FocusState("h", "d", HomeTarget.QUIZ),
            hasModules = true
        )
        assertEquals(HomeTarget.CARDS, rec.target)
        assertEquals("Review 2 due cards first.", rec.text)
    }
}
