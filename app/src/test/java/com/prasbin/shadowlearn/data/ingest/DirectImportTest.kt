package com.prasbin.shadowlearn.data.ingest

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.search.FtsIndex
import com.prasbin.shadowlearn.data.search.RoomBackedSqlExecutor
import com.prasbin.shadowlearn.data.search.SearchOutcome
import com.prasbin.shadowlearn.data.search.SearchRepository
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Direct multi-file upload: verified storage, semester-scoped SHA-256
 * deduplication, per-file extraction to READY, collision-safe naming, and
 * honest rejection of unsupported files. Uses the stream seam so no SAF
 * activity is required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DirectImportTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var repo: IngestRepository
    private lateinit var extraction: ExtractionRepository
    private lateinit var search: SearchRepository
    private var semesterId = 0L
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, ShadowLearnDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = IngestRepository(context, db.academicDao())
        extraction = ExtractionRepository(
            context = context,
            db = db,
            dao = db.extractionDao(),
            academicDao = db.academicDao(),
            fts = ExtractionRepository.ftsIndex(db),
            ioDispatcher = Dispatchers.Unconfined
        )
        search = SearchRepository(
            searchDao = db.searchDao(),
            fts = FtsIndex(RoomBackedSqlExecutor(db.openHelper.writableDatabase)),
            dispatcher = Dispatchers.Unconfined
        )
        val y = db.academicDao().insertYear(AcademicYear(name = "Year 1", sortOrder = 1))
        semesterId = db.academicDao().insertSemester(
            Semester(yearId = y, name = "Semester 1", sortOrder = 1)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun importText(
        text: String,
        name: String,
        module: String = "Biology",
        week: Int = 2
    ): DirectImportResult = runBlocking {
        repo.importStream(
            ByteArrayInputStream(text.toByteArray()),
            name,
            semesterId,
            module,
            week
        )
    }

    @Test
    fun import_storesFileThenExtractsToReady() = runBlocking {
        val result = importText(
            "Photosynthesis converts light into chemical energy.",
            "cell-biology.txt"
        )
        assertTrue("expected Imported, got $result", result is DirectImportResult.Imported)
        val imported = result as DirectImportResult.Imported

        val file = db.academicDao().file(imported.fileId)
        assertNotNull(file)
        assertEquals("cell-biology.txt", file!!.fileName)
        assertEquals("txt", file.fileType)
        assertEquals(51L, file.fileSize)

        val summary = extraction.processFile(imported.fileId)
        assertEquals(0, summary.failed)
        assertEquals(1, summary.extracted)
        assertTrue(summary.indexedChunks > 0)
    }

    @Test
    fun importedFile_isSearchableInSemester() = runBlocking {
        val result = importText(
            "Mitochondria produce ATP through cellular respiration.",
            "mitochondria.txt"
        ) as DirectImportResult.Imported
        extraction.processFile(result.fileId)

        val outcome = search.search("mitochondria", semesterId)
        assertTrue("expected results, got $outcome", outcome is SearchOutcome.Results)
        val hits = (outcome as SearchOutcome.Results).results
        assertEquals(1, hits.size)
        assertEquals(result.fileId, hits.first().academicFileId)
    }

    @Test
    fun identicalContent_isRejectedAsDuplicate() {
        val text = "Osmosis moves water across a semi-permeable membrane."
        assertTrue(importText(text, "osmosis.txt") is DirectImportResult.Imported)
        val second = importText(text, "osmosis-copy.txt")
        assertTrue("expected Duplicate, got $second", second is DirectImportResult.Duplicate)
        assertEquals(1, runBlocking { db.academicDao().getFileCount() })
    }

    @Test
    fun sameNameDifferentContent_getsDistinctRelativePaths() {
        val first = importText("First version of the notes.", "notes.txt")
        val second = importText("Second, different version.", "notes.txt")
        assertTrue(first is DirectImportResult.Imported)
        assertTrue(second is DirectImportResult.Imported)
        val a = runBlocking { db.academicDao().file((first as DirectImportResult.Imported).fileId) }
        val b = runBlocking { db.academicDao().file((second as DirectImportResult.Imported).fileId) }
        assertNotEquals(a!!.relativePath, b!!.relativePath)
        assertEquals(2, runBlocking { db.academicDao().getFileCount() })
    }

    @Test
    fun unsupportedExtension_isRejectedHonestly() {
        val result = importText("MZ...", "payload.exe")
        assertTrue(result is DirectImportResult.Failed)
        assertTrue((result as DirectImportResult.Failed).reason.contains("Unsupported file type"))
        assertEquals(0, runBlocking { db.academicDao().getFileCount() })
    }

    @Test
    fun moduleAndWeekAreCreatedOnDemand() = runBlocking {
        val result = importText("New module material.", "intro.txt", module = "Physics", week = 5)
            as DirectImportResult.Imported
        val file = db.academicDao().file(result.fileId)!!
        val week = db.academicDao().week(file.weekId)!!
        assertEquals(5, week.weekNumber)
        val module = db.academicDao().module(week.moduleId)!!
        assertEquals("Physics", module.name)
    }
}
