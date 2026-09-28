package com.prasbin.shadowlearn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prasbin.shadowlearn.ui.components.SectionCard
import com.prasbin.shadowlearn.ui.components.StatRow
import com.prasbin.shadowlearn.ui.dashboard.DashboardViewModel

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val vm: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(context))
    val s by vm.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("SHADOW LEARN", style = MaterialTheme.typography.headlineLarge)
            Text("Level ${s.level} · ${s.xp} XP", style = MaterialTheme.typography.titleMedium)
        }
        item {
            SectionCard("Today's Mission") { Text(s.mission) }
        }
        item {
            SectionCard("Progression") {
                StatRow("Level", "${s.level}")
                StatRow("Total XP", "${s.xp}")
                StatRow("XP to next level", "${s.xpIntoLevel} / ${s.xpForLevel}")
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { s.levelProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                StatRow("Streak", if (s.streak == 1) "1 day" else "${s.streak} days")
            }
        }
        item {
            SectionCard("Academic Progress") {
                StatRow("Progress", "${s.progressPct}%")
                if (s.progressBasis.isNotEmpty()) {
                    Text(
                        "Based on ${s.progressBasis}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatRow("Current Year", s.currentYear)
                StatRow("Current Semester", s.currentSemester)
                StatRow("Modules", s.moduleCount.toString())
            }
        }
        item {
            SectionCard("Training") {
                Text(
                    "XP is earned from real quiz answers and flashcard reviews.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
