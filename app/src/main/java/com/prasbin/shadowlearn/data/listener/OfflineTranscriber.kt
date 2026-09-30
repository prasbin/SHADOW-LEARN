package com.prasbin.shadowlearn.data.listener

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Phase 14 production [Transcriber]: real offline speech-to-text behind the
 * unchanged seam, powered by Vosk (Apache-2.0, small-en-US model bundled in
 * the APK, unpacked to app-private storage on first use).
 *
 * Pipeline (all on-device, all synchronous — the repository already calls
 * this on Dispatchers.IO):
 *
 * ```
 * session m4a (AAC 44.1 kHz, app-private)
 *   → MediaExtractor + MediaCodec decode → PCM
 *   → mono mix → linear resample to 16 kHz 16-bit
 *   → slice [startMs, endMs) per segment
 *   → Vosk Recognizer → verbatim text
 * ```
 *
 * Honesty contract (no invented speech, ever):
 * - Only English is supported (`null`/en tags); any other language tag is
 *   refused with a clear reason (the bundled model is English-only).
 * - Missing/empty model dir, native-load failure (e.g. 32-bit ABIs we
 *   deliberately do not ship), decoder failure, corrupt audio, empty
 *   segment slices, and engine throws all become `Failed` with a reason.
 *   `Throwable` is caught deliberately: native load failures surface as
 *   `UnsatisfiedLinkError`, i.e. an `Error`, not an `Exception`.
 * - Empty engine text is returned as `Ready("")` and left for the
 *   repository's existing empty guard (it already converts that case).
 *
 * Resources: the native `Model` is loaded once per process and shared
 * (loading costs seconds); each segment gets a fresh `Recognizer` (cheap,
 * and required so segments never bleed context into each other).
 */
