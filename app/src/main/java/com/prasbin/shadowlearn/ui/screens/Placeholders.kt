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

@Composable fun SearchScreen() = ComingSoon(
    "AI Search", "Phase 5",
    "Ask which week/module covers a topic. Retrieval over your uploaded material — never invented locations."
)

@Composable fun QuizScreen() = ComingSoon(
    "Daily Quiz", "Phase 6",
    "MCQs, short answers, coding and scenario questions from your content, with XP, streaks and weak-area tracking."
)

@Composable fun ListenerScreen() = ComingSoon(
    "Listener Mode", "Phase 7",
    "Visible lecture recording with consent notice, transcription, summaries, flashcards and action items."
)

@Composable fun FlashcardsScreen() = ComingSoon(
    "Flashcards", "Phase 8",
    "Auto-generated cards from materials, lectures, quiz mistakes and weak topics — with spaced review."
)

@Composable fun ProgressScreen() = ComingSoon(
    "Progress", "Phase 6",
    "Subject → Module → Week → Topic → Practice → Test → Mastery, with XP levels (500 XP = 1 level)."
)
