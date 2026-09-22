package com.prasbin.shadowlearn.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * Persisted app settings (DataStore). Only settings that actually work are
 * exposed; future items (export/import) are UI placeholders, not fake prefs.
 */
class SettingsRepository(private val context: Context) {

    val currentYearId: Flow<Long?> = context.settingsStore.data.map { it[Keys.YEAR_ID] }
    val currentSemesterId: Flow<Long?> = context.settingsStore.data.map { it[Keys.SEMESTER_ID] }
    val notificationsEnabled: Flow<Boolean> =
        context.settingsStore.data.map { it[Keys.NOTIFICATIONS] ?: true }
    val darkMode: Flow<Boolean> =
        context.settingsStore.data.map { it[Keys.DARK_MODE] ?: true }

    suspend fun setCurrentYear(id: Long?) = edit { if (id == null) it.remove(Keys.YEAR_ID) else it[Keys.YEAR_ID] = id }
    suspend fun setCurrentSemester(id: Long?) = edit { if (id == null) it.remove(Keys.SEMESTER_ID) else it[Keys.SEMESTER_ID] = id }
    suspend fun setNotificationsEnabled(enabled: Boolean) = edit { it[Keys.NOTIFICATIONS] = enabled }
    suspend fun setDarkMode(enabled: Boolean) = edit { it[Keys.DARK_MODE] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit(block)
    }

    private object Keys {
        val YEAR_ID = longPreferencesKey("current_year_id")
        val SEMESTER_ID = longPreferencesKey("current_semester_id")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
    }
}