class OfflineTranscriber(
    appContext: Context,
    private val provider: VoskModelProvider = VoskModelProvider(appContext.applicationContext),
    private val recognizerFactory: RecognizerFactory = VoskRecognizerFactory(),
    private val decode: (source: File, destPcm16kMono: File) -> Unit =
        { source, dest -> decodeToMono16kFile(source, dest) }
) : Transcriber {

    override fun transcribe(audioFile: File, languageTag: String?): TranscriptionResult =
        transcribe(audioFile, 0L, -1L, languageTag)

    override fun transcribe(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languageTag: String?
    ): TranscriptionResult {
        val tag = languageTag?.trim()?.lowercase()
        if (tag != null && !isSupportedLanguage(tag)) {
            return TranscriptionResult.Failed(
                "Speech recognition for \"$languageTag\" is not available on this device."
            )
        }
        return try {
            transcribeWithEngine(audioFile, startMs, endMs)
        } catch (t: Throwable) {
            TranscriptionResult.Failed(engineFailureReason(t))
        }
    }

    private fun transcribeWithEngine(audioFile: File, startMs: Long, endMs: Long): TranscriptionResult {
        if (!audioFile.exists() || audioFile.length() <= 0L) {
            return TranscriptionResult.Failed("Transcript unavailable: no usable audio for this segment.")
        }
        // Decoded PCM lives in a temp FILE, never in a giant heap buffer:
        // a 3-minute lecture is ~32 MB raw, which does not fit a 192 MB
        // heap next to the rest of the app (verified by OOM on CE_Test).
        val pcmFile = File.createTempFile("stt-slice", ".pcm", audioFile.parentFile)
        try {
            try {
                decode(audioFile, pcmFile)
            } catch (e: Exception) {
                return TranscriptionResult.Failed(
                    "Transcript unavailable: audio could not be decoded (${e.message ?: "unknown error"})."
                )
            }
            val totalSamples =
                (pcmFile.length() / 2L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val from = msToSamples(startMs.coerceAtLeast(0L), TARGET_SAMPLE_RATE)
                .coerceIn(0, totalSamples)
            val to = if (endMs < 0) totalSamples
            else msToSamples(endMs.coerceAtLeast(0L), TARGET_SAMPLE_RATE).coerceIn(0, totalSamples)
            if (to <= from) {
                return TranscriptionResult.Failed("Transcript unavailable: segment range holds no audio.")
            }
            val slice = readSlice(pcmFile, from, to)
            val modelDir = try {
                provider.modelDir()
            } catch (e: Exception) {
                return TranscriptionResult.Failed("Speech model unavailable: ${e.message ?: "unknown error"}.")
            }
            val text = recognizerFactory.recognize(modelDir, slice)
            return TranscriptionResult.Ready(text)
        } finally {
            pcmFile.delete()
        }
    }

    private fun readSlice(pcmFile: File, fromSamples: Int, toSamples: Int): ShortArray {
        java.io.RandomAccessFile(pcmFile, "r").use { raf ->
            raf.seek(fromSamples.toLong() * 2L)
            val bytes = ByteArray((toSamples - fromSamples) * 2)
            raf.readFully(bytes)
            val shorts = ShortArray(toSamples - fromSamples)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            return shorts
        }
    }

    private fun engineFailureReason(t: Throwable): String = when (t) {
        is UnsatisfiedLinkError, is NoClassDefFoundError ->
            "Speech engine could not start on this device (missing native library). Audio stays on the device."
        is java.io.IOException ->
            "Speech engine could not read the audio (${t.message ?: "I/O error"})."
        else -> "Transcription failed: ${t.message ?: t.javaClass.simpleName}."
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 16000

        fun isSupportedLanguage(tag: String): Boolean {
            val t = tag.trim().lowercase()
            return t == "en" || t.startsWith("en-") || t.startsWith("en_")
        }

        fun msToSamples(ms: Long, sampleRate: Int): Int =
            ((ms.coerceAtLeast(0L) * sampleRate) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        /** Average interleaved channels down to mono. */
        internal fun mixToMono(interleaved: ShortArray, channels: Int): ShortArray {
            if (channels <= 1) return interleaved.copyOf()
            val frames = interleaved.size / channels
            return ShortArray(frames) { f ->
                var sum = 0
                for (c in 0 until channels) sum += interleaved[f * channels + c].toInt()
                (sum / channels).toShort()
            }
        }

        /** Linear-interpolation resample of mono 16-bit PCM (single shot). */
        internal fun resampleMono(input: ShortArray, inRate: Int, outRate: Int = TARGET_SAMPLE_RATE): ShortArray {
            if (input.isEmpty()) return input
            if (inRate == outRate) return input.copyOf()
            return ChunkResampler(inRate, outRate).push(input)
        }

        /**
         * Decodes any framework-supported audio file straight to a mono
         * 16 kHz 16-bit PCM temp file (the shape Vosk consumes). Decoded
         * chunks stream through mono-mix + resample into the file, so heap
         * stays flat no matter the lecture length. Pure platform codecs —
         * offline.
         */
        @Throws(Exception::class)
        internal fun decodeToMono16kFile(source: File, dest: File) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(source.absolutePath)
                var track = -1
                var mime: String? = null
                for (i in 0 until extractor.trackCount) {
                    val m = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    if (m != null && m.startsWith("audio/")) {
                        track = i
                        mime = m
                        break
                    }
                }
                if (track < 0 || mime == null) {
                    throw java.io.IOException("No audio track in ${source.name}.")
                }
                extractor.selectTrack(track)
                val decoder = MediaCodec.createDecoderByType(mime)
                try {
                    decoder.configure(extractor.getTrackFormat(track), null, null, 0)
                    decoder.start()
                    var sampleRate = 44100
                    var channels = 1
                    var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
                    var resampler: ChunkResampler? = null
                    var decodedBytes = 0L
                    java.io.FileOutputStream(dest).use { fos ->
                        val info = MediaCodec.BufferInfo()
                        var inputDone = false
                        var outputDone = false
                        val outShorts = ShortArray(8192)
                        val outBytes = ByteBuffer.allocate(outShorts.size * 2)
                            .order(ByteOrder.LITTLE_ENDIAN)
                        fun flushShorts(n: Int) {
                            outBytes.clear()
                            outBytes.asShortBuffer().put(outShorts, 0, n)
                            fos.write(outBytes.array(), 0, n * 2)
                            decodedBytes += n * 2L
                        }
                    while (!outputDone) {
                        if (!inputDone) {
                            val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                            if (inIndex >= 0) {
                                val buf = decoder.getInputBuffer(inIndex)
                                if (buf == null) {
                                    decoder.queueInputBuffer(
                                        inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    val n = extractor.readSampleData(buf, 0)
                                    if (n < 0) {
                                        decoder.queueInputBuffer(
                                            inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                        inputDone = true
                                    } else {
                                        decoder.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                                        extractor.advance()
                                    }
                                }
                            }
                        }
                        when (val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val f = decoder.outputFormat
                                sampleRate = f.getInteger(
                                    MediaFormat.KEY_SAMPLE_RATE, sampleRate
                                )
                                channels = f.getInteger(
                                    MediaFormat.KEY_CHANNEL_COUNT, channels
                                )
                                pcmEncoding = f.getInteger(
                                    MediaFormat.KEY_PCM_ENCODING,
                                    android.media.AudioFormat.ENCODING_PCM_16BIT
                                )
                            }
                            else -> {
                                if (outIndex >= 0) {
                                    val buf = decoder.getOutputBuffer(outIndex)
                                    if (buf != null && info.size > 0) {
                                        val chunk = ByteArray(info.size)
                                        buf.position(info.offset)
                                        buf.limit(info.offset + info.size)
                                        buf.get(chunk)
                                        val shorts = bytesToShorts(chunk, pcmEncoding)
                                        val mono = mixToMono(shorts, channels)
                                        val res = resampler
                                            ?: ChunkResampler(sampleRate).also { resampler = it }
                                        val converted = res.push(mono)
                                        var off = 0
                                        while (off < converted.size) {
                                            val n = min(8192, converted.size - off)
                                            converted.copyInto(outShorts, 0, off, off + n)
                                            flushShorts(n)
                                            off += n
                                        }
                                    }
                                    decoder.releaseOutputBuffer(outIndex, false)
                                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                        outputDone = true
                                    }
                                }
                            }
                        }
                    }
                    if (decodedBytes == 0L) {
                        dest.delete()
                        throw java.io.IOException("Decoded no audio from ${source.name}.")
                    }
                }
            } finally {
                runCatching { decoder.stop() }
                decoder.release()
            }
            } finally {
                extractor.release()
            }
        }

        private fun bytesToShorts(chunk: ByteArray, pcmEncoding: Int): ShortArray {
            if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                val floats = ByteBuffer.wrap(chunk).order(ByteOrder.nativeOrder()).asFloatBuffer()
                return ShortArray(floats.remaining()) { i ->
                    (floats.get(i) * Short.MAX_VALUE).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            val bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return ShortArray(bb.remaining()).also { bb.get(it) }
        }

        private const val TIMEOUT_US = 10_000L
    }
}

/**
 * Stateful linear-interpolation resampler: identical math to the batch
 * [OfflineTranscriber.resampleMono], but fed one decoder chunk at a time
 * so a long lecture never sits in RAM twice. State is just the previous
 * sample plus the output position — continuity across chunks is exact up
 * to float rounding (see the split-push test).
 *
 * Top-level (not companion-nested) so streaming callers and tests share
 * one type.
 */
internal class ChunkResampler(
    inRate: Int,
    private val outRate: Int = OfflineTranscriber.TARGET_SAMPLE_RATE
) {
    private val ratio: Double
    private var pos = 0.0 // input-coordinate of the next output sample
    private var prev: Short = 0
    private var hasPrev = false
    private var consumed: Long = 0 // input samples seen before this chunk

    init {
        require(inRate > 0 && outRate > 0) { "Sample rates must be positive." }
        ratio = inRate.toDouble() / outRate.toDouble()
    }

    fun push(chunk: ShortArray): ShortArray {
        if (chunk.isEmpty()) return chunk
        val out = ArrayList<Short>(maxOf(16, (chunk.size / ratio).toInt() + 1))
        val base = consumed
        while (true) {
            val i0 = kotlin.math.floor(pos).toLong()
            // Both interpolation taps must be available.
            if (i0 + 1 >= base + chunk.size) break
            val a = sampleAt(i0, base, chunk).toInt()
            val b = sampleAt(i0 + 1, base, chunk).toInt()
            val frac = (pos - i0).toFloat()
            val mixed = a + (b - a) * frac
            out.add(mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            pos += ratio
        }
        consumed += chunk.size
        prev = chunk.last()
        hasPrev = true
        return out.toShortArray()
    }

    private fun sampleAt(global: Long, base: Long, chunk: ShortArray): Short {
        val local = (global - base).toInt()
        return if (local < 0) {
            check(hasPrev) { "Resampler ran before any input." }
            prev
        } else {
            chunk[local]
        }
    }
}

/**
 * Vosk recognition behind a seam so Robolectric tests (which cannot load
 * native code) never touch it: production passes [VoskRecognizerFactory],
 * tests pass fakes/doubles.
 */
fun interface RecognizerFactory {
    /** Returns the engine's verbatim final text (possibly empty). */
    @Throws(Exception::class)
    fun recognize(modelDir: File, samples16kMono: ShortArray): String
}

/** Production factory: one shared native Model, one fresh Recognizer per call. */
class VoskRecognizerFactory : RecognizerFactory {

    override fun recognize(modelDir: File, samples16kMono: ShortArray): String {
        val model = sharedModel(modelDir)
        val recognizer = org.vosk.Recognizer(model, 16000f)
        try {
            var offset = 0
            while (offset < samples16kMono.size) {
                val n = min(4096, samples16kMono.size - offset)
                recognizer.acceptWaveForm(samples16kMono.copyOfRange(offset, offset + n), n)
                offset += n
            }
            return org.json.JSONObject(recognizer.finalResult).optString("text", "").trim()
        } finally {
            recognizer.close()
        }
    }

    companion object {
        @Volatile
        private var cachedDir: String? = null

        @Volatile
        private var cachedModel: org.vosk.Model? = null

        private val lock = Any()

        /** Process-wide native model (loading costs seconds; documented). */
        @Throws(Exception::class)
        fun sharedModel(modelDir: File): org.vosk.Model {
            val key = modelDir.absolutePath
            cachedModel?.let { if (cachedDir == key) return it }
            synchronized(lock) {
                cachedModel?.let { if (cachedDir == key) return it }
                cachedModel?.close()
                val fresh = org.vosk.Model(key)
                cachedModel = fresh
                cachedDir = key
                return fresh
            }
        }

        /** Test-only escape hatch so suites never leak native state. */
        fun releaseForTests() {
            synchronized(lock) {
                runCatching { cachedModel?.close() }
                cachedModel = null
                cachedDir = null
            }
        }
    }
}
