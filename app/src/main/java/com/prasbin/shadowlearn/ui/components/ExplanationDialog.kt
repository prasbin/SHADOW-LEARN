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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prasbin.shadowlearn.data.intelligence.ExplanationStatus
import com.prasbin.shadowlearn.data.intelligence.GroundedExplanation

/**
 * Shared I4 explanation surface (Home + Status use this — never separate
 * explanation paths). VERIFIED source text uses the teal treatment;
 * GENERATED explanation text is explicitly labeled and purple-tinted,
 * never styled as source material. No chat UI, no glow, no AI branding.
 */
@Composable
fun ExplanationDialog(
    explanation: GroundedExplanation,
    onOpenSource: (Long) -> Unit,
    onOpenMaterial: () -> Unit,
    onOpenSearch: () -> Unit,
    onDismiss: () -> Unit,
    practiceFileId: Long? = null,
    onPractice: (Long) -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GROUNDED EXPLANATION") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (explanation.status) {
                    ExplanationStatus.EXPLAINED -> ExplainedBody(
                        explanation, onOpenSource, practiceFileId, onPractice
                    )
                    ExplanationStatus.INSUFFICIENT_EVIDENCE -> {
                        Text(
                            "INSUFFICIENT ACADEMIC EVIDENCE",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "SHADOW LEARN could not find enough indexed material " +
                                "in your current semester to explain this safely.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedButton(
                            onClick = onOpenMaterial,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("OPEN ACADEMIC MATERIAL") }
                        OutlinedButton(
                            onClick = onOpenSearch,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("SEARCH MATERIAL") }
                    }
                    ExplanationStatus.NO_SOURCE ->
                        FailureLine("No academic source is connected to this evidence.")
                    ExplanationStatus.SOURCE_NOT_INDEXED ->
                        FailureLine("The source file exists but has no indexed text yet.")
                    ExplanationStatus.NO_MATCH ->
                        FailureLine("No matching indexed material in the current semester.")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ExplainedBody(
    explanation: GroundedExplanation,
    onOpenSource: (Long) -> Unit,
    practiceFileId: Long?,
    onPractice: (Long) -> Unit
) {
    Text(
        "GENERATED FROM YOUR ACADEMIC MATERIAL",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary
    )
    Text(explanation.explanation, style = MaterialTheme.typography.bodyLarge)
    Text(
        explanation.groundingBasis,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    practiceFileId?.let { fileId ->
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { onPractice(fileId) },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("PRACTICE THIS MATERIAL ›") }
    }
    Text(
        "BASED ON YOUR MATERIAL",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    explanation.sources.forEach { source ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Text(source.fileName, style = MaterialTheme.typography.titleSmall)
            val scope = listOfNotNull(source.weekLabel, source.moduleName)
                .joinToString(" · ")
            if (scope.isNotEmpty()) {
                Text(
                    scope,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "“${source.excerpt}”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            source.weekId?.let { weekId ->
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { onOpenSource(weekId) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("OPEN SOURCE ›") }
            }
        }
    }
}

@Composable
private fun FailureLine(message: String) {
    Text(message, style = MaterialTheme.typography.bodyMedium)
}
