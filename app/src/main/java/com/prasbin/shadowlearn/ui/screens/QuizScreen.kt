package com.prasbin.shadowlearn.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prasbin.shadowlearn.data.quiz.ActiveQuestion
import com.prasbin.shadowlearn.data.quiz.ActiveQuiz
import com.prasbin.shadowlearn.data.quiz.QuestionType
import com.prasbin.shadowlearn.data.quiz.QuizResults
import com.prasbin.shadowlearn.data.quiz.QuizSource
import com.prasbin.shadowlearn.data.quiz.QuizSummary
import com.prasbin.shadowlearn.ui.quiz.QuizFeedback
import com.prasbin.shadowlearn.ui.quiz.QuizUiKind
import com.prasbin.shadowlearn.ui.quiz.QuizUiState
import com.prasbin.shadowlearn.ui.quiz.QuizViewModel
import com.prasbin.shadowlearn.navigation.Routes
import com.prasbin.shadowlearn.ui.components.GroupSwitcher
import java.util.Locale

/**
 * Phase 6 Daily Quiz — the SYSTEM-style quiz HUD over the current
 * semester's indexed chunks. Rule-based questions with verbatim options and
 * real citations, an honest score/XP/streak from completed sessions only.
 * Fully choice-based (no text input), scoped to the DataStore semester.
 */
@Composable
fun QuizScreen(onNavigate: (String) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: QuizViewModel = viewModel(factory = QuizViewModel.factory(context))
    val s by vm.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "STUDY",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "DAILY QUIZ",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Rule-based questions from your verified academic knowledge base",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item { ScopeIndicator(s) }

        item {
            GroupSwitcher(
                options = listOf("QUIZ", "CARDS"),
                selectedIndex = 0,
                onSelect = { if (it == 1) onNavigate(Routes.FLASHCARDS) }
            )
        }

        when (s.kind) {
            QuizUiKind.LOADING -> item {
                Column {
                    Text("Loading quiz…", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            QuizUiKind.NO_SEMESTER -> item {
                EmptyState("No semester configured. Select a year and semester in Settings, or import a semester ZIP in the Academic tab.")
                TextButton(
                    onClick = { onNavigate(Routes.SETTINGS) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("OPEN SETTINGS") }
            }
            QuizUiKind.NO_INDEXED -> item {
                EmptyState("No indexed academic content in this semester yet. Import a semester ZIP in the Academic tab to build quiz content.")
            }
            QuizUiKind.UNAVAILABLE -> item {
                EmptyState("This semester has content, but not enough that a quiz can be built from it yet. Add more material or import a bigger archive.")
            }
            QuizUiKind.ERROR -> item { EmptyState("Quiz error: ${s.error ?: "unknown"}") }
            QuizUiKind.IDLE -> item { IdleQuiz(s.summary, s.selectedLength, vm::selectLength, vm::start) }
            QuizUiKind.QUESTION -> {
                item { QuestionView(s.quiz!!, s.current, s.feedback, vm::answer, vm::next) }
            }
            QuizUiKind.RESULTS -> item { ResultsView(s.results!!, vm::newQuiz, { onNavigate(Routes.FLASHCARDS) }) }
        }
    }
}

@Composable
private fun ScopeIndicator(s: QuizUiState) {
    val label = when {
        s.semesterId == null -> "No semester configured"
        else -> listOfNotNull(s.yearName, s.semesterName).joinToString(" · ")
    }.ifEmpty { "Current semester" }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "SCOPE • CURRENT SEMESTER",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (s.indexedChunkCount > 0) {
                Text(
                    "${s.indexedChunkCount} indexed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun IdleQuiz(
    summary: QuizSummary?,
    selectedLength: Int,
    onLength: (Int) -> Unit,
    onStart: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (summary != null && summary.sessions > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("SESSION STATUS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Stat("SCORE", summary.lastCorrect?.let { "$it/${summary.lastTotal}" } ?: "—")
                        Stat("BEST", "${summary.bestScore}")
                        Stat("XP", "${summary.xp}")
                        Stat("STREAK", "${summary.streak} DAYS")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (summary.sessions == 1) "1 completed session" else "${summary.sessions} completed sessions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("RULES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Questions are generated from this semester's indexed material. " +
                        "Every option is a verbatim phrase from your files and every question cites its source. " +
                        "Answer by tapping — no typing required. Completed +1 XP per correct answer.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LENGTH", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            QuizViewModel.LENGTH_OPTIONS.forEach { n ->
                FilterChip(
                    selected = n == selectedLength,
                    onClick = { onLength(n) },
                    label = { Text("$n") },
                    modifier = Modifier.height(48.dp)
                )
            }
        }
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("START QUIZ")
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun QuestionView(
    quiz: ActiveQuiz,
    current: Int,
    feedback: QuizFeedback?,
    onAnswer: (String) -> Unit,
    onNext: () -> Unit
) {
    val question = quiz.questions[current]
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "QUESTION ${current + 1} OF ${quiz.total}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            Text(
                "SCORE ${quiz.correctSoFar}/${quiz.answered}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { (current + 1).toFloat() / quiz.total },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiary
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeChip(question.type)
                    Spacer(Modifier.weight(1f))
                    CitationLabel(question.source)
                }
                Spacer(Modifier.height(10.dp))
                Text(question.prompt, style = MaterialTheme.typography.bodyLarge)
            }
        }

        OptionsList(question, quiz.questions[current].userAnswer, feedback, onAnswer)

        if (feedback != null) {
            FeedbackCard(feedback)
            CitationCard(question.source)
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (current + 1 < quiz.total) "NEXT QUESTION" else "FINISH")
            }
        }
    }
}

@Composable
private fun OptionsList(
    question: ActiveQuestion,
    userAnswer: String?,
    feedback: QuizFeedback?,
    onAnswer: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        question.options.forEachIndexed { index, option ->
            OptionButton(
                letter = ('A' + index).toString(),
                option = option,
                display = if (question.type == QuestionType.TRUE_FALSE) option.uppercase(Locale.ROOT) else option,
                feedback = feedback,
                userPicked = option == userAnswer,
                onClick = { onAnswer(option) }
            )
        }
    }
}

