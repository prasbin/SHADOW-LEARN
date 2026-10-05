package com.prasbin.shadowlearn.data.listener

import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.ListenerSegment
import com.prasbin.shadowlearn.data.intelligence.CrossMaterialEngine
import com.prasbin.shadowlearn.data.search.SearchOutcome
import com.prasbin.shadowlearn.data.search.SearchRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * I7 listener-intelligence evidence roles. A transcript is academic
 * evidence, never automatically a fact, weakness, summary, or card.
 * Only READY transcripts carry usable content; anything else is UNKNOWN.
 */
enum class SegmentRole {
    /** READY text verified against ≥1 indexed chunk in this semester. */
    ACADEMIC,
    /** READY substantive text with no indexed counterpart (still reviewable). */
    TRANSCRIPT_ONLY,
    /** READY text too short/sparse to interpret (honest filler, not a verdict). */
    FILLER,
    /** Not READY, blank, or otherwise uninterpretable — never displayed as content. */
    UNKNOWN
}

/** One segment's interpretation; transcript text is verbatim or absent. */
data class SegmentUnderstanding(
    val segmentId: Long,
    val position: Int,
    val role: SegmentRole,
    /** Verbatim transcript (empty unless READY with text). */
    val transcript: String,
    /** FTS-confirmed corpus terms (empty unless ACADEMIC). */
    val matchedTerms: List<String>,
    val groundedFileId: Long?,
    val groundedFileName: String?
)

/** One indexed source backing ACADEMIC segments. */
data class GroundedLectureSource(
    val fileId: Long,
    val fileName: String,
    val weekId: Long?,
    val terms: List<String>,
    val chunkId: Long
)

/** Verbatim transcript quote selected as a key point. */
data class LectureKeyPoint(
    val segmentId: Long,
    val position: Int,
    val text: String
)

/**
 * Derived per-session understanding. Nothing here is persisted — it is
 * recomputed from listener rows + indexed content on review. Counts are
 * bounded integers, never percentages or scores.
 */
data class SessionUnderstanding(
    val sessionId: Long,
    val semesterId: Long,
    val totalSegments: Int,
    val readyCount: Int,
    val academicCount: Int,
    val fillerCount: Int,
    val transcriptOnlyCount: Int,
    val unknownCount: Int,
    val keyPoints: List<LectureKeyPoint>,
    val groundedSources: List<GroundedLectureSource>,
    /** First grounded file by segment order; null = no PRACTICE action. */
    val practiceFileId: Long?
)

/**
 * I7 listener intelligence: transcript evidence → academic signals, all
 * offline and deterministic. Text rules only — never voice characteristics.
 * Grounding reuses the existing semester-scoped search; card/quiz paths
 * are untouched (SEND READY TO CARDS and targeted practice consume the
 * file ids produced here through their existing flows).
 */
