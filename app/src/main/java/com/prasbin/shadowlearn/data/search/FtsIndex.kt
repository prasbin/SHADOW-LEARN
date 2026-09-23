package com.prasbin.shadowlearn.data.search

/**
 * SQLite full-text virtual index over extracted document chunks.
 *
 * Row identity: the FTS rowid == `document_chunks.id`, so results map back
 * to chunks/locations without a join and re-indexing is a plain
 * insert/delete mirror kept consistent by `ExtractionRepository` (the
 * repository deletes FTS rows together with chunk rows on re-extraction;
 * FTS rows have no FK by design — virtual tables cannot hold one).
 *
 * Module strategy (empirically verified, see docs/ARCHITECTURE.md):
 * Android's framework SQLite — including the API 36 emulator used for
 * verification — does NOT ship the FTS5 module (`no such module: fts5`),
 * while FTS4 is universally available. This class therefore picks FTS5
 * first when the runtime provides it and falls back to FTS4 otherwise,
 * using the SAME SQL in both dialects (`rowid`, `text`, `MATCH`,
 * `ORDER BY rank` are shared). Unit tests exercise BOTH real modules:
 * fts5 via sqlite-jdbc, the fallback via Robolectric's framework SQLite.
 *
 * The table is created on first use (ensureSchema), NOT in a Room
 * migration — it is intentionally absent from the exported schema so Room
 * validation never sees a non-entity virtual table.
 */
class FtsIndex(
    private val sql: SqlExecutor,
    private val onModuleChosen: (String) -> Unit = {}
) {

    @Volatile
    private var ensured = false

    /** Chosen module: "fts5" or "fts4". Empty until [ensureSchema]. */
    @Volatile
    var module: String = ""
        private set

    /** Creates the (empty) virtual table; idempotent. Throws if neither module exists. */
    fun ensureSchema() {
        if (ensured) return
        synchronized(this) {
            if (ensured) return
            try {
                sql.exec(CREATE_FTS5_SQL)
                module = "fts5"
            } catch (firstError: Exception) {
                try {
                    sql.exec(CREATE_FTS4_SQL)
                    module = "fts4"
                } catch (_: Exception) {
                    throw IllegalStateException(
                        "SQLite provides neither FTS5 nor FTS4. FTS5: " + firstError.message, firstError
                    )
                }
            }
            onModuleChosen(module)
            ensured = true
        }
    }

    fun insert(rowId: Long, text: String) {
        ensureSchema()
        sql.exec(INSERT_SQL, arrayOf(rowId, text))
    }

    fun insertAll(rows: List<Pair<Long, String>>) {
        if (rows.isEmpty()) return
        ensureSchema()
        rows.forEach { (id, text) -> sql.exec(INSERT_SQL, arrayOf(id, text)) }
    }

    /** Drops FTS mirror rows for the given chunk ids (mirrors chunk deletion). */
    fun deleteChunks(rowIds: Collection<Long>) {
        if (rowIds.isEmpty()) return
        ensureSchema()
        rowIds.chunked(MAX_BIND) { batch ->
            val placeholders = batch.joinToString(",") { "?" }
            sql.exec("DELETE FROM $TABLE WHERE rowid IN ($placeholders)", batch.toTypedArray())
        }
    }

    fun clear() {
        ensureSchema()
        sql.exec("DELETE FROM $TABLE")
    }

    /** Ranked MATCH search; results in FTS relevance order. */
    fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<FtsHit> {
        ensureSchema()
        val lower = query.trim()
        if (lower.isEmpty()) return emptyList()
        val rankedError = try {
            return queryRows(SEARCH_RANKED_SQL, lower, limit)
        } catch (e: Exception) {
            e
        }
        // Some SQLite builds (Robolectric's framework SQLite, older fts4)
        // lack the `rank` hidden column: retry deterministically without it.
        return try {
            queryRows(SEARCH_UNRANKED_SQL, lower, limit)
        } catch (_: Exception) {
            throw rankedError
        }
    }

    private fun queryRows(sql: String, query: String, limit: Int): List<FtsHit> =
        this.sql.queryRows(sql, arrayOf(query, limit)).mapNotNull { row ->
            val id = (row.getOrNull(0) as? Number)?.toLong()
                ?: row.getOrNull(0)?.toString()?.toLongOrNull()
            val text = row.getOrNull(1) as? String
            if (id == null) null else FtsHit(chunkId = id, text = text ?: "")
        }

    data class FtsHit(val chunkId: Long, val text: String)

    companion object {
        const val TABLE = "document_fts"
        const val DEFAULT_SEARCH_LIMIT = 50
        private const val MAX_BIND = 500

        private const val CREATE_FTS5_SQL =
            "CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE USING fts5(text)"
        private const val CREATE_FTS4_SQL =
            "CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE USING fts4(text)"
        private const val INSERT_SQL =
            "INSERT OR REPLACE INTO $TABLE(rowid, text) VALUES(?, ?)"
        private const val SEARCH_RANKED_SQL =
            "SELECT rowid, text FROM $TABLE WHERE $TABLE MATCH ? ORDER BY rank LIMIT ?"
        private const val SEARCH_UNRANKED_SQL =
            "SELECT rowid, text FROM $TABLE WHERE $TABLE MATCH ? LIMIT ?"
    }
}