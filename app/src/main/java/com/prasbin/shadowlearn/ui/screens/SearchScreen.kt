package com.prasbin.shadowlearn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prasbin.shadowlearn.data.search.SearchResult
import com.prasbin.shadowlearn.ui.search.SearchUiKind
import com.prasbin.shadowlearn.ui.search.SearchViewModel
import com.prasbin.shadowlearn.navigation.Routes

/**
 * Phase 5 Academic Search — the SYSTEM-style search HUD over the Phase 4
 * chunk index. Scoped to the current semester (DataStore context); every
 * state (empty / typing / results / no results / error / no indexed
 * content / no semester) is explicit and honest.
 */
@Composable
fun SearchScreen(onNavigate: (String) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(context))
    val s by vm.state.collectAsStateWithLifecycle()

    var selected by remember { mutableStateOf<SearchResult?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "ACADEMIC SEARCH",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Search your verified academic knowledge base",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            OutlinedTextField(
                value = s.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search the knowledge base") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (s.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear query")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Search
                )
            )
        }

        item { ScopeIndicator(s) }

        when (s.kind) {
            SearchUiKind.EMPTY -> item { EmptyState("Type a query to search the current semester's indexed material.") }
            SearchUiKind.NO_SEMESTER -> item {
                EmptyState("No semester configured. Select a year and semester in Settings, or import a semester ZIP in the Academic tab.")
                TextButton(
                    onClick = { onNavigate(Routes.SETTINGS) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("OPEN SETTINGS") }
            }
            SearchUiKind.NO_INDEXED -> item {
                EmptyState("No indexed academic content in this semester yet. Import a semester ZIP in the Academic tab to build the search index.")
            }
            SearchUiKind.SEARCHING -> item {
                Column {
                    Text("Searching...", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            SearchUiKind.ERROR -> item {
                EmptyState("Search error: ${s.error ?: "unknown"}")
            }
            SearchUiKind.NO_RESULTS -> item {
                EmptyState("No results for \"${s.query}\". Try a keyword from a file's content or title.")
            }
            SearchUiKind.RESULTS -> {
                item {
                    Text(
                        if (s.results.size == 1) "1 RESULT" else "${s.results.size} RESULTS",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(s.results, key = { it.chunkId }) { r ->
                    ResultCard(
                        result = r,
                        maxScore = s.maxScore,
                        onClick = { selected = r }
                    )
                }
            }
        }
    }

    val sel = selected
    val hierarchyTarget by produceState<String?>(initialValue = null, sel?.academicFileId) {
        value = sel?.let { vm.hierarchyTargetFor(it.academicFileId) }
    }
    sel?.let {
        DetailDialog(
            result = it,
            hierarchyTarget = hierarchyTarget,
            onOpenHierarchy = { target -> selected = null; onNavigate(target) },
            onDismiss = { selected = null }
        )
    }
}

@Composable
private fun ScopeIndicator(s: com.prasbin.shadowlearn.ui.search.SearchUiState) {
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
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
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
private fun ResultCard(result: SearchResult, maxScore: Double, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    result.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TypeChip(result.fileType)
                val ref = locationLabel(result)
                if (ref != null) {
                    Text(
                        ref,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(result.moduleName)
                    result.weekNumber?.let { append(" · Week $it") }
                    result.weekTitle?.let { append(" — $it") }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                buildAnnotatedString {
                    if (result.excerptHighlights.isEmpty()) {
                        append(result.excerpt)
                    } else {
                        var cursor = 0
                        result.excerptHighlights
                            .sortedBy { it.first }
                            .forEach { range ->
                                val start = range.first.coerceIn(0, result.excerpt.length)
                                val end = range.last.coerceIn(start, result.excerpt.length) + 1
                                append(result.excerpt, cursor, start)
                                withStyle(
                                    SpanStyle(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                ) { append(result.excerpt, start, end) }
                                cursor = end
                            }
                        append(result.excerpt, cursor, result.excerpt.length)
                    }
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            RelevanceBar(result.score, maxScore)
        }
    }
}

private fun locationLabel(result: SearchResult): String? {
    val n = result.pageNumber ?: return null
    return if (result.fileType == "pptx") "SLIDE $n" else "PAGE $n"
}

@Composable
private fun TypeChip(fileType: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            fileType.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun RelevanceBar(score: Double, maxScore: Double) {
    val fraction = if (maxScore > 0.0) (score / maxScore).coerceIn(0.0, 1.0).toFloat() else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "RELEVANCE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.width(120.dp),
            color = MaterialTheme.colorScheme.tertiary
        )
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
private fun DetailDialog(
    result: SearchResult,
    hierarchyTarget: String?,
    onOpenHierarchy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(result.fileName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("LOCATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(result.moduleName, style = MaterialTheme.typography.bodyLarge)
                result.weekNumber?.let {
                    Text("Week $it" + (result.weekTitle?.let { t -> " — $t" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                }
                locationLabel(result)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
                Text("TYPE ${result.fileType.uppercase()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.height(4.dp))
                Text("MATCH", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(result.excerpt, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text("SOURCE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Indexed and available in the Academic tab (${result.relativePath.ifEmpty { result.fileName }}).",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (hierarchyTarget != null) {
                TextButton(onClick = { onOpenHierarchy(hierarchyTarget) }) {
                    Text("OPEN IN HIERARCHY ›")
                }
            }
        }
    )
}