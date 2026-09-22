package com.prasbin.shadowlearn.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TrendingUp
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
}

data class Destination(val route: String, val label: String, val icon: ImageVector)

/** The 8 Phase 1 sections. Order defines the bottom-bar layout. */
val destinations = listOf(
    Destination(Routes.DASHBOARD, "Home", Icons.Filled.Home),
    Destination(Routes.ACADEMIC, "Academic", Icons.Filled.School),
    Destination(Routes.SEARCH, "Search", Icons.Filled.Search),
    Destination(Routes.QUIZ, "Quiz", Icons.Filled.Quiz),
    Destination(Routes.LISTENER, "Listen", Icons.Filled.Mic),
    Destination(Routes.FLASHCARDS, "Cards", Icons.Filled.Style),
    Destination(Routes.PROGRESS, "Progress", Icons.Filled.TrendingUp),
    Destination(Routes.SETTINGS, "Settings", Icons.Filled.Settings)
)
