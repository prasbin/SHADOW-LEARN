# SHADOW LEARN — FINAL PRODUCT-EVOLUTION REPORT

**Date of report:** 2026-11-04
**Work commit:** `39e269f0c092e6ebf72964e7ed236d5b9bd65b20` — `feat: add direct upload, large-corpus paging, assistant slice and home fix`
**Pre-pass baseline (origin/main):** `91ea7b4e047155ab8f5bb1bcf16dcc65b5f25ace`
**Final report commit:** `9150b0e42e1d9a5b6ef9eaa99c585312b0e5be2d` — `docs: add final product evolution report`
**Final local HEAD at report time:** `9150b0e42e1d9a5b6ef9eaa99c585312b0e5be2d`

---

## Honest status labels used in this report

| Label | Meaning |
| --- | --- |
| IMPLEMENTED AND TESTED | Implemented and verified by automated tests, plus device verification where stated. |
| IMPLEMENTED BUT NOT DEVICE-TESTED | Implemented with full unit/integration coverage, but no on-device verification of that specific flow. |
| PARTIALLY IMPLEMENTED | Some elements shipped; remaining parts explicitly deferred or blocked. |
| DESIGNED BUT NOT IMPLEMENTED | Design contract only; no production code changed for it. |
| BLOCKED | Cannot proceed in the current environment (credentials/access). |
| DEFERRED | Deliberately not implemented now; documented reason. |

---

## Completed items

### 1. Home navigation bug — IMPLEMENTED AND TESTED (device-verified)

- **Root cause:** the Home tab's saved back stack could restore a HOME-originated
  destination (the academic assistant), so tapping the Home tab reopened the
  assistant instead of the dashboard.
- **Fix:** `NavTab.HOME` no longer restores saved state
  (`NavTab.restoresSavedState()` → `false`); the assistant is a standalone
  destination (`tabForRoute(Routes.ASSISTANT)` → `null`, not a sixth tab).
- **Fix:** bottom-nav `onClick` now reads the live top route
  (`navController.currentBackStackEntry?.destination?.route`) instead of a
  captured composition value.
- **Verification:** `NavigationTest` home regression guards (incl.
  `homeNeverRestoresAssistantStack`); full suite green; on device
  (`YPA6RWNB7L7HPBRK`): Assistant → Home → Dashboard, Assistant → Academic →
  Home → Dashboard, and three consecutive Home taps all returned to Dashboard.
- **Label: IMPLEMENTED AND TESTED** (unit + device).

### 2. I8 Academic Assistant — first vertical slice — IMPLEMENTED AND TESTED

- Entry: `ACADEMIC ASSISTANT` card on SYSTEM HOME (no new tab; standalone
  destination).
- Typed input only; intents `Explain [Topic/Term]` and
  `What should I revise next?`; `UNSUPPORTED` intent for anything else.
- Reuses the intelligence layer (I1–I7): grounded `VERIFIED` source cards,
  `GENERATED` framing kept distinct, no invented facts/weaknesses/mastery/
  confidence, no concatenated excerpts presented as a summary.
- Navigation: OPEN SOURCE → `Routes.hierarchyWeek(weekId)`;
  PRACTICE → `Routes.practiceQuiz(fileId)`.
- Honest insufficient-evidence states.
- **Label: IMPLEMENTED AND TESTED** (AssistantEngine 9 unit tests,
  AssistantIntegrationTest 3, AssistantViewModel, AssistantScreen).

### 3. Direct multi-file academic upload — IMPLEMENTED AND TESTED (engine) / NOT DEVICE-TESTED (picker e2e)

- Supports PDF / DOCX / PPTX / TXT / MD; multi-select; streaming copy into
  app-private storage; SHA-256 semester-scoped dedup; per-source states
  (QUEUED/IMPORTING/PROCESSING/READY/FAILED); retry; failure isolation;
  process-death safety; ZIP import preserved.
- **Verification:** `DirectImportTest` (6 tests) green — storage+extraction →
  READY, searchable, duplicate rejection, name-collision paths, unsupported
  rejection, module/week creation. Real PDF/DOCX/PPTX bytes covered by
  `ExtractorsTest` / `ExtractionRepositoryTest`.
- **Not device-verified:** end-to-end selection through the Android document
  picker on the physical device (manual uiautomator attempts did not complete a
  selection).
- **Label: IMPLEMENTED AND TESTED** for the import pipeline; **DEFERRED** for the
  physical-picker e2e (optional remaining device test).

### 4. Scale to 1,000+ sources per notebook — IMPLEMENTED AND TESTED

