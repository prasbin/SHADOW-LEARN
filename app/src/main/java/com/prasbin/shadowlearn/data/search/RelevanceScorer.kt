package com.prasbin.shadowlearn.data.search

import kotlin.math.ln
import kotlin.math.max

/**
 * Deterministic, explainable relevance scoring for search results.
 *
 * NOT SQLite BM25: Android's SQLite exposes FTS4 without the `rank` hidden
 * column (see docs/ARCHITECTURE.md), so relevance must be computed in
 * Kotlin over the small set of FTS matches. This is the official
 * "SHADOW LEARN heuristic relevance score" — kept intentionally simple so
 * its behavior is fully predictable and unit-testable:
 *
 * ```
 * coverage  = #terms with any word starting with the term / #terms      (0..1)
 * exact     = word-boundary occurrences equal to a term
 * prefix    = word-boundary occurrences starting with (but not equal to) a term
 * fileName  = 1 when any term matches a word in the file name, else 0
 * module    = 1 when any term matches a word in the module name, else 0
 *
 *         100·coverage + 12·exact + 6·prefix + 80·fileName + 30·module
 * score = ─────────────────────────────────────────────────────────────
 *                  1 + ln(1 + max(1, charCount)) / 10
 * ```
 *
 * Multi-term coverage dominates (an answer containing BOTH terms beats one
 * containing one). A file-name match is a strong cue (title/topic), the
 * module name a weaker one. The length denominator prevents long chunks
 * from scoring high purely on size. Every arithmetic step is ordinary
 * IEEE doubles, so equal inputs always produce equal outputs — ordering is
 * stable across devices and runs.
 */
object RelevanceScorer {

    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** Word tokens of [text], lowercased (punctuation-split). */
    internal fun words(text: String): List<String> =
        WORD.findAll(text).map { it.value.lowercase() }.toList()

    private fun wordStartsWith(term: String, word: String): Boolean =
        word.startsWith(term)

    /** Does any word in [words] start with [term] (exact or prefix)? */
    private fun anyWordMatches(term: String, words: List<String>): Boolean =
        words.any { wordStartsWith(term, it) }

    /**
     * Computes the heuristic score for one chunk. [charCount] is the chunk's
     * stored character count (falls back to the actual text length when
     * misleading/zero).
     */
    fun score(
        query: SearchQuery,
        chunkText: String,
        fileName: String,
        moduleName: String,
        charCount: Int
    ): Double {
        if (query.isBlank) return 0.0
        val terms = query.terms
        val chunkWords = words(chunkText)
        val fileWords = words(fileName)
        val moduleWords = words(moduleName)

        var present = 0
        var exact = 0
        var prefix = 0
        for (term in terms) {
            var termPresent = false
            for (word in chunkWords) {
                if (word == term) {
                    exact++
                    termPresent = true
                } else if (word.startsWith(term)) {
                    prefix++
                    termPresent = true
                }
            }
            if (termPresent) present++
        }
        val coverage = present.toDouble() / terms.size

        val fileNameHit = if (terms.any { anyWordMatches(it, fileWords) }) 1.0 else 0.0
        val moduleHit = if (terms.any { anyWordMatches(it, moduleWords) }) 1.0 else 0.0

        val raw = 100.0 * coverage + 12.0 * exact + 6.0 * prefix +
            80.0 * fileNameHit + 30.0 * moduleHit
        val lengthFactor = 1.0 + ln(1.0 + max(1, charCount)) / 10.0
        return raw / lengthFactor
    }
}