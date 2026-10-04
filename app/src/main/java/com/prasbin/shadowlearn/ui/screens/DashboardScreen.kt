package com.prasbin.shadowlearn.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prasbin.shadowlearn.data.home.HomeTarget
import com.prasbin.shadowlearn.data.intelligence.GroundedExplanation
import com.prasbin.shadowlearn.data.intelligence.WeaknessStatus
import com.prasbin.shadowlearn.navigation.Routes
import com.prasbin.shadowlearn.ui.components.ExplanationDialog
import com.prasbin.shadowlearn.ui.components.SectionCard
import com.prasbin.shadowlearn.ui.components.StatRow
import com.prasbin.shadowlearn.ui.dashboard.DashboardViewModel

private fun HomeTarget.toRoute(): String = when (this) {
    HomeTarget.CARDS -> Routes.FLASHCARDS
    HomeTarget.QUIZ -> Routes.QUIZ
    HomeTarget.LISTEN -> Routes.LISTENER
    HomeTarget.ACADEMIC -> Routes.hierarchyRoot()
    HomeTarget.SETTINGS -> Routes.SETTINGS
}

/**
 * SYSTEM HOME — the academic command center. Every section derives from
 * real persisted rows (see DashboardViewModel/SystemHomeRepository);
 * empty inputs collapse to honest empty states, never invented content.
 */
@Composable
fun DashboardScreen(onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val vm: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(context))
    val s by vm.state.collectAsStateWithLifecycle()
    val go = { target: HomeTarget -> onNavigate(target.toRoute()) }
    var explanation: GroundedExplanation? by remember { mutableStateOf<GroundedExplanation?>(null) }
    var explanationPractice: Long? by remember { mutableStateOf<Long?>(null) }
    val openExplanation = { expl: GroundedExplanation, practiceFileId: Long? ->
        explanation = expl
        explanationPractice = practiceFileId
    }
    val closeExplanation = {
        explanation = null
        explanationPractice = null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("SHADOW LEARN", style = MaterialTheme.typography.headlineLarge)
            Text(
                "SYSTEM ● Level ${s.level} · ${s.xp} XP · Streak ${if (s.streak == 1) "1 day" else "${s.streak} days"}",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { s.levelProgress },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "${(s.levelProgress * 100).toInt()}% · ${s.xpToNextLevel} XP to next level",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            SectionCard(
                "Academic Status",
                modifier = Modifier.clickable { go(HomeTarget.ACADEMIC) }
            ) {
                Text(
                    "${s.currentYear} · ${s.currentSemester}",
                    style = MaterialTheme.typography.titleMedium
                )
                StatRow("Modules", s.moduleCount.toString())
                if (!s.scopeValid) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Select a year and semester in Settings to attach activity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            SectionCard("Current Focus") {
                val focus = s.focus
                if (focus == null) {
                    Text(
                        if (!s.scopeValid) "No academic scope yet."
                        else "Nothing studied yet — open a module to begin.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(focus.headline, style = MaterialTheme.typography.titleMedium)
                    Text(
                        focus.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = { go(focus.target) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue")
                    }
                }
            }
        }
        item {
            SectionCard("Today's Objectives") {
                if (s.objectives.isEmpty()) {
                    Text(
                        if (!s.scopeValid) "Objectives appear once a semester is configured."
                        else "All clear — nothing due.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    s.objectives.forEach { objective ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { go(objective.target) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "○  ${objective.title}",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "›",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            objective.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            val recommendation = s.recommendation
            if (recommendation != null) {
                SectionCard("System Recommendation") {
                    Text(recommendation.text, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        recommendation.evidence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    recommendation.sourceFileName?.let { name ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "SOURCE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        recommendation.sourceExcerpt?.let { excerpt ->
                            Text(
                                excerpt,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = { go(recommendation.target) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Do it")
                    }
                    recommendation.explanation?.let { expl ->
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = { openExplanation(expl, recommendation.practiceFileId) },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("EXPLAIN ›") }
                    }
                    recommendation.sourceWeekId?.let { weekId ->
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = { onNavigate(Routes.hierarchyWeek(weekId)) },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("OPEN SOURCE ›") }
                    }
                }
            }
        }
        if (s.quickActions.isNotEmpty()) {
            item {
                SectionCard("Quick Actions") {
                    s.quickActions.forEach { action ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { go(action.target) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                action.title,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "›",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            action.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            SectionCard("Academic Progress") {
                StatRow("Progress", "${s.progressPct}%")
                LinearProgressIndicator(
                    progress = { (s.progressPct / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (s.progressBasis.isNotEmpty()) {
                    Text(
                        "Based on ${s.progressBasis}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (s.weakAreas.isNotEmpty()) {
            item {
                SectionCard("Weak Areas") {
                    s.weakAreas.forEach { area ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { go(area.target) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                area.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (area.status == WeaknessStatus.OBSERVED.name)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                area.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        area.explanation?.let { expl ->
                            TextButton(
                                onClick = { openExplanation(expl, area.practiceFileId) },
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) { Text("EXPLAIN ›") }
                        }
                        area.practiceFileId?.let { fileId ->
                            TextButton(
                                onClick = { onNavigate(Routes.practiceQuiz(fileId)) },
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text(
                                    if (area.status == WeaknessStatus.OBSERVED.name) "PRACTICE THIS ›"
                                    else "PRACTICE ›"
                                )
                            }
                        }
                        area.sourceWeekId?.let { weekId ->
                            TextButton(
                                onClick = { onNavigate(Routes.hierarchyWeek(weekId)) },
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) { Text("OPEN SOURCE ›") }
                        }
                    }
                    Text(
                        "File-level signals only — concept tracking arrives later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (s.activity.isNotEmpty()) {
            item {
                SectionCard("Recent Activity") {
                    s.activity.forEach { event ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { go(event.target) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                event.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text("›", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        }
    }
    explanation?.let { expl ->
        ExplanationDialog(
            explanation = expl,
            onOpenSource = { weekId -> closeExplanation(); onNavigate(Routes.hierarchyWeek(weekId)) },
            onOpenMaterial = { closeExplanation(); onNavigate(Routes.hierarchyRoot()) },
            onOpenSearch = { closeExplanation(); onNavigate(Routes.SEARCH) },
            onDismiss = { closeExplanation() },
            practiceFileId = explanationPractice,
            onPractice = { fileId -> closeExplanation(); onNavigate(Routes.practiceQuiz(fileId)) }
        )
    }
}
