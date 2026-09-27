# Setup & Build — SHADOW LEARN (Phase 5)

## 1. Requirements (exact)

| Tool | Required | This machine |
|---|---|---|
| Android Studio | 2025.3.2 | installed |
| JDK for builds | 21 (Studio bundled JBR) | `C:\Program Files\Android\Android Studio\jbr` |
| Android SDK | platforms 36, build-tools 36.1.0 | `%LOCALAPPDATA%\Android\Sdk` |
| Gradle | 8.13 (via wrapper) | bootstrapped, dist on E: |
| AGP / Kotlin / KSP | 8.13.2 / 2.1.20 / 2.1.20-2.0.1 | pinned in `build.gradle.kts` |
| Compose BOM / Room / Navigation / DataStore / Work | 2026.04.01 / 2.8.3 / 2.7.7 / 1.1.1 / 2.9.0 | pinned in `app/build.gradle.kts` |

System Java 8 on PATH is **not** used. Every Gradle invocation must set:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:GRADLE_USER_HOME = "E:\Desktop\AGENTS-UP v2.0\.gradle-home"
```

`GRADLE_USER_HOME` on E: keeps multi-GB caches off C: (measured 6.75 GB free
on 2026-09-22 and shrinking — C: must not take build artifacts).
(Optional, persistent: set both as user env vars via Windows Settings.)

`app/build.gradle.kts` also adds `testImplementation
"org.xerial:sqlite-jdbc:3.41.2.2"` (JVM SQLite incl. FTS5 for the
`FtsIndex` unit tests); it is a **test-only** dependency — the APK has no
new runtime libraries.

Why AGP 8.13.2, not 9.x: 8.13.x is the latest stable 8.x line, pairs with
Gradle 8.13, and builds `compileSdk 36` with the locally installed
platform + build-tools 36.1.0. `android.suppressUnsupportedCompileSdk=36`
silences the "API 35 and lower" configuration warning. AGP 9.x (Gradle 9,
new DSL defaults) is a future upgrade, not needed for Phase 1.

## 2. First-time bootstrap

```powershell
cd "E:\Desktop\AGENTS-UP v2.0\SHADOW LEARN"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:GRADLE_USER_HOME = "E:\Desktop\AGENTS-UP v2.0\.gradle-home"
E:\Desktop\AGENTS-UP` v2.0\.tools\gradle-8.13\bin\gradle.bat wrapper --gradle-version 8.13 --distribution-type bin
```

(This generates `gradlew`/`gradlew.bat` + wrapper jar; run once, committed.)

## 3. Build / test / APK

```powershell
.\gradlew.bat assembleDebug          # APK: app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat testDebugUnitTest      # unit tests (Robolectric Room tests included)
.\gradlew.bat testDebugUnitTest assembleDebug   # full Phase 10 check (305 tests)
.\gradlew.bat :app:installDebug      # needs a device/emulator on adb
```

Tip: redirect Gradle output to a log file (`… > build.log 2>&1`) before
filtering — piping build output through more PowerShell pipeline stages on
this machine has triggered flaky `ChildProcess.kill` aborts.

`local.properties` (`sdk.dir`) is machine-specific and gitignored.

## 4. Known environment notes

- `sdkmanager` on PATH fails under Java 8; rerun with `JAVA_HOME` → JBR.
- C: must stay clear: Gradle home, project, and tool downloads live on E:.
- Unit tests use Robolectric, whose ~150 MB `android-all-instrumented`
  runtime is resolved by Robolectric itself (default remote
  `repo1.maven.org`, observed very slow here). The build pins its cache to
  `%GRADLE_USER_HOME%/robolectric-deps` (see `app/build.gradle.kts`); it was
  pre-seeded once from the Google Maven mirror
  (`maven-central.storage-download.googleapis.com`) as a FLAT file
  (`android-all-instrumented-<ver>.jar`, exact name required).
- Dependency downloads route through the Google Maven Central mirror
  (first entry in `settings.gradle.kts`) because `repo.maven.apache.org`
  was measured at KB/s trickle speed on this network; `mavenCentral()`
  remains as fallback. Re-seed with curl if the API level under test changes:
  `curl.exe -o <deps>/android-all-instrumented-<ver>.jar <mirror-url>`.
