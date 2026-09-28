package com.prasbin.shadowlearn.data.cards

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.FlashcardDao
import com.prasbin.shadowlearn.data.db.ListenerDao
import com.prasbin.shadowlearn.data.db.ListenerSegment
import com.prasbin.shadowlearn.data.db.ListenerSession
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
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
 * Phase 11 lecture-card loop tests: READY listener transcripts become
 * reviewable `seg:<id>` cards through the UNCHANGED Phase 8 deck build.
 * Uses a real in-memory Room database; no network, no speech engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LectureCardLoopTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var dao: FlashcardDao
    private lateinit var listenerDao: ListenerDao
    private lateinit var repo: FlashcardRepository

    private val semesterId = 7L
    private val otherSemesterId = 8L

    private val lectureText =
        "The professor explained gradient descent in full detail today."

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ShadowLearnDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.flashcardDao()
        listenerDao = db.listenerDao()
        repo = FlashcardRepository(dao, kotlinx.coroutines.Dispatchers.Unconfined)
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun readySession(
        semester: Long = semesterId,
        transcript: String = lectureText,
        status: String = ListenerSegment.STATUS_READY
    ): Long {
        val sessionId = listenerDao.insertSession(ListenerSession(semesterId = semester))
        listenerDao.insertSegment(
            ListenerSegment(
                sessionId = sessionId,
                position = 0,
                startedAtMs = 0,
                transcript = transcript,
                transcriptStatus = status
            )
        )
        return sessionId
    }

    private suspend fun lectureCards(deckId: Long) =
        dao.cardsOfDeck(deckId).filter { it.sourceListenerSegmentId != null }

    // ---- ready → deck ---------------------------------------------------------

    @Test
    fun readySegmentBuildsLectureCard() = runBlocking {
        val segId = listenerDao.segments(readySession()).single().id
        val deck = repo.buildDeck(semesterId)
        val cards = lectureCards(deck.id)
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals("seg:$segId", card.contentKey)
        assertEquals(segId, card.sourceListenerSegmentId)
        assertEquals("Lecture segment #$segId", card.sourceLabel)
        assertTrue(card.back.contains("gradient descent"))
    }

    @Test
    fun lectureCardFrontIsFirstVerbatimSentence() = runBlocking {
        readySession(transcript = "First sentence here. Second sentence follows along.")
        val deck = repo.buildDeck(semesterId)
        val card = lectureCards(deck.id).single()
        assertEquals("First sentence here.", card.front)
        assertTrue(card.back.contains("Second sentence follows along."))
    }

    @Test
    fun multipleSessionsSameSemesterAllFeedDeck() = runBlocking {
        readySession(transcript = "The professor explained gradient descent in full detail today.")
        readySession(transcript = "Today we covered optimization algorithms in great depth overall.")
        val deck = repo.buildDeck(semesterId)
        assertEquals(2, lectureCards(deck.id).size)
    }

    @Test
    fun otherSemesterSegmentsAreExcluded() = runBlocking {
        readySession(semester = otherSemesterId)
        val deck = repo.buildDeck(semesterId)
        assertEquals(0, lectureCards(deck.id).size)
    }

    // ---- exclusion guards ------------------------------------------------------

    @Test
    fun pendingSegmentsExcludedFromDeck() = runBlocking {
        readySession(status = ListenerSegment.STATUS_PENDING)
        val deck = repo.buildDeck(semesterId)
        assertEquals(0, lectureCards(deck.id).size)
    }

    @Test
    fun failedSegmentsExcludedFromDeck() = runBlocking {
        readySession(status = ListenerSegment.STATUS_FAILED)
        val deck = repo.buildDeck(semesterId)
        assertEquals(0, lectureCards(deck.id).size)
    }

    @Test
    fun shortTranscriptExcludedByMinSegmentGuard() = runBlocking {
        readySession(transcript = "Too short.")
        val deck = repo.buildDeck(semesterId)
        assertEquals(0, lectureCards(deck.id).size)
    }

    // ---- refresh / dedupe / snapshot semantics ----------------------------------

    @Test
    fun rebuildIsIdempotent() = runBlocking {
        readySession()
        val first = repo.buildDeck(semesterId)
        val second = repo.buildDeck(semesterId)
        assertEquals(first.id, second.id)
        assertEquals(1, lectureCards(second.id).size)
    }

    @Test
    fun refreshPicksUpNewlyReadyRows() = runBlocking {
        val sessionId = listenerDao.insertSession(ListenerSession(semesterId = semesterId))
        val segId = listenerDao.insertSegment(
            ListenerSegment(sessionId = sessionId, position = 0, startedAtMs = 0)
        )
        val deck = repo.buildDeck(semesterId)
        assertEquals(0, lectureCards(deck.id).size)
        listenerDao.setTranscript(segId, lectureText, ListenerSegment.STATUS_READY)
        val refreshed = repo.buildDeck(semesterId)
        assertEquals(deck.id, refreshed.id)
        assertEquals(1, lectureCards(refreshed.id).size)
    }

    @Test
    fun transcriptEditDoesNotRewriteExistingCard() = runBlocking {
        readySession()
        val deck = repo.buildDeck(semesterId)
        val before = lectureCards(deck.id).single()
        val segId = before.sourceListenerSegmentId!!
        db.openHelper.writableDatabase.execSQL(
            "UPDATE listener_segments SET transcript = 'Edited later text about nothing.' WHERE id = $segId"
        )
        val rebuilt = repo.buildDeck(semesterId)
        val after = lectureCards(rebuilt.id).single()
        assertEquals(before.id, after.id)
        assertEquals(before.back, after.back)
        assertTrue(after.back.contains("gradient descent"))
    }

    @Test
    fun contentKeyIsStableAcrossRebuilds() = runBlocking {
        readySession()
        val first = lectureCards(repo.buildDeck(semesterId).id).single().contentKey
        val second = lectureCards(repo.buildDeck(semesterId).id).single().contentKey
        assertEquals(first, second)
        assertTrue(second.startsWith("seg:"))
    }

    @Test
    fun emptyPoolBuildsEmptyDeck() = runBlocking {
        val deck = repo.buildDeck(semesterId)
        assertEquals(0, dao.cardCount(deck.id))
        assertEquals(0, repo.lectureCardCount(deck.id))
    }

    // ---- lectureCardCount ---------------------------------------------------------

    @Test
    fun lectureCardCountIsZeroWithoutLectureCards() = runBlocking {
        val deck = repo.buildDeck(semesterId)
        assertEquals(0, repo.lectureCardCount(deck.id))
    }

    @Test
    fun lectureCardCountCountsLectureCards() = runBlocking {
        readySession()
        readySession(transcript = "Today we covered optimization algorithms in great depth overall.")
        val deck = repo.buildDeck(semesterId)
        assertEquals(2, repo.lectureCardCount(deck.id))
        assertEquals(2, dao.cardCount(deck.id))
    }

    @Test
    fun lectureCardCountIncludesSuspendedCards() = runBlocking {
        readySession()
        val deck = repo.buildDeck(semesterId)
        val card = lectureCards(deck.id).single()
        repo.setSuspended(card.id, true, System.currentTimeMillis())
        // Suspended lecture cards still exist — the count stays honest.
        assertEquals(1, repo.lectureCardCount(deck.id))
    }

    @Test
    fun lectureCardAppearsInDueQueue() = runBlocking {
        readySession()
        val deck = repo.buildDeck(semesterId)
        val due = repo.dueCards(deck.id, System.currentTimeMillis())
        assertTrue(due.any { it.sourceListenerSegmentId != null })
    }
}
