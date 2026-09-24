package com.prasbin.shadowlearn.data.search

/**
 * Compact, term-aware excerpt for a search result.
 *
 * Shows the first window around a matched term instead of dumping the whole
 * chunk (memory-safe, readable); the text is sanitized so malformed
 * extraction bytes (control characters, runaway whitespace) are never shown
 * raw. [highlightRanges] are character offsets into [text] where matching
 * terms can be subtly emphasized by the UI.
 */
data class Excerpt(
    val text: String,
    val highlightRanges: List<IntRange>
)

object ExcerptGenerator {

    const val DEFAULT_WINDOW_BEFORE = 60
    const val DEFAULT_WINDOW_AFTER = 100
    const val MAX_HIGHLIGHTS = 3

    private const val ELLIPSIS = "…"
    private const val PREFACE_LIMIT = 200

    /** Removes control characters and collapses whitespace runs. */
    internal fun sanitize(text: String): String {
        val cleaned = text.map { c ->
            if (c.code < 0x20 && c != '\n' && c != '\t') ' ' else c
        }.joinToString("")
        return cleaned.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Produces an excerpt around the first query-term occurrence (word
     * boundary, case-insensitive). When no term occurs (defensive; FTS
     * already restricted matches) a leading window is used.
     */
    fun generate(
        text: String,
        terms: List<String>,
        windowBefore: Int = DEFAULT_WINDOW_BEFORE,
        windowAfter: Int = DEFAULT_WINDOW_AFTER
    ): Excerpt {
        val cleaned = sanitize(text)
        val queryTerms = terms.filter { it.isNotEmpty() }
        val firstIndex = firstTermIndex(cleaned, queryTerms)

        if (firstIndex < 0) return leadingWindow(cleaned, queryTerms, windowBefore + windowAfter - PREFACE_LIMIT, PREFACE_LIMIT)

        val start = moveToWordStart(cleaned, maxOf(0, firstIndex - windowBefore))
        var end = moveToWordEnd(cleaned, minOf(cleaned.length, firstIndex + windowAfter))
        if (end <= start) end = minOf(cleaned.length, start + 1)

        val prefix = if (start > 0) ELLIPSIS else ""
        val suffix = if (end < cleaned.length) ELLIPSIS else ""
        val body = cleaned.substring(start, end)
        val prefixLen = prefix.length

        val highlights = highlightRangesIn(cleaned, body, queryTerms, start, prefixLen)
        return Excerpt(prefix + body + suffix, highlights)
    }

    /** First term occurrence on a word boundary; -1 when none. */
    private fun firstTermIndex(cleaned: String, terms: List<String>): Int {
        var best = -1
        for (term in terms) {
            var from = 0
            while (true) {
                val at = cleaned.indexOf(term, from, ignoreCase = true)
                if (at < 0) break
                if (isBoundaryStart(cleaned, at)) {
                    if (best < 0 || at < best) best = at
                    break
                }
                from = at + 1
            }
        }
        return best
    }

    private fun highlightRangesIn(
        cleaned: String,
        body: String,
        terms: List<String>,
        start: Int,
        prefixLen: Int
    ): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        while (ranges.size < MAX_HIGHLIGHTS) {
            var next = -1
            var used = ""
            for (term in terms) {
                val at = cleaned.indexOf(term, cursor, ignoreCase = true)
                if (at in 0 until cleaned.length && at >= 0 && isBoundaryStart(cleaned, at) && at >= start) {
                    if (next < 0 || at < next) {
                        next = at
                        used = term
                    }
                }
            }
            if (next < 0 || next >= start + body.length) break
            val relStart = next - start + prefixLen
            val relEnd = minOf(body.length, relStart + used.length)
            if (relStart < body.length && relEnd > relStart) {
                ranges += relStart until relEnd
            }
            cursor = next + maxOf(1, used.length)
        }
        return mergeAdjacent(ranges)
    }

    private fun isBoundaryStart(cleaned: String, index: Int): Boolean {
        if (index <= 0) return true
        val prev = cleaned[index - 1]
        return !prev.isLetterOrDigit() && prev != '\'' && prev != '\u2019'
    }

    private fun moveToWordStart(cleaned: String, index: Int): Int {
        var i = index
        while (i > 0 && cleaned[i - 1].isLetterOrDigit() && i - 1 >= 0) i--
        return i
    }

    private fun moveToWordEnd(cleaned: String, index: Int): Int {
        var i = index
        while (i < cleaned.length && cleaned[i].isLetterOrDigit()) i++
        return i
    }

    private fun leadingWindow(cleaned: String, terms: List<String>, start: Int, width: Int): Excerpt {
        if (cleaned.isEmpty()) return Excerpt("", emptyList())
        val s = maxOf(0, start)
        val e = minOf(cleaned.length, width)
        val prefix = if (s > 0) ELLIPSIS else ""
        val suffix = if (e < cleaned.length) ELLIPSIS else ""
        val body = cleaned.substring(s, e)
        return Excerpt(prefix + body + suffix, highlightRangesIn(cleaned, body, terms, s, prefix.length))
    }

    private fun mergeAdjacent(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size < 2) return ranges
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf(sorted.first())
        for (r in sorted.drop(1)) {
            val last = merged.last()
            if (r.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
            } else {
                merged += r
            }
        }
        return merged
    }
}