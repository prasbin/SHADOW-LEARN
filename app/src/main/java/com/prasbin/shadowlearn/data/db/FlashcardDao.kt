package com.prasbin.shadowlearn.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Phase 8 DAO: decks, cards, review sessions + events.
 *
 * The due queue is a single indexed query (`dueAt <= now`, unsuspended,
 * deterministic `dueAt, id` order) — review never loads the whole deck.
 * Card schedule updates and their review events are written atomically via
 * [grade] so history can never disagree with card state.
 */
@Dao
abstract class FlashcardDao {

    // ---- decks/cards ------------------------------------------------------

    @Insert
    abstract suspend fun insertDeck(deck: FlashcardDeck): Long

    @Query("SELECT * FROM flashcard_decks WHERE semesterId = :semesterId ORDER BY id")
    abstract suspend fun decksOfSemester(semesterId: Long): List<FlashcardDeck>

    @Query("SELECT * FROM flashcard_decks WHERE id = :id LIMIT 1")
    abstract suspend fun deck(id: Long): FlashcardDeck?

    /** Rebuilds IGNORE duplicates on (deckId, contentKey); returns row ids. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCards(cards: List<Flashcard>): List<Long>

    @Query("SELECT * FROM flashcards WHERE id = :id LIMIT 1")
    abstract suspend fun card(id: Long): Flashcard?

    @Query("SELECT * FROM flashcards WHERE deckId = :deckId ORDER BY id")
    abstract suspend fun cardsOfDeck(deckId: Long): List<Flashcard>

    /**
     * The due queue: unsuspended cards due at [now], deterministic order.
     * Bounded by [limit] so review never materializes a huge deck.
     */
    @Query(
        "SELECT * FROM flashcards WHERE deckId = :deckId AND suspended = 0 " +
            "AND dueAt <= :now ORDER BY dueAt ASC, id ASC LIMIT :limit"
    )
    abstract suspend fun dueCards(deckId: Long, now: Long, limit: Int): List<Flashcard>

    @Query("SELECT COUNT(*) FROM flashcards WHERE deckId = :deckId")
    abstract suspend fun cardCount(deckId: Long): Int

    @Query("SELECT COUNT(*) FROM flashcards WHERE deckId = :deckId AND suspended = 0 AND dueAt <= :now")
    abstract suspend fun dueCount(deckId: Long, now: Long): Int

    @Query("SELECT COUNT(*) FROM flashcards WHERE deckId = :deckId AND suspended != 0")
    abstract suspend fun suspendedCount(deckId: Long): Int

    @Query("UPDATE flashcards SET suspended = :suspended, updatedAt = :at WHERE id = :id")
    abstract suspend fun setSuspended(id: Long, suspended: Boolean, at: Long)

    @Query(
        "UPDATE flashcards SET easeFactor = :ease, intervalDays = :interval, " +
            "dueAt = :dueAt, updatedAt = :at WHERE id = :id"
    )
    abstract suspend fun reschedule(id: Long, ease: Double, interval: Int, dueAt: Long, at: Long)

    @Query("DELETE FROM flashcard_decks WHERE id = :id")
    abstract suspend fun deleteDeck(id: Long)

    // ---- generation pools (bounded, semester-scoped joins) ----------------

    /** Chunk pool with module/week citation (bounded for memory safety). */
    @Query(
        "SELECT c.id AS chunkId, c.text AS text, c.pageNumber AS pageNumber, " +
            "f.id AS academicFileId, f.fileName AS fileName, f.fileType AS fileType, " +
            "m.name AS moduleName, w.weekNumber AS weekNumber " +
            "FROM document_chunks c " +
            "JOIN academic_files f ON f.id = c.academicFileId " +
            "JOIN weeks w ON w.id = f.weekId " +
            "JOIN modules m ON m.id = w.moduleId " +
            "WHERE m.semesterId = :semesterId ORDER BY c.id LIMIT :limit"
    )
    abstract suspend fun cardChunksOfSemester(semesterId: Long, limit: Int): List<CardChunkRow>

