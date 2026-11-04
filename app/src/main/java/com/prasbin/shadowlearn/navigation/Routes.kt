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
    const val QUIZ_PRACTICE = "quiz/practice"
    const val LISTENER = "listener"
    const val FLASHCARDS = "flashcards"
    const val PROGRESS = "progress"
    const val SETTINGS = "settings"
    const val HIERARCHY = "hierarchy"
    const val HIERARCHY_YEAR = "hierarchy/year"
    const val HIERARCHY_SEMESTER = "hierarchy/semester"
    const val HIERARCHY_MODULE = "hierarchy/module"
    const val HIERARCHY_WEEK = "hierarchy/week"

    /** I8 academic assistant destination (reached from SYSTEM HOME; no new tab). */
    const val ASSISTANT = "assistant"

    fun hierarchyRoot(): String = HIERARCHY
    fun hierarchyYear(yearId: Long): String = "$HIERARCHY_YEAR/$yearId"
    fun hierarchySemester(semesterId: Long): String = "$HIERARCHY_SEMESTER/$semesterId"
    fun hierarchyModule(moduleId: Long): String = "$HIERARCHY_MODULE/$moduleId"
    fun hierarchyWeek(weekId: Long): String = "$HIERARCHY_WEEK/$weekId"

    /** I5 targeted-practice entry for one weak source file. */
    fun practiceQuiz(fileId: Long): String = "$QUIZ_PRACTICE/$fileId"
}

/** Closest hierarchy destination for a file's week; null when unresolvable. */
fun hierarchyWeekRoute(weekId: Long?): String? =
    weekId?.let { Routes.hierarchyWeek(it) }

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
        route == Routes.QUIZ || route == Routes.FLASHCARDS ||
            route.startsWith(Routes.QUIZ + "/") -> NavTab.STUDY
        route == Routes.LISTENER -> NavTab.LISTEN
        route == Routes.PROGRESS || route == Routes.SETTINGS -> NavTab.SYSTEM
        // The assistant is a HOME-originated destination, not a sixth group.
        // Leaving it unowned keeps no tab falsely "already open", so tapping
        // HOME returns to the dashboard reliably.
        route == Routes.ASSISTANT -> null
        else -> null
    }
}

/**
 * Routes a tab's own screen(s) manage internally. Tapping an already-open
 * group must not reset that in-group state (quiz/flashcards, status/settings,
 * hierarchy browsing). A route that merely *belongs* to a tab but is a
 * separate destination (e.g. the assistant reached from HOME) is NOT owned:
 * tapping the tab then returns to the group's default route.
 */
fun NavTab.ownsRoute(route: String?): Boolean = when (this) {
    NavTab.HOME -> route == Routes.DASHBOARD
    NavTab.ACADEMIC -> route == Routes.ACADEMIC || route?.startsWith(Routes.HIERARCHY) == true
    NavTab.STUDY -> route == Routes.QUIZ || route == Routes.FLASHCARDS ||
        route?.startsWith(Routes.QUIZ + "/") == true
    NavTab.LISTEN -> route == Routes.LISTENER
    NavTab.SYSTEM -> route == Routes.PROGRESS || route == Routes.SETTINGS
}

/**
 * Whether tapping this tab should restore its saved back stack.
 *
 * HOME is deliberately excluded: its saved stack can contain a
 * HOME-originated detail destination (the academic assistant), and restoring
 * it made the Home icon reopen the assistant instead of the dashboard. HOME
 * therefore always lands on [NavTab.defaultRoute]; every other group keeps
 * its in-group state (quiz/cards, hierarchy, status/settings).
 */
fun NavTab.restoresSavedState(): Boolean = this != NavTab.HOME
