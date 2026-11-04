# LECTURE / TRANSCRIPTION CAPABILITY REPORT

**Audited against the current code, not assumptions.**

## Recording (implemented)

- `MediaRecorder` → MPEG-4/AAC, 128 kbps, 44.1 kHz mono source.
- One audio file per session in app-private storage.
- Segments close at every pause/resume boundary; segment times use a
  monotonic clock, so wall-clock jumps cannot corrupt durations.
- A foreground service keeps capture visible and recoverable.
- Process death / recorder failure: the latest open session is rehydrated,
  its open span is closed honestly (duration unknown → 0), the session is
  marked `INTERRUPTED`, and the audio file is preserved.
- A two-hour lecture is one AAC file (~115 MB at 128 kbps). The recorder has
  no duration cap; storage is the practical limit. Segments are bounded by
  pause/resume, and transcription processes one segment slice at a time.

## Transcription (implemented, English only)

- Engine: Vosk, Apache-2.0, on-device.
- Bundled model: `vosk-model-small-en-us-0.15` (~41 MB zip in assets,
  unpacked to app-private storage on first use).
- No network download, no cloud STT, no paid API.
- Output is verbatim recognizer text or an explicit failure reason.

### Language support — honest statement

| Language | Status |
| --- | --- |
| English | Supported by the bundled model |
| Nepali / Devanagari | **Not supported** — no model ships in this build |
| Nepali–English code-switching | **Not supported** — same limitation |

Any non-English language tag is refused with a clear reason instead of being
transcribed by the English model. Adding Nepali requires a separate offline
Vosk model (or another on-device engine) plus a size/RAM/thermal review on the
target Redmi Note 14 5G; that work is not part of this build.

### Mathematics — honest statement

Spoken formulas are transcribed as ordinary words. There is **no** formula
reconstruction, no equation normalization, and no OCR/visual extraction from
slides. The transcript must never be treated as a verified equation.

## What is deliberately not claimed

- Nepali or multilingual accuracy.
- Mathematical-expression recognition.
- Cloud transcription.
- Background transcription without an explicit user action.
