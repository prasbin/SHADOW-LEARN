# SHADOW LEARN — PRASBIN 2.0

Personal offline-first university learning OS (native Android).

- Package: `com.prasbin.shadowlearn`
- UI: Jetpack Compose (Material 3, dark futuristic theme)
- Data: Room (SQLite) + DataStore + Storage Access Framework
- Status: **Phase 6 — rule-based Daily Quiz engine.** Text, OOXML/.docx/
  .pptx, and PDF extraction with per-file, per-page chunks and an
  incremental FTS index (FTS5 auto-falls back to FTS4), fast honest
  academic search, and now a fully offline quiz engine over the current
  semester: true/false, fill-the-blank and multiple choice generated from
  verbatim excerpts, every option a real phrase from the material, every
  question citing its source. No AI, Listener Mode, or similarity
  checking yet (Phase 7+).

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

## Features (Phase 5)

- **Academic search, fully local.** A dedicated Search tab over the FTS
  index (same `FtsIndex` abstraction from Phase 4, so FTS5 *and* the
  production FTS4 path are both exercised).
  - Query sanitization (`SearchQuery`) — lowercase, dedupe, cap 8 terms ×
    64 chars, keep only `\p{L}\p{N}` letters; output is always
    space-separated *prefix* terms (`gradient* descent*`). A trailing `*`
    on every term doubles as injection defense: reserved FTS operator
    words (`or`, `and`, `not`) can never be parsed as operators.
  - Implicit-AND handling that works on every engine: the Android
    framework SQLite (API 36, verified on device) does **not** treat a
    bare `AND` keyword as an operator (it matches the literal word
    “and”), so the expression form deliberately stays FTS4's
    space-implicit AND — identical results on real FTS5 too.
  - Deterministic **“SHADOW LEARN heuristic relevance”**
    (`RelevanceScorer`): `(100·coverage + 12·exact + 6·prefix +
    80·fileNameHit + 30·moduleHit) / (1 + ln(1+len)/10)`. Long chunks are
    normalized; ties break `score ↓ → fileName ↑ → chunkId ↑`.
  - Spring-scoped results (`SearchDao.resolveChunks` joins chunk → file →
    module → semester in one query): searching never crosses into another
    semester's material, and the scope headline (“Year 2 · Semester 1”)
    tracks the Settings tab live, re-running the query on change.
  - Excerpts with highlight offsets generator (`ExcerptGenerator`): a
    60-char lead-in + 100-char window around the first hit, collapsed
    whitespace, sanitized control characters, bounded merged highlight
    ranges so the UI can render matches bold.
  - Honest UI states: EMPTY / NO_SEMESTER / NO_INDEXED / SEARCHING /
    RESULTS / NO_RESULTS / ERROR — a query in a semester with no indexed
    content says exactly that.
- Search UI in the dark futuristic SYSTEM identity: result cards with
  type chips (PDF/PPTX/DOCX/TXT), `PAGE n` / `SLIDE n` refs, highlighted
  excerpt, relative relevance bar, tap-to-open detail dialog, and a clear
  (A-) button.

## Features (Phase 6)

- **Rule-based Daily Quiz, fully offline** (Room v5: `quiz_sessions` +
  `quiz_questions`, `MIGRATION_4_5`). A quiz is generated deterministically
  from the current semester's chunk pool:
  - `QuestionGenerator` — three question kinds from one pool:
    **TRUE/FALSE** (mutation of a verbatim sentence), **FILL THE BLANK**
    (a term blanked from a sentence), and **MULTIPLE CHOICE** (a verbatim
    sentence as the answer with corpus-sourced distractors). Every option
    is a real phrase from the indexed files — never invented — and each
    question records its source file/type/page (+ the exact verbatim
    excerpt as the citation).
  - Complexity-aware `QuizPlanner`: chunks eligible for each kind are
    matched, capacity-bounded (≤3 questions/chunk), a recency ring skips
    the last ~30 completed chunks, pass-2 round-robins top-ups so a small
    pool still fills the session, and chunk slot rotation (`chunkId % 3`)
    guarantees type variety across truncated sessions.
  - Deterministic `Random(seed)` per session (seed persisted); at most
    one in-progress session resumes across process death.
  - Honest scoring: `+10 XP` per correct answer, streak computed from
    real completion timestamps only (`streakFrom`), and a persisted
    summary — SCORE / BEST / XP / STREAK on the idle screen.
- Quiz UI in the SYSTEM identity: scope headline (tracks Settings live),
    RULES, LENGTH 5/10/15 chips, question cards with type chips
    (`TRUE / FALSE`, `FILL THE BLANK`, `MULTIPLE CHOICE`), option cards,
    progress `QUESTION n OF m` + `SCORE x/y`, CORRECT/WRONG feedback with
    the verbatim citation, a results screen (`10/10`, `100%`, XP, STREAK)
    with per-question review rows and NEW QUIZ, and honest empty states
    (NO_SEMESTER / NO_INDEXED / not enough depth to build a quiz).
