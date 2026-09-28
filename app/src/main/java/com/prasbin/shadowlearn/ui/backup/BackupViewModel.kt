package com.prasbin.shadowlearn.ui.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prasbin.shadowlearn.data.AppContainer
import com.prasbin.shadowlearn.data.backup.BackupRepository
import com.prasbin.shadowlearn.data.backup.BackupState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BackupUiState(
    val currentSemesterId: Long? = null,
    val backup: BackupState = BackupState.Idle
)

/**
 * Settings backup state: semester context (shared with Settings via
 * DataStore) + export/import orchestration. SAF launchers live in the
 * screen; this model only moves stream bytes through [BackupRepository].
 */
class BackupViewModel(context: Context) : ViewModel() {

    private val app = context.applicationContext
    private val settings = AppContainer.settings(app)
    private val repo: BackupRepository = AppContainer.backup(app)

    val state = combine(
        settings.currentYearId,
        settings.currentSemesterId,
        repo.state
    ) { yearId, semesterId, backup ->
        BackupUiState(currentSemesterId = semesterId, backup = backup)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    fun exportTo(destination: Uri, semesterId: Long, archiveName: String) {
        viewModelScope.launch {
            val out = app.contentResolver.openOutputStream(destination)
            if (out == null) {
                return@launch
            }
            out.use { repo.exportSemester(semesterId, archiveName, it) }
        }
    }

    fun importFrom(source: Uri, label: String) {
        viewModelScope.launch {
            val ins = app.contentResolver.openInputStream(source) ?: return@launch
            ins.use { repo.importArchive(it, label) }
        }
    }

    fun reset() = repo.reset()

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { BackupViewModel(context.applicationContext) }
        }
    }
}
