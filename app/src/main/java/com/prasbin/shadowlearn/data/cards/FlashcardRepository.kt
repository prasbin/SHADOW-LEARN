package com.prasbin.shadowlearn.data.cards

import com.prasbin.shadowlearn.data.db.FlashcardDao
import com.prasbin.shadowlearn.data.db.FlashcardDeck
import com.prasbin.shadowlearn.data.db.Flashcard
import com.prasbin.shadowlearn.data.db.ReviewEvent
import com.prasbin.shadowlearn.data.db.ReviewSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Phase 8 flashcard orchestrator — the single entry point the
 * Cards tab talks to. Pipeline:
 *
 * ```
 * (semesterId)
 *   → FlashcardDao.cardChunksOfSemester   — bounded chunk pool
 *   → FlashcardDao.mistakeQuestionsOfSemester — wrong answers
 *   → FlashcardDao.readySegmentsOfSemester  — READY transcripts
 *   → CardGenerator.fromChunks / fromMistakes / fromSegments
 *   → FlashcardDao.insertDeck + insertCards  — persist (IGNORE dup)
 *   → FlashcardDeck
 *
 * Review:
 *   → FlashcardDao.dueCards              — bounded due queue
 *   → grade() (atomic: reschedule + event + counters)
 *   → ReviewSession + ReviewEvent persisted
 *   → resume via latestInProgress + reviewEvents
 * ```
 *
 * All generation and scheduling are deterministic: identical corpus
 * + seed ⇒ identical cards; identical rating + schedule ⇒ identical
 * next state. Review history is never reconstructed from mutable card
 * state — it is read from persisted events.
 */