@Composable
private fun OptionButton(
    letter: String,
    option: String,
    display: String,
    feedback: QuizFeedback?,
    userPicked: Boolean,
    onClick: () -> Unit
) {
    val isCorrectReveal = feedback != null && option == feedback.correctAnswer
    val isWrongPick = feedback != null && userPicked && option != feedback.correctAnswer
    val container = when {
        isCorrectReveal -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
        isWrongPick -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
        else -> MaterialTheme.colorScheme.surface
    }
    val border = when {
        isCorrectReveal -> MaterialTheme.colorScheme.tertiary
        isWrongPick -> MaterialTheme.colorScheme.error
        feedback != null -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.outline
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, border),
        enabled = feedback == null
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                letter,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 14.dp)
            )
            Text(
                display,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun FeedbackCard(feedback: QuizFeedback) {
    Surface(
        color = if (feedback.isCorrect) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (feedback.isCorrect) "CORRECT" else "INCORRECT",
                style = MaterialTheme.typography.labelLarge,
                color = if (feedback.isCorrect) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
            if (!feedback.isCorrect) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Correct answer: ${feedback.correctAnswer}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun CitationCard(source: QuizSource) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("SOURCE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    source.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                CitationLabel(source)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "\u201C${source.excerpt}\u201D",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun CitationLabel(source: QuizSource) {
    val n = source.pageNumber
    if (n == null) return
    Text(
        (if (source.fileType == "pptx") "SLIDE $n" else "PAGE $n"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary
    )
}

@Composable
private fun TypeChip(type: QuestionType) {
    val label = when (type) {
        QuestionType.MCQ -> "MULTIPLE CHOICE"
        QuestionType.TRUE_FALSE -> "TRUE / FALSE"
        QuestionType.FILL_BLANK -> "FILL THE BLANK"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ResultsView(results: QuizResults, onNewQuiz: () -> Unit, onPracticeMistakes: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${results.correct} / ${results.total}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "${results.percent}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (results.percent >= 60) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stat("CORRECT", "${results.correct}")
                    Stat("XP", "+${results.xp}")
                    Stat("STREAK", "${results.streak} DAYS")
                }
            }
        }
        results.questions.forEachIndexed { index, q ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(14.dp)) {
                    val ok = q.isCorrect == true
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Q${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        TypeChip(q.type)
                        Spacer(Modifier.weight(1f))
                        Text(
                            (if (ok) "CORRECT" else "INCORRECT"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(q.prompt, style = MaterialTheme.typography.bodyMedium)
                    if (!ok) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Your answer: ${q.userAnswer ?: "—"}   Correct: ${q.correctAnswer}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${q.source.fileName} · ${q.source.fileType.uppercase(Locale.ROOT)}" +
                            (q.source.pageNumber?.let { if (q.source.fileType == "pptx") " · SLIDE $it" else " · PAGE $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
        Button(onClick = onNewQuiz, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("NEW QUIZ")
        }
        // Cross-link #3: mistakes already persist as rows; opening Cards
        // rebuilds the deck through the existing mistake path (no new logic).
        val mistakes = results.total - results.correct
        Spacer(Modifier.height(4.dp))
        if (mistakes > 0) {
            OutlinedButton(onClick = onPracticeMistakes, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("PRACTICE MISTAKES IN CARDS ›")
            }
        } else {
            Text(
                "NO MISTAKES TO PRACTICE",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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