package com.prasbin.shadowlearn.data.intelligence

/**
 * I4 grounded explanation contract (docs §6). A result is either grounded
 * in retrieved academic chunks or an explicit honest failure — never a
 * mix, never invented content.
 */
enum class ExplanationStatus {
    EXPLAINED,
    INSUFFICIENT_EVIDENCE,
    NO_SOURCE,
    SOURCE_NOT_INDEXED,
    NO_MATCH
}

/** One verified source behind an explanation (provenance preserved). */
data class ExplanationSource(
    val academicFileId: Long,
    val fileName: String,
    val chunkId: Long,
    val chunkIndex: Int,
    /** Verbatim indexed sentence(s) used from this chunk. */
    val excerpt: String,
    val pageNumber: Long?,
    val weekLabel: String?,
    val moduleName: String?,
    /** Owning week for OPEN SOURCE routing (null when unresolvable). */
    val weekId: Long? = null
)

/** Pure explanation result: generated text + its verified grounding. */
data class GroundedExplanation(
    val status: ExplanationStatus,
    /** Assembled ONLY from retrieved sentences + fixed template glue. */
    val explanation: String,
    val sources: List<ExplanationSource>,
    /** Machine-readable basis, e.g. "2 passages · cell-biology.pdf". */
    val groundingBasis: String
)

/**
 * I4 deterministic explanation engine. PURE: no Room, no network, no
 * model calls. Behavior is extractive by design — the explanation is the
 * leading sentences of the retrieved chunks (document order) inside fixed
 * template glue, so by construction it cannot introduce facts absent from
 * the material (general-knowledge contamination is structurally impossible;
 * see tests). No question understanding is attempted in I4; the request
 * label only documents intent ("Explain the material associated with
 * this weak area."). Never claims "AI-generated": the UI labels output
 * GROUNDED EXPLANATION.
 */
object ExplanationEngine {

    /** Maximum sentences assembled into one explanation. */
    const val MAX_SENTENCES = 3

    /**
     * Builds an explanation from an I3 retrieval result. The engine never
     * touches the database — retrieval happens upstream.
     */
    fun explain(requestLabel: String, result: RetrievalResult): GroundedExplanation {
        require(requestLabel.isNotBlank()) { "explanation request label must not be blank" }
        return when (result) {
            is RetrievalResult.NoSource -> GroundedExplanation(
                ExplanationStatus.NO_SOURCE, "", emptyList(), "no source"
            )
            is RetrievalResult.SourceNotIndexed -> GroundedExplanation(
                ExplanationStatus.SOURCE_NOT_INDEXED, "", emptyList(), "source not indexed"
            )
            is RetrievalResult.NoMatch -> GroundedExplanation(
                ExplanationStatus.NO_MATCH, "", emptyList(), "no match"
            )
            is RetrievalResult.Retrieved -> explainChunks(result.chunks)
        }
    }

    private fun explainChunks(chunks: List<RetrievedChunk>): GroundedExplanation {
        data class Pick(val sentence: String, val chunk: RetrievedChunk)
        val picks = mutableListOf<Pick>()
        for (chunk in chunks.sortedBy { it.chunkIndex }) {
            for (sentence in splitSentences(chunk.excerpt)) {
                if (picks.size >= MAX_SENTENCES) break
                picks.add(Pick(sentence, chunk))
            }
            if (picks.size >= MAX_SENTENCES) break
        }
        if (picks.isEmpty()) {
            return GroundedExplanation(
                ExplanationStatus.INSUFFICIENT_EVIDENCE, "", emptyList(), "no usable sentences"
            )
        }
        val files = picks.map { it.chunk.fileName }.distinct()
        val text = "Based on ${plural(picks.size, "passage")} from ${files.joinToString(", ")}: " +
            picks.joinToString(" ") { it.sentence }
        return GroundedExplanation(
            status = ExplanationStatus.EXPLAINED,
            explanation = text,
            sources = picks.map {
                ExplanationSource(
                    academicFileId = it.chunk.academicFileId,
                    fileName = it.chunk.fileName,
                    chunkId = it.chunk.chunkId,
                    chunkIndex = it.chunk.chunkIndex,
                    excerpt = it.sentence,
                    pageNumber = it.chunk.pageNumber,
                    weekLabel = it.chunk.weekLabel,
                    moduleName = it.chunk.moduleName,
                    weekId = it.chunk.weekId
                )
            }.distinctBy { it.chunkId },
            groundingBasis = "${picks.size} passage${if (picks.size == 1) "" else "s"} · ${files.joinToString(", ")}"
        )
    }

    /** Deterministic sentence split on [. ! ?] boundaries, delimiters kept. */
    fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
}
