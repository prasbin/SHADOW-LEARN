package com.prasbin.shadowlearn.data.intelligence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.DocumentChunk
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.Semester
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.db.Week
import com.prasbin.shadowlearn.data.search.FtsIndex
import com.prasbin.shadowlearn.data.search.RoomBackedSqlExecutor
import com.prasbin.shadowlearn.data.search.SearchOutcome
import com.prasbin.shadowlearn.data.search.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
 * Large-notebook target (1,000+ sources per semester): proves the read paths
 * stay bounded — paged week listing, capped search results, indexed count,
 * and direct-file retrieval — instead of materializing the whole corpus.
 *
 * Measured on the JVM/Robolectric harness; device timings are reported
 * separately and are NOT claimed here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LargeCorpusTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var dao: AcademicDao
    private lateinit var search: SearchRepository
    private lateinit var retrieval: GroundedRetrievalRepository
    private lateinit var fts: FtsIndex
    private var semesterId = 0L
    private var weekId = 0L
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, ShadowLearnDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.academicDao()
        fts = FtsIndex(RoomBackedSqlExecutor(db.openHelper.writableDatabase))
        search = SearchRepository(db.searchDao(), fts, Dispatchers.Unconfined)
        retrieval = GroundedRetrievalRepository(
            academicDao = dao,
            extractionDao = db.extractionDao(),
            search = search,
            dispatcher = Dispatchers.Unconfined
        )
        val year = dao.insertYear(AcademicYear(name = "Year 1", sortOrder = 1))
        semesterId = dao.insertSemester(Semester(yearId = year, name = "Semester 1", sortOrder = 1))
        val module = dao.insertModule(Module(semesterId = semesterId, name = "Large Module"))
        weekId = dao.insertWeek(Week(moduleId = module, weekNumber = 1, title = "Week 1"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun thousandSources_stayBoundedAndSearchable() = runBlocking {
        fts.ensureSchema()
        val started = System.currentTimeMillis()
        val fileIds = ArrayList<Long>(SOURCES)
        for (i in 0 until SOURCES) {
            val text = "Source $i covers cellular respiration and energy transfer topic$i."
            val fileId = dao.insertFile(
                AcademicFile(
                    weekId = weekId,
                    fileName = "source-%04d.txt".format(i),
                    filePath = "/tmp/source-$i.txt",
                    fileType = "txt",
                    sha256 = "sha-%04d".format(i),
                    relativePath = "source-%04d.txt".format(i),
                    fileSize = text.length.toLong(),
                    indexed = true
                )
            )
            fileIds += fileId
            val chunkId = db.extractionDao().insertChunks(
                listOf(
                    DocumentChunk(
                        academicFileId = fileId,
                        chunkIndex = 0,
                        pageNumber = 1L,
                        text = text,
                        charCount = text.length
                    )
                )
            ).first()
            fts.insertAll(listOf(chunkId to text))
        }
        val seedMs = System.currentTimeMillis() - started

        assertEquals(SOURCES, search.indexedChunkCount(semesterId))

        // Paged source listing never returns the whole week at once.
        val page = dao.observeFilesLimited(weekId, PAGE).first()
        assertEquals(PAGE, page.size)

        // Search is capped regardless of corpus size.
        val searchStarted = System.currentTimeMillis()
        val outcome = search.search("respiration", semesterId)
        val searchMs = System.currentTimeMillis() - searchStarted
        assertTrue("expected Results, got $outcome", outcome is SearchOutcome.Results)
        val hits = (outcome as SearchOutcome.Results).results
        assertTrue("search must be capped", hits.size <= SearchRepository.RESULT_LIMIT)
        assertTrue(hits.isNotEmpty())

        // Direct-file retrieval resolves one file without scanning the corpus.
        val retrieveStarted = System.currentTimeMillis()
        val result = retrieval.retrieve(
            RetrievalRequest(semesterId = semesterId, fileId = fileIds[SOURCES / 2], maxChunks = 3)
        )
        val retrieveMs = System.currentTimeMillis() - retrieveStarted
        assertTrue("expected Retrieved, got $result", result is RetrievalResult.Retrieved)
        assertEquals(1, (result as RetrievalResult.Retrieved).chunks.size)

        println(
            "LARGE_CORPUS sources=$SOURCES seedMs=$seedMs searchMs=$searchMs " +
                "retrieveMs=$retrieveMs pageSize=${page.size} hits=${hits.size}"
        )
    }

    private companion object {
        const val SOURCES = 1000
        const val PAGE = 50
    }
}
