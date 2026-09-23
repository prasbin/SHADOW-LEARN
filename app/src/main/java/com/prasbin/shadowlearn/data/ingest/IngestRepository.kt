package com.prasbin.shadowlearn.data.ingest

import android.content.Context
import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.Module
import com.prasbin.shadowlearn.data.db.SourceFile
import com.prasbin.shadowlearn.data.db.Week
import java.io.File
import java.io.FileInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/** Outcome of one import run. `modules`/`weeks` count structure rows touched
 * (created or already present); the five classifications below are REAL
 * per-file resolutions (see [Reconcile]). */
data class IngestSummary(
    val modules: Int,
    val weeks: Int,
    val created: Int,
    val unchanged: Int,
    val changed: Int,
    val duplicate: Int,
    val failed: Int,
    val skipped: Int,
    val errors: List<String>
)

sealed interface IngestState {
    data object Idle : IngestState
    data class Importing(
        val currentEntry: String,
        val processed: Int,
        val total: Int,
        val bytesWritten: Long
    ) : IngestState
    data class Done(val summary: IngestSummary) : IngestState
    data class Failed(val reason: String, val processed: Int) : IngestState
}

/**
 * Phase 3 import orchestrator.
 *
 * Flow: SAF stream → temp staging ([ZipWalk]) → [HierarchyPlan] → reconcile
 * against the existing corpus → content-addressed physical copies + Room
 * rows. See docs/ARCHITECTURE.md for the reconciliation rules.
 *
 * Physical storage (Phase 3): one copy per distinct SHA-256 under
 * `<filesDir>/source/<sha256>` (the app-global content store), owned by a
 * `source_files` row. Every academic reference points at that copy via
 * `sourceFileId`; identical content at several positions/re-imports reuses
 * the same copy. Refcounts are recomputed and zero-reference copies removed
 * at the end of every import.
 *
 * Execution decision (documented): a ViewModel-scoped IO coroutine, NOT
 * WorkManager — imports are user-initiated foreground work needing live
 * progress and cancellation. WorkManager suits deferrable background work
 * and stays available for Phase 4+ indexing.
 */
