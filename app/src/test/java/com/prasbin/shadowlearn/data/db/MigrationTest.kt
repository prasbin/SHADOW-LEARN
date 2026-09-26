package com.prasbin.shadowlearn.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration tests: builds a REAL database file at an older version from the
 * committed schema exports (`app/schemas/…/N.json` — table SQL + identity
 * hash), then opens it with the current [ShadowLearnDatabase] so the shipped
 * [androidx.room.AutoMigration]s run and verifies every row survives with
 * the new columns defaulted. No destructive migration anywhere in this path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun schemaJson(version: Int): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("schemas/$version.json")
            ?: error("schemas/$version.json test resource missing")
        return JSONObject(stream.bufferedReader().readText())
    }

    /** Executes the committed schema's DDL into an empty SQLite file at [version]. */
    @Suppress("DEPRECATION")
    private fun buildVersionedDb(dbFile: java.io.File, version: Int) {
        val database = schemaJson(version).getJSONObject("database")
        val vHash = database.getString("identityHash")
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        val entities = database.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val sql = e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName"))
            raw.execSQL(sql)
        }
        raw.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        raw.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES (42, ?)", arrayOf(vHash))
        raw.execSQL("PRAGMA user_version = $version")
        raw.close()
    }

    @Test
    fun migrate1ToCurrent_preservesRowsAndDefaultsNewColumns() {
        val dbFile = context.getDatabasePath("migtest1.db")
        buildVersionedDb(dbFile, 1)

        // --- v1 rows ---------------------------------------------------------
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        raw.execSQL("INSERT INTO academic_years (id, name, sortOrder) VALUES (1, 'Year 2', 2)")
        raw.execSQL("INSERT INTO semesters (id, yearId, name, sortOrder) VALUES (1, 1, 'Semester 1', 1)")
        raw.execSQL("INSERT INTO modules (id, semesterId, name, code) VALUES (1, 1, 'Programming', 'CS201')")
        raw.execSQL("INSERT INTO weeks (id, moduleId, weekNumber, title) VALUES (1, 1, 3, 'Week 3')")
        raw.execSQL(
            "INSERT INTO academic_files (id, weekId, fileName, filePath, fileType, sha256, " +
                "fileSize, lastModified, indexed, createdAt, updatedAt) " +
                "VALUES (1, 1, 'l.pdf', '/old/path', 'pdf', 'abc', 10, 5, 0, 1, 2)"
        )
        raw.close()

        // --- open with the current DB (AutoMigrations 1→2→3 apply) ----------
        val db = Room.databaseBuilder(context, ShadowLearnDatabase::class.java, "migtest1.db")
            .allowMainThreadQueries()
            .addMigrations(ShadowLearnDatabase.MIGRATION_3_4, ShadowLearnDatabase.MIGRATION_4_5, ShadowLearnDatabase.MIGRATION_5_6, ShadowLearnDatabase.MIGRATION_6_7)
            .build()
        try {
            assertEquals(7, db.openHelper.readableDatabase.version)
            val dao = db.academicDao()
            runBlocking {
                assertEquals(listOf("Year 2"), dao.getYears().map { it.name })
                assertEquals(1, dao.getSemesters(1).size)
                assertEquals(1, dao.getModuleCount())
                val file = dao.findFileByHash("abc")!!
                assertEquals("l.pdf", file.fileName)
                // New v2/v3 columns defaulted, old data untouched.
                assertEquals("OTHER", file.classType)
                assertEquals("", file.relativePath)
                assertEquals("/old/path", file.filePath)
                assertEquals(null, file.sourceFileId)
                assertEquals(0, dao.getContentCount())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate2ToCurrent_preservesRowsAndLinksV3Column() {
        val dbFile = context.getDatabasePath("migtest2.db")
        buildVersionedDb(dbFile, 2)

        // v2 rows (identical insert shape to v1, plus a v2 column value).
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        raw.execSQL("INSERT INTO academic_years (id, name, sortOrder) VALUES (1, 'Year 2', 2)")
        raw.execSQL("INSERT INTO semesters (id, yearId, name, sortOrder) VALUES (1, 1, 'Semester 1', 1)")
        raw.execSQL("INSERT INTO modules (id, semesterId, name, code) VALUES (1, 1, 'Programming', 'CS201')")
        raw.execSQL("INSERT INTO weeks (id, moduleId, weekNumber, title) VALUES (1, 1, 3, 'Week 3')")
        raw.execSQL(
            "INSERT INTO academic_files (id, weekId, fileName, filePath, fileType, sha256, classType, " +
                "relativePath, fileSize, lastModified, indexed, createdAt, updatedAt) " +
                "VALUES (1, 1, 'l.pdf', '/app/academic/i1/Programming.zip/Week 3/Lecture/l.pdf', 'pdf', " +
                "'deadbeef', 'LECTURE', 'Programming.zip/Week 3/Lecture/l.pdf', 10, 5, 0, 1, 2)"
        )
        raw.close()

        // --- open with the current DB (AutoMigration 2→3 applies) ----------
        val db = Room.databaseBuilder(context, ShadowLearnDatabase::class.java, "migtest2.db")
            .allowMainThreadQueries()
            .addMigrations(ShadowLearnDatabase.MIGRATION_3_4, ShadowLearnDatabase.MIGRATION_4_5, ShadowLearnDatabase.MIGRATION_5_6, ShadowLearnDatabase.MIGRATION_6_7)
            .build()
        try {
            assertEquals(7, db.openHelper.readableDatabase.version)
            val dao = db.academicDao()
            runBlocking {
                assertEquals(listOf("Year 2"), dao.getYears().map { it.name })
                val file = dao.findFileByHash("deadbeef")!!
                assertEquals("LECTURE", file.classType)
                assertEquals("Programming.zip/Week 3/Lecture/l.pdf", file.relativePath)
                // New v3 column defaults to NULL; content table starts empty.
                assertEquals(null, file.sourceFileId)
                assertEquals(0, dao.getContentCount())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate3ToCurrent_preservesRowsAndAddsPhase4Tables() {
        val dbFile = context.getDatabasePath("migtest3.db")
        buildVersionedDb(dbFile, 3)

        // v3 rows (content-identity columns present).
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        raw.execSQL("INSERT INTO academic_years (id, name, sortOrder) VALUES (1, 'Year 2', 2)")
        raw.execSQL("INSERT INTO semesters (id, yearId, name, sortOrder) VALUES (1, 1, 'Semester 1', 1)")
        raw.execSQL("INSERT INTO modules (id, semesterId, name, code) VALUES (1, 1, 'Artificial Intelligence', NULL)")
        raw.execSQL("INSERT INTO weeks (id, moduleId, weekNumber, title) VALUES (1, 1, 2, 'Week 2')")
        raw.execSQL(
            "INSERT INTO source_files (id, sha256, storedPath, fileSize, refCount, createdAt) " +
                "VALUES (1, 'deadbeef', '/data/.../source/deadbeef', 10, 1, 1)"
        )
        raw.execSQL(
            "INSERT INTO academic_files (id, weekId, fileName, filePath, fileType, sha256, classType, " +
                "relativePath, fileSize, lastModified, indexed, createdAt, updatedAt, sourceFileId) " +
                "VALUES (1, 1, 'slides.pdf', '/data/.../source/deadbeef', 'pdf', 'deadbeef', " +
                "'LECTURE', 'AI.zip/Week 2/Lecture/slides.pdf', 10, 5, 0, 1, 2, 1)"
        )
        raw.close()

        // --- open with the current DB: AutoMigration 3→... + manual MIGRATION_3_4 ---
        val db = Room.databaseBuilder(context, ShadowLearnDatabase::class.java, "migtest3.db")
            .allowMainThreadQueries()
            .addMigrations(ShadowLearnDatabase.MIGRATION_3_4, ShadowLearnDatabase.MIGRATION_4_5, ShadowLearnDatabase.MIGRATION_5_6, ShadowLearnDatabase.MIGRATION_6_7)
            .build()
        try {
            assertEquals(7, db.openHelper.readableDatabase.version)
            val dao = db.academicDao()
            val extractionDao = db.extractionDao()
            runBlocking {
                val file = dao.findFileByHash("deadbeef")!!
                assertEquals("LECTURE", file.classType)
                assertEquals(1L, file.sourceFileId)
                assertEquals(1, dao.getContentCount())
                // Phase 4 tables exist, empty, and queryable.
                assertNull(extractionDao.getMeta(file.id))
                assertEquals(0, extractionDao.chunkCountForFile(file.id))
                assertEquals(listOf(file.id), extractionDao.filesOfSemester(1).map { it.id })
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate4ToCurrent_preservesChunksAndAddsPhase6Tables() {
        val dbFile = context.getDatabasePath("migtest4.db")
        buildVersionedDb(dbFile, 4)

        // v4 rows (Phase 4 tables present).
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        raw.execSQL("INSERT INTO academic_years (id, name, sortOrder) VALUES (1, 'Year 2', 2)")
        raw.execSQL("INSERT INTO semesters (id, yearId, name, sortOrder) VALUES (1, 1, 'Semester 1', 1)")
        raw.execSQL("INSERT INTO modules (id, semesterId, name, code) VALUES (1, 1, 'Artificial Intelligence', NULL)")
        raw.execSQL("INSERT INTO weeks (id, moduleId, weekNumber, title) VALUES (1, 1, 2, 'Week 2')")
        raw.execSQL(
            "INSERT INTO academic_files (id, weekId, fileName, filePath, fileType, sha256, classType, " +
                "relativePath, fileSize, lastModified, indexed, createdAt, updatedAt, sourceFileId) " +
                "VALUES (1, 1, 'neural.pdf', '/data/.../source/live', 'pdf', 'cafe', " +
                "'LECTURE', 'AI.zip/Week 2/Lecture/neural.pdf', 10, 5, 1, 1, 2, NULL)"
        )
        raw.execSQL(
            "INSERT INTO document_chunks (id, academicFileId, chunkIndex, pageNumber, text, charCount, createdAt) " +
                "VALUES (9, 1, 0, 1, 'neural networks learn by gradient descent', 40, 3)"
        )
        raw.close()

        // --- open with the current DB: manual MIGRATION_4_5 applies ---
        val db = Room.databaseBuilder(context, ShadowLearnDatabase::class.java, "migtest4.db")
            .allowMainThreadQueries()
            .addMigrations(ShadowLearnDatabase.MIGRATION_3_4, ShadowLearnDatabase.MIGRATION_4_5, ShadowLearnDatabase.MIGRATION_5_6, ShadowLearnDatabase.MIGRATION_6_7)
            .build()
        try {
            assertEquals(7, db.openHelper.readableDatabase.version)
            val dao = db.academicDao()
            val extractionDao = db.extractionDao()
            val quizDao = db.quizDao()
            runBlocking {
                // Chunk rows survive exactly; the semester pool is queryable.
                val file = dao.findFileByHash("cafe")!!
                assertEquals(1, extractionDao.chunkCountForFile(file.id))
                val pool = quizDao.chunksOfSemester(1)
                assertEquals(listOf(9L), pool.map { it.chunkId })
                assertEquals("neural.pdf", pool.first().fileName)
                // Quiz tables exist, start empty, and are fully usable.
                assertNull(quizDao.lastCompleted())
                val sessionId = quizDao.insertSession(
                    QuizSession(semesterId = 1, seed = 42, totalQuestions = 1)
                )
                quizDao.insertQuestions(
                    listOf(
                        QuizQuestion(
                            sessionId = sessionId, position = 0, chunkId = 9,
                            academicFileId = file.id, questionType = "MCQ",
                            prompt = "prompt", optionsJson = "[\"a\",\"b\"]",
                            correctAnswer = "a", srcFileName = "neural.pdf",
                            srcFileType = "pdf", srcPage = 1, srcExcerpt = "neural networks"
                        )
                    )
                )
                assertEquals(1, quizDao.questions(sessionId).size)
                assertEquals(0, quizDao.completedCount())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate5ToCurrent_preservesQuizAndAddsPhase7Tables() {
        val dbFile = context.getDatabasePath("migtest5.db")
        buildVersionedDb(dbFile, 5)

        // v5 rows (quiz tables present, listener tables absent).
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        raw.execSQL("INSERT INTO academic_years (id, name, sortOrder) VALUES (1, 'Year 2', 2)")
        raw.execSQL("INSERT INTO semesters (id, yearId, name, sortOrder) VALUES (1, 1, 'Semester 1', 1)")
        raw.execSQL("INSERT INTO modules (id, semesterId, name, code) VALUES (1, 1, 'AI', NULL)")
        raw.execSQL("INSERT INTO weeks (id, moduleId, weekNumber, title) VALUES (1, 1, 2, 'Week 2')")
        raw.execSQL(
            "INSERT INTO academic_files (id, weekId, fileName, filePath, fileType, sha256, classType, " +
                "relativePath, fileSize, lastModified, indexed, createdAt, updatedAt, sourceFileId) " +
                "VALUES (1, 1, 'neural.pdf', '/data/x', 'pdf', 'cafe', 'LECTURE', " +
                "'AI.zip/Week 2/neural.pdf', 10, 5, 1, 1, 2, NULL)"
        )
        raw.execSQL(
            "INSERT INTO quiz_sessions (id, semesterId, seed, totalQuestions, correctCount, " +
                "xpEarned, streak, status, startedAt, completedAt) " +
                "VALUES (3, 1, 42, 5, 4, 40, 2, 'completed', 9, 10)"
        )
        raw.close()

        // --- open with the current DB: manual MIGRATION_5_6 + 6_7 apply ---
        val db = Room.databaseBuilder(context, ShadowLearnDatabase::class.java, "migtest5.db")
            .allowMainThreadQueries()
            .addMigrations(
                ShadowLearnDatabase.MIGRATION_3_4,
                ShadowLearnDatabase.MIGRATION_4_5,
                ShadowLearnDatabase.MIGRATION_5_6,
                ShadowLearnDatabase.MIGRATION_6_7
            )
            .build()
        try {
            assertEquals(7, db.openHelper.readableDatabase.version)
            runBlocking {
                // Quiz history survives exactly.
                assertEquals(1, db.quizDao().completedCount())
                // Listener tables exist, start empty, and are fully usable.
                val listenerDao = db.listenerDao()
                assertEquals(0, listenerDao.sessionCount(1))
                val id = listenerDao.insertSession(ListenerSession(semesterId = 1))
                listenerDao.insertSegment(
                    ListenerSegment(sessionId = id, position = 0, startedAtMs = 0)
                )
                assertEquals(1, listenerDao.segments(id).size)
                assertEquals(1, listenerDao.sessionCount(1))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate6ToCurrent_preservesAcademicQuizListenerAndAddsPhase8Tables() {
        val dbFile = context.getDatabasePath("migtest6.db")
        buildVersionedDb(dbFile, 6)

        // v6 rows (all prior tables present, flashcard tables absent).
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        raw.execSQL("INSERT INTO academic_years (id, name, sortOrder) VALUES (1, 'Year 2', 2)")
        raw.execSQL("INSERT INTO semesters (id, yearId, name, sortOrder) VALUES (1, 1, 'Semester 1', 1)")
        raw.execSQL("INSERT INTO modules (id, semesterId, name, code) VALUES (1, 1, 'AI', NULL)")
        raw.execSQL("INSERT INTO weeks (id, moduleId, weekNumber, title) VALUES (1, 1, 2, 'Week 2')")
        raw.execSQL(
            "INSERT INTO academic_files (id, weekId, fileName, filePath, fileType, sha256, classType, " +
                "relativePath, fileSize, lastModified, indexed, createdAt, updatedAt, sourceFileId) " +
                "VALUES (1, 1, 'neural.pdf', '/data/x', 'pdf', 'cafe', 'LECTURE', " +
                "'AI.zip/Week 2/neural.pdf', 10, 5, 1, 1, 2, NULL)"
        )
        raw.execSQL(
            "INSERT INTO document_chunks (id, academicFileId, chunkIndex, pageNumber, text, charCount, createdAt) " +
                "VALUES (9, 1, 0, 1, 'neural networks learn by gradient descent', 40, 3)"
        )
        raw.execSQL(
            "INSERT INTO quiz_sessions (id, semesterId, seed, totalQuestions, correctCount, " +
                "xpEarned, streak, status, startedAt, completedAt) " +
                "VALUES (3, 1, 42, 5, 4, 40, 2, 'completed', 9, 10)"
        )
        raw.execSQL(
            "INSERT INTO listener_sessions (id, semesterId, status, startedAt, completedAt, audioPath, createdAt) " +
                "VALUES (7, 1, 'completed', 1, 2, '/data/audio.3gp', 3)"
        )
        raw.close()

        // --- open with the current DB: manual MIGRATION_6_7 applies ---
        val db = Room.databaseBuilder(context, ShadowLearnDatabase::class.java, "migtest6.db")
            .allowMainThreadQueries()
            .addMigrations(
                ShadowLearnDatabase.MIGRATION_3_4,
                ShadowLearnDatabase.MIGRATION_4_5,
                ShadowLearnDatabase.MIGRATION_5_6,
                ShadowLearnDatabase.MIGRATION_6_7
            )
            .build()
        try {
            assertEquals(7, db.openHelper.readableDatabase.version)
            runBlocking {
                // Academic + chunk data survives exactly.
                val file = db.academicDao().findFileByHash("cafe")!!
                assertEquals(1, db.extractionDao().chunkCountForFile(file.id))
                // Quiz history survives exactly.
                assertEquals(1, db.quizDao().completedCount())
                // Listener history survives exactly.
                assertEquals(1, db.listenerDao().sessionCount(1))
                // Flashcard tables exist, start empty, and are fully usable.
                val dao = db.flashcardDao()
                assertEquals(0, dao.cardCount(999))
                val deckId = dao.insertDeck(FlashcardDeck(semesterId = 1, title = "Test Deck"))
                val cardId = dao.insertCards(
                    listOf(
                        Flashcard(
                            deckId = deckId, front = "q", back = "a",
                            sourceLabel = "neural.pdf · PDF · PAGE 1",
                            contentKey = "chunk:1:test"
                        )
                    )
                ).first()
                assertEquals(1, dao.cardCount(deckId))
                val sessionId = dao.insertReviewSession(ReviewSession(deckId = deckId))
                val card = dao.card(cardId)!!
                dao.grade(
                    cardId = cardId, ease = 2.5, interval = 1, dueAt = System.currentTimeMillis(),
                    at = System.currentTimeMillis(),
                    event = ReviewEvent(
                        sessionId = sessionId, flashcardId = cardId, rating = "GOOD",
                        previousEaseFactor = card.easeFactor, newEaseFactor = 2.5,
                        previousIntervalDays = 0, newIntervalDays = 1, retained = true
                    ),
                    sessionId = sessionId, reviewed = 1, retained = 1
                )
                assertEquals(1, dao.reviewEvents(sessionId).size)
            }
        } finally {
            db.close()
        }
    }
}
