package com.prasbin.shadowlearn.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("SELECT * FROM academic_files WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findFileByHash(sha256: String): AcademicFile?

    // ---- deletes (cascades via FKs) ----------------------------------------

    @Query("DELETE FROM academic_years WHERE id = :id")
    suspend fun deleteYear(id: Long)
}
