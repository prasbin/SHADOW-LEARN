# SHADOW LEARN — PRASBIN 2.0

Personal offline-first university learning OS (native Android).

- Package: `com.prasbin.shadowlearn`
- UI: Jetpack Compose (Material 3, dark futuristic theme)
- Data: Room (SQLite) + DataStore + Storage Access Framework
- Status: **Phase 3 — incremental import & reconciliation.** No extraction,
  AI, quizzes, Listener Mode, or similarity checking yet (Phase 4+).

## Features (Phase 3)

- Hierarchical academic database: Year → Semester → Module → Week → File
  (Room v3, additive AutoMigrations v1→v2→v3; new `source_files` store).
- Deterministic incremental import. Each re-import classifies every file:
  **New / Unchanged / Changed / Duplicate / Skipped / Failed**, with a
  per-import summary (content SHA-256 identity; position identity =
  semester + module-relative path).
  - Identical re-import → all `Unchanged`, no new rows, no new copies.
  - Edited file → `Changed` (old copy released, new copy stored).
  - Same content at a new path → `Duplicate` (reuses one physical copy;
    `refCount` tracked app-wide and orphan copies garbage-collected).
- SAF ZIP import with honest progress, cancellation, and a result summary.
- Recursive walk of nested module ZIPs at any depth with per-archive error
  isolation and zip-slip protection (`Week N` folders, `Lecture(s)` /
  `Tutorial(s)` / `Workshop(s)` folders, wrapper stripping).
- Re-importing a partial archive never deletes unrelated material; corrupt
  archives fail cleanly ("Processed before failure: 0") leaving data intact.
- Year/semester context shared between Settings and the Academic tab.

## Build

Requirements and exact commands: see [docs/SETUP.md](docs/SETUP.md).
Architecture and database / ingest pipeline: see
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Status-line: `.\gradlew.bat testDebugUnitTest assembleDebug` (51 unit tests,
including Robolectric Room migrations + end-to-end ingest & reconciliation
tests).

## Testing status

- **51 unit tests green**: format rules, walk/plan (nesting, wrappers,
  empty folders, duplicates, unsafe paths, backslash archives),
  deterministic `Reconcile.classify` table, Robolectric Room migrations
  (v1→v3 and v2→v3 against committed schemas), and end-to-end ingest runs
  with real ZIP bytes covering first import, identical re-import, edited
  file, moved content, partial archive, and corrupt/failed archives.
- **Emulator verification performed** (`CE_Test`, API 36): a fresh v3
  install seeded Year 2 / Semester 1 and the full incremental session was
  driven end-to-end via SAF — first import `New: 3`, identical re-import
  `Unchanged: 3`, edited PDF `Changed: 1 / Unchanged: 2`, moved content
  `Duplicate: 1 / Unchanged: 1`, partial archive `Changed: 1 / Unchanged: 1`
  with unrelated material intact, and a corrupt ZIP failing cleanly with
  `Import failed: Invalid ZIP archive … Processed before failure: 0`. DB was
  inspected as `user_version 3` with linked `source_file_id`, per-content
  `refCount` (shared copy = 2 refs), and exactly the unreferenced-orphan-free
  physical files under `files/source/`.
- **REAL DEVICE TESTING: NOT YET PERFORMED.**

## Current limitations

- XP / level / streak / progress are honest zeros until the Phase 6 engine.
- Export / Import of saved archives is not yet implemented.
- Quiz, Cards, Search, Listen tabs remain placeholders.

## Next

Phase 4 — extraction pipeline (text/PDF) + full-text index ready for the
AI search layer.