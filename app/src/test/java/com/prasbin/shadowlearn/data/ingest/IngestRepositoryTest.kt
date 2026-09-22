package com.prasbin.shadowlearn.data.ingest

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end ingest tests: real ZIP bytes → [ZipWalk] → plan → Room rows +
 * app-private file copies. Asserts hierarchy, metadata, and error isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IngestRepositoryTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var repo: IngestRepository
    private var semesterId = 0L
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, ShadowLearnDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = IngestRepository(context, db.academicDao())
        val y = db.academicDao().insertYear(AcademicYear(name = "Year 2", sortOrder = 2))
        semesterId = db.academicDao().insertSemester(Semester(yearId = y, name = "Semester 1", sortOrder = 1))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun zipBytes(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((path, bytes) in entries) {
                zos.putNextEntry(ZipEntry(if (bytes == null && !path.endsWith('/')) "$path/" else path))
                if (bytes != null) zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Imports raw bytes through the stream entry point (no ContentResolver). */
    private suspend fun importBytes(bytes: ByteArray, label: String) {
        repo.importStream(ByteArrayInputStream(bytes), semesterId, label)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun fullImport_buildsHierarchyWithMetadata() = runBlocking {
        val l1 = "lecture-one-bytes".toByteArray()
        val prog = zipBytes(
            "Week 1/Lecture/l1.pdf" to l1,
            "Week 1/Tutorial/t1.py" to "print(1)".toByteArray(),
            "Week 2/notes.txt" to "hi".toByteArray()
        )
        val top = zipBytes("Programming.zip" to prog)
        importBytes(top, "semester1.zip")
        val state = repo.state.value
        assertTrue(state is IngestState.Done)
        val summary = (state as IngestState.Done).summary
        assertEquals(1, summary.modules)
        assertEquals(2, summary.weeks)
        assertEquals(3, summary.files)
        assertEquals(0, summary.skipped)

        val dao = db.academicDao()
        assertEquals(1, dao.getModules(semesterId).size)
        val mod = dao.findModule(semesterId, "Programming")!!
        val weeks = dao.getWeeks(mod.id)
        assertEquals(listOf(1, 2), weeks.map { it.weekNumber })
        val w1files = dao.getFiles(weeks.first { it.weekNumber == 1 }.id)
        assertEquals(2, w1files.size)
        val lecture = w1files.first { it.fileName == "l1.pdf" }
        assertEquals("LECTURE", lecture.classType)
        assertEquals("pdf", lecture.fileType)
        assertEquals(l1.size.toLong(), lecture.fileSize)
        assertEquals(sha256(l1), lecture.sha256)
        assertEquals("Programming.zip/Week 1/Lecture/l1.pdf", lecture.relativePath)
        assertTrue(File(lecture.filePath).readBytes().contentEquals(l1))
        val week2file = dao.getFiles(weeks.first { it.weekNumber == 2 }.id).single()
        assertEquals("OTHER", week2file.classType)
    }

    @Test
    fun unsupportedTypes_skippedAndReported() = runBlocking {
        val mod = zipBytes(
            "Week 1/Lecture/a.pdf" to "a".toByteArray(),
            "Week 1/Lecture/b.mp4" to "b".toByteArray()
        )
        importBytes(zipBytes("M.zip" to mod), "s.zip")
        val summary = (repo.state.value as IngestState.Done).summary
        assertEquals(1, summary.files)
        assertEquals(1, summary.skipped)
        assertTrue(summary.errors.any { it.contains("b.mp4") })
        assertEquals(1, db.academicDao().getFileCount())
    }

    @Test
    fun corruptArchive_failsWithoutCrash() = runBlocking {
        importBytes("not a zip".toByteArray(), "bad.zip")
        val state = repo.state.value
        assertTrue(state is IngestState.Failed)
        assertEquals(0, db.academicDao().getFileCount())
    }

    @Test
    fun emptyArchive_fails() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { }
        importBytes(out.toByteArray(), "empty.zip")
        assertTrue(repo.state.value is IngestState.Failed)
    }

    @Test
    fun oneBadNestedZip_doesNotSinkValidModules() = runBlocking {
        val good = zipBytes("Week 1/Lecture/ok.pdf" to "ok".toByteArray())
        val top = zipBytes(
            "Good.zip" to good,
            "Broken.zip" to "garbage".toByteArray()
        )
        importBytes(top, "s.zip")
        val summary = (repo.state.value as IngestState.Done).summary
        assertEquals(1, summary.files)
        assertTrue(summary.errors.any { it.contains("Broken.zip") })
    }
}
