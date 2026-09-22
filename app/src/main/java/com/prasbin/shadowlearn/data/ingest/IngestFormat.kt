package com.prasbin.shadowlearn.data.ingest

import com.prasbin.shadowlearn.data.db.ClassType

/**
 * Phase 2 format rules — pure Kotlin, no Android dependencies (unit-tested).
 *
 * Documented parsing assumptions (see docs/ARCHITECTURE.md for the full list):
 * - Hierarchy is derived from folder/ZIP names deterministically; ambiguous
 *   input gets a documented fallback, never a guessed "certain" placement.
 * - Module/week/class names are preserved verbatim (trimmed only).
 */
object IngestFormat {

    /** Extensions stored as academic source files (lowercase, no dot). */
    val STORED_EXTENSIONS: Set<String> = setOf(
        "pdf", "ppt", "pptx", "doc", "docx", "txt", "md", "rtf",
        "csv", "json", "xml", "html", "htm", "css", "sql",
        "py", "java", "kt", "js", "ts", "c", "h", "cpp", "hpp", "ipynb"
    )

    const val ZIP_EXTENSION = "zip"

    private val WEEK_REGEX = Regex("(?i)^week[\\s_\\-]*(\\d{1,3})\$")
    private val CLASS_ALIASES = mapOf(
        "lecture" to ClassType.LECTURE,
        "lectures" to ClassType.LECTURE,
        "tutorial" to ClassType.TUTORIAL,
        "tutorials" to ClassType.TUTORIAL,
        "workshop" to ClassType.WORKSHOP,
        "workshops" to ClassType.WORKSHOP
    )

    /** Safety caps (see docs for rationale). */
    const val MAX_NESTING_DEPTH = 10
    const val MAX_ENTRY_BYTES = 100L * 1024 * 1024 // 100 MB per stored file
    const val MAX_TOTAL_BYTES = 1L * 1024 * 1024 * 1024 // 1 GB per import
    const val MAX_ENTRIES = 20_000

    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    fun isZip(name: String): Boolean = extensionOf(name) == ZIP_EXTENSION

    fun isSupported(name: String): Boolean = extensionOf(name) in STORED_EXTENSIONS

    /** Parses "Week 3" (also "week_3", "WEEK-3") → 3, else null. */
    fun parseWeekNumber(segment: String): Int? {
        val stem = segment.trim().removeSuffix(".zip")
        return WEEK_REGEX.matchEntire(stem.trim())?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Maps a folder name to a class type, else null. */
    fun parseClassType(segment: String): ClassType? {
        val stem = segment.trim().removeSuffix(".zip")
        return CLASS_ALIASES[stem.trim().lowercase()]
    }

    /** File stem without extension, trimmed; empty if the name has no stem. */
    fun stemOf(fileName: String): String =
        fileName.substringAfterLast('/').substringBeforeLast('.').trim()

    /**
     * Makes a relative storage path safe for app-private files: keeps
     * slashes, replaces illegal characters, caps segment length.
     */
    fun sanitizeRelativePath(relativePath: String): String =
        relativePath.split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { seg ->
                seg.replace(Regex("[\\\\:*?\"<>|]"), "_").take(120)
            }
            .filter { it.isNotEmpty() }
            .joinToString("/")
            .ifEmpty { "unnamed" }
}
