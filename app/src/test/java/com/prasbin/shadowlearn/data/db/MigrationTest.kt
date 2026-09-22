package com.prasbin.shadowlearn.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1 → v2 migration test: builds a REAL v1 database file from the committed
 * `app/schemas/.../1.json` export (table SQL + identity hash), inserts v1
 * rows, then opens it with the v2 [ShadowLearnDatabase] (declared
 * AutoMigration 1→2) and verifies every row survives with the new columns
 * defaulted. No destructive migration anywhere in this path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun schemaJson(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("schemas/1.json")
            ?: error("schemas/1.json test resource missing")
        return JSONObject(stream.bufferedReader().readText())
    }

    @Test
    fun migrate1To2_preservesRowsAndDefaultsNewColumns() {
        val schema = schemaJson()
        val database = schema.getJSONObject("database")
        val v1Hash = database.getString("identityHash")
        val dbFile = context.getDatabasePath("migtest.db")
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        // --- create a genuine v1 file --------------------------------------
        val raw = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        val entities = database.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val sql = e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName"))
            raw.execSQL(sql)
        }
        raw.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        raw.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES (42, ?)", arrayOf(v1Hash))
        raw.execSQL("INSERT INTO academic_years (id, name, sortOrder) VALUES (1, 'Year 2', 2)")
        raw.execSQL("INSERT INTO semesters (id, yearId, name, sortOrder) VALUES (1, 1, 'Semester 1', 1)")
        raw.execSQL("INSERT INTO modules (id, semesterId, name, code) VALUES (1, 1, 'Programming', 'CS201')")
        raw.execSQL("INSERT INTO weeks (id, moduleId, weekNumber, title) VALUES (1, 1, 3, 'Week 3')")
        raw.execSQL(
            "INSERT INTO academic_files (id, weekId, fileName, filePath, fileType, sha256, " +
                "fileSize, lastModified, indexed, createdAt, updatedAt) " +
                "VALUES (1, 1, 'l.pdf', '/old/path', 'pdf', 'abc', 10, 5, 0, 1, 2)"
        )
        raw.execSQL("PRAGMA user_version = 1")
        raw.close()

        // --- open with v2 (AutoMigration 1→2 applies automatically) ---------
        val db = Room.databaseBuilder(context, ShadowLearnDatabase::class.java, "migtest.db")
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(2, db.openHelper.readableDatabase.version)
            val dao = db.academicDao()
            runBlocking {
                assertEquals(listOf("Year 2"), dao.getYears().map { it.name })
                assertEquals(1, dao.getSemesters(1).size)
                assertEquals(1, dao.getModuleCount())
                val file = dao.findFileByHash("abc")!!
                assertEquals("l.pdf", file.fileName)
                // New v2 columns defaulted, old data untouched.
                assertEquals("OTHER", file.classType)
                assertEquals("", file.relativePath)
                assertEquals("/old/path", file.filePath)
            }
        } finally {
            db.close()
        }
    }
}