class ListenerIntelligence(
    private val search: SearchRepository,
    private val academicDao: AcademicDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        /** READY text shorter than this (trimmed) cannot be substantive. */
        const val FILLER_MAX_CHARS = 40

        /** Terms evaluated per segment (bounded FTS cost). */
        const val MAX_TERMS_PER_SEGMENT = 5

        /** Segments analyzed per session (pause-bounded sessions stay small). */
        const val MAX_SEGMENTS = 30

        /** Key points and grounded sources shown (matches max-3 conventions). */
        const val MAX_KEY_POINTS = 3
        const val MAX_SOURCES = 3

        /** Verbatim quote length cap; truncation is marked, never paraphrased. */
        const val KEY_POINT_MAX_CHARS = 220

        /**
         * Stateless classification shared by tests and the orchestrator:
         * identical inputs always yield the identical role.
         */
        fun classify(transcript: String, status: String, hasChunkHit: Boolean): SegmentRole {
            if (status != ListenerSegment.STATUS_READY) return SegmentRole.UNKNOWN
            val trimmed = transcript.trim()
            if (trimmed.isEmpty() || trimmed == ListenerSegment.TRANSCRIPT_PENDING) return SegmentRole.UNKNOWN
            if (hasChunkHit) return SegmentRole.ACADEMIC
            val terms = CrossMaterialEngine.extractTerms(listOf(trimmed))
            if (trimmed.length < FILLER_MAX_CHARS && terms.size <= 1) return SegmentRole.FILLER
            return SegmentRole.TRANSCRIPT_ONLY
        }
    }

    /**
     * Full session understanding. Segments beyond [MAX_SEGMENTS] are
     * ignored deterministically (position order); every other bound is a
     * documented constant above. Never throws for missing data.
     */
    suspend fun understand(
        sessionId: Long,
        semesterId: Long,
        segments: List<ListenerSegment>
    ): SessionUnderstanding = withContext(dispatcher) {
        val ordered = segments.sortedBy { it.position }.take(MAX_SEGMENTS)
        val understood = ordered.map { seg ->
            val text = if (seg.transcriptStatus == ListenerSegment.STATUS_READY) seg.transcript.trim() else ""
            val terms = if (text.isEmpty()) {
                emptyList()
            } else {
                CrossMaterialEngine.extractTerms(listOf(text)).take(MAX_TERMS_PER_SEGMENT)
            }
            // Per-term FTS confirmation (semester-scoped by the repository).
            val hits = mutableMapOf<String, MutableList<Pair<Long, Long>>>()
            for (term in terms) {
                when (val outcome = search.search(term, semesterId)) {
                    is SearchOutcome.Failed -> { /* term unusable; contributes nothing */ }
                    is SearchOutcome.Results -> {
                        for (hit in outcome.results) {
                            hits.getOrPut(term) { mutableListOf() }
                                .add(hit.academicFileId to hit.chunkId)
                        }
                    }
                }
            }
            val fileIds = hits.values.flatten().map { it.first }.distinct()
            val groundedFileId = fileIds.sorted().firstOrNull()
            val role = classify(seg.transcript, seg.transcriptStatus, fileIds.isNotEmpty())
            Triple(seg, SegmentUnderstanding(
                segmentId = seg.id,
                position = seg.position,
                role = role,
                transcript = if (seg.transcriptStatus == ListenerSegment.STATUS_READY) seg.transcript.trim() else "",
                matchedTerms = if (role == SegmentRole.ACADEMIC) hits.keys.sorted() else emptyList(),
                groundedFileId = if (role == SegmentRole.ACADEMIC) groundedFileId else null,
                groundedFileName = null
            ), hits)
        }
        val perSegment = understood.associate { (seg, understanding, hits) ->
            seg.id to Triple(understanding, hits, seg)
        }

        val academicRanked = perSegment.values
            .filter { it.first.role == SegmentRole.ACADEMIC }
            .sortedWith(
                compareByDescending<Triple<SegmentUnderstanding, Map<String, List<Pair<Long, Long>>>, ListenerSegment>> { it.first.matchedTerms.size }
                    .thenBy { it.third.position }
            )
            .map { it.third to it.first }
        val transcriptOnlyRanked = perSegment.values
            .filter { it.first.role == SegmentRole.TRANSCRIPT_ONLY }
            .sortedBy { it.third.position }
            .map { it.third to it.first }
        val keyPoints = (academicRanked + transcriptOnlyRanked)
            .take(MAX_KEY_POINTS)
            .mapNotNull { (seg, _) ->
                val text = seg.transcript.trim()
                if (text.isEmpty()) null
                else LectureKeyPoint(
                    segmentId = seg.id,
                    position = seg.position,
                    text = if (text.length <= KEY_POINT_MAX_CHARS) text
                    else text.take(KEY_POINT_MAX_CHARS) + "…"
                )
            }

        val seenFiles = linkedMapOf<Long, GroundedLectureSource>()
        for ((seg, understanding) in academicRanked) {
            val fid = understanding.groundedFileId ?: continue
            if (seenFiles.containsKey(fid)) continue
            if (seenFiles.size >= MAX_SOURCES) break
            val file = runCatching { academicDao.file(fid) }.getOrNull() ?: continue
            val weekId = runCatching { academicDao.week(file.weekId)?.id }.getOrNull()
            val chunkId = perSegment[seg.id]?.second?.values?.flatten()
                ?.map { it.second }?.sorted()?.firstOrNull() ?: -1
            seenFiles[fid] = GroundedLectureSource(
                fileId = fid,
                fileName = file.fileName,
                weekId = weekId,
                terms = understanding.matchedTerms,
                chunkId = chunkId
            )
        }
        val ready = ordered.count { it.transcriptStatus == ListenerSegment.STATUS_READY }
        val byRole = perSegment.values.map { it.first.role }
        SessionUnderstanding(
            sessionId = sessionId,
            semesterId = semesterId,
            totalSegments = ordered.size,
            readyCount = ready,
            academicCount = byRole.count { it == SegmentRole.ACADEMIC },
            fillerCount = byRole.count { it == SegmentRole.FILLER },
            transcriptOnlyCount = byRole.count { it == SegmentRole.TRANSCRIPT_ONLY },
            unknownCount = byRole.count { it == SegmentRole.UNKNOWN },
            keyPoints = keyPoints,
            groundedSources = seenFiles.values.toList(),
            practiceFileId = seenFiles.values.firstOrNull()?.fileId
        )
    }
}
