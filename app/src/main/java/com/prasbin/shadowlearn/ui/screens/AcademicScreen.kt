package com.prasbin.shadowlearn.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prasbin.shadowlearn.data.ingest.ExtractionState
import com.prasbin.shadowlearn.data.ingest.IngestState
import com.prasbin.shadowlearn.ui.components.SectionCard
import com.prasbin.shadowlearn.ui.components.ScopeStrip
import com.prasbin.shadowlearn.ui.components.StatRow
import com.prasbin.shadowlearn.ui.ingest.DirectUploadStatus
import com.prasbin.shadowlearn.ui.ingest.IngestViewModel
import com.prasbin.shadowlearn.ui.ingest.PickedSource
import com.prasbin.shadowlearn.navigation.Routes
import com.prasbin.shadowlearn.util.formatBytes

private data class PickedZip(val uri: Uri, val name: String, val size: Long)

/** Direct-upload MIME filters (extension validation remains authoritative). */
private val DIRECT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/msword",
    "application/vnd.ms-powerpoint",
    "text/plain",
    "text/markdown",
    "text/x-markdown"
)

/** SAF display name for a picked source (falls back to the URI segment). */
private fun displayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { c -> if (c.moveToFirst() && c.columnCount > 0) c.getString(0) else null }
    }.getOrNull()
    return fromProvider?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "source"
}