- Emulator AVDs available: `CE_Test`, `Medium_Phone_API_36.1`.
- Emulator verification (2026-09-23, `CE_Test`, API 36, `-no-window
  -gpu swiftshader_indirect`): fresh v3 install; drove the full incremental
  session via `input tap` + `uiautomator dump` — first import
  (`New: 3`), identical re-import (`Unchanged: 3`), edited content
  (`Changed: 1 / Unchanged: 2`), moved content sharing a copy
  (`Duplicate: 1 / Unchanged: 1`, `refCount 2` on one physical file), partial
  archive (`Changed: 1 / Unchanged: 1` with unrelated material intact), and a
  corrupt ZIP failing cleanly with `Import failed: Invalid ZIP archive …
  Processed before failure: 0`. DB pulled with its WAL and inspected as
  `user_version 3`: `source_files` refCounts exact, `academic_files`
  `source_file_id`s non-NULL, and `files/source/` holding exactly the three
  referenced copies (auto-cleaned orphan from the changed edit). The
  CE_Test AVD uses a `<temp>` data partition (wiped on reboot) and needed
  its ROM memory raised (`-memory 3072`) plus TalkBack/SwitchAccess and
  `settings.intelligence` disabled to stop System UI ANRs. Use the Golden
  path with 3 GB+ when repeating.
- Phase 4 emulator session (2026-09-23, same AVD): installed the v4 build
  **over** the phase-3 data — the on-device 3→4 migration preserved every
  pre-existing academic row and created `document_chunks`/`extraction_meta`
  (`user_version 4` verified). Imported `phase4.zip` (a `Study.zip` module
  with a real 2-page PDF, `.docx`, `.pptx`, `.txt`, a garbage PDF, and an
  OLE `.doc`) → 5 files EXTRACTED (8 chunks; PDF=1 chunk per page, PPTX=1
  per slide), others FAILED *honestly* (`Not a valid PDF: no objects
  found.`, `Legacy/unsupported binary format (doc)`). Live-on-device
  (rooted) `sqlite3` on the app DB is the reliable inspection path — Room
  uses WAL, so pulling only the `.db` file gives a stale snapshot; pulling
  requires `adb root` + `cp`/`pull`, and PowerShell redirect must not be
  used on `adb exec-out` binaries (it applies CRLF translation). FTS
  verification: `SELECT rowid, text FROM document_fts WHERE document_fts
  MATCH 'optimization|backpropagation|gradient'` matched exactly the
  expected chunk rowids. Re-importing `phase4-v2.zip` (edited PDF)
  → `Changed: 1`, `Extracted: 1`, `Skipped (unchanged): 4`; the edited
  file's stale chunk/FTS rows were replaced with new ones (rowids 9,10;
  `MATCH 'forward'` hits only the new text). Device-found bugs fixed
  before wrap-up: Android `DocumentBuilderFactory` rejects Apache XXE
  features (now applied best-effort, `runCatching`, with
  `setExpandEntityReferences(false)` masked) and PDF object-skip (off-by-one
  at EOF — `start < lastStreamEnd`). FTS on a real device: FTS5 absent,
  FTS4 present with NO `rank` column → `FtsIndex.search` catches and
  retries an unranked `MATCH` (exercised both in tests and on device); see
  `docs/ARCHITECTURE.md` § Extraction & FTS indexing.
