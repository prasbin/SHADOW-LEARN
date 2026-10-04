package com.prasbin.shadowlearn.data.intelligence

import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.ExtractionDao
import com.prasbin.shadowlearn.data.search.SearchOutcome
import com.prasbin.shadowlearn.data.search.SearchRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * I3 grounded retrieval — the reusable layer beneath Search. Resolution
 * order is fixed: exact chunk → exact file → semester-scoped full-text.
 * Scope isolation happens inside the queries/walk, never as UI filtering.
 * Semester of a file is verified by FK walk (file→week→module→semester);
 * anything outside the requested semester is NO_MATCH, never substituted.
 *
 * Stateless, side-effect free, no tables, no migrations. Excerpts are
 * verbatim indexed text (full chunk text on exact paths; the existing
 * ExcerptGenerator output on the text path).
 */
class GroundedRetrievalRepository(
    private val academicDao: AcademicDao,
    private val extractionDao: ExtractionDao,
    private val search: SearchRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun retrieve(request: RetrievalRequest): RetrievalResult =
        withContext(dispatcher) {
            // R1/R2: exact chunk beats everything inferred.
            request.chunkId?.let { chunkId ->
                val chunk = extractionDao.chunk(chunkId)
                if (chunk != null) {
                    val scope = fileScope(chunk.academicFileId)
                    if (scope == null) return@withContext RetrievalResult.NoSource
                    if (scope.semesterId != request.semesterId) {
                        return@withContext RetrievalResult.NoMatch
                    }
                    return@withContext RetrievalResult.Retrieved(
                        listOf(
                            RetrievedChunk(
                                chunkId = chunk.id,
                                academicFileId = chunk.academicFileId,
                                fileName = scope.fileName,
                                excerpt = chunk.text,
                                pageNumber = chunk.pageNumber,
                                chunkIndex = chunk.chunkIndex,
                                weekLabel = scope.weekLabel,
                                moduleName = scope.moduleName
                            )
                        )
                    )
                }
                // Chunk gone: fall through to the file path when available.
            }
            // Exact file (direct id, or file behind a deleted chunk).
            val fileId = request.fileId
            if (fileId != null) {
                val scope = fileScope(fileId) ?: return@withContext RetrievalResult.NoSource
                if (scope.semesterId != request.semesterId) {
                    return@withContext RetrievalResult.NoMatch
                }
                val chunks = extractionDao.chunksForFile(fileId)
                if (chunks.isEmpty()) return@withContext RetrievalResult.SourceNotIndexed
                return@withContext RetrievalResult.Retrieved(
                    chunks.sortedBy { it.chunkIndex }
                        .take(request.maxChunks.coerceAtLeast(1))
                        .map {
                            RetrievedChunk(
                                chunkId = it.id,
                                academicFileId = fileId,
                                fileName = scope.fileName,
                                excerpt = it.text,
                                pageNumber = it.pageNumber,
                                chunkIndex = it.chunkIndex,
                                weekLabel = scope.weekLabel,
                                moduleName = scope.moduleName
                            )
                        }
                )
            }
            // R3: scoped lexical retrieval (honestly lexical, never semantic).
            // Reached only when no file was requested/resolved, so results
            // are already source-true; file requests take the exact path above.
            val query = request.query
            if (query.isNullOrBlank()) return@withContext RetrievalResult.NoSource
            when (val outcome = search.search(query, request.semesterId)) {
                is SearchOutcome.Failed -> RetrievalResult.NoMatch
                is SearchOutcome.Results -> {
                    val hits = outcome.results.take(request.maxChunks.coerceAtLeast(1))
                    if (hits.isEmpty()) RetrievalResult.NoMatch
                    else RetrievalResult.Retrieved(
                        hits.map {
                            RetrievedChunk(
                                chunkId = it.chunkId,
                                academicFileId = it.academicFileId,
                                fileName = it.fileName,
                                excerpt = it.excerpt,
                                pageNumber = it.pageNumber,
                                chunkIndex = it.chunkIndex,
                                weekLabel = it.weekNumber?.let { n -> "Week $n" },
                                moduleName = it.moduleName
                            )
                        }
                    )
                }
            }
        }

    private data class FileScope(
        val fileName: String,
        val semesterId: Long,
        val weekLabel: String?,
        val moduleName: String?
    )

    private suspend fun fileScope(fileId: Long): FileScope? {
        val file = academicDao.file(fileId) ?: return null
        val week = academicDao.week(file.weekId) ?: return FileScope(file.fileName, -1, null, null)
        val module = academicDao.module(week.moduleId) ?: return FileScope(file.fileName, -1, null, null)
        val semester = academicDao.semester(module.semesterId)
            ?: return FileScope(file.fileName, -1, null, null)
        return FileScope(
            fileName = file.fileName,
            semesterId = semester.id,
            weekLabel = "Week ${week.weekNumber}",
            moduleName = module.name
        )
    }
}
