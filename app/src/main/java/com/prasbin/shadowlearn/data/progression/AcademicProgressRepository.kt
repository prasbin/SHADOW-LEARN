package com.prasbin.shadowlearn.data.progression

import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.ExtractionDao
import com.prasbin.shadowlearn.data.db.FlashcardDao
import com.prasbin.shadowlearn.data.db.QuizDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/**
 * Phase 12 academic-progress snapshot — all values DERIVED, never stored.
 *
 * @property percent 0..100 from [ProgressCalculator.progressOf].
 * @property basis honest one-line basis, "" when nothing is reached.
 */
data class AcademicProgress(
    val percent: Int = 0,
    val basis: String = ""
)

/**
 * The four binary milestones behind [AcademicProgress], exposed so the
 * Academic Status screen can explain the basis without inventing
 * counts. Same row signals, same semantics — never a new formula.
 */
data class ProgressMilestones(
    val hasImport: Boolean = false,
    val hasExtraction: Boolean = false,
    val hasQuiz: Boolean = false,
    val hasReview: Boolean = false
)

/**
 * The ONE authoritative academic-progress calculation path. Combines four
 * existing row-count signals (files, chunks, completed quiz sessions,
 * review events) into the dashboard Progress %. The UI never queries Room
 * for progress directly.
 *
 * No new tables and no migration: everything is derived from existing rows.
 */
class AcademicProgressRepository(
    private val academicDao: AcademicDao,
    private val extractionDao: ExtractionDao,
    private val quizDao: QuizDao,
    private val flashcardDao: FlashcardDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /** Reactive snapshot that recomputes whenever any source table changes. */
    fun observe(): Flow<AcademicProgress> = combine(
        academicDao.observeFileCount(),
        extractionDao.observeChunkCount(),
        quizDao.observeSummary(),
        flashcardDao.observeReviewActivity()
    ) { files, chunks, summary, activity ->
        compute(
            hasImport = files > 0,
            hasExtraction = chunks > 0,
            hasQuiz = summary.sessions > 0,
            hasReview = activity.isNotEmpty()
        )
    }

    /** One-shot snapshot (used by tests and non-reactive callers). */
    suspend fun current(): AcademicProgress = withContext(dispatcher) {
        compute(
            hasImport = academicDao.getFileCount() > 0,
            hasExtraction = extractionDao.totalChunkCount() > 0,
            hasQuiz = quizDao.completedCount() > 0,
            hasReview = flashcardDao.reviewActivity().isNotEmpty()
        )
    }

    /** Reactive milestone flags — the same four signals behind [observe]. */
    fun observeMilestones(): Flow<ProgressMilestones> = combine(
        academicDao.observeFileCount(),
        extractionDao.observeChunkCount(),
        quizDao.observeSummary(),
        flashcardDao.observeReviewActivity()
    ) { files, chunks, summary, activity ->
        ProgressMilestones(
            hasImport = files > 0,
            hasExtraction = chunks > 0,
            hasQuiz = summary.sessions > 0,
            hasReview = activity.isNotEmpty()
        )
    }

    private fun compute(
        hasImport: Boolean,
        hasExtraction: Boolean,
        hasQuiz: Boolean,
        hasReview: Boolean
    ): AcademicProgress = AcademicProgress(
        percent = ProgressCalculator.progressOf(hasImport, hasExtraction, hasQuiz, hasReview),
        basis = ProgressCalculator.basisOf(hasImport, hasExtraction, hasQuiz, hasReview)
    )
}
