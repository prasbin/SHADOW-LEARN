package com.prasbin.shadowlearn.data.home

import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.ExtractionDao
import com.prasbin.shadowlearn.data.db.FlashcardDao
import com.prasbin.shadowlearn.data.db.ListenerDao
import com.prasbin.shadowlearn.data.db.ListenerSession
import com.prasbin.shadowlearn.data.db.QuizDao
import com.prasbin.shadowlearn.data.db.QuizSession
import com.prasbin.shadowlearn.data.db.ReviewSession
import com.prasbin.shadowlearn.data.intelligence.EvidenceRepository
import com.prasbin.shadowlearn.data.intelligence.WeaknessStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Navigation target for a Home row (mapped to Routes by the screen). */
enum class HomeTarget { CARDS, QUIZ, LISTEN, ACADEMIC, SETTINGS }

/** One actionable objective derived from real review signals. */
data class HomeObjective(
    val kind: ObjectiveKind,
    val title: String,
    val detail: String,
    val count: Int,
    val target: HomeTarget
)

enum class ObjectiveKind {
    DUE_REVIEW,
    RESUME_REVIEW,
    RESUME_QUIZ,
    QUIZ_MISTAKES,
    PENDING_TRANSCRIPTS
}

/** One file-level weak spot: never a concept label (those don't exist yet). */
data class WeakArea(
    val label: String,
    val detail: String,
    val target: HomeTarget,
    /** I1 signal status driving the tint: OBSERVED / IMPROVING / POSSIBLE. */
    val status: String = WeaknessStatus.POSSIBLE.name,
    /** Owning week for OPEN SOURCE routing (else null = no action shown). */
    val sourceWeekId: Long? = null,
    /**
     * Owning file for targeted PRACTICE (else null = no action shown).
     * Set only when the file exists with indexed chunks (I5 gate).
     */
    val practiceFileId: Long? = null,
    /**
     * I6 related materials for this source (max 3, ranked). Empty = no
     * RELATED MATERIAL action (honest absence, never a dead button).
     */
    val relatedMaterials: List<com.prasbin.shadowlearn.data.intelligence.RelatedMaterial> = emptyList(),
    /** Grounded explanation when the source resolved (else null = no EXPLAIN). */
    val explanation: com.prasbin.shadowlearn.data.intelligence.GroundedExplanation? = null
)

/** One human-readable activity feed row. */
data class ActivityEvent(
    val text: String,
    val timestamp: Long,
    val target: HomeTarget
)

/** Resumed material, when derivable from real activity — never invented. */
data class FocusState(
    val headline: String,
    val detail: String,
    val target: HomeTarget
)

/** Deterministic recommendation: first matching rule wins, with evidence. */
data class Recommendation(
    val text: String,
    val evidence: String,
    val target: HomeTarget,
    val kind: com.prasbin.shadowlearn.data.intelligence.RecommendationKind,
    /** Grounded source file name for weakness recommendations (else null). */
    val sourceFileName: String? = null,
    /** Verbatim indexed excerpt backing the recommendation (else null). */
    val sourceExcerpt: String? = null,
    /** Owning week for OPEN SOURCE routing (else null = no action shown). */
    val sourceWeekId: Long? = null,
    /**
     * Owning file for targeted PRACTICE (else null = no action shown).
     * Set only when the file exists with indexed chunks (I5 gate).
     */
    val practiceFileId: Long? = null,
    /** Grounded explanation when the source resolved (else null = no EXPLAIN). */
    val explanation: com.prasbin.shadowlearn.data.intelligence.GroundedExplanation? = null
)

/**
 * Phase 12+ system-home aggregation — the ONE path behind the command
 * center. Every value derives from existing persisted rows; empty inputs
 * yield honest empty states, never fabricated content.
 *
 * Queries stay bounded (mistake pool capped, recent sessions capped) so
 * Home never materializes large histories.
 */
