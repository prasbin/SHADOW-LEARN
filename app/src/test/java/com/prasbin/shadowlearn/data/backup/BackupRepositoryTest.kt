package com.prasbin.shadowlearn.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.Flashcard
import com.prasbin.shadowlearn.data.db.FlashcardDeck
import com.prasbin.shadowlearn.data.db.ListenerSegment
import com.prasbin.shadowlearn.data.db.ListenerSession
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.QuizQuestion
import com.prasbin.shadowlearn.data.db.QuizSession
import com.prasbin.shadowlearn.data.db.ReviewEvent
import com.prasbin.shadowlearn.data.db.ReviewSession
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.db.Week
import com.prasbin.shadowlearn.data.ingest.IngestRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 13 backup tests: manifest, export structure, full round-trip
 * restore into a fresh database (ID remapping), idempotent re-import,
 * reconcile outcomes, history preservation, honest per-record failures,
 * audio exclusion, and validation failures with zero writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: ShadowLearnDatabase
    private lateinit var repo: BackupRepository

    private var yearId: Long = 0
    private var semesterId: Long = 0
    private var weekId: Long = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ShadowLearnDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = BackupRepository(
            context = context,
            db = db,
            academicDao = db.academicDao(),
            extractionDao = db.extractionDao(),
            quizDao = db.quizDao(),
            flashcardDao = db.flashcardDao(),
            listenerDao = db.listenerDao(),
            ingest = IngestRepository(context, db.academicDao()),
            afterAcademicImport = null,
            dispatcher = Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- seeding ----------------------------------------------------------

    private suspend fun seedHierarchy(): Long {
        yearId = db.academicDao().insertYear(AcademicYear(name = "Year 2", sortOrder = 1))
        semesterId = db.academicDao()
            .insertSemester(Semester(yearId = yearId, name = "Semester 1", sortOrder = 1))
        val modId = db.academicDao().insertModule(Module(semesterId = semesterId, name = "AI"))
        weekId = db.academicDao().insertWeek(Week(moduleId = modId, weekNumber = 1, title = "Week 1"))
        return semesterId
    }

    private fun sha(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256")
        return d.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** Academic file row whose bytes really exist (export reads them back). */
    private suspend fun seedFile(
        relativePath: String = "AI.zip/Week 1/Lecture/l1.pdf",
        bytes: ByteArray = "lecture one".toByteArray()
    ): AcademicFile {
        val f = File.createTempFile("seed", ".pdf", context.cacheDir)
        f.writeBytes(bytes)
        val row = AcademicFile(
            weekId = weekId,
            fileName = relativePath.substringAfterLast('/'),
            filePath = f.absolutePath,
            fileType = "pdf",
            sha256 = sha(bytes),
            relativePath = relativePath,
            fileSize = bytes.size.toLong()
        )
        val id = db.academicDao().insertFile(row)
        return row.copy(id = id)
    }

    private suspend fun seedHistory() {
        val qs = db.quizDao().insertSession(
            QuizSession(
                semesterId = semesterId, seed = 42, totalQuestions = 1,
                correctCount = 1, xpEarned = 10, streak = 1,
                status = QuizSession.STATUS_COMPLETED,
                startedAt = 1_700_000_000_000L, completedAt = 1_700_000_001_000L
            )
        )
        db.quizDao().insertQuestions(
            listOf(
                QuizQuestion(
                    sessionId = qs, position = 0, chunkId = 7, academicFileId = 9,
                    questionType = "TRUE_FALSE", prompt = "Sky is blue.",
                    optionsJson = null, correctAnswer = "true",
                    userAnswer = "true", isCorrect = true,
                    srcFileName = "l1.pdf", srcFileType = "pdf", srcPage = 1,
                    srcExcerpt = "Sky is blue."
                )
            )
        )
        val deck = db.flashcardDao().insertDeck(FlashcardDeck(semesterId = semesterId, title = "Deck"))
        db.flashcardDao().insertCards(
            listOf(
                Flashcard(
                    deckId = deck, front = "q", back = "a",
                    sourceLabel = "l1.pdf", contentKey = "quiz:5"
                )
            )
        )
        val rs = db.flashcardDao().insertReviewSession(
            ReviewSession(deckId = deck, status = ReviewSession.STATUS_COMPLETED)
        )
        db.flashcardDao().insertEvent(
            ReviewEvent(
                sessionId = rs, flashcardId = 3, rating = "GOOD",
                reviewedAt = 1_700_000_002_000L,
                previousEaseFactor = 2.5, newEaseFactor = 2.5,
                previousIntervalDays = 0, newIntervalDays = 1, retained = true
            )
        )
        val ls = db.listenerDao().insertSession(
            ListenerSession(
                semesterId = semesterId, status = ListenerSession.STATUS_COMPLETED,
                audioPath = "/fake/private.m4a"
            )
        )
        db.listenerDao().insertSegment(
            ListenerSegment(
                sessionId = ls, position = 0, startedAtMs = 0, durationMs = 1000,
                transcript = "The professor explained gradient descent in full.",
                transcriptStatus = ListenerSegment.STATUS_READY
            )
        )
    }

    private fun freshDb(): ShadowLearnDatabase =
        Room.inMemoryDatabaseBuilder(context, ShadowLearnDatabase::class.java)
            .allowMainThreadQueries().build()

    private fun freshRepo(other: ShadowLearnDatabase): BackupRepository =
        BackupRepository(
            context = context,
            db = other,
            academicDao = other.academicDao(),
            extractionDao = other.extractionDao(),
            quizDao = other.quizDao(),
            flashcardDao = other.flashcardDao(),
            listenerDao = other.listenerDao(),
            ingest = IngestRepository(context, other.academicDao()),
            afterAcademicImport = null,
            dispatcher = Dispatchers.Unconfined
        )

    private fun zipEntries(bytes: ByteArray): Set<String> {
        val out = mutableSetOf<String>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                out.add(e.name)
            }
        }
        return out
    }

    private fun readEntry(bytes: ByteArray, name: String): ByteArray {
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.name == name) return zis.readBytes()
            }
        }
        throw AssertionError("missing entry $name")
    }

    // ---- export -------------------------------------------------------------

    @Test
    fun exportWritesManifestAcademicAndHistory() = runBlocking {
        seedHierarchy()
        seedFile()
        seedHistory()
        val out = ByteArrayOutputStream()
        val summary = repo.exportSemester(semesterId, "b.zip", out)
        assertEquals(1, summary.filesExported)
        assertTrue(summary.historyRecords > 0)
        assertFalse(summary.audioIncluded)
        assertEquals(1, summary.excludedAudioCount)
        assertTrue(summary.failures.isEmpty())
        val names = zipEntries(out.toByteArray())
        assertTrue(names.contains(BackupFormat.MANIFEST_PATH))
        assertTrue(names.any { it.startsWith(BackupFormat.ACADEMIC_PREFIX) })
        assertTrue(names.contains(BackupFormat.HISTORY_PREFIX + BackupFormat.QUIZ_SESSIONS_FILE))
        val manifest = BackupFormat.parseManifest(
            String(readEntry(out.toByteArray(), BackupFormat.MANIFEST_PATH), Charsets.UTF_8)
        )
        assertEquals("Semester 1", manifest.semesterName)
        assertEquals(1, manifest.files.size)
    }

    @Test
    fun exportIsDeterministic() = runBlocking {
        seedHierarchy()
        seedFile()
        seedHistory()
        val a = ByteArrayOutputStream()
        repo.exportSemester(semesterId, "a.zip", a)
        val b = ByteArrayOutputStream()
        repo.exportSemester(semesterId, "b.zip", b)
        // Same entry set + same academic bytes (manifest carries wall-clock time).
        assertEquals(zipEntries(a.toByteArray()), zipEntries(b.toByteArray()))
        val academic = zipEntries(a.toByteArray()).first { it.startsWith(BackupFormat.ACADEMIC_PREFIX) }
        assertTrue(readEntry(a.toByteArray(), academic).contentEquals(readEntry(b.toByteArray(), academic)))
    }

    // ---- round-trip restore ----------------------------------------------------

    @Test
    fun roundTripRestoresEverything() = runBlocking {
        seedHierarchy()
        val bytes = "lecture one".toByteArray()
        seedFile(bytes = bytes)
        seedHistory()
        val exported = ByteArrayOutputStream()
        repo.exportSemester(semesterId, "b.zip", exported)

        val db2 = freshDb()
        try {
            // Shift every id so the test proves remapping, not coincidence.
            val dummyYear = db2.academicDao().insertYear(AcademicYear(name = "Dummy", sortOrder = 0))
            db2.academicDao().insertSemester(Semester(yearId = dummyYear, name = "DummySem", sortOrder = 0))
            val repo2 = freshRepo(db2)
            val summary = repo2.importArchive(ByteArrayInputStream(exported.toByteArray()), "b.zip")
            assertTrue("errors=" + summary.errors, summary.ok)
            assertEquals("Year 2", summary.yearName)
            assertEquals("Semester 1", summary.semesterName)

            val targetSem = db2.academicDao().getSemesters(
                db2.academicDao().getYears().first { it.name == "Year 2" }.id
            ).first { it.name == "Semester 1" }
            // Academic hierarchy by stable names.
            val mod = db2.academicDao().findModule(targetSem.id, "AI")
            assertNotNull(mod)
            val week = db2.academicDao().findWeek(mod!!.id, 1)
            assertNotNull(week)
            val file = db2.academicDao().findFileByPosition(targetSem.id, "AI.zip/Week 1/Lecture/l1.pdf")
            assertNotNull(file)
            assertEquals(sha(bytes), file!!.sha256)
            // History remapped onto the new ids.
            val sessions = db2.quizDao().sessionsOfSemester(targetSem.id)
            assertEquals(1, sessions.size)
            assertEquals(10, sessions[0].xpEarned)
            assertEquals(1, db2.quizDao().questions(sessions[0].id).size)
            val decks = db2.flashcardDao().decksOfSemester(targetSem.id)
            assertEquals(1, decks.size)
            assertEquals(1, db2.flashcardDao().cardsOfDeck(decks[0].id).size)
            val reviews = db2.flashcardDao().reviewSessionsOfDeck(decks[0].id)
            assertEquals(1, reviews.size)
            assertEquals(1, db2.flashcardDao().reviewEvents(reviews[0].id).size)
            val listeners = db2.listenerDao().sessionsOfSemester(targetSem.id)
            assertEquals(1, listeners.size)
            assertNull("audio must not be restored", listeners[0].audioPath)
            val segs = db2.listenerDao().segments(listeners[0].id)
            assertEquals(1, segs.size)
            assertEquals(ListenerSegment.STATUS_READY, segs[0].transcriptStatus)
            assertTrue(segs[0].transcript.contains("gradient descent"))
            // Counts honestly reported.
            assertTrue((summary.history["quizSessions"] ?: 0) >= 1)
            assertTrue((summary.history["cardsCreated"] ?: 0) >= 1)
            assertFalse(summary.audioIncluded)
            assertEquals(1, summary.excludedAudioCount)
        } finally {
            db2.close()
        }
    }

    @Test
    fun idempotentReimportCreatesNothingNew() = runBlocking {
        seedHierarchy()
        seedFile()
        seedHistory()
        val exported = ByteArrayOutputStream()
        repo.exportSemester(semesterId, "b.zip", exported)
        val db2 = freshDb()
        try {
            val repo2 = freshRepo(db2)
            val first = repo2.importArchive(ByteArrayInputStream(exported.toByteArray()), "b.zip")
            assertTrue(first.ok)
            suspend             fun counts(): List<Int> = listOf(
                db2.academicDao().getFileCount(),
                db2.quizDao().sessionsOfSemester(first.targetSemesterId).size,
                db2.flashcardDao().decksOfSemester(first.targetSemesterId).size,
                db2.listenerDao().sessionsOfSemester(first.targetSemesterId).size
            )
            suspend fun childCounts(): List<Int> {
                val qs = db2.quizDao().sessionsOfSemester(first.targetSemesterId)
                val decks = db2.flashcardDao().decksOfSemester(first.targetSemesterId)
                val listeners = db2.listenerDao().sessionsOfSemester(first.targetSemesterId)
                return listOf(
                    qs.sumOf { db2.quizDao().questions(it.id).size },
                    decks.sumOf { db2.flashcardDao().cardsOfDeck(it.id).size },
                    decks.flatMap { db2.flashcardDao().reviewSessionsOfDeck(it.id) }
                        .sumOf { db2.flashcardDao().reviewEvents(it.id).size },
                    listeners.sumOf { db2.listenerDao().segments(it.id).size }
                )
            }
            val before = counts()
            val beforeChildren = childCounts()
            val second = repo2.importArchive(ByteArrayInputStream(exported.toByteArray()), "b.zip")
            assertTrue(second.ok)
            assertEquals(before, counts())
            assertEquals(beforeChildren, childCounts())
            assertEquals(0, second.created)
            assertTrue((second.history["quizSessionsDuplicate"] ?: 0) >= 1)
            assertTrue((second.history["quizQuestionsDuplicate"] ?: 0) >= 1)
            assertTrue((second.history["cardsDuplicate"] ?: 0) >= 1)
            assertTrue((second.history["reviewSessionsDuplicate"] ?: 0) >= 1)
            assertTrue((second.history["reviewEventsDuplicate"] ?: 0) >= 1)
            assertTrue((second.history["listenerSessionsDuplicate"] ?: 0) >= 1)
            assertTrue((second.history["listenerSegmentsDuplicate"] ?: 0) >= 1)
            val decks = db2.flashcardDao().decksOfSemester(second.targetSemesterId)
            val events = decks.flatMap {
                db2.flashcardDao().reviewSessionsOfDeck(it.id)
            }.flatMap { db2.flashcardDao().reviewEvents(it.id) }
            assertEquals(1, events.size)
        } finally {
            db2.close()
        }
    }

    @Test
    fun changedFileReconciliation() = runBlocking {
        val db2 = freshDb()
        try {
            val repo2 = freshRepo(db2)
            val v1 = "version one content here".toByteArray()
            val v2 = "version two content here!!".toByteArray()
            val a1 = handBuiltArchive(
                relativePath = "ModA.zip/Week 1/Lecture/doc.pdf", bytes = v1,
                year = "Y", semester = "S"
            )
            val r1 = repo2.importArchive(ByteArrayInputStream(a1), "a.zip")
            assertTrue(r1.ok)
            assertEquals(1, r1.created)
            val a2 = handBuiltArchive(
                relativePath = "ModA.zip/Week 1/Lecture/doc.pdf", bytes = v2,
                year = "Y", semester = "S"
            )
            val r2 = repo2.importArchive(ByteArrayInputStream(a2), "a.zip")
            assertTrue(r2.ok)
            assertEquals(1, r2.changed)
            assertEquals(0, r2.created)
            val sem = db2.academicDao().getSemesters(
                db2.academicDao().getYears().first { it.name == "Y" }.id
            ).first { it.name == "S" }
            val row = db2.academicDao()
                .findFileByPosition(sem.id, "ModA.zip/Week 1/Lecture/doc.pdf")!!
            assertEquals(sha(v2), row.sha256)
        } finally {
            db2.close()
        }
    }

    @Test
    fun duplicateContentHandling() = runBlocking {
        val db2 = freshDb()
        try {
            val repo2 = freshRepo(db2)
            val same = "identical bytes for both positions".toByteArray()
            val out = ByteArrayOutputStream()
            val manifest = BackupFormat.buildManifest(
                appVersion = "t", schemaVersion = 7, exportedAt = 1,
                yearName = "Y", semesterName = "S", excludedAudioCount = 0,
                counts = BackupFormat.ManifestCounts(files = 2),
                files = listOf(
                    BackupFormat.ManifestFile(
                        BackupFormat.ACADEMIC_PREFIX + "M.zip/Week 1/Lecture/a.pdf",
                        sha(same), same.size.toLong()
                    ),
                    BackupFormat.ManifestFile(
                        BackupFormat.ACADEMIC_PREFIX + "M.zip/Week 1/Lecture/b.pdf",
                        sha(same), same.size.toLong()
                    )
                )
            )
            ZipOutputStream(out).use { zos ->
                zos.putNextEntry(ZipEntry(BackupFormat.MANIFEST_PATH))
                zos.write(manifest.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                for (name in listOf("a.pdf", "b.pdf")) {
                    zos.putNextEntry(ZipEntry(BackupFormat.ACADEMIC_PREFIX + "M.zip/Week 1/Lecture/$name"))
                    zos.write(same)
                    zos.closeEntry()
                }
                for (h in listOf(
                    BackupFormat.QUIZ_SESSIONS_FILE, BackupFormat.QUIZ_QUESTIONS_FILE,
                    BackupFormat.FLASHCARD_DECKS_FILE, BackupFormat.FLASHCARDS_FILE,
                    BackupFormat.REVIEW_SESSIONS_FILE, BackupFormat.REVIEW_EVENTS_FILE,
                    BackupFormat.LISTENER_SESSIONS_FILE, BackupFormat.LISTENER_SEGMENTS_FILE
                )) {
                    zos.putNextEntry(ZipEntry(BackupFormat.HISTORY_PREFIX + h))
                    zos.write("[]".toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
            val r = repo2.importArchive(ByteArrayInputStream(out.toByteArray()), "dup.zip")
            assertTrue("errors=" + r.errors, r.ok)
            assertEquals(1, r.created)
            assertEquals(1, r.duplicate)
            // One physical content row shared by both references.
            assertEquals(1, db2.academicDao().getContentCount())
        } finally {
            db2.close()
        }
    }

    // ---- honest records ----------------------------------------------------------

    @Test
    fun missingHistorySourceFailsRecordHonestly() = runBlocking {
        seedHierarchy()
        seedFile()
        val out = ByteArrayOutputStream()
        repo.exportSemester(semesterId, "b.zip", out)
        // Corrupt the questions file to reference a nonexistent parent session.
        val tampered = replaceHistoryFile(
            out.toByteArray(),
            BackupFormat.QUIZ_QUESTIONS_FILE,
            JSONArray().put(
                JSONObject()
                    .put("sessionSeed", 999999L)
                    .put("sessionStartedAt", 123456789L)
                    .put("position", 0)
                    .put("chunkId", 1L)
                    .put("academicFileId", 1L)
                    .put("questionType", "TRUE_FALSE")
                    .put("prompt", "Orphan?")
                    .put("correctAnswer", "true")
                    .put("srcFileName", "x.pdf")
                    .put("srcFileType", "pdf")
                    .put("srcExcerpt", "x")
            ).toString()
        )
        val db2 = freshDb()
        try {
            val repo2 = freshRepo(db2)
            val r = repo2.importArchive(ByteArrayInputStream(tampered), "t.zip")
            assertTrue(r.ok)
            assertTrue(r.failed >= 1)
            assertTrue(r.errors.any { it.contains("parent quiz session") })
            // The semester + academic file still restored.
            val sems = db2.academicDao().getSemesters(
                db2.academicDao().getYears().first { it.name == "Year 2" }.id
            )
            assertEquals(1, sems.size)
        } finally {
            db2.close()
        }
    }

    @Test
    fun pendingAndFailedSegmentsStayNonReady() = runBlocking {
        seedHierarchy()
        seedFile()
        val ls = db.listenerDao().insertSession(
            ListenerSession(semesterId = semesterId, status = ListenerSession.STATUS_COMPLETED)
        )
        db.listenerDao().insertSegment(
            ListenerSegment(sessionId = ls, position = 0, startedAtMs = 0)
        )
        db.listenerDao().insertSegment(
            ListenerSegment(
                sessionId = ls, position = 1, startedAtMs = 0,
                transcript = "bad", transcriptStatus = ListenerSegment.STATUS_FAILED
            )
        )
        val exported = ByteArrayOutputStream()
        repo.exportSemester(semesterId, "b.zip", exported)
        val db2 = freshDb()
        try {
            val repo2 = freshRepo(db2)
            val r = repo2.importArchive(ByteArrayInputStream(exported.toByteArray()), "b.zip")
            assertTrue(r.ok)
            val sem = db2.academicDao().getSemesters(
                db2.academicDao().getYears().first { it.name == "Year 2" }.id
            ).first { it.name == "Semester 1" }
            val sessions = db2.listenerDao().sessionsOfSemester(sem.id)
            assertEquals(1, sessions.size)
            val segs = db2.listenerDao().segments(sessions[0].id).sortedBy { it.position }
            assertEquals(ListenerSegment.STATUS_PENDING, segs[0].transcriptStatus)
            assertEquals(ListenerSegment.STATUS_FAILED, segs[1].transcriptStatus)
        } finally {
            db2.close()
        }
    }

    // ---- validation failures (zero writes) ------------------------------------------

    @Test
    fun corruptZipFailsWithZeroWrites() = runBlocking {
        val beforeFiles = db.academicDao().getFileCount()
        val r = repo.importArchive(
            ByteArrayInputStream("this is definitely not a zip file".toByteArray()), "bad.zip"
        )
        assertFalse(r.ok)
        assertTrue(r.failureReason!!.contains("Import failed"))
        assertTrue(r.errors.any { it.contains("Processed before failure: 0") })
        assertEquals(beforeFiles, db.academicDao().getFileCount())
    }

    @Test
    fun missingManifestFails() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("random/file.txt"))
            zos.write("hi".toByteArray())
            zos.closeEntry()
        }
        val r = repo.importArchive(ByteArrayInputStream(out.toByteArray()), "x.zip")
        assertFalse(r.ok)
        assertTrue(r.failureReason!!.contains("manifest", ignoreCase = true))
    }

    @Test
    fun unsupportedVersionFails() = runBlocking {
        seedHierarchy()
        seedFile()
        val out = ByteArrayOutputStream()
        repo.exportSemester(semesterId, "b.zip", out)
        val manifestJson = BackupFormat.buildManifest(
            appVersion = "t", schemaVersion = 7, exportedAt = 1,
            yearName = "Y", semesterName = "S", excludedAudioCount = 0,
            counts = BackupFormat.ManifestCounts(), files = emptyList()
        ).replace("\"archiveVersion\": 1", "\"archiveVersion\": 99")
        val rebuilt = rebuildWithManifest(out.toByteArray(), manifestJson)
        val r = repo.importArchive(ByteArrayInputStream(rebuilt), "v.zip")
        assertFalse(r.ok)
        assertTrue(r.failureReason!!.contains("99"))
    }

    @Test
    fun invalidHashFailsWithZeroWrites() = runBlocking {
        seedHierarchy()
        seedFile(bytes = "original bytes here".toByteArray())
        val out = ByteArrayOutputStream()
        repo.exportSemester(semesterId, "b.zip", out)
        // Tamper the staged academic bytes (append a byte) without touching the manifest.
        val tampered = tamperFirstAcademicEntry(out.toByteArray())
        val before = db.academicDao().getFileCount()
        val r = repo.importArchive(ByteArrayInputStream(tampered), "t.zip")
        assertFalse(r.ok)
        assertTrue(r.failureReason!!.contains("Hash mismatch"))
        assertEquals(before, db.academicDao().getFileCount())
    }

    // ---- helpers to build/tamper archives ----------------------------------------------

    private fun handBuiltArchive(
        relativePath: String,
        bytes: ByteArray,
        year: String,
        semester: String
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val manifest = BackupFormat.buildManifest(
            appVersion = "t", schemaVersion = 7, exportedAt = 1,
            yearName = year, semesterName = semester, excludedAudioCount = 0,
            counts = BackupFormat.ManifestCounts(files = 1),
            files = listOf(
                BackupFormat.ManifestFile(
                    BackupFormat.ACADEMIC_PREFIX + relativePath, sha(bytes), bytes.size.toLong()
                )
            )
        )
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry(BackupFormat.MANIFEST_PATH))
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(BackupFormat.ACADEMIC_PREFIX + relativePath))
            zos.write(bytes)
            zos.closeEntry()
            for (h in listOf(
                BackupFormat.QUIZ_SESSIONS_FILE, BackupFormat.QUIZ_QUESTIONS_FILE,
                BackupFormat.FLASHCARD_DECKS_FILE, BackupFormat.FLASHCARDS_FILE,
                BackupFormat.REVIEW_SESSIONS_FILE, BackupFormat.REVIEW_EVENTS_FILE,
                BackupFormat.LISTENER_SESSIONS_FILE, BackupFormat.LISTENER_SEGMENTS_FILE
            )) {
                zos.putNextEntry(ZipEntry(BackupFormat.HISTORY_PREFIX + h))
                zos.write("[]".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Replaces one entry's bytes inside an existing archive. */
    private fun replaceHistoryFile(archive: ByteArray, name: String, content: String): ByteArray {
        val target = if (name == "manifest.json") BackupFormat.MANIFEST_PATH
        else BackupFormat.HISTORY_PREFIX + name
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            java.util.zip.ZipInputStream(archive.inputStream()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    zos.putNextEntry(ZipEntry(e.name))
                    if (e.name == target) zos.write(content.toByteArray(Charsets.UTF_8))
                    else zis.readBytes().let { zos.write(it) }
                    zos.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    private fun rebuildWithManifest(archive: ByteArray, manifestJson: String): ByteArray =
        replaceHistoryFile(archive, "manifest.json", manifestJson)

    /** Appends one byte to the first academic entry (breaks its manifest hash). */
    private fun tamperFirstAcademicEntry(archive: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var tampered = false
        ZipOutputStream(out).use { zos ->
            java.util.zip.ZipInputStream(archive.inputStream()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val data = zis.readBytes()
                    zos.putNextEntry(ZipEntry(e.name))
                    if (!tampered && e.name.startsWith(BackupFormat.ACADEMIC_PREFIX)) {
                        zos.write(data)
                        zos.write(0)
                        tampered = true
                    } else {
                        zos.write(data)
                    }
                    zos.closeEntry()
                }
            }
        }
        assertTrue(tampered)
        return out.toByteArray()
    }
}