class IngestRepository(
    private val context: Context,
    private val dao: AcademicDao
) {
    private val _state = MutableStateFlow<IngestState>(IngestState.Idle)
    val state: StateFlow<IngestState> = _state

    private var job: Job? = null

    fun cancel() {
        job?.cancel()
    }

    fun reset() {
        _state.value = IngestState.Idle
    }

    /**
     * Imports [zipUri] into [semesterId].
     *
     * @param archiveLabel display name of the archive (for module fallback
     * and messages).
     */
    suspend fun import(zipUri: android.net.Uri, semesterId: Long, archiveLabel: String) {
        val stream = context.contentResolver.openInputStream(zipUri)
        if (stream == null) {
            _state.value = IngestState.Failed("Cannot open selected file.", 0)
            return
        }
        importStream(stream, semesterId, archiveLabel)
    }

    /** Stream-based import core (also used by tests without a ContentResolver). */
    suspend fun importStream(stream: java.io.InputStream, semesterId: Long, archiveLabel: String) {
        job?.cancel()
        _state.value = IngestState.Importing("Preparing…", 0, 0, 0)
        val importId = "import-${System.currentTimeMillis()}"
        val staging = File(context.cacheDir, "shadowlearn-ingest/$importId").apply { mkdirs() }
        try {
            supervisorScope {
                job = launch(Dispatchers.IO) {
                    runImport(stream, semesterId, archiveLabel, importId, staging)
                }
                job!!.join()
            }
        } catch (e: CancellationException) {
            _state.value = IngestState.Failed("Cancelled by user.", 0)
        } finally {
            // Leave the DB/reference state consistent even when we stopped early.
            removeOrphanedContent()
            staging.deleteRecursively()
        }
    }

    /**
     * Recomputes source_files refcounts and deletes physical copies that no
     * academic reference points at. Idempotent; safe to call after direct
     * row removal (covered by tests).
     */
    suspend fun removeOrphanedContent() {
        dao.refreshRefCounts()
        dao.zeroRefContents().forEach { runCatching { File(it.storedPath).delete() } }
        dao.deleteZeroRefContents()
    }

    private suspend fun runImport(
        stream: java.io.InputStream,
        semesterId: Long,
        archiveLabel: String,
        importId: String,
        staging: File
    ) = withContext(Dispatchers.IO) {
        val walk = ZipWalk.walk(stream, archiveLabel, staging)
        if (walk.entries.isEmpty() && walk.dirPaths.isEmpty()) {
            val reason = walk.errors.firstOrNull() ?: "Archive is empty."
            _state.value = IngestState.Failed(reason, 0)
            return@withContext
        }
        val fallbackModule = IngestFormat.stemOf(archiveLabel).ifEmpty { "Imported" }
        val plan = buildPlan(ZipWalk.toPlanInput(walk), fallbackModule)

        val moduleIds = mutableMapOf<String, Long>()
        val weekIds = mutableMapOf<Pair<String, Int>, Long>()
        val errors = walk.errors.toMutableList()
        val totalSteps = plan.files.size

        var created = 0
        var unchanged = 0
        var changed = 0
        var duplicate = 0
        var failed = 0
        var skipped = 0
        var bytes = 0L

        // Structure rows first so empty folders stay visible.
        for (m in plan.modules) {
            moduleIds[m.name] = findOrCreateModule(semesterId, m.name)
        }
        for (w in plan.weeks) {
            val mid = moduleIds[w.moduleName] ?: findOrCreateModule(semesterId, w.moduleName)
            moduleIds[w.moduleName] = mid
            weekIds[w.moduleName to w.weekNumber] =
                dao.findWeek(mid, w.weekNumber)?.id
                    ?: dao.insertWeek(Week(moduleId = mid, weekNumber = w.weekNumber, title = w.weekTitle))
        }

        val sourceDir = File(context.filesDir, "source").apply { mkdirs() }

        for ((index, pf) in plan.files.withIndex()) {
            ensureActive()
            val fileName = pf.entry.logicalPath.substringAfterLast('/')
            val ext = IngestFormat.extensionOf(fileName)
            if (!IngestFormat.isSupported(fileName)) {
                skipped++
                errors.add("Unsupported file type, skipped: ${pf.entry.logicalPath}")
                continue
            }
            val staged = walk.entries.firstOrNull { it.logicalPath == pf.entry.logicalPath }
            if (staged == null) {
                skipped++
                errors.add("Missing staged bytes, skipped: ${pf.entry.logicalPath}")
                continue
            }
            _state.value = IngestState.Importing(fileName, index, totalSteps, bytes)
            try {
                val (sha, size) = hashOf(staged.stagedFile)
                bytes += size
                val relativePath = pf.entry.logicalPath
                val atPosition = dao.findFileByPosition(semesterId, relativePath)
                val sameContent = dao.findFileByHashInSemester(semesterId, sha)
                when (Reconcile.classify(atPosition, sha, sameContent)) {
                    ReconcileOutcome.NEW -> {
                        val content = contentFor(sha, size, sourceDir, staged.stagedFile, null)
                        ensureCopy(staged.stagedFile, File(content.storedPath))
                        insertReference(pf, semesterId, moduleIds, weekIds, ext, sha, size,
                            content.storedPath, content.id, fileName, relativePath)
                        created++
                    }

                    ReconcileOutcome.UNCHANGED -> {
                        val row = atPosition!!
                        val adoptLegacy = run {
                            if (row.sourceFileId != null) null else {
                                val legacy = File(row.filePath)
                                if (legacy.exists() && legacy.length() == size.toLong()) legacy.absolutePath else null
                            }
                        }
                        val content = contentFor(sha, size, sourceDir, staged.stagedFile, adoptLegacy)
                        ensureCopy(staged.stagedFile, File(content.storedPath))
                        if (row.sourceFileId != content.id || row.filePath != content.storedPath) {
                            dao.updateFile(row.copy(filePath = content.storedPath, sourceFileId = content.id))
                        }
                        unchanged++
                    }

                    ReconcileOutcome.CHANGED -> {
                        val row = atPosition!!
                        val content = contentFor(sha, size, sourceDir, staged.stagedFile, null)
                        ensureCopy(staged.stagedFile, File(content.storedPath))
                        dao.updateFile(
                            row.copy(
                                sha256 = sha,
                                filePath = content.storedPath,
                                fileSize = size,
                                lastModified = pf.entry.lastModified,
                                sourceFileId = content.id,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        changed++
                    }

                    ReconcileOutcome.DUPLICATE -> {
                        val content = contentFor(sha, size, sourceDir, staged.stagedFile, null)
                        ensureCopy(staged.stagedFile, File(content.storedPath))
                        insertReference(pf, semesterId, moduleIds, weekIds, ext, sha, size,
                            content.storedPath, content.id, fileName, relativePath)
                        duplicate++
                    }
                }
                _state.value = IngestState.Importing(fileName, index + 1, totalSteps, bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                errors.add("Failed storing ${pf.entry.logicalPath}: ${e.message}")
            }
        }

        _state.value = IngestState.Done(
            IngestSummary(
                modules = moduleIds.size,
                weeks = weekIds.size,
                created = created,
                unchanged = unchanged,
                changed = changed,
                duplicate = duplicate,
                failed = failed,
                skipped = skipped,
                errors = errors
            )
        )
        removeOrphanedContent()
    }

    /** Content row for [sha], creating it (and placing the copy) if new. */
    private suspend fun contentFor(
        sha: String,
        size: Long,
        sourceDir: File,
        staged: File,
        adoptLegacyPath: String?
    ): SourceFile {
        dao.findContentByHash(sha)?.let { return it }
        val canonical = File(sourceDir, sha)
        val storedPath = adoptLegacyPath ?: canonical.absolutePath
        if (adoptLegacyPath == null) ensureCopy(staged, canonical)
        val id = dao.insertContent(
            SourceFile(sha256 = sha, storedPath = storedPath, fileSize = size, refCount = 0)
        )
        return SourceFile(id = id, sha256 = sha, storedPath = storedPath, fileSize = size, refCount = 0)
    }

    private suspend fun insertReference(
        pf: PlannedFile,
        semesterId: Long,
        moduleIds: MutableMap<String, Long>,
        weekIds: MutableMap<Pair<String, Int>, Long>,
        ext: String,
        sha: String,
        size: Long,
        storedPath: String,
        contentId: Long,
        fileName: String,
        relativePath: String
    ) {
        val mid = moduleIds[pf.moduleName] ?: findOrCreateModule(semesterId, pf.moduleName)
        moduleIds[pf.moduleName] = mid
        val wid = weekIds[pf.moduleName to pf.weekNumber]
            ?: dao.findWeek(mid, pf.weekNumber)?.id
            ?: dao.insertWeek(Week(moduleId = mid, weekNumber = pf.weekNumber, title = pf.weekTitle))
        weekIds[pf.moduleName to pf.weekNumber] = wid
        dao.insertFile(
            AcademicFile(
                weekId = wid,
                fileName = fileName,
                filePath = storedPath,
                fileType = ext,
                sha256 = sha,
                classType = pf.classType.name,
                relativePath = relativePath,
                fileSize = size,
                lastModified = pf.entry.lastModified,
                sourceFileId = contentId
            )
        )
    }

    private suspend fun findOrCreateModule(semesterId: Long, name: String): Long =
        dao.findModule(semesterId, name)?.id
            ?: dao.insertModule(Module(semesterId = semesterId, name = name))

    /** SHA-256 + byte size of the staged copy. */
    private fun hashOf(src: File): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        FileInputStream(src).use { fis ->
            DigestInputStream(fis, digest).use { dis ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = dis.read(buf)
                    if (n < 0) break
                    size += n
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to size
    }

    /** Copies [src] to [dest] unless an equal-size copy already exists. */
    private fun ensureCopy(src: File, dest: File) {
        if (dest.exists() && dest.length() == src.length()) return
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
    }
}