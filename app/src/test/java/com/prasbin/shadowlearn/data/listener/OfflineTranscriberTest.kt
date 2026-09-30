package com.prasbin.shadowlearn.data.listener

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.prasbin.shadowlearn.data.AppContainer
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 14 engine tests. Robolectric cannot load native code, so anything
 * touching libvosk is verified as HONEST FAILURE here; real engine output
 * travels through the [RecognizerFactory]/decoder seams via doubles.
 * Tests that need the native library are marked REAL ENGINE EXECUTED only
 * when they run on-device — never here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineTranscriberTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun writePcm(dest: File, samples: ShortArray) {
        java.io.FileOutputStream(dest).use { fos ->
            val bb = java.nio.ByteBuffer.allocate(samples.size * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(samples)
            fos.write(bb.array())
        }
    }

    private fun pcmDecoder(samples: ShortArray): (File, File) -> Unit =
        { _, dest -> writePcm(dest, samples) }

    private fun twoSeconds(): ShortArray = ShortArray(2 * 16000) { (it % 100).toShort() }

    private fun fakeModelDir(): File =
        File(context.cacheDir, "fake-model").apply { mkdirs() }

    private fun fakeProvider(): VoskModelProvider =
        object : VoskModelProvider(context) {
            override fun modelDir(): File = fakeModelDir()
        }

    private fun transcriber(
        factory: RecognizerFactory = RecognizerFactory { _, _ -> "hello world" },
        decode: (File, File) -> Unit = pcmDecoder(twoSeconds()),
        provider: VoskModelProvider = fakeProvider()
    ): OfflineTranscriber = OfflineTranscriber(
        appContext = context,
        provider = provider,
        recognizerFactory = factory,
        decode = decode
    )

    @After
    fun tearDown() {
        VoskRecognizerFactory.releaseForTests()
    }

    // ---- seam ------------------------------------------------------------------

    @Test
    fun offlineTranscriberExistsBehindTranscriberSeam() {
        val t: Transcriber = transcriber()
        assertTrue(t is OfflineTranscriber)
    }

    @Test
    fun rangedOverloadDefaultsToWholeFileBehavior() {
        val fake = object : Transcriber {
            override fun transcribe(audioFile: File, languageTag: String?) =
                TranscriptionResult.Ready("whole")
        }
        val f = File(context.cacheDir, "r.mp4").apply { writeBytes(ByteArray(8)) }
        // Calls the ranged overload, which the fake does not override.
        val result = fake.transcribe(f, 0L, 500L, null)
        assertTrue(result is TranscriptionResult.Ready)
        assertEquals("whole", (result as TranscriptionResult.Ready).transcript)
    }

    @Test
    fun productionWiringPointsToRealImplementation() {
        val repo = AppContainer.transcription(context)
        val field = repo.javaClass.getDeclaredField("transcriber")
        field.isAccessible = true
        assertTrue(field.get(repo) is OfflineTranscriber)
    }

    // ---- success path (doubles; UNIT TESTED, not native) -------------------------

    @Test
    fun engineSuccessProducesReadyTranscript() {
        var seen = 0
        val t = transcriber(factory = RecognizerFactory { _, samples ->
            seen = samples.size
            "the professor explained gradient descent"
        })
        val f = File(context.cacheDir, "s.mp4").apply { writeBytes(ByteArray(8)) }
        val result = t.transcribe(f, 0L, 2000L, null)
        assertTrue(result is TranscriptionResult.Ready)
        assertEquals("the professor explained gradient descent", (result as TranscriptionResult.Ready).transcript)
        assertEquals(32000, seen)
    }

    @Test
    fun emptyEngineResultStaysReadyForRepositoryGuard() {
        val t = transcriber(factory = RecognizerFactory { _, _ -> "   " })
        val f = File(context.cacheDir, "e.mp4").apply { writeBytes(ByteArray(8)) }
        val result = t.transcribe(f, 0L, 1000L, null)
        // Empty text is passed through; ListenerTranscriptionRepository
        // converts it to FAILED ("engine returned empty text").
        assertTrue(result is TranscriptionResult.Ready)
    }

    @Test
    fun segmentSliceIsHonored() {
        var seen = -1
        val t = transcriber(factory = RecognizerFactory { _, samples ->
            seen = samples.size
            "x"
        })
        val f = File(context.cacheDir, "r.mp4").apply { writeBytes(ByteArray(8)) }
        t.transcribe(f, 0L, 500L, null)
        assertEquals(8000, seen)
    }

    // ---- honest failures -----------------------------------------------------------

    @Test
    fun missingAudioFailsHonestly() {
        var calls = 0
        val t = transcriber(factory = RecognizerFactory { _, _ -> calls++; "x" })
        val result = t.transcribe(File(context.cacheDir, "absent.mp4"), 0L, 1000L, null)
        assertTrue(result is TranscriptionResult.Failed)
        assertEquals(0, calls)
    }

    @Test
    fun missingModelFailsHonestly() {
        val t = transcriber(
            provider = VoskModelProvider(context, assetName = "no-such-model.zip")
        )
        val f = File(context.cacheDir, "m.mp4").apply { writeBytes(ByteArray(8)) }
        val result = t.transcribe(f, 0L, 1000L, null)
        assertTrue(result is TranscriptionResult.Failed)
        val reason = (result as TranscriptionResult.Failed).reason
        assertTrue("reason=$reason", reason.contains("model", ignoreCase = true))
    }

    @Test
    fun engineExceptionFailsHonestly() {
        val t = transcriber(factory = RecognizerFactory { _, _ ->
            throw RuntimeException("decoder exploded")
        })
        val f = File(context.cacheDir, "x.mp4").apply { writeBytes(ByteArray(8)) }
        val result = t.transcribe(f, 0L, 1000L, null)
        assertTrue(result is TranscriptionResult.Failed)
        assertTrue((result as TranscriptionResult.Failed).reason.contains("decoder exploded"))
    }

    @Test
    fun corruptAudioDecodeFailsHonestly() {
        val t = transcriber(decode = { _, _ -> throw java.io.IOException("no audio track") })
        val f = File(context.cacheDir, "c.mp4").apply { writeBytes(ByteArray(8)) }
        val result = t.transcribe(f, 0L, 1000L, null)
        assertTrue(result is TranscriptionResult.Failed)
        assertTrue((result as TranscriptionResult.Failed).reason.contains("decod", ignoreCase = true))
    }

    @Test
    fun emptySegmentRangeFailsHonestly() {
        var calls = 0
        val t = transcriber(
            factory = RecognizerFactory { _, _ -> calls++; "x" },
            decode = { _, dest -> java.io.FileOutputStream(dest).close() }
        )
        val f = File(context.cacheDir, "z.mp4").apply { writeBytes(ByteArray(8)) }
        val result = t.transcribe(f, 5000L, 6000L, null)
        assertTrue(result is TranscriptionResult.Failed)
        assertEquals(0, calls)
    }

    @Test
    fun unsupportedLanguageFailsHonestly() {
        var calls = 0
        val t = transcriber(factory = RecognizerFactory { _, _ -> calls++; "x" })
        val f = File(context.cacheDir, "l.mp4").apply { writeBytes(ByteArray(8)) }
        val result = t.transcribe(f, 0L, 1000L, "es-ES")
        assertTrue(result is TranscriptionResult.Failed)
        assertTrue((result as TranscriptionResult.Failed).reason.contains("es-ES"))
        assertEquals(0, calls)
    }

    @Test
    fun englishTagsAreAccepted() {
        val cases = listOf(null, "en", "en-US", "en_US")
        for (tag in cases) {
            val t = transcriber()
            val f = File(context.cacheDir, "ok.mp4").apply { writeBytes(ByteArray(8)) }
            val result = t.transcribe(f, 0L, 1000L, tag)
            // Missing model is fine here — the point is the tag was NOT refused as unsupported.
            if (result is TranscriptionResult.Failed) {
                assertFalse(result.reason.contains("not available on this device"))
            }
        }
    }

    @Test
    fun nativeLibraryUnavailableUnderRobolectric() {
        // REAL ENGINE EXECUTED: never here. This test documents that the
        // native Vosk path cannot run on the host JVM — it must fail, not
        // silently succeed.
        val factory = VoskRecognizerFactory()
        try {
            factory.recognize(File(context.cacheDir, "model"), ShortArray(160))
            fail("native Vosk must not load under Robolectric")
        } catch (t: Throwable) {
            assertTrue(
                "expected native-load failure, got ${t.javaClass.name}",
                t is UnsatisfiedLinkError || t is NoClassDefFoundError || t is ExceptionInInitializerError
            )
        }
    }

    @Test
    fun noInternetPermissionRequested() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toList() ?: emptyList()
        assertTrue(requested.contains(android.Manifest.permission.RECORD_AUDIO))
        assertFalse(
            "STT must stay offline: INTERNET must not be requested (found=$requested)",
            requested.contains(android.Manifest.permission.INTERNET)
        )
    }

    // ---- pure audio math ---------------------------------------------------------------

    @Test
    fun msToSamplesIsExact() {
        assertEquals(16000, OfflineTranscriber.msToSamples(1000L, 16000))
        assertEquals(8000, OfflineTranscriber.msToSamples(500L, 16000))
        assertEquals(0, OfflineTranscriber.msToSamples(-5L, 16000))
    }

    @Test
    fun mixToMonoAveragesChannels() {
        val stereo = shortArrayOf(1000, 3000, -1000, -3000)
        assertEquals(listOf(2000.toShort(), (-2000).toShort()), OfflineTranscriber.mixToMono(stereo, 2).toList())
        val mono = shortArrayOf(5, 6)
        assertEquals(listOf(5.toShort(), 6.toShort()), OfflineTranscriber.mixToMono(mono, 1).toList())
    }

    @Test
    fun resampleHalvesRate() {
        val input = ShortArray(441) { 1000 }
        val out = OfflineTranscriber.resampleMono(input, 44100, 16000)
        assertEquals(160, out.size)
        assertTrue(out.all { it in 990..1010 })
    }

    @Test
    fun resampleSameRateCopies() {
        val input = shortArrayOf(1, -2, 300)
        assertEquals(input.toList(), OfflineTranscriber.resampleMono(input, 16000, 16000).toList())
    }

    @Test
    fun chunkResamplerSplitPushMatchesSinglePush() {
        val input = ShortArray(2000) { (it % 250).toShort() }
        val single = OfflineTranscriber.resampleMono(input, 44100, 16000)
        val streamer = ChunkResampler(44100, 16000)
        val streamed = streamer.push(input.copyOfRange(0, 700)) +
            streamer.push(input.copyOfRange(700, input.size))
        assertEquals(single.size, streamed.size)
        // Same math, float accumulation order only — allow 1 LSB.
        for (i in streamed.indices) {
            assertTrue(
                "i=$i single=${single[i]} streamed=${streamed[i]}",
                kotlin.math.abs(single[i] - streamed[i]) <= 1
            )
        }
    }

    // ---- model layout -----------------------------------------------------------

    @Test
    fun commonTopLevelStripsSingleWrapper() {
        assertEquals(
            "vosk-model-small-en-us-0.15/",
            VoskModelProvider.commonTopLevel(
                listOf(
                    "vosk-model-small-en-us-0.15/",
                    "vosk-model-small-en-us-0.15/am/final.mdl",
                    "vosk-model-small-en-us-0.15/graph/HCLr.fst"
                )
            )
        )
    }

    @Test
    fun commonTopLevelKeepsMixedRoots() {
        assertEquals(
            null,
            VoskModelProvider.commonTopLevel(listOf("a/x", "b/y"))
        )
        assertEquals(null, VoskModelProvider.commonTopLevel(listOf("flat")))
        assertEquals(null, VoskModelProvider.commonTopLevel(emptyList()))
    }

    @Test
    fun hasModelFilesAcceptsBothGraphLayouts() {
        val provider = VoskModelProvider(context)
        val rescoring = File(context.cacheDir, "m-rescore").apply { mkdirs() }
        File(rescoring, "am").mkdirs()
        File(rescoring, "am/final.mdl").writeBytes(ByteArray(4))
        File(rescoring, "graph").mkdirs()
        File(rescoring, "graph/HCLr.fst").writeBytes(ByteArray(4))
        File(rescoring, "graph/Gr.fst").writeBytes(ByteArray(4))
        assertTrue(provider.hasModelFiles(rescoring))
        val static = File(context.cacheDir, "m-static").apply { mkdirs() }
        File(static, "am").mkdirs()
        File(static, "am/final.mdl").writeBytes(ByteArray(4))
        File(static, "graph").mkdirs()
        File(static, "graph/HCLG.fst").writeBytes(ByteArray(4))
        assertTrue(provider.hasModelFiles(static))
        val broken = File(context.cacheDir, "m-broken").apply { mkdirs() }
        File(broken, "am").mkdirs()
        File(broken, "am/final.mdl").writeBytes(ByteArray(4))
        assertFalse(provider.hasModelFiles(broken))
    }
}
