package com.prasbin.shadowlearn.data.ingest

import android.content.Context
import android.net.Uri
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.DocumentChunk
import com.prasbin.shadowlearn.data.db.ExtractionDao
import com.prasbin.shadowlearn.data.db.ExtractionMeta
import com.prasbin.shadowlearn.data.db.ExtractionStatus
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.extract.ExtractionException
import com.prasbin.shadowlearn.data.extract.ExtractionFormat
import com.prasbin.shadowlearn.data.extract.TextChunker
import com.prasbin.shadowlearn.data.search.FtsIndex
import com.prasbin.shadowlearn.data.search.RoomBackedSqlExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Result of one extraction pass over a semester's corpus. */
data class ExtractionSummary(
    val totalFiles: Int,
    val extracted: Int,
    val reused: Int,
    /** Unchanged files with a matching EXTRACTED signature (no re-extraction). */
    val skipped: Int,
    val failed: Int,
    /** Chunks mirrored into the FTS index during this pass. */
    val indexedChunks: Int,
    val elapsedMs: Long,
    val errors: List<String>
)

/** Observable extraction progression for the Academic screen. */
sealed interface ExtractionState {
    data object Idle : ExtractionState
    data class Progress(val currentEntry: String, val done: Int, val total: Int) : ExtractionState
    data class Done(val summary: ExtractionSummary) : ExtractionState
}

/**
 * Phase 4 extraction orchestrator.
 *
 * Incremental contract (mirrors Phase 3 reconciliation, see ARCHITECTURE):
 * - NEW / CHANGED / legacy-adopted files → extract (fresh). CHANGED always
 *   drops the stale chunks + FTS mirror first, even if the new extraction
 *   fails.
 * - UNCHANGED with an EXTRACTED meta whose sha256 equals the current file
 *   sha → skipped (proven no-op, never re-extracted "unnecessarily").
 * - UNCHANGED with a FAILED meta of the same sha → retried (transient
 *   failures are isolated, not papered over).
 * - DUPLICATE content elsewhere in the semester → reuse the sibling's
 *   extracted representation (clone chunks + mirror them into FTS) instead
 *   of re-extracting.
 *
 * Every file is processed with isolation: one bad file never fails the
 * pass; its meta records FAILED + reason. FTS rows are cleaned together
 * with chunk deletion (virtual tables cannot hold foreign keys).
 */
