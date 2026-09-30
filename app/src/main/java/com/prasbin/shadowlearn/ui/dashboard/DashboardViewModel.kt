package com.prasbin.shadowlearn.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prasbin.shadowlearn.data.AppContainer
import com.prasbin.shadowlearn.data.home.ActivityEvent
import com.prasbin.shadowlearn.data.home.FocusState
import com.prasbin.shadowlearn.data.home.HomeObjective
import com.prasbin.shadowlearn.data.home.Recommendation
import com.prasbin.shadowlearn.data.home.WeakArea
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Dashboard state — the SYSTEM HOME snapshot. Level / XP / streak come
 * from the Phase 9 progression engine, Progress % from Phase 12, and
 * objectives / weak areas / activity / focus / recommendation from the
 * system-home aggregator — all derived from real persisted rows (never
 * fabricated). Year / semester names resolve from the academic tables;
 * a dangling selection reports honestly instead of showing raw ids.
 */
data class DashboardUiState(
    val level: Int = 1,
    val xp: Long = 0L,
    val xpIntoLevel: Long = 0L,
    val xpForLevel: Long = 100L,
    val xpToNextLevel: Long = 100L,
    val levelProgress: Float = 0f,
    val progressPct: Int = 0,
    /** Honest basis line for [progressPct], "" when nothing is reached. */
    val progressBasis: String = "",
    val currentYear: String = "Not configured",
    val currentSemester: String = "Not configured",
    val scopeValid: Boolean = false,
    val moduleCount: Int = 0,
    val streak: Int = 0,
    val focus: FocusState? = null,
    val objectives: List<HomeObjective> = emptyList(),
    val weakAreas: List<WeakArea> = emptyList(),
    val activity: List<ActivityEvent> = emptyList(),
    val recommendation: Recommendation? = null
)

class DashboardViewModel(
    context: Context
) : ViewModel() {

    private val dao = AppContainer.dao(context.applicationContext)
    private val settings = AppContainer.settings(context.applicationContext)
    private val progression = AppContainer.progression(context.applicationContext)
    private val academicProgress = AppContainer.academicProgress(context.applicationContext)
    private val home = AppContainer.systemHome(context.applicationContext)

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
        val yearName = yearId?.let { id ->
            runCatching { dao.getYears().firstOrNull { it.id == id }?.name }.getOrNull()
        }
        val semesterName = if (yearId == null || semesterId == null) null else runCatching {
            dao.getSemesters(yearId).firstOrNull { it.id == semesterId }?.name
        }.getOrNull()
        val scopeValid = yearName != null && semesterName != null
        val snapshot = if (semesterId != null && scopeValid) {
            runCatching { home.snapshot(semesterId, System.currentTimeMillis()) }.getOrNull()
        } else {
            null
        }
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
            currentYear = yearName ?: if (yearId == null) "Not configured" else "Selection unavailable",
            currentSemester = semesterName ?: if (semesterId == null) "Not configured" else "Selection unavailable",
            scopeValid = scopeValid,
            streak = prog.streak,
            focus = snapshot?.focus,
            objectives = snapshot?.objectives ?: emptyList(),
            weakAreas = snapshot?.weakAreas ?: emptyList(),
            activity = snapshot?.activity ?: emptyList(),
            recommendation = snapshot?.recommendation
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { DashboardViewModel(context.applicationContext) }
        }
    }
}
