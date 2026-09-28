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
 * Dashboard state. Level / XP / streak come from the Phase 9 progression
 * engine, derived from real persisted quiz + flashcard activity (never
 * fabricated). Year / semester / module counts come from the real local
 * database.
 */
data class DashboardUiState(
    val level: Int = 1,
    val xp: Long = 0L,
    val xpIntoLevel: Long = 0L,
    val xpForLevel: Long = 100L,
    val xpToNextLevel: Long = 100L,
    val levelProgress: Float = 0f,
    val mission: String = "Import your first semester ZIP (Phase 2)",
    val progressPct: Int = 0,
    /** Honest basis line for [progressPct], "" when nothing is reached. */
    val progressBasis: String = "",
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
    private val progression = AppContainer.progression(context.applicationContext)
    private val academicProgress = AppContainer.academicProgress(context.applicationContext)

    val state = combine(
        combine(
            dao.observeModuleCount(),
            settings.currentYearId,
            settings.currentSemesterId
        ) { modules, yearId, semesterId -> Triple(modules, yearId, semesterId) },
        progression.observe(),
        academicProgress.observe()
    ) { scope, prog, progress ->
        val (modules, yearId, semesterId) = scope
        DashboardUiState(
            level = prog.level,
            xp = prog.totalXp,
            xpIntoLevel = prog.xpIntoLevel,
            xpForLevel = prog.xpForLevel,
            xpToNextLevel = prog.xpToNextLevel,
            levelProgress = prog.levelProgress,
            progressPct = progress.percent,
            progressBasis = progress.basis,
            moduleCount = modules,
            currentYear = if (yearId == null) "Not configured" else "Year #$yearId",
            currentSemester = if (semesterId == null) "Not configured" else "Semester #$semesterId",
            streak = prog.streak
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { DashboardViewModel(context.applicationContext) }
        }
    }
}
