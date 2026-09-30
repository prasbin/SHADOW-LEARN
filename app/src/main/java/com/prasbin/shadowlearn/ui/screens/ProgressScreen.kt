package com.prasbin.shadowlearn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prasbin.shadowlearn.navigation.Routes
import com.prasbin.shadowlearn.ui.components.SectionCard
import com.prasbin.shadowlearn.ui.components.GroupSwitcher
import com.prasbin.shadowlearn.ui.components.StatRow
import com.prasbin.shadowlearn.ui.progress.ProgressViewModel

/**
 * ACADEMIC STATUS — the deep view of the state SYSTEM HOME summarizes.
 * Percent, basis, milestones, level, XP, and streak all come from the
 * same repositories Home reads, so the screens cannot disagree. Milestone
 * rows report presence only (REACHED/PENDING) — never invented counts.
 */
@Composable
fun ProgressScreen(onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val vm: ProgressViewModel = viewModel(factory = ProgressViewModel.factory(context))
    val s by vm.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text(
                    "ACADEMIC STATUS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (s.scopeValid) "${s.currentYear} · ${s.currentSemester}"
                    else "Academic scope not configured",
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        }
        item {
            GroupSwitcher(
                options = listOf("STATUS", "SETTINGS"),
                selectedIndex = 0,
                onSelect = { if (it == 1) onNavigate(Routes.SETTINGS) }
            )
        }
        item {
            SectionCard("Academic Progress") {
                if (s.basis.isEmpty()) {
                    Text("NO ACADEMIC PROGRESS", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Import academic material to begin.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text("${s.percent}%", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (s.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Based on ${s.basis}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            SectionCard("Learning Evidence") {
                MilestoneRow("IMPORT", "Academic material imported", s.milestones.hasImport)
                MilestoneRow("EXTRACTION", "Document content extracted", s.milestones.hasExtraction)
                MilestoneRow("QUIZ", "Quiz session completed", s.milestones.hasQuiz)
                MilestoneRow("REVIEW", "Flashcard review recorded", s.milestones.hasReview)
            }
        }
        item {
            SectionCard("Progression") {
                StatRow("Level", s.level.toString())
                StatRow("XP", s.xp.toString())
                StatRow(
                    "Streak",
                    if (s.streak == 1) "1 day" else "${s.streak} days"
                )
            }
        }
        item {
            SectionCard("Current Scope") {
                if (!s.scopeValid) {
                    Text(
                        if (s.currentYear == "Not configured") "Not configured"
                        else "Selection unavailable",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (s.currentYear == "Not configured") "Select a year and semester to attach activity."
                        else "Reselect your academic scope.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { onNavigate(Routes.SETTINGS) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Configure")
                    }
                } else {
                    StatRow("Year", s.currentYear)
                    StatRow("Semester", s.currentSemester)
                }
            }
        }
        item {
            Button(
                onClick = { onNavigate(Routes.hierarchyRoot()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("OPEN ACADEMIC MATERIAL")
            }
        }
    }
}

@Composable
private fun MilestoneRow(label: String, detail: String, reached: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            if (reached) "● REACHED" else "○ PENDING",
            style = MaterialTheme.typography.labelSmall,
            color = if (reached) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
