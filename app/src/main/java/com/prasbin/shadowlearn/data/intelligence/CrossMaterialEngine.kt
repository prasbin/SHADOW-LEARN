package com.prasbin.shadowlearn.data.intelligence

/**
 * I6 pure cross-material engine: term extraction + deterministic ranking.
 * No Room, no Android, no network, no AI. Same input always yields the
 * same output. Thresholds are I6-initial (contract open question 1):
 * documented here, never silently tuned.
 */
object CrossMaterialEngine {

    /** Minimum significant term length. */
    const val MIN_TERM_LENGTH = 5

    /** Maximum terms extracted per source file. */
    const val MAX_TERMS = 12

    /** Distinct confirmed terms for a VERIFIED content relationship. */
    const val VERIFIED_TERM_THRESHOLD = 3

    /** Maximum relationships returned per source. */
    const val MAX_RELATIONSHIPS = 3

    /**
     * Embedded stopword set: common English function words plus academic
     * filler that carries no topical signal (lecture, chapter, figure…).
     * Corpora in other languages pass through on length alone — accepted
     * and documented limitation, not silent behavior.
     */
    val STOPWORDS: Set<String> = setOf(
        "about", "after", "also", "been", "before", "being", "between",
        "both", "could", "during", "each", "from", "have", "here",
        "into", "more", "most", "much", "only", "other", "over", "should",
        "such", "than", "that", "their", "them", "then", "there", "these",
        "those", "through", "under", "very", "well", "were", "what",
        "when", "where", "which", "while", "will", "with", "would",
        "your", "study", "learn", "lecture", "chapter", "introduction",
        "example", "figure", "table", "section", "page", "slide", "notes",
        "summary", "overview", "material", "content", "using", "used"
    )

    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    /**
     * Source-file terms for FTS confirmation: lowercased, length-filtered,
     * stopword-free, frequency-ranked with alphabetical tie-break, capped.
     */
    fun extractTerms(texts: List<String>): List<String> {
        val counts = linkedMapOf<String, Int>()
        for (text in texts) {
            for (match in TOKEN.findAll(text.lowercase())) {
                val token = match.value
                if (token.length < MIN_TERM_LENGTH) continue
                if (token in STOPWORDS) continue
                counts[token] = (counts[token] ?: 0) + 1
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_TERMS)
            .map { it.key }
    }

    /** Structural + content facts about one candidate file (no text). */
    data class CandidateInfo(
        val fileId: Long,
        val fileName: String,
        val sha256: String,
        val weekId: Long,
        val weekNumber: Int,
        val moduleId: Long,
        val moduleName: String,
        /** Distinct source terms with ≥1 real FTS hit chunk in this file. */
        val matchedTerms: List<String>,
        /** Hit chunk ids backing the terms (bounded upstream). */
        val matchedChunkIds: List<Long>
    )

    /** Structural position of the source file. */
    data class SourceInfo(
        val fileId: Long,
        val sha256: String,
        val weekId: Long,
        val weekNumber: Int,
        val moduleId: Long
    )

    /**
     * Ranks candidates deterministically: SAME_WEEK first, then VERIFIED
     * content, then SAME_MODULE, then POSSIBLE content; within a class by
     * confirmed-term count desc, week number asc, file id asc. Self and
     * same-content mirrors are excluded here as well as upstream (defense
     * in depth — the repository pre-filters too).
     */
    fun evaluate(
        source: SourceInfo,
        candidates: List<CandidateInfo>
    ): List<RankedCandidate> {
        data class Scored(val candidate: CandidateInfo, val rank: Int)
        val out = mutableListOf<Scored>()
        for (c in candidates) {
            if (c.fileId == source.fileId) continue
            if (c.sha256.isNotEmpty() && c.sha256 == source.sha256) continue
            val sameWeek = c.weekId == source.weekId
            val sameModule = c.moduleId == source.moduleId
            val terms = c.matchedTerms.size
            val rank = when {
                sameWeek -> 0
                terms >= VERIFIED_TERM_THRESHOLD -> 1
                sameModule -> 2
                // Cross-module 1–2 term overlaps are too weak: no item.
                else -> continue
            }
            out.add(Scored(c, rank))
        }
        return out.sortedWith(
            compareBy<Scored> { it.rank }
                .thenByDescending { it.candidate.matchedTerms.size }
                .thenBy { it.candidate.weekNumber }
                .thenBy { it.candidate.fileId }
        ).take(MAX_RELATIONSHIPS).map { (c, rank) ->
            RankedCandidate(
                candidate = c,
                type = when (rank) {
                    0 -> RelationshipType.SAME_WEEK
                    1 -> RelationshipType.SHARED_CONTENT
                    else -> RelationshipType.SAME_MODULE
                },
                status = if (rank <= 1) RelationshipStatus.VERIFIED else RelationshipStatus.POSSIBLE
            )
        }
    }

    /** Ranked candidate with its evidence, pre-excerpt (repository hydrates). */
    data class RankedCandidate(
        val candidate: CandidateInfo,
        val type: RelationshipType,
        val status: RelationshipStatus
    )
}
