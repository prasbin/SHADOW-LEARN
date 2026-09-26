package com.prasbin.shadowlearn.data.listener

import android.media.MediaRecorder
import java.io.File

/**
 * Phase 7 recording abstraction — the seam future transcription plugs into.
 *
 * The UI and [ListenerRepository] only ever talk to this interface, so the
 * MediaRecorder implementation can be replaced (or decorated with a
 * transcriber) without touching session/segment logic. Test fakes live in
 * the unit tests; production uses [MediaRecorderListenerRecorder].
 */
interface ListenerRecorder {

    /**
     * Begins recording into [outputFile] (parent must exist).
     * Throws [SecurityException] without RECORD_AUDIO and
     * [java.io.IOException]/[IllegalStateException] on device failure.
     */
    @Throws(Exception::class)
    fun start(outputFile: File)

    /** Suspends capture, keeping the file open (API 24+; minSdk is 26). */
    fun pause()

    /** Resumes a paused capture. */
    fun resume()

    /**
     * Finalizes the file. Throws [RuntimeException] when nothing recordable
     * was captured (e.g. stopped immediately) — callers must still treat the
     * session row honestly instead of crashing.
     */
    fun stop()

    /** Releases native resources; safe to call more than once. */
    fun release()

    /** 0..32767 recent peak, or 0 when unavailable (emulators report 0). */
    fun maxAmplitude(): Int
}

/**
 * Production [ListenerRecorder] over [MediaRecorder] (MPEG-4/AAC —
 * universally playable, small enough for lecture lengths).
 */
class MediaRecorderListenerRecorder : ListenerRecorder {

    private var recorder: MediaRecorder? = null

    override fun start(outputFile: File) {
        release()
        // The no-arg constructor is deprecated in S+ but remains functional
        // on every API level we target (the S+ Context constructor adds no
        // behavior Listener Mode needs), so one code path covers all devices.
        @Suppress("DEPRECATION")
        val fresh = MediaRecorder()
        fresh.setAudioSource(MediaRecorder.AudioSource.MIC)
        fresh.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        fresh.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        fresh.setAudioEncodingBitRate(128_000)
        fresh.setAudioSamplingRate(44_100)
        fresh.setOutputFile(outputFile.absolutePath)
        fresh.prepare()
        fresh.start()
        recorder = fresh
    }

    override fun pause() {
        recorder?.pause()
    }

    override fun resume() {
        recorder?.resume()
    }

    override fun stop() {
        val mr = recorder ?: return
        recorder = null
        try {
            mr.stop()
        } finally {
            mr.release()
        }
    }

    override fun release() {
        val mr = recorder ?: return
        recorder = null
        runCatching { mr.release() }
    }

    override fun maxAmplitude(): Int =
        runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
}
