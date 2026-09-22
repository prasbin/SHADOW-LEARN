package com.prasbin.shadowlearn.data.ingest

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipException

/**
 * Archive walker: expands a ZIP (and nested ZIPs at any depth) into a flat
 * [WalkedEntry] list plus a content callback for file bytes.
 *
 * Design (see docs/ARCHITECTURE.md):
 * - The top-level stream is materialized to a temp file first so [ZipFile]
 *   validates the central directory upfront and reports an entry count for
 *   honest progress. Corrupt top-level archives fail here, before any DB
 *   writes.
 * - Each nested `.zip` is materialized to its own bounded temp file, so one
 *   corrupt nested archive cannot desynchronize the parent stream — the
 *   failure is isolated to that nested path.
 * - Temps live under `<cacheDir>/shadowlearn-ingest/<importId>/` and are
 *   deleted when the walk ends (success or failure).
 * - Guards: [IngestFormat.MAX_NESTING_DEPTH], per-file and total byte caps,
 *   entry-count cap. Zip-slip is neutralized: entries with absolute paths or
 *   `..` segments are skipped with an error (never written outside the
 *   import directory).
 */
object ZipWalk {

    data class WalkResult(
        val entries: List<WalkedFile>,
        val dirPaths: List<String>,
        val totalEntries: Int,
        val errors: List<String>
    )

    /**
     * A file entry whose bytes are staged in a temp file, plus already-known
     * metadata. Callers stream [stagedFile] once (hash + copy), then it is
     * deleted with the rest of the staging directory.
     */
    data class WalkedFile(
        val logicalPath: String,
        val stagedFile: File,
        val size: Long,
        val lastModified: Long
    )

    fun walk(
        source: InputStream,
        sourceName: String,
        stagingDir: File,
        onEntryCount: (Int) -> Unit = {}
    ): WalkResult {
        stagingDir.mkdirs()
        val errors = mutableListOf<String>()
        val files = mutableListOf<WalkedFile>()
        val dirs = mutableSetOf<String>()

        val top = stageStream(source, File(stagingDir, "top.zip"), IngestFormat.MAX_TOTAL_BYTES, errors)
            ?: return WalkResult(emptyList(), emptyList(), 0, errors)
        try {
            val zip = openZip(top, sourceName, errors) ?: return WalkResult(
                emptyList(), emptyList(), 0, errors
            )
            zip.use {
                val all = it.entries().toList()
                onEntryCount(all.size)
                if (all.size > IngestFormat.MAX_ENTRIES) {
                    errors.add(
                        "Archive has ${all.size} entries (limit ${IngestFormat.MAX_ENTRIES}); " +
                            "importing the first ${IngestFormat.MAX_ENTRIES}."
                    )
                }
                walkZipFile(
                    zipFile = it,
                    prefix = "",
                    depth = 0,
                    stagingDir = stagingDir,
                    errors = errors,
                    files = files,
                    dirs = dirs,
                    budget = Budget()
                )
            }
        } catch (e: Exception) {
            errors.add("Failed reading archive $sourceName: ${e.message}")
        }
        return WalkResult(files, dirs.sorted(), files.size + dirs.size, errors)
    }

    private class Budget(var total: Long = 0)

