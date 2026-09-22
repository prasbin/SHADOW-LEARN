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
import com.prasbin.shadowlearn.data.ingest.IngestRepository
import com.prasbin.shadowlearn.data.ingest.IngestState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class IngestScreenState(
    val years: List<AcademicYear> = emptyList(),
    val currentYearId: Long? = null,
    val currentSemesterId: Long? = null,
    val ingest: IngestState = IngestState.Idle
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

    fun semestersOf(yearId: Long): Flow<List<Semester>> = dao.observeSemesters(yearId)
    fun modulesOf(semesterId: Long): Flow<List<Module>> = dao.observeModules(semesterId)
    fun weeksOf(moduleId: Long) = dao.observeWeeks(moduleId)
    fun filesOf(weekId: Long) = dao.observeFiles(weekId)

    val state: StateFlow<IngestScreenState> = combine(
        dao.observeYears(),
        settings.currentYearId,
        settings.currentSemesterId,
        repo.state
    ) { years, yearId, semesterId, ingest ->
        IngestScreenState(years, yearId, semesterId, ingest)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IngestScreenState())

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
        viewModelScope.launch { repo.import(uri, semesterId, label) }
    }

    fun cancelImport() = repo.cancel()

    fun resetImport() = repo.reset()

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { IngestViewModel(context.applicationContext) }
        }
    }
}
