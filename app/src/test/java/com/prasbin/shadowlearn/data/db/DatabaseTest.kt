package com.prasbin.shadowlearn.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1 database tests (local JVM via Robolectric):
 * creation, hierarchy insert/retrieve, hash lookup, cascade delete,
 * and schema-version assertion (migration infrastructure, v4 baseline).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Highest API Robolectric 4.14.1 supports; app targets 36 — fine for DB tests.
class DatabaseTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var dao: AcademicDao

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ShadowLearnDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.academicDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun schemaVersion_isSeven() {
        assertEquals(7, ShadowLearnDatabase.DATABASE_VERSION)
        assertEquals(7, db.openHelper.readableDatabase.version)
    }

    @Test
    fun listenerTables_existAndAcceptRows() = runBlocking {
        val dao = db.listenerDao()
        val id = dao.insertSession(ListenerSession(semesterId = 7))
        dao.insertSegment(ListenerSegment(sessionId = id, position = 0, startedAtMs = 0))
        assertEquals(1, dao.sessionCount(7))
        assertEquals(0, dao.sessionCount(8))
        val stored = dao.session(id)!!
        assertEquals(ListenerSession.STATUS_RECORDING, stored.status)
        assertEquals(1, dao.segments(id).size)
        assertEquals(ListenerSegment.STATUS_PENDING, dao.segments(id)[0].transcriptStatus)
    }

    @Test
    fun hierarchy_insertAndRetrieve() = runBlocking {
        val yearId = dao.insertYear(AcademicYear(name = "Year 2", sortOrder = 2))
        val semId = dao.insertSemester(Semester(yearId = yearId, name = "Semester 1", sortOrder = 1))
        val modId = dao.insertModule(Module(semesterId = semId, name = "Artificial Intelligence"))
        val weekId = dao.insertWeek(Week(moduleId = modId, weekNumber = 1))
        val fileId = dao.insertFile(
            AcademicFile(
                weekId = weekId,
                fileName = "lecture1.pdf",
                filePath = "/tmp/lecture1.pdf",
                fileType = "pdf",
                sha256 = "abc123",
                fileSize = 1024,
                lastModified = 1L
            )
        )

        assertTrue(yearId > 0 && semId > 0 && modId > 0 && weekId > 0 && fileId > 0)
        assertEquals(1, dao.getModuleCount())
        assertEquals(1, dao.getFileCount())
        assertNotNull(dao.findFileByHash("abc123"))
        assertNull(dao.findFileByHash("missing"))
    }

    @Test
    fun deleteYear_cascadesWholeHierarchy() = runBlocking {
        val yearId = dao.insertYear(AcademicYear(name = "Year 1", sortOrder = 1))
        val semId = dao.insertSemester(Semester(yearId = yearId, name = "Semester 1", sortOrder = 1))
        val modId = dao.insertModule(Module(semesterId = semId, name = "Programming"))
        val weekId = dao.insertWeek(Week(moduleId = modId, weekNumber = 1))
        dao.insertFile(
            AcademicFile(
                weekId = weekId, fileName = "tut1.pdf", filePath = "/tmp/tut1.pdf",
                fileType = "pdf", sha256 = "deadbeef", fileSize = 512, lastModified = 1L
            )
        )

        dao.deleteYear(yearId)

        assertEquals(0, dao.getYears().size)
        assertEquals(0, dao.getSemesters(yearId).size)
        assertEquals(0, dao.getModuleCount())
        assertEquals(0, dao.getFileCount())
    }

    @Test
    fun semesters_scopedToYear() = runBlocking {
        val y1 = dao.insertYear(AcademicYear(name = "Year 1", sortOrder = 1))
        val y2 = dao.insertYear(AcademicYear(name = "Year 2", sortOrder = 2))
        dao.insertSemester(Semester(yearId = y1, name = "Semester 1", sortOrder = 1))
        dao.insertSemester(Semester(yearId = y1, name = "Semester 2", sortOrder = 2))
        dao.insertSemester(Semester(yearId = y2, name = "Semester 1", sortOrder = 1))

        assertEquals(2, dao.getSemesters(y1).size)
        assertEquals(1, dao.getSemesters(y2).size)
    }
}
