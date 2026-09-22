# SHADOW LEARN — PRASBIN 2.0

Personal offline-first university learning OS (native Android).

- Package: `com.prasbin.shadowlearn`
- UI: Jetpack Compose (Material 3, dark futuristic theme)
- Data: Room (SQLite) + DataStore + Storage Access Framework
- Status: **Phase 2 — academic database + ZIP ingestion.** No extraction,
  AI, quizzes, Listener Mode, or similarity checking yet (Phase 3+).

## Features (Phase 2)

- Hierarchical academic database: Year → Semester → Module → Week → File
  (Room v2, additive AutoMigration v1→v2).
- SAF ZIP import with honest progress, cancellation, and a result summary
  (`Imported N file(s) in W week(s) across M module(s)`).
- Recursive walk of nested module ZIPs at any depth with per-archive error
  isolation and zip-slip protection.
- Deterministic hierarchy planning: `Week N` folders, `Lecture(s)` /
  `Tutorial(s)` / `Workshop(s)` folders, wrapper stripping, transparent
  containers.
- Files copied into app-private storage with SHA-256 + size/mtime metadata;
  unsupported types skipped and reported, corrupt archives fail cleanly.
- Year/semester context shared between Settings and the Academic tab.

## Build

Requirements and exact commands: see [docs/SETUP.md](docs/SETUP.md).
Architecture and database / ingest pipeline: see
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Status-line: `.\gradlew.bat testDebugUnitTest assembleDebug` (34 unit tests,
including Robolectric Room migration + end-to-end ingest tests).

## Testing status

- **34 unit tests green**: format rules, walk/plan (nesting, wrappers,
  empty folders, duplicates, unsafe paths, backslash archives), Robolectric
  Room migration, and end-to-end ingest with real ZIP bytes.
- **Emulator verification performed** (`CE_Test`, API 36): fresh install of a
  semester ZIP produced the expected Module → Week → ClassType hierarchy;
  a corrupt ZIP failed cleanly with `Import failed: Invalid ZIP archive …
  Processed before failure: 0`, leaving no rows.
- **REAL DEVICE TESTING: NOT YET PERFORMED.**

## Current limitations

- XP / level / streak / progress are honest zeros until the Phase 6 engine.
- Export / Import of saved archives and deduplicating re-imports (Phase 3+).
- Quiz, Cards, Search, Listen tabs remain placeholders.

## Next

Phase 3 — incremental indexing/extraction groundwork and import dedupe.