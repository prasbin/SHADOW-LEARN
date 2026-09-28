package com.prasbin.shadowlearn.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.ExtractionDao
import com.prasbin.shadowlearn.data.db.Flashcard
import com.prasbin.shadowlearn.data.db.FlashcardDao
import com.prasbin.shadowlearn.data.db.FlashcardDeck
import com.prasbin.shadowlearn.data.db.ListenerDao
import com.prasbin.shadowlearn.data.db.ListenerSegment
import com.prasbin.shadowlearn.data.db.ListenerSession
import com.prasbin.shadowlearn.data.db.QuizDao
import com.prasbin.shadowlearn.data.db.QuizQuestion
import com.prasbin.shadowlearn.data.db.QuizSession
import com.prasbin.shadowlearn.data.db.ReviewEvent
import com.prasbin.shadowlearn.data.db.ReviewSession
import com.prasbin.shadowlearn.data.db.ShadowLearnDatabase
import com.prasbin.shadowlearn.data.ingest.IngestRepository
import com.prasbin.shadowlearn.data.ingest.IngestState
import com.prasbin.shadowlearn.data.ingest.ZipWalk
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Honest outcome of one semester export. */
data class ExportSummary(
    val yearName: String,
    val semesterName: String,
    val archiveName: String,
    val filesExported: Int,
    val historyRecords: Int,
    val audioIncluded: Boolean,
    val excludedAudioCount: Int,
    val failures: List<String>
)

/** Honest outcome of one archive import. `ok=false` means validation failed. */
data class ImportSummary(
    val ok: Boolean,
    val failureReason: String?,
    val yearName: String,
    val semesterName: String,
    val targetSemesterId: Long,
    val created: Int,
    val unchanged: Int,
    val changed: Int,
    val duplicate: Int,
    val failed: Int,
    val skipped: Int,
    /** Restored-history counts by kind (quizSessions, quizQuestions, decks, …). */
    val history: Map<String, Int>,
    val audioIncluded: Boolean,
    val excludedAudioCount: Int,
    val errors: List<String>
)

sealed interface BackupState {
    data object Idle : BackupState
    data class Working(val operation: String, val currentEntry: String, val processed: Int, val total: Int) :
        BackupState
    data class ExportDone(val summary: ExportSummary) : BackupState
    data class ImportDone(val summary: ImportSummary) : BackupState
    data class Failed(val reason: String, val processed: Int) : BackupState
}

/**
 * Phase 13 portable backup — export/import of one semester as a versioned
 * ZIP (see [BackupFormat] for the layout).
 *
 * Design rules:
 * - Academic content reuses the Phase 2/3 pipeline: the `academic/`
 *   subtree is re-zipped verbatim and fed to
 *   [IngestRepository.importStream], so NEW/UNCHANGED/CHANGED/DUPLICATE
 *   semantics, SHA-256 dedup, and never-delete come free. No second
 *   ingestion architecture exists.
 * - History rows are plain snapshots; installation-local ids are NEVER
 *   trusted. Academic hierarchy rematches by name, quiz/review/listener
 *   runs dedupe by (scope, seed/start-time) identity, cards dedupe by
 *   (deck, contentKey), and card/question/chunk references stay plain
 *   columns exactly as the schema documents them.
 * - Listener audio policy B: audio is excluded; metadata + transcripts
 *   travel (`audioIncluded=false`, excluded count reported).
 * - Validation (ZIP → manifest → hashes → history JSON) completes with
 *   ZERO writes; only then does mutation begin. History restore runs in
 *   one Room transaction; the academic phase is independently idempotent,
 *   so a retry converges instead of duplicating.
 */
