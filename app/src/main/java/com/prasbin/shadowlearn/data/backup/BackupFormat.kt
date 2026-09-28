package com.prasbin.shadowlearn.data.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 13 portable archive format (v1) — pure Kotlin + org.json (present
 * on Android and under Robolectric, like the quiz options codec).
 *
 * Layout inside the ZIP (all paths use `/`):
 *
 * ```
 * SHADOW_LEARN_ARCHIVE/
 *   manifest.json
 *   academic/<relativePath…>          # mirrors the import layout, so
 *                                     # re-import reproduces positions
 *   history/quiz_sessions.json
 *   history/quiz_questions.json
 *   history/flashcard_decks.json
 *   history/flashcards.json
 *   history/flashcard_review_sessions.json
 *   history/flashcard_review_events.json
 *   history/listener_sessions.json
 *   history/listener_segments.json
 * ```
 *
 * Listener audio policy (explicit, honest): audio is NEVER included
 * (`audioIncluded=false`); metadata/transcripts travel, and
 * `excludedAudioCount` + `audioNote` say so. Raw bytes never enter SQLite.
 */
object BackupFormat {

    const val ROOT_DIR = "SHADOW_LEARN_ARCHIVE"
    const val MANIFEST_PATH = "$ROOT_DIR/manifest.json"
    const val ACADEMIC_PREFIX = "$ROOT_DIR/academic/"
    const val HISTORY_PREFIX = "$ROOT_DIR/history/"

    const val FORMAT_ID = "SHADOW_LEARN_ARCHIVE"
    const val ARCHIVE_VERSION = 1

    const val QUIZ_SESSIONS_FILE = "quiz_sessions.json"
    const val QUIZ_QUESTIONS_FILE = "quiz_questions.json"
    const val FLASHCARD_DECKS_FILE = "flashcard_decks.json"
    const val FLASHCARDS_FILE = "flashcards.json"
    const val REVIEW_SESSIONS_FILE = "flashcard_review_sessions.json"
    const val REVIEW_EVENTS_FILE = "flashcard_review_events.json"
    const val LISTENER_SESSIONS_FILE = "listener_sessions.json"
    const val LISTENER_SEGMENTS_FILE = "listener_segments.json"

    const val AUDIO_EXCLUDED_REASON =
        "Listener audio is app-private and excluded; metadata/transcripts preserved."

    /** One academic file entry in the manifest. */
    data class ManifestFile(val path: String, val sha256: String, val size: Long)

    /** Counts block of the manifest (descriptive, not a promise). */
    data class ManifestCounts(
        val modules: Int = 0,
        val weeks: Int = 0,
        val files: Int = 0,
        val quizSessions: Int = 0,
        val quizQuestions: Int = 0,
        val decks: Int = 0,
        val cards: Int = 0,
        val reviewSessions: Int = 0,
        val reviewEvents: Int = 0,
        val listenerSessions: Int = 0,
        val listenerSegments: Int = 0
    )

    /** Parsed + validated manifest. */
    data class Manifest(
        val archiveVersion: Int,
        val appVersion: String,
        val schemaVersion: Int,
        val exportedAt: Long,
        val yearName: String,
        val semesterName: String,
        val audioIncluded: Boolean,
        val excludedAudioCount: Int,
        val audioNote: String,
        val counts: ManifestCounts,
        val files: List<ManifestFile>
    )

