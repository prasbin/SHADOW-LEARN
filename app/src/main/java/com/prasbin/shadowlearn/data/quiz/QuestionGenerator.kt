package com.prasbin.shadowlearn.data.quiz

import com.prasbin.shadowlearn.data.db.QuizChunkRow
import com.prasbin.shadowlearn.data.search.ExcerptGenerator
import kotlin.random.Random

/**
 * Rule-based, fully deterministic quiz question generator over extracted
 * chunk TEXT (Phase 6 — no LLM, no network). Every option and every answer
 * is a VERBATIM string from the material, so nothing is ever invented:
 *
 * - FILL_BLANK — a segment with one key term blanked out: "complete the
 *   statement". Options = the true term + 3 other genuine corpus terms.
 * - MCQ       — "which statement about «TERM» is correct?" Options = the
 *   segment containing it + 3 other verbatim segments that do not mention it.
 * - TRUE_FALSE— "the material states: «…»". TRUE only when the statement is
 *   verbatim; FALSE when a single term was swapped for another genuine
 *   corpus term (guarded so the mutated text can never equal ANY real
 *   corpus segment — the user must remember the material to answer).
 *
 * Determinism: given the same (pool, seed) every generated question and
 * option order is identical — `Random(seed)` is consumed in a fixed order.
 */
object QuestionGenerator {

    const val MAX_PER_CHUNK = 3
    const val OPTIONS = 4
    const val MIN_SENTENCE = 16
    const val MAX_SENTENCE = 220
    const val MIN_TERM_LEN = 5
    const val MAX_TERM_LEN = 26
    const val MAX_DISTRACTORS = 200

    private val TERM = Regex("[\\p{L}\\p{N}]+")
    private val BREAK = Regex("(?<=[.!?…;:])\\s+")

    /** Corpus context shared by every question in one session. */
    class Context(
        val terms: List<String>,
        /** Distinct, ordered corpus segments; also the mutation-guard set. */
        val segments: List<String>
    ) {
        val segmentSet: Set<String> = segments.map { it.lowercase() }.toSet()
    }

    /** Builds the session corpus context once (used by every question). */
    fun context(pool: List<QuizChunkRow>): Context {
        val terms = linkedSetOf<String>()
        val segments = linkedSetOf<String>()
        for (chunk in pool) {
            terms += termsOf(chunk.text)
            segments += segmentsOf(chunk.text)
        }
        return Context(terms = terms.take(MAX_DISTRACTORS), segments = segments.toList())
    }

    /** Upper bound on questions a chunk can yield today (0 = ineligible). */
    fun capacity(chunk: QuizChunkRow): Int =
        termsOf(chunk.text).count { hasSegment(chunk, it) }.coerceAtMost(MAX_PER_CHUNK)

    /**
     * Emits up to [MAX_PER_CHUNK] questions for one chunk with the running
     * [rng]. Question order/type and option order are reproducible for a
     * fixed seed. May return fewer than [capacity] when session distractors
     * run short (an honest lower count, never a fabricated one).
     */
    fun build(chunk: QuizChunkRow, ctx: Context, rng: Random): List<GeneratedQuestion> {
        val segments = segmentsOf(chunk.text)
        val out = mutableListOf<GeneratedQuestion>()
        // Rotate the starting slot per chunk so truncated sessions (take N)
        // still expose a mix of question types across the pool.
        var slot = (chunk.chunkId % 3).toInt()
        for (term in termsOf(chunk.text)) {
            if (out.size >= MAX_PER_CHUNK) break
            val segment = segments.firstOrNull { containsWord(it, term) } ?: continue
            val question = when (slot % 3) {
                0 -> fillBlank(chunk, term, segment, ctx, rng)
                1 -> multipleChoice(chunk, term, segment, ctx, rng)
                else -> trueFalse(chunk, term, segment, ctx, rng)
            }
            slot++
            if (question != null) out += question
        }
        return out
    }

    // ---- question builders -----------------------------------------------

    private fun fillBlank(
        chunk: QuizChunkRow, term: String, segment: String, ctx: Context, rng: Random
    ): GeneratedQuestion? {
        val correct = originalCasing(segment, term) ?: term
        val distractors = ctx.terms
            .filter { !it.equals(term, ignoreCase = true) && !it.equals(correct, ignoreCase = true) && !containsWord(segment, it) }
            .distinct()
            .take(OPTIONS - 1)
        if (distractors.size < OPTIONS - 1) return null
        val blanked = replaceTerm(segment, term, "________")
        val options = shuffled(listOf(correct) + distractors, rng)
        return GeneratedQuestion(
            type = QuestionType.FILL_BLANK,
            prompt = "Complete the following statement from the material: \"$blanked\" Which phrase fills the blank?",
            options = options,
            correctAnswer = correct,
            source = source(chunk, segment)
        )
    }

    private fun multipleChoice(
        chunk: QuizChunkRow, term: String, segment: String, ctx: Context, rng: Random
    ): GeneratedQuestion? {
        val correct = trimmed(segment, 90)
        val distractors = ctx.segments
            .filter { it != segment && !containsWord(it, term) }
            .map { trimmed(it, 90) }
            .distinct()
            .take(OPTIONS - 1)
        if (distractors.size < OPTIONS - 1) return null
        val options = shuffled(listOf(correct) + distractors, rng)
        return GeneratedQuestion(
            type = QuestionType.MCQ,
            prompt = "According to the material, which statement about \"${term.uppercase()}\" is correct?",
            options = options,
            correctAnswer = correct,
            source = source(chunk, segment)
        )
    }