- Phase 5 emulator session (2026-09-24, `CE_Test`, API 36, windowless):
  installed the Phase 5 build over the Phase 4 data (all 8 chunks + FTS
  rows intact). Search tab: navigated via the bottom bar, typed `gradient`
  → 3 results ranked `ai.pptx › neural.pdf › readme.txt` with type chips,
  `PAGE/SLIDE` refs, highlighted excerpts, relevance indicators, and a
  working detail dialog (open/close). Nonexistent terms ("qmn", "fa")
  render the honest `No results for "…"` state; the clear-query × works.
  Semester scope verified end-to-end: seeded an empty "Semester 2" in the
  DB (root sqlite3), switched to it in Settings → the Search scope headline
  updated and a query produced the `NO_INDEXED` state ("No indexed academic
  content in this semester yet…"); switched back and the same query
  re-scoped live to Semester 1. **FTS4 `AND` discovery** (the reason the
  search query form is the way it is): on this device build, bare operator
  keywords behave inconsistently — `OR`, `NOT`, `NEAR` and space-implicit
  AND all work, but a bare `AND` keyword is parsed as a *literal term*
  (`forward AND network` matched only a row containing the word “and”);
  `forward* network*` (the app's exact expression) ANDs correctly, so
  `SearchQuery` deliberately emits space-separated prefixes (identical
  behavior on real FTS5). Device input caveat: `adb shell input text`
  only reliably injects into the Compose field on a fresh process/IME
  handshake and then drops most characters, so typing-driven scenarios
  beyond a few keystrokes were replaced by driving the production query
  path directly against the device index (`forward* network*` → only the
  PDF chunk containing both terms; `print*` → the `.py` chunk). Real-phone
   keyboard/IME behavior is untested.
- Phase 6 emulator re-verification (2026-09-26, `CE_Test`, API 36,
  windowless, current `app-debug.apk` over existing Phase 6 data):
  Quiz idle → START QUIZ → 10 questions answered via `input tap`
  (all three kinds; one deliberate wrong answer) → INCORRECT/CORRECT
  feedback with `SOURCE file · PAGE n` + verbatim excerpt each time →
  RESULTS `4/10 · 40% · +40 XP · STREAK 2 DAYS` with per-question
  review rows → `am force-stop` + relaunch → idle restored from real
  rows (XP 140, 2 completed sessions), no crash. On-device sqlite3
  (via stdin pipe — inline `adb shell` quoting breaks on this box)
  confirmed both `quiz_sessions` rows and per-question persistence.
- Phase 7 emulator session (2026-09-26, `CE_Test`, API 36, windowless,
  current `app-debug.apk` installed over Phase 6 data): install ran the
  v5→v6 migration (`user_version 6`, quiz rows intact). Listener tab:
  IDLE renders with live scope; START → rationale → system permission
  dialog → grant → RECORDING with live timer + real amplitude readout
  (1394/32767 on the emulator mic). `dumpsys` verified the foreground
  service (`isForeground=true`, microphone type, `listener_recording`
  channel, Stop action). PAUSE (46 s) → RESUME → STOP → session
  COMPLETED with 2 PENDING segments; on-device sqlite3 shows
  `(0, 0 ms, 46853 ms)` + `(1, 53164 ms, 7336 ms)` — the 6.3 s paused
  gap exactly excluded — plus a 677 KB app-private `.m4a`
  (`-rw-------`). Segment detail dialog shows "Transcript pending."
  with the no-STT disclaimer. `am force-stop` + relaunch → IDLE with
  1 persisted session, no FATALs. Debugging notes: `adb shell
  uiautomator dump` intermittently returns "null root node" while a
  permission dialog animates (retry the dump); inline `adb shell`
  quoting mangles sqlite3 statements on this box — pipe SQL via stdin
  instead. Emulator CANNOT prove real-mic quality, OEM battery-killer
  behavior, or Bluetooth routing.
- Phase 8 emulator session (2026-09-26, `CE_Test`, API 36, windowless,
  current `app-debug.apk` installed over Phase 7 data): install ran the
  v6→v7 migration (`user_version 7`, all Phase 5/6/7 rows intact).
  Cards tab: renders **real Phase 8 UI** (not ComingSoon) —
  `FLASHCARDS` title, deck/semester/total/due/suspended counts,
  Start Review. Deck built from seeded Phase 7 data (4 chunk cards +
  1 READY segment card; PENDING/FAILED excluded). Start Review → Reveal
  → back text + citation (`lecture1.pdf · PDF · PAGE 1`) → AGAIN/HARD/
  GOOD/EASY → GOOD grades card (interval 0→1, dueAt tomorrow day-
  boundary). Force-stop mid-review (2/3 cards graded) → relaunch →
  deck shows "Due now: 2" + **Resume Review** → resumes at next card
  (previously graded cards excluded), counts preserved (reviewed/retained).
  Complete remaining 2 cards (GOOD) → Results: Reviewed 3, Retained 3,
  100%. DB verified: 2 COMPLETED sessions, 4 events, all cards
   intervalDays=1, dueAt=tomorrow day-boundary. No FATAL crashes.
- Phase 9 emulator session (2026-09-27, `CE_Test`, API 36, current
  `app-debug.apk` installed over Phase 8 data, no migration):
  dashboard showed the seeded device's real values (`Level 1 - 20 XP`,
  `Total XP: 20`, `20 / 100`, `Streak: 1 day` from 4 GOOD events and 0
  quiz rows - verified against independent day-index math). Resumed
  review, graded one EASY -> 28 XP / 28-100 / 2-day streak; seeded one
  completed 40-XP quiz row -> 68 XP / 68-100 / streak 2; force-stop +
  relaunch preserved everything; no app FATALs. Environment note: a
  preinstalled `com.shadowbody.app` repeatedly stole window focus and
  was disabled (`pm disable-user`) before driving taps; `uiautomator
  dump` still returns "null root node" transiently (retry the dump).
- Phase 10 emulator session (2026-09-27, `CE_Test`, API 36, current
  `app-debug.apk` installed over Phase 9 data, no migration,
  `user_version` stays 7, all rows survive): recorded a real ~2-minute
  session (2 segments, live amplitude 141), stopped, tapped TRANSCRIBE
  - both segments FAILED honestly ("No on-device speech engine is
  installed. Audio stays on the device."), UI summary + per-row
  reasons + DB agree, seeded session untouched. Force-stop + relaunch
  preserved rows; Cards tab intact (deck 5, due 5). No app FATALs
  (only the pre-existing unrelated `droid.bluetooth` daemon abort).
- **REAL DEVICE TESTING: NOT PERFORMED.**
