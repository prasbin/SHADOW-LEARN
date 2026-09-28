package com.prasbin.shadowlearn.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Phase 1 DAO: hierarchy CRUD + counts the dashboard/settings read. */
@Dao
interface AcademicDao {

    // ---- inserts -----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertYear(year: AcademicYear): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSemester(semester: Semester): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertModule(module: Module): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWeek(week: Week): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFile(file: AcademicFile): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertContent(content: SourceFile): Long

    @Update
    suspend fun updateFile(file: AcademicFile)

    @Query("DELETE FROM academic_files WHERE id = :id")
    suspend fun deleteFile(id: Long)

    // ---- reads -------------------------------------------------------------

    @Query("SELECT * FROM academic_years ORDER BY sortOrder, name")
    fun observeYears(): Flow<List<AcademicYear>>

    @Query("SELECT * FROM academic_years ORDER BY sortOrder, name")
    suspend fun getYears(): List<AcademicYear>

    @Query("SELECT * FROM semesters WHERE yearId = :yearId ORDER BY sortOrder, name")
    fun observeSemesters(yearId: Long): Flow<List<Semester>>

    @Query("SELECT * FROM semesters WHERE yearId = :yearId ORDER BY sortOrder, name")
    suspend fun getSemesters(yearId: Long): List<Semester>

    @Query("SELECT * FROM modules WHERE semesterId = :semesterId ORDER BY name")
    fun observeModules(semesterId: Long): Flow<List<Module>>

    @Query("SELECT COUNT(*) FROM modules")
    fun observeModuleCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM modules")
    suspend fun getModuleCount(): Int

    @Query("SELECT COUNT(*) FROM academic_files")
    suspend fun getFileCount(): Int

    /** Reactive file count for the Phase 12 derived-progress engine. */
    @Query("SELECT COUNT(*) FROM academic_files")
    fun observeFileCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM source_files")
    suspend fun getContentCount(): Int

    @Query("SELECT * FROM source_files ORDER BY sha256")
    suspend fun getSources(): List<SourceFile>

    @Query("SELECT COUNT(*) FROM source_files WHERE refCount > 0")
    suspend fun getReferencedContentCount(): Int

    @Query("SELECT * FROM academic_files WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findFileByHash(sha256: String): AcademicFile?

    @Query("SELECT * FROM modules WHERE semesterId = :semesterId AND name = :name LIMIT 1")
    suspend fun findModule(semesterId: Long, name: String): Module?

    @Query("SELECT * FROM weeks WHERE moduleId = :moduleId AND weekNumber = :weekNumber LIMIT 1")
    suspend fun findWeek(moduleId: Long, weekNumber: Int): Week?

    // ---- Phase 3 reconciliation lookups ------------------------------------

    /** Row at the same logical position (semester + relativePath). */
    @Query(
        "SELECT f.* FROM academic_files f " +
            "JOIN weeks w ON w.id = f.weekId " +
            "JOIN modules m ON m.id = w.moduleId " +
            "WHERE m.semesterId = :semesterId AND f.relativePath = :relativePath LIMIT 1"
    )
    suspend fun findFileByPosition(semesterId: Long, relativePath: String): AcademicFile?

    /** Any row already holding this content within the semester (duplicate check). */
    @Query(
        "SELECT f.* FROM academic_files f " +
            "JOIN weeks w ON w.id = f.weekId " +
            "JOIN modules m ON m.id = w.moduleId " +
            "WHERE m.semesterId = :semesterId AND f.sha256 = :sha256 LIMIT 1"
    )
    suspend fun findFileByHashInSemester(semesterId: Long, sha256: String): AcademicFile?

    /** Content row owning the physical copy for this hash (app-global). */
    @Query("SELECT * FROM source_files WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findContentByHash(sha256: String): SourceFile?

    // ---- Phase 3 refcount maintenance (run at end of each import) ----------

    @Query(
        "UPDATE source_files SET refCount = " +
            "(SELECT COUNT(*) FROM academic_files WHERE academic_files.sourceFileId = source_files.id)"
    )
    suspend fun refreshRefCounts()

    @Query("SELECT * FROM source_files WHERE refCount <= 0")
    suspend fun zeroRefContents(): List<SourceFile>

    @Query("DELETE FROM source_files WHERE refCount <= 0")
    suspend fun deleteZeroRefContents()

    // ---- Phase 2 hierarchy browser -----------------------------------------

    @Query("SELECT * FROM modules WHERE semesterId = :semesterId ORDER BY name")
    suspend fun getModules(semesterId: Long): List<Module>

    @Query("SELECT * FROM weeks WHERE moduleId = :moduleId ORDER BY weekNumber")
    fun observeWeeks(moduleId: Long): Flow<List<Week>>

    @Query("SELECT * FROM weeks WHERE moduleId = :moduleId ORDER BY weekNumber")
    suspend fun getWeeks(moduleId: Long): List<Week>

    @Query("SELECT * FROM academic_files WHERE weekId = :weekId ORDER BY fileName")
    fun observeFiles(weekId: Long): Flow<List<AcademicFile>>

    @Query("SELECT * FROM academic_files WHERE weekId = :weekId ORDER BY fileName")
    suspend fun getFiles(weekId: Long): List<AcademicFile>

    @Query("SELECT COUNT(*) FROM academic_files WHERE weekId IN (SELECT id FROM weeks WHERE moduleId IN (SELECT id FROM modules WHERE semesterId = :semesterId))")
    fun observeSemesterFileCount(semesterId: Long): Flow<Int>

    // ---- deletes (cascades via FKs) ----------------------------------------

    @Query("DELETE FROM academic_years WHERE id = :id")
    suspend fun deleteYear(id: Long)
}