    private fun trueFalse(
        chunk: QuizChunkRow, term: String, segment: String, ctx: Context, rng: Random
    ): GeneratedQuestion {
        // FALSE variant: swap the term with a genuine corpus term, accepted
        // only when the result is NOT verbatim anywhere in the corpus.
        val swap = ctx.terms.firstOrNull { it != term && !containsWord(segment, it) && mutatedIsNovel(segment, term, it, ctx) }
        val mutated = swap?.let { replaceTerm(segment, term, it) }?.trim()
        val (statement, correct) = if (mutated != null && rng.nextBoolean()) mutated to "false" else segment to "true"
        return GeneratedQuestion(
            type = QuestionType.TRUE_FALSE,
            prompt = "TRUE or FALSE — the material states: \"$statement\"",
            options = listOf("true", "false"),
            correctAnswer = correct,
            source = source(chunk, segment)
        )
    }

    private fun mutatedIsNovel(segment: String, term: String, swap: String, ctx: Context): Boolean {
        val mutated = replaceTerm(segment, term, swap).trim()
        return mutated.lowercase() !in ctx.segmentSet && !mutated.equals(segment, ignoreCase = true)
    }

    private fun source(chunk: QuizChunkRow, segment: String): QuizSource = QuizSource(
        chunkId = chunk.chunkId,
        academicFileId = chunk.academicFileId,
        fileName = chunk.fileName,
        fileType = chunk.fileType,
        pageNumber = chunk.pageNumber,
        excerpt = segment
    )

    // ---- corpus text helpers ---------------------------------------------

    /** Distinct terms of a chunk in deterministic priority order. */
    private fun termsOf(text: String): List<String> {
        val sanitized = sanitize(text)
        val freq = LinkedHashMap<String, Int>()
        val first = LinkedHashMap<String, Int>()
        var index = 0
        for (m in TERM.findAll(sanitized)) {
            val w = m.value.take(MAX_TERM_LEN).lowercase()
            if (w.length in MIN_TERM_LEN..MAX_TERM_LEN) {
                freq[w] = (freq[w] ?: 0) + 1
                first.putIfAbsent(w, index)
            }
            index++
        }
        return freq.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { it.key.length }
                    .thenBy { first[it.key] }
            )
            .map { it.key }
    }

    private fun hasSegment(chunk: QuizChunkRow, term: String): Boolean =
        segmentsOf(chunk.text).any { containsWord(it, term) }

    /** Deterministic segment list of a chunk (see class doc). */
    fun segmentsOf(text: String): List<String> {
        val sanitized = sanitize(text)
        if (sanitized.length < MIN_SENTENCE) return emptyList()
        val parts = if (sanitized.length > 400) BREAK.split(sanitized).filter { it.isNotBlank() } else listOf(sanitized)
        val out = mutableListOf<String>()
        for (part in parts) {
            if (part.length <= MAX_SENTENCE) {
                out += part
            } else {
                var cur = part
                while (cur.length > MAX_SENTENCE) {
                    val cut = cur.lastIndexOf(' ', MAX_SENTENCE)
                    if (cut <= 0) break
                    out += cur.substring(0, cut).trim()
                    cur = cur.substring(cut).trim()
                }
                if (cur.isNotEmpty()) out += cur
            }
        }
        return out
            .map { it.trim() }
            .filter { it.length >= MIN_SENTENCE && it.count { c -> c.isLetterOrDigit() } > 5 }
            .distinct()
    }

    // ---- text helpers (deterministic, word-boundary aware) ----------------

    internal fun sanitize(text: String) = ExcerptGenerator.sanitize(text)

    internal fun containsWord(haystack: String, word: String): Boolean {
        val h = haystack.lowercase()
        val w = word.lowercase()
        var from = 0
        while (true) {
            val at = h.indexOf(w, from)
            if (at < 0) return false
            val before = if (at == 0) ' ' else h[at - 1]
            val after = if (at + w.length < h.length) h[at + w.length] else ' '
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = at + w.length
        }
    }

    /** Replaces the FIRST word-boundary occurrence of [word] in [text]. */
    internal fun replaceTerm(text: String, word: String, replacement: String): String {
        val lower = text.lowercase()
        val w = word.lowercase()
        var from = 0
        while (true) {
            val at = lower.indexOf(w, from)
            if (at < 0) return text
            val before = if (at == 0) ' ' else text[at - 1]
            val after = if (at + w.length < text.length) text[at + w.length] else ' '
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) {
                return text.substring(0, at) + replacement + text.substring(at + w.length)
            }
            from = at + w.length
        }
    }

    /** Casing of the first word-boundary occurrence of [word] in [text]. */
    private fun originalCasing(text: String, word: String): String {
        val lower = text.lowercase()
        val w = word.lowercase()
        var from = 0
        while (true) {
            val at = lower.indexOf(w, from)
            if (at < 0) return word
            val before = if (at == 0) ' ' else text[at - 1]
            val after = if (at + w.length < text.length) text[at + w.length] else ' '
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) {
                return text.substring(at, at + w.length)
            }
            from = at + w.length
        }
    }

    internal fun trimmed(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.lastIndexOf(' ', max)
        val end = if (cut > max / 2) cut else max
        return text.substring(0, end).trimEnd() + "…"
    }

    private fun shuffled(items: List<String>, rng: Random): List<String> = items.shuffled(rng)
}