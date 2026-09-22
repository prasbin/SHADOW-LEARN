package com.prasbin.shadowlearn.data.ingest

import android.content.Context
import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.AcademicFile
import com.prasbin.shadowlearn.data.db.Module
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

/** Outcome of one import run. */
data class IngestSummary(
    val modules: Int,
    val weeks: Int,
    val files: Int,
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
 * Phase 2 import orchestrator.
 *
 * Flow: SAF stream → temp staging ([ZipWalk]) → [HierarchyPlan] →
 * app-private copies + Room rows. One import = one `<filesDir>/academic/
 * <importId>/` tree; every stored file is traceable via
 * [AcademicFile.relativePath]. Failed files leave no record; successful
 * entries are kept (partial imports are resumable by re-import — Phase 3
 * deduplication will reconcile repeats).
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
        val destRoot = File(context.filesDir, "academic/$importId").apply { mkdirs() }
        try {
            supervisorScope {
                job = launch(Dispatchers.IO) {
                    runImport(stream, semesterId, archiveLabel, importId, staging, destRoot)
                }
                job!!.join()
            }
        } catch (e: CancellationException) {
            _state.value = IngestState.Failed("Cancelled by user.", 0)
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun runImport(
        stream: java.io.InputStream,
        semesterId: Long,
        archiveLabel: String,
        importId: String,
        staging: File,
        destRoot: File
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
        val usedPaths = mutableSetOf<String>()
        var stored = 0
        var skipped = 0
        var bytes = 0L
        val errors = walk.errors.toMutableList()
        val totalSteps = walk.entries.size

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

        for ((index, pf) in plan.files.withIndex()) {
            ensureActive()
            val ext = IngestFormat.extensionOf(pf.entry.logicalPath.substringAfterLast('/'))
            if (!IngestFormat.isSupported(pf.entry.logicalPath.substringAfterLast('/'))) {
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
            _state.value = IngestState.Importing(
                pf.entry.logicalPath.substringAfterLast('/'), index, totalSteps, bytes
            )
            try {
                val rel = IngestFormat.sanitizeRelativePath(pf.entry.logicalPath)
                val dest = uniqueDest(destRoot, rel, usedPaths)
                val (sha, size) = hashAndCopy(staged.stagedFile, dest)
                bytes += size
                val fileName = pf.entry.logicalPath.substringAfterLast('/')
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
                        filePath = dest.absolutePath,
                        fileType = ext,
                        sha256 = sha,
                        classType = pf.classType.name,
                        relativePath = pf.entry.logicalPath,
                        fileSize = size,
                        lastModified = pf.entry.lastModified
                    )
                )
                stored++
                _state.value = IngestState.Importing(fileName, index + 1, totalSteps, bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                skipped++
                errors.add("Failed storing ${pf.entry.logicalPath}: ${e.message}")
            }
        }

        _state.value = IngestState.Done(
            IngestSummary(
                modules = moduleIds.size,
                weeks = weekIds.size,
                files = stored,
                skipped = skipped,
                errors = errors
            )
        )
    }

    private suspend fun findOrCreateModule(semesterId: Long, name: String): Long =
        dao.findModule(semesterId, name)?.id
            ?: dao.insertModule(Module(semesterId = semesterId, name = name))

    private fun uniqueDest(root: File, rel: String, used: MutableSet<String>): File {
        var candidate = rel
        var n = 2
        while (!used.add(candidate)) {
            val dot = rel.lastIndexOf('.')
            candidate = if (dot > 0) {
                rel.substring(0, dot) + " ($n)" + rel.substring(dot)
            } else {
                "$rel ($n)"
            }
            n++
        }
        return File(root, candidate).also { it.parentFile?.mkdirs() }
    }

    private fun hashAndCopy(src: File, dest: File): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        FileInputStream(src).use { fis ->
            DigestInputStream(fis, digest).use { dis ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = dis.read(buf)
                        if (n < 0) break
                        size += n
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to size
    }
}
