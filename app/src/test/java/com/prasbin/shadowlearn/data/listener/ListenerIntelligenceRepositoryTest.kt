package com.prasbin.shadowlearn.data.listener

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.DocumentChunk
import com.prasbin.shadowlearn.data.db.ListenerSegment
import com.prasbin.shadowlearn.data.db.ListenerSession
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.db.Week
import com.prasbin.shadowlearn.data.intelligence.EvidenceRepository
import com.prasbin.shadowlearn.data.search.FtsIndex
import com.prasbin.shadowlearn.data.search.RoomBackedSqlExecutor
import com.prasbin.shadowlearn.data.search.SearchRepository
import kotlinx.coroutines.Dispatchers
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
 * I7 repository tests over REAL Room + FTS rows. READY transcripts with
 * corpus overlap ground to indexed chunks; everything else stays honest.
 * Listener rows alone never produce weakness signals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListenerIntelligenceRepositoryTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var intelligence: ListenerIntelligence

    private var sem1: Long = 0
    private var sem2: Long = 0
    private var bioFile: Long = 0
    private var bioWeek: Long = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ShadowLearnDatabase::class.java
        ).allowMainThreadQueries().build()
        val fts = FtsIndex(RoomBackedSqlExecutor(db.openHelper.writableDatabase))
        intelligence = ListenerIntelligence(
            SearchRepository(db.searchDao(), fts, Dispatchers.Unconfined),
            db.academicDao(),
            Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedSemester(yearName: String): Long {
        val academic = db.academicDao()
        val yearId = academic.insertYear(AcademicYear(name = yearName, sortOrder = 0))
        return academic.insertSemester(Semester(yearId = yearId, name = "S1", sortOrder = 0))
    }

    private suspend fun seedFile(
        sem: Long,
        module: String,
        weekNumber: Int,
        fileName: String,
        texts: List<String>
    ): Long {
        val academic = db.academicDao()
        val modId = academic.insertModule(Module(semesterId = sem, name = module))
        val weekId = academic.insertWeek(Week(moduleId = modId, weekNumber = weekNumber, title = "W$weekNumber"))
        val fileId = academic.insertFile(
            AcademicFile(
                weekId = weekId, fileName = fileName, filePath = "/tmp/$fileName",
                fileType = "pdf", sha256 = "h-$fileName", relativePath = fileName
            )
        )
        val ids = db.extractionDao().insertChunks(
            texts.mapIndexed { i, t ->
                DocumentChunk(academicFileId = fileId, chunkIndex = i, pageNumber = (i + 1).toLong(), text = t, charCount = t.length)
            }
        )
        FtsIndex(RoomBackedSqlExecutor(db.openHelper.writableDatabase)).insertAll(ids.zip(texts))
        return fileId
    }

    private suspend fun seedSession(sem: Long, status: String = ListenerSession.STATUS_COMPLETED): Long =
        db.listenerDao().insertSession(ListenerSession(semesterId = sem, status = status))

    private suspend fun seedSegment(
        sessionId: Long,
        position: Int,
        text: String,
        status: String = ListenerSegment.STATUS_READY
    ): Long = db.listenerDao().insertSegment(
        ListenerSegment(
            sessionId = sessionId, position = position, startedAtMs = position * 1_000L,
            transcript = text, transcriptStatus = status
        )
    )

    private suspend fun seedAll(): Long {
        sem1 = seedSemester("Year 1")
        sem2 = seedSemester("Year 2")
        bioFile = seedFile(
            sem1, "Biology", 3, "cell-biology.pdf",
            listOf("Photosynthesis converts light energy in chloroplast thylakoid membranes producing glucose and oxygen.")
        )
        bioWeek = db.academicDao().week(db.academicDao().file(bioFile)!!.weekId)!!.id
        // Identical twin in another semester: isolation probe.
        seedFile(
            sem2, "Physics", 1, "twin.pdf",
            listOf("Photosynthesis converts light energy in chloroplast thylakoid membranes producing glucose and oxygen.")
        )
        return db.listenerDao().insertSession(ListenerSession(semesterId = sem1))
    }

    @Test
    fun academicSegment_groundsToIndexedChunk() = runBlocking {
        val sessionId = seedAll()
        seedSegment(sessionId, 0, "Photosynthesis converts light energy in chloroplast thylakoid membranes.")

        val out = intelligence.understand(sessionId, sem1, db.listenerDao().segments(sessionId))

        assertEquals(1, out.totalSegments)
        assertEquals(1, out.readyCount)
        assertEquals(1, out.academicCount)
        assertEquals(bioFile, out.practiceFileId)
        val source = out.groundedSources.single()
        assertEquals(bioFile, source.fileId)
        assertEquals("cell-biology.pdf", source.fileName)
        assertEquals(bioWeek, source.weekId)
        assertTrue(source.terms.isNotEmpty())
        // Provenance: the chunk really contains a matched term.
        val chunk = db.extractionDao().chunk(source.chunkId)!!
        assertTrue(source.terms.any { chunk.text.lowercase().contains(it) })
        // Key point is verbatim transcript, never invented.
        assertEquals(1, out.keyPoints.size)
        assertTrue(out.keyPoints[0].text.startsWith("Photosynthesis converts"))
    }

    @Test
    fun fillerAndFailedStayHonest() = runBlocking {
        val sessionId = seedAll()
        seedSegment(sessionId, 0, "Okay, let's move on.")
        seedSegment(sessionId, 1, "untranscribed span", ListenerSegment.STATUS_FAILED)

        val out = intelligence.understand(sessionId, sem1, db.listenerDao().segments(sessionId))

        assertEquals(1, out.fillerCount)
        assertEquals(1, out.unknownCount)
        assertEquals(0, out.academicCount)
        assertTrue(out.keyPoints.isEmpty())
        assertTrue(out.groundedSources.isEmpty())
        assertEquals(null, out.practiceFileId)
    }

    @Test
    fun transcriptOnlyRemainsReviewable() = runBlocking {
        val sessionId = seedAll()
        seedSegment(sessionId, 0, "Remember that this concept is important for the exam.")

        val out = intelligence.understand(sessionId, sem1, db.listenerDao().segments(sessionId))

        assertEquals(1, out.transcriptOnlyCount)
        assertEquals(0, out.academicCount)
        assertEquals(1, out.keyPoints.size)
        assertTrue(out.groundedSources.isEmpty())
    }

    @Test
    fun crossSemesterTwinNeverLeaks() = runBlocking {
        val sessionId = seedAll()
        seedSegment(sessionId, 0, "Photosynthesis converts light energy in chloroplast thylakoid membranes.")

        val out = intelligence.understand(sessionId, sem1, db.listenerDao().segments(sessionId))

        assertTrue(out.groundedSources.all { it.fileId != 0L })
        val twinId = db.academicDao().let { dao ->
            dao.getModules(sem2).flatMap { dao.getWeeks(it.id) }
                .flatMap { dao.getFiles(it.id) }
                .first { it.fileName == "twin.pdf" }.id
        }
        assertTrue(out.groundedSources.none { it.fileId == twinId })
        assertEquals(bioFile, out.practiceFileId)
    }

    @Test
    fun understandIsDeterministic() = runBlocking {
        val sessionId = seedAll()
        seedSegment(sessionId, 0, "Photosynthesis converts light energy in chloroplast thylakoid membranes.")
        val segments = db.listenerDao().segments(sessionId)

        assertEquals(
            intelligence.understand(sessionId, sem1, segments),
            intelligence.understand(sessionId, sem1, segments)
        )
    }

    @Test
    fun listenerRowsAloneCreateNoWeakness() = runBlocking {
        val sessionId = seedAll()
        seedSegment(sessionId, 0, "Photosynthesis converts light energy in chloroplast thylakoid membranes.")
        seedSegment(sessionId, 1, "Osmosis moves water across membranes.")

        val evidence = EvidenceRepository(
            db.academicDao(), db.extractionDao(), db.quizDao(), db.flashcardDao(),
            Dispatchers.Unconfined
        )
        // Transcripts (even READY) are not assessment/review evidence.
        assertTrue(evidence.weaknessSignals(sem1, System.currentTimeMillis()).isEmpty())
    }

    @Test
    fun emptySessionHasNoUnderstanding() = runBlocking {
        val sessionId = seedAll()

        val out = intelligence.understand(sessionId, sem1, emptyList())

        assertEquals(0, out.totalSegments)
        assertEquals(0, out.readyCount)
        assertTrue(out.keyPoints.isEmpty())
        assertTrue(out.groundedSources.isEmpty())
        assertEquals(null, out.practiceFileId)
    }
}
