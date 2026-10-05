package com.prasbin.shadowlearn.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prasbin.shadowlearn.data.intelligence.RelatedMaterial
import com.prasbin.shadowlearn.data.intelligence.RelationshipType

/**
 * Shared I6 related-material surface (Home weak rows, Status signals, and
 * ExplanationDialog all open this — never separate implementations).
 * VERIFIED academic evidence styling throughout: real filenames, fixed
 * evidence-level reason strings, verbatim excerpts, existing hierarchy
 * routing. No scores, no AI branding, no glow. Max 3 results by contract;
 * the caller only opens this dialog when the list is non-empty.
 */
@Composable
fun RelatedMaterialDialog(
    materials: List<RelatedMaterial>,
    onOpenSource: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("RELATED MATERIAL") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                materials.take(3).forEach { material ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(material.relatedFileName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            material.type.name.replace('_', ' '),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            material.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (material.excerpt.isNotBlank()) {
                            Text(
                                "“${material.excerpt}”",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        material.relatedWeekId?.let { weekId ->
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = { onOpenSource(weekId) },
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) { Text("OPEN SOURCE ›") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
