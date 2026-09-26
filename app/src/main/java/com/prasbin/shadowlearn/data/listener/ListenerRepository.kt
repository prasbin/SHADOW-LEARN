package com.prasbin.shadowlearn.data.listener

import android.os.SystemClock
import com.prasbin.shadowlearn.data.db.ListenerDao
import com.prasbin.shadowlearn.data.db.ListenerSegment
import com.prasbin.shadowlearn.data.db.ListenerSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Thrown for illegal Listener Mode operations (bad transitions, no session). */
class ListenerException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Live handle of the session currently being recorded. */
data class ActiveRecording(
    val sessionId: Long,
    val semesterId: Long,
    val audioPath: String?,
    val state: ListenerState
)

/** A recovered session after process death / recorder failure. */
data class RecoveredSession(
    val sessionId: Long,
    val semesterId: Long,
    val status: String,
    val audioPath: String?,
    val segments: List<ListenerSegment>
)

/**
 * Phase 7 Listener Mode orchestrator — the only entry point the UI talks to.
 *
 * Owns the [ListenerState] machine, the [ListenerRecorder], segment-boundary
 * math, and all persistence. Segment times use the monotonic [monoClock]
 * (pause math is exact and immune to wall-clock jumps); wall time is only
 * stamped on session rows.
 *
 * Failure contract: a recorder throw never corrupts the DB — the session is
 * marked `failed` (or `interrupted` on rehydration) with whatever segments
 * were safely persisted, and the throw is wrapped in [ListenerException].
 * A missing/empty audio file is reported honestly (`audioPath == null`),
 * never faked.
 */