- Quiz is removable/verifiable end-to-end: generator/planner/repository
  are testable without Android; repository/DAO are exercised over a real
  Room + sqlite-jdbc engine.

## Build

Requirements and exact commands: see [docs/SETUP.md](docs/SETUP.md).
Architecture and database / ingest / extraction pipeline: see
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Status-line: `.\gradlew.bat testDebugUnitTest assembleDebug` (**164 unit
tests**, including Robolectric Room migrations + end-to-end ingest,
reconciliation, extraction, Search, and the quiz engine over a real FTS4
index).

## Testing status

- **127 unit tests green**: format rules, walk/plan (nesting, wrappers,
  empty folders, duplicates, unsafe paths, backslash archives),
  deterministic `Reconcile.classify` table, extractor codecs (txt/docx/
  pptx/pdf incl. garbage + truncated + legacy-binary inputs),
  incremental `ExtractionRepository` semantics (skip/change/fail),
  Robolectric Room migrations (v1→v4, v2→v4, v3→v4 against committed
  schemas), FtsIndex (FTS5 and FTS4 fallback paths), and end-to-end ingest
  runs with real ZIP bytes. Phase 5 adds 52 tests: query parsing/
  sanitization, heuristic scoring determinism, excerpt generation, and
  `SearchRepository` end-to-end against a real in-memory FTS4 index
  (scoring order, ties, scope isolation, page/slide refs, no-indexed
  reporting) plus FTS5-path tests (mult-term implicit AND and injection
  sanitization on real sqlite-jdbc FTS5).
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
- **Emulator verification performed — Phase 5 Search** (`CE_Test`,
  API 36): Search tab renders in the SYSTEM identity; query “gradient”
  returned 3 results ranked ai.pptx › neural.pdf › readme.txt with type
  chips, `PAGE/SLIDE` refs, highlighted excerpts, relevance indicators
  and a working detail dialog; nonexistent terms show an honest
  “No results” state; switching semester in Settings live-switched the
  scope headline and produced the correct NO_INDEXED state for the empty
  semester (repo-scope, never cross-semester results); adb text-input
  swallowing quarantined the dex/query typing but the production query
  path was exercised directly against the device index: `forward*
  network*` (implicit AND, the exact sanitized expression) hits only the
  PDF chunk containing both terms, `print*` hits the `.py` chunk, and
  single prefixes behave identically. ALSO verified on device: this
  Android build's FTS4 parses bare `OR`/`NOT`/`NEAR` and space-implicit
  AND but **not** a bare `AND` keyword — hence the expression form.
- **REAL DEVICE TESTING: NOT YET PERFORMED.**
- **Emulator verification performed — Phase 6 Quiz** (`CE_Test`,
  API 36): a fresh install (post `pm clear`) rebuilt the semester via SAF
  (Year 2 / Semester 1, 8 indexed chunks from the txt/pdf/docx/pptx
  fixtures) and a full 10-question quiz was driven end-to-end over the
  persisted session plan: all three kinds appeared (TRUE/FALSE, FILL THE
  BLANK, MULTIPLE CHOICE), every question verified CORRECT feedback plus
  its verbatim citation (`SOURCE … PAGE n` / `SLIDE n`), SCORE tracked
  1/1→10/10, RESULTS rendered `10 / 10 · 100% · XP +100 · STREAK 1 DAYS`
  with per-question review rows (`… CORRECT · neural.pdf · PDF · PAGE 2`,
  `readme.txt · TXT`, etc.), NEW QUIZ returned to an idle summary showing
  SCORE 10/10, BEST 10, XP 100, STREAK 1 DAYS, 1 completed session.
  Live DB inspection confirmed `quiz_sessions`: `total=10, correct=10,
  xp=100, streak=1, status=completed` and that **every**
  `quiz_questions.userAnswer == correctAnswer` with `isCorrect=1`, TF
  rows persisting `optionsJson=NULL` (UI renders the fixed TRUE/FALSE
  pair) and fill/MCQ rows persisting the verbatim option list. (The
  empty-state guard was also exercised: a single 1-chunk import could not
  build a quiz → honest “add more material” state; Dashboard XP/level/
  streak stay honest zeros until the progression engine.)

## Current limitations

- XP / level / streak / progress on the Home dashboard are honest zeros
  until the progression engine (quiz XP/streak are persisted in the quiz
  tables already).
- Export / Import of saved archives is not yet implemented.
- Cards, Listen tabs remain placeholders.
- Search covers the current semester's indexed *chunks* only; unindexed
  file types (`.rtf`, OLE `.doc`, garbage files) are explained per file by
  the extraction metadata and never silently “match nothing”.

## Next

Phase 7 — the ONE next step (see the Phase 6 hand-off prompt).