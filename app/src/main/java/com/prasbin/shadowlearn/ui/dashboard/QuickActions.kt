package com.prasbin.shadowlearn.ui.dashboard

import com.prasbin.shadowlearn.data.home.FocusState
import com.prasbin.shadowlearn.data.home.HomeObjective
import com.prasbin.shadowlearn.data.home.HomeTarget
import com.prasbin.shadowlearn.data.home.ObjectiveKind
import com.prasbin.shadowlearn.data.home.Recommendation

/** One fast entry into an existing capability — never a new destination. */
data class QuickAction(
    val title: String,
    val detail: String,
    val target: HomeTarget
)

/**
 * Context-aware Quick Actions for SYSTEM HOME (max 4). Every action
 * reuses an existing route/state; counts come from the objectives list.
 * A destination already served by the Focus Continue button or the
 * recommendation Do-it button is omitted — fast access, not repetition.
 * No scope → no actions (Home already guides setup elsewhere).
 */
fun buildQuickActions(
    focus: FocusState?,
    recommendation: Recommendation?,
    objectives: List<HomeObjective>,
    scopeValid: Boolean
): List<QuickAction> {
    if (!scopeValid) return emptyList()
    val used = mutableSetOf<HomeTarget>()
    val out = mutableListOf<QuickAction>()
    if (focus != null && focus.target != recommendation?.target) {
        out.add(QuickAction("CONTINUE", focus.headline, focus.target))
        used.add(focus.target)
    }
    val due = objectives.firstOrNull { it.kind == ObjectiveKind.DUE_REVIEW }?.count ?: 0
    if (due > 0 && HomeTarget.CARDS !in used && HomeTarget.CARDS != recommendation?.target) {
        out.add(
            QuickAction(
                "REVIEW CARDS",
                if (due == 1) "1 DUE" else "$due DUE",
                HomeTarget.CARDS
            )
        )
        used.add(HomeTarget.CARDS)
    }
    if (HomeTarget.QUIZ !in used && HomeTarget.QUIZ != recommendation?.target) {
        out.add(QuickAction("START QUIZ", "Rule-based questions", HomeTarget.QUIZ))
        used.add(HomeTarget.QUIZ)
    }
    if (HomeTarget.ACADEMIC !in used && HomeTarget.ACADEMIC != recommendation?.target) {
        out.add(QuickAction("BROWSE MATERIAL", "Year → Semester → Module → Week → File", HomeTarget.ACADEMIC))
    }
    return out.take(4)
}
