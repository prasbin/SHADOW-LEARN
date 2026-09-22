package com.prasbin.shadowlearn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
            SectionCard("Academic Progress") {
                StatRow("Progress", "${s.progressPct}%")
                StatRow("Current Year", s.currentYear)
                StatRow("Current Semester", s.currentSemester)
                StatRow("Modules", s.moduleCount.toString())
            }
        }
        item {
            SectionCard("Training") {
                StatRow("Quiz Streak", s.streak.toString())
                Text("Continue Learning — unlocks with the first import.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
