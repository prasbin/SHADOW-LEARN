package com.prasbin.shadowlearn.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Phase 5 read model: resolves FTS chunk hits back to their academic
 * context (file + module + week) in ONE join, scoped to the current
 * semester. Deliberately does NOT select chunk text here — the FTS hit
 * already carries it, avoiding duplicated full-text in memory.
 */
@Dao
interface SearchDao {

    /** Metadata row for one chunk hit (no chunk text). */
    @Query(
        "SELECT c.id AS chunkId, c.chunkIndex, c.pageNumber, c.charCount, " +
            "c.academicFileId, f.fileName, f.fileType, f.classType, f.relativePath, " +
            "m.name AS moduleName, w.weekNumber, w.title AS weekTitle " +
            "FROM document_chunks c " +
            "JOIN academic_files f ON f.id = c.academicFileId " +
            "JOIN weeks w ON w.id = f.weekId " +
            "JOIN modules m ON m.id = w.moduleId " +
            "WHERE m.semesterId = :semesterId AND c.id IN (:chunkIds)"
    )
    suspend fun resolveChunks(semesterId: Long, chunkIds: List<Long>): List<ChunkHitRow>

    /** How many chunks are indexed in the semester (drives the empty/scope state). */
    @Query(
        "SELECT COUNT(*) FROM document_chunks c " +
            "JOIN academic_files f ON f.id = c.academicFileId " +
            "JOIN weeks w ON w.id = f.weekId " +
            "JOIN modules m ON m.id = w.moduleId " +
            "WHERE m.semesterId = :semesterId"
    )
    fun observeIndexedChunkCount(semesterId: Long): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM document_chunks c " +
            "JOIN academic_files f ON f.id = c.academicFileId " +
            "JOIN weeks w ON w.id = f.weekId " +
            "JOIN modules m ON m.id = w.moduleId " +
            "WHERE m.semesterId = :semesterId"
    )
    suspend fun indexedChunkCount(semesterId: Long): Int
}

/** Column projection for one chunk hit (see [SearchDao.resolveChunks]). */
data class ChunkHitRow(
    val chunkId: Long,
    val chunkIndex: Int,
    val pageNumber: Long?,
    val charCount: Int,
    val academicFileId: Long,
    val fileName: String,
    val fileType: String,
    val classType: String,
    val relativePath: String,
    val moduleName: String,
    val weekNumber: Int?,
    val weekTitle: String?
)