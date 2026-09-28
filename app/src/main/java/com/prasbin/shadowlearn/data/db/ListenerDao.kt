package com.prasbin.shadowlearn.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Phase 7 DAO: listener sessions + their transcript-ready segments.
 *
 * Session history reads are small and synchronous (the Listener screen is a
 * snapshot machine like Quiz, not a reactive feed). Segment rows are the
 * future transcription units — always ordered by [position].
 */
@Dao
abstract class ListenerDao {

    @Insert
    abstract suspend fun insertSession(session: ListenerSession): Long

    @Insert
    abstract suspend fun insertSegment(segment: ListenerSegment): Long

    @Query("SELECT * FROM listener_sessions WHERE id = :id LIMIT 1")
    abstract suspend fun session(id: Long): ListenerSession?

    /** The latest session that is still open (recording or paused). */
    @Query(
        "SELECT * FROM listener_sessions " +
            "WHERE status IN ('recording', 'paused') ORDER BY id DESC LIMIT 1"
    )
    abstract suspend fun latestOpen(): ListenerSession?

    @Query("SELECT * FROM listener_sessions WHERE semesterId = :semesterId ORDER BY id DESC")
    abstract suspend fun sessionsOfSemester(semesterId: Long): List<ListenerSession>

    /**
     * Idempotence key for restore: the same exported recording must not be
     * inserted twice (start time is stable across installations).
     */
    @Query(
        "SELECT * FROM listener_sessions WHERE semesterId = :semesterId " +
            "AND startedAt = :startedAt LIMIT 1"
    )
    abstract suspend fun findListenerSession(semesterId: Long, startedAt: Long): ListenerSession?

    @Query("SELECT * FROM listener_segments WHERE sessionId = :sessionId ORDER BY position")
    abstract suspend fun segments(sessionId: Long): List<ListenerSegment>

    @Query("UPDATE listener_sessions SET status = :status WHERE id = :id")
    abstract suspend fun setStatus(id: Long, status: String)

    @Query("UPDATE listener_sessions SET audioPath = :path WHERE id = :id")
    abstract suspend fun setAudioPath(id: Long, path: String?)

    @Query(
        "UPDATE listener_sessions SET status = :status, completedAt = :at WHERE id = :id"
    )
    abstract suspend fun closeSession(id: Long, status: String, at: Long)

    @Query(
        "UPDATE listener_segments SET durationMs = :durationMs WHERE id = :id"
    )
    abstract suspend fun closeSegment(id: Long, durationMs: Long)

    /**
     * Writes a transcript result onto a segment. [transcriptStatus] is one
     * of ListenerSegment.STATUS_READY / STATUS_FAILED. The transcript is the
     * recognizer's VERBATIM text (or the human failure reason when failed).
     */
    @Query(
        "UPDATE listener_segments SET transcript = :transcript, transcriptStatus = :status " +
            "WHERE id = :id AND transcriptStatus = 'pending'"
    )
    abstract suspend fun setTranscript(id: Long, transcript: String, status: String)

    /** The number of still-pending segments in [sessionId] (no double work). */
    @Query(
        "SELECT COUNT(*) FROM listener_segments " +
            "WHERE sessionId = :sessionId AND transcriptStatus = 'pending'"
    )
    abstract suspend fun pendingSegmentCount(sessionId: Long): Int

    @Query("SELECT COUNT(*) FROM listener_sessions WHERE semesterId = :semesterId")
    abstract suspend fun sessionCount(semesterId: Long): Int
}
