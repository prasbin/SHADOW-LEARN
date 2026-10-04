package com.prasbin.shadowlearn.ui.dashboard

import com.prasbin.shadowlearn.data.home.FocusState
import com.prasbin.shadowlearn.data.home.HomeObjective
import com.prasbin.shadowlearn.data.home.HomeTarget
import com.prasbin.shadowlearn.data.intelligence.RecommendationKind
import com.prasbin.shadowlearn.data.home.ObjectiveKind
import com.prasbin.shadowlearn.data.home.Recommendation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quick Actions derive only from existing Home state — no invented content. */
class QuickActionsTest {

    private fun dueObjective(n: Int) = HomeObjective(
        ObjectiveKind.DUE_REVIEW, "Review $n due", "detail", n, HomeTarget.CARDS
    )

    private val quizRecommendation =
        Recommendation("Quiz now", "evidence", HomeTarget.QUIZ, RecommendationKind.RESUME)
    private val cardsRecommendation =
        Recommendation("Review now", "evidence", HomeTarget.CARDS, RecommendationKind.DUE_REVIEW)
    private val cardsFocus = FocusState("Spaced review", "detail", HomeTarget.CARDS)
    private val listenFocus = FocusState("Lecture capture", "detail", HomeTarget.LISTEN)

    @Test
    fun noScopeMeansNoActions() {
        assertTrue(buildQuickActions(listenFocus, quizRecommendation, listOf(dueObjective(5)), false).isEmpty())
    }

    @Test
    fun fullStateWithoutOverlapYieldsOrderedActions() {
        val actions = buildQuickActions(listenFocus, quizRecommendation, listOf(dueObjective(5)), true)
        assertEquals(
            listOf(HomeTarget.LISTEN, HomeTarget.CARDS, HomeTarget.ACADEMIC),
            actions.map { it.target }
        )
        assertEquals("CONTINUE", actions[0].title)
        assertEquals("REVIEW CARDS", actions[1].title)
        assertEquals("5 DUE", actions[1].detail)
        assertEquals("BROWSE MATERIAL", actions[2].title)
    }

    @Test
    fun recommendationTargetIsNotRepeated() {
        val actions = buildQuickActions(listenFocus, cardsRecommendation, listOf(dueObjective(5)), true)
        assertTrue(actions.none { it.target == HomeTarget.CARDS })
        assertTrue(actions.any { it.target == HomeTarget.QUIZ })
    }

    @Test
    fun focusTargetIsNotRepeated() {
        val actions = buildQuickActions(cardsFocus, quizRecommendation, listOf(dueObjective(5)), true)
        assertEquals(1, actions.count { it.target == HomeTarget.CARDS })
        assertEquals("CONTINUE", actions.first { it.target == HomeTarget.CARDS }.title)
    }

    @Test
    fun noFocusMeansNoContinue() {
        val actions = buildQuickActions(null, quizRecommendation, emptyList(), true)
        assertTrue(actions.none { it.title == "CONTINUE" })
        assertEquals(listOf(HomeTarget.ACADEMIC), actions.map { it.target })
    }

    @Test
    fun zeroDueMeansNoReviewAction() {
        val actions = buildQuickActions(null, null, emptyList(), true)
        assertEquals(
            listOf(HomeTarget.QUIZ, HomeTarget.ACADEMIC),
            actions.map { it.target }
        )
    }

    @Test
    fun neverExceedsFourActions() {
        val actions = buildQuickActions(
            listenFocus, null,
            listOf(dueObjective(9)), true
        )
        assertTrue(actions.size <= 4)
        assertEquals(actions.size, actions.map { it.target }.toSet().size)
    }
}
