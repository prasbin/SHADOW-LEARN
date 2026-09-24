package com.prasbin.shadowlearn.data.search

import com.prasbin.shadowlearn.data.db.SearchDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Phase 5 search orchestrator — the UI's only entry point into SQLite
 * search. Pipeline:
 *
 * ```
 * raw query → SearchQuery (sanitized tokens)         — no MATCH syntax risk
 *          → FtsIndex.search(expr)                    — SQLite filters chunks
 *          → SearchDao.resolveChunks(semester, ids)   — one metadata join
 *          → RelevanceScorer + ExcerptGenerator       — rank & excerpt
 *          → sorted List<SearchResult>                — score DESC, fileName
 *                                                       ASC, chunkId ASC
 * ```
 *
 * Everything runs on [ioDispatcher]; nothing touches the main thread. FTS
 * does the filtering (never a Kotlin scan of the whole corpus), and chunk
 * metadata is resolved in a single indexed query (no per-result queries,
 * no duplicated full text — the hit text comes from FTS).
 */
class SearchRepository(
    private val searchDao: SearchDao,
    private val fts: FtsIndex,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Search [rawQuery] within [semesterId]. Returns
     * [SearchOutcome.Results] (possibly empty) or [SearchOutcome.Failed]
     * when SQLite itself rejects something. Semester scoping is enforced in
     * the metadata join, never by loading whole semesters into memory.
     */
    suspend fun search(rawQuery: String, semesterId: Long): SearchOutcome = withContext(dispatcher) {
        val query = SearchQuery.parse(rawQuery)
        if (query.isBlank || semesterId <= 0L) {
            return@withContext SearchOutcome.Results(emptyList(), 0.0)
        }
        val matchExpression = query.toMatchExpression()
        val hits = try {
            fts.search(matchExpression, FETCH_LIMIT)
        } catch (e: Exception) {
            return@withContext SearchOutcome.Failed(e.message ?: "Search failed")
        }
        if (hits.isEmpty()) return@withContext SearchOutcome.Results(emptyList(), 0.0)

        val rows = searchDao.resolveChunks(
            semesterId, hits.map { it.chunkId }
        ).associateBy { it.chunkId }

        val ranked = hits.mapNotNull { hit ->
            val row = rows[hit.chunkId] ?: return@mapNotNull null // chunk gone after query; skip
            val excerpt = ExcerptGenerator.generate(hit.text, query.terms)
            val score = RelevanceScorer.score(
                query = query,
                chunkText = hit.text,
                fileName = row.fileName,
                moduleName = row.moduleName,
                charCount = maxOf(row.charCount, hit.text.length)
            )
            SearchResult(
                chunkId = hit.chunkId,
                academicFileId = row.academicFileId,
                fileName = row.fileName,
                fileType = row.fileType,
                classType = row.classType,
                relativePath = row.relativePath,
                moduleName = row.moduleName,
                weekNumber = row.weekNumber,
                weekTitle = row.weekTitle,
                pageNumber = row.pageNumber,
                chunkIndex = row.chunkIndex,
                score = score,
                excerpt = excerpt.text,
                excerptHighlights = excerpt.highlightRanges
            )
        }
            .sortedWith(
                compareByDescending<SearchResult> { it.score }
                    .thenBy { it.fileName.lowercase() }
                    .thenBy { it.chunkId }
            )
            .take(RESULT_LIMIT)

        SearchOutcome.Results(ranked, ranked.maxOfOrNull { it.score } ?: 0.0)
    }

    fun observeIndexedChunkCount(semesterId: Long): Flow<Int> =
        searchDao.observeIndexedChunkCount(semesterId)

    suspend fun indexedChunkCount(semesterId: Long): Int = searchDao.indexedChunkCount(semesterId)

    companion object {
        const val RESULT_LIMIT = 50
        const val FETCH_LIMIT = 200
    }
}