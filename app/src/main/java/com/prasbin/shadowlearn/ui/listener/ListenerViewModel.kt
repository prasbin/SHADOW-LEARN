package com.prasbin.shadowlearn.ui.listener

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prasbin.shadowlearn.data.AppContainer
import com.prasbin.shadowlearn.data.db.ListenerSegment
import com.prasbin.shadowlearn.data.listener.ActiveRecording
import com.prasbin.shadowlearn.data.listener.ListenerException
import com.prasbin.shadowlearn.data.listener.ListenerRepository
import com.prasbin.shadowlearn.data.listener.ListenerService
import com.prasbin.shadowlearn.data.listener.ListenerState
import com.prasbin.shadowlearn.data.listener.RecoveredSession
import com.prasbin.shadowlearn.data.listener.SessionTranscription
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.zip
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Explicit transcription state of the Listener tab (Phase 10). */
enum class TranscriptionUi {
    IDLE,
    TRANSCRIBING,
    DONE,
    FAILED
}

/** Distinct screen states of the Listener tab (see ListenerScreen). */
enum class ListenerUiKind {
    LOADING,
    NO_SEMESTER,
    IDLE,
    REQUESTING_PERMISSION,
    RECORDING,
    PAUSED,
    SEGMENTS,
    ERROR
}

data class ListenerUiState(
    val kind: ListenerUiKind = ListenerUiKind.LOADING,
    val yearName: String? = null,
    val semesterName: String? = null,
    val semesterId: Long? = null,
    /** Active recording handle (RECORDING/PAUSED). */
    val recording: ActiveRecording? = null,
    /** Elapsed capture time in ms (ticker-driven). */
    val elapsedMs: Long = 0,
    /** 0..32767 recent peak (0 = idle or unavailable — shown honestly). */
    val amplitude: Int = 0,
    /** Live segment count of the active session. */
    val segmentCount: Int = 0,
    /** Review list (SEGMENTS/ERROR-after-recovery). */
    val segments: List<ListenerSegment> = emptyList(),
    /** Session id under review (completed or interrupted). */
    val reviewSessionId: Long = 0,
    val reviewStatus: String? = null,
    val reviewAudioPath: String? = null,
    val permissionDenied: Boolean = false,
    val permissionPermanentlyDenied: Boolean = false,
    val selectedSegment: ListenerSegment? = null,
    val sessionCount: Int = 0,
    /** Phase 10 transcription progress for the session under review. */
    val transcription: TranscriptionUi = TranscriptionUi.IDLE,
    /** Human summary of the last transcription pass (counts / reason). */
    val transcriptionMessage: String? = null,
    val error: String? = null
)

/**
 * Listener tab state — a snapshot machine over [ListenerRepository].
 * Recording lifecycle (permission → start → pause/resume → stop) mutates
 * SQLite through the repository; the foreground service is started/stopped
 * around the same transitions so capture is always user-visible.
 */
class ListenerViewModel(context: Context) : ViewModel() {

    private val app = context.applicationContext
    private val dao = AppContainer.dao(app)
    private val settings = AppContainer.settings(app)
    private val repo: ListenerRepository = AppContainer.listener(app)
    private val transcriptionRepo = AppContainer.transcription(app)

    private val _state = MutableStateFlow(ListenerUiState())
    val state: StateFlow<ListenerUiState> = _state.asStateFlow()

    private var ticker: Job? = null

    init {
        viewModelScope.launch {
            settings.currentYearId
                .zip(settings.currentSemesterId) { year, sem -> year to sem }
                .distinctUntilChanged()
                .collect { (yearId, semesterId) -> reload(yearId, semesterId) }
        }
    }