    /** Wrongly answered questions of completed sessions in the semester. */
    @Query(
        "SELECT q.id AS questionId, q.prompt AS prompt, q.correctAnswer AS correctAnswer, " +
            "q.srcFileName AS srcFileName, q.srcFileType AS srcFileType, q.srcPage AS srcPage " +
            "FROM quiz_questions q JOIN quiz_sessions s ON s.id = q.sessionId " +
            "WHERE s.semesterId = :semesterId AND s.status = 'completed' AND q.isCorrect = 0 " +
            "ORDER BY q.id LIMIT :limit"
    )
    abstract suspend fun mistakeQuestionsOfSemester(semesterId: Long, limit: Int): List<MistakeRow>

    /** READY transcript segments of the semester (PENDING/FAILED excluded). */
    @Query(
        "SELECT g.id AS segmentId, g.transcript AS transcript, " +
            "g.transcriptStatus AS transcriptStatus " +
            "FROM listener_segments g JOIN listener_sessions s ON s.id = g.sessionId " +
            "WHERE s.semesterId = :semesterId AND g.transcriptStatus = 'ready' " +
            "ORDER BY g.id LIMIT :limit"
    )
    abstract suspend fun readySegmentsOfSemester(semesterId: Long, limit: Int): List<ReadySegmentRow>

    // ---- review sessions/events -------------------------------------------

    @Insert
    abstract suspend fun insertReviewSession(session: ReviewSession): Long

    @Query("SELECT * FROM flashcard_review_sessions WHERE id = :id LIMIT 1")
    abstract suspend fun reviewSession(id: Long): ReviewSession?

    @Query(
        "SELECT * FROM flashcard_review_sessions WHERE deckId = :deckId " +
            "AND status = 'IN_PROGRESS' ORDER BY id DESC LIMIT 1"
    )
    abstract suspend fun latestInProgress(deckId: Long): ReviewSession?

    @Query("SELECT * FROM flashcard_review_events WHERE sessionId = :sessionId ORDER BY id")
    abstract suspend fun reviewEvents(sessionId: Long): List<ReviewEvent>

    @Query(
        "UPDATE flashcard_review_sessions SET reviewedCount = :reviewed, " +
            "retainedCount = :retained WHERE id = :id"
    )
    abstract suspend fun updateReviewCounts(id: Long, reviewed: Int, retained: Int)

    @Query(
        "UPDATE flashcard_review_sessions SET status = :status, completedAt = :at, " +
            "reviewedCount = :reviewed, retainedCount = :retained WHERE id = :id"
    )
    abstract suspend fun closeReviewSession(id: Long, status: String, at: Long, reviewed: Int, retained: Int)

    /**
     * Rates one card atomically: schedule update + review event + session
     * counters move together, so history can never disagree with card state.
     */
    @Transaction
    open suspend fun grade(
        cardId: Long,
        ease: Double,
        interval: Int,
        dueAt: Long,
        at: Long,
        event: ReviewEvent,
        sessionId: Long,
        reviewed: Int,
        retained: Int
    ): Long {
        reschedule(cardId, ease, interval, dueAt, at)
        val eventId = insertEvent(event.copy(sessionId = sessionId))
        updateReviewCounts(sessionId, reviewed, retained)
        return eventId
    }

    @Insert
    abstract suspend fun insertEvent(event: ReviewEvent): Long
}

/** Chunk pool row for card generation (see [FlashcardDao.cardChunksOfSemester]). */
data class CardChunkRow(
    val chunkId: Long,
    val text: String,
    val pageNumber: Long?,
    val academicFileId: Long,
    val fileName: String,
    val fileType: String,
    val moduleName: String,
    val weekNumber: Int?
)

/** Wrong-answer row for mistake cards (see [FlashcardDao.mistakeQuestionsOfSemester]). */
data class MistakeRow(
    val questionId: Long,
    val prompt: String,
    val correctAnswer: String,
    val srcFileName: String,
    val srcFileType: String,
    val srcPage: Long?
)

/** READY transcript row for lecture cards (see [FlashcardDao.readySegmentsOfSemester]). */
data class ReadySegmentRow(
    val segmentId: Long,
    val transcript: String,
    /** Echoed so generation can refuse non-ready rows even if a caller errs. */
    val transcriptStatus: String
)
