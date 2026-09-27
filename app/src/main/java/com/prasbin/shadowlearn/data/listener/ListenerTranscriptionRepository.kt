package com.prasbin.shadowlearn.data.listener

import com.prasbin.shadowlearn.data.db.ListenerDao
import com.prasbin.shadowlearn.data.db.ListenerSegment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Outcome of one `transcribeSession` pass (counts only, never content). */
data class SessionTranscription(
    val attempted: Int,
    val ready: Int,
    val failed: Int
)

/**
 * Phase 10 Listener transcription orchestrator — the ONE entry point for
 * transcribing segments.
 *
 * Audio resolution: segments carry NO audio path of their own. The single
 * recording file lives on the parent [ListenerSession.audioPath]
 * (app-private `filesDir/listener/…`); every segment of the session is
 * transcribed against that one file. Raw bytes are never read into SQLite.
 *
 * Rules: only `pending` segments are attempted (guarded both here and by
 * `ListenerDao.setTranscript`, which refuses non-pending rows); a segment
 * becomes READY with the recognizer's VERBATIM text or FAILED with an
 * honest human reason — never invented speech. READY/FAILED rows are never
 * touched again, so repeated calls are safe and idempotent. Once READY, a
 * segment flows into Phase 8 deck generation unchanged.
 */
class ListenerTranscriptionRepository(
    private val dao: ListenerDao,
    private val transcriber: Transcriber,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Transcribes every `pending` segment of [sessionId] in position order.
     * Missing session, missing/empty audio, unavailable engine, and
     * transcriber throws all resolve to honest FAILED rows — never a crash,
     * never fabricated text.
     */
    suspend fun transcribeSession(sessionId: Long): SessionTranscription =
        withContext(dispatcher) {
            val session = dao.session(sessionId) ?: return@withContext SessionTranscription(0, 0, 0)
            val audioFile = session.audioPath?.let { File(it) }
            val segments = dao.segments(sessionId)
                .filter { it.transcriptStatus == ListenerSegment.STATUS_PENDING }
            var ready = 0
            var failed = 0
            for (segment in segments) {
                val outcome = transcribeOne(audioFile, segment)
                if (outcome) ready++ else failed++
            }
            SessionTranscription(attempted = segments.size, ready = ready, failed = failed)
        }

    private suspend fun transcribeOne(audioFile: File?, segment: ListenerSegment): Boolean {
        if (audioFile == null || !audioFile.exists() || audioFile.length() <= 0L) {
            dao.setTranscript(
                segment.id,
                "Transcript unavailable: no usable audio for this segment.",
                ListenerSegment.STATUS_FAILED
            )
            return false
        }
        val result = runCatching { transcriber.transcribe(audioFile, null) }
            .getOrElse { e ->
                TranscriptionResult.Failed("Transcription failed: ${e.message ?: "unknown error"}.")
            }
        return when (result) {
            is TranscriptionResult.Ready -> {
                val text = result.transcript.trim()
                if (text.isEmpty()) {
                    dao.setTranscript(
                        segment.id,
                        "Transcript unavailable: the engine returned empty text.",
                        ListenerSegment.STATUS_FAILED
                    )
                    false
                } else {
                    dao.setTranscript(segment.id, text, ListenerSegment.STATUS_READY)
                    true
                }
            }
            is TranscriptionResult.Failed -> {
                dao.setTranscript(segment.id, result.reason, ListenerSegment.STATUS_FAILED)
                false
            }
        }
    }
}