    private suspend fun reload(yearId: Long?, semesterId: Long?) {
        val base = ListenerUiState(
            yearName = yearId?.let { dao.getYears().firstOrNull { y -> y.id == yearId }?.name },
            semesterName = if (yearId == null || semesterId == null) null
            else dao.getSemesters(yearId).firstOrNull { it.id == semesterId }?.name,
            semesterId = semesterId
        )
        if (semesterId == null) {
            _state.value = base.copy(kind = ListenerUiKind.NO_SEMESTER)
            return
        }
        // Recover anything left open by a dead process before showing IDLE.
        val recovered = runCatching { repo.rehydrate() }.getOrNull()
        val count = runCatching { repo.sessionsOfSemester(semesterId).size }.getOrDefault(0)
        _state.value = if (recovered != null) {
            base.copy(
                kind = ListenerUiKind.SEGMENTS,
                segments = recovered.segments,
                reviewSessionId = recovered.sessionId,
                reviewStatus = recovered.status,
                reviewAudioPath = recovered.audioPath,
                sessionCount = count,
                error = "The previous recording was interrupted (the app closed mid-capture). " +
                    "Its saved segments are shown below — nothing was deleted."
            )
        } else {
            base.copy(kind = ListenerUiKind.IDLE, sessionCount = count)
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** START RECORDING entry: permission gate first, never record without it. */
    fun onStartPressed() {
        val s = _state.value
        if (s.semesterId == null) return
        if (hasPermission()) {
            startRecording()
        } else {
            _state.value = s.copy(
                kind = ListenerUiKind.REQUESTING_PERMISSION,
                permissionDenied = false,
                permissionPermanentlyDenied = false
            )
        }
    }

    fun onPermissionGranted() = startRecording()

    fun onPermissionDenied(permanently: Boolean) {
        val s = _state.value
        _state.value = s.copy(
            kind = ListenerUiKind.REQUESTING_PERMISSION,
            permissionDenied = true,
            permissionPermanentlyDenied = permanently
        )
    }

    fun cancelPermissionRequest() {
        val s = _state.value
        _state.value = s.copy(
            kind = ListenerUiKind.IDLE,
            permissionDenied = false,
            permissionPermanentlyDenied = false
        )
    }

    fun pause() {
        viewModelScope.launch {
            try {
                val rec = repo.pause()
                ListenerService.stop(app)
                _state.value = _state.value.copy(
                    kind = ListenerUiKind.PAUSED,
                    recording = rec,
                    segmentCount = repo.segments(rec.sessionId).size
                )
            } catch (e: Exception) {
                enterError(e)
            }
        }
    }

    fun resume() {
        viewModelScope.launch {
            try {
                val rec = repo.resume()
                ListenerService.start(app, rec.sessionId)
                _state.value = _state.value.copy(
                    kind = ListenerUiKind.RECORDING,
                    recording = rec,
                    segmentCount = repo.segments(rec.sessionId).size
                )
            } catch (e: Exception) {
                enterError(e)
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            val id = _state.value.recording?.sessionId ?: return@launch
            try {
                repo.stop()
            } catch (e: ListenerException) {
                // stop() still persisted a completed session; surface the
                // warning but show the honest review list.
                _state.value = _state.value.copy(error = e.message)
            } catch (e: Exception) {
                enterError(e)
                return@launch
            } finally {
                ListenerService.stop(app)
            }
            showSegments(id, completed = true)
        }
    }

    fun selectSegment(segment: ListenerSegment?) {
        _state.value = _state.value.copy(selectedSegment = segment)
    }

    /**
     * Phase 10 explicit transcription pass over the session under review.
     * User-initiated only (battery policy: never in the background) — the
     * repository flips `pending` segments to READY/FAILED, then the screen
     * reloads the rows. READY transcripts feed Phase 8 cards unchanged.
     */
    fun transcribeCurrentSession() {
        val s = _state.value
        if (s.kind != ListenerUiKind.SEGMENTS || s.reviewSessionId == 0L) return
        if (s.transcription == TranscriptionUi.TRANSCRIBING) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                transcription = TranscriptionUi.TRANSCRIBING,
                transcriptionMessage = null
            )
            val id = _state.value.reviewSessionId
            val outcome = runCatching { transcriptionRepo.transcribeSession(id) }.getOrElse {
                _state.value = _state.value.copy(
                    transcription = TranscriptionUi.FAILED,
                    transcriptionMessage = "Transcription failed: ${it.message ?: "unknown error"}."
                )
                return@launch
            }
            val segs = runCatching { repo.segments(id) }.getOrDefault(_state.value.segments)
            _state.value = _state.value.copy(
                transcription = if (outcome.failed > 0 && outcome.ready == 0 && outcome.attempted > 0)
                    TranscriptionUi.FAILED else TranscriptionUi.DONE,
                transcriptionMessage = transcriptionSummary(outcome),
                segments = segs,
                segmentCount = segs.size
            )
        }
    }

    /**
     * Cross-link #2: runs the existing lecture-card generation path
     * ([FlashcardRepository.buildDeck] merges READY segments via contentKey —
     * same call Cards makes on load) so the resulting cards are waiting when
     * the user continues into Cards. No new generator, no new persistence.
     */
    fun sendReadyToCards() {
        val semId = _state.value.semesterId ?: return
        viewModelScope.launch {
            runCatching { AppContainer.flashcard(app).buildDeck(semId) }
        }
    }

    private fun transcriptionSummary(outcome: SessionTranscription): String = when {        outcome.attempted == 0 -> "Nothing to transcribe — no pending segments."
        outcome.ready > 0 && outcome.failed == 0 ->
            if (outcome.ready == 1) "Transcribed 1 segment." else "Transcribed ${outcome.ready} segments."
        outcome.ready > 0 -> "Transcribed ${outcome.ready}; ${outcome.failed} unavailable."
        else -> "No transcript produced — the on-device engine is unavailable. Audio stays on the device."
    }

    fun newRecording() {
        viewModelScope.launch {
            runCatching { repo.reset() }
            stopTicker()
            val s = _state.value
            val count = s.semesterId?.let { runCatching { repo.sessionsOfSemester(it).size }.getOrDefault(0) } ?: 0
            _state.value = s.copy(
                kind = ListenerUiKind.IDLE,
                recording = null,
                segments = emptyList(),
                reviewSessionId = 0,
                reviewStatus = null,
                reviewAudioPath = null,
                selectedSegment = null,
                elapsedMs = 0,
                amplitude = 0,
                segmentCount = 0,
                sessionCount = count,
                error = null,
                permissionDenied = false,
                permissionPermanentlyDenied = false,
                transcription = TranscriptionUi.IDLE,
                transcriptionMessage = null
            )
        }
    }

    // ---- internals ---------------------------------------------------------

    private fun startRecording() {
        val semesterId = _state.value.semesterId ?: return
        viewModelScope.launch {
            try {
                val rec = repo.start(semesterId)
                ListenerService.start(app, rec.sessionId)
                _state.value = _state.value.copy(
                    kind = ListenerUiKind.RECORDING,
                    recording = rec,
                    elapsedMs = 0,
                    amplitude = 0,
                    segmentCount = 1,
                    error = null,
                    permissionDenied = false,
                    permissionPermanentlyDenied = false
                )
                startTicker()
            } catch (e: Exception) {
                enterError(e)
            }
        }
    }

    private suspend fun showSegments(sessionId: Long, completed: Boolean) {
        stopTicker()
        val segs = repo.segments(sessionId)
        val s = _state.value
        val count = s.semesterId?.let { runCatching { repo.sessionsOfSemester(it).size }.getOrDefault(0) } ?: 0
        _state.value = s.copy(
            kind = ListenerUiKind.SEGMENTS,
            recording = null,
            segments = segs,
            reviewSessionId = sessionId,
            reviewStatus = if (completed) "completed" else s.reviewStatus,
            reviewAudioPath = s.recording?.audioPath ?: s.reviewAudioPath,
            elapsedMs = 0,
            amplitude = 0,
            segmentCount = segs.size,
            sessionCount = count,
            selectedSegment = null
        )
    }

    private suspend fun enterError(e: Exception) {
        ListenerService.stop(app)
        stopTicker()
        val id = _state.value.recording?.sessionId ?: 0
        val segs = if (id != 0L) runCatching { repo.segments(id) }.getOrDefault(emptyList()) else emptyList()
        _state.value = _state.value.copy(
            kind = ListenerUiKind.ERROR,
            recording = null,
            segments = segs,
            reviewSessionId = id,
            elapsedMs = 0,
            amplitude = 0,
            error = e.message ?: "Recording failed."
        )
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                val s = _state.value
                if (s.kind != ListenerUiKind.RECORDING && s.kind != ListenerUiKind.PAUSED) break
                _state.value = s.copy(
                    elapsedMs = repo.recordingElapsed(),
                    amplitude = repo.amplitude()
                )
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    override fun onCleared() {
        stopTicker()
        super.onCleared()
    }

    companion object {
        private const val TICK_MS = 500L

        fun factory(context: Context) = viewModelFactory {
            initializer { ListenerViewModel(context.applicationContext) }
        }
    }
}
