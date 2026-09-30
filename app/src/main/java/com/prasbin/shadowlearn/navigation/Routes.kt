package com.prasbin.shadowlearn.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val DASHBOARD = "dashboard"
    const val ACADEMIC = "academic"
    const val SEARCH = "search"
    const val QUIZ = "quiz"
    const val LISTENER = "listener"
    const val FLASHCARDS = "flashcards"
    const val PROGRESS = "progress"
    const val SETTINGS = "settings"
    const val HIERARCHY = "hierarchy"
    const val HIERARCHY_YEAR = "hierarchy/year"
    const val HIERARCHY_SEMESTER = "hierarchy/semester"
    const val HIERARCHY_MODULE = "hierarchy/module"
    const val HIERARCHY_WEEK = "hierarchy/week"

    fun hierarchyRoot(): String = HIERARCHY
    fun hierarchyYear(yearId: Long): String = "$HIERARCHY_YEAR/$yearId"
    fun hierarchySemester(semesterId: Long): String = "$HIERARCHY_SEMESTER/$semesterId"
    fun hierarchyModule(moduleId: Long): String = "$HIERARCHY_MODULE/$moduleId"
    fun hierarchyWeek(weekId: Long): String = "$HIERARCHY_WEEK/$weekId"
}

data class Destination(val route: String, val label: String, val icon: ImageVector)

/**
 * SYSTEM bottom navigation: five groups replace the eight flat tabs.
 * Every legacy route still exists and resolves into exactly one group
 * (or null for the standalone Search action). STUDY defaults to Quiz,
 * SYSTEM defaults to Academic Status; tapping an already-open group
 * is a no-op so state is never reset.
 */
enum class NavTab(val label: String, val icon: ImageVector, val defaultRoute: String) {
    HOME("Home", Icons.Filled.Home, Routes.DASHBOARD),
    ACADEMIC("Academic", Icons.Filled.School, Routes.ACADEMIC),
    STUDY("Study", Icons.Filled.Quiz, Routes.QUIZ),
    LISTEN("Listen", Icons.Filled.Mic, Routes.LISTENER),
    SYSTEM("System", Icons.Filled.Settings, Routes.PROGRESS)
}

/** Group owning [route]; null for standalone destinations (Search) or unknown. */
fun tabForRoute(route: String?): NavTab? {
    if (route == null) return null
    return when {
        route == Routes.DASHBOARD -> NavTab.HOME
        route == Routes.ACADEMIC || route.startsWith(Routes.HIERARCHY) -> NavTab.ACADEMIC
        route == Routes.QUIZ || route == Routes.FLASHCARDS -> NavTab.STUDY
        route == Routes.LISTENER -> NavTab.LISTEN
        route == Routes.PROGRESS || route == Routes.SETTINGS -> NavTab.SYSTEM
        else -> null
    }
}
