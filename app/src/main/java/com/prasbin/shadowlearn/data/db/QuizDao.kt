package com.prasbin.shadowlearn.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Phase 6 DAO: quiz sessions + questions + the semester chunk pool the
 * generator reads from.
 *
 * Chunk pool is resolved in ONE join scoped to the active semester (the same
 * shape SearchDao uses) so quiz generation never scans other semesters. Quiz
 * history reads are small and synchronous (the Quiz screen state is a
 * snapshot machine, not a reactive feed) except the idle summary, which is
 * observed via [observeSummary].
 */
@Dao
abstract class QuizDao {

    // ---- writes -----------------------------------------------------------

    @Insert
    abstract suspend fun insertSession(session: QuizSession): Long

    @Insert
    abstract suspend fun insertQuestions(questions: List<QuizQuestion>): List<Long>

    /** Inserts a run (session + its questions) atomically. */
    @Transaction
    open suspend fun insertRun(session: QuizSession, questions: List<QuizQuestion>): Pair<Long, List<Long>> {
        val sessionId = insertSession(session)
        val ids = if (questions.isEmpty()) emptyList() else {
            insertQuestions(questions.map { it.copy(sessionId = sessionId) })
        }
        return sessionId to ids
    }

    @Query("UPDATE quiz_questions SET userAnswer = :answer, isCorrect = :correct WHERE id = :id")
    abstract suspend fun answer(id: Long, answer: String?, correct: Boolean?)

    @Query(
        "UPDATE quiz_sessions SET status = 'completed', completedAt = :at, " +
            "correctCount = :correct, xpEarned = :xp, streak = :streak WHERE id = :id"
    )
    abstract suspend fun completeSession(id: Long, at: Long, correct: Int, xp: Int, streak: Int)

    // ---- sessions/questions reads ----------------------------------------

    @Query("SELECT * FROM quiz_sessions WHERE id = :id LIMIT 1")
    abstract suspend fun session(id: Long): QuizSession?

    @Query("SELECT * FROM quiz_sessions WHERE status = 'in_progress' ORDER BY id DESC LIMIT 1")
    abstract suspend fun latestInProgress(): QuizSession?

    @Query("SELECT * FROM quiz_questions WHERE sessionId = :sessionId ORDER BY position")
    abstract suspend fun questions(sessionId: Long): List<QuizQuestion>

    @Query("SELECT * FROM quiz_questions WHERE id = :id LIMIT 1")
    abstract suspend fun question(id: Long): QuizQuestion?

    @Query("SELECT * FROM quiz_sessions WHERE status = 'completed' ORDER BY id DESC LIMIT 1")
    abstract suspend fun lastCompleted(): QuizSession?

    /** All sessions of one semester (Phase 13 export; history is semester-scoped). */
    @Query("SELECT * FROM quiz_sessions WHERE semesterId = :semesterId ORDER BY id")
    abstract suspend fun sessionsOfSemester(semesterId: Long): List<QuizSession>

    /**
     * Idempotence key for restore: the same exported run must not be
     * inserted twice (seed + start time are stable across installations).
     */
    @Query(
        "SELECT * FROM quiz_sessions WHERE semesterId = :semesterId AND seed = :seed " +
            "AND startedAt = :startedAt LIMIT 1"
    )
    abstract suspend fun findQuizSession(semesterId: Long, seed: Long, startedAt: Long): QuizSession?

    // ---- history / stats (honest, from real completed rows) ---------------

    @Query(
        "SELECT DISTINCT q.chunkId FROM quiz_questions q " +
            "JOIN quiz_sessions s ON s.id = q.sessionId " +
            "WHERE s.status = 'completed' ORDER BY s.id DESC"
    )
    abstract suspend fun recentCompletedChunkIds(): List<Long>

    @Query("SELECT completedAt FROM quiz_sessions WHERE status = 'completed' AND completedAt IS NOT NULL ORDER BY completedAt DESC")
    abstract suspend fun completionTimes(): List<Long>

    /** Reactive completion times for the Phase 9 progression streak. */
    @Query("SELECT completedAt FROM quiz_sessions WHERE status = 'completed' AND completedAt IS NOT NULL ORDER BY completedAt DESC")
    abstract fun observeCompletionTimes(): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM quiz_sessions WHERE status = 'completed'")
    abstract suspend fun completedCount(): Int

    @Query("SELECT IFNULL(SUM(xpEarned), 0) FROM quiz_sessions WHERE status = 'completed'")
    abstract suspend fun totalXp(): Int

    /** Live weekly summary for the Quiz idle state. */
    @Query(
        "SELECT " +
            "COUNT(*) AS sessions, " +
            "IFNULL(SUM(xpEarned), 0) AS xp, " +
            "IFNULL(MAX(correctCount), 0) AS best, " +
            "IFNULL(MAX(streak), 0) AS streak " +
            "FROM quiz_sessions WHERE status = 'completed'"
    )
    abstract fun observeSummary(): Flow<QuizSummaryRow>

