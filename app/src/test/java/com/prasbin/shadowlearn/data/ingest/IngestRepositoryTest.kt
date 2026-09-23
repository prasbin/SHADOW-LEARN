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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3 ingest + reconciliation tests: real ZIP bytes → [ZipWalk] → plan →
 * Room rows + content-addressed physical copies. Asserts the deterministic
 * NEW / UNCHANGED / CHANGED / DUPLICATE model, physical-copy reuse, safe
 * orphan removal, and failure isolation.
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

    private suspend fun doneSummary(): IngestSummary {
        val state = repo.state.value
        assertTrue("expected Done, got $state", state is IngestState.Done)
        return (state as IngestState.Done).summary
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val contentRoot = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "source")

    private fun programmingZip(lectureBytes: ByteArray) = zipBytes(
        "Week 1/Lecture/l1.pdf" to lectureBytes,
        "Week 1/Tutorial/t1.py" to "print(1)".toByteArray()
    )

    // ---------- 1. first import creates records ----------------------------

    @Test
    fun firstImport_createsRecordsWithMetadataAndSingleCopy() = runBlocking {
        val l1 = "lecture-one-bytes".toByteArray()
        val top = zipBytes("Programming.zip" to zipBytes(
            "Week 1/Lecture/l1.pdf" to l1,
            "Week 1/Tutorial/t1.py" to "print(1)".toByteArray(),
            "Week 2/notes.txt" to "hi".toByteArray()
        ))
        importBytes(top, "semester1.zip")
        val summary = doneSummary()
        assertEquals(1, summary.modules)
        assertEquals(2, summary.weeks)
        assertEquals(3, summary.created)
        assertEquals(0, summary.unchanged)
        assertEquals(0, summary.changed)
        assertEquals(0, summary.duplicate)
        assertEquals(0, summary.failed)
        assertEquals(0, summary.skipped)

        val dao = db.academicDao()
        assertEquals(3, dao.getFileCount())
        assertEquals(3, dao.getContentCount())
        assertEquals(3, dao.getSources().size)
        assertEquals(3, contentRoot.listFiles()?.size ?: 0)

        val mod = dao.findModule(semesterId, "Programming")!!
        val we = dao.getWeeks(mod.id)
        val lecture = dao.getFiles(we.first { it.weekNumber == 1 }.id).first { it.fileName == "l1.pdf" }
        assertEquals("LECTURE", lecture.classType)
        assertEquals("pdf", lecture.fileType)
        assertEquals(l1.size.toLong(), lecture.fileSize)
        assertEquals(sha256(l1), lecture.sha256)
        assertEquals("Programming.zip/Week 1/Lecture/l1.pdf", lecture.relativePath)
        val sha = sha256(l1)
        assertEquals(contentRoot.resolve(sha).absolutePath, lecture.filePath)
        assertEquals(sha, sha256(File(lecture.filePath).readBytes()))
        assertTrue("row must be linked to content", lecture.sourceFileId != null)
    }

    // ---------- 2. identical re-import is incremental -----------------------

    @Test
    fun reimportIdenticalZip_allUnchanged_noNewRowsOrCopies() = runBlocking {
        val top = zipBytes("Programming.zip" to programmingZip("lecture-one-bytes".toByteArray()))
        importBytes(top, "semester1.zip")
        assertEquals(2, doneSummary().created)

        importBytes(top, "semester1.zip")
        val second = doneSummary()
        assertEquals(2, second.unchanged)
        assertEquals(0, second.created)
        assertEquals(0, second.changed)
        assertEquals(0, second.duplicate)

        val dao = db.academicDao()
        assertEquals(2, dao.getFileCount())
        assertEquals(2, dao.getContentCount())
        assertEquals(2, contentRoot.listFiles()?.size ?: 0)
    }

    // ---------- 3. same content, different filename -------------------------

    @Test
    fun sameContentDifferentFileName_isDuplicate_withSharedCopy() = runBlocking {
        val bytes = "shared-body".toByteArray()
        importBytes(zipBytes("Programming.zip" to zipBytes("Week 1/Lecture/l1.pdf" to bytes)), "s1.zip")
        assertEquals(1, doneSummary().created)

        importBytes(
            zipBytes("Programming.zip" to zipBytes("Week 1/Lecture/l1-RENAMED.pdf" to bytes)),
            "s1.zip"
        )
        val second = doneSummary()
        assertEquals(1, second.duplicate)
        assertEquals(0, second.created)
        assertEquals(0, second.unchanged)
        assertEquals(0, second.changed)

        val dao = db.academicDao()
        assertEquals(2, dao.getFileCount())
        assertEquals(1, dao.getContentCount())
        assertEquals(1, contentRoot.listFiles()?.size ?: 0)
        val content = dao.findContentByHash(sha256(bytes))!!
        assertEquals(2, content.refCount)
        assertTrue(File(content.storedPath).readBytes().contentEquals(bytes))
    }

    // ---------- 4. same content, different academic path --------------------

    @Test
    fun sameContentDifferentAcademicPath_isDuplicate_sharedCopy() = runBlocking {
        val bytes = "week-shared".toByteArray()
        importBytes(zipBytes("Programming.zip" to zipBytes("Week 1/Lecture/x.pdf" to bytes)), "s1.zip")
        assertEquals(1, doneSummary().created)

        importBytes(zipBytes("Programming.zip" to zipBytes("Week 2/Lecture/x.pdf" to bytes)), "s1.zip")
        val second = doneSummary()
        assertEquals(1, second.duplicate)

        val dao = db.academicDao()
        assertEquals(2, dao.getFileCount())
        assertEquals(1, dao.getContentCount())
        assertEquals(1, contentRoot.listFiles()?.size ?: 0)
        assertEquals(2, dao.findContentByHash(sha256(bytes))!!.refCount)
    }

    // ---------- 5. different content, same filename -------------------------

    @Test
    fun sameFilenameChangedContent_isChanged_oldCopyRemoved() = runBlocking {
        val a = "version-a".toByteArray()
        val b = "version-b-aaaaaaaaaaaaaaaa".toByteArray()
        importBytes(zipBytes("Programming.zip" to zipBytes("Week 1/Lecture/t1.pdf" to a)), "s1.zip")
        assertEquals(1, doneSummary().created)

        importBytes(zipBytes("Programming.zip" to zipBytes("Week 1/Lecture/t1.pdf" to b)), "s1.zip")
        val second = doneSummary()
        assertEquals(1, second.changed)
        assertEquals(0, second.created)
        assertEquals(0, second.unchanged)
        assertEquals(0, second.duplicate)

        val dao = db.academicDao()
        assertEquals(1, dao.getFileCount())
        val mod = dao.findModule(semesterId, "Programming")!!
        val row = dao.getFiles(dao.getWeeks(mod.id).single().id).single()
        assertEquals(sha256(b), row.sha256)
        assertEquals(b.size.toLong(), row.fileSize)
        // Old content copy removed (refcount dropped to 0), new one present.
        assertFalse(contentRoot.resolve(sha256(a)).exists())
        assertTrue(contentRoot.resolve(sha256(b)).exists())
        assertEquals(1, dao.getContentCount())
    }

    // ---------- 6. different content, different hash ------------------------

    @Test
    fun differentContentDifferentHash_staysDistinct() = runBlocking {
        val x = "content-x".toByteArray()
        val y = "content-y".toByteArray()
        importBytes(zipBytes("M.zip" to zipBytes(
            "Week 1/Lecture/x.pdf" to x,
            "Week 2/Lecture/y.pdf" to y
        )), "s.zip")
        val s = doneSummary()
        assertEquals(2, s.created)
        val dao = db.academicDao()
        assertEquals(2, dao.getContentCount())
        assertEquals(2, contentRoot.listFiles()?.size ?: 0)
        assertTrue(sha256(x) != sha256(y))
    }

    // ---------- 7. SHA-256 identity round-trip ------------------------------

    @Test
    fun shaIdentity_roundTripsThroughContentTable() = runBlocking {
        val bytes = "identity-probe".toByteArray()
        importBytes(zipBytes("M.zip" to zipBytes("Week 1/Lecture/i.txt" to bytes)), "s.zip")
        assertEquals(1, doneSummary().created)
        val content = db.academicDao().findContentByHash(sha256(bytes))!!
        assertEquals(contentRoot.resolve(sha256(bytes)).absolutePath, content.storedPath)
        assertTrue(File(content.storedPath).readBytes().contentEquals(bytes))
    }

    // ---------- 8/9. failure does not corrupt prior data --------------------

    @Test
    fun reimportPreservesValidRecords_afterCorrupt() = runBlocking {
        importBytes(zipBytes("M.zip" to zipBytes("Week 1/Lecture/ok.pdf" to "ok".toByteArray())), "s.zip")
        assertEquals(1, doneSummary().created)

        importBytes("not a zip".toByteArray(), "broken.zip")
        val state = repo.state.value
        assertTrue(state is IngestState.Failed)
        assertTrue((state as IngestState.Failed).reason.contains("Invalid ZIP"))
        val dao = db.academicDao()
        assertEquals(1, dao.getFileCount())
        assertEquals(1, dao.getContentCount())
        assertEquals(1, contentRoot.listFiles()?.size ?: 0)
    }

    @Test
    fun corruptArchive_failsWithoutCrash_orWrites() = runBlocking {
        importBytes("not a zip".toByteArray(), "bad.zip")
        assertTrue(repo.state.value is IngestState.Failed)
        assertEquals(0, db.academicDao().getFileCount())
        assertEquals(0, db.academicDao().getContentCount())
        assertEquals(0, contentRoot.listFiles()?.size ?: 0)
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
        importBytes(zipBytes("Good.zip" to good, "Broken.zip" to "garbage".toByteArray()), "s.zip")
        val summary = doneSummary()
        assertEquals(1, summary.created)
        assertEquals(0, summary.failed)
        assertTrue(summary.errors.any { it.contains("Broken.zip") })
        assertEquals(1, db.academicDao().getFileCount())
    }

    @Test
    fun unsupportedTypes_skippedAndReported() = runBlocking {
        importBytes(zipBytes("M.zip" to zipBytes(
            "Week 1/Lecture/a.pdf" to "a".toByteArray(),
            "Week 1/Lecture/b.mp4" to "b".toByteArray()
        )), "s.zip")
        val summary = doneSummary()
        assertEquals(1, summary.created)
        assertEquals(1, summary.skipped)
        assertTrue(summary.errors.any { it.contains("b.mp4") })
        assertEquals(1, db.academicDao().getFileCount())
    }

    // ---------- 10. reference-aware physical deletion -----------------------

    @Test
    fun physicalCopySurvivesWhileAnyReferenceRemains() = runBlocking {
        val bytes = "shared-body".toByteArray()
        importBytes(zipBytes("Programming.zip" to zipBytes("Week 1/Lecture/l1.pdf" to bytes)), "s1.zip")
        importBytes(zipBytes("Programming.zip" to zipBytes("Week 2/Lecture/l1.pdf" to bytes)), "s1.zip")
        val dao = db.academicDao()
        assertEquals(2, dao.getFileCount())
        val content = dao.findContentByHash(sha256(bytes))!!
        assertEquals(2, content.refCount)

        // Remove exactly one reference: the shared physical copy must survive.
        val mod = dao.findModule(semesterId, "Programming")!!
        val allWeeks = dao.getWeeks(mod.id)
        dao.deleteFile(dao.getFiles(allWeeks.first().id).first().id)
        repo.removeOrphanedContent()
        assertEquals(1, dao.getFileCount())
        assertEquals(1, dao.findContentByHash(sha256(bytes))!!.refCount)
        assertTrue(File(content.storedPath).exists())

        // Remove the last reference: the now-unreferenced copy is cleaned up.
        val weeks = dao.getWeeks(mod.id)
        weeks.forEach { w -> dao.getFiles(w.id).forEach { dao.deleteFile(it.id) } }
        repo.removeOrphanedContent()
        assertEquals(0, dao.getFileCount())
        assertNull(dao.findContentByHash(sha256(bytes)))
        assertFalse(File(content.storedPath).exists())
    }

    // ---------- 11. partial import never deletes unrelated material ---------

    @Test
    fun partialZip_keepsUnrelatedMaterial() = runBlocking {
        val docx = "notes-docx-11-bytes".toByteArray()
        val aiBytes = zipBytes("Week 2/Workshop/notes.docx" to docx)
        val progBytes = programmingZip("lecture-one-bytes".toByteArray())
        // Semester archive: one entry per module, the entry bytes ARE the module zip.
        importBytes(zipBytes("AI.zip" to aiBytes, "Programming.zip" to progBytes), "semester1.zip")
        assertEquals(3, doneSummary().created)

        // A later import contains ONLY Programming (same content).
        importBytes(zipBytes("Programming.zip" to programmingZip("lecture-one-bytes".toByteArray())), "p.zip")
        val partial = doneSummary()
        assertEquals(2, partial.unchanged)
        assertEquals(0, partial.created)

        // AI is untouched and still present.
        val dao = db.academicDao()
        assertEquals(listOf("AI", "Programming"), dao.getModules(semesterId).map { it.name }.sorted())
        assertEquals(3, dao.getFileCount())
        assertTrue(dao.findFileByHashInSemester(semesterId, sha256(docx)) != null)
    }
}