- Bounded listing (`observeFilesLimited`) + paged reads (50/page, LOAD MORE);
  capped search results.
- `LargeCorpusTest` (1,000 sources): `seedMs=6595 searchMs=44 retrieveMs=9
  pageSize=50 hits=50`.

### 5. Lecture / transcription capability audit — PARTIALLY IMPLEMENTED (+ DEFERRED language/math work)

- Recording: `MediaRecorder` one file/session, pause/resume segments with
  monotonic clock, foreground service, process-death recovery, no duration cap
  (2h lecture ≈ one AAC file; storage is the practical limit).
- Transcription: on-device Vosk (`vosk-model-small-en-us-0.15`, bundled,
  offline, English only); non-English tags refused with a clear reason; formulas
  transcribed as words, no reconstruction.
- **DEFERRED:** Nepali/Devanagari/code-switching model; mathematical-equation
  recognition; cloud STT; speech-to-assistant input.
- Documented in `docs/LECTURE_CAPABILITIES.md`; honest capability card added to
  `ListenerScreen`.

### 6. Home UX hardening — IMPLEMENTED AND TESTED

- `DashboardUiState` loading/error, safe-flow catches, snapshot error state,
  `refresh()` retry, Dashboard loading + error/retry UI.

### 7. Full suite and build — IMPLEMENTED AND TESTED

- Command: `:app:testDebugUnitTest --rerun-tasks` → **542 tests, 50 suites, 0
  failures, 0 errors, 0 skipped**.
- `:app:assembleDebug` → BUILD SUCCESSFUL; APK `app/build/outputs/apk/debug/app-debug.apk`
  = **82,624,780 bytes (~82.6 MB)**; SHA-256 `90D0874F36A750CDA32DB58843303A77F0A9D19305D1DCD3DB3203E90E19A575`.

---

## Measured results

| Metric | Value |
| --- | --- |
| Full unit suite | 542 tests / 50 suites, **0 failures, 0 errors, 0 skipped** |
| Large corpus (1,000 sources) | seedMs=6595, searchMs=44, retrieveMs=9, pageSize=50, hits=50 |
| Debug APK | 82,624,780 bytes (~82.6 MB), SHA-256 `90D0874F36A750CDA32DB58843303A77F0A9D19305D1DCD3DB3203E90E19A575` |
| Installed build hash match | Installed APK SHA-256 == build APK SHA-256 (`90D0874F…A575`) |

---

## Device verification (honest claims only)

- Device: `YPA6RWNB7L7HPBRK` (Redmi Note 14 5G, Android via MIUI).
- Physical display 1080x2400 @ 450 dpi; a forced-res check (`adb shell wm size
  1012x2400`) verified layout at ~360 dp; display size reset afterwards.
- Rotation locked to portrait during checks; `accelerometer_rotation` restored to `1`
  and `user_rotation` to `0` after testing.
- Home/Assistant/360 dp layout verified via `uiautomator` dumps and screen text;
  no FATAL exceptions observed in the captured logcat window.
- **No unsupported capability was claimed.** English-only transcription,
  no Nepali/Devanagari/code-switching, no formula reconstruction — all stated
  explicitly.

---

## GitHub backup — **BLOCKED**

- `git push origin main` failed: `fatal: unable to access 'https://github.com/prasbin/SHADOW-LEARN.git/': The requested URL returned error: 403`.
- Remote: `Permission to prasbin/SHADOW-LEARN.git denied to krishna-sa-r.`
- SSH remote also unavailable: `git@github.com: Permission denied (publickey)`.
- `gh` CLI not installed; no GitHub token present in the environment.
- **Local work preserved:** HEAD = `39e269f0c092e6ebf72964e7ed236d5b9bd65b20`;
  remote `main` = `91ea7b4e047155ab8f5bb1bcf16dcc65b5f25ace` (unchanged).
- Once credentials/access for `prasbin` are supplied, run:

  ```bash
  git push origin main
  git rev-parse HEAD
  git status --short
  git ls-remote origin refs/heads/main
  ```

---

## Final state

- Local HEAD (final): `9150b0e42e1d9a5b6ef9eaa99c585312b0e5be2d`
- Work commit: `39e269f0c092e6ebf72964e7ed236d5b9bd65b20`
- Working tree: clean at time of final report generation.
- Remaining open item: GitHub backup (`BLOCKED` on credentials) and optional
  physical-device picker e2e (marked **DEFERRED**).

---

*Report generated after completing the SHADOW LEARN product-evolution pass.
All claims above use the honest labels listed at the top.*
