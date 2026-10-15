package com.prasbin.shadowlearn.ui.listener

import com.prasbin.shadowlearn.data.db.ListenerSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * I7 resume-selection unit tests (pure, no Room, no ViewModel).
 * Real-device finding: a completed session with pending transcripts must
 * reopen its review on Listener entry, otherwise TRANSCRIBE is unreachable
 * after tab switches and restarts.
 */
class ListenerResumeTest {

    private fun session(id: Long, status: String) =
        ListenerSession(id = id, semesterId = 1, status = status)

    @Test
    fun picksLatestCompletedWithPending() = runBlocking {
        val sessions = listOf(
            session(4, ListenerSession.STATUS_COMPLETED),
            session(3, ListenerSession.STATUS_COMPLETED)
        )
        val picked = pickResumeSession(sessions) { id -> if (id == 4L) 1 else 0 }
        assertEquals(4L, picked?.id)
    }

    @Test
    fun skipsCompletedWithoutPending() = runBlocking {
        val sessions = listOf(
            session(5, ListenerSession.STATUS_COMPLETED),
            session(4, ListenerSession.STATUS_COMPLETED)
        )
        val picked = pickResumeSession(sessions) { id -> if (id == 4L) 2 else 0 }
        assertEquals(4L, picked?.id)
    }

    @Test
    fun picksInterruptedWithPending() = runBlocking {
        val sessions = listOf(session(2, ListenerSession.STATUS_INTERRUPTED))
        val picked = pickResumeSession(sessions) { 1 }
        assertEquals(2L, picked?.id)
    }

    @Test
    fun skipsOpenSessions() = runBlocking {
        val sessions = listOf(
            session(6, ListenerSession.STATUS_RECORDING),
            session(5, ListenerSession.STATUS_PAUSED)
        )
        assertNull(pickResumeSession(sessions) { 3 })
    }

    @Test
    fun nullWhenNoUnfinishedWork() = runBlocking {
        assertNull(pickResumeSession(emptyList()) { 1 })
        val sessions = listOf(session(4, ListenerSession.STATUS_COMPLETED))
        assertNull(pickResumeSession(sessions) { 0 })
    }

    @Test
    fun missingCountsTreatedAsZero() = runBlocking {
        val sessions = listOf(session(4, ListenerSession.STATUS_COMPLETED))
        val counts = emptyMap<Long, Int>()
        assertNull(pickResumeSession(sessions) { id -> counts[id] ?: 0 })
    }
}
