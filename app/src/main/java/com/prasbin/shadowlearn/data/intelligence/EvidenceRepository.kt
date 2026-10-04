package com.prasbin.shadowlearn.data.intelligence

import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.ExtractionDao
import com.prasbin.shadowlearn.data.db.FlashcardDao
import com.prasbin.shadowlearn.data.db.QuizDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * I1 evidence aggregation — the single source that Home and Status both
 * read (contract §16). Gathers bounded, semester-scoped row projections
 * and resolves them to file identity WITHOUT parsing provenance strings:
 * quiz questions carry direct file/chunk ids; AGAIN events resolve through
 * card chunk → owning file. Unresolvable sources keep their snapshot name
 * and are flagged dangling (contract: capped POSSIBLE, never promoted).
 *
 * Stateless and side-effect free; all interpretation lives in
 * [WeaknessEngine]. No tables, no migrations.
 */
class EvidenceRepository(
    private val academicDao: AcademicDao,
    private val extractionDao: ExtractionDao,
    private val quizDao: QuizDao,
    private val flashcardDao: FlashcardDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Single shared I1 read: gathers mistake/correct/AGAIN evidence for
     * one semester, resolves file scopes, and runs the pure engine.
     * Home and Status both consume this — never separate calculations.
     */
    suspend fun weaknessSignals(semesterId: Long, now: Long): List<WeaknessSignal> =
        withContext(dispatcher) {
            val mistakes = quizDao.wrongAnswerEvents(semesterId, EVIDENCE_LIMIT).map {
                MistakeEvidence(
                    questionId = it.questionId,
                    sessionId = it.sessionId,
                    academicFileId = it.academicFileId.takeIf { id -> id > 0 },
                    chunkId = it.chunkId.takeIf { id -> id > 0 },
                    srcFileName = it.srcFileName,
                    observedAt = it.observedAt
                )
            }
            val corrects = quizDao.correctAnswerEvents(semesterId, EVIDENCE_LIMIT).map {
                CorrectEvidence(
                    questionId = it.questionId,
                    sessionId = it.sessionId,
                    academicFileId = it.academicFileId.takeIf { id -> id > 0 },
                    srcFileName = it.srcFileName,
                    observedAt = it.observedAt
                )
            }
            val agains = flashcardDao.againEvents(semesterId, EVIDENCE_LIMIT).map { row ->
                val fileId = row.sourceChunkId
                    ?.let { runCatching { extractionDao.chunk(it) }.getOrNull() }
                    ?.academicFileId
                AgainEvidence(
                    eventId = row.eventId,
                    fileId = fileId,
                    srcLabel = row.sourceLabel ?: "Unknown source",
                    reviewedAt = row.reviewedAt
                )
            }
            val fileIds = (mistakes.mapNotNull { it.academicFileId } +
                agains.mapNotNull { it.fileId }).toSet()
            val scopes = fileIds.associate { id -> "file:$id" to scopeForDirect(id) }
            WeaknessEngine.evaluate(mistakes, corrects, agains, scopes, now).map { signal ->
                // I5: targeted practice availability — owning file exists AND
                // yields indexed chunks. One bounded COUNT per signal file.
                val practicable = signal.fileId?.let { id ->
                    runCatching { extractionDao.chunkCountForFile(id) > 0 }.getOrDefault(false)
                } ?: false
                signal.copy(practicable = practicable)
            }
        }
    private suspend fun scopeForDirect(fileId: Long): SourceScope? {
        val file = academicDao.file(fileId) ?: return null
        val week = academicDao.week(file.weekId)
        val module = week?.let { academicDao.module(it.moduleId) }
        return SourceScope(
            fileId = file.id,
            fileName = file.fileName,
            weekId = week?.id,
            weekLabel = week?.let { "Week ${it.weekNumber}" },
            moduleId = module?.id,
            moduleName = module?.name
        )
    }

    companion object {
        /** Per-query bound so evidence reads never materialize histories. */
        const val EVIDENCE_LIMIT = 500
    }
}
