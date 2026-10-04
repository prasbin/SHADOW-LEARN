package com.prasbin.shadowlearn.data.intelligence

/**
 * I1 evidence objects (contract §§3, 13). Each wraps persisted rows with
 * provenance — never a conclusion. All are DERIVED at read time; nothing
 * here is stored. See docs/I1_EVIDENCE_CONTRACT.md.
 */

/** One wrong answer on a completed quiz (AssessmentEvidence). */
data class MistakeEvidence(
    val questionId: Long,
    val sessionId: Long,
    /** Owning file (plain column); null only for legacy rows. */
    val academicFileId: Long?,
    val chunkId: Long?,
    /** Snapshot name — survives file deletion (dangling-source case). */
    val srcFileName: String,
    /** Session-grained: questions carry no per-answer timestamp. */
    val observedAt: Long
)

/** One correct answer on a completed quiz (improvement evidence). */
data class CorrectEvidence(
    val questionId: Long,
    val sessionId: Long,
    val academicFileId: Long?,
    val srcFileName: String,
    val observedAt: Long
)

/** One AGAIN rating (ReviewEvidence). Rating semantics stay engine-defined. */
data class AgainEvidence(
    val eventId: Long,
    /** Owning file resolved via card chunk; null when unresolvable. */
    val fileId: Long?,
    /** Card citation snapshot (display fallback, never parsed for links). */
    val srcLabel: String,
    val reviewedAt: Long
)

/** Academic position of a file, resolved via FK walk (contract §8). */
data class SourceScope(
    val fileId: Long,
    val fileName: String,
    val weekId: Long?,
    val weekLabel: String?,
    val moduleId: Long?,
    val moduleName: String?
)
