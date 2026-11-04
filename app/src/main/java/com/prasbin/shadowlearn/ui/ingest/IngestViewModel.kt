package com.prasbin.shadowlearn.ui.ingest

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prasbin.shadowlearn.data.AppContainer
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.ingest.DirectImportResult
import com.prasbin.shadowlearn.data.ingest.ExtractionRepository
import com.prasbin.shadowlearn.data.ingest.ExtractionState
import com.prasbin.shadowlearn.data.ingest.IngestRepository
import com.prasbin.shadowlearn.data.ingest.IngestState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One directly-picked file and its verified display name. */
data class PickedSource(val uri: Uri, val name: String)

/** Per-source direct-upload lifecycle (QUEUED → IMPORTING → PROCESSING → READY). */
enum class DirectUploadStatus {
    QUEUED,
    IMPORTING,
    PROCESSING,
    READY,
    DUPLICATE,
    FAILED
}

data class DirectUpload(
    val name: String,
    val status: DirectUploadStatus,
    val reason: String? = null,
    val fileId: Long? = null
)

data class IngestScreenState(
    val years: List<AcademicYear> = emptyList(),
    val currentYearId: Long? = null,
    val currentSemesterId: Long? = null,
    val ingest: IngestState = IngestState.Idle,
    val extraction: ExtractionState = ExtractionState.Idle
)

/**
 * Academic tab state: Year/Semester context (shared with Settings via
 * DataStore) + ZIP import orchestration. No Phase 3+ logic lives here.
 */
class IngestViewModel(context: Context) : ViewModel() {

    private val app = context.applicationContext
    private val dao = AppContainer.dao(app)
    private val settings = AppContainer.settings(app)
    private val repo = IngestRepository(app, dao)
    private val extraction = AppContainer.extraction(app)

    fun semestersOf(yearId: Long): Flow<List<Semester>> = dao.observeSemesters(yearId)
    fun modulesOf(semesterId: Long): Flow<List<Module>> = dao.observeModules(semesterId)
    fun weeksOf(moduleId: Long) = dao.observeWeeks(moduleId)
    fun filesOf(weekId: Long) = dao.observeFiles(weekId)

    /** Bounded page for large notebooks; [limit] grows only on LOAD MORE. */
    fun filesPage(weekId: Long, limit: Int) = dao.observeFilesLimited(weekId, limit)

    val state: StateFlow<IngestScreenState> = combine(
        dao.observeYears(),
        settings.currentYearId,
        settings.currentSemesterId,
        repo.state,
        extraction.state
    ) { years, yearId, semesterId, ingest, extraction ->
        IngestScreenState(years, yearId, semesterId, ingest, extraction)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IngestScreenState())

    private val _direct = MutableStateFlow<List<DirectUpload>>(emptyList())

    /** Per-source direct-upload statuses, newest run only. */
    val direct: StateFlow<List<DirectUpload>> = _direct.asStateFlow()

    fun semestersFlow(yearId: Long?): Flow<List<Semester>> =
        if (yearId == null) kotlinx.coroutines.flow.flowOf(emptyList())
        else dao.observeSemesters(yearId)

    fun selectYear(id: Long?) {
        viewModelScope.launch {
            settings.setCurrentYear(id)
            settings.setCurrentSemester(null)
        }
    }

    fun selectSemester(id: Long?) {
        viewModelScope.launch { settings.setCurrentSemester(id) }
    }

    fun importZip(uri: Uri, semesterId: Long, label: String) {
        viewModelScope.launch {
            repo.import(uri, semesterId, label)
            // Phase 4: index the semester corpus once storage completed cleanly.
            if (repo.state.value is IngestState.Done) {
                extraction.processForSemester(semesterId)
            }
        }
    }

    fun cancelImport() = repo.cancel()

    fun resetImport() = repo.reset()

    fun resetDirect() {
        _direct.value = emptyList()
    }

    /**
     * Direct multi-file upload into the selected semester at
     * [moduleName]/[weekNumber]. Each file is stored with the same verified
     * pipeline as ZIP import, then extracted + indexed individually so the
     * list can show READY only after real indexed chunks exist.
     *
     * One bad file never stops the rest. Process death is safe: rows are only
     * created after a complete copy + hash, and staging lives in cache.
     */
    fun importFiles(
        files: List<PickedSource>,
        semesterId: Long,
        moduleName: String,
        weekNumber: Int
    ) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            _direct.value = files.map { DirectUpload(it.name, DirectUploadStatus.QUEUED) }
            files.forEachIndexed { index, picked ->
                updateDirect(index) {
                    it.copy(status = DirectUploadStatus.IMPORTING, reason = null)
                }
                when (val result = repo.importFile(
                    uri = picked.uri,
                    semesterId = semesterId,
                    moduleName = moduleName,
                    weekNumber = weekNumber
                )) {
                    is DirectImportResult.Imported -> {
                        updateDirect(index) {
                            it.copy(status = DirectUploadStatus.PROCESSING, fileId = result.fileId)
                        }
                        val summary = extraction.processFile(result.fileId)
                        val ready = summary.failed == 0 &&
                            (summary.extracted + summary.reused) > 0
                        updateDirect(index) {
                            if (ready) {
                                it.copy(status = DirectUploadStatus.READY)
                            } else {
                                it.copy(
                                    status = DirectUploadStatus.FAILED,
                                    reason = summary.errors.firstOrNull()
                                        ?: "No extractable text was found in this file."
                                )
                            }
                        }
                    }
                    is DirectImportResult.Duplicate -> updateDirect(index) {
                        it.copy(
                            status = DirectUploadStatus.DUPLICATE,
                            reason = "Already imported in this semester (same content hash)."
                        )
                    }
                    is DirectImportResult.Failed -> updateDirect(index) {
                        it.copy(status = DirectUploadStatus.FAILED, reason = result.reason)
                    }
                }
            }
        }
    }

    private fun updateDirect(index: Int, transform: (DirectUpload) -> DirectUpload) {
        _direct.value = _direct.value.mapIndexed { i, item ->
            if (i == index) transform(item) else item
        }
    }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { IngestViewModel(context.applicationContext) }
        }
    }
}