    @Query(
        "SELECT " +
            "COUNT(*) AS sessions, " +
            "IFNULL(SUM(xpEarned), 0) AS xp, " +
            "IFNULL(MAX(correctCount), 0) AS best, " +
            "IFNULL(MAX(streak), 0) AS streak " +
            "FROM quiz_sessions WHERE status = 'completed'"
    )
    abstract suspend fun summary(): QuizSummaryRow

    // ---- generation pool --------------------------------------------------

    /** Every chunk in the semester (id + text + citation metadata), one join. */
    @Query(
        "SELECT c.id AS chunkId, c.academicFileId AS academicFileId, c.chunkIndex AS chunkIndex, " +
            "c.pageNumber AS pageNumber, c.text AS text, " +
            "f.fileName AS fileName, f.fileType AS fileType " +
            "FROM document_chunks c " +
            "JOIN academic_files f ON f.id = c.academicFileId " +
            "JOIN weeks w ON w.id = f.weekId " +
            "JOIN modules m ON m.id = w.moduleId " +
            "WHERE m.semesterId = :semesterId ORDER BY c.id"
    )
    abstract suspend fun chunksOfSemester(semesterId: Long): List<QuizChunkRow>

    @Query(
        "SELECT COUNT(*) FROM document_chunks c " +
            "JOIN academic_files f ON f.id = c.academicFileId " +
            "JOIN weeks w ON w.id = f.weekId " +
            "JOIN modules m ON m.id = w.moduleId " +
            "WHERE m.semesterId = :semesterId"
    )
    abstract suspend fun indexedChunkCount(semesterId: Long): Int

    // ---- I5 targeted practice (read-only file-scope checks) ----------------

    /** Whether the academic file row still exists. */
    @Query("SELECT EXISTS(SELECT 1 FROM academic_files WHERE id = :fileId)")
    abstract suspend fun fileExists(fileId: Long): Boolean

    /** Owning semester of a file via hierarchy walk; null when unresolvable. */
    @Query(
        "SELECT m.semesterId FROM academic_files f " +
            "JOIN weeks w ON w.id = f.weekId " +
            "JOIN modules m ON m.id = w.moduleId " +
            "WHERE f.id = :fileId LIMIT 1"
    )
    abstract suspend fun fileSemester(fileId: Long): Long?

    // ---- I1 evidence (read-only, bounded, semester-scoped joins) ----------

    /**
     * Wrong answers of completed sessions with session time. Recency is
     * session-grained: questions carry no per-answer timestamp (contract §5).
     */
    @Query(
        "SELECT q.id AS questionId, q.sessionId AS sessionId, " +
            "q.academicFileId AS academicFileId, q.chunkId AS chunkId, " +
            "q.srcFileName AS srcFileName, " +
            "IFNULL(s.completedAt, s.startedAt) AS observedAt " +
            "FROM quiz_questions q JOIN quiz_sessions s ON s.id = q.sessionId " +
            "WHERE s.semesterId = :semesterId AND s.status = 'completed' " +
            "AND q.isCorrect = 0 AND q.userAnswer IS NOT NULL " +
            "ORDER BY observedAt DESC LIMIT :limit"
    )
    abstract suspend fun wrongAnswerEvents(semesterId: Long, limit: Int): List<AnswerEventRow>

    /** Correct answers of completed sessions (I1 improvement evidence). */
    @Query(
        "SELECT q.id AS questionId, q.sessionId AS sessionId, " +
            "q.academicFileId AS academicFileId, q.chunkId AS chunkId, " +
            "q.srcFileName AS srcFileName, " +
            "IFNULL(s.completedAt, s.startedAt) AS observedAt " +
            "FROM quiz_questions q JOIN quiz_sessions s ON s.id = q.sessionId " +
            "WHERE s.semesterId = :semesterId AND s.status = 'completed' " +
            "AND q.isCorrect = 1 AND q.userAnswer IS NOT NULL " +
            "ORDER BY observedAt DESC LIMIT :limit"
    )
    abstract suspend fun correctAnswerEvents(semesterId: Long, limit: Int): List<AnswerEventRow>
}

/** Aggregated session stats read by the Quiz idle screen (raw, honest). */
data class QuizSummaryRow(
    val sessions: Int,
    val xp: Int,
    val best: Int,
    val streak: Int
)

/** Generation-pool projection for one semester chunk (see [QuizDao.chunksOfSemester]). */
data class QuizChunkRow(
    val chunkId: Long,
    val academicFileId: Long,
    val chunkIndex: Int,
    val pageNumber: Long?,
    val text: String,
    val fileName: String,
    val fileType: String
)

/**
 * One answered-question event for I1 evidence (see
 * [QuizDao.wrongAnswerEvents]/[QuizDao.correctAnswerEvents]). Recency is
 * session-grained: questions carry no per-answer timestamp.
 */
data class AnswerEventRow(
    val questionId: Long,
    val sessionId: Long,
    val academicFileId: Long,
    val chunkId: Long,
    val srcFileName: String,
    val observedAt: Long
)