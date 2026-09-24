package com.prasbin.shadowlearn.data.search

/**
 * Normalized, sanitized academic query for the FTS MATCH layer.
 *
 * The raw user string is reduced to plain letter/digit tokens so that no
 * FTS query syntax (quotes, `:`, `(`, `)`, `*`, `-`, `^`, `{`, `[`, `OR`,
 * `NOT`, …) can ever reach the MATCH expression. Every token is matched as
 * a prefix (`term*`) so partial words still hit; tokens in the expression
 * are implicitly AND-ed by FTS4/FTS5, which keeps multi-word queries
 * precise (a chunk must contain all terms).
 *
 * This is the ONLY place user input meets the MATCH syntax — malformed
 * input cannot crash the search because tokens are validated here.
 */
data class SearchQuery private constructor(
    /** Deterministic, lowercased, deduplicated tokens in first-seen order. */
    val terms: List<String>
) {
    val isBlank: Boolean get() = terms.isEmpty()

    /**
     * FTS MATCH-safe expression (space-separated prefix terms).
     *
     * The space shorthand is FTS4's implicit AND, which the Android
     * framework build DOES honor (verified on-device, API 36: an explicit
     * `AND` keyword is NOT treated as an operator there and would silently
     * match the literal word "and"). A trailing `*` on every term also
     * guarantees user input can never collide with the reserved operator
     * keywords `and`/`or`/`not` (a bare keyword is an operator, `or*` is a
     * plain prefix term). Only valid when [isBlank] is false.
     */
    fun toMatchExpression(): String = terms.joinToString(" ") { "${it}*" }

    companion object {
        const val MAX_TERMS = 8
        const val MAX_TERM_LENGTH = 64

        private val TOKEN = Regex("[\\p{L}\\p{N}]+")

        /** Parses a raw query string into sanitized [SearchQuery]. Never throws. */
        fun parse(raw: String): SearchQuery {
            val terms = LinkedHashSet<String>()
            for (match in TOKEN.findAll(raw)) {
                if (terms.size >= MAX_TERMS) break
                val token = match.value.take(MAX_TERM_LENGTH).lowercase()
                if (token.isNotEmpty()) terms.add(token)
            }
            return SearchQuery(terms.toList())
        }
    }
}