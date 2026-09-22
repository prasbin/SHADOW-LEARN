package com.prasbin.shadowlearn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prasbin.shadowlearn.BuildConfig
import com.prasbin.shadowlearn.ui.components.SectionCard
import com.prasbin.shadowlearn.ui.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context))
    val s by vm.state.collectAsStateWithLifecycle()

    var newYear by remember { mutableStateOf("") }
    var newSemester by remember { mutableStateOf("") }
    var yearExpanded by remember { mutableStateOf(false) }
    var semesterExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }

        item {
            SectionCard("Academic Year") {
                ExposedDropdownMenuBox(expanded = yearExpanded, onExpandedChange = { yearExpanded = it }) {
                    TextField(
                        value = s.years.firstOrNull { it.id == s.currentYearId }?.name ?: "Not configured",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Current year") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(yearExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = yearExpanded, onDismissRequest = { yearExpanded = false }) {
                        s.years.forEach { y ->
                            DropdownMenuItem(
                                text = { Text(y.name) },
                                onClick = { vm.selectYear(y.id); yearExpanded = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = newYear, onValueChange = { newYear = it },
                    label = { Text("New year name (e.g. Year 2)") }, modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { vm.addYear(newYear, s.years.size + 1); newYear = "" },
                    enabled = newYear.isNotBlank()
                ) { Text("Add year") }
            }
        }

        item {
            SectionCard("Semester") {
                val semesters by vm.semestersOf(s.currentYearId ?: -1L)
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                ExposedDropdownMenuBox(expanded = semesterExpanded, onExpandedChange = { semesterExpanded = it }) {
                    TextField(
                        value = semesters.firstOrNull { it.id == s.currentSemesterId }?.name ?: "Not configured",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Current semester") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(semesterExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = semesterExpanded, onDismissRequest = { semesterExpanded = false }) {
                        semesters.forEach { sem ->
                            DropdownMenuItem(
                                text = { Text(sem.name) },
                                onClick = { vm.selectSemester(sem.id); semesterExpanded = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = newSemester, onValueChange = { newSemester = it },
                    label = { Text("New semester name (e.g. Semester 1)") }, modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val y = s.currentYearId ?: return@Button
                        vm.addSemester(y, newSemester, semesters.size + 1); newSemester = ""
                    },
                    enabled = newSemester.isNotBlank() && s.currentYearId != null
                ) { Text("Add semester") }
                if (s.currentYearId == null) Text("Select or add a year first.")
            }
        }

        item {
            SectionCard("Preferences") {
                SettingToggle("Notifications", "Reminder scheduling arrives in a later phase.",
                    s.notificationsEnabled, vm::setNotifications)
                SettingToggle("Dark mode", "Futuristic dark interface.", s.darkMode, vm::setDarkMode)
            }
        }

        item {
            SectionCard("Data") {
                Button(onClick = {}, enabled = false) { Text("Export Data") }
                Button(onClick = {}, enabled = false) { Text("Import Data") }
                Text("Backup / restore arrives in a later phase.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        item {
            SectionCard("About") {
                Text("SHADOW LEARN — personal offline-first learning system.")
                Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Text("Package ${BuildConfig.APPLICATION_ID}")
            }
        }
    }
}

@Composable
private fun SettingToggle(
    title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
