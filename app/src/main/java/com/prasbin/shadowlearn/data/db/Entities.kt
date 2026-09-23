package com.prasbin.shadowlearn.data.db

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
