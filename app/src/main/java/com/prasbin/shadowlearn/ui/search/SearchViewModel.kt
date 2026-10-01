package com.prasbin.shadowlearn.ui.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prasbin.shadowlearn.data.AppContainer
import com.prasbin.shadowlearn.data.search.SearchOutcome
import com.prasbin.shadowlearn.data.search.SearchRepository
import com.prasbin.shadowlearn.data.search.SearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** Distinct visual states of the search screen (see SearchScreen). */
enum class SearchUiKind { EMPTY, NO_SEMESTER, NO_INDEXED, SEARCHING, RESULTS, NO_RESULTS, ERROR }

data class SearchUiState(
    val query: String = "",
    val yearName: String? = null,
    val semesterName: String? = null,
    val semesterId: Long? = null,
    val indexedChunkCount: Int = 0,
    val kind: SearchUiKind = SearchUiKind.EMPTY,
    val results: List<SearchResult> = emptyList(),
    val maxScore: Double = 0.0,
    val error: String? = null
)

/**
 * Search tab state. Query text is debounced (250 ms) and every stable
 * change re-runs the search on the current semester; switching the
 * semester (Settings/Academic tab, DataStore-backed) re-runs the current
 * query against the new scope automatically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(context: Context) : ViewModel() {

    private val app = context.applicationContext
    private val dao = AppContainer.dao(app)
    private val settings = AppContainer.settings(app)
    private val repo: SearchRepository = AppContainer.search(app)

    private val query = MutableStateFlow("")

    val state: StateFlow<SearchUiState> = combine(
        settings.currentYearId,
        settings.currentSemesterId,
        query
    ) { yearId, semesterId, text -> Triple(yearId, semesterId, text) }
        .debounce(DEBOUNCE_MS)
        .flatMapLatest { (yearId, semesterId, text) -> produce(text, yearId, semesterId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    private fun produce(text: String, yearId: Long?, semesterId: Long?): Flow<SearchUiState> = flow {
        val base = SearchUiState(
            query = text,
            yearName = resolveYearName(yearId),
            semesterName = resolveSemesterName(yearId, semesterId),
            semesterId = semesterId
        )
        when {
            text.isBlank() -> emit(base)
            semesterId == null -> emit(base.copy(kind = SearchUiKind.NO_SEMESTER))
            else -> {
                val count = repo.indexedChunkCount(semesterId)
                if (count == 0) {
                    emit(base.copy(indexedChunkCount = 0, kind = SearchUiKind.NO_INDEXED))
                    return@flow
                }
                emit(base.copy(indexedChunkCount = count, kind = SearchUiKind.SEARCHING))
                delay(SEARCHING_MIN_MS) // keep the indicator readable on fast indexes
                when (val outcome = repo.search(text, semesterId)) {
                    is SearchOutcome.Results -> {
                        val kind = if (outcome.results.isEmpty()) SearchUiKind.NO_RESULTS else SearchUiKind.RESULTS
                        emit(base.copy(kind = kind, results = outcome.results, maxScore = outcome.maxScore))
                    }
                    is SearchOutcome.Failed -> emit(base.copy(kind = SearchUiKind.ERROR, error = outcome.message))
                }
            }
        }
    }

    private suspend fun resolveYearName(yearId: Long?): String? {
        if (yearId == null) return null
        return dao.getYears().firstOrNull { it.id == yearId }?.name
    }

    private suspend fun resolveSemesterName(yearId: Long?, semesterId: Long?): String? {
        if (semesterId == null || yearId == null) return null
        return dao.getSemesters(yearId).firstOrNull { it.id == semesterId }?.name
    }

    fun onQueryChange(text: String) {
        query.value = text
    }

    /**
     * Cross-link #1: closest hierarchy destination for a search hit's file
     * (FILE → WEEK level). Null when the file row is gone — the dialog
     * then shows no hierarchy action instead of a dead one.
     */
    suspend fun hierarchyTargetFor(fileId: Long): String? =
        com.prasbin.shadowlearn.navigation.hierarchyWeekRoute(dao.file(fileId)?.weekId)

    companion object {
        const val DEBOUNCE_MS = 250L
        const val SEARCHING_MIN_MS = 200L

        fun factory(context: Context) = viewModelFactory {
            initializer { SearchViewModel(context.applicationContext) }
        }
    }
}