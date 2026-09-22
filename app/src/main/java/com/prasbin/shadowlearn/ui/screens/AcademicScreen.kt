package com.prasbin.shadowlearn.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.prasbin.shadowlearn.ui.components.SectionCard
import com.prasbin.shadowlearn.ui.components.StatRow
import com.prasbin.shadowlearn.util.formatBytes

private data class PickedFile(val name: String, val type: String, val size: Long)

/**
 * Phase 1 file-access proof: SAF picker only. Displays name/type/size of the
 * selected document. ZIP extraction / ingestion is Phase 2 — nothing here
 * claims to import academic content yet.
 */
@Composable
fun AcademicScreen() {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<PickedFile?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                picked = PickedFile(
                    name = if (nameIdx >= 0) c.getString(nameIdx) else uri.toString(),
                    type = context.contentResolver.getType(uri) ?: "unknown",
                    size = if (sizeIdx >= 0) c.getLong(sizeIdx) else -1
                )
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Academic Database", style = MaterialTheme.typography.headlineMedium) }
        item {
            SectionCard("Import (Phase 1 check)") {
                Text("Select any file to verify storage access. ZIP ingestion arrives in Phase 2.")
                Button(onClick = { launcher.launch(arrayOf("*/*")) }) {
                    Text("Select file")
                }
                val p = picked
                if (p != null) {
                    StatRow("Name", p.name)
                    StatRow("Type", p.type)
                    StatRow("Size", formatBytes(p.size))
                } else {
                    Text("No file selected.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            SectionCard("Hierarchy") {
                Text("Year → Semester → Module → Week → Lecture / Tutorial / Workshop → File → Topic")
                Text("Browsing and ingestion arrive in Phase 2.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
