package com.prasbin.shadowlearn.data.intelligence

/**
 * I3 grounded retrieval contract (docs §6–8). Priority is fixed:
 * exact chunk → exact file → scoped full-text → failure. Every returned
 * passage is verbatim indexed text with provenance — never generated,
 * never paraphrased. No AI, no embeddings, no scores shown to students.
 */

/** What to retrieve: exact ids win over lexical queries. */
data class RetrievalRequest(
    val semesterId: Long,
    val fileId: Long? = null,
    val chunkId: Long? = null,
    val query: String? = null,
    val maxChunks: Int = 3
)

/** One verbatim indexed passage with its full provenance. */
data class RetrievedChunk(
    val chunkId: Long,
    val academicFileId: Long,
    val fileName: String,
    /** Verbatim chunk text (exact paths) or query excerpt (text path). */
    val excerpt: String,
    val pageNumber: Long?,
    val chunkIndex: Int,
    val weekLabel: String?,
    val moduleName: String?,
    /** Owning week for OPEN SOURCE routing (null when unresolvable). */
    val weekId: Long? = null
)

/** Retrieval outcome — the four states are never collapsed. */
sealed interface RetrievalResult {
    /** Actual source material found (1..maxChunks passages). */
    data class Retrieved(val chunks: List<RetrievedChunk>) : RetrievalResult

    /** Evidence cannot be connected to an academic file. */
    data object NoSource : RetrievalResult

    /** File exists but has no usable indexed chunks. */
    data object SourceNotIndexed : RetrievalResult

    /** Scoped retrieval attempted, nothing matched (or scope excluded it). */
    data object NoMatch : RetrievalResult
}
