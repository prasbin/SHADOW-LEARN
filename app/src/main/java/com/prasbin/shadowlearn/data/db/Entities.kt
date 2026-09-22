package com.prasbin.shadowlearn.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
 * A file attached to a week (lecture / tutorial / workshop material).
 *
 * [sha256] is the content hash used by the Phase 3 incremental-update engine
 * for new/changed/duplicate detection. [indexed] marks whether Phase 4
 * extraction has processed this file. Content chunks, pages, slides and
 * topics live in their own tables (added in later phases, referencing
 * [AcademicFile.id]) so this schema never needs a destructive redesign.
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
    /** Hex-encoded SHA-256 of file content. Empty until the Phase 3 engine computes it. */
    val sha256: String = "",
    val fileSize: Long = 0,
    val lastModified: Long = 0,
    val indexed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