    /** Builds the manifest JSON string (keys in fixed order for determinism). */
    fun buildManifest(
        appVersion: String,
        schemaVersion: Int,
        exportedAt: Long,
        yearName: String,
        semesterName: String,
        excludedAudioCount: Int,
        counts: ManifestCounts,
        files: List<ManifestFile>
    ): String {
        val root = JSONObject()
        root.put("format", FORMAT_ID)
        root.put("archiveVersion", ARCHIVE_VERSION)
        root.put("appVersion", appVersion)
        root.put("schemaVersion", schemaVersion)
        root.put("exportedAt", exportedAt)
        root.put("year", JSONObject().put("name", yearName))
        root.put("semester", JSONObject().put("name", semesterName))
        root.put("audioIncluded", false)
        root.put("excludedAudioCount", excludedAudioCount)
        root.put("audioNote", AUDIO_EXCLUDED_REASON)
        val c = JSONObject()
        c.put("modules", counts.modules)
        c.put("weeks", counts.weeks)
        c.put("files", counts.files)
        c.put("quizSessions", counts.quizSessions)
        c.put("quizQuestions", counts.quizQuestions)
        c.put("decks", counts.decks)
        c.put("cards", counts.cards)
        c.put("reviewSessions", counts.reviewSessions)
        c.put("reviewEvents", counts.reviewEvents)
        c.put("listenerSessions", counts.listenerSessions)
        c.put("listenerSegments", counts.listenerSegments)
        root.put("counts", c)
        val arr = JSONArray()
        for (f in files.sortedBy { it.path }) {
            arr.put(
                JSONObject()
                    .put("path", f.path)
                    .put("sha256", f.sha256)
                    .put("size", f.size)
            )
        }
        root.put("files", arr)
        return root.toString(2)
    }

    /** Failure reason when validation rejects an archive (zero writes). */
    class ManifestException(message: String) : Exception(message)

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    /**
     * Parses + strictly validates manifest JSON. Throws [ManifestException]
     * on anything malformed, unsupported, or missing — callers must perform
     * zero writes in that case.
     */
    @Throws(ManifestException::class)
    fun parseManifest(json: String): Manifest {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw ManifestException("Manifest is not valid JSON: ${e.message}")
        }
        if (root.optString("format", "") != FORMAT_ID) {
            throw ManifestException("Not a SHADOW LEARN archive (bad format id).")
        }
        val version = root.optInt("archiveVersion", -1)
        if (version != ARCHIVE_VERSION) {
            throw ManifestException("Unsupported archive version: $version (supports $ARCHIVE_VERSION).")
        }
        val yearName = root.optJSONObject("year")?.optString("name", "")?.trim().orEmpty()
        val semesterName = root.optJSONObject("semester")?.optString("name", "")?.trim().orEmpty()
        if (yearName.isEmpty() || semesterName.isEmpty()) {
            throw ManifestException("Manifest year/semester names are missing.")
        }
        val countsObj = root.optJSONObject("counts")
            ?: throw ManifestException("Manifest counts block is missing.")
        fun count(key: String): Int {
            val v = countsObj.optInt(key, -1)
            if (v < 0) throw ManifestException("Manifest count '$key' is missing or negative.")
            return v
        }
        val counts = ManifestCounts(
            modules = count("modules"),
            weeks = count("weeks"),
            files = count("files"),
            quizSessions = count("quizSessions"),
            quizQuestions = count("quizQuestions"),
            decks = count("decks"),
            cards = count("cards"),
            reviewSessions = count("reviewSessions"),
            reviewEvents = count("reviewEvents"),
            listenerSessions = count("listenerSessions"),
            listenerSegments = count("listenerSegments")
        )
        val filesArr = root.optJSONArray("files")
            ?: throw ManifestException("Manifest files list is missing.")
        val files = mutableListOf<ManifestFile>()
        for (i in 0 until filesArr.length()) {
            val o = filesArr.optJSONObject(i)
                ?: throw ManifestException("Manifest file entry #$i is not an object.")
            val path = o.optString("path", "")
            val sha = o.optString("sha256", "").lowercase()
            val size = o.optLong("size", -1)
            if (path.isEmpty() || !path.startsWith(ACADEMIC_PREFIX)) {
                throw ManifestException("Manifest file entry #$i has a bad path.")
            }
            if (!SHA256_HEX.matches(sha)) {
                throw ManifestException("Manifest file entry #$i has a bad sha256.")
            }
            if (size < 0) throw ManifestException("Manifest file entry #$i has a bad size.")
            files.add(ManifestFile(path, sha, size))
        }
        return Manifest(
            archiveVersion = version,
            appVersion = root.optString("appVersion", "unknown"),
            schemaVersion = root.optInt("schemaVersion", -1),
            exportedAt = root.optLong("exportedAt", 0),
            yearName = yearName,
            semesterName = semesterName,
            audioIncluded = root.optBoolean("audioIncluded", false),
            excludedAudioCount = root.optInt("excludedAudioCount", 0),
            audioNote = root.optString("audioNote", ""),
            counts = counts,
            files = files
        )
    }
}
