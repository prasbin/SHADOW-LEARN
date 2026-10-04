package com.prasbin.shadowlearn.data.intelligence

import com.prasbin.shadowlearn.data.home.FocusState
import com.prasbin.shadowlearn.data.home.HomeTarget
import com.prasbin.shadowlearn.data.home.Recommendation

/**
 * I2 recommendation priority (contract §7). Internal order only — never
 * shown to the student as a number (no scores, §19).
 */
enum class RecommendationKind {
    DUE_REVIEW,
    WEAKNESS_OBSERVED,
    RESUME,
    WEAKNESS_POSSIBLE,
    TRANSCRIPTS,
    MATERIAL,
    SETUP
}

/** Everything the priority rules may read. All inputs are existing state. */
data class RecommendationInput(
    val scopeValid: Boolean,
    val dueTotal: Int = 0,
    /** Pre-ranked I1 signals (strongest first); IMPROVING never recommends. */
    val signals: List<WeaknessSignal> = emptyList(),
    val hasInProgressReview: Boolean = false,
    val hasInProgressQuiz: Boolean = false,
    val hasLiveListenerSession: Boolean = false,
    val pendingTranscripts: Int = 0,
    val focus: FocusState? = null,
    /** Imported files exist in the semester. */
    val hasMaterial: Boolean = false
)

/**
 * I2 grounded recommendations — the ONE source behind Home's System
 * Recommendation (contract §5). PURE and deterministic: same input ⇒
 * same output. Priority: due review → observed weakness → resume/continue
 * → possible weakness → transcripts → untouched material → setup guidance.
 * Every branch names WHAT + WHY + evidence and a reachable ACTION.
 */
object RecommendationEngine {

    fun recommend(input: RecommendationInput): Recommendation {
        // Canonical order regardless of caller list order (determinism).
        val ordered = input.signals.sortedWith(WeaknessEngine.SIGNAL_ORDER)
        if (!input.scopeValid) {
            return Recommendation(
                "Select a semester.",
                "Choose your year and semester in Settings to begin.",
                HomeTarget.SETTINGS,
                RecommendationKind.SETUP
            )
        }
        if (input.dueTotal > 0) {
            return Recommendation(
                "Review ${input.dueTotal} due card${if (input.dueTotal == 1) "" else "s"} first.",
                "Spaced repetition is time-sensitive: ${input.dueTotal} waiting.",
                HomeTarget.CARDS,
                RecommendationKind.DUE_REVIEW
            )
        }
        ordered.firstOrNull { it.status == WeaknessStatus.OBSERVED }?.let { signal ->
            return Recommendation(
                "Focus: ${signal.fileName}.",
                "Repeated difficulty detected: ${signal.evidenceSummary()}.",
                HomeTarget.CARDS,
                RecommendationKind.WEAKNESS_OBSERVED
            )
        }
        if (input.hasInProgressReview) {
            return Recommendation(
                "Resume your review session.",
                "An unfinished session is waiting.",
                HomeTarget.CARDS,
                RecommendationKind.RESUME
            )
        }
        if (input.hasInProgressQuiz) {
            return Recommendation(
                "Resume your quiz.",
                "An unfinished quiz is waiting.",
                HomeTarget.QUIZ,
                RecommendationKind.RESUME
            )
        }
        if (input.hasLiveListenerSession) {
            return Recommendation(
                "Continue lecture capture.",
                "A recording session is still open.",
                HomeTarget.LISTEN,
                RecommendationKind.RESUME
            )
        }
        ordered.firstOrNull { it.status == WeaknessStatus.POSSIBLE }?.let { signal ->
            return Recommendation(
                "Review ${signal.fileName}.",
                "Recent mistakes detected: ${signal.evidenceSummary()}. " +
                    "More evidence is needed to call this an observed weakness.",
                HomeTarget.CARDS,
                RecommendationKind.WEAKNESS_POSSIBLE
            )
        }
        if (input.pendingTranscripts > 0) {
            return Recommendation(
                "Transcribe ${input.pendingTranscripts} segment${if (input.pendingTranscripts == 1) "" else "s"}.",
                "Pending transcripts can't feed review yet.",
                HomeTarget.LISTEN,
                RecommendationKind.TRANSCRIPTS
            )
        }
        input.focus?.let { focus ->
            return Recommendation(
                "Continue: ${focus.headline}.",
                focus.detail,
                focus.target,
                RecommendationKind.RESUME
            )
        }
        if (input.hasMaterial) {
            return Recommendation(
                "Explore current material.",
                "New academic material is available for this semester.",
                HomeTarget.ACADEMIC,
                RecommendationKind.MATERIAL
            )
        }
        return Recommendation(
            "Import your first semester ZIP.",
            "No academic scope configured yet.",
            HomeTarget.SETTINGS,
            RecommendationKind.SETUP
        )
    }
}
