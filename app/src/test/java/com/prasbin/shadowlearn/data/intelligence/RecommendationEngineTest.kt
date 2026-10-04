package com.prasbin.shadowlearn.data.intelligence

import com.prasbin.shadowlearn.data.home.FocusState
import com.prasbin.shadowlearn.data.home.HomeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I2 recommendation priority tests (pure, deterministic). Priority:
 * due → observed → resume → possible → transcripts → focus → material
 * → setup. Same input twice ⇒ same output, always.
 */
class RecommendationEngineTest {

    private val now = 1_704_000_000_000L

    private fun observedSignal(name: String = "cell-biology.pdf") = WeaknessSignal(
        fileId = 1, fileName = name, weekLabel = "Week 3", moduleName = "Biology",
        status = WeaknessStatus.OBSERVED, mistakeCount = 4, mistakeSessions = 2,
        againCount = 0, recentCorrects = 0, newestAt = now - 86_400_000L, dangling = false
    )

    private fun possibleSignal(name: String = "cell-biology.pdf") = WeaknessSignal(
        fileId = 1, fileName = name, weekLabel = null, moduleName = null,
        status = WeaknessStatus.POSSIBLE, mistakeCount = 1, mistakeSessions = 1,
        againCount = 0, recentCorrects = 0, newestAt = now - 86_400_000L, dangling = false
    )

    private fun improvingSignal() = WeaknessSignal(
        fileId = 1, fileName = "cell-biology.pdf", weekLabel = null, moduleName = null,
        status = WeaknessStatus.IMPROVING, mistakeCount = 3, mistakeSessions = 3,
        againCount = 0, recentCorrects = 3, newestAt = now - 86_400_000L, dangling = false
    )

    private val quizFocus = FocusState("Quiz practice", "3/6 correct last run", HomeTarget.QUIZ)

    @Test
    fun noScope_returnsSetupGuidance() {
        val rec = RecommendationEngine.recommend(RecommendationInput(scopeValid = false))
        assertEquals(RecommendationKind.SETUP, rec.kind)
        assertEquals(HomeTarget.SETTINGS, rec.target)
        assertEquals("Select a semester.", rec.text)
    }

    @Test
    fun dueCards_winOverEverything() {
        val rec = RecommendationEngine.recommend(
            RecommendationInput(
                scopeValid = true, dueTotal = 2,
                signals = listOf(observedSignal()),
                hasInProgressReview = true, focus = quizFocus, hasMaterial = true
            )
        )
        assertEquals(RecommendationKind.DUE_REVIEW, rec.kind)
        assertEquals(HomeTarget.CARDS, rec.target)
        assertEquals("Review 2 due cards first.", rec.text)
    }

    @Test
    fun observedWeakness_beatsResume() {
        val rec = RecommendationEngine.recommend(
            RecommendationInput(
                scopeValid = true,
                signals = listOf(observedSignal()),
                hasInProgressReview = true, focus = quizFocus, hasMaterial = true
            )
        )
        assertEquals(RecommendationKind.WEAKNESS_OBSERVED, rec.kind)
        assertEquals(HomeTarget.CARDS, rec.target)
        assertEquals("Focus: cell-biology.pdf.", rec.text)
        assertTrue(rec.evidence.contains("4 incorrect answers across 2 sessions"))
    }

    @Test
    fun resumeReview_beatsPossible() {
        val rec = RecommendationEngine.recommend(
            RecommendationInput(
                scopeValid = true,
                signals = listOf(possibleSignal()),
                hasInProgressReview = true
            )
        )
        assertEquals(RecommendationKind.RESUME, rec.kind)
        assertEquals("Resume your review session.", rec.text)
    }

    @Test
    fun resumeQuiz_andListenerSession() {
        val quiz = RecommendationEngine.recommend(
            RecommendationInput(scopeValid = true, hasInProgressQuiz = true)
        )
        assertEquals(RecommendationKind.RESUME, quiz.kind)
        assertEquals(HomeTarget.QUIZ, quiz.target)

        val listen = RecommendationEngine.recommend(
            RecommendationInput(scopeValid = true, hasLiveListenerSession = true)
        )
        assertEquals(RecommendationKind.RESUME, listen.kind)
        assertEquals(HomeTarget.LISTEN, listen.target)
    }

    @Test
    fun possibleWeakness_isCautious() {
        val rec = RecommendationEngine.recommend(
            RecommendationInput(scopeValid = true, signals = listOf(possibleSignal()))
        )
        assertEquals(RecommendationKind.WEAKNESS_POSSIBLE, rec.kind)
        assertEquals(HomeTarget.CARDS, rec.target)
        assertTrue(rec.evidence.contains("More evidence is needed"))
    }

    @Test
    fun improving_isNeverRecommendedAsWeakness() {
        val rec = RecommendationEngine.recommend(
            RecommendationInput(
                scopeValid = true,
                signals = listOf(improvingSignal()),
                focus = quizFocus, hasMaterial = true
            )
        )
        assertTrue(rec.kind != RecommendationKind.WEAKNESS_OBSERVED)
        assertTrue(rec.kind != RecommendationKind.WEAKNESS_POSSIBLE)
        assertEquals(RecommendationKind.RESUME, rec.kind)
    }

    @Test
    fun pendingTranscripts_goToListener() {
        val rec = RecommendationEngine.recommend(
            RecommendationInput(scopeValid = true, pendingTranscripts = 2, hasMaterial = true)
        )
        assertEquals(RecommendationKind.TRANSCRIPTS, rec.kind)
        assertEquals(HomeTarget.LISTEN, rec.target)
    }

    @Test
    fun focusContinues_whenNothingStronger() {
        val rec = RecommendationEngine.recommend(
            RecommendationInput(scopeValid = true, focus = quizFocus, hasMaterial = true)
        )
        assertEquals(RecommendationKind.RESUME, rec.kind)
        assertEquals("Continue: Quiz practice.", rec.text)
        assertEquals(HomeTarget.QUIZ, rec.target)
    }

    @Test
    fun untouchedMaterial_exploresHierarchy() {
        val rec = RecommendationEngine.recommend(
            RecommendationInput(scopeValid = true, hasMaterial = true)
        )
        assertEquals(RecommendationKind.MATERIAL, rec.kind)
        assertEquals(HomeTarget.ACADEMIC, rec.target)
    }

    @Test
    fun emptyScope_fallsBackToImport() {
        val rec = RecommendationEngine.recommend(RecommendationInput(scopeValid = true))
        assertEquals(RecommendationKind.SETUP, rec.kind)
        assertEquals("Import your first semester ZIP.", rec.text)
    }

    @Test
    fun equalPriority_tieBreaksDeterministically() {
        fun input() = RecommendationInput(
            scopeValid = true,
            signals = listOf(observedSignal("b.pdf"), observedSignal("a.pdf"))
        )
        val first = RecommendationEngine.recommend(input())
        val second = RecommendationEngine.recommend(input())
        assertEquals(first, second)
        assertEquals("Focus: a.pdf.", first.text)
    }

    @Test
    fun sameStateTwice_isIdentical() {
        val input = RecommendationInput(
            scopeValid = true, dueTotal = 1,
            signals = listOf(possibleSignal()),
            pendingTranscripts = 3, focus = quizFocus, hasMaterial = true
        )
        assertEquals(
            RecommendationEngine.recommend(input),
            RecommendationEngine.recommend(input)
        )
    }
}
