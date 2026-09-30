package com.prasbin.shadowlearn.ui.hierarchy

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prasbin.shadowlearn.data.AppContainer
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.Week
import kotlinx.coroutines.flow.Flow

/**
 * Hierarchy browser state: YEAR → SEMESTER → MODULE → WEEK → FILE reads
 * flow straight from the existing academic tables (no new model, no
 * aggregation). Breadcrumb titles resolve through the same DAO lookups;
 * a missing row resolves to null and the screen reports honestly.
 */
class HierarchyViewModel(context: Context) : ViewModel() {

    private val app = context.applicationContext
    private val dao = AppContainer.dao(app)
    private val settings = AppContainer.settings(app)

    val currentYearId: Flow<Long?> = settings.currentYearId
    val currentSemesterId: Flow<Long?> = settings.currentSemesterId

    fun years(): Flow<List<AcademicYear>> = dao.observeYears()

    fun semestersOf(yearId: Long): Flow<List<Semester>> =
        dao.observeSemesters(yearId)

    fun modulesOf(semesterId: Long): Flow<List<Module>> =
        dao.observeModules(semesterId)

    fun weeksOf(moduleId: Long): Flow<List<Week>> = dao.observeWeeks(moduleId)

    fun filesOf(weekId: Long): Flow<List<AcademicFile>> = dao.observeFiles(weekId)

    suspend fun yearName(yearId: Long): String? = dao.year(yearId)?.name

    /** "Year 1 · Semester 1", null when either row is gone. */
    suspend fun semesterTitle(semesterId: Long): String? {
        val semester = dao.semester(semesterId) ?: return null
        val year = dao.year(semester.yearId)?.name ?: return null
        return "$year · ${semester.name}"
    }

    /** "Semester 1 · Module X", null when the chain breaks. */
    suspend fun moduleTitle(moduleId: Long): String? {
        val module = dao.module(moduleId) ?: return null
        val semester = dao.semester(module.semesterId) ?: return null
        return "${semester.name} · ${module.name}"
    }

    /** "Module X · Week 1", null when the chain breaks. */
    suspend fun weekTitle(weekId: Long): String? {
        val week = dao.week(weekId) ?: return null
        val module = dao.module(week.moduleId) ?: return null
        val label = "Week ${week.weekNumber}" + (week.title?.let { " — $it" } ?: "")
        return "${module.name} · $label"
    }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { HierarchyViewModel(context.applicationContext) }
        }
    }
}
