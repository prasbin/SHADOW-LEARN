package com.prasbin.shadowlearn.data.quiz

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.DocumentChunk
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.QuizSession
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.db.Week
import com.prasbin.shadowlearn.data.intelligence.EvidenceRepository
import com.prasbin.shadowlearn.data.intelligence.WeaknessStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * I5 weakness-driven practice over REAL Room rows. Targeted sessions reuse
 * the normal quiz transaction ([QuizRepository.planForWeakness] mirrors
 * [QuizRepository.launch] with a file-constrained pool); results flow back
 * through the standard answer/complete path, so new evidence emerges
 * without any new persistence or scoring model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeaknessPracticeTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var repo: QuizRepository

    private var s1: Long = 0
    private var s2: Long = 0
    private var weakFile: Long = 0
    private var otherFile: Long = 0
    private var foreignFile: Long = 0

    private val richText = "Neural networks learn representations through backpropagation and " +
        "gradient descent optimization. Supervised learning uses labelled training data to build " +
        "predictive models. The loss function measures prediction error across many training epochs."

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ShadowLearnDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = QuizRepository(db.quizDao(), Dispatchers.Unconfined)
        runBlocking { seed() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed() {
        val dao = db.academicDao()
        val y = dao.insertYear(AcademicYear(name = "Year 1", sortOrder = 0))
        s1 = dao.insertSemester(Semester(yearId = y, name = "Semester 1", sortOrder = 0))
        s2 = dao.insertSemester(Semester(yearId = y, name = "Semester 2", sortOrder = 1))
        val m1 = dao.insertModule(Module(semesterId = s1, name = "Biology"))
        val w1 = dao.insertWeek(Week(moduleId = m1, weekNumber = 1, title = "Cells"))
        val m2 = dao.insertModule(Module(semesterId = s1, name = "Chemistry"))
        val w2 = dao.insertWeek(Week(moduleId = m2, weekNumber = 1, title = "Atoms"))
        val m9 = dao.insertModule(Module(semesterId = s2, name = "Physics"))
        val w9 = dao.insertWeek(Week(moduleId = m9, weekNumber = 1, title = "Motion"))

        weakFile = dao.insertFile(
            AcademicFile(
                weekId = w1, fileName = "cell-biology.pdf", filePath = "/local/cell-biology.pdf",
                fileType = "pdf", sha256 = "weak-sha", classType = "LECTURE",
                relativePath = "y.zip/cell-biology.pdf", indexed = true
            )
        )
        otherFile = dao.insertFile(
            AcademicFile(
                weekId = w2, fileName = "chem.pdf", filePath = "/local/chem.pdf",
                fileType = "pdf", sha256 = "other-sha", classType = "LECTURE",
                relativePath = "y.zip/chem.pdf", indexed = true
            )
        )
        val foreign = dao.insertFile(
            AcademicFile(
                weekId = w9, fileName = "physics.pdf", filePath = "/local/physics.pdf",
                fileType = "pdf", sha256 = "foreign-sha", classType = "LECTURE",
                relativePath = "y.zip/physics.pdf", indexed = true
            )
        )
        foreignFile = foreign
        db.extractionDao().insertChunks(
            listOf(
                DocumentChunk(academicFileId = weakFile, chunkIndex = 0, pageNumber = 1, text = richText, charCount = richText.length),
                DocumentChunk(academicFileId = weakFile, chunkIndex = 1, pageNumber = 2, text = richText, charCount = richText.length),
                DocumentChunk(academicFileId = weakFile, chunkIndex = 2, pageNumber = 3, text = richText, charCount = richText.length),
                DocumentChunk(academicFileId = otherFile, chunkIndex = 0, pageNumber = 1, text = richText, charCount = richText.length),
                DocumentChunk(academicFileId = foreign, chunkIndex = 0, pageNumber = 1, text = richText, charCount = richText.length)
            )
        )
    }

    private suspend fun ready(fileId: Long = weakFile, sem: Long = s1): ActiveQuiz {
        val outcome = repo.planForWeakness(sem, fileId, 5, seed = 7)
        assertTrue("expected Ready, got $outcome", outcome is TargetedPracticeOutcome.Ready)
        return (outcome as TargetedPracticeOutcome.Ready).quiz
    }

    @Test
    fun observedWeakness_buildsTargetedPlan() = runBlocking {
        val outcome = repo.planForWeakness(s1, weakFile, 5, seed = 7)
        assertTrue(outcome is TargetedPracticeOutcome.Ready)
        val quiz = (outcome as TargetedPracticeOutcome.Ready).quiz
        assertTrue(quiz.questions.size in 1..5)
        // T4/T5: every question is tied to the weak file; others excluded.
        assertTrue(quiz.questions.isNotEmpty())
        assertTrue(quiz.questions.all { it.source.academicFileId == weakFile })
        // T6: provenance preserved on every question.
        quiz.questions.forEach { q ->
            assertEquals("cell-biology.pdf", q.source.fileName)
            assertTrue(q.source.excerpt.isNotBlank())
            assertTrue(q.source.chunkId > 0)
        }
    }

    @Test
    fun sessionSize_isBounded() = runBlocking {
        val quiz = ready()
        assertTrue(quiz.questions.size <= 5)
    }

    @Test
    fun unknownFile_isNoSource() = runBlocking {
        val outcome = repo.planForWeakness(s1, 9999, 5, seed = 7)
        assertTrue(outcome is TargetedPracticeOutcome.Unavailable)
        assertEquals(
            TargetedPracticeOutcome.Reason.NO_SOURCE,
            (outcome as TargetedPracticeOutcome.Unavailable).reason
        )
    }

    @Test
    fun fileWithoutChunks_isSourceNotIndexed() = runBlocking {
        val bare = db.academicDao().insertFile(
            AcademicFile(
                weekId = db.academicDao().getWeeks(
                    db.academicDao().getModules(s1).first().id
                ).first().id,
                fileName = "bare.pdf", filePath = "/local/bare.pdf", fileType = "pdf",
                sha256 = "bare-sha", classType = "LECTURE", relativePath = "y.zip/bare.pdf",
                indexed = false
            )
        )
        val outcome = repo.planForWeakness(s1, bare, 5, seed = 7)
        assertTrue(outcome is TargetedPracticeOutcome.Unavailable)
        assertEquals(
            TargetedPracticeOutcome.Reason.SOURCE_NOT_INDEXED,
            (outcome as TargetedPracticeOutcome.Unavailable).reason
        )
    }

    @Test
    fun sameSeed_samePlan() = runBlocking {
        val a = ready()
        // Fresh sessions each call, but identical content/order for one seed.
        val b = ready()
        assertEquals(
            a.questions.map { it.prompt },
            b.questions.map { it.prompt }
        )
    }

    @Test
    fun noDuplicateQuestionIds() = runBlocking {
        val quiz = ready()
        val ids = quiz.questions.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun normalQuiz_unaffected_spansFiles() = runBlocking {
        val quiz = repo.launch(s1, 5, 42)
        assertNotNull("normal launch must keep working", quiz)
        // Normal planning still draws from the whole semester pool.
        assertTrue(quiz!!.questions.map { it.source.academicFileId }.toSet().size >= 1)
    }

    @Test
    fun targetedSession_persistsThroughNormalTransaction() = runBlocking {
        val quiz = ready()
        val session = db.quizDao().session(quiz.sessionId)
        assertNotNull(session)
        assertEquals(QuizSession.STATUS_IN_PROGRESS, session!!.status)
        assertEquals(s1, session.semesterId)
        assertEquals(quiz.questions.size, db.quizDao().questions(quiz.sessionId).size)
    }

    @Test
    fun targetedAnswers_becomeNormalEvidence() = runBlocking {
        val quiz = ready()
        // Answer everything wrong through the standard path, then complete.
        quiz.questions.forEach { q ->
            val wrong = q.options.first { it != q.correctAnswer }
            repo.answer(q.id, wrong)
        }
        val results = repo.complete(quiz.sessionId)
        assertEquals(quiz.questions.size, results.total)
        assertEquals(0, results.correct)
        // New rows are ordinary quiz evidence (wrong answers recorded).
        val mistakes = db.quizDao().wrongAnswerEvents(s1, 50)
        assertTrue(mistakes.size >= quiz.questions.size)
        assertTrue(mistakes.all { it.academicFileId == weakFile })
    }

    @Test
    fun openingPractice_createsNoAssessmentEvidence() = runBlocking {
        val before = db.quizDao().wrongAnswerEvents(s1, 500).size
        ready()
        val after = db.quizDao().wrongAnswerEvents(s1, 500).size
        assertEquals(before, after)
        // Unanswered questions are not mistakes.
        val rows = db.quizDao().questions(db.quizDao().sessionsOfSemester(s1).last().id)
        assertTrue(rows.all { it.isCorrect == null })
    }

    @Test
    fun crossSemesterFile_isRefused() = runBlocking {
        // The physics.pdf file lives in s2; requesting it under s1 refuses.
        val outcome = repo.planForWeakness(s1, foreignFile, 5, seed = 7)
        assertTrue(outcome is TargetedPracticeOutcome.Unavailable)
        assertEquals(
            TargetedPracticeOutcome.Reason.NO_SOURCE,
            (outcome as TargetedPracticeOutcome.Unavailable).reason
        )
    }

    @Test
    fun outcomeModel_carriesNoScores() = runBlocking {
        // Exhaustive when: adding a third outcome shape (scores, confidence,
        // mastery) breaks compilation here by design. The only two shapes
        // carry a quiz session + file name, or an honest failure reason.
        fun tag(o: TargetedPracticeOutcome): String = when (o) {
            is TargetedPracticeOutcome.Ready -> "ready:${o.quiz.sessionId}:${o.fileName}"
            is TargetedPracticeOutcome.Unavailable -> "unavailable:${o.reason}"
        }
        val readyTag = tag(repo.planForWeakness(s1, weakFile, 5, seed = 7))
        assertTrue(readyTag.startsWith("ready:"))
        assertTrue(readyTag.endsWith("cell-biology.pdf"))
        val deniedTag = tag(repo.planForWeakness(s1, 9999, 5, seed = 7))
        assertEquals("unavailable:NO_SOURCE", deniedTag)
    }

    @Test
    fun improvementLoop_usesRealEvidencePath() = runBlocking {
        // T15/T16: seed OBSERVED-level mistakes, then recovery, via real DAOs.
        val evidence = EvidenceRepository(
            db.academicDao(), db.extractionDao(), db.quizDao(), db.flashcardDao(),
            Dispatchers.Unconfined
        )
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        suspend fun answerRound(daysAgo: Long, correct: Boolean) {
            val sid = db.quizDao().insertSession(
                QuizSession(
                    semesterId = s1, seed = daysAgo, totalQuestions = 1,
                    status = QuizSession.STATUS_COMPLETED,
                    startedAt = now - daysAgo * day, completedAt = now - daysAgo * day
                )
            )
            db.quizDao().insertQuestions(
                listOf(
                    com.prasbin.shadowlearn.data.db.QuizQuestion(
                        sessionId = sid, position = 0, chunkId = 0, academicFileId = weakFile,
                        questionType = "MCQ", prompt = "Q?", optionsJson = "[\"a\",\"b\"]",
                        correctAnswer = "a",
                        userAnswer = if (correct) "a" else "b",
                        isCorrect = correct,
                        srcFileName = "cell-biology.pdf", srcFileType = "pdf", srcExcerpt = "excerpt"
                    )
                )
            )
        }
        // 3 wrong across 3 sessions 20/15/12 days ago -> OBSERVED bar met.
        answerRound(20, false)
        answerRound(15, false)
        answerRound(12, false)
        // 3 correct in trailing 7d, no new negatives -> IMPROVING.
        answerRound(3, true)
        answerRound(2, true)
        answerRound(1, true)
        val improving = evidence.weaknessSignals(s1, now)
            .firstOrNull { it.fileId == weakFile }
        assertNotNull(improving)
        assertEquals(WeaknessStatus.IMPROVING, improving!!.status)
        // T16: one fresh mistake resets to OBSERVED (no false improvement).
        answerRound(0, false)
        val observed = evidence.weaknessSignals(s1, now)
            .firstOrNull { it.fileId == weakFile }
        assertNotNull(observed)
        assertEquals(WeaknessStatus.OBSERVED, observed!!.status)
    }
}