class SystemHomeRepository(
    private val academicDao: AcademicDao,
    private val extractionDao: ExtractionDao,
    private val quizDao: QuizDao,
    private val flashcardDao: FlashcardDao,
    private val listenerDao: ListenerDao,
    private val evidence: EvidenceRepository,
    private val retrieval: com.prasbin.shadowlearn.data.intelligence.GroundedRetrievalRepository,
    private val relationships: com.prasbin.shadowlearn.data.intelligence.RelationshipRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    data class HomeSnapshot(
        val objectives: List<HomeObjective> = emptyList(),
        val weakAreas: List<WeakArea> = emptyList(),
        val activity: List<ActivityEvent> = emptyList(),
        val focus: FocusState? = null,
        val recommendation: Recommendation =
            Recommendation(
                "Import your first semester ZIP.",
                "No academic scope configured yet.",
                HomeTarget.SETTINGS,
                com.prasbin.shadowlearn.data.intelligence.RecommendationKind.SETUP
            )
    )

    /** Full snapshot for [semesterId] at [now]. */
    suspend fun snapshot(semesterId: Long, now: Long): HomeSnapshot =
        withContext(dispatcher) {
            val decks = flashcardDao.decksOfSemester(semesterId)
            var dueTotal = 0
            var inProgressDeck: Long? = null
            for (deck in decks) {
                dueTotal += flashcardDao.dueCount(deck.id, now)
                if (inProgressDeck == null &&
                    flashcardDao.latestInProgress(deck.id) != null
                ) {
                    inProgressDeck = deck.id
                }
            }
            val mistakes = flashcardDao.mistakeQuestionsOfSemester(semesterId, MISTAKE_LIMIT)
            val inProgressQuiz = quizDao.latestInProgress()
            val listenerSessions = listenerDao.sessionsOfSemester(semesterId)
            var pendingTranscripts = 0
            for (s in listenerSessions) {
                pendingTranscripts += listenerDao.pendingSegmentCount(s.id)
            }

            val objectives = buildList {
                if (dueTotal > 0) add(
                    HomeObjective(
                        ObjectiveKind.DUE_REVIEW,
                        "Review $dueTotal due card${if (dueTotal == 1) "" else "s"}",
                        "Spaced repetition is time-sensitive.",
                        dueTotal, HomeTarget.CARDS
                    )
                )
                if (inProgressDeck != null) add(
                    HomeObjective(
                        ObjectiveKind.RESUME_REVIEW,
                        "Resume review session",
                        "An unfinished session is waiting.",
                        1, HomeTarget.CARDS
                    )
                )
                if (inProgressQuiz != null) add(
                    HomeObjective(
                        ObjectiveKind.RESUME_QUIZ,
                        "Resume quiz",
                        "An unfinished quiz is waiting.",
                        1, HomeTarget.QUIZ
                    )
                )
                if (mistakes.isNotEmpty()) add(
                    HomeObjective(
                        ObjectiveKind.QUIZ_MISTAKES,
                        "Review ${mistakes.size} quiz mistake${if (mistakes.size == 1) "" else "s"}",
                        "Wrong answers become review cards.",
                        mistakes.size, HomeTarget.CARDS
                    )
                )
                if (pendingTranscripts > 0) add(
                    HomeObjective(
                        ObjectiveKind.PENDING_TRANSCRIPTS,
                        "Transcribe $pendingTranscripts segment${if (pendingTranscripts == 1) "" else "s"}",
                        "Pending transcripts can't feed review.",
                        pendingTranscripts, HomeTarget.LISTEN
                    )
                )
            }

            // I2: one shared engine result feeds weak areas + recommendation.
            val signals = evidence.weaknessSignals(semesterId, now)
            // I4: one shared explanations map feeds weak areas + recommendation.
            val explanations = retrieval.explainAll(
                semesterId,
                signals,
                "Explain the material associated with this weak area."
            )
            // I6: one shared related-materials map (same signals, same repo).
            val related = mutableMapOf<Long, List<com.prasbin.shadowlearn.data.intelligence.RelatedMaterial>>()
            for (signal in signals.take(4)) {
                val fileId = signal.fileId ?: continue
                if (related.containsKey(fileId)) continue
                related[fileId] = runCatching {
                    relationships.relatedFor(semesterId, fileId)
                }.getOrDefault(emptyList())
            }
            val weakAreas = buildWeakAreas(signals, explanations, related, now)
            val activity = buildActivity(semesterId)
            val focus = buildFocus(semesterId, academicDao)
            val recommendation = com.prasbin.shadowlearn.data.intelligence.RecommendationEngine.recommend(
                com.prasbin.shadowlearn.data.intelligence.RecommendationInput(
                    scopeValid = true,
                    dueTotal = dueTotal,
                    signals = signals,
                    hasInProgressReview = inProgressDeck != null,
                    hasInProgressQuiz = inProgressQuiz != null,
                    hasLiveListenerSession = listenerSessions.any {
                        it.status == ListenerSession.STATUS_RECORDING ||
                            it.status == ListenerSession.STATUS_PAUSED ||
                            it.status == ListenerSession.STATUS_INTERRUPTED
                    },
                    pendingTranscripts = pendingTranscripts,
                    focus = focus,
                    hasMaterial = extractionDao.filesOfSemester(semesterId).isNotEmpty()
                )
            )
            // I3: ground weakness recommendations with source + excerpt.
            // I4: attach the same shared explanation when the source resolved.
            val grounded = groundRecommendation(recommendation, signals, semesterId, explanations)
            HomeSnapshot(objectives, weakAreas, activity, focus, grounded)
        }

    /**
     * I3 grounding: for weakness recommendations, attach the source file,
     * one verbatim indexed excerpt when available, and the owning week for
     * OPEN SOURCE routing. Unresolvable sources keep the snapshot name
     * with no excerpt and no route (honest unavailable, never a dead end).
     */
    private suspend fun groundRecommendation(
        recommendation: Recommendation,
        signals: List<com.prasbin.shadowlearn.data.intelligence.WeaknessSignal>,
        semesterId: Long,
        explanations: Map<Long, com.prasbin.shadowlearn.data.intelligence.GroundedExplanation>
    ): Recommendation {
        val wanted = when (recommendation.kind) {
            com.prasbin.shadowlearn.data.intelligence.RecommendationKind.WEAKNESS_OBSERVED ->
                com.prasbin.shadowlearn.data.intelligence.WeaknessStatus.OBSERVED
            com.prasbin.shadowlearn.data.intelligence.RecommendationKind.WEAKNESS_POSSIBLE ->
                com.prasbin.shadowlearn.data.intelligence.WeaknessStatus.POSSIBLE
            else -> return recommendation
        }
        val signal = signals.firstOrNull { it.status == wanted } ?: return recommendation
        val fileId = signal.fileId ?: return recommendation.copy(sourceFileName = signal.fileName)
        val result = retrieval.retrieve(
            com.prasbin.shadowlearn.data.intelligence.RetrievalRequest(
                semesterId = semesterId, fileId = fileId, maxChunks = 1
            )
        )
        val base = when (result) {
            is com.prasbin.shadowlearn.data.intelligence.RetrievalResult.Retrieved -> recommendation.copy(
                sourceFileName = signal.fileName,
                sourceExcerpt = result.chunks.first().excerpt,
                sourceWeekId = signal.weekId,
                practiceFileId = if (signal.practicable) signal.fileId else null
            )
            else -> recommendation.copy(
                sourceFileName = signal.fileName,
                sourceWeekId = signal.weekId,
                practiceFileId = if (signal.practicable) signal.fileId else null
            )
        }
        // I4: same shared explanation object the weak-area row carries.
        return base.copy(explanation = explanations[fileId])
    }

    /**
     * I1 weak areas: shared engine output mapped to Home rows (max 4).
     * OBSERVED/IMPROVING/POSSIBLE all surface (POSSIBLE muted by the
     * screen); empty evidence ⇒ empty list (UNKNOWN is section absence).
     */
    private fun buildWeakAreas(
        signals: List<com.prasbin.shadowlearn.data.intelligence.WeaknessSignal>,
        explanations: Map<Long, com.prasbin.shadowlearn.data.intelligence.GroundedExplanation>,
        related: Map<Long, List<com.prasbin.shadowlearn.data.intelligence.RelatedMaterial>>,
        now: Long
    ): List<WeakArea> =
        signals.take(4).map { signal ->
            WeakArea(
                label = signal.homeLabel(),
                detail = "${signal.status.name} — ${signal.homeDetail(now)}",
                target = HomeTarget.CARDS,
                status = signal.status.name,
                sourceWeekId = signal.weekId,
                practiceFileId = if (signal.practicable) signal.fileId else null,
                relatedMaterials = signal.fileId?.let { related[it] } ?: emptyList(),
                explanation = signal.fileId?.let { explanations[it] }
            )
        }

    private suspend fun buildActivity(semesterId: Long): List<ActivityEvent> {
        val out = mutableListOf<ActivityEvent>()
        extractionDao.filesOfSemester(semesterId)
            .sortedByDescending { it.createdAt }.take(3)
            .forEach { out.add(ActivityEvent("Imported ${it.fileName}", it.createdAt, HomeTarget.ACADEMIC)) }
        quizDao.sessionsOfSemester(semesterId)
            .filter { it.status == QuizSession.STATUS_COMPLETED }
            .sortedByDescending { it.completedAt ?: it.startedAt }.take(2)
            .forEach {
                out.add(
                    ActivityEvent(
                        "Quiz completed ${it.correctCount}/${it.totalQuestions}",
                        it.completedAt ?: it.startedAt, HomeTarget.QUIZ
                    )
                )
            }
        for (deck in flashcardDao.decksOfSemester(semesterId)) {
            flashcardDao.reviewSessionsOfDeck(deck.id)
                .filter { it.status == ReviewSession.STATUS_COMPLETED }
                .sortedByDescending { it.completedAt ?: it.startedAt }.take(2)
                .forEach {
                    out.add(
                        ActivityEvent(
                            "Reviewed ${it.reviewedCount} cards",
                            it.completedAt ?: it.startedAt, HomeTarget.CARDS
                        )
                    )
                }
        }
        listenerDao.sessionsOfSemester(semesterId)
            .sortedByDescending { it.completedAt ?: it.startedAt }.take(2)
            .forEach {
                val verb = when (it.status) {
                    ListenerSession.STATUS_COMPLETED -> "Lecture recorded"
                    else -> "Lecture session ${it.status}"
                }
                out.add(ActivityEvent(verb, it.completedAt ?: it.startedAt, HomeTarget.LISTEN))
            }
        return out.sortedByDescending { it.timestamp }.take(8)
    }

    private suspend fun buildFocus(
        semesterId: Long,
        academicDao: AcademicDao
    ): FocusState? {
        data class Candidate(val at: Long, val build: suspend () -> FocusState?)
        val modulesById = academicDao.getModules(semesterId).associateBy { it.id }
        val weeksById = modulesById.keys.flatMap { academicDao.getWeeks(it) }
            .associateBy { it.id }
        val candidates = mutableListOf<Candidate>()
        val latestFile = extractionDao.filesOfSemester(semesterId)
            .maxByOrNull { it.createdAt }
        if (latestFile != null) {
            candidates.add(
                Candidate(latestFile.createdAt) {
                    val week = weeksById[latestFile.weekId]
                    val module = week?.let { modulesById[it.moduleId] }
                    FocusState(
                        headline = module?.name ?: "Imported material",
                        detail = if (week != null) {
                            "Week ${week.weekNumber} · ${latestFile.fileName}"
                        } else {
                            latestFile.fileName
                        },
                        target = HomeTarget.ACADEMIC
                    )
                }
            )
        }
        val latestQuiz = quizDao.sessionsOfSemester(semesterId)
            .filter { it.status == QuizSession.STATUS_COMPLETED }
            .maxByOrNull { it.completedAt ?: it.startedAt }
        if (latestQuiz != null) {
            candidates.add(
                Candidate(latestQuiz.completedAt ?: latestQuiz.startedAt) {
                    FocusState(
                        headline = "Quiz practice",
                        detail = "${latestQuiz.correctCount}/${latestQuiz.totalQuestions} correct last run",
                        target = HomeTarget.QUIZ
                    )
                }
            )
        }
        val latestReview = flashcardDao.decksOfSemester(semesterId)
            .flatMap { flashcardDao.reviewSessionsOfDeck(it.id) }
            .filter { it.status == ReviewSession.STATUS_COMPLETED }
            .maxByOrNull { it.completedAt ?: it.startedAt }
        if (latestReview != null) {
            candidates.add(
                Candidate(latestReview.completedAt ?: latestReview.startedAt) {
                    FocusState(
                        headline = "Spaced review",
                        detail = "${latestReview.reviewedCount} cards reviewed last session",
                        target = HomeTarget.CARDS
                    )
                }
            )
        }
        val latestListen = listenerDao.sessionsOfSemester(semesterId)
            .maxByOrNull { it.completedAt ?: it.startedAt }
        if (latestListen != null) {
            candidates.add(
                Candidate(latestListen.completedAt ?: latestListen.startedAt) {
                    FocusState(
                        headline = "Lecture capture",
                        detail = "Session ${latestListen.status}",
                        target = HomeTarget.LISTEN
                    )
                }
            )
        }
        return candidates.maxByOrNull { it.at }?.build()
    }


    companion object {
        private const val MISTAKE_LIMIT = 200
    }
}
