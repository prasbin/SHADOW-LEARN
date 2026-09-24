package com.prasbin.shadowlearn.data.search

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.DocumentChunk
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.db.Week
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end search over the REAL production path: Room DB + FTS4 fallback
 * (Robolectric's framework SQLite, exactly what Android ships) + FTS5
 * enabled variant via sqlite-jdbc in FtsIndexTest. Seeds two semesters and
 * verifies scoping, query handling, metadata/page/slide resolution,
 * deterministic ordering and ranking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchRepositoryTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var fts: FtsIndex
    private lateinit var repo: SearchRepository

    private var s1: Long = 0 // Artificial Intelligence (current semester)
    private var s2: Long = 0 // Physics 101 (unrelated semester)
    private var s3: Long = 0 // empty semester

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ShadowLearnDatabase::class.java)
            .allowMainThreadQueries().build()
        fts = FtsIndex(RoomBackedSqlExecutor(db.openHelper.writableDatabase))
        repo = SearchRepository(db.searchDao(), fts, kotlinx.coroutines.Dispatchers.Unconfined)
        runBlocking { seed() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed() {
        val dao = db.academicDao()
        val yearId = dao.insertYear(AcademicYear(name = "Year 2", sortOrder = 0))
        s1 = dao.insertSemester(Semester(yearId = yearId, name = "Semester 1", sortOrder = 0))
        s2 = dao.insertSemester(Semester(yearId = yearId, name = "Semester 2", sortOrder = 1))
        s3 = dao.insertSemester(Semester(yearId = yearId, name = "Semester 3", sortOrder = 2))

        val ai = dao.insertModule(Module(semesterId = s1, name = "Artificial Intelligence"))
        val week1 = dao.insertWeek(Week(moduleId = ai, weekNumber = 1, title = "Foundations"))
        val physics = dao.insertModule(Module(semesterId = s2, name = "Physics 101"))
        val pWeek = dao.insertWeek(Week(moduleId = physics, weekNumber = 2))

        suspend fun file(week: Long, name: String, type: String): Long = dao.insertFile(
            AcademicFile(
                weekId = week, fileName = name, filePath = "/tmp/$name",
                fileType = type, sha256 = name + "-sha", classType = "LECTURE",
                relativePath = "x.zip/$name", indexed = true
            )
        )

        val neural = file(week1, "neural.pdf", "pdf")
        val notes = file(week1, "notes.docx", "docx")
        val aiSlides = file(week1, "ai.pptx", "pptx")
        val handout = file(week1, "handout.pdf", "pdf")
        val gradientNotes = file(week1, "gradient-notes.pdf", "pdf")
        val quantum = file(pWeek, "quantum.pdf", "pdf")

        val chunks = listOf(
            DocumentChunk(academicFileId = neural, chunkIndex = 0, pageNumber = 1, text = "neural networks learn by gradient descent and backpropagation", charCount = 61),
            DocumentChunk(academicFileId = neural, chunkIndex = 1, pageNumber = 2, text = "optimization algorithms for training", charCount = 38),
            DocumentChunk(academicFileId = notes, chunkIndex = 0, pageNumber = 2, text = "delta rule lecture notes network", charCount = 34),
            DocumentChunk(academicFileId = aiSlides, chunkIndex = 0, pageNumber = 1, text = "gradient descent learning rates", charCount = 31),
            DocumentChunk(academicFileId = handout, chunkIndex = 0, pageNumber = null, text = "reading digestion practice", charCount = 26),
            DocumentChunk(academicFileId = handout, chunkIndex = 1, pageNumber = null, text = "reading comprehension quiz", charCount = 26),
            DocumentChunk(academicFileId = gradientNotes, chunkIndex = 0, pageNumber = 3, text = "gradient descent methods", charCount = 26),
            DocumentChunk(academicFileId = quantum, chunkIndex = 0, pageNumber = 1, text = "learning physics quantum computing", charCount = 36)
        )
        val ids = db.extractionDao().insertChunks(chunks)
        fts.insertAll(ids.zip(chunks.map { it.text }))
    }

    private suspend fun search(q: String) = repo.search(q, s1)

    private suspend fun fileNames(q: String): List<String> =
        (search(q) as SearchOutcome.Results).results.map { it.fileName }

    @Test
    fun emptyQueryReturnsNoResults() {
        val out = runBlocking { search("") }
        assertTrue((out as SearchOutcome.Results).results.isEmpty())
    }

    @Test
    fun whitespaceQueryReturnsNoResults() {
        val out = runBlocking { search("    ") }
        assertTrue((out as SearchOutcome.Results).results.isEmpty())
    }

    @Test
    fun oneTermSearchReturnsMatchingFiles() {
        val names = runBlocking { fileNames("gradient") }
        assertTrue(names.contains("neural.pdf"))
        assertTrue(names.contains("ai.pptx"))
        assertTrue(names.contains("gradient-notes.pdf"))
    }

    @Test
    fun multiTermSearchIsAnd() {
        val names = runBlocking { fileNames("neural gradient") }
        // Only neural.pdf's page-1 chunk has BOTH words.
        assertEquals(listOf("neural.pdf"), names)
    }

    @Test
    fun noResultQueryReturnsEmpty() {
        val out = runBlocking { search("elephant") }
        assertTrue((out as SearchOutcome.Results).results.isEmpty())
    }

    @Test
    fun specialCharactersAreSanitized() {
        val names = runBlocking { fileNames("gradient!!!***") }
        assertTrue(names.isNotEmpty())
    }

    @Test
    fun malformedMatchInputDoesNotCrash() {
        val out = runBlocking { search("\"OR NOT (:) * [ ] { } ^ + -") }
        // Either a valid sanitized query or a clean error — never an exception.
        assertTrue(out is SearchOutcome.Results || out is SearchOutcome.Failed)
    }

    @Test
    fun currentSemesterScopeExcludesOtherSemester() {
        val results = runBlocking {
            (search("learning") as SearchOutcome.Results).results
        }
        // "learning" appears in S1 (neural.pdf) and S2 (quantum.pdf) — only S1 visible.
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.fileName != "quantum.pdf" })
    }

    @Test
    fun fileMetadataIsResolved() {
        val result = runBlocking {
            (search("backpropagation") as SearchOutcome.Results).results.first()
        }
        assertEquals("neural.pdf", result.fileName)
        assertEquals("pdf", result.fileType)
        assertEquals("Artificial Intelligence", result.moduleName)
        assertEquals(1, result.weekNumber)
        assertEquals("Foundations", result.weekTitle)
        assertEquals("LECTURE", result.classType)
    }

    @Test
    fun pageNumberIsResolved() {
        val result = runBlocking {
            (search("backpropagation") as SearchOutcome.Results).results.first()
        }
        assertEquals(1L, result.pageNumber)
    }

    @Test
    fun slideNumberIsResolved() {
        val result = runBlocking {
            val r = (search("gradient") as SearchOutcome.Results).results
            r.first { it.fileType == "pptx" }
        }
        // PPTX chunk carries its slide reference in pageNumber.
        assertEquals(1L, result.pageNumber)
        assertEquals("ai.pptx", result.fileName)
    }

    @Test
    fun rankingIsDeterministicAcrossRuns() {
        val order1 = runBlocking { fileNames("gradient") }
        val order2 = runBlocking { fileNames("gradient") }
        assertEquals(order1, order2)
    }

    @Test
    fun orderingIsDeterministicWithFileNameWeighting() {
        val r = runBlocking {
            (search("gradient") as SearchOutcome.Results).results.map { it.fileName }
        }
        // gradient-notes.pdf outscores (text AND file-name match); remaining
        // ties order alphabetically by file name (length-normalized scores).
        assertEquals(listOf("gradient-notes.pdf", "ai.pptx", "neural.pdf"), r)
    }

    @Test
    fun equalScoreTiesBreakByChunkId() {
        val r = runBlocking {
            (search("reading") as SearchOutcome.Results).results
        }
        assertEquals(2, r.size)
        assertEquals("handout.pdf", r[0].fileName)
        assertEquals("handout.pdf", r[1].fileName)
        assertTrue(r[0].chunkId < r[1].chunkId)
    }

    @Test
    fun duplicateMatchingChunksAreReturned() {
        val r = runBlocking {
            (search("reading") as SearchOutcome.Results).results
        }
        // Same file, two matching chunks — both must appear (the UI can show
        // both locations).
        assertEquals(2, r.size)
        assertTrue(r.all { it.academicFileId == r.first().academicFileId })
    }

    @Test
    fun fileNameWeightingOutranksTextOnlyMatch() {
        val order = runBlocking { fileNames("gradient") }
        assertEquals("gradient-notes.pdf", order.first())
    }

    @Test
    fun noIndexedContentSemesterReportsZero() {
        assertEquals(0, runBlocking { repo.indexedChunkCount(s3) })
    }

    @Test
    fun indexedChunkCountIsSemesterScoped() {
        assertEquals(7, runBlocking { repo.indexedChunkCount(s1) })
        assertEquals(1, runBlocking { repo.indexedChunkCount(s2) })
    }

    @Test
    fun observeIndexedCountEmitsLiveValue() {
        val count = runBlocking { repo.observeIndexedChunkCount(s1).first() }
        assertEquals(7, count)
    }

    @Test
    fun allResultsHaveCompleteFields() {
        val results = runBlocking {
            (search("gradient") as SearchOutcome.Results).results
        }
        assertTrue(results.isNotEmpty())
        results.forEach {
            assertNotNull(it.fileName)
            assertNotNull(it.moduleName)
            assertTrue(it.fileType.isNotEmpty())
            assertTrue(it.excerpt.isNotEmpty())
            assertTrue(it.score > 0.0)
        }
    }
}