package com.prasbin.shadowlearn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prasbin.shadowlearn.ui.components.SectionCard

@Composable
private fun ComingSoon(title: String, phase: String, body: String) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text(title, style = MaterialTheme.typography.headlineMedium) }
        item {
            SectionCard("Status: foundation only") {
                Text(body)
                Text("Full functionality: $phase.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable fun QuizScreen_forRemoval() = Unit // placeholder removed; real QuizScreen lives in QuizScreen.kt

// ListenerScreen removed: real ListenerScreen lives in ListenerScreen.kt (Phase 7).

@Composable fun ProgressScreen() = ComingSoon(
    "Progress", "Phase 6",
    "Subject → Module → Week → Topic → Practice → Test → Mastery, with XP levels (500 XP = 1 level)."
)