class ExtractionRepository(
    private val context: Context,
    private val db: ShadowLearnDatabase,
    private val dao: ExtractionDao,
    private val academicDao: AcademicDao,
    private val fts: FtsIndex,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private val _state = MutableStateFlow<ExtractionState>(ExtractionState.Idle)
    val state: StateFlow<ExtractionState> = _state

    companion object {
        /** Production FTS index over the app database connection. */
        fun ftsIndex(db: ShadowLearnDatabase): FtsIndex =
            FtsIndex(RoomBackedSqlExecutor(db.openHelper.writableDatabase))

        const val MAX_ERRORS = 50
    }

    suspend fun processForSemester(semesterId: Long): ExtractionSummary {
        _state.value = ExtractionState.Idle
        return withContext(ioDispatcher) {
            val files = try {
                dao.filesOfSemester(semesterId)
            } catch (e: Exception) {
                _state.value = ExtractionState.Done(emptySummary(0, listOf("Query failed: ${e.message}")))
                return@withContext emptySummary(0, listOf("Query failed: ${e.message}"))
            }
            processFiles(files, semesterId)
        }
    }

    private suspend fun processFiles(files: List<AcademicFile>, semesterId: Long): ExtractionSummary {
        val start = now()
        fts.ensureSchema()
        var extracted = 0
        var reused = 0
        var skipped = 0
        var failed = 0
        var indexedChunks = 0
        val errors = mutableListOf<String>()

        files.forEachIndexed { idx, file ->
            _state.value = ExtractionState.Progress(file.fileName, idx, files.size)
            try {
                val outcome = processOne(file, semesterId)
                when (outcome.kind) {
                    OutcomeKind.SKIPPED -> skipped++
                    OutcomeKind.REUSED -> { reused++; indexedChunks += outcome.chunkCount }
                    OutcomeKind.EXTRACTED -> { extracted++; indexedChunks += outcome.chunkCount }
                    OutcomeKind.FAILED -> {
                        failed++
                        errors += "${file.relativePath.ifEmpty { file.fileName }}: ${outcome.message}"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                errors += "${file.fileName}: ${e.message}"
            }
        }

        val elapsed = now() - start
        val summary = ExtractionSummary(
            totalFiles = files.size,
            extracted = extracted,
            reused = reused,
            skipped = skipped,
            failed = failed,
            indexedChunks = indexedChunks,
            elapsedMs = elapsed,
            errors = errors.take(MAX_ERRORS)
        )
        _state.value = ExtractionState.Done(summary)
        return summary
    }

    private enum class OutcomeKind { SKIPPED, REUSED, EXTRACTED, FAILED }

    private data class Outcome(val kind: OutcomeKind, val chunkCount: Int, val message: String = "")

    private suspend fun processOne(file: AcademicFile, semesterId: Long): Outcome {
        val meta = dao.getMeta(file.id)
        if (meta?.status == ExtractionStatus.EXTRACTED.name && meta.sha256 == file.sha256) {
            return Outcome(OutcomeKind.SKIPPED, 0)
        }

        // Any previously persisted representation of this file (for a superseded
        // or older content) is dropped first, keeping chunks + FTS consistent.
        val stale = dao.chunksForFile(file.id)
        if (stale.isNotEmpty()) {
            fts.deleteChunks(stale.map { it.id })
        }

        // Duplicate reuse: same semester, same sha, already EXTRACTED elsewhere.
        val sibling = dao.findExtractedSibling(semesterId, file.sha256, file.id)
        if (sibling != null) {
            return reuseFrom(file, sibling)
        }

        return try {
            extractInto(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailed(file, e)
            Outcome(OutcomeKind.FAILED, 0, e.message ?: "Unknown error")
        }
    }

    private suspend fun reuseFrom(file: AcademicFile, sibling: ExtractionMeta): Outcome {
        val t = now()
        val source = dao.chunksForFile(sibling.academicFileId)
        val cloned = source.map { c ->
            DocumentChunk(
                academicFileId = file.id,
                chunkIndex = c.chunkIndex,
                pageNumber = c.pageNumber,
                text = c.text,
                charCount = c.charCount,
                createdAt = t
            )
        }
        val ids = dao.insertChunks(cloned)
        fts.insertAll(ids.zip(cloned.map { it.text }))
        dao.upsertMeta(
            ExtractionMeta(
                academicFileId = file.id,
                sha256 = file.sha256,
                status = ExtractionStatus.EXTRACTED.name,
                format = sibling.format,
                charCount = cloned.sumOf { it.charCount.toLong() },
                chunkCount = cloned.size,
                error = null,
                startedAt = t,
                completedAt = t
            )
        )
        academicDao.updateFile(file.copy(indexed = true))
        return Outcome(OutcomeKind.REUSED, cloned.size)
    }

    private suspend fun extractInto(file: AcademicFile): Outcome {
        val started = now()
        val bytes = readStoredBytes(file)
        val kind = ExtractionFormat.kindFor(file.fileType)
        if (kind == ExtractionFormat.Kind.UNSUPPORTED) {
            throw ExtractionException("Legacy/unsupported binary format (${file.fileType}): not supported.")
        }
        val document = ExtractionFormat.extract(kind, bytes)
        val pieces = TextChunker.chunk(document)
        val chunkRows = pieces.map {
            DocumentChunk(
                academicFileId = file.id,
                chunkIndex = it.index,
                pageNumber = it.pageNumber,
                text = it.text,
                charCount = it.text.length,
                createdAt = now()
            )
        }
        val completed = now()
        val meta = ExtractionMeta(
            academicFileId = file.id,
            sha256 = file.sha256,
            status = ExtractionStatus.EXTRACTED.name,
            format = ExtractionFormat.extractorName(kind),
            charCount = chunkRows.sumOf { it.charCount.toLong() },
            chunkCount = chunkRows.size,
            error = null,
            startedAt = started,
            completedAt = completed
        )
        val ids = dao.replaceExtraction(file.id, chunkRows, meta)
        if (ids.isNotEmpty()) {
            fts.insertAll(ids.zip(chunkRows.map { it.text }))
        }
        academicDao.updateFile(file.copy(indexed = true))
        return Outcome(OutcomeKind.EXTRACTED, chunkRows.size)
    }

    private suspend fun recordFailed(file: AcademicFile, e: Exception) {
        val t = now()
        dao.replaceExtraction(
            file.id,
            emptyList(),
            ExtractionMeta(
                academicFileId = file.id,
                sha256 = file.sha256,
                status = ExtractionStatus.FAILED.name,
                format = "unknown",
                charCount = 0,
                chunkCount = 0,
                error = e.message ?: "Unknown error",
                startedAt = t,
                completedAt = t
            )
        )
        academicDao.updateFile(file.copy(indexed = false))
    }

    private fun readStoredBytes(file: AcademicFile): ByteArray {
        val path = file.filePath
        val bytes = when {
            path.startsWith("content://") -> {
                context.contentResolver.openInputStream(Uri.parse(path))?.use { it.readBytes() }
                    ?: throw ExtractionException("Cannot open stored content: $path")
            }
            else -> {
                val f = File(path)
                if (!f.exists()) throw ExtractionException("Missing stored file: $path")
                f.inputStream().use { it.readBytes() }
            }
        }
        if (bytes.size > ExtractionFormat.MAX_EXTRACT_BYTES) {
            throw ExtractionException(
                "File too large for extraction (${bytes.size} > ${ExtractionFormat.MAX_EXTRACT_BYTES} bytes)."
            )
        }
        return bytes
    }

    private fun emptySummary(total: Int, errors: List<String>) =
        ExtractionSummary(totalFiles = total, extracted = 0, reused = 0, skipped = 0, failed = 0, indexedChunks = 0, elapsedMs = 0, errors = errors)
}