class BackupRepository(
    private val context: Context,
    private val db: ShadowLearnDatabase,
    private val academicDao: AcademicDao,
    private val extractionDao: ExtractionDao,
    private val quizDao: QuizDao,
    private val flashcardDao: FlashcardDao,
    private val listenerDao: ListenerDao,
    private val ingest: IngestRepository,
    /** Prod wires extraction re-run; tests pass null (chunks verified separately). */
    private val afterAcademicImport: (suspend (Long) -> Unit)? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state

    fun reset() {
        _state.value = BackupState.Idle
    }

    // ---- export ----------------------------------------------------------

    suspend fun exportSemester(semesterId: Long, archiveName: String, out: OutputStream): ExportSummary =
        withContext(dispatcher) {
            _state.value = BackupState.Working("export", "Preparing…", 0, 0)
            val failures = mutableListOf<String>()
            val semester = academicDao.semester(semesterId)
                ?: return@withContext exportFailed(
                    archiveName, "Semester not found.", failures
                )
            val yearName = academicDao.year(semester.yearId)?.name ?: "Year"

            val modules = academicDao.getModules(semesterId)
            val weeksByModule = modules.associate { it.id to academicDao.getWeeks(it.id) }
            val files = extractionDao.filesOfSemester(semesterId).sortedBy { it.relativePath }

            // History reads (id-ordered for determinism).
            val quizSessions = quizDao.sessionsOfSemester(semesterId)
            val quizQuestions = quizSessions.flatMap { quizDao.questions(it.id) }
            val decks = flashcardDao.decksOfSemester(semesterId)
            val cardsByDeck = decks.associate { it.id to flashcardDao.cardsOfDeck(it.id) }
            val reviewSessionsByDeck =
                decks.associate { it.id to flashcardDao.reviewSessionsOfDeck(it.id) }
            val reviewEvents = reviewSessionsByDeck.values.flatten()
                .flatMap { rs -> flashcardDao.reviewEvents(rs.id) }
            val listenerSessions = listenerDao.sessionsOfSemester(semesterId)
            val segmentsBySession =
                listenerSessions.associate { it.id to listenerDao.segments(it.id) }
            val excludedAudio = listenerSessions.count { it.audioPath != null }

            // File bytes: canonical copy, falling back to the content store.
            data class ReadyFile(val row: AcademicFile, val bytes: File, val sha: String, val size: Long)
            val readyFiles = mutableListOf<ReadyFile>()
            for (row in files) {
                val candidate =
                    File(row.filePath).takeIf { it.exists() && it.length() > 0 }
                        ?: academicDao.findContentByHash(row.sha256)?.storedPath?.let { File(it) }
                            ?.takeIf { it.exists() }
                if (candidate == null) {
                    failures.add("Unreadable file, skipped: ${row.relativePath}")
                    continue
                }
                readyFiles.add(ReadyFile(row, candidate, row.sha256, candidate.length()))
            }

            // Empty-structure dirs for modules/weeks with no exported files.
            val filesByWeek = readyFiles.groupBy { it.row.weekId }.mapKeys { it.key }
            val weekToModule = mutableMapOf<Long, String>()
            for (m in modules) for (w in (weeksByModule[m.id] ?: emptyList())) weekToModule[w.id] = m.name
            val modulesWithFiles = filesByWeek.keys.mapNotNullTo(mutableSetOf()) { weekToModule[it] }
            val weeksWithFiles = mutableMapOf<String, MutableSet<Int>>()
            for ((weekId, _) in filesByWeek) {
                val mod = weekToModule[weekId] ?: continue
                val w = (weeksByModule.values.flatten().firstOrNull { it.id == weekId }) ?: continue
                weeksWithFiles.getOrPut(mod) { mutableSetOf() }.add(w.weekNumber)
            }
            val dirEntries = mutableListOf<String>()
            for (m in modules.sortedBy { it.name }) {
                if (m.name !in modulesWithFiles) dirEntries.add("${m.name}/")
                for (w in (weeksByModule[m.id] ?: emptyList()).sortedBy { it.weekNumber }) {
                    if (w.weekNumber !in (weeksWithFiles[m.name] ?: emptySet())) {
                        dirEntries.add("${m.name}/Week ${w.weekNumber}/")
                    }
                }
            }

            val counts = BackupFormat.ManifestCounts(
                modules = modules.size,
                weeks = weeksByModule.values.sumOf { it.size },
                files = readyFiles.size,
                quizSessions = quizSessions.size,
                quizQuestions = quizQuestions.size,
                decks = decks.size,
                cards = cardsByDeck.values.sumOf { it.size },
                reviewSessions = reviewSessionsByDeck.values.sumOf { it.size },
                reviewEvents = reviewEvents.size,
                listenerSessions = listenerSessions.size,
                listenerSegments = segmentsBySession.values.sumOf { it.size }
            )
            val historyRecords = counts.quizSessions + counts.quizQuestions + counts.decks +
                counts.cards + counts.reviewSessions + counts.reviewEvents +
                counts.listenerSessions + counts.listenerSegments

            ZipOutputStream(out.buffered()).use { zos ->
                var done = 0
                val total = readyFiles.size
                for (rf in readyFiles.sortedBy { it.row.relativePath }) {
                    ensureActive()
                    val path = BackupFormat.ACADEMIC_PREFIX + rf.row.relativePath
                    _state.value = BackupState.Working("export", rf.row.fileName, done, total)
                    zos.putNextEntry(java.util.zip.ZipEntry(path))
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    FileInputStream(rf.bytes).use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            zos.write(buf, 0, n)
                            size += n
                        }
                    }
                    zos.closeEntry()
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actual != rf.sha.lowercase() || size != rf.size) {
                        failures.add("Hash drift on export, kept with fresh hash: ${rf.row.relativePath}")
                    }
                    done++
                }
                for (dir in dirEntries.sorted()) {
                    zos.putNextEntry(java.util.zip.ZipEntry(BackupFormat.ACADEMIC_PREFIX + dir))
                    zos.closeEntry()
                }
                writeHistoryEntry(zos, BackupFormat.QUIZ_SESSIONS_FILE, encodeQuizSessions(quizSessions))
                writeHistoryEntry(zos, BackupFormat.QUIZ_QUESTIONS_FILE, encodeQuizQuestions(quizSessions, quizQuestions))
                writeHistoryEntry(zos, BackupFormat.FLASHCARD_DECKS_FILE, encodeDecks(decks))
                writeHistoryEntry(zos, BackupFormat.FLASHCARDS_FILE, encodeCards(decks, cardsByDeck))
                writeHistoryEntry(
                    zos, BackupFormat.REVIEW_SESSIONS_FILE,
                    encodeReviewSessions(decks, reviewSessionsByDeck)
                )
                writeHistoryEntry(
                    zos, BackupFormat.REVIEW_EVENTS_FILE,
                    encodeReviewEvents(decks, reviewSessionsByDeck, cardsByDeck, reviewEvents)
                )
                writeHistoryEntry(zos, BackupFormat.LISTENER_SESSIONS_FILE, encodeListenerSessions(listenerSessions))
                writeHistoryEntry(
                    zos, BackupFormat.LISTENER_SEGMENTS_FILE,
                    encodeListenerSegments(listenerSessions, segmentsBySession)
                )
                val manifestFiles = readyFiles.map {
                    BackupFormat.ManifestFile(
                        BackupFormat.ACADEMIC_PREFIX + it.row.relativePath, it.sha, it.size
                    )
                }
                val manifest = BackupFormat.buildManifest(
                    appVersion = appVersion(),
                    schemaVersion = ShadowLearnDatabase.DATABASE_VERSION,
                    exportedAt = System.currentTimeMillis(),
                    yearName = yearName,
                    semesterName = semester.name,
                    excludedAudioCount = excludedAudio,
                    counts = counts,
                    files = manifestFiles
                )
                zos.putNextEntry(java.util.zip.ZipEntry(BackupFormat.MANIFEST_PATH))
                zos.write(manifest.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            val summary = ExportSummary(
                yearName = yearName,
                semesterName = semester.name,
                archiveName = archiveName,
                filesExported = readyFiles.size,
                historyRecords = historyRecords,
                audioIncluded = false,
                excludedAudioCount = excludedAudio,
                failures = failures.toList()
            )
            _state.value = BackupState.ExportDone(summary)
            summary
        }

    private fun exportFailed(archiveName: String, reason: String, failures: List<String>): ExportSummary {
        val summary = ExportSummary("", "", archiveName, 0, 0, false, 0, failures + reason)
        _state.value = BackupState.Failed(reason, 0)
        return summary
    }

    // ---- import ----------------------------------------------------------

    suspend fun importArchive(input: InputStream, label: String): ImportSummary =
        withContext(dispatcher) {
            val staging = File(context.cacheDir, "shadowlearn-backup/import-${System.currentTimeMillis()}")
            try {
                _state.value = BackupState.Working("import", "Validating…", 0, 0)
                val walk = ZipWalk.walk(input, label, staging)
                if (walk.entries.isEmpty() && walk.dirPaths.isEmpty()) {
                    val reason = walk.errors.firstOrNull() ?: "Archive is empty."
                    return@withContext importFailed("", "", -1, reason, 0)
                }
                val byPath = walk.entries.associateBy { it.logicalPath }
                val manifestFile = byPath[BackupFormat.MANIFEST_PATH]
                    ?: return@withContext importFailed("", "", -1, "Not a SHADOW LEARN archive (manifest missing).", 0)
                if (manifestFile.stagedFile.length() > 4 * 1024 * 1024) {
                    return@withContext importFailed("", "", -1, "Manifest too large.", 0)
                }
                val manifest = try {
                    BackupFormat.parseManifest(manifestFile.stagedFile.readText(Charsets.UTF_8))
                } catch (e: BackupFormat.ManifestException) {
                    return@withContext importFailed("", "", -1, e.message ?: "Invalid manifest.", 0)
                }
                // Hash validation: every listed file present with matching bytes.
                for (mf in manifest.files) {
                    val staged = byPath[mf.path]
                        ?: return@withContext importFailed(
                            manifest.yearName, manifest.semesterName, -1,
                            "Archive file missing: ${mf.path}.", 0
                        )
                    val actual = hashOf(staged.stagedFile)
                    if (actual != mf.sha256 || staged.stagedFile.length() != mf.size) {
                        return@withContext importFailed(
                            manifest.yearName, manifest.semesterName, -1,
                            "Hash mismatch: ${mf.path}.", 0
                        )
                    }
                }
                // History files: required when their count > 0, parsed now (still zero writes).
                val history = readHistory(byPath, manifest)
                    ?: return@withContext importFailed(
                        manifest.yearName, manifest.semesterName, -1,
                        "History section is corrupt.", 0
                    )

                // ---- apply phase (validation already passed) ----
                val yearId = run {
                    val existing = academicDao.getYears().firstOrNull { it.name == manifest.yearName }
                    existing?.id ?: academicDao.insertYear(
                        com.prasbin.shadowlearn.data.db.AcademicYear(
                            name = manifest.yearName,
                            sortOrder = (academicDao.getYears().maxOfOrNull { it.sortOrder } ?: -1) + 1
                        )
                    )
                }
                val semesterId = run {
                    val existing =
                        academicDao.getSemesters(yearId).firstOrNull { it.name == manifest.semesterName }
                    existing?.id ?: academicDao.insertSemester(
                        com.prasbin.shadowlearn.data.db.Semester(
                            yearId = yearId, name = manifest.semesterName,
                            sortOrder = academicDao.getSemesters(yearId).size
                        )
                    )
                }

                var created = 0
                var unchanged = 0
                var changed = 0
                var duplicate = 0
                var failed = 0
                var skipped = 0
                val errors = walk.errors.toMutableList()
                val academicEntries = walk.entries.filter { it.logicalPath.startsWith(BackupFormat.ACADEMIC_PREFIX) }
                val academicDirs = walk.dirPaths.filter { it.startsWith(BackupFormat.ACADEMIC_PREFIX) }
                if (academicEntries.isNotEmpty() || academicDirs.isNotEmpty()) {
                    val academicZip = File(staging, "academic.zip")
                    ZipOutputStream(FileOutputStream(academicZip)).use { zos ->
                        for (dir in academicDirs.sorted()) {
                            val name = dir.removePrefix(BackupFormat.ACADEMIC_PREFIX)
                            if (name.isNotEmpty()) {
                                zos.putNextEntry(java.util.zip.ZipEntry("$name/"))
                                zos.closeEntry()
                            }
                        }
                        var n = 0
                        for (e in academicEntries.sortedBy { it.logicalPath }) {
                            n++
                            _state.value = BackupState.Working("import", e.logicalPath, n, academicEntries.size)
                            val name = e.logicalPath.removePrefix(BackupFormat.ACADEMIC_PREFIX)
                            zos.putNextEntry(java.util.zip.ZipEntry(name))
                            FileInputStream(e.stagedFile).use { ins -> ins.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    FileInputStream(academicZip).use { ins ->
                        ingest.importStream(ins, semesterId, label)
                    }
                    when (val s = ingest.state.value) {
                        is IngestState.Done -> {
                            created = s.summary.created
                            unchanged = s.summary.unchanged
                            changed = s.summary.changed
                            duplicate = s.summary.duplicate
                            failed = s.summary.failed
                            skipped = s.summary.skipped
                            errors.addAll(s.summary.errors)
                        }
                        is IngestState.Failed -> {
                            failed++
                            errors.add("Academic import failed: ${s.reason}")
                        }
                        else -> {
                            failed++
                            errors.add("Academic import ended unexpectedly.")
                        }
                    }
                    afterAcademicImport?.invoke(semesterId)
                }

                val hist = db.withTransaction {
                    restoreHistory(semesterId, history)
                }
                val summary = ImportSummary(
                    ok = true,
                    failureReason = null,
                    yearName = manifest.yearName,
                    semesterName = manifest.semesterName,
                    targetSemesterId = semesterId,
                    created = created,
                    unchanged = unchanged,
                    changed = changed,
                    duplicate = duplicate,
                    failed = failed + hist.failedRecords,
                    skipped = skipped,
                    history = hist.counts,
                    audioIncluded = false,
                    excludedAudioCount = manifest.excludedAudioCount,
                    errors = (errors + hist.errors).toList()
                )
                _state.value = BackupState.ImportDone(summary)
                summary
            } finally {
                staging.deleteRecursively()
            }
        }

    private fun importFailed(
        yearName: String,
        semesterName: String,
        targetSemesterId: Long,
        reason: String,
        processed: Int
    ): ImportSummary {
        val summary = ImportSummary(
            ok = false,
            failureReason = "Import failed: $reason",
            yearName = yearName,
            semesterName = semesterName,
            targetSemesterId = targetSemesterId,
            created = 0, unchanged = 0, changed = 0, duplicate = 0,
            failed = 0, skipped = 0,
            history = emptyMap(),
            audioIncluded = false,
            excludedAudioCount = 0,
            errors = listOf("Import failed: $reason", "Processed before failure: $processed")
        )
        _state.value = BackupState.Failed("Import failed: $reason", processed)
        return summary
    }

    // ---- history export codecs ---------------------------------------------

    private fun encodeQuizSessions(sessions: List<QuizSession>): JSONArray {
        val arr = JSONArray()
        for (s in sessions) {
            arr.put(
                JSONObject()
                    .put("seed", s.seed)
                    .put("totalQuestions", s.totalQuestions)
                    .put("correctCount", s.correctCount)
                    .put("xpEarned", s.xpEarned)
                    .put("streak", s.streak)
                    .put("status", s.status)
                    .put("startedAt", s.startedAt)
                    .putOptNN("completedAt", s.completedAt)
            )
        }
        return arr
    }

    private fun encodeQuizQuestions(
        sessions: List<QuizSession>,
        questions: List<QuizQuestion>
    ): JSONArray {
        val sessionById = sessions.associateBy { it.id }
        val arr = JSONArray()
        for (q in questions) {
            val s = sessionById[q.sessionId] ?: continue
            arr.put(
                JSONObject()
                    .put("sessionSeed", s.seed)
                    .put("sessionStartedAt", s.startedAt)
                    .put("position", q.position)
                    .put("chunkId", q.chunkId)
                    .put("academicFileId", q.academicFileId)
                    .put("questionType", q.questionType)
                    .put("prompt", q.prompt)
                    .putOptNN("optionsJson", q.optionsJson)
                    .put("correctAnswer", q.correctAnswer)
                    .putOptNN("userAnswer", q.userAnswer)
                    .putOptNN("isCorrect", q.isCorrect)
                    .put("srcFileName", q.srcFileName)
                    .put("srcFileType", q.srcFileType)
                    .putOptNN("srcPage", q.srcPage)
                    .put("srcExcerpt", q.srcExcerpt)
            )
        }
        return arr
    }

    private fun encodeDecks(decks: List<FlashcardDeck>): JSONArray {
        val arr = JSONArray()
        for (d in decks) {
            arr.put(JSONObject().put("title", d.title).put("createdAt", d.createdAt))
        }
        return arr
    }

    private fun encodeCards(
        decks: List<FlashcardDeck>,
        cardsByDeck: Map<Long, List<Flashcard>>
    ): JSONArray {
        val deckById = decks.associateBy { it.id }
        val arr = JSONArray()
        for ((deckId, cards) in cardsByDeck) {
            val title = deckById[deckId]?.title ?: continue
            for (c in cards) {
                arr.put(
                    JSONObject()
                        .put("deckTitle", title)
                        .put("front", c.front)
                        .put("back", c.back)
                        .putOptNN("sourceChunkId", c.sourceChunkId)
                        .putOptNN("sourceQuestionId", c.sourceQuestionId)
                        .putOptNN("sourceListenerSegmentId", c.sourceListenerSegmentId)
                        .put("sourceLabel", c.sourceLabel)
                        .put("contentKey", c.contentKey)
                        .put("easeFactor", c.easeFactor)
                        .put("intervalDays", c.intervalDays)
                        .put("dueAt", c.dueAt)
                        .put("suspended", c.suspended)
                        .put("createdAt", c.createdAt)
                        .put("updatedAt", c.updatedAt)
                )
            }
        }
        return arr
    }

    private fun encodeReviewSessions(
        decks: List<FlashcardDeck>,
        sessionsByDeck: Map<Long, List<ReviewSession>>
    ): JSONArray {
        val deckById = decks.associateBy { it.id }
        val arr = JSONArray()
        for ((deckId, sessions) in sessionsByDeck) {
            val title = deckById[deckId]?.title ?: continue
            for (s in sessions) {
                arr.put(
                    JSONObject()
                        .put("deckTitle", title)
                        .put("startedAt", s.startedAt)
                        .putOptNN("completedAt", s.completedAt)
                        .put("reviewedCount", s.reviewedCount)
                        .put("retainedCount", s.retainedCount)
                        .put("status", s.status)
                )
            }
        }
        return arr
    }

    private fun encodeReviewEvents(
        decks: List<FlashcardDeck>,
        sessionsByDeck: Map<Long, List<ReviewSession>>,
        cardsByDeck: Map<Long, List<Flashcard>>,
        events: List<ReviewEvent>
    ): JSONArray {
        val deckById = decks.associateBy { it.id }
        val cardKeyById = cardsByDeck.values.flatten().associate { it.id to it.contentKey }
        val sessionKey = sessionsByDeck.flatMap { (deckId, sessions) ->
            sessions.map { it.id to Pair(deckById[deckId]?.title, it.startedAt) }
        }.toMap()
        val arr = JSONArray()
        for (e in events) {
            val (deckTitle, sessionStartedAt) = sessionKey[e.sessionId] ?: continue
            if (deckTitle == null) continue
            arr.put(
                JSONObject()
                    .put("deckTitle", deckTitle)
                    .put("sessionStartedAt", sessionStartedAt)
                    .putOptNN("flashcardContentKey", cardKeyById[e.flashcardId])
                    .put("flashcardId", e.flashcardId)
                    .put("rating", e.rating)
                    .put("reviewedAt", e.reviewedAt)
                    .put("previousEaseFactor", e.previousEaseFactor)
                    .put("newEaseFactor", e.newEaseFactor)
                    .put("previousIntervalDays", e.previousIntervalDays)
                    .put("newIntervalDays", e.newIntervalDays)
                    .put("retained", e.retained)
            )
        }
        return arr
    }

    private fun encodeListenerSessions(sessions: List<ListenerSession>): JSONArray {
        val arr = JSONArray()
        for (s in sessions) {
            arr.put(
                JSONObject()
                    .put("startedAt", s.startedAt)
                    .putOptNN("completedAt", s.completedAt)
                    .put("status", s.status)
                    .put("createdAt", s.createdAt)
                    .put("hadAudio", s.audioPath != null)
            )
        }
        return arr
    }

    private fun encodeListenerSegments(
        sessions: List<ListenerSession>,
        segmentsBySession: Map<Long, List<ListenerSegment>>
    ): JSONArray {
        val startedById = sessions.associate { it.id to it.startedAt }
        val arr = JSONArray()
        for ((sessionId, segments) in segmentsBySession) {
            val sessionStartedAt = startedById[sessionId] ?: continue
            for (g in segments) {
                arr.put(
                    JSONObject()
                        .put("sessionStartedAt", sessionStartedAt)
                        .put("position", g.position)
                        .put("startedAtMs", g.startedAtMs)
                        .put("durationMs", g.durationMs)
                        .put("transcript", g.transcript)
                        .put("transcriptStatus", g.transcriptStatus)
                )
            }
        }
        return arr
    }

    private fun JSONObject.putOptNN(key: String, value: Any?): JSONObject {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
        return this
    }

    private fun writeHistoryEntry(zos: ZipOutputStream, name: String, arr: JSONArray) {
        zos.putNextEntry(java.util.zip.ZipEntry(BackupFormat.HISTORY_PREFIX + name))
        zos.write(arr.toString(2).toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun appVersion(): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        pi.versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    private fun hashOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- history import ------------------------------------------------------

    private class HistoryBundle(
        val quizSessions: JSONArray,
        val quizQuestions: JSONArray,
        val decks: JSONArray,
        val cards: JSONArray,
        val reviewSessions: JSONArray,
        val reviewEvents: JSONArray,
        val listenerSessions: JSONArray,
        val listenerSegments: JSONArray
    )

    private class HistoryOutcome(
        var failedRecords: Int = 0,
        val counts: MutableMap<String, Int> = mutableMapOf(),
        val errors: MutableList<String> = mutableListOf()
    ) {
        fun add(key: String, n: Int = 1) {
            counts[key] = (counts[key] ?: 0) + n
        }
    }

    private class RecordException(message: String) : Exception(message)

    private fun readHistory(
        byPath: Map<String, ZipWalk.WalkedFile>,
        manifest: BackupFormat.Manifest
    ): HistoryBundle? {
        fun required(name: String, count: Int): JSONArray? {
            val path = BackupFormat.HISTORY_PREFIX + name
            val staged = byPath[path]
            if (staged == null) {
                if (count > 0) return null
                return JSONArray()
            }
            return try {
                JSONArray(staged.stagedFile.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }
        val c = manifest.counts
        val qs = required(BackupFormat.QUIZ_SESSIONS_FILE, c.quizSessions) ?: return null
        val qq = required(BackupFormat.QUIZ_QUESTIONS_FILE, c.quizQuestions) ?: return null
        val dk = required(BackupFormat.FLASHCARD_DECKS_FILE, c.decks) ?: return null
        val cd = required(BackupFormat.FLASHCARDS_FILE, c.cards) ?: return null
        val rs = required(BackupFormat.REVIEW_SESSIONS_FILE, c.reviewSessions) ?: return null
        val re = required(BackupFormat.REVIEW_EVENTS_FILE, c.reviewEvents) ?: return null
        val ls = required(BackupFormat.LISTENER_SESSIONS_FILE, c.listenerSessions) ?: return null
        val lg = required(BackupFormat.LISTENER_SEGMENTS_FILE, c.listenerSegments) ?: return null
        return HistoryBundle(qs, qq, dk, cd, rs, re, ls, lg)
    }

    private fun JSONObject.reqString(key: String): String {
        if (!has(key) || isNull(key)) throw RecordException("missing '$key'")
        return getString(key)
    }

    private fun JSONObject.reqLong(key: String): Long {
        if (!has(key) || isNull(key)) throw RecordException("missing '$key'")
        return getLong(key)
    }

    private fun JSONObject.reqInt(key: String): Int {
        if (!has(key) || isNull(key)) throw RecordException("missing '$key'")
        return getInt(key)
    }

    private fun JSONObject.reqBoolean(key: String): Boolean {
        if (!has(key) || isNull(key)) throw RecordException("missing '$key'")
        return getBoolean(key)
    }

    private fun JSONObject.reqDouble(key: String): Double {
        if (!has(key) || isNull(key)) throw RecordException("missing '$key'")
        return getDouble(key)
    }

    private fun JSONObject.optLongNN(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private fun JSONObject.optStringNN(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.optBooleanNN(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else getBoolean(key)

    private suspend fun restoreHistory(semesterId: Long, h: HistoryBundle): HistoryOutcome {
        val out = HistoryOutcome()
        // Quiz sessions (dedup by scope+seed+start).
        val newQuizParents = mutableSetOf<Pair<Long, Long>>()
        for (i in 0 until h.quizSessions.length()) {
            try {
                val o = h.quizSessions.getJSONObject(i)
                val seed = o.reqLong("seed")
                val startedAt = o.reqLong("startedAt")
                val existing = quizDao.findQuizSession(semesterId, seed, startedAt)
                if (existing != null) {
                    out.add("quizSessionsDuplicate")
                    continue
                }
                quizDao.insertSession(
                    QuizSession(
                        semesterId = semesterId,
                        seed = seed,
                        totalQuestions = o.reqInt("totalQuestions"),
                        correctCount = o.optInt("correctCount", 0),
                        xpEarned = o.optInt("xpEarned", 0),
                        streak = o.optInt("streak", 0),
                        status = o.reqString("status"),
                        startedAt = startedAt,
                        completedAt = o.optLongNN("completedAt")
                    )
                )
                newQuizParents.add(seed to startedAt)
                out.add("quizSessions")
            } catch (e: Exception) {
                out.failedRecords++
                out.errors.add("Skipped quiz session #$i: ${recordMessage(e)}")
            }
        }
        // Quiz questions: link by (seed, startedAt) of the parent session.
        // Questions of an already-present session are duplicates by
        // association — inserting them again would fork history.
        for (i in 0 until h.quizQuestions.length()) {
            try {
                val o = h.quizQuestions.getJSONObject(i)
                val parentKey = o.reqLong("sessionSeed") to o.reqLong("sessionStartedAt")
                if (parentKey !in newQuizParents) {
                    // Parent pre-existed: verify it is really there, then skip.
                    quizDao.findQuizSession(
                        semesterId, parentKey.first, parentKey.second
                    ) ?: throw RecordException("parent quiz session not found")
                    out.add("quizQuestionsDuplicate")
                    continue
                }
                val parent = quizDao.findQuizSession(
                    semesterId, parentKey.first, parentKey.second
                ) ?: throw RecordException("parent quiz session not found")
                quizDao.insertQuestions(
                    listOf(
                        QuizQuestion(
                            sessionId = parent.id,
                            position = o.reqInt("position"),
                            chunkId = o.reqLong("chunkId"),
                            academicFileId = o.reqLong("academicFileId"),
                            questionType = o.reqString("questionType"),
                            prompt = o.reqString("prompt"),
                            optionsJson = o.optStringNN("optionsJson"),
                            correctAnswer = o.reqString("correctAnswer"),
                            userAnswer = o.optStringNN("userAnswer"),
                            isCorrect = o.optBooleanNN("isCorrect"),
                            srcFileName = o.reqString("srcFileName"),
                            srcFileType = o.reqString("srcFileType"),
                            srcPage = o.optLongNN("srcPage"),
                            srcExcerpt = o.reqString("srcExcerpt")
                        )
                    )
                )
                out.add("quizQuestions")
            } catch (e: Exception) {
                out.failedRecords++
                out.errors.add("Skipped quiz question #$i: ${recordMessage(e)}")
            }
        }
        // Decks (dedup by scope+title).
        val deckMap = mutableMapOf<String, Long>()
        for (i in 0 until h.decks.length()) {
            try {
                val o = h.decks.getJSONObject(i)
                val title = o.reqString("title")
                val existing = flashcardDao.decksOfSemester(semesterId).firstOrNull { it.title == title }
                val deckId = existing?.id ?: flashcardDao.insertDeck(
                    FlashcardDeck(semesterId = semesterId, title = title, createdAt = o.optLong("createdAt", 0))
                )
                if (existing != null) out.add("decksDuplicate") else out.add("decks")
                deckMap[title] = deckId
            } catch (e: Exception) {
                out.failedRecords++
                out.errors.add("Skipped deck #$i: ${recordMessage(e)}")
            }
        }
        // Cards (IGNORE on (deckId, contentKey); -1 means duplicate).
        for (i in 0 until h.cards.length()) {
            try {
                val o = h.cards.getJSONObject(i)
                val deckId = deckMap[o.reqString("deckTitle")]
                    ?: throw RecordException("parent deck not found")
                val ids = flashcardDao.insertCards(
                    listOf(
                        Flashcard(
                            deckId = deckId,
                            front = o.reqString("front"),
                            back = o.reqString("back"),
                            sourceChunkId = o.optLongNN("sourceChunkId"),
                            sourceQuestionId = o.optLongNN("sourceQuestionId"),
                            sourceListenerSegmentId = o.optLongNN("sourceListenerSegmentId"),
                            sourceLabel = o.reqString("sourceLabel"),
                            contentKey = o.reqString("contentKey"),
                            easeFactor = o.optDouble("easeFactor", 2.5),
                            intervalDays = o.optInt("intervalDays", 0),
                            dueAt = o.optLong("dueAt", 0),
                            suspended = o.optBoolean("suspended", false),
                            createdAt = o.optLong("createdAt", 0),
                            updatedAt = o.optLong("updatedAt", 0)
                        )
                    )
                )
                if (ids.firstOrNull() == -1L) out.add("cardsDuplicate") else out.add("cardsCreated")
            } catch (e: Exception) {
                out.failedRecords++
                out.errors.add("Skipped card #$i: ${recordMessage(e)}")
            }
        }
        // Review sessions (dedup by deck+start; skip events of duplicates).
        val reviewSessionMap = mutableMapOf<Pair<String, Long>, Long>()
        val skippedEventParents = mutableSetOf<Pair<String, Long>>()
        for (i in 0 until h.reviewSessions.length()) {
            try {
                val o = h.reviewSessions.getJSONObject(i)
                val deckTitle = o.reqString("deckTitle")
                val deckId = deckMap[deckTitle] ?: throw RecordException("parent deck not found")
                val startedAt = o.reqLong("startedAt")
                val existing = flashcardDao.findReviewSession(deckId, startedAt)
                if (existing != null) {
                    reviewSessionMap[deckTitle to startedAt] = existing.id
                    skippedEventParents.add(deckTitle to startedAt)
                    out.add("reviewSessionsDuplicate")
                    continue
                }
                val id = flashcardDao.insertReviewSession(
                    ReviewSession(
                        deckId = deckId,
                        startedAt = startedAt,
                        completedAt = o.optLongNN("completedAt"),
                        reviewedCount = o.optInt("reviewedCount", 0),
                        retainedCount = o.optInt("retainedCount", 0),
                        status = o.reqString("status")
                    )
                )
                reviewSessionMap[deckTitle to startedAt] = id
                out.add("reviewSessions")
            } catch (e: Exception) {
                out.failedRecords++
                out.errors.add("Skipped review session #$i: ${recordMessage(e)}")
            }
        }
        // Review events (attach by (deck, sessionStart); remap card by contentKey).
        for (i in 0 until h.reviewEvents.length()) {
            try {
                val o = h.reviewEvents.getJSONObject(i)
                val key = o.reqString("deckTitle") to o.reqLong("sessionStartedAt")
                if (key in skippedEventParents) {
                    out.add("reviewEventsDuplicate")
                    continue
                }
                val sessionId = reviewSessionMap[key]
                    ?: throw RecordException("parent review session not found")
                val deckId = deckMap[o.reqString("deckTitle")]
                    ?: throw RecordException("parent deck not found")
                val contentKey = o.optStringNN("flashcardContentKey")
                val cardId = contentKey?.let { flashcardDao.findCardByContentKey(deckId, it)?.id }
                    ?: o.reqLong("flashcardId")
                flashcardDao.insertEvent(
                    ReviewEvent(
                        sessionId = sessionId,
                        flashcardId = cardId,
                        rating = o.reqString("rating"),
                        reviewedAt = o.reqLong("reviewedAt"),
                        previousEaseFactor = o.reqDouble("previousEaseFactor"),
                        newEaseFactor = o.reqDouble("newEaseFactor"),
                        previousIntervalDays = o.reqInt("previousIntervalDays"),
                        newIntervalDays = o.reqInt("newIntervalDays"),
                        retained = o.reqBoolean("retained")
                    )
                )
                out.add("reviewEvents")
            } catch (e: Exception) {
                out.failedRecords++
                out.errors.add("Skipped review event #$i: ${recordMessage(e)}")
            }
        }
        // Listener sessions (dedup by scope+start; audio never restored).
        val newListenerParents = mutableSetOf<Long>()
        for (i in 0 until h.listenerSessions.length()) {
            try {
                val o = h.listenerSessions.getJSONObject(i)
                val startedAt = o.reqLong("startedAt")
                val existing = listenerDao.findListenerSession(semesterId, startedAt)
                if (existing != null) {
                    out.add("listenerSessionsDuplicate")
                    continue
                }
                listenerDao.insertSession(
                    ListenerSession(
                        semesterId = semesterId,
                        status = o.reqString("status"),
                        startedAt = startedAt,
                        completedAt = o.optLongNN("completedAt"),
                        audioPath = null,
                        createdAt = o.optLong("createdAt", 0)
                    )
                )
                newListenerParents.add(startedAt)
                out.add("listenerSessions")
            } catch (e: Exception) {
                out.failedRecords++
                out.errors.add("Skipped listener session #$i: ${recordMessage(e)}")
            }
        }
        // Listener segments (attach by parent start time; verbatim, incl.
        // status). Segments of an already-present session are duplicates by
        // association — inserting them again would fork history.
        for (i in 0 until h.listenerSegments.length()) {
            try {
                val o = h.listenerSegments.getJSONObject(i)
                val parentStart = o.reqLong("sessionStartedAt")
                if (parentStart !in newListenerParents) {
                    listenerDao.findListenerSession(semesterId, parentStart)
                        ?: throw RecordException("parent listener session not found")
                    out.add("listenerSegmentsDuplicate")
                    continue
                }
                val parentId = listenerDao.findListenerSession(semesterId, parentStart)?.id
                    ?: throw RecordException("parent listener session not found")
                val status = o.reqString("transcriptStatus")
                if (status != ListenerSegment.STATUS_PENDING &&
                    status != ListenerSegment.STATUS_READY &&
                    status != ListenerSegment.STATUS_FAILED
                ) {
                    throw RecordException("unknown transcriptStatus '$status'")
                }
                listenerDao.insertSegment(
                    ListenerSegment(
                        sessionId = parentId,
                        position = o.reqInt("position"),
                        startedAtMs = o.reqLong("startedAtMs"),
                        durationMs = o.reqLong("durationMs"),
                        transcript = o.reqString("transcript"),
                        transcriptStatus = status
                    )
                )
                out.add("listenerSegments")
            } catch (e: Exception) {
                out.failedRecords++
                out.errors.add("Skipped listener segment #$i: ${recordMessage(e)}")
            }
        }
        return out
    }

    private fun recordMessage(e: Exception): String =
        if (e is RecordException) e.message ?: "bad record" else (e.message ?: e.javaClass.simpleName)
}
