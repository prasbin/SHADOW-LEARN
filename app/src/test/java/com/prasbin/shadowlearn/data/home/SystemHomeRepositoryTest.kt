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
import com.prasbin.shadowlearn.data.intelligence.GroundedRetrievalRepository
import com.prasbin.shadowlearn.data.intelligence.RecommendationKind
import com.prasbin.shadowlearn.data.search.FtsIndex
import com.prasbin.shadowlearn.data.search.RoomBackedSqlExecutor
import com.prasbin.shadowlearn.data.search.SearchRepository
import kotlinx.coroutines.Dispatchers
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
            ),
            GroundedRetrievalRepository(
                db.academicDao(), db.extractionDao(),
                SearchRepository(
                    db.searchDao(),
                    FtsIndex(RoomBackedSqlExecutor(db.openHelper.writableDatabase)),
                    Dispatchers.Unconfined
                ),
                Dispatchers.Unconfined
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
    fun recommend_prefersDueOverEverythingElse() = runBlocking {
        // Engine-owned priority now; covered by RecommendationEngineTest.
        // Here: Room proof that file-only scope continues via focus.
        seedScope()
        val snap = runBlocking { repo.snapshot(semId, now) }
        assertEquals(RecommendationKind.RESUME, snap.recommendation.kind)
        assertEquals(HomeTarget.ACADEMIC, snap.recommendation.target)
    }

    private suspend fun seedMistakesOnDays(daysAgo: List<Long>, fileId: Long = 1, name: String = "notes.pdf") {
        val quiz: QuizDao = db.quizDao()
        for ((i, d) in daysAgo.withIndex()) {
            val sid = quiz.insertSession(
                QuizSession(
                    semesterId = semId, seed = 100L + i, totalQuestions = 1, correctCount = 0,
                    status = QuizSession.STATUS_COMPLETED,
                    startedAt = now - d * 86_400_000L - 1_000,
                    completedAt = now - d * 86_400_000L
                )
            )
            quiz.insertQuestions(
                listOf(
                    QuizQuestion(
                        sessionId = sid, position = 0, chunkId = 0, academicFileId = fileId,
                        questionType = "MCQ", prompt = "Q$i", optionsJson = "[\"a\",\"b\"]",
                        correctAnswer = "a", userAnswer = "b", isCorrect = false,
                        srcFileName = name, srcFileType = "pdf", srcExcerpt = "excerpt"
                    )
                )
            )
        }
    }

    @Test
    fun observedWeakness_drivesRecommendationAndWeakArea() = runBlocking {
        seedScope()
        seedMistakesOnDays(listOf(9, 5, 2))

        val snap = repo.snapshot(semId, now)

        assertEquals(RecommendationKind.WEAKNESS_OBSERVED, snap.recommendation.kind)
        assertEquals(HomeTarget.CARDS, snap.recommendation.target)
        assertEquals("Focus: notes.pdf.", snap.recommendation.text)
        assertTrue(snap.weakAreas.any { it.label == "notes.pdf" })
    }

    @Test
    fun scopeWithoutMaterial_recommendsImport() = runBlocking {
        val academic: AcademicDao = db.academicDao()
        val yearId = academic.insertYear(AcademicYear(name = "Year 1", sortOrder = 0))
        val bareSem = academic.insertSemester(Semester(yearId = yearId, name = "S1", sortOrder = 0))

        val snap = repo.snapshot(bareSem, now)

        assertEquals(RecommendationKind.SETUP, snap.recommendation.kind)
        assertEquals("Import your first semester ZIP.", snap.recommendation.text)
    }

    @Test
    fun otherSemesterWeakness_isIgnored() = runBlocking {
        seedScope()
        val academic: AcademicDao = db.academicDao()
        val year2 = academic.insertYear(AcademicYear(name = "Year 2", sortOrder = 1))
        val sem2 = academic.insertSemester(Semester(yearId = year2, name = "S2", sortOrder = 0))
        val mod2 = academic.insertModule(Module(semesterId = sem2, name = "M2"))
        val week2 = academic.insertWeek(Week(moduleId = mod2, weekNumber = 1, title = "W1"))
        val file2 = academic.insertFile(
            AcademicFile(
                weekId = week2, fileName = "other.pdf", filePath = "/tmp/other.pdf",
                fileType = "pdf", sha256 = "def", relativePath = "other.pdf"
            )
        )
        val savedSem = semId
        semId = sem2
        seedMistakesOnDays(listOf(9, 5, 2), fileId = file2, name = "other.pdf")
        semId = savedSem

        val snap = repo.snapshot(semId, now)

        assertTrue(
            snap.recommendation.kind != RecommendationKind.WEAKNESS_OBSERVED &&
                snap.recommendation.kind != RecommendationKind.WEAKNESS_POSSIBLE
        )
        assertTrue(snap.weakAreas.none { it.label == "other.pdf" })
    }

    @Test
    fun staleMistakes_fallThrough() = runBlocking {
        seedScope()
        seedMistakesOnDays(listOf(70, 65, 61))

        val snap = repo.snapshot(semId, now)

        assertTrue(
            snap.recommendation.kind != RecommendationKind.WEAKNESS_OBSERVED &&
                snap.recommendation.kind != RecommendationKind.WEAKNESS_POSSIBLE
        )
    }

    @Test
    fun observedWeakness_recommendationIsGrounded() = runBlocking {
        seedScope()
        seedMistakesOnDays(listOf(9, 5, 2))
        val fileId = db.extractionDao().filesOfSemester(semId).first().id
        db.extractionDao().insertChunks(
            listOf(
                com.prasbin.shadowlearn.data.db.DocumentChunk(
                    academicFileId = fileId, chunkIndex = 0, pageNumber = 2,
                    text = "Supervised learning uses labelled data.", charCount = 39
                )
            )
        )
        val weekId = db.academicDao()
            .getWeeks(db.academicDao().getModules(semId).first().id).first().id

        val snap = repo.snapshot(semId, now)

        assertEquals(RecommendationKind.WEAKNESS_OBSERVED, snap.recommendation.kind)
        assertEquals("notes.pdf", snap.recommendation.sourceFileName)
        assertEquals("Supervised learning uses labelled data.", snap.recommendation.sourceExcerpt)
        assertEquals(weekId, snap.recommendation.sourceWeekId)
        // T14: Home rows share the identical underlying source result.
        val area = snap.weakAreas.first { it.label == "notes.pdf" }
        assertEquals(snap.recommendation.sourceWeekId, area.sourceWeekId)
    }
}