    private fun walkZipFile(
        zipFile: ZipFile,
        prefix: String,
        depth: Int,
        stagingDir: File,
        errors: MutableList<String>,
        files: MutableList<WalkedFile>,
        dirs: MutableSet<String>,
        budget: Budget
    ) {
        if (depth > IngestFormat.MAX_NESTING_DEPTH) {
            errors.add("Nesting deeper than ${IngestFormat.MAX_NESTING_DEPTH} at $prefix — skipped.")
            return
        }
        val entries = zipFile.entries().toList().take(IngestFormat.MAX_ENTRIES)
        for (zipEntry in entries) {
            // Canonicalize: some archives (and Android's ZipFile on nested
            // streams) deliver backslash-separated entry names. Logical paths
            // must use '/' or hierarchy planning splits on empty segments.
            val rawName = zipEntry.name.replace('\\', '/').trim('/').trim()
            if (rawName.isEmpty()) continue
            if (isUnsafePath(rawName)) {
                errors.add("Unsafe entry path skipped: $rawName")
                continue
            }
            val logical = if (prefix.isEmpty()) rawName else "$prefix/$rawName"
            if (zipEntry.isDirectory) {
                dirs.add(logical)
                continue
            }
            try {
                if (IngestFormat.isZip(rawName)) {
                    val nested = stageEntry(zipFile, zipEntry, stagingDir, errors, budget)
                        ?: continue
                    val nestedZip = openZip(nested, logical, errors)
                    if (nestedZip == null) {
                        errors.add("Corrupt nested archive skipped: $logical")
                        continue
                    }
                    nestedZip.use {
                        walkZipFile(it, logical, depth + 1, stagingDir, errors, files, dirs, budget)
                    }
                } else {
                    val staged = stageEntry(zipFile, zipEntry, stagingDir, errors, budget)
                        ?: continue
                    val size = if (zipEntry.size >= 0) zipEntry.size else staged.length()
                    files.add(
                        WalkedFile(
                            logicalPath = logical,
                            stagedFile = staged,
                            size = size,
                            lastModified = zipEntry.time.coerceAtLeast(0)
                        )
                    )
                }
            } catch (e: Exception) {
                errors.add("Unreadable entry $logical: ${e.message}")
            }
        }
    }

    private fun openZip(file: File, label: String, errors: MutableList<String>): ZipFile? =
        try {
            ZipFile(file)
        } catch (e: ZipException) {
            errors.add("Invalid ZIP archive: $label (${e.message})")
            null
        } catch (e: Exception) {
            errors.add("Cannot open archive $label: ${e.message}")
            null
        }

    /** Copies an entry stream to a temp file within caps; null + error on breach. */
    private fun stageEntry(
        zipFile: ZipFile,
        entry: ZipEntry,
        stagingDir: File,
        errors: MutableList<String>,
        budget: Budget
    ): File? {
        if (entry.size > IngestFormat.MAX_ENTRY_BYTES) {
            errors.add("Entry too large, skipped: ${entry.name} (${entry.size} bytes)")
            return null
        }
        val tmp = File.createTempFile("entry", ".bin", stagingDir)
        return try {
            zipFile.getInputStream(entry).use { input ->
                copyBounded(input, tmp, IngestFormat.MAX_ENTRY_BYTES, budget, IngestFormat.MAX_TOTAL_BYTES)
            }
            tmp
        } catch (e: BudgetExceeded) {
            tmp.delete()
            errors.add("Import budget exceeded at ${entry.name}: ${e.message}")
            null
        } catch (e: Exception) {
            tmp.delete()
            errors.add("Cannot read entry ${entry.name}: ${e.message}")
            null
        }
    }

    private fun stageStream(
        input: InputStream,
        dest: File,
        cap: Long,
        errors: MutableList<String>
    ): File? =
        try {
            input.use { copyBounded(it, dest, cap, Budget(), cap) }
            dest
        } catch (e: Exception) {
            dest.delete()
            errors.add("Cannot stage archive: ${e.message}")
            null
        }

    private fun copyBounded(
        input: InputStream,
        dest: File,
        perFileCap: Long,
        budget: Budget,
        totalCap: Long
    ) {
        dest.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var written = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                written += n
                budget.total += n
                if (written > perFileCap) throw BudgetExceeded("file exceeds $perFileCap bytes")
                if (budget.total > totalCap) throw BudgetExceeded("import exceeds $totalCap bytes total")
                out.write(buf, 0, n)
            }
        }
    }

    private class BudgetExceeded(message: String) : Exception(message)

    /** Rejects absolute paths and `..` escapes (zip-slip), both separators. */
    fun isUnsafePath(name: String): Boolean {
        if (name.startsWith('/') || name.startsWith('\\')) return true
        if (Regex("^[A-Za-z]:").containsMatchIn(name)) return true
        return name.split('/', '\\').any { it == ".." }
    }

    /** Converts walk output (files + explicit dir entries) to plan input. */
    fun toPlanInput(result: WalkResult): List<WalkedEntry> {
        val fileEntries = result.entries.map {
            WalkedEntry(it.logicalPath, isDirectory = false, size = it.size, lastModified = it.lastModified)
        }
        val dirEntries = result.dirPaths.map { WalkedEntry(it, isDirectory = true) }
        return fileEntries + dirEntries
    }
}
