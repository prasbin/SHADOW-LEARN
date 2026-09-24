package com.prasbin.shadowlearn.data.search

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FTS index tests against BOTH real modules:
 * - FTS5 via sqlite-jdbc (the spec-mandated module, available on JVM).
 * - The FTS4 fallback via Robolectric's framework SQLite (identical SQL;
 *   this is the path production uses on Android, whose SQLite lacks FTS5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FtsIndexTest {

    private lateinit var jdbcExecutor: JdbcSqlExecutor
    private lateinit var jdbcFile: File

    @Before
    fun setUp() {
        jdbcFile = File.createTempFile("fts-index-test", ".db")
        jdbcExecutor = JdbcSqlExecutor.file(jdbcFile)
    }

    @After
    fun tearDown() {
        jdbcExecutor.close()
        jdbcFile.delete()
    }

    @Test
    fun fts5_moduleSelectedAndRankedSearchWorks() {
        var moduleChosen = ""
        val index = FtsIndex(jdbcExecutor) { moduleChosen = it }
        index.ensureSchema()
        assertEquals("fts5", moduleChosen)
        assertEquals("fts5", index.module)

        index.insert(1, "neural networks gradient descent")
        index.insert(2, "gradient coloring book")
        index.insert(3, "cooking pasta")

        val hits = index.search("gradient")
        assertEquals(2, hits.size)
        assertTrue(hits.map { it.chunkId }.containsAll(listOf(1L, 2L)))

        index.deleteChunks(listOf(2L))
        assertEquals(1, index.search("gradient").size)
        assertEquals(1L, index.search("gradient").first().chunkId)

        index.clear()
        assertEquals(0, index.search("gradient").size)
    }

    @Test
    fun fts4_fallbackOnFrameworkSqlite() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, com.prasbin.shadowlearn.data.db.ShadowLearnDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            var moduleChosen = ""
            val index = FtsIndex(RoomBackedSqlExecutor(db.openHelper.writableDatabase)) { moduleChosen = it }
            index.ensureSchema()
            // Framework SQLite (= Robolectric's bundled sqlite) lacks FTS5.
            assertEquals("fts4", moduleChosen)
            assertEquals("fts4", index.module)

            index.insert(11, "document extraction pipeline")
            index.insert(12, "extraction is deterministic")
            val hits = index.search("extraction")
            assertEquals(2, hits.size)
            assertTrue(hits.map { it.chunkId }.contains(11L))
            index.deleteChunks(listOf(12L))
            assertEquals(1, index.search("extraction").size)
        } finally {
            db.close()
        }
    }

    @Test
    fun rankedDeterminism_andInsertReplace() {
        val a = FtsIndex(jdbcExecutor)
        a.insert(10, "alpha beta")
        a.insert(11, "alpha only")
        a.insert(12, "nothing here")
        val r1 = a.search("alpha").map { it.chunkId to it.text }
        // INSERT OR REPLACE on the same rowid keeps a stable set.
        a.insert(11, "alpha beta gamma")
        val r2 = a.search("alpha").map { it.chunkId to it.text }
        assertEquals(r1.map { it.first }, r2.map { it.first })
        assertTrue(r2.any { it.first == 11L && it.second.contains("gamma") })
    }

    /**
     * Phase 5 query layer against REAL FTS5 (sqlite-jdbc): the sanitized
     * prefix expression produced by SearchQuery must behave identically on
     * the FTS5 module production prefers when available.
     */
    @Test
    fun fts5_multiTermPrefixExpressionIsAnd() {
        val a = FtsIndex(jdbcExecutor)
        a.insert(1, "gradient descent methods")
        a.insert(2, "gradient only here")
        a.insert(3, "descent methods only")

        val andExpr = SearchQuery.parse("gradient descent").toMatchExpression()
        assertEquals("gradient* descent*", andExpr)
        // Space = implicit AND: only chunk 1 contains BOTH. (chunk 3 lacks "gradient".)
        assertEquals(listOf(1L), a.search(andExpr).map { it.chunkId })

        val prefixExpr = SearchQuery.parse("grad desc").toMatchExpression()
        assertEquals(1, a.search(prefixExpr).size)
    }

    @Test
    fun fts5_sanitizedSpecialCharQueryStillMatches() {
        val a = FtsIndex(jdbcExecutor)
        a.insert(7, "neural networks or transformers")
        a.insert(8, "cooking pasta")
        // There must be no syntax risk in the sanitized expression.
        val expr = SearchQuery.parse("neural!! (networks) \"*OR\"").toMatchExpression()
        assertEquals("neural* networks* or*", expr)
        assertEquals(listOf(7L), a.search(expr).map { it.chunkId })
    }
}