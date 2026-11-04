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
 * I8 assistant first-slice integration tests: intent → grounded retrieval →
 * extractive explanation over a REAL Room + FTS database, plus the
 * empty-corpus honest state. Source grounding and insufficient-evidence
 * handling are asserted through the existing I3/I4 components.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssistantIntegrationTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var retrieval: GroundedRetrievalRepository
    private lateinit var search: SearchRepository

    private var sem1: Long = 0
    private var fileA: Long = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ShadowLearnDatabase::class.java
        ).allowMainThreadQueries().build()
        val fts = FtsIndex(RoomBackedSqlExecutor(db.openHelper.writableDatabase))
        search = SearchRepository(db.searchDao(), fts, Dispatchers.Unconfined)
        retrieval = GroundedRetrievalRepository(
            db.academicDao(),
            db.extractionDao(),
            search,
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

    private suspend fun seedFile(sem: Long, texts: List<String>): Long {
        val academic = db.academicDao()
        val moduleId = academic.insertModule(Module(semesterId = sem, name = "Biology"))
        val weekId = academic.insertWeek(Week(moduleId = moduleId, weekNumber = 2, title = "W2"))
        val fileId = academic.insertFile(
            AcademicFile(
                weekId = weekId,
                fileName = "cell-biology.pdf",
                filePath = "/tmp/cell-biology.pdf",
                fileType = "pdf",
                sha256 = "sha-cell-biology",
                relativePath = "cell-biology.pdf"
            )
        )
        val ids = db.extractionDao().insertChunks(
            texts.mapIndexed { i, t ->
                DocumentChunk(
                    academicFileId = fileId,
                    chunkIndex = i,
                    pageNumber = (i + 1).toLong(),
                    text = t,
                    charCount = t.length
                )
            }
        )
        FtsIndex(RoomBackedSqlExecutor(db.openHelper.writableDatabase)).insertAll(ids.zip(texts))
        return fileId
    }

    @Test
    fun explainFlow_endToEnd_isGroundedAndHonest() = runBlocking {
        sem1 = seedSemester("Year 1")
        fileA = seedFile(sem1, listOf("Photosynthesis converts light into chemical energy."))

        val intent = AssistantEngine.classify("Explain photosynthesis")
        assertEquals(AssistantIntentType.EXPLAIN, intent.type)
        assertEquals("photosynthesis", intent.topic)

        val retrievalResult = retrieval.retrieve(
            RetrievalRequest(semesterId = sem1, query = intent.topic, maxChunks = 3)
        )
        val explanation = ExplanationEngine.explain("Explain: photosynthesis", retrievalResult)

        assertEquals(ExplanationStatus.EXPLAINED, explanation.status)
        assertEquals("1 passage · cell-biology.pdf", explanation.groundingBasis)
        assertTrue(explanation.sources.isNotEmpty())
        assertEquals(fileA, explanation.sources.first().academicFileId)
        assertEquals("Photosynthesis converts light into chemical energy.", explanation.sources.first().excerpt)
    }

    @Test
    fun explainUnknownTerm_isHonestNoMatch() = runBlocking {
        sem1 = seedSemester("Year 1")
        seedFile(sem1, listOf("Photosynthesis converts light into chemical energy."))

        val intent = AssistantEngine.classify("Explain zzzqqqunknowntopic")
        val retrievalResult = retrieval.retrieve(
            RetrievalRequest(semesterId = sem1, query = intent.topic, maxChunks = 3)
        )
        val explanation = ExplanationEngine.explain("Explain: zzzqqqunknowntopic", retrievalResult)

        assertEquals(ExplanationStatus.NO_MATCH, explanation.status)
        assertEquals("", explanation.explanation)
        assertTrue(explanation.sources.isEmpty())
    }

    @Test
    fun emptySemester_hasNoIndexedMaterial() = runBlocking {
        sem1 = seedSemester("Year 1")
        // No file seeded → no indexed chunks → honest empty-corpus state applies.
        assertEquals(0, search.indexedChunkCount(sem1))
    }
}
