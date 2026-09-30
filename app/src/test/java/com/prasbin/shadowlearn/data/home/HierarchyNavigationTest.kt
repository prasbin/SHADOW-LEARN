package com.prasbin.shadowlearn.data.home

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.db.Week
import com.prasbin.shadowlearn.navigation.Routes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hierarchy slice reads over REAL Room rows: each level filters by its
 * parent, empty levels are honest (empty lists / nulls — never invented
 * rows), and browsing never depends on configured scope.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HierarchyNavigationTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var dao: AcademicDao

    private var yearA: Long = 0
    private var yearB: Long = 0
    private var semA1: Long = 0
    private var semA2: Long = 0
    private var modId: Long = 0
    private var weekId: Long = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ShadowLearnDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.academicDao()
        runBlocking {
            yearA = dao.insertYear(AcademicYear(name = "Year 1", sortOrder = 0))
            yearB = dao.insertYear(AcademicYear(name = "Year 2", sortOrder = 1))
            semA1 = dao.insertSemester(Semester(yearId = yearA, name = "S1", sortOrder = 0))
            semA2 = dao.insertSemester(Semester(yearId = yearA, name = "S2", sortOrder = 1))
            modId = dao.insertModule(Module(semesterId = semA1, name = "M", code = "CS201"))
            weekId = dao.insertWeek(Week(moduleId = modId, weekNumber = 1, title = "W1"))
            dao.insertFile(
                AcademicFile(
                    weekId = weekId, fileName = "lecture1.pdf", filePath = "/tmp/lecture1.pdf",
                    fileType = "pdf", sha256 = "abc", relativePath = "M/Week 1/lecture1.pdf"
                )
            )
        }
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun years_loadAll() = runBlocking {
        val years = dao.getYears()
        assertEquals(2, years.size)
        assertEquals("Year 1", years[0].name)
    }

    @Test
    fun semesters_filterByYear() = runBlocking {
        assertEquals(2, dao.getSemesters(yearA).size)
        assertTrue(dao.getSemesters(yearB).isEmpty())
    }

    @Test
    fun modules_filterBySemester() = runBlocking {
        val modules = dao.getModules(semA1)
        assertEquals(1, modules.size)
        assertEquals("CS201", modules[0].code)
        assertTrue(dao.getModules(semA2).isEmpty())
    }

    @Test
    fun weeks_filterByModule() = runBlocking {
        val weeks = dao.getWeeks(modId)
        assertEquals(1, weeks.size)
        assertEquals(1, weeks[0].weekNumber)
    }

    @Test
    fun files_filterByWeek() = runBlocking {
        val files = dao.getFiles(weekId)
        assertEquals(1, files.size)
        assertEquals("lecture1.pdf", files[0].fileName)
        assertTrue(dao.getFiles(weekId + 999).isEmpty())
    }

    @Test
    fun idLookups_resolveBreadcrumbChain() = runBlocking {
        assertEquals("Year 1", dao.year(yearA)?.name)
        assertEquals("S1", dao.semester(semA1)?.name)
        assertEquals("M", dao.module(modId)?.name)
        assertEquals(1, dao.week(weekId)?.weekNumber)
        assertEquals("lecture1.pdf", dao.file(1)?.fileName)
    }

    @Test
    fun idLookups_missingRowsAreNull() = runBlocking {
        assertNull(dao.year(999))
        assertNull(dao.semester(999))
        assertNull(dao.module(999))
        assertNull(dao.week(999))
        assertNull(dao.file(999))
    }

    @Test
    fun routes_buildExpectedDestinations() {
        assertEquals("hierarchy", Routes.hierarchyRoot())
        assertEquals("hierarchy/year/1", Routes.hierarchyYear(1))
        assertEquals("hierarchy/semester/2", Routes.hierarchySemester(2))
        assertEquals("hierarchy/module/3", Routes.hierarchyModule(3))
        assertEquals("hierarchy/week/4", Routes.hierarchyWeek(4))
    }
}
