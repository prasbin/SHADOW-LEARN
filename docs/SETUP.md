# Setup & Build — SHADOW LEARN (Phase 1)

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
- **REAL DEVICE TESTING: NOT YET PERFORMED.**
