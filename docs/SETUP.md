# Setup & Build — SHADOW LEARN (Phase 4)

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
.\gradlew.bat testDebugUnitTest assembleDebug   # full Phase 4 check (75 tests)
.\gradlew.bat :app:installDebug      # needs a device/emulator on adb
```

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
- **REAL DEVICE TESTING: NOT YET PERFORMED.**
