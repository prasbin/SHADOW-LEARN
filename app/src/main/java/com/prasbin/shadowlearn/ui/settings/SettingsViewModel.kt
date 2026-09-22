package com.prasbin.shadowlearn.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prasbin.shadowlearn.data.AppContainer
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.Semester
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val years: List<AcademicYear> = emptyList(),
    val semesters: List<Semester> = emptyList(),
    val currentYearId: Long? = null,
    val currentSemesterId: Long? = null,
    val notificationsEnabled: Boolean = true,
    val darkMode: Boolean = true
)

class SettingsViewModel(context: Context) : ViewModel() {

    private val app = context.applicationContext
    private val dao = AppContainer.dao(app)
    private val settings = AppContainer.settings(app)

    val state = combine(
        dao.observeYears(),
        settings.currentYearId,
        settings.currentSemesterId,
        settings.notificationsEnabled,
        settings.darkMode
    ) { years, yearId, semesterId, notif, dark ->
        SettingsUiState(
            years = years,
            currentYearId = yearId,
            currentSemesterId = semesterId,
            notificationsEnabled = notif,
            darkMode = dark
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun semestersOf(yearId: Long) = dao.observeSemesters(yearId)

    fun addYear(name: String, order: Int) = viewModelScope.launch {
        val id = dao.insertYear(AcademicYear(name = name.trim(), sortOrder = order))
        settings.setCurrentYear(id)
        settings.setCurrentSemester(null)
    }

    fun addSemester(yearId: Long, name: String, order: Int) = viewModelScope.launch {
        val id = dao.insertSemester(Semester(yearId = yearId, name = name.trim(), sortOrder = order))
        settings.setCurrentSemester(id)
    }

    fun selectYear(id: Long?) = viewModelScope.launch {
        settings.setCurrentYear(id)
        settings.setCurrentSemester(null)
    }

    fun selectSemester(id: Long?) = viewModelScope.launch { settings.setCurrentSemester(id) }

    fun setNotifications(enabled: Boolean) = viewModelScope.launch {
        settings.setNotificationsEnabled(enabled)
    }

    fun setDarkMode(enabled: Boolean) = viewModelScope.launch { settings.setDarkMode(enabled) }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { SettingsViewModel(context.applicationContext) }
        }
    }
}
