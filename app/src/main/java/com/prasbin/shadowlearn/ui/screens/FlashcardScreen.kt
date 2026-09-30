package com.prasbin.shadowlearn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prasbin.shadowlearn.data.cards.Rating
import com.prasbin.shadowlearn.data.db.Flashcard
import com.prasbin.shadowlearn.ui.cards.FlashcardUiKind
import com.prasbin.shadowlearn.ui.cards.FlashcardUiState
import com.prasbin.shadowlearn.ui.cards.FlashcardViewModel
import com.prasbin.shadowlearn.navigation.Routes
import com.prasbin.shadowlearn.ui.components.GroupSwitcher

/**
 * Phase 8 Flashcards — spaced review over verbatim corpus cards.
 *
 * States: LOADING, NO_SEMESTER, NO_DECKS, IDLE, REVIEW, RESULTS, ERROR.
 * All card content is verbatim from the source material (never invented).
 * Review scheduling uses SM-2-lite with dueAt as the single canonical timestamp.
 */
@Composable
fun FlashcardsScreen(onNavigate: (String) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: FlashcardViewModel = viewModel(factory = FlashcardViewModel.factory(context))
    val s by vm.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "FLASHCARDS",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Verbatim corpus cards with SM-2-lite spaced review",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            GroupSwitcher(
                options = listOf("QUIZ", "CARDS"),
                selectedIndex = 1,
                onSelect = { if (it == 0) onNavigate(Routes.QUIZ) }
            )
        }

        when (s.kind) {
            FlashcardUiKind.LOADING -> item { LoadingState() }
            FlashcardUiKind.NO_SEMESTER -> item {
                EmptyState("No semester configured. Select a year and semester in Settings.")
                TextButton(
                    onClick = { onNavigate(Routes.SETTINGS) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("OPEN SETTINGS") }
            }
            FlashcardUiKind.NO_DECKS -> item {
                EmptyState("No flashcard deck found for this semester. Build one from your academic materials, quiz mistakes, and READY listener segments.")
            }
            FlashcardUiKind.ERROR -> item { EmptyState("Cards error: ${s.error ?: "unknown"}") }
            FlashcardUiKind.IDLE -> item {
                DeckView(s, vm::startReview, vm::resumeReview)
            }
            FlashcardUiKind.REVIEW -> item { ReviewView(s, vm::reveal, vm::gradeCurrent, vm::toggleSuspend) }
            FlashcardUiKind.RESULTS -> item { ResultsView(s, vm::newReview, { vm.loadDeck(s.semesterId ?: 0L) }) }
        }
    }
}

@Composable
private fun LoadingState() {
    Column {
        Text("Building deck…", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DeckView(s: FlashcardUiState, onStartReview: () -> Unit, onResumeReview: () -> Unit) {
    SectionCard("Deck: ${s.deckTitle}") {
        StatRow("Semester", s.semesterName ?: "—")
        StatRow("Total cards", "${s.totalCards}")
        StatRow("Due now", "${s.dueCount}")
        if (s.lectureCardCount > 0) {
            StatRow("Lecture cards", "${s.lectureCardCount}")
        }
        if (s.suspendedCount > 0) {
            StatRow("Suspended", "${s.suspendedCount}")
        }
        if (s.hasInProgress) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onResumeReview,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text("Resume Review")
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onStartReview,
            modifier = Modifier.fillMaxWidth(),
            enabled = s.dueCount > 0
        ) {
            Text("Start Review")
        }
    }
}

@Composable
private fun ReviewView(
    s: FlashcardUiState,
    onReveal: () -> Unit,
    onGrade: (Rating) -> Unit,
    onToggleSuspend: () -> Unit
) {
    val card = s.queue.getOrNull(s.currentIndex)
    val progress = if (s.queue.isEmpty()) 0 else (s.currentIndex + 1)

    // Progress bar
    LinearProgressIndicator(
        progress = { if (s.queue.isEmpty()) 0f else progress.toFloat() / s.queue.size },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))

    Text(
        "Card $progress of ${s.queue.size}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    // Card
    card?.let { f ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                if (!s.revealed) {
                    Text(
                        f.front,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onReveal, modifier = Modifier.fillMaxWidth()) {
                        Text("Reveal")
                    }
                } else {
                    Text(
                        "Answer",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        f.back,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        f.sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.height(12.dp))
                    RatingButtons(onGrade)
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // Suspend toggle
    if (card != null) {
        Button(
            onClick = onToggleSuspend,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (card.suspended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(if (card.suspended) "Resume Card" else "Suspend Card")
        }
    }
}

@Composable
private fun RatingButtons(onGrade: (Rating) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RatingButton(Rating.AGAIN, "AGAIN", onGrade)
        RatingButton(Rating.HARD, "HARD", onGrade)
        RatingButton(Rating.GOOD, "GOOD", onGrade)
        RatingButton(Rating.EASY, "EASY", onGrade)
    }
}

@Composable
private fun RowScope.RatingButton(rating: Rating, label: String, onGrade: (Rating) -> Unit) {
    Button(
        onClick = { onGrade(rating) },
        modifier = Modifier.weight(1f),
        colors = ButtonDefaults.buttonColors(
            containerColor = when (rating) {
                Rating.AGAIN -> MaterialTheme.colorScheme.error
                Rating.HARD -> MaterialTheme.colorScheme.tertiary
                Rating.GOOD -> MaterialTheme.colorScheme.primary
                Rating.EASY -> MaterialTheme.colorScheme.tertiaryContainer
            }
        )
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ResultsView(s: FlashcardUiState, onNewReview: () -> Unit, onBackToDeck: () -> Unit) {
    SectionCard("Review Complete") {
        StatRow("Reviewed", "${s.reviewedCount}")
        StatRow("Retained", "${s.retainedCount}")
        if (s.reviewedCount > 0) {
            Text(
                "Retention rate: ${"%.0f".format(100f * s.retainedCount / s.reviewedCount)}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onNewReview, modifier = Modifier.fillMaxWidth()) {
            Text("New Review")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onBackToDeck, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text("Back to Deck")
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyLarge)
}


