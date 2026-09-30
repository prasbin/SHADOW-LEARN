package com.prasbin.shadowlearn.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prasbin.shadowlearn.data.ingest.ExtractionState
import com.prasbin.shadowlearn.data.ingest.IngestState
import com.prasbin.shadowlearn.ui.components.SectionCard
import com.prasbin.shadowlearn.ui.components.StatRow
import com.prasbin.shadowlearn.ui.ingest.IngestViewModel
import com.prasbin.shadowlearn.navigation.Routes
import com.prasbin.shadowlearn.util.formatBytes

private data class PickedZip(val uri: Uri, val name: String, val size: Long)

/**
 * Phase 2 Academic tab: Year/Semester context, SAF ZIP picker, import with
 * honest progress, result/error summary, Phase 4 extraction/indexing status,
 * and the ingested hierarchy browser. No search or sync logic lives here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcademicScreen(onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val vm: IngestViewModel = viewModel(factory = IngestViewModel.factory(context))
    val s by vm.state.collectAsStateWithLifecycle()
    val semesters by vm.semestersFlow(s.currentYearId)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var picked by remember { mutableStateOf<PickedZip?>(null) }
    var yearExpanded by remember { mutableStateOf(false) }
    var semesterExpanded by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        var name = uri.toString()
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni)
                if (si >= 0) size = c.getLong(si)
            }
        }
        picked = PickedZip(uri, name, size)
        vm.resetImport()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Academic Database", style = MaterialTheme.typography.headlineMedium) }

        item {
            SectionCard("Import target") {
                YearSemesterSelectors(
                    years = s.years,
                    semesters = semesters,
                    currentYearId = s.currentYearId,
                    currentSemesterId = s.currentSemesterId,
                    yearExpanded = yearExpanded,
                    onYearExpanded = { yearExpanded = it },
                    semesterExpanded = semesterExpanded,
                    onSemesterExpanded = { semesterExpanded = it },
                    onSelectYear = { vm.selectYear(it); yearExpanded = false },
                    onSelectSemester = { vm.selectSemester(it); semesterExpanded = false }
                )
            }
        }

        item {
            SectionCard("Semester ZIP") {
                Text("Pick the semester archive (may contain nested module ZIPs).")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed")) }) {
                        Text("Select ZIP")
                    }
                    val z = picked
                    val semId = s.currentSemesterId
                    Button(
                        onClick = { if (z != null && semId != null) vm.importZip(z.uri, semId, z.name) },
                        enabled = z != null && semId != null && s.ingest !is IngestState.Importing
                    ) { Text("Import") }
                    if (s.ingest is IngestState.Importing) {
                        OutlinedButton(onClick = { vm.cancelImport() }) { Text("Cancel") }
                    }
                }
                val z = picked
                if (z != null) {
                    StatRow("Archive", z.name)
                    StatRow("Size", formatBytes(z.size))
                }
                if (s.currentSemesterId == null) {
                    Text("Select a year and semester first (System → Settings can create them).")
                    TextButton(
                        onClick = { onNavigate(Routes.SETTINGS) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("OPEN SETTINGS") }
                }
                ImportStatus(s.ingest)
                ExtractionStatus(s.extraction)
            }
        }

        val semId = s.currentSemesterId
        if (semId != null) {
            item {
                SectionCard("Imported hierarchy") {
                    HierarchyBrowser(vm, semId)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YearSemesterSelectors(
    years: List<com.prasbin.shadowlearn.data.db.AcademicYear>,
    semesters: List<com.prasbin.shadowlearn.data.db.Semester>,
    currentYearId: Long?,
    currentSemesterId: Long?,
    yearExpanded: Boolean,
    onYearExpanded: (Boolean) -> Unit,
    semesterExpanded: Boolean,
    onSemesterExpanded: (Boolean) -> Unit,
    onSelectYear: (Long) -> Unit,
    onSelectSemester: (Long) -> Unit
) {
    ExposedDropdownMenuBox(expanded = yearExpanded, onExpandedChange = onYearExpanded) {
        TextField(
            value = years.firstOrNull { it.id == currentYearId }?.name ?: "Select year",
            onValueChange = {},
            readOnly = true,
            label = { Text("Year") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(yearExpanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = yearExpanded, onDismissRequest = { onYearExpanded(false) }) {
            years.forEach { y ->
                DropdownMenuItem(text = { Text(y.name) }, onClick = { onSelectYear(y.id) })
            }
        }
    }
    ExposedDropdownMenuBox(expanded = semesterExpanded, onExpandedChange = onSemesterExpanded) {
        TextField(
            value = semesters.firstOrNull { it.id == currentSemesterId }?.name ?: "Select semester",
            onValueChange = {},
            readOnly = true,
            label = { Text("Semester") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(semesterExpanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = semesterExpanded, onDismissRequest = { onSemesterExpanded(false) }) {
            semesters.forEach { sem ->
                DropdownMenuItem(text = { Text(sem.name) }, onClick = { onSelectSemester(sem.id) })
            }
        }
    }
}

@Composable
private fun ImportStatus(state: IngestState) {
    when (state) {
        is IngestState.Idle -> Unit
        is IngestState.Importing -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            StatRow("Processing", state.currentEntry)
            StatRow("Files", "${state.processed} / ${state.total}")
            StatRow("Stored", formatBytes(state.bytesWritten))
        }
        is IngestState.Done -> {
            val r = state.summary
            StatRow("Structure", "${r.modules} module(s), ${r.weeks} week(s)")
            StatRow("New", r.created.toString())
            StatRow("Changed", r.changed.toString())
            StatRow("Duplicate", r.duplicate.toString())
            StatRow("Unchanged", r.unchanged.toString())
            StatRow("Failed", r.failed.toString())
            if (r.skipped > 0) StatRow("Skipped", r.skipped.toString())
            ErrorList(r.errors)
        }
        is IngestState.Failed -> {
            Text("Import failed: ${state.reason}", color = MaterialTheme.colorScheme.error)
            Text("Processed before failure: ${state.processed}")
        }
    }
}

@Composable
private fun ErrorList(errors: List<String>) {
    if (errors.isEmpty()) return
    Text("Notes (${errors.size}):", style = MaterialTheme.typography.titleMedium)
    errors.take(20).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    if (errors.size > 20) Text("…and ${errors.size - 20} more.")
}

/** Phase 4 extraction/indexing status shown under the import card. */
@Composable
private fun ExtractionStatus(state: ExtractionState) {
    when (state) {
        is ExtractionState.Idle -> Unit
        is ExtractionState.Progress -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Text("Indexing…", style = MaterialTheme.typography.titleSmall)
            StatRow("Processing", state.currentEntry)
            StatRow("Files", "${state.done} / ${state.total}")
        }
        is ExtractionState.Done -> {
            val r = state.summary
            Text("Extraction", style = MaterialTheme.typography.titleSmall)
            StatRow("Extracted", r.extracted.toString())
            StatRow("Reused (duplicates)", r.reused.toString())
            StatRow("Skipped (unchanged)", r.skipped.toString())
            StatRow("Failed", r.failed.toString())
            StatRow("Chunks indexed", r.indexedChunks.toString())
            if (r.elapsedMs > 0) StatRow("Elapsed", "${r.elapsedMs} ms")
            ErrorList(r.errors)
        }
    }
}

/** Modules → weeks → files for the selected semester, read live from Room. */
@Composable
private fun HierarchyBrowser(vm: IngestViewModel, semesterId: Long) {
    val modules by vm.modulesOf(semesterId).collectAsStateWithLifecycle(initialValue = emptyList())
    if (modules.isEmpty()) {
        Text("Nothing imported into this semester yet.")
        return
    }
    modules.forEach { mod ->
        Text(mod.name, style = MaterialTheme.typography.titleMedium)
        val weeks by vm.weeksOf(mod.id).collectAsStateWithLifecycle(initialValue = emptyList())
        weeks.forEach { week ->
            Text(
                "Week ${week.weekNumber}" + (week.title?.let { " — $it" } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp)
            )
            val files by vm.filesOf(week.id).collectAsStateWithLifecycle(initialValue = emptyList())
            files.forEach { f ->
                Text(
                    "[${f.classType}] ${f.fileName} (${formatBytes(f.fileSize)})",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }
    }
}