class FlashcardRepository(
    private val flashcardDao: FlashcardDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Builds (or refreshes) a flashcard deck for [semesterId].
     * Generates cards from chunks, quiz mistakes, and READY listener
     * segments. Rebuilds are idempotent via the unique (deckId,
     * contentKey) index — duplicates are IGNORED.
     *
     * Returns the created [FlashcardDeck].
     */
    suspend fun buildDeck(semesterId: Long, title: String = "Review Deck"): FlashcardDeck =
        withContext(dispatcher) {
            val chunks = flashcardDao.cardChunksOfSemester(semesterId, LIMIT)
            val mistakes = flashcardDao.mistakeQuestionsOfSemester(semesterId, LIMIT)
            val segments = flashcardDao.readySegmentsOfSemester(semesterId, LIMIT)

            val chunkCards = CardGenerator.fromChunks(chunks)
            val mistakeCards = CardGenerator.fromMistakes(mistakes)
            val segmentCards = CardGenerator.fromSegments(segments)
            val allCards = chunkCards + mistakeCards + segmentCards

            // Reuse the semester's existing deck row so refreshes never
            // duplicate decks; new cards merge into it via contentKey.
            val existing = flashcardDao.decksOfSemester(semesterId).firstOrNull()
            val deck = existing ?: FlashcardDeck(semesterId = semesterId, title = title)
            val deckId = existing?.id ?: flashcardDao.insertDeck(deck)
            if (allCards.isNotEmpty()) {
                val updated = allCards.map { draft ->
                    Flashcard(
                        deckId = deckId,
                        front = draft.front,
                        back = draft.back,
                        sourceChunkId = draft.sourceChunkId,
                        sourceQuestionId = draft.sourceQuestionId,
                        sourceListenerSegmentId = draft.sourceListenerSegmentId,
                        sourceLabel = draft.sourceLabel,
                        contentKey = draft.contentKey
                    )
                }
                flashcardDao.insertCards(updated)
            }
            deck.copy(id = deckId)
        }

    /**
     * Returns the deck for [semesterId] if it exists, or null.
     */
    suspend fun deckOfSemester(semesterId: Long): FlashcardDeck? =
        withContext(dispatcher) { flashcardDao.decksOfSemester(semesterId).firstOrNull() }

    /**
     * Returns the due queue for [deckId] bounded by [limit].
     * Cards are ordered by [dueAt] then [id] for determinism.
     */
    suspend fun dueCards(deckId: Long, now: Long, limit: Int = LIMIT): List<Flashcard> =
        withContext(dispatcher) { flashcardDao.dueCards(deckId, now, limit) }

    /**
     * Returns the total card count for [deckId].
     */
    suspend fun cardCount(deckId: Long): Int =
        withContext(dispatcher) { flashcardDao.cardCount(deckId) }

    /**
     * Returns the number of unsuspended cards due at [now].
     */
    suspend fun dueCount(deckId: Long, now: Long): Int =
        withContext(dispatcher) { flashcardDao.dueCount(deckId, now) }

    /**
     * Returns the number of suspended cards in [deckId].
     */
    suspend fun suspendedCount(deckId: Long): Int =
        withContext(dispatcher) { flashcardDao.suspendedCount(deckId) }

    /**
     * Atomically grades one card: reschedules the card, persists the
     * review event, and updates session counters — all in one Room
     * transaction so history can never disagree with card state.
     *
     * Returns the review event row id.
     */
    suspend fun grade(
        card: Flashcard,
        rating: Rating,
        sessionId: Long,
        now: Long
    ): Long = withContext(dispatcher) {
        val schedule = ReviewScheduler.schedule(rating, card.easeFactor, card.intervalDays, now)
        val event = ReviewEvent(
            sessionId = 0,
            flashcardId = card.id,
            rating = rating.name,
            reviewedAt = now,
            previousEaseFactor = card.easeFactor,
            newEaseFactor = schedule.easeFactor,
            previousIntervalDays = card.intervalDays,
            newIntervalDays = schedule.intervalDays,
            retained = schedule.retained
        )
        // Read current counts so grade() accumulates (updateReviewCounts
        // writes absolute values, not increments).
        val session = flashcardDao.reviewSession(sessionId)
        val prevReviewed = session?.reviewedCount ?: 0
        val prevRetained = session?.retainedCount ?: 0
        flashcardDao.grade(
            cardId = card.id,
            ease = schedule.easeFactor,
            interval = schedule.intervalDays,
            dueAt = schedule.dueAt,
            at = now,
            event = event,
            sessionId = sessionId,
            reviewed = prevReviewed + 1,
            retained = prevRetained + (if (schedule.retained) 1 else 0)
        )
    }

    /**
     * Creates a new IN_PROGRESS review session for [deckId].
     */
    suspend fun startReview(deckId: Long): Long = withContext(dispatcher) {
        val session = ReviewSession(deckId = deckId)
        flashcardDao.insertReviewSession(session)
    }

    /**
     * Returns the review session with [id], or null.
     */
    suspend fun session(id: Long): ReviewSession? =
        withContext(dispatcher) { flashcardDao.reviewSession(id) }

    /**
     * Returns the most recent IN_PROGRESS session for [deckId], or null.
     */
    suspend fun latestInProgress(deckId: Long): ReviewSession? =
        withContext(dispatcher) { flashcardDao.latestInProgress(deckId) }

    /**
     * Returns all review events for [sessionId], ordered by id.
     */
    suspend fun reviewEvents(sessionId: Long): List<ReviewEvent> =
        withContext(dispatcher) { flashcardDao.reviewEvents(sessionId) }

    /**
     * Closes a review session as COMPLETED with the final counts.
     */
    suspend fun completeSession(sessionId: Long, reviewed: Int, retained: Int, now: Long) =
        withContext(dispatcher) {
            flashcardDao.closeReviewSession(sessionId, ReviewSession.STATUS_COMPLETED, now, reviewed, retained)
        }

    /**
     * Closes a review session as INTERRUPTED (process death / resume).
     */
    suspend fun interruptSession(sessionId: Long, reviewed: Int, retained: Int, now: Long) =
        withContext(dispatcher) {
            flashcardDao.closeReviewSession(sessionId, ReviewSession.STATUS_INTERRUPTED, now, reviewed, retained)
        }

    /**
     * Suspends or resumes a card.
     */
    suspend fun setSuspended(cardId: Long, suspended: Boolean, now: Long) =
        withContext(dispatcher) { flashcardDao.setSuspended(cardId, suspended, now) }

    /**
     * Returns the card with [id], or null.
     */
    suspend fun card(id: Long): Flashcard? =
        withContext(dispatcher) { flashcardDao.card(id) }

    companion object {
        /** Bounded generation/review limit for memory safety. */
        const val LIMIT = 200
    }
}
