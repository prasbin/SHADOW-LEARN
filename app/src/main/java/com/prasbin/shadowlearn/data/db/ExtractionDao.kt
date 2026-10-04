package com.prasbin.shadowlearn.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Phase 4 DAO: document chunks + extraction metadata.
 *
 * Chunk table and FTS virtual index are mirrored: chunks persist here (Room
 * entities, schema-exported, cascading FK to [AcademicFile]) while the same
 * text is mirrored into the FTS index by `data/search/FtsIndex.kt` (the FTS
 * table is created via raw virtual-table DDL on first use — see its docs).
 * The repository keeps both sides consistent on the same connection.
 */
@Dao
abstract class ExtractionDao {

    @Insert
    abstract suspend fun insertChunks(chunks: List<DocumentChunk>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMeta(meta: ExtractionMeta): Long

    @Query("DELETE FROM document_chunks WHERE academicFileId = :fileId")
    abstract suspend fun deleteChunksForFile(fileId: Long)

    @Query("DELETE FROM extraction_meta WHERE academicFileId = :fileId")
    abstract suspend fun deleteMeta(fileId: Long)

    @Query("SELECT * FROM extraction_meta WHERE academicFileId = :fileId LIMIT 1")
    abstract suspend fun getMeta(fileId: Long): ExtractionMeta?

    /**
     * An already-EXTRACTED file elsewhere in the same semester holding the
     * same content (duplicate reuse path): when a duplicate is imported we
     * reuse the sibling's extracted representation instead of re-extracting.
     */
    @Query(
        "SELECT em.* FROM extraction_meta em " +
            "JOIN academic_files f ON f.id = em.academicFileId " +
            "JOIN weeks w ON w.id = f.weekId " +
            "JOIN modules m ON m.id = w.moduleId " +
            "WHERE m.semesterId = :semesterId AND em.sha256 = :sha256 " +
            "AND em.status = 'EXTRACTED' AND em.academicFileId != :excludeFileId LIMIT 1"
    )
    abstract suspend fun findExtractedSibling(semesterId: Long, sha256: String, excludeFileId: Long): ExtractionMeta?

    @Query("SELECT * FROM document_chunks WHERE academicFileId = :fileId ORDER BY chunkIndex")
    abstract suspend fun chunksForFile(fileId: Long): List<DocumentChunk>

    /** Single chunk by id (I1 source resolution: card chunk → owning file). */
    @Query("SELECT * FROM document_chunks WHERE id = :id LIMIT 1")
    abstract suspend fun chunk(id: Long): DocumentChunk?

    @Query("SELECT COUNT(*) FROM document_chunks WHERE academicFileId = :fileId")
    abstract suspend fun chunkCountForFile(fileId: Long): Int

    /** Reactive total chunk count for the Phase 12 derived-progress engine. */
    @Query("SELECT COUNT(*) FROM document_chunks")
    abstract fun observeChunkCount(): Flow<Int>

    /** Suspend variant of [observeChunkCount] for one-shot reads/tests. */
    @Query("SELECT COUNT(*) FROM document_chunks")
    abstract suspend fun totalChunkCount(): Int

    @Query(
        "SELECT f.* FROM academic_files f " +
            "JOIN weeks w ON w.id = f.weekId " +
            "JOIN modules m ON m.id = w.moduleId " +
            "WHERE m.semesterId = :semesterId ORDER BY f.id"
    )
    abstract suspend fun filesOfSemester(semesterId: Long): List<AcademicFile>

    /**
     * Replaces this file's chunks and upserts its meta inside one
     * transaction, returning the inserted chunk ids (== FTS rowids).
     */
    @Transaction
    open suspend fun replaceExtraction(fileId: Long, chunks: List<DocumentChunk>, meta: ExtractionMeta): List<Long> {
        deleteChunksForFile(fileId)
        val ids = if (chunks.isEmpty()) emptyList() else insertChunks(chunks)
        upsertMeta(meta)
        return ids
    }
}