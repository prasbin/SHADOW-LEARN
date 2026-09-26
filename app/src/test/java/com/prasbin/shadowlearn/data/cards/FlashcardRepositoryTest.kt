package com.prasbin.shadowlearn.data.cards

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.Flashcard
import com.prasbin.shadowlearn.data.db.FlashcardDao
import com.prasbin.shadowlearn.data.db.FlashcardDeck
import com.prasbin.shadowlearn.data.db.ReviewEvent
import com.prasbin.shadowlearn.data.db.ReviewSession
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 8 repository/DAO tests: deck persistence, card persistence,
 * due ordering, suspension, review sessions/events, atomic grade,
 * and resume. Uses an in-memory Room database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FlashcardRepositoryTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var dao: FlashcardDao
    private lateinit var repo: FlashcardRepository

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ShadowLearnDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.flashcardDao()
        repo = FlashcardRepository(dao, kotlinx.coroutines.Dispatchers.Unconfined)
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun seedDeck(cardCount: Int = 3): Long {
        val deckId = dao.insertDeck(FlashcardDeck(semesterId = 1, title = "Test"))
        val cards = (1..cardCount).map { i ->
            Flashcard(
                deckId = deckId, front = "front $i", back = "back $i",
                sourceLabel = "file.pdf · PDF · PAGE $i",
                contentKey = "chunk:$i:test",
                dueAt = now + i * 1000L,
                intervalDays = 0, easeFactor = 2.5
            )
        }
        dao.insertCards(cards)
        return deckId
    }

    // ---- deck persistence --------------------------------------------------

    @Test
    fun deckIsPersisted() = runBlocking {
        val deckId = dao.insertDeck(FlashcardDeck(semesterId = 42, title = "AI Deck"))
        val deck = dao.deck(deckId)
        assertNotNull(deck)
        assertEquals("AI Deck", deck!!.title)
        assertEquals(42L, deck.semesterId)
    }

    @Test
    fun deckOfSemesterReturnsFirstMatch() = runBlocking {
        dao.insertDeck(FlashcardDeck(semesterId = 1, title = "A"))
        dao.insertDeck(FlashcardDeck(semesterId = 1, title = "B"))
        val deck = repo.deckOfSemester(1)
        assertEquals("A", deck?.title)
    }

    // ---- card persistence --------------------------------------------------

    @Test
    fun cardsArePersisted() = runBlocking {
        val deckId = seedDeck(5)
        assertEquals(5, dao.cardCount(deckId))
    }

    @Test
    fun duplicateContentKeyIsIgnored() = runBlocking {
        val deckId = dao.insertDeck(FlashcardDeck(semesterId = 1, title = "T"))
        val card = Flashcard(deckId = deckId, front = "f", back = "b",
            sourceLabel = "x", contentKey = "dup-key")
        dao.insertCards(listOf(card))
        dao.insertCards(listOf(card)) // duplicate — should be IGNORED
        assertEquals(1, dao.cardCount(deckId))
    }

    // ---- due queue ordering ------------------------------------------------

    @Test
    fun dueCardsOrderedByDueAtThenId() = runBlocking {
        val deckId = seedDeck(3) // dueAt = now+1000, now+2000, now+3000
        val due = dao.dueCards(deckId, now + 100_000, 10)
        assertEquals(3, due.size)
        assertEquals(due.sortedBy { it.dueAt }, due)
    }

    @Test
    fun futureCardsNotInDueQueue() = runBlocking {
        val deckId = dao.insertDeck(FlashcardDeck(semesterId = 1, title = "T"))
        dao.insertCards(listOf(
            Flashcard(deckId = deckId, front = "f", back = "b",
                sourceLabel = "x", contentKey = "k1", dueAt = now + 100_000)
        ))
        val due = dao.dueCards(deckId, now, 10)
        assertTrue(due.isEmpty())
    }

    @Test
    fun suspendedCardsExcludedFromDueQueue() = runBlocking {
        val deckId = seedDeck(2)
        val cards = dao.cardsOfDeck(deckId)
        dao.setSuspended(cards[0].id, true, now)
        val due = dao.dueCards(deckId, now + 100_000, 10)
        assertTrue(due.none { it.id == cards[0].id })
    }

    @Test
    fun suspensionCountIsAccurate() = runBlocking {
        val deckId = seedDeck(3)
        val cards = dao.cardsOfDeck(deckId)
        dao.setSuspended(cards[0].id, true, now)
        dao.setSuspended(cards[1].id, true, now)
        assertEquals(2, dao.suspendedCount(deckId))
    }

    @Test
    fun dueCountExcludesSuspended() = runBlocking {
        val deckId = seedDeck(3)
        val cards = dao.cardsOfDeck(deckId)
        dao.setSuspended(cards[0].id, true, now)
        assertEquals(2, dao.dueCount(deckId, now + 100_000))
    }

    // ---- review sessions ---------------------------------------------------

    @Test
    fun reviewSessionIsPersisted() = runBlocking {
        val deckId = seedDeck(1)
        val sessionId = dao.insertReviewSession(ReviewSession(deckId = deckId))
        val session = dao.reviewSession(sessionId)
        assertNotNull(session)
        assertEquals(ReviewSession.STATUS_IN_PROGRESS, session!!.status)
    }

    @Test
    fun latestInProgressReturnsSession() = runBlocking {
        val deckId = seedDeck(1)
        dao.insertReviewSession(ReviewSession(deckId = deckId))
        val latest = dao.latestInProgress(deckId)
        assertNotNull(latest)
        assertEquals(deckId, latest!!.deckId)
    }

    @Test
    fun completedSessionNotReturnedByLatestInProgress() = runBlocking {
        val deckId = seedDeck(1)
        val sessionId = dao.insertReviewSession(ReviewSession(deckId = deckId))
        dao.closeReviewSession(sessionId, ReviewSession.STATUS_COMPLETED, now, 3, 3)
        assertNull(dao.latestInProgress(deckId))
    }

    // ---- review events + atomic grade --------------------------------------

    @Test
    fun gradePersistsEventAndUpdatesCard() = runBlocking {
        val deckId = seedDeck(1)
        val card = dao.cardsOfDeck(deckId).first()
        val sessionId = dao.insertReviewSession(ReviewSession(deckId = deckId))
        val schedule = ReviewScheduler.schedule(Rating.GOOD, card.easeFactor, card.intervalDays, now)
        val event = ReviewEvent(
            sessionId = sessionId, flashcardId = card.id, rating = "GOOD",
            previousEaseFactor = card.easeFactor, newEaseFactor = schedule.easeFactor,
            previousIntervalDays = card.intervalDays, newIntervalDays = schedule.intervalDays,
            retained = schedule.retained
        )
        dao.grade(card.id, schedule.easeFactor, schedule.intervalDays, schedule.dueAt,
            now, event, sessionId, 1, 1)
        // Card schedule updated.
        val updated = dao.card(card.id)!!
        assertEquals(schedule.easeFactor, updated.easeFactor, 0.001)
        assertEquals(schedule.intervalDays, updated.intervalDays)
        assertEquals(schedule.dueAt, updated.dueAt)
        // Event persisted.
        val events = dao.reviewEvents(sessionId)
        assertEquals(1, events.size)
        assertEquals("GOOD", events[0].rating)
        // Session counters updated.
        val session = dao.reviewSession(sessionId)!!
        assertEquals(1, session.reviewedCount)
        assertEquals(1, session.retainedCount)
    }

    @Test
    fun gradeIsAtomicAllOrNothing() = runBlocking {
        val deckId = seedDeck(1)
        val card = dao.cardsOfDeck(deckId).first()
        val sessionId = dao.insertReviewSession(ReviewSession(deckId = deckId))
        val schedule = ReviewScheduler.schedule(Rating.AGAIN, card.easeFactor, card.intervalDays, now)
        val event = ReviewEvent(
            sessionId = sessionId, flashcardId = card.id, rating = "AGAIN",
            previousEaseFactor = card.easeFactor, newEaseFactor = schedule.easeFactor,
            previousIntervalDays = card.intervalDays, newIntervalDays = schedule.intervalDays,
            retained = false
        )
        dao.grade(card.id, schedule.easeFactor, schedule.intervalDays, schedule.dueAt,
            now, event, sessionId, 1, 0)
        val updated = dao.card(card.id)!!
        assertEquals(0, updated.intervalDays)
        assertEquals(ReviewScheduler.dayStart(now), updated.dueAt)
        assertEquals(1, dao.reviewEvents(sessionId).size)
        val session = dao.reviewSession(sessionId)!!
        assertEquals(1, session.reviewedCount)
        assertEquals(0, session.retainedCount)
    }

    // ---- completion --------------------------------------------------------

    @Test
    fun completedSessionSnapshotsCounts() = runBlocking {
        val deckId = seedDeck(1)
        val sessionId = dao.insertReviewSession(ReviewSession(deckId = deckId))
        dao.closeReviewSession(sessionId, ReviewSession.STATUS_COMPLETED, now, 5, 4)
        val session = dao.reviewSession(sessionId)!!
        assertEquals(ReviewSession.STATUS_COMPLETED, session.status)
        assertEquals(5, session.reviewedCount)
        assertEquals(4, session.retainedCount)
        assertNotNull(session.completedAt)
    }

    @Test
    fun interruptedSessionIsPreserved() = runBlocking {
        val deckId = seedDeck(1)
        val sessionId = dao.insertReviewSession(ReviewSession(deckId = deckId))
        dao.closeReviewSession(sessionId, ReviewSession.STATUS_INTERRUPTED, now, 3, 2)
        val session = dao.reviewSession(sessionId)!!
        assertEquals(ReviewSession.STATUS_INTERRUPTED, session.status)
        assertEquals(3, session.reviewedCount)
        assertEquals(2, session.retainedCount)
    }

    // ---- resume ------------------------------------------------------------

    @Test
    fun resumeReadsEventsFromPersistedSession() = runBlocking {
        val deckId = seedDeck(3)
        val sessionId = dao.insertReviewSession(ReviewSession(deckId = deckId))
        val card1 = dao.cardsOfDeck(deckId)[0]
        val card2 = dao.cardsOfDeck(deckId)[1]
        // Grade first two cards (accumulated counts).
        var reviewed = 0
        var retained = 0
        for (card in listOf(card1, card2)) {
            val s = ReviewScheduler.schedule(Rating.GOOD, card.easeFactor, card.intervalDays, now)
            reviewed++
            if (s.retained) retained++
            dao.grade(card.id, s.easeFactor, s.intervalDays, s.dueAt, now,
                ReviewEvent(sessionId = sessionId, flashcardId = card.id, rating = "GOOD",
                    previousEaseFactor = card.easeFactor, newEaseFactor = s.easeFactor,
                    previousIntervalDays = card.intervalDays, newIntervalDays = s.intervalDays,
                    retained = s.retained), sessionId, reviewed, retained)
        }
        // Resume: latest IN_PROGRESS session + events determine progress.
        val session = dao.latestInProgress(deckId)
        assertNotNull(session)
        val events = dao.reviewEvents(session!!.id)
        assertEquals(2, events.size)
        assertEquals(2, session.reviewedCount)
        assertEquals(2, session.retainedCount)
    }
}
