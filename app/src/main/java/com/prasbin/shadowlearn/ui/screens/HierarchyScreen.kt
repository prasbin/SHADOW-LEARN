package com.prasbin.shadowlearn.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prasbin.shadowlearn.navigation.Routes
import com.prasbin.shadowlearn.ui.components.SectionCard
import com.prasbin.shadowlearn.ui.hierarchy.HierarchyViewModel
import com.prasbin.shadowlearn.util.formatBytes

/** One drill-down level of the YEAR → … → FILE hierarchy. */
sealed interface HierarchyLevel {
    data object Years : HierarchyLevel
    data class Semesters(val yearId: Long) : HierarchyLevel
    data class Modules(val semesterId: Long) : HierarchyLevel
    data class Weeks(val moduleId: Long) : HierarchyLevel
    data class Files(val weekId: Long) : HierarchyLevel
}

/**
 * Academic hierarchy browser: every row comes from the existing Room
 * tables, empty levels report honestly, and the configured year/semester
 * carry a subtle CURRENT marker. File rows are informational (provenance
 * only) — no viewer exists yet, so none is pretended.
 */
@Composable
fun HierarchyScreen(level: HierarchyLevel, onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val vm: HierarchyViewModel = viewModel(factory = HierarchyViewModel.factory(context))
    val currentYearId by vm.currentYearId.collectAsStateWithLifecycle(initialValue = null)
    val currentSemesterId by vm.currentSemesterId.collectAsStateWithLifecycle(initialValue = null)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (level) {
            is HierarchyLevel.Years -> {
                item { HierarchyHeader(eyebrow = "ACADEMIC HIERARCHY", title = "Years") }
                item { YearsBody(vm, currentYearId, onNavigate) }
            }
            is HierarchyLevel.Semesters -> {
                item { SemestersBody(vm, level.yearId, currentSemesterId, onNavigate) }
            }
            is HierarchyLevel.Modules -> {
                item { ModulesBody(vm, level.semesterId, onNavigate) }
            }
            is HierarchyLevel.Weeks -> {
                item { WeeksBody(vm, level.moduleId, onNavigate) }
            }
            is HierarchyLevel.Files -> {
                item { FilesBody(vm, level.weekId) }
            }
        }
    }
}

@Composable
private fun YearsBody(
    vm: HierarchyViewModel,
    currentYearId: Long?,
    onNavigate: (String) -> Unit
) {
    val years by vm.years().collectAsStateWithLifecycle(initialValue = emptyList())
    if (years.isEmpty()) {
        HierarchyEmpty(
            "NO YEARS",
            "No academic years exist yet. Import a semester ZIP in the Academic tab to begin."
        )
    } else {
        SectionCard("Select year") {
            years.forEach { year ->
                HierarchyRow(
                    title = year.name,
                    current = year.id == currentYearId,
                    onClick = { onNavigate(Routes.hierarchyYear(year.id)) }
                )
            }
        }
    }
}

@Composable
private fun SemestersBody(
    vm: HierarchyViewModel,
    yearId: Long,
    currentSemesterId: Long?,
    onNavigate: (String) -> Unit
) {
    val title by produceState<String?>(initialValue = "", yearId) {
        value = vm.yearName(yearId)
    }
    HierarchyHeader(eyebrow = "ACADEMIC HIERARCHY", title = title ?: "Years")
    if (title == null) {
        HierarchyEmpty("UNAVAILABLE", "This year is no longer available.")
    } else {
        val semesters by vm.semestersOf(yearId)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        if (semesters.isEmpty()) {
            HierarchyEmpty(
                "NO SEMESTERS",
                "No semesters are available for this year. Import academic material to continue."
            )
        } else {
            SectionCard(title!!) {
                semesters.forEach { semester ->
                    HierarchyRow(
                        title = semester.name,
                        current = semester.id == currentSemesterId,
                        onClick = { onNavigate(Routes.hierarchySemester(semester.id)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModulesBody(
    vm: HierarchyViewModel,
    semesterId: Long,
    onNavigate: (String) -> Unit
) {
    val title by produceState<String?>(initialValue = "", semesterId) {
        value = vm.semesterTitle(semesterId)
    }
    HierarchyHeader(eyebrow = "ACADEMIC HIERARCHY", title = title ?: "Semesters")
    if (title == null) {
        HierarchyEmpty("UNAVAILABLE", "This semester is no longer available.")
    } else {
        val modules by vm.modulesOf(semesterId)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        if (modules.isEmpty()) {
            HierarchyEmpty("NO MODULES", "No modules are available for this semester.")
        } else {
            SectionCard(title!!) {
                modules.forEach { module ->
                    HierarchyRow(
                        title = module.name,
                        subtitle = module.code,
                        onClick = { onNavigate(Routes.hierarchyModule(module.id)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WeeksBody(
    vm: HierarchyViewModel,
    moduleId: Long,
    onNavigate: (String) -> Unit
) {
    val title by produceState<String?>(initialValue = "", moduleId) {
        value = vm.moduleTitle(moduleId)
    }
    HierarchyHeader(eyebrow = "ACADEMIC HIERARCHY", title = title ?: "Modules")
    if (title == null) {
        HierarchyEmpty("UNAVAILABLE", "This module is no longer available.")
    } else {
        val weeks by vm.weeksOf(moduleId)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        if (weeks.isEmpty()) {
            HierarchyEmpty("NO WEEKS", "No academic weeks are available for this module.")
        } else {
            SectionCard(title!!) {
                weeks.forEach { week ->
                    HierarchyRow(
                        title = "Week ${week.weekNumber}",
                        subtitle = week.title,
                        onClick = { onNavigate(Routes.hierarchyWeek(week.id)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilesBody(vm: HierarchyViewModel, weekId: Long) {
    val title by produceState<String?>(initialValue = "", weekId) {
        value = vm.weekTitle(weekId)
    }
    HierarchyHeader(eyebrow = "ACADEMIC HIERARCHY", title = title ?: "Weeks")
    if (title == null) {
        HierarchyEmpty("UNAVAILABLE", "This week is no longer available.")
    } else {
        val files by vm.filesOf(weekId)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        if (files.isEmpty()) {
            HierarchyEmpty("NO MATERIAL", "No files are available for this week.")
        } else {
            SectionCard(title!!) {
                files.forEach { file ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(file.fileName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${file.classType} · ${formatBytes(file.fileSize)} · " +
                                if (file.indexed) "INDEXED" else "NOT INDEXED",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (file.relativePath.isNotEmpty()) {
                            Text(
                                file.relativePath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HierarchyHeader(eyebrow: String, title: String) {
    Column {
        Text(
            eyebrow,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun HierarchyEmpty(heading: String, body: String) {
    SectionCard(heading) {
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HierarchyRow(
    title: String,
    subtitle: String? = null,
    current: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                if (current) {
                    Text(
                        "  ● CURRENT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            "›",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
