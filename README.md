# SHADOW LEARN — PRASBIN 2.0

Personal offline-first university learning OS (native Android).

- Package: `com.prasbin.shadowlearn`
- UI: Jetpack Compose (Material 3, dark futuristic theme)
- Data: Room (SQLite) + DataStore + Storage Access Framework
- Status: **Phase 4 — extraction pipeline & full-text index.** Text,
  OOXML/.docx/.pptx, and PDF extraction with per-file, per-page chunks and
  an incremental FTS index (FTS5 auto-falls back to FTS4). No AI,
  quizzes, Listener Mode, or similarity checking yet (Phase 5+).

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

## Features (Phase 4)

- Extraction at import time, incremental and honest (Room v4: new
  `document_chunks` + `extraction_meta`; `MIGRATION_3_4`):
  - Hand-rolled extractors for UTF-8 text, Office Open XML
    (`.docx`, `.pptx`), and PDF (`FlateDecode` streams, `Tj`/`TJ`
    operators); `.rtf`/OLE `.doc`/`.ppt` are reported as unsupported, and
    garbage files fail with a real reason (`Not a valid PDF: no objects
    found.`) — per-file isolation means one bad file never blocks the
    import.
  - `document_chunks` gives each piece a `chunkIndex`, optional
    `pageNumber`, and `charCount`; a PDF is one chunk per page, a PPTX one
    chunk per slide, a DOCX one chunk per page break.
  - Re-import of the *same* file → `Skipped (unchanged)` (no re-extract).
    *Changed* file → old chunks + old FTS rows removed, re-extracted, and
    re-indexed in one transaction. Falls `Failed` files retry on the next
    import.
- Full-text search index without dependencies: `FtsIndex` probes for FTS5
  and falls back to FTS4 on engines that lack it (real devices compile
  SQLite without FTS5, so the FTS4 path *is* the production path). FTS
  rowids are chunk ids, so every hit resolves to a file.
- The DB can be inspected live (rooted emulator):
  `sqlite3 …/databases/shadowlearn.db` → `user_version=4`, exact
  `extraction_meta` rows, `document_chunks` with pageNumber, and
  `SELECT … FROM document_fts WHERE document_fts MATCH '…'` hit counts.

## Build

Requirements and exact commands: see [docs/SETUP.md](docs/SETUP.md).
Architecture and database / ingest / extraction pipeline: see
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Status-line: `.\gradlew.bat testDebugUnitTest assembleDebug` (**75 unit
tests**, including Robolectric Room migrations + end-to-end ingest,
reconciliation, and extraction tests).

## Testing status

- **75 unit tests green**: format rules, walk/plan (nesting, wrappers,
  empty folders, duplicates, unsafe paths, backslash archives),
  deterministic `Reconcile.classify` table, extractor codecs (txt/docx/
  pptx/pdf incl. garbage + truncated + legacy-binary inputs),
  incremental `ExtractionRepository` semantics (skip/change/fail),
  Robolectric Room migrations (v1→v4, v2→v4, v3→v4 against committed
  schemas), FtsIndex (FTS5 and FTS4 fallback paths), and end-to-end ingest
  runs with real ZIP bytes.
- **Emulator verification performed** (`CE_Test`, API 36): a fresh v4
  install seeded Year 2 / Semester 1 and the full incremental session was
  driven end-to-end via SAF — imports reconciled; extraction produced
  EXTRACTED/FAILED metadata and indexed 8 chunks across txt/pdf/docx/pptx;
  FTS `MATCH` returned correct rowid+text for `optimization`,
  `backpropagation`, `network`, `gradient`. v3→v4 migration ran on device
  preserving every pre-existing row; re-importing a *changed* archive
  re-extracted only the edited PDF (`Changed: 1`, `Extracted: 1`,
  `Skipped: 4`), replacing its chunk + FTS rows. DB inspected live as
  `user_version 4`. (Device-found bugs fixed: Android's XML factory
  rejecting Apache XXE features → best-effort feature application; PDF
  object-skip off-by-one at EOF; missing `/Filter /FlateDecode` in the
  test fixtures → extractor is spec-correct, fixtures fixed.)
- **REAL DEVICE TESTING: NOT YET PERFORMED.**

## Current limitations

- XP / level / streak / progress are honest zeros until the Phase 6 engine.
- Export / Import of saved archives is not yet implemented.
- Quiz, Cards, Search, Listen tabs remain placeholders.
- FTS4 has no `rank` column on this platform, so Phase 5 ordering must
  compute its own relevance (rowid order today).

## Next

Phase 5 — AI-powered search UI on the chunk index + ranking.