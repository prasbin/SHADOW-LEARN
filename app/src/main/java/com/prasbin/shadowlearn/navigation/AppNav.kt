package com.prasbin.shadowlearn.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
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

/** Bottom-bar navigation shell; each destination owns its future feature module. */
@Composable
fun AppNav() {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStack by navController.currentBackStackEntryAsState()
                val current = backStack?.destination
                destinations.forEach { dest ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == dest.route } == true,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) }
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
                DashboardScreen(onNavigate = { route ->
                    navController.navigate(route) { launchSingleTop = true }
                })
            }
            composable(Routes.ACADEMIC) { AcademicScreen() }
            composable(Routes.HIERARCHY) {
                HierarchyScreen(
                    level = HierarchyLevel.Years,
                    onNavigate = { route ->
                        navController.navigate(route) { launchSingleTop = true }
                    }
                )
            }
            composable(
                route = "${Routes.HIERARCHY_YEAR}/{yearId}",
                arguments = listOf(navArgument("yearId") { type = NavType.LongType })
            ) { entry ->
                HierarchyScreen(
                    level = HierarchyLevel.Semesters(entry.arguments?.getLong("yearId") ?: -1),
                    onNavigate = { route ->
                        navController.navigate(route) { launchSingleTop = true }
                    }
                )
            }
            composable(
                route = "${Routes.HIERARCHY_SEMESTER}/{semesterId}",
                arguments = listOf(navArgument("semesterId") { type = NavType.LongType })
            ) { entry ->
                HierarchyScreen(
                    level = HierarchyLevel.Modules(entry.arguments?.getLong("semesterId") ?: -1),
                    onNavigate = { route ->
                        navController.navigate(route) { launchSingleTop = true }
                    }
                )
            }
            composable(
                route = "${Routes.HIERARCHY_MODULE}/{moduleId}",
                arguments = listOf(navArgument("moduleId") { type = NavType.LongType })
            ) { entry ->
                HierarchyScreen(
                    level = HierarchyLevel.Weeks(entry.arguments?.getLong("moduleId") ?: -1),
                    onNavigate = { route ->
                        navController.navigate(route) { launchSingleTop = true }
                    }
                )
            }
            composable(
                route = "${Routes.HIERARCHY_WEEK}/{weekId}",
                arguments = listOf(navArgument("weekId") { type = NavType.LongType })
            ) { entry ->
                HierarchyScreen(
                    level = HierarchyLevel.Files(entry.arguments?.getLong("weekId") ?: -1),
                    onNavigate = { route ->
                        navController.navigate(route) { launchSingleTop = true }
                    }
                )
            }
            composable(Routes.SEARCH) { SearchScreen() }
            composable(Routes.QUIZ) { QuizScreen() }
            composable(Routes.LISTENER) { ListenerScreen() }
            composable(Routes.FLASHCARDS) { FlashcardsScreen() }
            composable(Routes.PROGRESS) { ProgressScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
        }
    }
}
