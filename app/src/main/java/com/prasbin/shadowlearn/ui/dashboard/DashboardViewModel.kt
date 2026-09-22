package com.prasbin.shadowlearn.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prasbin.shadowlearn.data.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Dashboard state. XP / level / streak / progress read from the progression
 * engine in Phase 6 — until then they are honest zeros (never fabricated).
 * Year / semester / module counts come from the real local database.
 */
data class DashboardUiState(
    val level: Int = 1,
    val xp: Int = 0,
    val mission: String = "Import your first semester ZIP (Phase 2)",
    val progressPct: Int = 0,
    val currentYear: String = "Not configured",
    val currentSemester: String = "Not configured",
    val moduleCount: Int = 0,
    val streak: Int = 0
)

class DashboardViewModel(
    context: Context
) : ViewModel() {

    private val dao = AppContainer.dao(context.applicationContext)
    private val settings = AppContainer.settings(context.applicationContext)

    val state = combine(
        dao.observeModuleCount(),
        settings.currentYearId,
        settings.currentSemesterId
    ) { modules, yearId, semesterId ->
        DashboardUiState(
            moduleCount = modules,
            currentYear = if (yearId == null) "Not configured" else "Year #$yearId",
            currentSemester = if (semesterId == null) "Not configured" else "Semester #$semesterId"
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { DashboardViewModel(context.applicationContext) }
        }
    }
}
