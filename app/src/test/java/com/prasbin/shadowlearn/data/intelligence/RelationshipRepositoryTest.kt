package com.prasbin.shadowlearn.data.intelligence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.AcademicYear
import com.prasbin.shadowlearn.data.db.DocumentChunk
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.QuizQuestion
import com.prasbin.shadowlearn.data.db.QuizSession
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
 * I6 repository tests over REAL Room + FTS rows. Semester S1 (Biology,
 * Weeks 1–3 + Chemistry) carries overlapping distinctive vocab; S2 holds
 * an identical-text twin for isolation. No test invents relationships —
 * every asserted link traces to seeded rows + real FTS hits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelationshipRepositoryTest {

    private lateinit var db: ShadowLearnDatabase
    private lateinit var repo: RelationshipRepository

    private var s1: Long = 0
    private var s2: Long = 0
    private var fileA: Long = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ShadowLearnDatabase::class.java
        ).allowMainThreadQueries().build()
        val fts = FtsIndex(RoomBackedSqlExecutor(db.openHelper.writableDatabase))
        repo = RelationshipRepository(
            db.academicDao(), db.extractionDao(),
            SearchRepository(db.searchDao(), fts, Dispatchers.Unconfined),
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
        texts: List<String>,
        sha: String = "h-$fileName"
    ): Long {
        val academic = db.academicDao()
        val existing = academic.getModules(sem).firstOrNull { it.name == module }
        val modId = existing?.id ?: academic.insertModule(Module(semesterId = sem, name = module))
        // Deduplicate weeks: same-week files must share one week row.
        val existingWeek = academic.getWeeks(modId).firstOrNull { it.weekNumber == weekNumber }
        val weekId = existingWeek?.id
            ?: academic.insertWeek(Week(moduleId = modId, weekNumber = weekNumber, title = "W$weekNumber"))
        val fileId = academic.insertFile(
            AcademicFile(
                weekId = weekId, fileName = fileName, filePath = "/tmp/$fileName",
                fileType = "pdf", sha256 = sha, relativePath = fileName
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

    private suspend fun seedAll() {
        s1 = seedSemester("Year 1")
        s2 = seedSemester("Year 2")
        fileA = seedFile(
            s1, "Biology", 1, "cell-bio.pdf",
            listOf("Photosynthesis converts light energy in chloroplast thylakoid membranes producing glucose and oxygen.")
        )
        // Same week, disjoint vocab → SAME_WEEK VERIFIED, no content terms.
        seedFile(
            s1, "Biology", 1, "quantum.pdf",
            listOf("Quantum tunneling enables particles through barriers slowly.")
        )
        // Same-week mirror (same bytes) → excluded, never duplicated.
        seedFile(
            s1, "Biology", 1, "mirror.pdf",
            listOf("Photosynthesis converts light energy in chloroplast thylakoid membranes producing glucose and oxygen."),
            sha = "h-cell-bio.pdf"
        )
        // Week 2, overlapping vocab → SHARED_CONTENT VERIFIED.
        seedFile(
            s1, "Biology", 2, "respiration.pdf",
            listOf("Cellular respiration releases energy from glucose using oxygen in mitochondria, unlike thylakoid photosynthesis pathways.")
        )
        // Week 3, disjoint vocab → SAME_MODULE POSSIBLE.
        seedFile(
            s1, "Biology", 3, "pottery.pdf",
            listOf("Medieval pottery kilns fired clay vessels slowly.")
        )
        // Other module, overlapping vocab → SHARED_CONTENT VERIFIED.
        seedFile(
            s1, "Chemistry", 1, "chem-glucose.pdf",
            listOf("Glucose regulation involves oxygen transport and thylakoid research methods in photosynthesis labs.")
        )
        // Other module, exactly two shared terms → too weak, absent.
        seedFile(
            s1, "Chemistry", 2, "two-terms.pdf",
            listOf("Glucose oxygen pottery overview.")
        )
        // Twin text in the other semester → isolation test.
        seedFile(
            s2, "Physics", 1, "twin.pdf",
            listOf("Photosynthesis converts light energy in chloroplast thylakoid membranes producing glucose and oxygen.")
        )
    }

    @Test
    fun sameWeek_isVerifiedStructural() = runBlocking {
        seedAll()
        val out = repo.relatedFor(s1, fileA)
        val same = out.firstOrNull { it.relatedFileName == "quantum.pdf" }
        assertTrue(same != null)
        assertEquals(RelationshipType.SAME_WEEK, same!!.type)
        assertEquals(RelationshipStatus.VERIFIED, same.status)
        assertTrue(same.matchedTerms.isEmpty())
    }

    @Test
    fun sharedContent_needsThreeConfirmedTerms() = runBlocking {
        seedAll()
        val out = repo.relatedFor(s1, fileA)
        val byName = out.associateBy { it.relatedFileName }
        val resp = byName["respiration.pdf"]
        assertTrue(resp != null)
        assertEquals(RelationshipType.SHARED_CONTENT, resp!!.type)
        assertEquals(RelationshipStatus.VERIFIED, resp.status)
        assertTrue(resp.matchedTerms.size >= 3)
        assertTrue(resp.evidenceChunkIds.isNotEmpty())
        val chem = byName["chem-glucose.pdf"]
        assertTrue(chem != null)
        assertEquals(RelationshipType.SHARED_CONTENT, chem!!.type)
        // Two shared terms without a structural tie stay absent.
        assertTrue(byName["two-terms.pdf"] == null)
    }

    @Test
    fun sameModuleDifferentWeek_isPossible() = runBlocking {
        seedAll()
        // Pottery's view: Biology siblings are structural POSSIBLE.
        val pottery = db.academicDao().let { dao ->
            dao.getModules(s1).flatMap { dao.getWeeks(it.id) }
                .flatMap { dao.getFiles(it.id) }
                .first { it.fileName == "pottery.pdf" }.id
        }
        val out = repo.relatedFor(s1, pottery)
        assertTrue(out.isNotEmpty())
        assertTrue(out.all {
            it.type == RelationshipType.SAME_MODULE && it.status == RelationshipStatus.POSSIBLE
        })
    }

    @Test
    fun ordering_cap_selfMirrorAndIsolation() = runBlocking {
        seedAll()
        val out = repo.relatedFor(s1, fileA)
        // Cap: quantum + respiration + chem (pottery crowded out).
        assertEquals(3, out.size)
        // Order: SAME_WEEK, then content by term count, then module.
        assertEquals("quantum.pdf", out[0].relatedFileName)
        assertEquals("respiration.pdf", out[1].relatedFileName)
        assertEquals("chem-glucose.pdf", out[2].relatedFileName)
        // Self and same-sha mirror never appear.
        assertTrue(out.none { it.relatedFileId == fileA })
        assertTrue(out.none { it.relatedFileName == "mirror.pdf" })
        // Cross-semester twin never leaks in.
        assertTrue(out.none { it.relatedFileName == "twin.pdf" })
    }

    @Test
    fun provenance_resolves() = runBlocking {
        seedAll()
        val out = repo.relatedFor(s1, fileA)
        val resp = out.first { it.relatedFileName == "respiration.pdf" }
        assertEquals("Week 2", resp.relatedWeekLabel)
        assertEquals("Biology", resp.relatedModuleName)
        assertTrue(resp.relatedWeekId != null && resp.relatedWeekId > 0)
        assertTrue(resp.excerpt.isNotBlank())
        // Every evidence chunk really contains a matched term.
        for (id in resp.evidenceChunkIds) {
            val text = db.extractionDao().chunk(id)!!.text.lowercase()
            assertTrue(resp.matchedTerms.any { text.contains(it) })
        }
    }

    @Test
    fun deletedSource_returnsEmpty() = runBlocking {
        seedAll()
        assertTrue(repo.relatedFor(s1, 9999).isEmpty())
    }

    @Test
    fun unindexedSource_returnsEmpty() = runBlocking {
        seedAll()
        val academic = db.academicDao()
        val modId = academic.getModules(s1).first { it.name == "Biology" }.id
        val weekId = academic.insertWeek(Week(moduleId = modId, weekNumber = 9, title = "W9"))
        val bare = academic.insertFile(
            AcademicFile(
                weekId = weekId, fileName = "bare.pdf", filePath = "/tmp/bare.pdf",
                fileType = "pdf", sha256 = "bare", relativePath = "bare.pdf"
            )
        )
        assertTrue(repo.relatedFor(s1, bare).isEmpty())
    }

    @Test
    fun singleFileSemester_returnsEmpty() = runBlocking {
        val lone = seedSemester("Solo")
        val only = seedFile(lone, "Solo", 1, "only.pdf", listOf("Lonesome academic content here."))
        assertTrue(repo.relatedFor(lone, only).isEmpty())
    }

    @Test
    fun deterministicRepeat() = runBlocking {
        seedAll()
        assertEquals(repo.relatedFor(s1, fileA), repo.relatedFor(s1, fileA))
    }

    @Test
    fun adjacentWeeks_implyNothing() = runBlocking {
        seedAll()
        val out = repo.relatedFor(s1, fileA)
        val texts = out.map { it.reason.lowercase() }
        assertTrue(texts.none {
            it.contains("prerequisite") || it.contains("continuation") ||
                it.contains("builds on") || it.contains("depends on")
        })
        // Adjacent-week respiration is present for CONTENT, not adjacency.
        val resp = out.first { it.relatedFileName == "respiration.pdf" }
        assertEquals(RelationshipType.SHARED_CONTENT, resp.type)
    }

    @Test
    fun weaknessSource_requestDoesNotMutateEvidence() = runBlocking {
        seedAll()
        val quiz = db.quizDao()
        val sid = quiz.insertSession(
            QuizSession(
                semesterId = s1, seed = 1, totalQuestions = 1, correctCount = 0,
                status = QuizSession.STATUS_COMPLETED,
                startedAt = 1000, completedAt = 2000
            )
        )
        val chunkId = db.extractionDao().chunksForFile(fileA).first().id
        quiz.insertQuestions(
            listOf(
                QuizQuestion(
                    sessionId = sid, position = 0, chunkId = chunkId, academicFileId = fileA,
                    questionType = "MCQ", prompt = "Q?", optionsJson = "[\"a\",\"b\"]",
                    correctAnswer = "a", userAnswer = "b", isCorrect = false,
                    srcFileName = "cell-bio.pdf", srcFileType = "pdf", srcExcerpt = "excerpt"
                )
            )
        )
        val before = quiz.wrongAnswerEvents(s1, 500).size
        val out = repo.relatedFor(s1, fileA)
        assertTrue(out.isNotEmpty())
        assertEquals(before, quiz.wrongAnswerEvents(s1, 500).size)
    }
}
