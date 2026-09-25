package com.prasbin.shadowlearn.data.quiz

/** Question families the rule-based generator can emit. */
enum class QuestionType(val storage: String) {
    MCQ("MCQ"),
    TRUE_FALSE("TRUE_FALSE"),
    FILL_BLANK("FILL_BLANK");

    companion object {
        fun from(storage: String): QuestionType =
            entries.firstOrNull { it.storage.equals(storage, ignoreCase = true) } ?: MCQ
    }
}

/** Verbatim source location a question was generated from (never invented). */
data class QuizSource(
    val chunkId: Long,
    val academicFileId: Long,
    val fileName: String,
    val fileType: String,
    val pageNumber: Long?,
    val excerpt: String
)

/** A fully-built question before it is persisted. */
data class GeneratedQuestion(
    val type: QuestionType,
    val prompt: String,
    /**
     * Choices the user picks from (always choice-based so the answer is
     * deterministic): 4 options for MCQ/fill-blank; `true`/`false` tokens
     * for true-false.
     */
    val options: List<String>,
    /** Canonical answer — one of [options]. */
    val correctAnswer: String,
    val source: QuizSource
)

/** A question inside a live/persisted session (answer state included). */
data class ActiveQuestion(
    val id: Long,
    val position: Int,
    val type: QuestionType,
    val prompt: String,
    val options: List<String>,
    val correctAnswer: String,
    val source: QuizSource,
    val userAnswer: String? = null,
    val isCorrect: Boolean? = null
)

/** The running quiz (title, prompt + options + answer state + citations). */
data class ActiveQuiz(
    val sessionId: Long,
    val semesterId: Long,
    val seed: Long,
    val questions: List<ActiveQuestion>
) {
    val total: Int get() = questions.size
    val answered: Int get() = questions.count { it.isCorrect != null }
    val correctSoFar: Int get() = questions.count { it.isCorrect == true }
    val currentIndex: Int
        get() = questions.indexOfFirst { it.isCorrect == null }
            .let { if (it < 0) total - 1 else it }
    val completed: Boolean get() = questions.isNotEmpty() && questions.all { it.isCorrect != null }
}

/** Final, persisted results of a completed session (honest totals). */
data class QuizResults(
    val sessionId: Long,
    val semesterId: Long,
    val total: Int,
    val correct: Int,
    val xp: Int,
    val streak: Int,
    val questions: List<ActiveQuestion>
) {
    val percent: Int get() = if (total == 0) 0 else (correct * 100) / total
}

/** Idle-screen aggregate read from real completed sessions. */
data class QuizSummary(
    val sessions: Int,
    val xp: Int,
    val bestScore: Int,
    val streak: Int,
    val lastCorrect: Int?,
    val lastTotal: Int?
)