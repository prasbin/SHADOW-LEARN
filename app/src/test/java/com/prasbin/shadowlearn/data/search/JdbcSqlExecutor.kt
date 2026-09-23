package com.prasbin.shadowlearn.data.search

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/**
 * JVM-only [SqlExecutor] backed by `org.xerial:sqlite-jdbc` (test scope).
 * Its bundled SQLite ships the FTS5 module, so unit tests exercise the
 * FTS5 path of [FtsIndex] against a REAL FTS5 index deterministically —
 * using exactly the SQL strings production runs via [RoomBackedSqlExecutor].
 */
class JdbcSqlExecutor private constructor(private val connection: Connection) :
    SqlExecutor, AutoCloseable {

    companion object {
        fun inMemory(): JdbcSqlExecutor =
            JdbcSqlExecutor(DriverManager.getConnection("jdbc:sqlite::memory:"))

        fun file(path: File): JdbcSqlExecutor {
            path.parentFile?.mkdirs()
            return JdbcSqlExecutor(DriverManager.getConnection("jdbc:sqlite:${path.absolutePath}"))
        }
    }

    override fun exec(sql: String, bindArgs: Array<Any?>) {
        connection.prepareStatement(sql).use { stmt -> bind(stmt, bindArgs); stmt.execute() }
    }

    override fun queryRows(sql: String, bindArgs: Array<Any?>): List<Array<Any?>> =
        connection.prepareStatement(sql).use { stmt ->
            bind(stmt, bindArgs)
            stmt.executeQuery().use { rs ->
                val cols = rs.metaData.columnCount
                val result = mutableListOf<Array<Any?>>()
                while (rs.next()) {
                    result += Array(cols) { j -> rs.getObject(j + 1) }
                }
                result
            }
        }

    private fun bind(stmt: PreparedStatement, args: Array<Any?>) {
        args.forEachIndexed { i, value ->
            when (value) {
                null -> stmt.setObject(i + 1, null)
                is Long -> stmt.setLong(i + 1, value)
                is Int -> stmt.setLong(i + 1, value.toLong())
                is String -> stmt.setString(i + 1, value)
                is Double -> stmt.setDouble(i + 1, value)
                else -> stmt.setObject(i + 1, value)
            }
        }
    }

    override fun close() {
        connection.close()
    }
}