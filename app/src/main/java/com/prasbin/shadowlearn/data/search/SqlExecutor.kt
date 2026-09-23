package com.prasbin.shadowlearn.data.search

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Minimal SQL execution seam for the FTS index. Two real implementations:
 *
 * - [RoomBackedSqlExecutor] — production, runs on the app's Room
 *   connection (`RoomDatabase.openHelper`), so FTS rows share the same
 *   file and lifecycle as `document_chunks`.
 * - `JdbcSqlExecutor` (test sources) — runs the identical SQL against the
 *   bundled `sqlite-jdbc` (builds FTS5) to exercise the FTS5 module path
 *   deterministically in unit tests. See docs/ARCHITECTURE.md for the
 *   FTS5→FTS4 module story.
 */
interface SqlExecutor {
    fun exec(sql: String, bindArgs: Array<Any?> = emptyArray())

    fun queryRows(sql: String, bindArgs: Array<Any?> = emptyArray()): List<Array<Any?>>
}

/** Production executor over the app database's [SupportSQLiteDatabase]. */
class RoomBackedSqlExecutor(private val db: SupportSQLiteDatabase) : SqlExecutor {

    override fun exec(sql: String, bindArgs: Array<Any?>) {
        db.execSQL(sql, bindArgs)
    }

    override fun queryRows(sql: String, bindArgs: Array<Any?>): List<Array<Any?>> {
        val cursor = db.query(sql, bindArgs)
        return cursor.use { c ->
            val cols = c.columnCount
            val result = mutableListOf<Array<Any?>>()
            while (c.moveToNext()) {
                val row = arrayOfNulls<Any?>(cols)
                for (j in 0 until cols) {
                    // Cursor.getType() constants: NULL=0, INTEGER=1, FLOAT=2, STRING=3, BLOB=4.
                    row[j] = when (c.getType(j)) {
                        1 -> c.getLong(j)
                        2 -> c.getDouble(j)
                        3 -> c.getString(j)
                        4 -> c.getBlob(j)
                        else -> null
                    }
                }
                result += row
            }
            result
        }
    }
}