class ListenerRepository(
    private val dao: ListenerDao,
    /** App-private directory for recordings (created on demand). */
    private val audioDir: File,
    private val recorderFactory: () -> ListenerRecorder = { MediaRecorderListenerRecorder() },
    private val monoClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val lock = Mutex()

    private var recorder: ListenerRecorder? = null
    private var sessionId: Long = 0
    private var semesterId: Long = 0
    private var state: ListenerState = ListenerState.IDLE
    private var sessionMonoStart: Long = 0
    private var segmentMonoStart: Long = 0
    private var openSegmentId: Long = 0
    private var nextPosition: Int = 0

    /** Begins a new recording for [semesterId]. Fails closed on any error. */
    suspend fun start(semesterId: Long): ActiveRecording = withContext(dispatcher) {
        lock.withLock {
            ListenerState.requireTransition(state, ListenerState.RECORDING)
            require(semesterId > 0) { "No semester selected" }
            val nowWall = wallClock()
            val id = dao.insertSession(
                ListenerSession(semesterId = semesterId, startedAt = nowWall, createdAt = nowWall)
            )
            val file = audioFile(id, nowWall)
            val rec = recorderFactory()
            try {
                file.parentFile?.mkdirs()
                rec.start(file)
            } catch (e: Exception) {
                runCatching { rec.release() }
                dao.setStatus(id, ListenerSession.STATUS_FAILED)
                // NOTE: the machine deliberately stays IDLE here (no
                // IDLE→ERROR transition exists): no session became active,
                // so a retry is a plain start(). The failed ROW carries the
                // failure for audit; failLocked() covers active-session
                // failures, which do move to ERROR.
                throw ListenerException("Recording could not start: ${e.message}", e)
            }
            // The recorder accepted the file: persist the path (a later
            // missing/empty file is still reported honestly at stop time).
            dao.setAudioPath(id, file.absolutePath)
            val mono = monoClock()
            sessionMonoStart = mono
            segmentMonoStart = mono
            openSegmentId = dao.insertSegment(
                ListenerSegment(sessionId = id, position = 0, startedAtMs = 0)
            )
            nextPosition = 1
            recorder = rec
            this@ListenerRepository.sessionId = id
            this@ListenerRepository.semesterId = semesterId
            state = ListenerState.RECORDING
            ActiveRecording(id, semesterId, file.absolutePath, state)
        }
    }

    /** Suspends capture and closes the current segment with exact duration. */
    suspend fun pause(): ActiveRecording = withContext(dispatcher) {
        lock.withLock {
            ListenerState.requireTransition(state, ListenerState.PAUSED)
            try {
                recorder?.pause()
            } catch (e: Exception) {
                return@withContext failLocked("Pause failed: ${e.message}", e)
            }
            closeOpenSegmentLocked()
            dao.setStatus(sessionId, ListenerSession.STATUS_PAUSED)
            state = ListenerState.PAUSED
            ActiveRecording(sessionId, semesterId, dao.session(sessionId)?.audioPath, state)
        }
    }

    /** Resumes capture, opening a new segment at the current offset. */
    suspend fun resume(): ActiveRecording = withContext(dispatcher) {
        lock.withLock {
            // Stricter than the generic gate on purpose: IDLE→RECORDING is
            // legal for start() (permission pre-granted path), but resume()
            // without a paused session — and its session row — must fail
            // instead of inserting an orphan segment.
            check(state == ListenerState.PAUSED) {
                "Resume requires a paused session (current=$state)"
            }
            try {
                recorder?.resume()
            } catch (e: Exception) {
                return@withContext failLocked("Resume failed: ${e.message}", e)
            }
            segmentMonoStart = monoClock()
            openSegmentId = dao.insertSegment(
                ListenerSegment(
                    sessionId = sessionId,
                    position = nextPosition++,
                    startedAtMs = segmentMonoStart - sessionMonoStart
                )
            )
            dao.setStatus(sessionId, ListenerSession.STATUS_RECORDING)
            state = ListenerState.RECORDING
            ActiveRecording(sessionId, semesterId, dao.session(sessionId)?.audioPath, state)
        }
    }

    /**
     * Finalizes the session: closes the open span, stops the recorder, marks
     * `completed`. A recorder that throws on stop (e.g. nothing captured)
     * still yields a completed session row — with `audioPath` cleared when
     * the file is missing/empty, so the UI can say so honestly.
     */
    suspend fun stop(): ActiveRecording = withContext(dispatcher) {
        lock.withLock {
            ListenerState.requireTransition(state, ListenerState.COMPLETED)
            closeOpenSegmentLocked()
            val rec = recorder
            recorder = null
            var stopError: Exception? = null
            try {
                rec?.stop()
            } catch (e: Exception) {
                stopError = e
            } finally {
                runCatching { rec?.release() }
            }
            val path = dao.session(sessionId)?.audioPath
            if (path == null || !usableAudioFile(path)) {
                dao.setAudioPath(sessionId, null)
            }
            dao.closeSession(sessionId, ListenerSession.STATUS_COMPLETED, wallClock())
            state = ListenerState.COMPLETED
            if (stopError != null) {
                throw ListenerException(
                    "Recording stopped with errors: ${stopError.message}. " +
                        "The session was still saved.",
                    stopError
                )
            }
            ActiveRecording(sessionId, semesterId, dao.session(sessionId)?.audioPath, state)
        }
    }

    /** Resets the machine to IDLE after a completed/error session. Idempotent. */
    suspend fun reset() = withContext(dispatcher) {
        lock.withLock {
            if (state == ListenerState.IDLE) {
                clearLocked()
                return@withLock
            }
            ListenerState.requireTransition(state, ListenerState.IDLE)
            clearLocked()
        }
    }

    /**
     * Recovers the latest still-open session after process death: closes any
     * open span (duration capped at recovery time), marks `interrupted`, and
     * returns it for the ERROR/recovery UI. Returns null when nothing was
     * left open. Never deletes anything.
     */
    suspend fun rehydrate(): RecoveredSession? = withContext(dispatcher) {
        lock.withLock {
            val open = dao.latestOpen() ?: return@withContext null
            val segs = dao.segments(open.id)
            val last = segs.lastOrNull()
            if (last != null && last.durationMs == 0L) {
                // The monotonic origin died with the process, so the tail
                // span's true length is unknowable — close it with duration
                // 0 (unknown) rather than fabricating precision.
                dao.closeSegment(last.id, 0)
            }
            dao.closeSession(open.id, ListenerSession.STATUS_INTERRUPTED, wallClock())
            RecoveredSession(
                sessionId = open.id,
                semesterId = open.semesterId,
                status = ListenerSession.STATUS_INTERRUPTED,
                audioPath = open.audioPath?.takeIf { usableAudioFile(it) },
                segments = dao.segments(open.id)
            )
        }
    }

    suspend fun segments(sessionId: Long): List<ListenerSegment> =
        dao.segments(sessionId)

    suspend fun sessionsOfSemester(semesterId: Long) =
        dao.sessionsOfSemester(semesterId)

    /** Recent peak amplitude, or 0 when idle/unavailable. Never throws. */
    fun amplitude(): Int = runCatching { recorder?.maxAmplitude() ?: 0 }.getOrDefault(0)

    /** Milliseconds captured so far in the active session (0 when idle). */
    fun recordingElapsed(): Long =
        if (sessionId == 0L || (state != ListenerState.RECORDING && state != ListenerState.PAUSED)) 0
        else (monoClock() - sessionMonoStart).coerceAtLeast(0L)

    fun currentState(): ListenerState = state

    fun currentSessionId(): Long = sessionId

    // ---- internals (lock held) -------------------------------------------

    private suspend fun closeOpenSegmentLocked() {
        if (openSegmentId != 0L) {
            val duration = (monoClock() - segmentMonoStart).coerceAtLeast(0L)
            dao.closeSegment(openSegmentId, duration)
            openSegmentId = 0
        }
    }

    private suspend fun failLocked(message: String, cause: Throwable?): ActiveRecording {
        runCatching { recorder?.release() }
        recorder = null
        if (sessionId != 0L) {
            closeOpenSegmentLocked()
            dao.setStatus(sessionId, ListenerSession.STATUS_FAILED)
        }
        state = ListenerState.ERROR
        throw ListenerException(message, cause)
    }

    private fun clearLocked() {
        recorder = null
        sessionId = 0
        semesterId = 0
        state = ListenerState.IDLE
        sessionMonoStart = 0
        segmentMonoStart = 0
        openSegmentId = 0
        nextPosition = 0
    }

    private fun audioFile(id: Long, wallTs: Long): File =
        File(audioDir, "listener_${id}_${wallTs}.m4a")

    private fun usableAudioFile(path: String): Boolean {
        val f = File(path)
        return f.exists() && f.length() > 0
    }
}
