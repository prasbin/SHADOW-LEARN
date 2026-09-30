package com.prasbin.shadowlearn.data.listener

import java.io.File

/**
 * Result of one transcription attempt.
 *
 * [Ready] carries the recognizer's verbatim text — it is written straight
 * from the engine and never paraphrased or fabricated. [Failed] carries an
 * honest human reason (unsupported language, missing audio, no engine…)
 * and NEVER produces invented speech.
 */
sealed class TranscriptionResult {
    data class Ready(val transcript: String) : TranscriptionResult()
    data class Failed(val reason: String) : TranscriptionResult()
}

/**
 * Phase 10 speech-to-text seam — the transcription twin of [ListenerRecorder].
 *
 * The repository only talks to this interface, so the engine can be swapped
 * (e.g. a bundled on-device model) without touching session/segment logic,
 * exactly like the recorder seam. Tests inject a fake; production uses the
 * default [UnavailableTranscriber] (see its docs for why).
 *
 * Contract: given the segment's audio file, return either verbatim
 * [TranscriptionResult.Ready] text or an honest [TranscriptionResult.Failed]
 * reason. Never return made-up text.
 */
interface Transcriber {

    /**
     * Transcribes [audioFile] (an app-private m4a) into text, or explains
     * why it could not. [languageTag] is an optional BCP-47 tag (e.g.
     * "es-ES"); null means the caller detected no explicit preference.
     */
    fun transcribe(audioFile: File, languageTag: String? = null): TranscriptionResult

    /**
     * Transcribes the [startMs, endMs) slice of [audioFile] (Phase 14
     * per-segment recognition). `endMs < 0` means "to end of file".
     * The default implementation ignores the range and transcribes the
     * whole file, so existing fakes keep compiling and behaving.
     */
    fun transcribe(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languageTag: String? = null
    ): TranscriptionResult = transcribe(audioFile, languageTag)
}

/**
 * Default production [Transcriber]: the honest "no on-device engine is
 * wired to the seam yet" implementation.
 *
 * SHADOW LEARN is aggressively offline — no network permission, no cloud
 * STT, no audio ever leaves the device. Android's platform
 * [android.speech.SpeechRecognizer] routes through a system service that is
 * absent on the CE_Test emulator and does not accept a stored audio file,
 * and bundling a full on-device engine (e.g. Vosk) is a multi-megabyte
 * native + model dependency that this phase deliberately does not ship.
 *
 * Instead of fabricating transcripts, segments that would be transcribed by
 * this implementation transition to `failed` with the reason below — the
 * exact "report unsupported, don't invent" behavior the phase requires. A
 * real engine can be dropped in behind [Transcriber] later without touching
 * the repository, UI, or database.
 */
class UnavailableTranscriber : Transcriber {
    override fun transcribe(audioFile: File, languageTag: String?): TranscriptionResult =
        TranscriptionResult.Failed(
            if (languageTag != null && !supportLanguage(languageTag)) {
                "Speech recognition for \"$languageTag\" is not available on this device."
            } else {
                "No on-device speech engine is installed. Audio stays on the device."
            }
        )

    private fun supportLanguage(tag: String): Boolean = false
}