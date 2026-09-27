package com.prasbin.shadowlearn.data.quiz

import com.prasbin.shadowlearn.data.db.QuizDao
import com.prasbin.shadowlearn.data.db.QuizQuestion
import com.prasbin.shadowlearn.data.db.QuizSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random
import org.json.JSONArray

/**
 * Phase 6 quiz orchestrator — the single entry point the Quiz screen talks
 * to. Pipeline:
 *
 * ```
 * (semesterId, length, seed)
 *   → QuizDao.chunksOfSemester         — one join, semester-scoped pool
 *   → QuestionGenerator (capacity)     — eligible chunks only
 *   → QuizPlanner.plan                 — complexity-aware, recency ring,
 *                                        no chunk repeated per session
 *   → QuestionGenerator.build          — seeded, verbatim-options questions
 *   → QuizDao.insertRun                — atomic persist (session + questions)
 *   → ActiveQuiz
 * ```
 *
 * Answers and completions are written to SQLite so a session survives
 * process death and resumes via [activeQuiz]; scores/XP/streak are computed
 * ONLY from real rows (no fabrication — rich analytics land in Phase 9).
 */
class QuizRepository(
    private val quizDao: QuizDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Plans + persists a new quiz for [semesterId] with up to [length]
     * questions. Returns null when the semester has no question-ready
     * chunks. The whole run is inserted atomically.
     */
    suspend fun launch(semesterId: Long, length: Int, seed: Long = Random.nextLong()): ActiveQuiz? =
        withContext(dispatcher) {
            val pool = quizDao.chunksOfSemester(semesterId)
            if (pool.isEmpty()) return@withContext null
            val eligible = pool.mapNotNull { chunk ->
                val cap = QuestionGenerator.capacity(chunk)
                if (cap > 0) chunk.chunkId to cap else null
            }
            if (eligible.isEmpty()) return@withContext null

            val ctx = QuestionGenerator.context(pool)
            val recent = quizDao.recentCompletedChunkIds().take(RECENT_WINDOW_CHUNKS).toSet()
            val assignments = QuizPlanner.plan(eligible, length, recent, Random(seed))
            if (assignments.isEmpty()) return@withContext null

            val byId = pool.associateBy { it.chunkId }
            val rng = Random(seed)
            val generated = mutableListOf<GeneratedQuestion>()
            for (a in assignments) {
                val chunk = byId[a.chunkId] ?: continue
                generated += QuestionGenerator.build(chunk, ctx, rng).take(a.questionCount)
            }
            if (generated.isEmpty()) return@withContext null

            val session = QuizSession(
                semesterId = semesterId,
                seed = seed,
                totalQuestions = generated.size
            )
            val entities = generated.mapIndexed { i, q -> q.toEntity(sessionId = 0, position = i) }
            val (sessionId, ids) = quizDao.insertRun(session, entities)
            ActiveQuiz(
                sessionId = sessionId,
                semesterId = semesterId,
                seed = seed,
                questions = ids.mapIndexed { i, id -> generated[i].toActive(id, i) }
            )
        }

    /** The most recent in-progress quiz (resume path), or null. */
    suspend fun activeQuiz(): ActiveQuiz? = withContext(dispatcher) {
        val session = quizDao.latestInProgress() ?: return@withContext null
        val rows = quizDao.questions(session.id)
        if (rows.isEmpty()) return@withContext null
        ActiveQuiz(
            sessionId = session.id,
            semesterId = session.semesterId,
            seed = session.seed,
            questions = rows.map { it.toActive(it.id) }
        )
    }

    /**
     * Records an answer; returns whether it was correct. [answer] must be
     * one of the question's options (guaranteed by the choice-based UI).
     */
    suspend fun answer(questionId: Long, answer: String): Boolean = withContext(dispatcher) {
        val question = quizDao.question(questionId) ?: return@withContext false
        val correct = answer == question.correctAnswer
        quizDao.answer(questionId, answer, correct)
        correct
    }

    /**
     * Closes an in-progress session: computes correct/XP/streak from the
     * real answers and snapshots them on the row. Idempotent on already
     * completed rows is NOT offered — the UI only completes once.
     */
    suspend fun complete(sessionId: Long): QuizResults = withContext(dispatcher) {
        val session = quizDao.session(sessionId) ?: error("No quiz session $sessionId")
        val rows = quizDao.questions(sessionId)
        val correct = rows.count { it.isCorrect == true }
        val xp = correct * XP_PER_CORRECT
        val now = System.currentTimeMillis()
        val streak = streakFrom(quizDao.completionTimes().plus(now), now)
        quizDao.completeSession(sessionId, now, correct, xp, streak)
        QuizResults(
            sessionId = sessionId,
            semesterId = session.semesterId,
            total = rows.size,
            correct = correct,
            xp = xp,
            streak = streak,
            questions = rows.map { it.toActive(it.id) }
        )
    }

    /** Idle-screen aggregate from real completed sessions. */
    suspend fun summary(): QuizSummary = withContext(dispatcher) {
        val row = quizDao.summary()
        val last = quizDao.lastCompleted()
        QuizSummary(
            sessions = row.sessions,
            xp = row.xp,
            bestScore = row.best,
            streak = row.streak,
            lastCorrect = last?.correctCount,
            lastTotal = last?.totalQuestions
        )
    }

    suspend fun indexedChunkCount(semesterId: Long): Int = quizDao.indexedChunkCount(semesterId)

    /**
     * Consecutive-day streak ending today (or yesterday when today has no
     * session yet). Delegates to the shared Phase 9 progression helper so
     * there is exactly one streak implementation.
     */
    internal fun streakFrom(completionTimes: List<Long>, now: Long): Int =
        com.prasbin.shadowlearn.data.progression.ProgressionCalculator.streakFrom(completionTimes, now)

    private fun GeneratedQuestion.toEntity(sessionId: Long, position: Int) = QuizQuestion(
        sessionId = sessionId,
        position = position,
        chunkId = source.chunkId,
        academicFileId = source.academicFileId,
        questionType = type.storage,
        prompt = prompt,
        optionsJson = if (type == QuestionType.TRUE_FALSE) null else optionsJson(options),
        correctAnswer = correctAnswer,
        srcFileName = source.fileName,
        srcFileType = source.fileType,
        srcPage = source.pageNumber,
        srcExcerpt = source.excerpt
    )

    private fun GeneratedQuestion.toActive(id: Long, position: Int) = ActiveQuestion(
        id = id,
        position = position,
        type = type,
        prompt = prompt,
        options = if (type == QuestionType.TRUE_FALSE) TRUE_FALSE_OPTIONS else options,
        correctAnswer = correctAnswer,
        source = source,
        userAnswer = null,
        isCorrect = null
    )

    private fun QuizQuestion.toActive(id: Long) = ActiveQuestion(
        id = id,
        position = position,
        type = QuestionType.from(questionType),
        prompt = prompt,
        options = optionsJson?.let { decode(it) } ?: TRUE_FALSE_OPTIONS,
        correctAnswer = correctAnswer,
        source = QuizSource(
            chunkId = chunkId,
            academicFileId = academicFileId,
            fileName = srcFileName,
            fileType = srcFileType,
            pageNumber = srcPage,
            excerpt = srcExcerpt
        ),
        userAnswer = userAnswer,
        isCorrect = isCorrect
    )

    private fun optionsJson(options: List<String>): String? {
        if (options.isEmpty()) return null
        val json = JSONArray()
        options.forEach { json.put(it) }
        return json.toString()
    }

    private fun decode(json: String): List<String> {
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getString(it) }
    }

    companion object {
        /** Single source of truth for quiz XP lives in the progression engine. */
        const val XP_PER_CORRECT = com.prasbin.shadowlearn.data.progression.ProgressionCalculator.QUIZ_XP_PER_CORRECT
        private val TRUE_FALSE_OPTIONS = listOf("true", "false")
        /** Ring capacity for "recent chunk ids" (≈ 3 sessions of 10). */
        private const val RECENT_WINDOW_CHUNKS = 30
    }
}