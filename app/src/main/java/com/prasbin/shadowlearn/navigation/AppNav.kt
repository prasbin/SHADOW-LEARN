package com.prasbin.shadowlearn.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.prasbin.shadowlearn.ui.screens.AcademicScreen
import com.prasbin.shadowlearn.ui.screens.DashboardScreen
import com.prasbin.shadowlearn.ui.screens.FlashcardsScreen
import com.prasbin.shadowlearn.ui.screens.HierarchyLevel
import com.prasbin.shadowlearn.ui.screens.HierarchyScreen
import com.prasbin.shadowlearn.ui.screens.ListenerScreen
import com.prasbin.shadowlearn.ui.screens.ProgressScreen
import com.prasbin.shadowlearn.ui.screens.QuizScreen
import com.prasbin.shadowlearn.ui.screens.SearchScreen
import com.prasbin.shadowlearn.ui.screens.SettingsScreen

/**
 * SYSTEM navigation shell: five bottom groups (HOME/ACADEMIC/STUDY/LISTEN/
 * SYSTEM) plus a persistent top-bar Search action. Grouped destinations
 * keep their own routes (quiz/cards, status/settings) with a shared
 * in-screen switcher; tapping the open group is a no-op so state is kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val currentTab = tabForRoute(currentRoute)
    val go = { route: String ->
        navController.navigate(route) { launchSingleTop = true }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (currentTab?.label ?: "SEARCH").uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                actions = {
                    if (currentRoute != Routes.SEARCH) {
                        IconButton(onClick = { go(Routes.SEARCH) }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = "Search academic material"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = {
                            if (currentTab != tab) {
                                navController.navigate(tab.defaultRoute) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(inner)
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(onNavigate = go)
            }
            composable(Routes.ACADEMIC) { AcademicScreen(onNavigate = go) }
            composable(Routes.HIERARCHY) {
                HierarchyScreen(level = HierarchyLevel.Years, onNavigate = go)
            }
            composable(
                route = "${Routes.HIERARCHY_YEAR}/{yearId}",
                arguments = listOf(navArgument("yearId") { type = NavType.LongType })
            ) { entry ->
                HierarchyScreen(
                    level = HierarchyLevel.Semesters(entry.arguments?.getLong("yearId") ?: -1),
                    onNavigate = go
                )
            }
            composable(
                route = "${Routes.HIERARCHY_SEMESTER}/{semesterId}",
                arguments = listOf(navArgument("semesterId") { type = NavType.LongType })
            ) { entry ->
                HierarchyScreen(
                    level = HierarchyLevel.Modules(entry.arguments?.getLong("semesterId") ?: -1),
                    onNavigate = go
                )
            }
            composable(
                route = "${Routes.HIERARCHY_MODULE}/{moduleId}",
                arguments = listOf(navArgument("moduleId") { type = NavType.LongType })
            ) { entry ->
                HierarchyScreen(
                    level = HierarchyLevel.Weeks(entry.arguments?.getLong("moduleId") ?: -1),
                    onNavigate = go
                )
            }
            composable(
                route = "${Routes.HIERARCHY_WEEK}/{weekId}",
                arguments = listOf(navArgument("weekId") { type = NavType.LongType })
            ) { entry ->
                HierarchyScreen(
                    level = HierarchyLevel.Files(entry.arguments?.getLong("weekId") ?: -1),
                    onNavigate = go
                )
            }
            composable(Routes.SEARCH) { SearchScreen(onNavigate = go) }
            composable(Routes.QUIZ) { QuizScreen(onNavigate = go) }
            composable(Routes.LISTENER) { ListenerScreen(onNavigate = go) }
            composable(Routes.FLASHCARDS) { FlashcardsScreen(onNavigate = go) }
            composable(Routes.PROGRESS) { ProgressScreen(onNavigate = go) }
            composable(Routes.SETTINGS) { SettingsScreen(onNavigate = go) }
        }
    }
}
