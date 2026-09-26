package com.prasbin.shadowlearn.data.quiz

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.DocumentChunk
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.db.Week
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end quiz flow over Room: session persistence, per-semester scope,
 * honest scoring/XP, streak calculation from real completion history and
 * resume of the latest in-progress session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuizRepositoryTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var repo: QuizRepository
    private var s1: Long = 0
    private var s2: Long = 0
    private var s3: Long = 0

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
        val y = dao.insertYear(AcademicYear(name = "Year 2", sortOrder = 0))
        s1 = dao.insertSemester(Semester(yearId = y, name = "Semester 1", sortOrder = 0))
        s2 = dao.insertSemester(Semester(yearId = y, name = "Semester 2", sortOrder = 1))
        s3 = dao.insertSemester(Semester(yearId = y, name = "Semester 3", sortOrder = 2))

        suspend fun file(semester: Long, name: String, type: String): Long {
            val m = dao.insertModule(Module(semesterId = semester, name = name))
            val w = dao.insertWeek(Week(moduleId = m, weekNumber = 1))
            return dao.insertFile(
                AcademicFile(
                    weekId = w, fileName = name, filePath = "/local/$name", fileType = type,
                    sha256 = name + "-sha", classType = "LECTURE", relativePath = "y.zip/$name", indexed = true
                )
            )
        }

        val neural = file(s1, "neural.pdf", "pdf")
        val notes = file(s1, "notes.docx", "docx")
        val slides = file(s1, "slides.pptx", "pptx")
        val protocols = file(s1, "protocols.pdf", "pdf")
        val chemistry = file(s1, "chemistry.pdf", "pdf")
        val other = file(s2, "quantum.pdf", "pdf")

        db.extractionDao().insertChunks(
            listOf(
                DocumentChunk(academicFileId = neural, chunkIndex = 0, pageNumber = 1,
                    text = "neural networks learn by gradient descent and backpropagation. The optimizer adjusts weights to reduce the loss each epoch.", charCount = 100),
                DocumentChunk(academicFileId = neural, chunkIndex = 1, pageNumber = 2,
                    text = "deep learning relies on large datasets for training. regularization helps prevent overfitting on small samples.", charCount = 96),
                DocumentChunk(academicFileId = notes, chunkIndex = 0, pageNumber = 2,
                    text = "adversarial examples can fool a trained model. data augmentation expands the training set with transformations.", charCount = 105),
                DocumentChunk(academicFileId = slides, chunkIndex = 0, pageNumber = 1,
                    text = "transformers use self attention to process sequences. attention heads capture relationships between words.", charCount = 99),
                DocumentChunk(academicFileId = protocols, chunkIndex = 0, pageNumber = 4,
                    text = "internet protocols route packets across the network. routers inspect packet headers to forward traffic correctly.", charCount = 101),
                DocumentChunk(academicFileId = chemistry, chunkIndex = 0, pageNumber = 2,
                    text = "catalysts lower activation energy in chemical reactions. enzymes accelerate reactions without being consumed.", charCount = 98),
                DocumentChunk(academicFileId = other, chunkIndex = 0, pageNumber = 3,
                    text = "quantum mechanics uses wave functions. measurements collapse the wave function into eigenstates.", charCount = 83)
            )
        )
    }

    private suspend fun startQuiz(length: Int = 5, seed: Long = 42): ActiveQuiz {
        val quiz = repo.launch(s1, length, seed)
        assertNotNull("expected a usable quiz", quiz)
        return quiz!!
    }

    @Test
    fun launchPersistsSessionScopedToSemester() = runBlocking {
        val quiz = startQuiz()
        assertTrue(quiz.total in 1..5)
        val stored = db.quizDao().session(quiz.sessionId)!!
        assertEquals(quiz.questions.size, stored.totalQuestions)
        assertEquals("in_progress", stored.status)
        val chunkIds = quiz.questions.map { it.source.chunkId }
        val s1Chunks = db.quizDao().chunksOfSemester(s1).associateBy { it.chunkId }
        chunkIds.groupingBy { it }.eachCount().forEach { (id, count) ->
            val row = s1Chunks[id]
            assertNotNull("chunk $id must be an s1 chunk", row)
            val cap = QuestionGenerator.capacity(row!!)
            assertTrue("chunk $id used $count but capacity is $cap", count <= cap)
            assertTrue(count >= 1)
        }
        quiz.questions.forEachIndexed { i, q ->
            assertEquals(i, db.quizDao().question(q.id)!!.position)
        }
        // s2 chunk must never leak into the quiz
        val s2FileId = db.extractionDao().filesOfSemester(s2).first().id
        val s2ChunkId = db.extractionDao().chunksForFile(s2FileId).first().id
        assertEquals("s2 chunk must not appear", 0, chunkIds.count { it == s2ChunkId })
    }

    @Test
    fun emptyOrIneligibleSemestersReturnNull() = runBlocking {
        assertNull(repo.launch(s3, 5, 1))
        // build an s3 with a single tiny chunk (too short to extract segments)
        val m = db.academicDao().insertModule(Module(semesterId = s3, name = "tiny"))
        val w = db.academicDao().insertWeek(Week(moduleId = m, weekNumber = 1))
        val f = db.academicDao().insertFile(
            AcademicFile(weekId = w, fileName = "tiny.txt", filePath = "/local/tiny.txt", fileType = "txt",
                sha256 = "tiny-sha", classType = "LECTURE", relativePath = "y.zip/tiny.txt", indexed = true)
        )
        db.extractionDao().insertChunks(
            listOf(DocumentChunk(academicFileId = f, chunkIndex = 0, pageNumber = null, text = "hi there", charCount = 8))
        )
        assertNull(repo.launch(s3, 5, 1))
    }

    @Test
    fun activeQuizResumesLatestInProgressAcrossInstances() = runBlocking {
        val quiz = startQuiz(5, 7)
        val q0 = quiz.questions[0]
        assertTrue(repo.answer(q0.id, q0.correctAnswer))
        val second = QuizRepository(db.quizDao(), Dispatchers.Unconfined)
        val resumed = second.activeQuiz()
        assertNotNull(resumed)
        assertEquals(quiz.sessionId, resumed!!.sessionId)
        assertEquals(1, resumed.answered)
        assertEquals(quiz.questions.size, resumed.questions.size)
        assertEquals(quiz.semesterId, resumed.semesterId)
        resumed.questions.forEachIndexed { i, rq ->
            val oq = quiz.questions[i]
            assertEquals(oq.id, rq.id)
            assertEquals(oq.position, rq.position)
            assertEquals(oq.type, rq.type)
            assertEquals(oq.prompt, rq.prompt)
            assertEquals(oq.options, rq.options)
            assertEquals(oq.correctAnswer, rq.correctAnswer)
            assertEquals(oq.source, rq.source)
        }
        assertEquals(q0.correctAnswer, resumed.questions[0].userAnswer) // answer persisted
    }

    @Test
    fun answerMarksCorrectnessAndPersistsChoice() = runBlocking {
        val quiz = startQuiz(5, 3)
        val q = quiz.questions[0]
        val correct = q.correctAnswer
        val wrong = q.options.first { it != correct }
        assertTrue(repo.answer(q.id, correct))
        assertFalse(repo.answer(q.id, wrong)) // answer() reports correctness
        assertTrue(repo.answer(q.id, correct)) // re-answer updates the choice
        val row = db.quizDao().question(q.id)!!
        assertEquals(correct, row.userAnswer)
        assertEquals(correct, row.correctAnswer)
        assertEquals(true, row.isCorrect)
        val q1 = quiz.questions[1]
        assertTrue(repo.answer(q1.id, q1.correctAnswer))
        assertEquals(true, db.quizDao().question(q1.id)!!.isCorrect)
    }

    @Test
    fun completeComputesHonestScoreXpAndStreak() = runBlocking {
        val quiz = startQuiz(3, 5)
        val answers = quiz.questions.mapIndexed { i, q ->
            if (i % 2 == 0) q.correctAnswer else q.options.first { it != q.correctAnswer }
        }
        assertTrue(repo.answer(quiz.questions[0].id, answers[0]))  // correct
        assertFalse(repo.answer(quiz.questions[1].id, answers[1])) // wrong
        assertTrue(repo.answer(quiz.questions[2].id, answers[2]))  // correct
        val results = repo.complete(quiz.sessionId)
        assertEquals(3, results.total)
        assertEquals(2, results.correct)     // first is wrong, last two correct
        assertEquals(20, results.xp)
        assertEquals(1, results.streak)      // first ever completion
        val stored = db.quizDao().session(quiz.sessionId)!!
        assertEquals("completed", stored.status)
        assertEquals(2, stored.correctCount)
        assertEquals(20, stored.xpEarned)
        assertEquals(1, stored.streak)
        assertNull(repo.activeQuiz())
        val summary = repo.summary()
        assertEquals(1, summary.sessions)
        assertEquals(20, summary.xp)
        assertEquals(2, summary.lastCorrect)
        assertEquals(3, summary.lastTotal)
    }

    @Test
    fun recentCompletedChunksAreRecorded() = runBlocking {
        val quiz = startQuiz(3, 9)
        repeat(quiz.total) { i ->
            val q = quiz.questions[i]
            repo.answer(q.id, q.correctAnswer)
        }
        repo.complete(quiz.sessionId)
        val recent = db.quizDao().recentCompletedChunkIds()
        val used = quiz.questions.map { it.source.chunkId }.toSet()
        assertTrue("recent must contain the used chunks", recent.toSet().containsAll(used))
        assertEquals(recent.distinct().size, recent.size)
    }

    @Test
    fun newSessionAfterCompletionBuildsANewQuiz() = runBlocking {
        val a = startQuiz(3, 11)
        repeat(a.total) { i -> repo.answer(a.questions[i].id, a.questions[i].correctAnswer) }
        repo.complete(a.sessionId)
        val b = startQuiz(3, 12)
        assertNotEquals(a.sessionId, b.sessionId)
        // b is now the active in-progress session (a is closed)
        assertEquals(b.sessionId, repo.activeQuiz()!!.sessionId)
    }

    @Test
    fun indexedChunkCountReportsSemesterScope() = runBlocking {
        assertEquals(6, repo.indexedChunkCount(s1))
        assertEquals(1, repo.indexedChunkCount(s2))
        assertEquals(0, repo.indexedChunkCount(s3))
    }

    @Test
    fun completedQuizRemainsReviewableAfterCorpusDeletion() = runBlocking {
        val quiz = startQuiz(3, 21)
        repeat(quiz.total) { i ->
            val q = quiz.questions[i]
            repo.answer(q.id, q.correctAnswer)
        }
        val results = repo.complete(quiz.sessionId)
        val snapshot = results.questions.map {
            Triple(it.prompt, it.correctAnswer, it.source)
        }
        // Simulate a destructive re-import: wipe every academic row + chunk.
        val dao = db.academicDao()
        val extraction = db.extractionDao()
        db.quizDao().chunksOfSemester(s1).map { it.academicFileId }.distinct()
            .forEach { fileId ->
                extraction.deleteChunksForFile(fileId)
                extraction.deleteMeta(fileId)
                dao.deleteFile(fileId)
            }
        assertEquals(0, repo.indexedChunkCount(s1))
        // The completed session must stay fully reviewable from snapshots.
        val stored = db.quizDao().session(quiz.sessionId)!!
        assertEquals("completed", stored.status)
        val rows = db.quizDao().questions(quiz.sessionId)
        assertEquals(snapshot.size, rows.size)
        rows.forEachIndexed { i, row ->
            assertEquals(snapshot[i].first, row.prompt)
            assertEquals(snapshot[i].second, row.correctAnswer)
            assertEquals(snapshot[i].third.fileName, row.srcFileName)
            assertEquals(snapshot[i].third.excerpt, row.srcExcerpt)
            assertEquals(results.questions[i].userAnswer, row.userAnswer)
        }
    }

    // --- streak math (pure) ---

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_704_000_000_000L // some Wednesday noon

    private fun atOffset(daysBefore: Long, hour: Int = 12) = now - daysBefore * day + hour * 3_600_000L

    @Test
    fun streakFromEmptyHistoryIsZero() {
        assertEquals(0, repo.streakFrom(emptyList(), now))
        assertEquals(0, repo.streakFrom(listOf(now - 10 * day), now))
    }

    @Test
    fun streakFromMultipleSameDaySessionsCountsOneDay() {
        assertEquals(1, repo.streakFrom(listOf(atOffset(0, 9), atOffset(0, 14)), now))
    }

    @Test
    fun streakFromConsecutiveDaysAccumulates() {
        val times = listOf(atOffset(2), atOffset(1), atOffset(0))
        assertEquals(3, repo.streakFrom(times, now))
    }

    @Test
    fun streakFromGapResets() {
        val times = listOf(atOffset(3), atOffset(2), atOffset(0))
        assertEquals(1, repo.streakFrom(times, now)) // gap on day 1 breaks at 3
    }

    @Test
    fun streakFromYesterdayAloneKeepsOneDay() {
        assertEquals(1, repo.streakFrom(listOf(atOffset(1)), now))
    }

    @Test
    fun streakFromTodayWithoutYesterdayStartsFresh() {
        assertEquals(1, repo.streakFrom(listOf(atOffset(0)), now))
    }

    @Test
    fun launchUsesDeterministicSeedForRepeatableContent() = runBlocking {
        val a = startQuiz(4, 123)
        val b = startQuiz(4, 123)
        assertEquals(a.questions.size, b.questions.size)
        a.questions.zip(b.questions).forEach { (x, y) ->
            assertEquals(x.prompt, y.prompt)
            assertEquals(x.correctAnswer, y.correctAnswer)
            assertEquals(x.options, y.options)
        }
        val c = startQuiz(4, 124)
        // different seed may still produce overlapping content, but the sets must
        // not be forced equal; both quizzes remain valid & persisted.
        assertTrue(c.questions.size in 1..4)
    }

    @Test
    fun eligibleChunksAcrossPoolAllTypesCanAppear() = runBlocking {
        val seen = mutableSetOf<QuestionType>()
        (1L..8L).forEach { seed ->
            val quiz = startQuiz(4, seed)
            repeat(quiz.total) { i ->
                seen += quiz.questions[i].type
            }
        }
        assertTrue("expected multiple question types, saw $seen", seen.size >= 2)
    }
}