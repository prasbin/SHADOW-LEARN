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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prasbin.shadowlearn.data.cards.Rating
import com.prasbin.shadowlearn.ui.cards.FlashcardUiKind
import com.prasbin.shadowlearn.ui.cards.FlashcardUiState
import com.prasbin.shadowlearn.ui.cards.FlashcardViewModel
import com.prasbin.shadowlearn.navigation.Routes
import com.prasbin.shadowlearn.ui.components.GroupSwitcher
import com.prasbin.shadowlearn.ui.components.ScopeStrip
import com.prasbin.shadowlearn.ui.components.SectionCard
import com.prasbin.shadowlearn.ui.components.StatRow
import com.prasbin.shadowlearn.ui.theme.WarningAmber

/**
 * Academic review — "clear my due cards". SYSTEM command module over the
 * existing SM-2-lite engine: scope strip, prominent due count, one dominant
 * Start/Resume action, honest zero-due state with real next actions, full
 * provenance on every card, and semantic 48dp rating controls.
 *
 * States: LOADING, NO_SEMESTER, NO_DECKS, IDLE (due / zero-due), REVIEW,
 * RESULTS, ERROR. All content is verbatim source material (never invented);
 * scheduling, XP, and persistence are untouched engine behavior.
 */
@Composable
fun FlashcardsScreen(onNavigate: (String) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: FlashcardViewModel = viewModel(factory = FlashcardViewModel.factory(context))
    val s by vm.state.collectAsStateWithLifecycle()

    // Cross-link freshness: quiz mistakes and READY transcripts merged into
    // the deck elsewhere appear on return. Guarded inside refreshDeck to
    // IDLE/NO_DECKS, so REVIEW and RESULTS are never reset by a revisit.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refreshDeck()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "ACADEMIC REVIEW",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text("Cards", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Clear your due — verbatim review from your material",
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

        item { ScopeStrip(s.yearName, s.semesterName) }

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
                EmptyState("No cards exist yet. Cards are built from your material, quiz mistakes, and READY transcripts.")
                Button(
                    onClick = { onNavigate(Routes.hierarchyRoot()) },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("OPEN ACADEMIC MATERIAL") }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { onNavigate(Routes.QUIZ) },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("PRACTICE IN QUIZ") }
            }
            FlashcardUiKind.ERROR -> item { EmptyState("Cards error: ${s.error ?: "unknown"}") }
            FlashcardUiKind.IDLE -> item {
                if (s.dueCount > 0) {
                    DueReview(s, vm::startReview, vm::resumeReview)
                } else {
                    ZeroDue(s, onNavigate)
                }
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

/** Due state: prominent count, one dominant action, deck facts secondary. */
@Composable
private fun DueReview(s: FlashcardUiState, onStartReview: () -> Unit, onResumeReview: () -> Unit) {
    SectionCard("Due now") {
        Text(
            "${s.dueCount}",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            if (s.dueCount == 1) "CARD DUE" else "CARDS DUE",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        if (s.hasInProgress) {
            Button(
                onClick = onResumeReview,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("RESUME REVIEW") }
        } else {
            Button(
                onClick = onStartReview,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("START REVIEW") }
        }
    }
    Spacer(Modifier.height(4.dp))
    SectionCard("Deck: ${s.deckTitle}") {
        StatRow("Total cards", "${s.totalCards}")
        if (s.lectureCardCount > 0) {
            StatRow("Lecture cards", "${s.lectureCardCount}")
        }
        if (s.suspendedCount > 0) {
            StatRow("Suspended", "${s.suspendedCount}")
        }
    }
}

/**
 * Zero-due state: never a dead button. The queue is clear; new cards
 * arrive automatically when material, quiz mistakes, or READY transcripts
 * appear. Quiz practice is the real card-producing action available now.
 */
@Composable
private fun ZeroDue(s: FlashcardUiState, onNavigate: (String) -> Unit) {
    SectionCard("Review queue clear") {
        Text("NO CARDS DUE", style = MaterialTheme.typography.titleMedium)
        Text(
            "Your current review queue is clear.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        StatRow("Cards in deck", "${s.totalCards}")
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onNavigate(Routes.QUIZ) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("PRACTICE IN QUIZ") }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = { onNavigate(Routes.hierarchyRoot()) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("OPEN ACADEMIC MATERIAL") }
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

    LinearProgressIndicator(
        progress = { if (s.queue.isEmpty()) 0f else progress.toFloat() / s.queue.size },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))

    Text(
        "CARD $progress OF ${s.queue.size}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))

    card?.let { f ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                if (!s.revealed) {
                    Text(
                        "QUESTION",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        f.front,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onReveal,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text("REVEAL ANSWER")
                    }
                } else {
                    Text(
                        "ANSWER",
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

    Spacer(Modifier.height(4.dp))

    if (card != null) {
        TextButton(
            onClick = onToggleSuspend,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (card.suspended) "UNSUSPEND THIS CARD" else "SUSPEND THIS CARD",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

/**
 * Semantic rating hierarchy: AGAIN warns (amber), HARD stays neutral,
 * GOOD is primary, EASY is teal. All 48dp, readable labels, no glow.
 */
@Composable
private fun RowScope.RatingButton(rating: Rating, label: String, onGrade: (Rating) -> Unit) {
    val container = when (rating) {
        Rating.AGAIN -> WarningAmber
        Rating.HARD -> MaterialTheme.colorScheme.surfaceVariant
        Rating.GOOD -> MaterialTheme.colorScheme.primary
        Rating.EASY -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = when (rating) {
        Rating.AGAIN -> MaterialTheme.colorScheme.onPrimary
        Rating.HARD -> MaterialTheme.colorScheme.onSurface
        Rating.GOOD -> MaterialTheme.colorScheme.onPrimary
        Rating.EASY -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Button(
        onClick = { onGrade(rating) },
        modifier = Modifier.weight(1f).height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
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
        Button(onClick = onNewReview, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("NEW REVIEW")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBackToDeck, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("BACK TO DECK")
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

