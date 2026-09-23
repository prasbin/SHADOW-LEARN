package com.prasbin.shadowlearn.data.ingest

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.ExtractionDao
import com.prasbin.shadowlearn.data.db.ExtractionMeta
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.db.Week
import com.prasbin.shadowlearn.data.extract.Fixtures
import com.prasbin.shadowlearn.data.search.FtsIndex
import com.prasbin.shadowlearn.data.search.JdbcSqlExecutor
import java.io.File
import java.security.MessageDigest
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
import org.robolectric.annotation.LooperMode

/**
 * End-to-end Phase 4 extraction repository tests: real Room + real FTS5
 * (sqlite-jdbc) exercising the FULL production incremental contract
 * (fresh / unchanged / changed / duplicate / failed-isolation) plus the
 * emulator walk-through equivalent at unit level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ExtractionRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ShadowLearnDatabase
    private lateinit var dao: AcademicDao
    private lateinit var extractionDao: ExtractionDao
    private lateinit var fts: FtsIndex
    private lateinit var jdbc: JdbcSqlExecutor
    private lateinit var repo: ExtractionRepository

    private var weekId = 0L
    private var semesterId = 0L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, ShadowLearnDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.academicDao()
        extractionDao = db.extractionDao()
        jdbc = JdbcSqlExecutor.inMemory()
        fts = FtsIndex(jdbc)
        repo = ExtractionRepository(context, db, extractionDao, dao, fts)
        runBlocking {
            semesterId = seedHierarchy()
            weekId = dao.getWeeks(dao.getModules(semesterId)[0].id)[0].id
        }
    }

    @After
    fun tearDown() {
        jdbc.close()
        db.close()
    }

    private suspend fun seedHierarchy(): Long {
        val yearId = dao.insertYear(AcademicYear(name = "Year 2", sortOrder = 2))
        val semId = dao.insertSemester(Semester(yearId = yearId, name = "Semester 1", sortOrder = 1))
        val modId = dao.insertModule(Module(semesterId = semId, name = "Artificial Intelligence"))
        dao.insertWeek(Week(moduleId = modId, weekNumber = 1, title = "Week 1"))
        return semId
    }

    /** Writes content to a real app-private file and registers the row. */
    private suspend fun addFile(name: String, ext: String, content: ByteArray, relativePath: String): Long {
        val dir = File(context.filesDir, "phase4-fixtures").apply { mkdirs() }
        val f = File(dir, name).apply { writeBytes(content) }
        return dao.insertFile(
            AcademicFile(
                weekId = weekId,
                fileName = name,
                filePath = f.absolutePath,
                fileType = ext,
                sha256 = sha256(content),
                fileSize = content.size.toLong(),
                relativePath = relativePath,
                lastModified = System.currentTimeMillis()
            )
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun fileRow(id: Long): AcademicFile = dao.getFiles(weekId).first { it.id == id }

    private fun meta(id: Long): ExtractionMeta? = runBlocking { extractionDao.getMeta(id) }

    private fun chunkIds(id: Long): List<Long> = runBlocking { extractionDao.chunksForFile(id).map { it.id } }

    private fun search(text: String): List<Long> = fts.search(text).map { it.chunkId }

    @Test
    fun freshImport_extractsAllFormatsAndIndexesFts() = runBlocking {
        val txt = addFile("notes.txt", "txt", Fixtures.txtBytes("hello extraction world\nsecond line"), "Week 1/Lecture/notes.txt")
        val pdf = addFile("slides.pdf", "pdf", Fixtures.pdfPages(), "Week 1/Lecture/slides.pdf")
        val docx = addFile("lab.docx", "docx", Fixtures.docxPages(), "Week 1/Tutorial/lab.docx")
        val pptx = addFile("intro.pptx", "pptx", Fixtures.pptxSlides(), "Week 1/Lecture/intro.pptx")
        val scanned = addFile("scan.pdf", "pdf", Fixtures.pdfNoText(), "Week 1/Workshop/scan.pdf")
        val blank = addFile("blank.txt", "txt", Fixtures.whitespaceTxtBytes(), "Week 1/blank.txt")

        val summary = repo.processForSemester(semesterId)

        assertEquals(6, summary.totalFiles)
        assertEquals(6, summary.extracted)
        assertEquals(0, summary.failed)
        assertEquals(0, summary.skipped)
        assertTrue(summary.indexedChunks >= 5)

        assertTrue(meta(txt)!!.status == "EXTRACTED" && meta(txt)!!.sha256 == fileRow(txt).sha256)
        assertTrue(fileRow(txt).indexed)
        assertEquals(1, chunkIds(txt).size)

        // PDF: 2 pages, page references preserved, text mirrored in FTS.
        val pdfChunks = runBlocking { extractionDao.chunksForFile(pdf) }
        assertEquals(2, pdfChunks.size)
        assertEquals(listOf(1L, 2L), pdfChunks.map { it.pageNumber })
        assertTrue(search("gradient").isNotEmpty())
        assertTrue(search("gradient").all { it in chunkIds(pdf) })
        assertTrue(search("optimization").isNotEmpty())

        // DOCX: page-break based pages; PPTX: slide references.
        assertEquals(2, pdfChunks.size)
        val docxChunks = runBlocking { extractionDao.chunksForFile(docx) }
        assertEquals(2, docxChunks.size)
        assertTrue(search("Eta").isNotEmpty())
        val pptxChunks = runBlocking { extractionDao.chunksForFile(pptx) }
        assertEquals(2, pptxChunks.size)
        assertEquals(listOf(1L, 2L), pptxChunks.map { it.pageNumber })
        assertTrue(search("backpropagation").isNotEmpty())

        // Empty documents are honestly EXTRACTED with zero chunks.
        assertTrue(meta(scanned)!!.status == "EXTRACTED" && meta(scanned)!!.chunkCount == 0)
        assertTrue(meta(blank)!!.status == "EXTRACTED" && meta(blank)!!.chunkCount == 0)
    }

    @Test
    fun unchangedReimport_isSkippedWithoutReextraction() = runBlocking {
        val txt = addFile("stable.txt", "txt", Fixtures.txtBytes("deterministic purple fox"), "notes.txt")
        repo.processForSemester(semesterId)

        val meta1 = meta(txt)!!
        val ids1 = chunkIds(txt)
        assertTrue(ids1.isNotEmpty() && search("purple").isNotEmpty())

        val summary = repo.processForSemester(semesterId)

        assertEquals(1, summary.skipped)
        assertEquals(0, summary.extracted)
        assertEquals(0, summary.indexedChunks)
        assertEquals(meta1.completedAt, meta(txt)!!.completedAt)
        assertEquals(ids1, chunkIds(txt))
        assertEquals(ids1, search("purple"))
    }

    @Test
    fun changedContent_reextractsAndDropsStaleIndex() = runBlocking {
        val file = addFile("evolving.txt", "txt", Fixtures.txtBytes("old superseded word"), "evolving.txt")
        repo.processForSemester(semesterId)
        val oldIds = chunkIds(file).toSet()
        assertTrue(oldIds.isNotEmpty() && search("superseded").isNotEmpty())

        val dir = File(context.filesDir, "phase4-fixtures")
        val stored = File(dir, "evolving.txt")
        stored.writeBytes(Fixtures.txtBytes("new lighthouse beacon word"))
        val newHash = sha256(stored.readBytes())
        val row = fileRow(file)
        dao.updateFile(row.copy(sha256 = newHash, fileSize = stored.length(), updatedAt = System.currentTimeMillis()))

        val summary = repo.processForSemester(semesterId)

        assertEquals(1, summary.extracted)
        assertEquals(0, summary.skipped)
        // Stale chunks + FTS gone; new ones present.
        val newIds = chunkIds(file)
        assertTrue(newIds.isNotEmpty())
        assertTrue(newIds.none { it in oldIds })
        assertEquals(0, search("superseded").size)
        assertTrue(search("lighthouse").isNotEmpty())
        assertEquals(newHash, meta(file)!!.sha256)
    }

    @Test
    fun duplicateImport_reusesSiblingRepresentation() = runBlocking {
        val content = Fixtures.txtBytes("shared core material reused")
        val original = addFile("original.txt", "txt", content, "a/original.txt")
        repo.processForSemester(semesterId)
        val originalChunks = runBlocking { extractionDao.chunksForFile(original) }
        assertTrue(originalChunks.isNotEmpty())

        val dup = addFile("copy.txt", "txt", content, "b/copy.txt")
        val summary = repo.processForSemester(semesterId)

        assertEquals(1, summary.reused)
        assertEquals(0, summary.extracted)
        assertTrue(summary.indexedChunks == originalChunks.size)
        val dupChunks = runBlocking { extractionDao.chunksForFile(dup) }
        assertEquals(originalChunks.map { it.text }, dupChunks.map { it.text })
        assertTrue(meta(dup)!!.status == "EXTRACTED")
        assertTrue(fileRow(dup).indexed)
        // Both files' chunks are searchable.
        val hits = search("reused")
        assertTrue(hits.containsAll(originalChunks.map { it.id }))
        assertTrue(hits.any { it in chunkIds(dup) })
    }

    @Test
    fun oneBrokenFile_failsInIsolationOthersSucceed() = runBlocking {
        val txt = addFile("good.txt", "txt", Fixtures.txtBytes("healthy corpus content"), "good.txt")
        val broken = addFile("broken.pdf", "pdf", "definitely not a pdf".toByteArray(), "broken.pdf")

        val summary = repo.processForSemester(semesterId)

        assertEquals(1, summary.failed)
        assertEquals(1, summary.extracted)
        assertTrue(summary.errors.any { it.contains("no objects") })
        assertTrue(meta(broken)!!.status == "FAILED")
        assertTrue(meta(broken)!!.error!!.contains("no objects"))
        assertTrue(!fileRow(broken).indexed)
        // Healthy file unaffected and indexed.
        assertTrue(meta(txt)!!.status == "EXTRACTED")
        assertTrue(search("healthy").isNotEmpty())
    }

    @Test
    fun legacyUnsupportedFormat_flaggedFailedHonestly() = runBlocking {
        val legacy = addFile("legacy.doc", "doc", byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()), "legacy.doc")

        val summary = repo.processForSemester(semesterId)

        assertEquals(1, summary.failed)
        assertTrue(summary.errors.any { it.contains("not supported") })
        assertTrue(meta(legacy)!!.status == "FAILED")
        assertEquals(0, meta(legacy)!!.chunkCount)
        assertEquals(0, chunkIds(legacy).size)
    }

    @Test
    fun emptySemester_doneWithZeros() = runBlocking {
        val summary = repo.processForSemester(semesterId)
        assertEquals(0, summary.totalFiles)
        assertEquals(0, summary.extracted)
        assertEquals(0, summary.failed)
    }

    @Test
    fun failedThenContentChange_retriesSuccessfully() = runBlocking {
        val name = "retry.pdf"
        val file = addFile(name, "pdf", "garbage bytes".toByteArray(), name)
        repo.processForSemester(semesterId)
        assertTrue(meta(file)!!.status == "FAILED")

        // Same sha (unchanged) + FAILED meta => retried on next pass honestly.
        val retry = repo.processForSemester(semesterId)
        assertEquals(1, retry.failed)

        // Fix the content, simulate CHANGED import, then extraction succeeds.
        val stored = File(File(context.filesDir, "phase4-fixtures"), name)
        stored.writeBytes(Fixtures.pdfPages())
        val newHash = sha256(stored.readBytes())
        val row = fileRow(file)
        dao.updateFile(row.copy(sha256 = newHash, fileSize = stored.length(), updatedAt = System.currentTimeMillis()))
        val pass = repo.processForSemester(semesterId)
        assertEquals(1, pass.extracted)
        assertTrue(meta(file)!!.status == "EXTRACTED")
        assertTrue(search("gradient").all { it in chunkIds(file) })
    }
}