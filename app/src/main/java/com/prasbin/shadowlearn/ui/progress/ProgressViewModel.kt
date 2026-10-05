package com.prasbin.shadowlearn.ui.progress

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prasbin.shadowlearn.data.AppContainer
import com.prasbin.shadowlearn.data.intelligence.GroundedExplanation
import com.prasbin.shadowlearn.data.intelligence.RelatedMaterial
import com.prasbin.shadowlearn.data.intelligence.WeaknessSignal
import com.prasbin.shadowlearn.data.progression.ProgressMilestones
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Academic Status state. Percent/basis/milestones come from the Phase 12
 * progress engine, level/XP/streak from the Phase 9 progression engine —
 * the same sources SYSTEM HOME reads, so the two screens cannot disagree.
 * Year/semester names resolve with the same semantics as Home; a dangling
 * selection reports honestly instead of showing raw ids.
 */
data class ProgressUiState(
    val percent: Int = 0,
    val basis: String = "",
    val milestones: ProgressMilestones = ProgressMilestones(),
    val level: Int = 1,
    val xp: Long = 0L,
    val streak: Int = 0,
    val currentYear: String = "Not configured",
    val currentSemester: String = "Not configured",
    val scopeValid: Boolean = false,
    /** Same I1 engine result Home reads (max 3 displayed); empty = UNKNOWN. */
    val learningSignals: List<WeaknessSignal> = emptyList(),
    /** I4 explanations keyed by owning file id — same shared objects as Home. */
    val signalExplanations: Map<Long, GroundedExplanation> = emptyMap(),
    /** I6 related materials keyed by owning file id — same shared results as Home. */
    val signalRelated: Map<Long, List<RelatedMaterial>> = emptyMap()
)

class ProgressViewModel(context: Context) : ViewModel() {

    private val dao = AppContainer.dao(context.applicationContext)
    private val settings = AppContainer.settings(context.applicationContext)
    private val academicProgress = AppContainer.academicProgress(context.applicationContext)
    private val progression = AppContainer.progression(context.applicationContext)
    private val evidence = AppContainer.evidence(context.applicationContext)
    private val retrieval = AppContainer.retrieval(context.applicationContext)
    private val relationships = AppContainer.relationships(context.applicationContext)

    val state = combine(
        combine(
            academicProgress.observe(),
            academicProgress.observeMilestones(),
            progression.observe()
        ) { progress, milestones, prog -> Triple(progress, milestones, prog) },
        combine(
            settings.currentYearId,
            settings.currentSemesterId
        ) { yearId, semesterId -> Pair(yearId, semesterId) }
    ) { data, scope ->
        val (progress, milestones, prog) = data
        val (yearId, semesterId) = scope
        val yearName = yearId?.let { id ->
            runCatching { dao.getYears().firstOrNull { it.id == id }?.name }.getOrNull()
        }
        val semesterName = if (yearId == null || semesterId == null) null else runCatching {
            dao.getSemesters(yearId).firstOrNull { it.id == semesterId }?.name
        }.getOrNull()
        val scopeOk = yearName != null && semesterName != null && semesterId != null
        val signals = if (scopeOk) {
            runCatching {
                evidence.weaknessSignals(semesterId!!, System.currentTimeMillis()).take(3)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        ProgressUiState(
            percent = progress.percent,
            basis = progress.basis,
            milestones = milestones,
            level = prog.level,
            xp = prog.totalXp,
            streak = prog.streak,
            currentYear = yearName ?: if (yearId == null) "Not configured" else "Selection unavailable",
            currentSemester = semesterName ?: if (semesterId == null) "Not configured" else "Selection unavailable",
            scopeValid = yearName != null && semesterName != null,
            learningSignals = signals,
            signalExplanations = if (scopeOk) {
                runCatching {
                    retrieval.explainAll(
                        semesterId!!,
                        signals,
                        "Explain the material associated with this weak area."
                    )
                }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            },
            signalRelated = if (scopeOk) {
                val out = mutableMapOf<Long, List<RelatedMaterial>>()
                for (signal in signals) {
                    val fileId = signal.fileId ?: continue
                    if (out.containsKey(fileId)) continue
                    out[fileId] = runCatching {
                        relationships.relatedFor(semesterId!!, fileId)
                    }.getOrDefault(emptyList())
                }
                out
            } else {
                emptyMap()
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressUiState())

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { ProgressViewModel(context.applicationContext) }
        }
    }
}
