package com.prasbin.shadowlearn.data.progression

/**
 * Phase 12 derived academic progress — PURE, deterministic, integer-only.
 *
 * Formula (documented, no magic): four milestones, 25 points each, max 100.
 *
 * - import:     >= 1 academic file persisted          → 25
 * - extraction: >= 1 extracted document chunk         → 25
 * - quiz:       >= 1 completed quiz session           → 25
 * - review:     >= 1 flashcard review event           → 25
 *
 * Binary milestones are deliberate: any graduated thresholds would be
 * invented cutoffs. Counts beyond the first row never add more — so
 * repeated activity can never inflate progress (no double-counting).
 * Empty database → 0% (honest zero, matching the old placeholder only
 * when nothing exists).
 */
object ProgressCalculator {

    /** Points awarded per reached milestone. */
    const val POINTS_PER_MILESTONE = 25

    /** Max progress (all four milestones reached). */
    const val MAX_PROGRESS = 100

    fun progressOf(
        hasImport: Boolean,
        hasExtraction: Boolean,
        hasQuiz: Boolean,
        hasReview: Boolean
    ): Int {
        var progress = 0
        if (hasImport) progress += POINTS_PER_MILESTONE
        if (hasExtraction) progress += POINTS_PER_MILESTONE
        if (hasQuiz) progress += POINTS_PER_MILESTONE
        if (hasReview) progress += POINTS_PER_MILESTONE
        return progress.coerceIn(0, MAX_PROGRESS)
    }

    /**
     * Honest one-line basis, e.g. "import · quiz (2 of 4)" or "" when
     * nothing is reached. The caller decides whether to show it.
     */
    fun basisOf(
        hasImport: Boolean,
        hasExtraction: Boolean,
        hasQuiz: Boolean,
        hasReview: Boolean
    ): String {
        val reached = buildList {
            if (hasImport) add("import")
            if (hasExtraction) add("extraction")
            if (hasQuiz) add("quiz")
            if (hasReview) add("review")
        }
        if (reached.isEmpty()) return ""
        return reached.joinToString(" · ") + " (${reached.size} of 4)"
    }
}