private fun DirectUploadStatus.label(): String = when (this) {
    DirectUploadStatus.QUEUED -> "QUEUED"
    DirectUploadStatus.IMPORTING -> "IMPORTING…"
    DirectUploadStatus.PROCESSING -> "PROCESSING…"
    DirectUploadStatus.READY -> "READY"
    DirectUploadStatus.DUPLICATE -> "DUPLICATE"
    DirectUploadStatus.FAILED -> "FAILED"
}

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
    var directModule by remember { mutableStateOf("Imported") }
    var directWeekText by remember { mutableStateOf("1") }
    var pickedSources by remember { mutableStateOf<List<PickedSource>>(emptyList()) }
    val direct by vm.direct.collectAsStateWithLifecycle()
    val directBusy = direct.any {
        it.status == DirectUploadStatus.QUEUED ||
            it.status == DirectUploadStatus.IMPORTING ||
            it.status == DirectUploadStatus.PROCESSING
    }

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

    val directPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val sources = uris.map { uri ->
            // Persist read access for retry within this session.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            PickedSource(uri, displayName(context, uri))
        }
        pickedSources = sources
        vm.resetDirect()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "ACADEMIC INTAKE",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text("Material", style = MaterialTheme.typography.headlineMedium)
            Text(
                "IMPORT AND INDEX YOUR VERIFIED ACADEMIC MATERIAL",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            val yearName = s.years.firstOrNull { it.id == s.currentYearId }?.name
            val semesterName = semesters.firstOrNull { it.id == s.currentSemesterId }?.name
            ScopeStrip(yearName, semesterName)
        }

        if (s.currentSemesterId == null) {
            item {
                SectionCard("Academic scope required") {
                    Text("Select a year and semester to import into.")
                    TextButton(
                        onClick = { onNavigate(Routes.SETTINGS) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("OPEN SETTINGS") }
                }
            }
        }

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
            SectionCard("Add sources (direct upload)") {
                Text(
                    "Pick PDF, DOCX, PPTX, TXT or Markdown files directly — no ZIP needed. " +
                        "Each file is copied into app-private storage, hashed, deduplicated, " +
                        "extracted and indexed.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = directModule,
                    onValueChange = { directModule = it },
                    label = { Text("Module") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = directWeekText,
                    onValueChange = { text ->
                        if (text.length <= 3 && text.all { it.isDigit() }) directWeekText = text
                    },
                    label = { Text("Week") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                val directWeek = directWeekText.toIntOrNull()
                val semId = s.currentSemesterId
                OutlinedButton(
                    onClick = { directPicker.launch(DIRECT_MIME_TYPES) },
                    enabled = semId != null && directWeek != null && directWeek >= 1 && !directBusy,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("SELECT FILES") }
                if (semId == null) {
                    Text(
                        "Select a year and semester first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (directWeek == null || directWeek < 1) {
                    Text(
                        "Enter a week number (1 or higher).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val chosen = pickedSources
                if (chosen.isNotEmpty() && direct.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${chosen.size} file(s) selected.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (chosen.isNotEmpty() && semId != null && directWeek != null) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            vm.importFiles(
                                files = chosen,
                                semesterId = semId,
                                moduleName = directModule,
                                weekNumber = directWeek
                            )
                        },
                        enabled = !directBusy,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text(if (direct.any { it.status == DirectUploadStatus.FAILED }) "RETRY IMPORT" else "IMPORT SELECTED")
                    }
                }
                if (direct.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("SOURCE STATUS", style = MaterialTheme.typography.labelLarge)
                    direct.forEach { upload ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(
                                "${upload.status.label()}  ${upload.name}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            upload.reason?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (upload.status == DirectUploadStatus.FAILED)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard("Semester ZIP") {
                Text("Pick the semester archive (may contain nested module ZIPs).")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("SELECT ZIP")
                }
                val z = picked
                val semId = s.currentSemesterId
                if (z != null) {
                    Spacer(Modifier.height(4.dp))
                    StatRow("Archive", z.name)
                    StatRow("Size", formatBytes(z.size))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { if (z != null && semId != null) vm.importZip(z.uri, semId, z.name) },
                    enabled = z != null && semId != null && s.ingest !is IngestState.Importing,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("IMPORT") }
                if (s.ingest is IngestState.Importing) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { vm.cancelImport() },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text("CANCEL") }
                }
                ImportStatus(s.ingest, s.currentSemesterId, onNavigate)
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
private fun ImportStatus(
    state: IngestState,
    semesterId: Long?,
    onNavigate: (String) -> Unit
) {
    when (state) {
        is IngestState.Idle -> Unit
        is IngestState.Importing -> {
            Text("IMPORTING…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            StatRow("Processing", state.currentEntry)
            StatRow("Files", "${state.processed} / ${state.total}")
            StatRow("Stored", formatBytes(state.bytesWritten))
        }
        is IngestState.Done -> {
            val r = state.summary
            Text("IMPORT COMPLETE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            StatRow("Structure", "${r.modules} module(s), ${r.weeks} week(s)")
            StatRow("New", r.created.toString())
            StatRow("Changed", r.changed.toString())
            StatRow("Duplicate", r.duplicate.toString())
            StatRow("Unchanged", r.unchanged.toString())
            StatRow("Failed", r.failed.toString())
            if (r.skipped > 0) StatRow("Skipped", r.skipped.toString())
            ErrorList(r.errors)
            Spacer(Modifier.height(4.dp))
            // Cross-link #4: hand off into the system at the current scope.
            Button(
                onClick = {
                    onNavigate(
                        if (semesterId != null) Routes.hierarchySemester(semesterId)
                        else Routes.hierarchyRoot()
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("BROWSE MATERIAL ›") }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { onNavigate(Routes.SEARCH) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("SEARCH INDEX ›") }
        }
        is IngestState.Failed -> {
            Text("IMPORT FAILED", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
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
            Text("EXTRACTING…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Text("Indexing…", style = MaterialTheme.typography.titleSmall)
            StatRow("Processing", state.currentEntry)
            StatRow("Files", "${state.done} / ${state.total}")
        }
        is ExtractionState.Done -> {
            val r = state.summary
            Text("EXTRACTION", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
            var fileLimit by remember(week.id) { mutableStateOf(50) }
            val files by vm.filesPage(week.id, fileLimit)
                .collectAsStateWithLifecycle(initialValue = emptyList())
            files.forEach { f ->
                Text(
                    "[${f.classType}] ${f.fileName} (${formatBytes(f.fileSize)})",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            if (files.size >= fileLimit) {
                TextButton(
                    onClick = { fileLimit += 50 },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("LOAD MORE (${files.size} shown)")
                }
            }
        }
    }
}
