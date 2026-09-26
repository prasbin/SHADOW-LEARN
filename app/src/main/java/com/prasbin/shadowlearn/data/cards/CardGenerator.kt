package com.prasbin.shadowlearn.data.cards

import com.prasbin.shadowlearn.data.db.CardChunkRow
import com.prasbin.shadowlearn.data.db.MistakeRow
import com.prasbin.shadowlearn.data.db.ReadySegmentRow
import kotlin.math.min
import kotlin.random.Random

/**
 * Phase 8 deterministic card generator — pure functions over verified
 * corpus rows. The content rule is structural, not aspirational: every
 * front/back is a VERBATIM substring (or whole string) of its source row,
 * so no code path can invent an academic fact.
 *
 * - Chunks → vocabulary cards: front = a corpus term, back = the verbatim
 *   segment containing it (capped at [MAX_PER_CHUNK] per chunk).
 * - Quiz mistakes → review cards: front = the verbatim question prompt,
 *   back = verbatim correct answer + citation (isCorrect == 0 rows only;
 *   the DAO guarantees the filter, the builder re-checks nothing because
 *   it never sees non-mistakes).
 * - Listener segments → lecture cards: front = first verbatim sentence,
 *   back = full verbatim transcript. Callers MUST only pass READY rows —
 *   the DAO query filters `transcriptStatus = 'ready'`, and [fromSegments]
 *   additionally refuses rows whose status is not READY (defense in depth:
 *   PENDING/FAILED transcripts never become cards even if a caller errs).
 *
 * Determinism: fixed iteration order (chunk id, term order, row id) plus an
 * optional seeded shuffle for deck variety. Identical corpus + seed ⇒
 * identical cards. [contentKey] values (`chunk:<id>:<term>` /
 * `quiz:<qid>` / `seg:<sid>`) make rebuilds idempotent via the unique
 * (deckId, contentKey) index.
 */
object CardGenerator {

    const val MAX_PER_CHUNK = 2
    const val MIN_TERM_LEN = 5
    const val MAX_TERM_LEN = 26
    const val MIN_SEGMENT = 16
    const val MAX_SEGMENT = 220
    const val MAX_FRONT_SEGMENT = 90

    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val BREAK = Regex("(?<=[.!?…;:])\\s+")

    /** A generated card before persistence (deckId assigned by the repository). */
    data class DraftCard(
        val front: String,
        val back: String,
        val sourceChunkId: Long?,
        val sourceQuestionId: Long?,
        val sourceListenerSegmentId: Long?,
        val sourceLabel: String,
        val contentKey: String
    )

    /** Vocabulary cards from extracted chunk text (verbatim term → segment). */
    fun fromChunks(
        chunks: List<CardChunkRow>,
        maxPerChunk: Int = MAX_PER_CHUNK,
        rng: Random = Random(0)
    ): List<DraftCard> {
        val out = mutableListOf<DraftCard>()
        for (chunk in chunks) {
            val segments = segmentsOf(chunk.text)
            if (segments.isEmpty()) continue
            val terms = termsOf(chunk.text)
            var made = 0
            // Deterministic term order; a seeded rotation only varies WHICH
            // terms win when a chunk overflows the cap.
            val ordered = if (terms.size > maxPerChunk) {
                val shift = rng.nextInt(terms.size)
                terms.drop(shift) + terms.take(shift)
            } else terms
            for (term in ordered) {
                if (made >= maxPerChunk) break
                val segment = segments.firstOrNull { containsWord(it, term) } ?: continue
                val displayTerm = originalCasing(segment, term) ?: term
                out += DraftCard(
                    front = displayTerm,
                    back = segment,
                    sourceChunkId = chunk.chunkId,
                    sourceQuestionId = null,
                    sourceListenerSegmentId = null,
                    sourceLabel = citation(chunk.fileName, chunk.fileType, chunk.pageNumber),
                    contentKey = "chunk:${chunk.chunkId}:${term.lowercase()}"
                )
                made++
            }
        }
        return out
    }

    /** Review cards from wrongly answered quiz questions (verbatim). */
    fun fromMistakes(mistakes: List<MistakeRow>): List<DraftCard> =
        mistakes.map { row ->
            DraftCard(
                front = row.prompt,
                back = "Answer: ${row.correctAnswer}",
                sourceChunkId = null,
                sourceQuestionId = row.questionId,
                sourceListenerSegmentId = null,
                sourceLabel = citation(row.srcFileName, row.srcFileType, row.srcPage),
                contentKey = "quiz:${row.questionId}"
            )
        }

    /**
     * Lecture cards from READY transcripts only. Rows with any other status
     * are refused (defense in depth beyond the DAO filter).
     */
    fun fromSegments(rows: List<ReadySegmentRow>): List<DraftCard> {
        val out = mutableListOf<DraftCard>()
        for (row in rows) {
            if (row.transcriptStatus != "ready") continue
            val text = row.transcript.trim()
            if (text.length < MIN_SEGMENT) continue
            val first = segmentsOf(text).firstOrNull() ?: continue
            out += DraftCard(
                front = trimmed(first, MAX_FRONT_SEGMENT),
                back = text,
                sourceChunkId = null,
                sourceQuestionId = null,
                sourceListenerSegmentId = row.segmentId,
                contentKey = "seg:${row.segmentId}",
                sourceLabel = "Lecture segment #${row.segmentId}"
            )
        }
        return out
    }

    /** Human citation snapshot for chunk/mistake cards. */
    fun citation(fileName: String, fileType: String, page: Long?): String {
        val type = fileType.uppercase()
        val ref = if (page != null && page > 0) {
            if (fileType.equals("pptx", ignoreCase = true)) "SLIDE $page" else "PAGE $page"
        } else null
        return listOfNotNull(fileName, type, ref).joinToString(" · ")
    }

    // ---- text utilities (verbatim-safe) -----------------------------------

    internal fun segmentsOf(text: String): List<String> =
        BREAK.split(text)
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in MIN_SEGMENT..MAX_SEGMENT }
            .distinct()

    internal fun termsOf(text: String): List<String> {
        val words = WORD.findAll(text).map { it.value }.toList()
        val freq = linkedMapOf<String, Int>()
        for (w in words) {
            if (w.length in MIN_TERM_LEN..MAX_TERM_LEN) {
                val key = w.lowercase()
                freq[key] = (freq[key] ?: 0) + 1
            }
        }
        return freq.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    internal fun containsWord(haystack: String, term: String): Boolean =
        Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])").containsMatchIn(haystack)

    internal fun originalCasing(segment: String, term: String): String? =
        WORD.findAll(segment).firstOrNull { it.value.equals(term, ignoreCase = true) }?.value

    internal fun trimmed(text: String, max: Int): String {
        val clean = text.trim()
        if (clean.length <= max) return clean
        val cut = clean.take(max).trimEnd()
        val wordEnd = cut.lastIndexOf(' ').takeIf { it >= min(20, cut.length / 2) } ?: cut.length
        return cut.substring(0, wordEnd).trimEnd() + "…"
    }
}
