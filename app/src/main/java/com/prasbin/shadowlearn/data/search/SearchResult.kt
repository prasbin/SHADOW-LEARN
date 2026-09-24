package com.prasbin.shadowlearn.data.search

/**
 * One search hit, fully resolved for the UI: chunk identity, academic
 * context (file, module, week), location reference (page/slide), a compact
 * [excerpt] with [excerptHighlights], and the deterministic
 * [score] (SHADOW LEARN heuristic relevance score, see [RelevanceScorer]).
 */
data class SearchResult(
    val chunkId: Long,
    val academicFileId: Long,
    val fileName: String,
    val fileType: String,
    val classType: String,
    val relativePath: String,
    val moduleName: String,
    val weekNumber: Int?,
    val weekTitle: String?,
    val pageNumber: Long?,
    val chunkIndex: Int,
    val score: Double,
    val excerpt: String,
    val excerptHighlights: List<IntRange>
)

/** Outcome of one search execution, separating "no hits" from "search failed". */
sealed interface SearchOutcome {
    data class Results(val results: List<SearchResult>, val maxScore: Double) : SearchOutcome
    data class Failed(val message: String) : SearchOutcome
}