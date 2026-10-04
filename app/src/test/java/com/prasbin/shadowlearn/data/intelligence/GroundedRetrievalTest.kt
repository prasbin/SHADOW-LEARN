package com.prasbin.shadowlearn.data.intelligence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.DocumentChunk
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.db.Week
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
 * I3 grounded retrieval over REAL Room + FTS rows. Priority is fixed:
 * exact chunk → exact file → scoped text. Failure states stay distinct.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroundedRetrievalTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var repo: GroundedRetrievalRepository

    private var sem1: Long = 0
    private var sem2: Long = 0
    private var fileA: Long = 0
    private var chunkIds: List<Long> = emptyList()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ShadowLearnDatabase::class.java
        ).allowMainThreadQueries().build()
        val fts = FtsIndex(RoomBackedSqlExecutor(db.openHelper.writableDatabase))
        repo = GroundedRetrievalRepository(
            db.academicDao(), db.extractionDao(),
            SearchRepository(db.searchDao(), fts, Dispatchers.Unconfined),
            Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun seedFile(
        sem: Long,
        module: String = "Biology",
        weekNumber: Int = 2,
        fileName: String = "cell-biology.pdf",
        texts: List<String> = listOf(
            "Photosynthesis converts light into chemical energy.",
            "Osmosis moves water across membranes."
        )
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

    private suspend fun seedSemester(yearName: String): Long {
        val academic = db.academicDao()
        val yearId = academic.insertYear(AcademicYear(name = yearName, sortOrder = 0))
        return academic.insertSemester(Semester(yearId = yearId, name = "S1", sortOrder = 0))
    }

    private fun req(
        sem: Long = sem1,
        file: Long? = null,
        chunk: Long? = null,
        query: String? = null,
        max: Int = 3
    ) = RetrievalRequest(
        semesterId = sem, fileId = file, chunkId = chunk, query = query, maxChunks = max
    )

    @Test
    fun exactChunk_returnsThatChunk() = runBlocking {
        sem1 = seedSemester("Year 1")
        fileA = seedFile(sem1)
        chunkIds = db.extractionDao().chunksForFile(fileA).map { it.id }

        val out = repo.retrieve(req(chunk = chunkIds[1]))

        assertTrue(out is RetrievalResult.Retrieved)
        val chunks = (out as RetrievalResult.Retrieved).chunks
        assertEquals(1, chunks.size)
        assertEquals(chunkIds[1], chunks[0].chunkId)
        assertEquals("Osmosis moves water across membranes.", chunks[0].excerpt)
    }

    @Test
    fun exactFile_returnsOnlyItsChunksInOrder() = runBlocking {
        sem1 = seedSemester("Year 1")
        fileA = seedFile(sem1)

        val out = repo.retrieve(req(file = fileA))

        assertTrue(out is RetrievalResult.Retrieved)
        val chunks = (out as RetrievalResult.Retrieved).chunks
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.academicFileId == fileA })
        assertEquals(listOf(0, 1), chunks.map { it.chunkIndex })
    }

    @Test
    fun scopedText_returnsCurrentSemesterMatch() = runBlocking {
        sem1 = seedSemester("Year 1")
        seedFile(sem1)

        val out = repo.retrieve(req(query = "photosynthesis"))

        assertTrue(out is RetrievalResult.Retrieved)
        val chunks = (out as RetrievalResult.Retrieved).chunks
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.fileName == "cell-biology.pdf" })
    }

    @Test
    fun crossSemesterMatch_isExcluded() = runBlocking {
        sem1 = seedSemester("Year 1")
        seedFile(sem1)
        sem2 = seedSemester("Year 2")
        seedFile(sem2, fileName = "other.pdf", texts = listOf("Photosynthesis also happens here."))

        val out = repo.retrieve(req(sem = sem1, query = "photosynthesis"))

        assertTrue(out is RetrievalResult.Retrieved)
        val chunks = (out as RetrievalResult.Retrieved).chunks
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.fileName == "cell-biology.pdf" })
    }

    @Test
    fun sourceScopedText_staysInRequestedFile() = runBlocking {
        sem1 = seedSemester("Year 1")
        fileA = seedFile(sem1)
        seedFile(sem1, module = "Chemistry", fileName = "chem.pdf", texts = listOf("Photosynthesis in chemistry context."))

        val out = repo.retrieve(req(file = fileA, query = "photosynthesis"))

        assertTrue(out is RetrievalResult.Retrieved)
        val chunks = (out as RetrievalResult.Retrieved).chunks
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.academicFileId == fileA })
    }

    @Test
    fun unknownFile_isNoSource() = runBlocking {
        sem1 = seedSemester("Year 1")

        assertEquals(RetrievalResult.NoSource, repo.retrieve(req(file = 999)))
        assertEquals(RetrievalResult.NoSource, repo.retrieve(req(sem = sem1)))
    }

    @Test
    fun fileWithoutChunks_isSourceNotIndexed() = runBlocking {
        sem1 = seedSemester("Year 1")
        val academic = db.academicDao()
        val modId = academic.insertModule(Module(semesterId = sem1, name = "M"))
        val weekId = academic.insertWeek(Week(moduleId = modId, weekNumber = 1, title = "W1"))
        val bare = academic.insertFile(
            AcademicFile(
                weekId = weekId, fileName = "empty.pdf", filePath = "/tmp/empty.pdf",
                fileType = "pdf", sha256 = "empty", relativePath = "empty.pdf"
            )
        )

        assertEquals(RetrievalResult.SourceNotIndexed, repo.retrieve(req(file = bare)))
    }

    @Test
    fun unmatchedQuery_isNoMatch() = runBlocking {
        sem1 = seedSemester("Year 1")
        seedFile(sem1)

        assertEquals(RetrievalResult.NoMatch, repo.retrieve(req(query = "xyzzz-no-such-term")))
    }

    @Test
    fun sameRequestTwice_isIdentical() = runBlocking {
        sem1 = seedSemester("Year 1")
        fileA = seedFile(sem1)
        val request = req(file = fileA)

        assertEquals(repo.retrieve(request), repo.retrieve(request))
    }

    @Test
    fun moreChunksThanCap_areCappedDeterministically() = runBlocking {
        sem1 = seedSemester("Year 1")
        fileA = seedFile(
            sem1,
            texts = listOf("alpha one", "alpha two", "alpha three", "alpha four", "alpha five")
        )

        val out = repo.retrieve(req(file = fileA))

        assertTrue(out is RetrievalResult.Retrieved)
        val chunks = (out as RetrievalResult.Retrieved).chunks
        assertEquals(3, chunks.size)
        assertEquals(listOf(0, 1, 2), chunks.map { it.chunkIndex })
    }

    @Test
    fun provenance_isPreserved() = runBlocking {
        sem1 = seedSemester("Year 1")
        fileA = seedFile(sem1)

        val out = repo.retrieve(req(file = fileA))

        assertTrue(out is RetrievalResult.Retrieved)
        val first = (out as RetrievalResult.Retrieved).chunks.first()
        assertEquals(fileA, first.academicFileId)
        assertEquals("cell-biology.pdf", first.fileName)
        assertEquals("Week 2", first.weekLabel)
        assertEquals("Biology", first.moduleName)
        assertEquals(1L, first.pageNumber)
    }
}
