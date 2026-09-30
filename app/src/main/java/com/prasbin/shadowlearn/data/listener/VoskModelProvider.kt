package com.prasbin.shadowlearn.data.listener

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Phase 14 model supply: the Vosk small-en-US model ships inside the APK
 * (`src/main/assets/vosk-model-small-en-us-0.15.zip`, ~41 MB) and is
 * unpacked to app-private storage on first transcription. No network
 * download ever happens; the zip is deleted never (kept as the
 * reinstall source of truth), the unpacked dir is the runtime source.
 *
 * This class touches files only — never native code — so it is fully
 * unit-testable. All native/model failures surface as thrown exceptions
 * with honest messages; callers convert them to `Failed` reasons.
 */
open class VoskModelProvider(
    private val appContext: Context,
    private val assetName: String = MODEL_ASSET,
    private val modelDirName: String = MODEL_DIR
) {

    /**
     * Returns the unpacked model directory, unpacking from assets on first
     * use. Throws with an honest message when the asset is missing or the
     * unpack fails.
     */
    @Throws(Exception::class)
    open fun modelDir(): File {
        val dir = File(File(appContext.filesDir, "vosk"), modelDirName)
        val marker = File(dir, READY_MARKER)
        if (marker.exists() && hasModelFiles(dir)) return dir
        unpack(dir)
        if (!hasModelFiles(dir)) {
            throw IllegalStateException("Unpacked Vosk model is incomplete in ${dir.absolutePath}.")
        }
        marker.parentFile?.mkdirs()
        marker.writeText("ready")
        return dir
    }

    internal fun hasModelFiles(dir: File): Boolean {
        if (!File(dir, "am/final.mdl").exists()) return false
        // Small models ship either a static HCLG.fst or the rescoring
        // pair (HCLr.fst + Gr.fst, as in small-en-us-0.15) — accept both.
        if (File(dir, "graph/HCLG.fst").exists()) return true
        return File(dir, "graph/HCLr.fst").exists() &&
            File(dir, "graph/Gr.fst").exists()
    }

    private fun unpack(dir: File) {
        dir.deleteRecursively()
        dir.mkdirs()
        val tmp = createTempCopy()
        try {
            ZipFile(tmp).use { zip -> extractZip(zip, dir) }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        } finally {
            tmp.delete()
        }
    }

    private fun extractZip(zip: ZipFile, dir: File) {
        // Model zips carry one top-level folder (e.g.
        // `vosk-model-small-en-us-0.15/...`); strip it so the model dir
        // itself holds am/, graph/, conf/.
        val names = zip.entries().toList().map { it.name }
        val prefix = commonTopLevel(names)
        val entries = zip.entries()
        val buf = ByteArray(64 * 1024)
        val base = dir.canonicalPath + File.separator
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val stripped = if (prefix != null) {
                if (!entry.name.startsWith(prefix)) continue
                entry.name.removePrefix(prefix)
            } else {
                entry.name
            }
            if (stripped.isEmpty()) continue
            // Zip-slip guard: never write outside the model dir.
            val out = File(dir, stripped).canonicalFile
            if (!out.path.startsWith(base)) {
                throw SecurityException("Unsafe model entry: ${entry.name}")
            }
            if (entry.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { eis ->
                    FileOutputStream(out).use { fos ->
                        while (true) {
                            val n = eis.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                        }
                    }
                }
            }
        }
    }

    private fun createTempCopy(): File {
        val tmp = File.createTempFile("vosk-model", ".zip", appContext.cacheDir)
        try {
            appContext.assets.open(assetName).use { ins ->
                FileOutputStream(tmp).use { out -> ins.copyTo(out) }
            }
        } catch (e: java.io.FileNotFoundException) {
            tmp.delete()
            throw java.io.FileNotFoundException("Vosk model asset missing: $assetName.")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        return tmp
    }

    companion object {
        const val MODEL_ASSET = "vosk-model-small-en-us-0.15.zip"
        const val MODEL_DIR = "vosk-model-small-en-us-0.15"
        const val READY_MARKER = ".ready"

        /**
         * Single shared top-level folder of a model zip (or null).
         * Pure, unit-tested.
         */
        internal fun commonTopLevel(names: List<String>): String? {
            val tops = names.filter { it.contains('/') }.map { it.substringBefore('/') }.toSet()
            return if (tops.size == 1) tops.single() + "/" else null
        }
    }
}
