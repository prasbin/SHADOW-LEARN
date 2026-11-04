package com.prasbin.shadowlearn.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prasbin.shadowlearn.data.home.HomeTarget
import com.prasbin.shadowlearn.data.home.Recommendation
import com.prasbin.shadowlearn.data.home.WeakArea
import com.prasbin.shadowlearn.data.intelligence.ExplanationStatus
import com.prasbin.shadowlearn.data.intelligence.GroundedExplanation
import com.prasbin.shadowlearn.data.intelligence.RelatedMaterial
import com.prasbin.shadowlearn.navigation.Routes
import com.prasbin.shadowlearn.ui.components.ExplanationDialog
import com.prasbin.shadowlearn.ui.components.RelatedMaterialDialog

/**
 * I8 academic assistant screen — first vertical slice.
 *
 * Typed input only (speech deferred). Answers come from the assistant view
 * model via existing grounded intelligence (I1–I7). Honest empty / loading /
 * unsupported / no-match / insufficient-evidence states. Source cards carry
 * VERIFIED excerpts with provenance and OPEN SOURCE / PRACTICE actions
 * through real existing routes. No chat shell beyond this transcript; no
 * decorative AI, no invented facts.
 */
@Composable
fun AssistantScreen(onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val vm: AssistantViewModel = viewModel(factory = AssistantViewModel.factory(context))
    val s by vm.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    var explanation by remember { mutableStateOf<GroundedExplanation?>(null) }
    var explanationPractice by remember { mutableStateOf<Long?>(null) }
    var explanationRelated by remember { mutableStateOf<List<RelatedMaterial>>(emptyList()) }
    var related by remember { mutableStateOf<List<RelatedMaterial>?>(null) }

    // Design §8: the input is focused on entry (speech deferred).
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val submit = {
        val trimmed = draft.trim()
        if (trimmed.isNotEmpty() && !s.loading) {
            vm.send(trimmed)
            draft = ""
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "ACADEMIC ASSISTANT",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Grounded answers from your current semester's indexed material. " +
                    "Try \"Explain photosynthesis\" or \"What should I revise next?\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (s.question.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    s.question,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            when {
                s.loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                    Text(
                        "Searching your indexed material…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                s.unsupported -> {
                    Text(
                        s.message ?: "I couldn't understand your question. Please try rephrasing it.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "This assistant currently supports \"Explain [topic]\" and " +
                            "\"What should I revise next?\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                s.scopeInvalid || s.emptyCorpus -> {
                    Text(
                        s.message ?: "Your current semester has no indexed academic materials.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (s.scopeInvalid) {
                        OutlinedButton(
                            onClick = { onNavigate(Routes.SETTINGS) },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("OPEN SETTINGS") }
                    }
                    if (s.emptyCorpus) {
                        OutlinedButton(
                            onClick = { onNavigate(Routes.ACADEMIC) },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("OPEN ACADEMIC MATERIAL") }
                    }
                }

                s.result != null -> {
                    val result = s.result
                    if (result != null) {
                        s.message?.let { message ->
                            Text(message, style = MaterialTheme.typography.bodyLarge)
                        }
                        result.explanation?.let { explanationResult ->
                            AssistantExplanationCard(
                                explanation = explanationResult,
                                onOpenSource = { weekId -> onNavigate(Routes.hierarchyWeek(weekId)) },
                                onOpenPractice = { fileId -> onNavigate(Routes.practiceQuiz(fileId)) },
                                onOpenAcademic = { onNavigate(Routes.ACADEMIC) },
                                onOpenSearch = { onNavigate(Routes.SEARCH) }
                            )
                        }
                        result.recommendation?.let { recommendation ->
                            AssistantRecommendationCard(
                                recommendation = recommendation,
                                onDoIt = { onNavigate(recommendation.target.toAssistantRoute()) },
                                onOpenSource = { weekId -> onNavigate(Routes.hierarchyWeek(weekId)) },
                                onOpenPractice = { fileId -> onNavigate(Routes.practiceQuiz(fileId)) },
                                onOpenAcademic = { onNavigate(Routes.ACADEMIC) },
                                onOpenSearch = { onNavigate(Routes.SEARCH) }
                            )
                        }
                        if (result.weakAreas.isNotEmpty()) {
                            Text(
                                "WEAK AREAS FROM YOUR STUDY EVIDENCE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            result.weakAreas.take(3).forEach { area ->
                                AssistantWeakAreaItem(
                                    area = area,
                                    onOpenSource = { weekId -> onNavigate(Routes.hierarchyWeek(weekId)) },
                                    onOpenPractice = { fileId -> onNavigate(Routes.practiceQuiz(fileId)) },
                                    onExplain = { expl ->
                                        explanation = expl
                                        explanationPractice = area.practiceFileId
                                        explanationRelated = area.relatedMaterials
                                    },
                                    onOpenRelated = { materials -> related = materials }
                                )
                            }
                            Text(
                                "File-level signals only — concept tracking arrives later.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .focusRequester(focusRequester),
                singleLine = true,
                enabled = !s.loading,
                placeholder = { Text("Ask about your semester's material…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() })
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { submit() },
                enabled = draft.isNotBlank() && !s.loading,
                modifier = Modifier.height(48.dp)
            ) {
                Text("SEND")
            }
        }
    }

    explanation?.let { expl ->
        ExplanationDialog(
            explanation = expl,
            onOpenSource = { weekId ->
                explanation = null
                explanationPractice = null
                explanationRelated = emptyList()
                onNavigate(Routes.hierarchyWeek(weekId))
            },
            onOpenMaterial = {
                explanation = null
                explanationPractice = null
                explanationRelated = emptyList()
                onNavigate(Routes.ACADEMIC)
            },
            onOpenSearch = {
                explanation = null
                explanationPractice = null
                explanationRelated = emptyList()
                onNavigate(Routes.SEARCH)
            },
            onDismiss = {
                explanation = null
                explanationPractice = null
                explanationRelated = emptyList()
            },
            practiceFileId = explanationPractice,
            onPractice = { fileId ->
                explanation = null
                explanationPractice = null
                explanationRelated = emptyList()
                onNavigate(Routes.practiceQuiz(fileId))
            },
            relatedMaterials = explanationRelated,
            onOpenRelated = {
                val materials = explanationRelated
                explanation = null
                explanationPractice = null
                explanationRelated = emptyList()
                related = materials
            }
        )
    }
    related?.let { materials ->
        RelatedMaterialDialog(
            materials = materials,
            onOpenSource = { weekId ->
                related = null
                onNavigate(Routes.hierarchyWeek(weekId))
            },
            onDismiss = { related = null }
        )
    }
}

@Composable
private fun AssistantExplanationCard(
    explanation: GroundedExplanation,
    onOpenSource: (Long) -> Unit,
    onOpenPractice: (Long) -> Unit,
    onOpenAcademic: () -> Unit,
    onOpenSearch: () -> Unit
) {
    when (explanation.status) {
        ExplanationStatus.EXPLAINED -> {
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
            Text(
                "BASED ON YOUR MATERIAL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            explanation.sources.forEach { source ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(source.fileName, style = MaterialTheme.typography.titleSmall)
                    val scope = buildList {
                        source.weekLabel?.let { add(it) }
                        source.moduleName?.let { add(it) }
                        source.pageNumber?.let { add("Page $it") }
                        add("Chunk ${source.chunkIndex + 1}")
                    }.joinToString(" · ")
                    Text(
                        scope,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "“${source.excerpt}”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    source.weekId?.let { weekId ->
                        TextButton(
                            onClick = { onOpenSource(weekId) },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("OPEN SOURCE ›") }
                    }
                    TextButton(
                        onClick = { onOpenPractice(source.academicFileId) },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text("PRACTICE THIS MATERIAL ›") }
                }
            }
        }

        ExplanationStatus.INSUFFICIENT_EVIDENCE -> {
            Text(
                "INSUFFICIENT ACADEMIC EVIDENCE",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "SHADOW LEARN could not find enough indexed material in your current " +
                    "semester to explain this safely.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(
                onClick = onOpenAcademic,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("OPEN ACADEMIC MATERIAL") }
            OutlinedButton(
                onClick = onOpenSearch,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("SEARCH MATERIAL") }
        }

        ExplanationStatus.NO_SOURCE -> Text(
            "No academic source is connected to this evidence.",
            style = MaterialTheme.typography.bodyMedium
        )

        ExplanationStatus.SOURCE_NOT_INDEXED -> Text(
            "The source file exists but has no indexed text yet.",
            style = MaterialTheme.typography.bodyMedium
        )

        ExplanationStatus.NO_MATCH -> Text(
            "No matching indexed material in the current semester.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun AssistantRecommendationCard(
    recommendation: Recommendation,
    onDoIt: () -> Unit,
    onOpenSource: (Long) -> Unit,
    onOpenPractice: (Long) -> Unit,
    onOpenAcademic: () -> Unit,
    onOpenSearch: () -> Unit
) {
    Text(
        "RECOMMENDED NEXT ACTION",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary
    )
    Text(recommendation.text, style = MaterialTheme.typography.bodyLarge)
    Text(
        recommendation.evidence,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    recommendation.sourceFileName?.let { name ->
        Text(
            "SOURCE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(name, style = MaterialTheme.typography.bodyMedium)
        recommendation.sourceExcerpt?.let { excerpt ->
            Text(
                "“$excerpt”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
    recommendation.explanation?.let { explanation ->
        AssistantExplanationCard(
            explanation = explanation,
            onOpenSource = onOpenSource,
            onOpenPractice = onOpenPractice,
            onOpenAcademic = onOpenAcademic,
            onOpenSearch = onOpenSearch
        )
    }
    recommendation.sourceWeekId?.let { weekId ->
        TextButton(
            onClick = { onOpenSource(weekId) },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("OPEN SOURCE ›") }
    }
    recommendation.practiceFileId?.let { fileId ->
        TextButton(
            onClick = { onOpenPractice(fileId) },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("PRACTICE THIS MATERIAL ›") }
    }
    Button(onClick = onDoIt, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text("DO IT")
    }
}

@Composable
private fun AssistantWeakAreaItem(
    area: WeakArea,
    onOpenSource: (Long) -> Unit,
    onOpenPractice: (Long) -> Unit,
    onExplain: (GroundedExplanation) -> Unit,
    onOpenRelated: (List<RelatedMaterial>) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(area.label, style = MaterialTheme.typography.titleSmall)
        Text(
            area.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        area.explanation?.let { expl ->
            TextButton(
                onClick = { onExplain(expl) },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("EXPLAIN ›") }
        }
        area.practiceFileId?.let { fileId ->
            TextButton(
                onClick = { onOpenPractice(fileId) },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("PRACTICE THIS MATERIAL ›") }
        }
        area.sourceWeekId?.let { weekId ->
            TextButton(
                onClick = { onOpenSource(weekId) },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("OPEN SOURCE ›") }
        }
        if (area.relatedMaterials.isNotEmpty()) {
            TextButton(
                onClick = { onOpenRelated(area.relatedMaterials) },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("RELATED MATERIAL ›") }
        }
    }
}

private fun HomeTarget.toAssistantRoute(): String = when (this) {
    HomeTarget.CARDS -> Routes.FLASHCARDS
    HomeTarget.QUIZ -> Routes.QUIZ
    HomeTarget.LISTEN -> Routes.LISTENER
    HomeTarget.ACADEMIC -> Routes.hierarchyRoot()
    HomeTarget.SETTINGS -> Routes.SETTINGS
}
