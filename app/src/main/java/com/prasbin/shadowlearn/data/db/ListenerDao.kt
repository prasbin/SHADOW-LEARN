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

    @Query("SELECT COUNT(*) FROM listener_sessions WHERE semesterId = :semesterId")
    abstract suspend fun sessionCount(semesterId: Long): Int
}
