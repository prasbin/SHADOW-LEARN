package com.prasbin.shadowlearn.data.db

import com.prasbin.shadowlearn.data.cards.ReviewScheduler
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lecture / Tutorial / Workshop origin of an [AcademicFile]. Stored as name string. */
enum class ClassType {
    LECTURE, TUTORIAL, WORKSHOP, OTHER
}

/** Academic year container, e.g. "Year 2". */
@Entity(tableName = "academic_years")
data class AcademicYear(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int
)

/** Semester within a year, e.g. "Semester 1". */
@Entity(
    tableName = "semesters",
    foreignKeys = [
        ForeignKey(
            entity = AcademicYear::class,
            parentColumns = ["id"],
            childColumns = ["yearId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("yearId")]
)
data class Semester(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val yearId: Long,
    val name: String,
    val sortOrder: Int
)

/** Teaching module within a semester, e.g. "Artificial Intelligence". */
@Entity(
    tableName = "modules",
    foreignKeys = [
        ForeignKey(
            entity = Semester::class,
            parentColumns = ["id"],
            childColumns = ["semesterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("semesterId")]
)
data class Module(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val semesterId: Long,
    val name: String,
    /** Short code when the syllabus provides one, e.g. "CS201". Null otherwise. */
    val code: String? = null
)

/** Week within a module, e.g. Week 4. */
@Entity(
    tableName = "weeks",
    foreignKeys = [
        ForeignKey(
            entity = Module::class,
            parentColumns = ["id"],
            childColumns = ["moduleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("moduleId")]
)
data class Week(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val moduleId: Long,
    val weekNumber: Int,
    val title: String? = null
)

/**
 * Phase 3 content identity: one row per distinct SHA-256 across the whole
 * app, owning the single physical copy in app-private storage
 * (`<filesDir>/source/<sha256>`, see [SourceFile.storedPath]).
 *
 * [refCount] is how many [AcademicFile] rows currently reference this
 * content (recomputed after every import by `refreshRefCounts`). Rows whose
 * refCount falls to zero are deleted together with their physical file —
 * this is the only removal path exercised by Phase 3 (orphaned copies from
 * superseded/legacy data are NOT deleted; see docs/ARCHITECTURE.md).
 */
@Entity(
    tableName = "source_files",
    indices = [Index(value = ["sha256"], unique = true)]
)
data class SourceFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Hex-encoded SHA-256 content hash (content identity, globally unique). */
    val sha256: String,
    /** Absolute app-private path of the canonical physical copy. */
    val storedPath: String,
    val fileSize: Long,
    /** Number of academic_files referencing this content. */
    val refCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/** Extraction lifecycle of one [AcademicFile] (one row per file). */
enum class ExtractionStatus {
    /** Extraction succeeded (may legitimately be an empty document, 0 chunks). */
    EXTRACTED,
    /** Extraction was attempted and failed; the error is recorded. */
    FAILED
}

/**
 * Phase 4 extraction metadata: one row per [AcademicFile].
 *
 * [sha256] is the content hash at extraction time — the incremental
 * no-re-extract key: an [EXTRACTED] row whose [sha256] matches the current
 * [AcademicFile.sha256] is never re-extracted (see docs/ARCHITECTURE.md).
 * A [FAILED] row with a matching hash is retried on the next pass (honest
 * failure isolation, no artificial success).
 *
 * Extracted text itself never lives here; it is split into
 * [DocumentChunk]s (with page/slide references where available).
 */
@Entity(
    tableName = "extraction_meta",
    foreignKeys = [
        ForeignKey(
            entity = AcademicFile::class,
            parentColumns = ["id"],
            childColumns = ["academicFileId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ExtractionMeta(
    @PrimaryKey val academicFileId: Long,
    /** SHA-256 at extraction time (incremental skip / reuse key). */
    val sha256: String,
    /** [ExtractionStatus] name. */
    val status: String,
    /** Extractor that produced this result, e.g. "pdf", "docx", "txt". */
    val format: String,
    val charCount: Long,
    val chunkCount: Int,
    val error: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

/**
 * Phase 4 document chunk: one row per extractable unit of text from an
 * [AcademicFile]. Chunk granularity is one per page/slide where the format
 * provides them, split at [TextChunker.MAX_CHUNK_CHARS] (word boundaries)
 * when a single page/slide is too long.
 *
 * The chunk keeps [AcademicFile] traceability and an optional stable
 * [pageNumber] / slide reference. The same text is mirrored into the FTS
 * virtual index table (`document_fts`, rowid == this table's [id]) — see
 * `data/search/FtsIndex.kt`. Deleting a file cascades here (Room FK); the
 * FTS mirror is cleaned together with chunk deletion on re-extraction.
 */
@Entity(
    tableName = "document_chunks",
    foreignKeys = [
        ForeignKey(
            entity = AcademicFile::class,
            parentColumns = ["id"],
            childColumns = ["academicFileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("academicFileId"), Index("chunkIndex")]
)
data class DocumentChunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val academicFileId: Long,
    /** Deterministic 0-based order within the file. */
    val chunkIndex: Int,
    /** Page (PDF) or slide (PPTX) reference where realistically available, else null. */
    val pageNumber: Long? = null,
    val text: String,
    val charCount: Int,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Phase 7 listener session: one row per lecture recording. Created
 * `recording` by ListenerRepository, moved through `paused`, closed as
 * `completed` on stop — or marked `interrupted`/`failed` when the process
 * dies or the recorder errors, so an incomplete session is always visible
 * and never silently half-present.
 *
 * [semesterId] is a PLAIN historical reference (same rule as quiz history):
 * re-importing or deleting academic content must never cascade-delete
 * listener history. [audioPath] is the app-private recording file
 * (`filesDir/listener/…`); raw audio bytes are NEVER stored in SQLite.
 */
@Entity(
    tableName = "listener_sessions",
    indices = [Index("semesterId"), Index("status")]
)
data class ListenerSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Semester the recording belongs to (snapshot of the active scope). */
    val semesterId: Long,
    /** recording / paused / completed / interrupted / failed. */
    val status: String = STATUS_RECORDING,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    /** App-private audio file path; null until the recorder file exists. */
    val audioPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_RECORDING = "recording"
        const val STATUS_PAUSED = "paused"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_INTERRUPTED = "interrupted"
        const val STATUS_FAILED = "failed"
    }
}

/**
 * Phase 7 listener segment: one row per continuous recording span inside a
 * [ListenerSession]. Pause/resume boundaries cut segments; each segment is
 * the future unit of transcription (Phase 8+).
 *
 * [startedAtMs]/[durationMs] are monotonic-clock offsets/durations in
 * milliseconds (not wall time), so pause math is exact. [transcript] is a
 * placeholder until real speech-to-text lands — [transcriptStatus] says so
 * honestly (`pending` / `ready` / `failed`); Phase 7 never fabricates
 * transcript text.
 */
@Entity(
    tableName = "listener_segments",
    foreignKeys = [
        ForeignKey(
            entity = ListenerSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ListenerSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** 0-based order within the session. */
    val position: Int,
    /** Monotonic-clock offset from session start when this span began. */
    val startedAtMs: Long,
    /** Span length in ms; 0 while the span is still open. */
    val durationMs: Long = 0,
    /** Transcript text; Phase 7 always writes the pending placeholder. */
    val transcript: String = TRANSCRIPT_PENDING,
    /** pending / ready / failed. */
    val transcriptStatus: String = STATUS_PENDING
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_READY = "ready"
        const val STATUS_FAILED = "failed"
        const val TRANSCRIPT_PENDING = "Transcript pending."
    }
}

/**
 * Phase 8 flashcard deck: one review collection per semester (the UI builds
 * and refreshes a single deck per semester; the DAO allows more).
 *
 * [semesterId] is a PLAIN historical reference (same rule as quiz/listener
 * history): academic re-imports must never cascade-delete review material.
 */
@Entity(
    tableName = "flashcard_decks",
    indices = [Index("semesterId")]
)
data class FlashcardDeck(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Semester the deck was built for (snapshot of the active scope). */
    val semesterId: Long,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Phase 8 flashcard: a verbatim-corpus review card with SM-2-lite schedule.
 *
 * Content rule (enforced by CardGenerator, guaranteed by schema): front/back
 * are VERBATIM strings from the material — never invented. The three source
 * references are PLAIN nullable columns (never FKs into academic tables),
 * so cards survive chunk re-extraction and file deletes; [sourceLabel] is
 * the human citation snapshot shown on every card. [contentKey] is the
 * deterministic dedup key (`chunk:<id>:<term>` / `quiz:<qid>` /
 * `seg:<sid>`), unique per deck — rebuilds IGNORE duplicates.
 *
 * Scheduling: [dueAt] is the ONE canonical timestamp (UTC-day-start +
 * interval); there is deliberately no second `nextReview` column.
 * [suspended] cards never enter the due queue.
 */
@Entity(
    tableName = "flashcards",
    foreignKeys = [
        ForeignKey(
            entity = FlashcardDeck::class,
            parentColumns = ["id"],
            childColumns = ["deckId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("deckId"), Index("dueAt"), Index("suspended"), Index(value = ["deckId", "contentKey"], unique = true)]
)
data class Flashcard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deckId: Long,
    val front: String,
    val back: String,
    /** Chunk the card was derived from (plain column, no FK). */
    val sourceChunkId: Long? = null,
    /** Quiz question the card reviews (plain column, no FK). */
    val sourceQuestionId: Long? = null,
    /** READY listener segment the card quotes (plain column, no FK). */
    val sourceListenerSegmentId: Long? = null,
    /** Human citation snapshot, e.g. "neural.pdf · PDF · PAGE 2". */
    val sourceLabel: String,
    /** Deterministic dedup key, unique within the deck. */
    val contentKey: String,
    /** SM-2-lite easiness, bounded [1.3, 2.8]. */
    val easeFactor: Double = ReviewScheduler.INITIAL_EASE,
    /** SM-2-lite interval in whole days, bounded [0, 36500]. */
    val intervalDays: Int = 0,
    /** Canonical scheduling timestamp (UTC-day-start + interval). */
    val dueAt: Long = 0,
    val suspended: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Phase 8 review session: one persisted run through a due queue.
 *
 * The reviewed card ORDER is not stored as a list — it is re-derived
 * deterministically (due queue minus already-reviewed events), so resume
 * needs no extra table. Counts are snapshotted at completion so history
 * never depends on later card edits; [status] is IN_PROGRESS / COMPLETED /
 * INTERRUPTED. [deckId] is deliberately PLAIN: deleting a deck must not
 * erase the review history.
 */
@Entity(
    tableName = "flashcard_review_sessions",
    indices = [Index("deckId"), Index("status")]
)
data class ReviewSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Deck reviewed (plain reference — history survives deck deletion). */
    val deckId: Long,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val reviewedCount: Int = 0,
    val retainedCount: Int = 0,
    /** IN_PROGRESS / COMPLETED / INTERRUPTED. */
    val status: String = STATUS_IN_PROGRESS
) {
    companion object {
        const val STATUS_IN_PROGRESS = "IN_PROGRESS"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_INTERRUPTED = "INTERRUPTED"
    }
}

/**
 * Phase 8 review event: one persisted rating. Together with the card's
 * schedule snapshot (previous → new ease/interval) this is the auditable
 * review history — never reconstructed from current card state.
 * [flashcardId] is PLAIN (a deleted card must not erase its events).
 */
@Entity(
    tableName = "flashcard_review_events",
    foreignKeys = [
        ForeignKey(
            entity = ReviewSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ReviewEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** Card rated (plain reference). */
    val flashcardId: Long,
    /** AGAIN / HARD / GOOD / EASY. */
    val rating: String,
    val reviewedAt: Long = System.currentTimeMillis(),
    val previousEaseFactor: Double,
    val newEaseFactor: Double,
    val previousIntervalDays: Int,
    val newIntervalDays: Int,
    /** True unless the rating was AGAIN (see ReviewScheduler). */
    val retained: Boolean
)

/**
 * Phase 6 quiz session: one row per run of the daily quiz. Created
 * `in_progress` by QuizRepository, marked `completed` with final totals when
 * the last question is answered. [seed] fixes the deterministic generation
 * so a run is reproducible; correctCount/XP/streak are written ONLY from the
 * real completed answers (never fabricated — analytics detail lands with
 * Progress in Phase 9).
 */
@Entity(
    tableName = "quiz_sessions",
    indices = [Index("semesterId"), Index("status")]
)
data class QuizSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Semester the quiz was generated for (snapshot of the active scope). */
    val semesterId: Long,
    /** RNG seed every question in this run was derived from. */
    val seed: Long,
    val totalQuestions: Int,
    val correctCount: Int = 0,
    /** XP awarded at completion: correct × QuizRepository.XP_PER_CORRECT. */
    val xpEarned: Int = 0,
    /** Day streak at completion, computed from prior completed rows. */
    val streak: Int = 0,
    val status: String = STATUS_IN_PROGRESS,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    companion object {
        const val STATUS_IN_PROGRESS = "in_progress"
        const val STATUS_COMPLETED = "completed"
    }
}

/**
 * Phase 6 quiz question: one row per question inside a [QuizSession].
 *
 * The prompt, options (JSON array — MCQ / fill-blank only; null for
 * true-false), correct answer and the SOURCE citation are SNAPSHOTTED at
 * generation time (srcFileName/srcPage/srcExcerpt), so a completed session
 * stays fully reviewable even if the source file is later re-extracted or
 * removed. [chunkId]/[academicFileId] are deliberately PLAIN columns with no
 * foreign key (mirroring `AcademicFile.sourceFileId`): deleting or
 * re-importing academic content must never cascade-delete quiz history.
 */
@Entity(
    tableName = "quiz_questions",
    foreignKeys = [
        ForeignKey(
            entity = QuizSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class QuizQuestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** 0-based order within the session. */
    val position: Int,
    /** Chunk the question was generated from (plain column, no FK). */
    val chunkId: Long,
    /** Owning academic file (plain column, no FK). */
    val academicFileId: Long,
    /** See data/quiz QuestionType (MCQ / TRUE_FALSE / FILL_BLANK). */
    val questionType: String,
    val prompt: String,
    /** JSON array of option strings; null for true-false (True/False implied). */
    val optionsJson: String?,
    val correctAnswer: String,
    /** The option the user picked; null until answered. */
    val userAnswer: String? = null,
    val isCorrect: Boolean? = null,
    /** Citation snapshot: source file name / type / page-or-slide / excerpt. */
    val srcFileName: String,
    val srcFileType: String,
    val srcPage: Long? = null,
    val srcExcerpt: String
)

/**
 * A file attached to a week (lecture / tutorial / workshop material).
 *
 * [sha256] is the hex-encoded SHA-256 content hash, computed at import time.
 * Phase 3 reconciliation treats it as the *content identity* — the row's
 * bytes live at [SourceFile.storedPath] (shared when identical content
 * appears at several positions). [sourceFileId] links this reference to its
 * content row. NOTE: deliberately a plain column, NOT a Room foreign key —
 * deleting an academic reference must never cascade-delete content, and
 * content removal is gated only by [SourceFile.refCount].
 *
 * [indexed] marks whether Phase 4 extraction has processed this file.
 * [classType] records the Lecture/Tutorial/Workshop folder the file came
 * from (`OTHER` when none applied). [relativePath] is the file's original
 * normalized logical path inside the imported archive, for traceability and
 * the Phase 3 *position identity* `(semesterId, relativePath)`.
 * [filePath] is the app-private stored copy (the content's canonical copy).
 *
 * Content chunks, pages, slides and topics live in their own tables (added
 * in later phases, referencing [AcademicFile.id]) so this schema never needs
 * a destructive redesign.
 */
@Entity(
    tableName = "academic_files",
    foreignKeys = [
        ForeignKey(
            entity = Week::class,
            parentColumns = ["id"],
            childColumns = ["weekId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("weekId"), Index("sha256")]
)
data class AcademicFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weekId: Long,
    val fileName: String,
    /** Storage location (SAF tree path or app-private path). Never assumed stable across reinstalls. */
    val filePath: String,
    /** Lowercase extension without dot, e.g. "pdf", "pptx". */
    val fileType: String,
    /** Hex-encoded SHA-256 of file content, computed at import time. */
    val sha256: String = "",
    /** Lecture / Tutorial / Workshop origin folder; OTHER when none applied. */
    @ColumnInfo(defaultValue = "'OTHER'")
    val classType: String = ClassType.OTHER.name,
    /** Original normalized logical path inside the imported archive, e.g. "AI.zip/Week 1/Lecture/l1.pdf". */
    @ColumnInfo(defaultValue = "''")
    val relativePath: String = "",
    val fileSize: Long = 0,
    val lastModified: Long = 0,
    val indexed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Phase 3: reference to the [SourceFile] content row owning the physical copy. Null for pre-v3 rows. */
    val sourceFileId: Long? = null
)
