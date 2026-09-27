# Architecture — SHADOW LEARN

> **UI identity requirement (project-wide):** the app must keep its original
> dark, futuristic academic RPG/"SYSTEM" interface (inspired by the web
> novel/comic *Solo Leveling*'s SYSTEM concept as a *general design
> reference only*). No copyrighted artwork, characters, names, or
> screenshots from any franchise may be used. This applies to every UI
> phase, including search/browse screens.

## Stack

Native Kotlin. Single `:app` module by design — feature modules
(`core-data`, `core-ingest`, …) are introduced only when they carry real
functionality. Current layers inside `:app`:

```text
com.prasbin.shadowlearn
├── MainActivity            # edge-to-edge host, theme wiring
├── navigation/             # Routes + AppNav (bottom bar, 8 destinations)
├── ui/
│   ├── theme/              # dark futuristic Material 3 theme
│   ├── components/         # SectionCard, StatRow
│   ├── screens/            # Dashboard, Academic, Settings, Search + 4 placeholders
│   ├── dashboard/          # DashboardViewModel (DB counts + settings)
│   ├── settings/           # SettingsViewModel (CRUD years/semesters, prefs)
│   ├── search/             # SearchViewModel (debounce/flatMapLatest state machine)
│   └── ingest/             # IngestViewModel (academic context + import state)
├── data/
│   ├── AppContainer        # minimal service locator (no DI framework yet)
│   ├── db/                 # Room entities, AcademicDao, ExtractionDao, SearchDao, ShadowLearnDatabase
│   ├── extract/            # PlainText / OOXML / PDF extractors, TextChunker
│   ├── search/             # SearchQuery, RelevanceScorer, ExcerptGenerator, SearchRepository, FtsIndex
│   ├── ingest/             # IngestFormat, ZipWalk, HierarchyPlan, IngestRepository, ExtractionRepository
│   └── settings/           # SettingsRepository (DataStore)
└── util/                   # formatBytes (unit-tested)
```

## Database (v7)

Tables: `academic_years` → `semesters` → `modules` → `weeks` →
`academic_files`, all with `CASCADE` deletes and FK indices.
`academic_files` carries `sha256`, `indexed`, size/mtime, `classType`
(`LECTURE` / `TUTORIAL` / `WORKSHOP` / `OTHER`) and `relativePath` (the
logical path inside its module archive).

v3 adds `source_files`, the content-addressed store that backs the
Phase 3 reconciliation engine:

- One row per unique byte-content (SHA-256 keyed, global across all
  semesters), columns: `sha256` (unique), `storedPath`
  (`<filesDir>/source/<sha256>`), `fileSize`, `refCount`, `createdAt`.
- `academic_files.sourceFileId` is a plain nullable column referencing
  `source_files.id`. It is deliberately **not** a Room `@ForeignKey`:
  deleting a reference row must never cascade-delete content (only a zero
  `refCount` may), and v1/v2 rows are upgraded with `NULL` until the next
  matching import adopts them.
- `refCount` is recomputed from `academic_files` after every import, then
  zero-ref rows and their physical files are deleted.

v4 adds the Phase 4 extraction model:

- `document_chunks`, the consumer-facing document unit (Phase 9 flashcard
  cards and Phase 6 quiz materials operate on these). Columns:
  `id` (autoincrement — this is the FTS rowid), `academicFileId` (FK,
  `CASCADE`), `chunkIndex`, nullable `pageNumber`, `text`, `charCount`,
  `createdAt`; indices on `(academicFileId, chunkIndex)`.
- `extraction_meta`, one row per file's last extraction attempt:
  `academicFileId` (PK + FK `CASCADE`), `sha256`, `status`
  (`EXTRACTED` / `FAILED`), `format` (`pdf`/`docx`/`pptx`/`txt`/`unknown`),
  `charCount`, `chunkCount`, nullable `error`, `startedAt`, `completedAt`.
- The full-text `document_fts` virtual table is **not** in Room's schema
  (it is not an entity): `FtsIndex` creates it on first use against the
  live SQLite engine, so Room validation never sees a non-entity virtual
  table. FTS rowid == `document_chunks.id`.

v5 adds the Phase 6 quiz model:

- `quiz_sessions`: `semesterId` (plain column, no FK — quiz history
  survives semester re-imports), `seed` (persisted determinism),
  `totalQuestions`, `correctCount`, `xpEarned`, `streak`, `status`
  (`in_progress` / `completed`), `startedAt`, `completedAt`; indices on
  `(semesterId)` and `(status)`.
- `quiz_questions`: `sessionId` (FK `CASCADE`), `position`, plain
  `chunkId` / `academicFileId` columns (no FK, same reason), `questionType`
  (`TRUE_FALSE` / `FILL_BLANK` / `MCQ`), `prompt`, nullable `optionsJson`
  (NULL for true/false; verbatim option list otherwise), `correctAnswer`,
  nullable `userAnswer` / `isCorrect`, and the citation snapshot
  `srcFileName`, `srcFileType`, `srcPage`, `srcExcerpt` — so results and
  feedback never need to re-query the ever-changing corpus.
- `MIGRATION_4_5` is purely additive; Room schema `5.json` is committed
  and migration tests cover v1→v5 … v4→v5.

Future tables (pages/slides, topics, cards, transcripts, progress,
similarity) reference these primary keys — no destructive redesign
planned.

## Migration strategy

- Bump `DATABASE_VERSION` and ship a Room `AutoMigration` specification for
  additive changes; run and export the schema on the first build so the
  generated migration is created from the previous exported version.
  `fallbackToDestructiveMigration()` is **banned** — a missing migration
  must fail loudly, never wipe academic data.
- `exportSchema = true`; schemas committed under `app/schemas/`
  (`1.json`, `2.json`, `3.json`, `4.json`, `5.json`, `6.json`, `7.json`).
- v3→v4 is a **manual** `MIGRATION_3_4` (`document_chunks` +
  `extraction_meta` + two indices) with DDL copied verbatim from the Room
  ‑exported `4.json`/`3.json`, registered in the builder, and covered by a
  dedicated `migrate3ToCurrent` test that seeds a real v3 DB (from the
  committed `3.json`), inserts hierarchy + source columns, opens it under
  the current version, and asserts rows survive and extraction tables are
  empty/queryable.
- v4→v5 is a **manual** `MIGRATION_4_5` (both quiz tables + indices),
  likewise verbatim from `5.json`/`4.json` and covered by a
  `migrate4ToCurrent` test; v1→current / v2→current / v3→current
  migrations each pass straight through it.
- v5→v6 is a **manual** `MIGRATION_5_6` (both listener tables + indices),
  verbatim from `6.json`/`5.json` and covered by a `migrate5ToCurrent`
  test that seeds v5 rows (academic + quiz) and asserts they survive with
  listener tables empty/usable.
- v6→v7 is a **manual** `MIGRATION_6_7` (Phase 8 flashcard tables:
  `flashcard_decks`, `flashcards`, `flashcard_review_sessions`,
  `flashcard_review_events` + indices), verbatim from `7.json`/`6.json`
  and covered by a `migrate6ToCurrent` test that seeds v6 rows (academic
  + quiz + listener) and asserts they survive with flashcard tables
  empty/usable.
- Migration tests: `DatabaseTest` asserts the current baseline
  (`schemaVersion_isSeven`); `MigrationTest` drives the real v1→current,
  v2→current, v3→current, v4→current, v5→current, and v6→current paths
  with `MigrationTestHelper` using the committed `1..7.json` schemas —
  including the manual v1 `createSql` variant (which contains a
  `${TABLE_NAME}` placeholder the helper cannot substitute) — and asserts
  rows survive and later columns default correctly.

## Import pipeline (Phases 2–3)

One import = SAF `OpenDocument` stream → stage → walk → plan → write:
`IngestRepository.importStream` → `ZipWalk.walk` → `buildPlan` →
app-private copies (`<filesDir>/academic/<importId>/`) + Room rows.

**ZipWalk** expands the top archive and nested `.zip`s at any depth:
- The top stream is materialized to a temp file first so `ZipFile` validates
  the central directory upfront and reports an entry count for honest
  progress. Corruption there fails before any DB write.
- Each nested `.zip` is staged to its own bounded temp file, so one corrupt
  nested archive is isolated to that path instead of desynchronizing the
  parent stream.
- Logical paths are canonicalized to `/` separators (see backslash note),
  capped by `MAX_NESTING_DEPTH`, `MAX_ENTRY_BYTES`, `MAX_TOTAL_BYTES`,
  `MAX_ENTRIES`; zip-slip (`..`, absolute, drive-letter) entries are
  rejected — `isUnsafePath` checks both `/` and `\`.
- Temps live under cache and are deleted when the walk ends.

**IngestFormat** — deterministic format rules (no Android deps, unit-tested):
- Stored extensions in `STORED_EXTENSIONS`; everything else is skipped with a
  report (never fails the import).
- `Week N` (also `week_N`, `WEEK-N`) segments select the week number.
- `Lecture(s)` / `Tutorial(s)` / `Workshop(s)` folders select the class type
  for descendant files (a class directory also forces a week context).
- Ambiguous input falls back to documented defaults, never a guessed
  "certain" placement: unplaced files → `fallbackModule` / week 1
  titled "General".

**HierarchyPlan** turns the flat walked list into Module → Week → ClassType:
1. A single shared top-level wrapper directory is stripped (never if the top
   is a `.zip` or looks like a module root: a week/class-type direct child).
2. A nested `.zip` at semester-root level opens a module named after its
   stem; a nested `.zip` elsewhere with a week-matching stem selects that
   week; otherwise it is a transparent container.
3. Week-matching directories select that week (same number merges); empty
   week directories still create the week.
4. Class directories set the class type; other directories are transparent
   except a top-level one, which opens a module (empty modules still exist).
5. Names are preserved verbatim (trimmed only).

**IngestRepository** — one import = one `<importId>` tree; every stored file
is traceable via `relativePath`. SHA-256 is computed from the staged bytes
during the copy. Failed files leave no record; successful entries are kept
(partial imports are resumable by re-importing).

## Reconciliation (Phase 3)

A re-import reconciles its archive against the existing semester instead of
blindly duplicating rows. Position identity is `(semesterId, relativePath)`;
content identity is SHA-256. Per file, `Reconcile.classify` applies, in order:

1. Row at that position with the **same** `sha256` → `UNCHANGED`.
   If the row is a legacy (pre-v3) row with `sourceFileId = NULL`, it is
   **adopted**: the still-present app copy is linked to the content store
   (fallback: re-copy from the archive) so legacy rows become first-class
   refs on re-encounter.
2. Row at that position with a **different** `sha256` → `CHANGED`: the row is
   updated to the new content (new physical copy; old copy is orphan-cleaned
   later). The prior bytes are **not** inspected further.
3. No row at that position, but identical content **already exists in the
   same semester** (checked in the semester, not app-wide) → `DUPLICATE`: a
   new reference row reuses the existing physical copy (also covers moves
   that kept the bytes).
4. Otherwise → `NEW`: new row + new physical copy.

Unsupported/missing staged files → `SKIPPED` (reported, never a failure);
store I/O errors → `FAILED` (the file is left untouched).

**Referential semantics:**
- **No move/delete inference.** A re-import never deletes rows it does not
  touch; an archive containing only `Programming.zip` leaves `AI` untouched.
  A moved file surfaces as `DUPLICATE` at the new position (the old position
  keeps its row until explicitly cleared).
- Logical file names/paths may normalize per `relativePath` on `CHANGED`
  updates; note that a pre-existing row whose file *name* changed keeps its
  old identifiers unless the SHA also changed.
- One physical copy per unique content app-wide, tracked by `source_files`,
  regardless of how many rows reference it. After each import
  `refreshRefCounts` recomputes references and `removeOrphanedContent`
  deletes zero-ref rows + files (also public for tests). Changing one file
  back to earlier bytes restores the deduplicated single copy.

Limitations (documented, accepted):
- Legacy pre-v3 rows are never rewritten in place; the first re-import of
  their content produces reconciled rows beside them (existing duplicate
  behavior is preserved).
- Duplicate detection is per-semester, so the same content imported into a
  second semester creates a second content row (two physical copies). This
  is a deliberate correctness boundary, not a bug.

Execution is a ViewModel-scoped IO coroutine, not WorkManager — imports are
user-initiated foreground work needing live progress and cancellation.
Extraction runs in the same coroutine after each successful reconcile and is
incremental, so work is proportional to changed/new files. WorkManager stays
available for future background indexing (Phase 7+).

## Extraction & FTS indexing (Phase 4)

**Extractors** (`data/extract/`) are hand-rolled, dependency-light codecs for
the formats the target audience needs most; no Apache POI / PDFBox / Tika
(their JARs are multi-MB, drag the package past 30 MB, and PDFBox needs a
large font-package for real PDF text anyway):

- `PlainTextExtractor` — UTF-8 text (chatgpt-style PST), guarded by max-size.
- `OoxmlTextExtractor` — Office Open XML via `ZipFile`: `word/document.xml`,
  `ppt/slides/slide*.xml` (each slide = its own chunk, gets a pageNumber),
  and `word/document.xml` page break detection (`w:br w:type="page"` +
  `<w:p>` before it) splits documents by page. One ZIP read per part (seek
  back to `PPTX_SLIDE_OFFSET` per slide); no DOM retention.
- `PdfTextExtractor` — an explicit (spec-literal) PDF object walker:
  consecutive objects at the same xref offset → cross-reference "stub"
  objects collapsed so pages resolve to the content stream that flushes
  them (`Invalid PDF: cross-reference tables split a content stream.`). Content
  streams are un-FlateDecoded (replacing `FlateDecode` with `FlateDecode
  /DecodeParms <</Columns 256/Predictor 12>>` as needed) and decompressed
  with `java.util.zip`, `Tj`/`TJ`/`'`/`"` string operands concatenated,
  hex `<>` re-encoded, duplicate `(…)` dropped, page breaks emitted on each
  `BT` after the first. Errors are honest: `Not a valid PDF: no objects
  found.` for garbage / truncated files, `Legacy/unsupported binary format
  (doc): not supported.` for OLE .doc/.ppt | `.rtf` run (you still get
  proper .docx/.pptx).
- `TextChunker` — 512-char soft-window segments (a single huge PDF keeps
  its long token, one docx page-break section may be one token).

**Extraction schedule (`ExtractionRepository`)** — after import, per file
with a stored local copy, matched on `sha256`:

1. `EXTRACTED` **and same sha256** → **SKIPPED** (unchanged; no re-extract,
   no chunk rewrites; honest `Skipped (unchanged)`).
2. Otherwise → (re)extract: `document_chunks` rows and their FTS rows for
   that file are deleted (handling the *changed* case — stale chunks must
   leave the index), then all chunks are re-inserted in one transaction
   (`replaceExtraction`), then `FtsIndex.upsertChunks` drains the new rows
   into FTS in the same transaction. Failure anywhere records
   `FAILED` + a human `error`, keeps the file's old chunks if it previously
   extracted (deduped re-attempts don't duplicate content), and never fails
   the import. Sibling files are isolated from each other (per-file
   try/catch) — one bad PDF can't block the semester.

**FTS (`data/search/FtsIndex`)** — a single dependency-free index that
probes which `FTS5` extension the running SQLite actually compiled in and
falls back to a `FTS4` virtual table (`CREATE VIRTUAL TABLE document_fts
USING fts5(text)` → `USING fts4(text)`). Notes:

- Device established SQLite **without FTS5** and **with FTS4**; tests run
  on the JVM `sqlite-jdbc` (FTS5 available) *and* Robolectric uses the
  same in-memory engine as Robolectric's bundled SQLite (FTS4, no FTS5, no
  `rank`) — so the fallback is exercised in test *and* in the app.
- FTS4's `rank` is **not** available here (`no such column: rank`), so
  `search()` tries the ranked statement and, on any exception, re-issues an
  unranked `WHERE document_fts MATCH ?` (`SEARCH_UNRANKED_SQL`). ORDER-BYS
  are best-effort in the index; the Phase 5 `SearchRepository` owns all
  ranking above this seam.
- `FtsHit` = `(rowid, text)`; rowids are the autoincrement
  `document_chunks` ids, so hits resolve to chunks and `academicFiles` via
  plain Room joins.
- Because the FTS rowids are the chunk PKs and stale rows are deleted with
  their chunks, incremental re-extraction keeps the index exact (device:
  changed PDF re-extract dropped rowids 2,3 and re-indexed 9,10;
  `MATCH 'forward'` hit only the new content).

## Search (Phase 5)

The Search tab binds the Phase 4 chunk index to a focused,
spring-scoped, ranked result set. Everything is local; the query string
never reaches a Matcher that a user could break.

- **`SearchQuery`** — pure sanitization. Input is lowercased and split on
  `[\p{L}\p{N}]+` (capped: 8 terms × 64 chars, deduped preserving order).
  `toMatchExpression()` emits space-separated *prefix* terms, e.g.
  `gradient descent` → `gradient* descent*`. Prefix form is deliberate:
  - FTS4's implicit AND is the space operator — the Android framework
    build (verified on device, API 36) parses a bare `AND` keyword **not**
    as an operator but as a literal term (`risk AND return` matched only
    a row containing the word “and”), while `or* network*`-style queries
    AND correctly. The space form behaves identically on real FTS5.
  - A trailing `*` on every term means user-typed `or` / `and` / `not`
    can never collide with FTS operator keywords.
- **`FtsIndex.search(expression)`** returns `(rowid, text)` hits (same
  ranked→unranked fallback as extraction), cap 200 rows (`FETCH_LIMIT`).
- **`SearchDao.resolveChunks(semesterId, ids)`** — a single metadata
  JOIN (chunk → file → week → module → semester) returns `ChunkHitRow`
  (file name/type/path, class, page/slide, module, week, charCount).
  The `WHERE semesterId = ?` clause is the *scope guarantee*: results
  can never cross into another semester. `indexedChunkCount(semesterId)`
  / `observeIndexedChunkCount` feed state and (future) index progress.
- **`RelevanceScorer`** — deterministic “SHADOW LEARN heuristic”: per hit,
  `raw = 100·coverage + 12·exact + 6·prefix + 80·fileNameHit +
  30·moduleHit`, then `score = raw / (1 + ln(1 + max(1, charCount)) / 10)`
  (length-normalized so a 50-char chunk can out-rank a 5 000-char one that
  merely contains the term). Coverage = number of distinct query tokens
  matched textually (prefix-aware). Modules and repeated calls are
  deterministic — tests freeze the exact orderings.
- **`ExcerptGenerator`** — a ~60-char lead-in plus a ~100-char window
  around the first corpus match, with control-character sanitization,
  collapsed whitespace, ellipses, and a small bounded set of merged
  highlight ranges the UI maps to bold spans.
- **`SearchRepository.search(raw, semesterId)`** — orchestrates
  sanitize → FTS → resolve → score → sort (`score ↓, fileName ↑,
  chunkId ↑`), returns `Results(maxScore, hits)` or a `Failed(message)`
  outcome (whole search never crashes the screen). Runs on
  `Dispatchers.IO`.
- **`SearchViewModel`** — `combine(semesterId, query)`, debounce 250 ms,
  `flatMapLatest` producing a typed `SearchUiState`. States are explicit
  and honest: `EMPTY` / `NO_SEMESTER` / `NO_INDEXED` / `SEARCHING` /
  `RESULTS` / `NO_RESULTS` / `ERROR`; a semester with zero indexed chunks
  says so instead of fabricating zero results. Changing the semester in
  Settings re-scopes an in-flight query live (flatMapLatest cancels the
  old producer).
- **`SearchScreen`** — SYSTEM identity (header, scope headline, subtle
  glow accents, monospace data labels); a field with clear (×), type
  chips (PDF/PPTX/DOCX/TXT), `PAGE n` / `SLIDE n` refs, highlighted
  excerpts, relative relevance bars, and a tap-to-open detail dialog.

**Interface rule:** the search & browse UI keeps the dark futuristic
SYSTEM identity (see top note); the app has no `ACTION_VIEW`/`ACTION_SEND`
intent filter, so imports go through the in-app SAF picker only.

## Daily Quiz (Phase 6)

Quiz data lives in Room v5 (`quiz_sessions`, `quiz_questions`; additive
`MIGRATION_4_5`, no destructive fallback). The engine is three clean
layers behind `QuizRepository` — the only surface the screen talks to.

- **`QuizRepository.launch(semesterId, length, seed)`** — one
  semester-scoped pool query (`QuizDao.chunksOfSemester` join) →
  `QuestionGenerator.capacity` (eligible chunks only) →
  `QuizPlanner.plan` → `QuestionGenerator.build` (seeded) →
  `QuizDao.insertRun` (atomic persist). Returns `null` when no chunk can
  produce a question → the UI shows an honest “not enough depth” empty
  state instead of fabricating one.
- **`QuestionGenerator`** — deterministic rule-based kinds over chunk
  terms + segments:
  - TRUE/FALSE: mutate a verbatim sentence (global minimal edit distance
    across the pool so variants aren't countable guesses) rendered as the
    fixed visual pair, options never persisted (`optionsJson = NULL`).
  - FILL THE BLANK / MULTIPLE CHOICE: verify the candidate token is a
    real term, rebuild with tappable word chip, two distractors for
    FILL / three for MCQ chosen from other *verbatim* corpus phrases by
    segment similarity (`charsInCommon`), never invented, never repeated.
  - Options are `trimmed(90)` verbatim phrases (U+2026 appended); slot
    rotation `chunkId % 3` ∈ {FILL, MCQ, TF} cycles per chunk so
    truncated sessions expose all kinds (fixes `.take()` head-bias).
- **`QuizPlanner`** — greedy first pass re-chunks capacity-bounded
  (≤3/chunk) over the recent-completed ring (`RECENT_WINDOW_CHUNKS=30`),
  then round-robin pass-2 top-ups for small pools. Deterministic under a
  session seed; `Random` order only affects tie breaks.
- **`QuizRepository.answer / complete`** — writes `userAnswer` +
  `isCorrect`; completion computes `correct × XP_PER_CORRECT (10)` and
  `streakFrom` (consecutive calendar days from real `completionTimes`),
  snapshots them on the row. Sessions survive process death via
  `activeQuiz()` (resume the sole in-progress row; `positions` map to
  the persisted `optionsJson`/stored type).
- **`QuizViewModel`** — snapshot machine: `LOADING / NO_SEMESTER /
  NO_INDEXED / UNAVAILABLE / IDLE / QUESTION / RESULTS / ERROR`.
  Reloads when the DataStore year/semester changes; every action mutates
  SQLite then rebuilds local state (nothing quiz-shaped lives only in
  memory). `feedback` is a derived getter over the last answer
  (correct + correctAnswer + citation excerpt) — shown as CORRECT/WRONG
  with the verbatim `SOURCE … PAGE n` citation before advancing.
- **`QuizScreen`** — SYSTEM identity; idle card (scope headline, live
  stats SCORE / BEST / XP / STREAK, RULES, LENGTH 5/10/15, START QUIZ),
  question cards (type chip, prompt, option cards, `QUESTION n OF m` +
  `SCORE x/y`), feedback card + citation, results card (`n/m`, `%`,
  XP, STREAK, per-question review rows, NEW QUIZ).
- **Verifiability** — planner/generator are pure-JVM tested;
  repository/DAO run over real Room + sqlite-jdbc; full 37-test quiz
  suite is deterministic (frozen seeds). On-device DB inspection path:
  `run-as` + sqlite3 = `SELECT … FROM quiz_sessions/quiz_questions`.

## Emulator finding: backslash separators

Android's `java.util.zip` delivered nested entry names with `\` separators
for forward-slash-host archives, collapsing `buildPlan`'s `/`-only splits
into single segments (everything landed in week 1 / `OTHER`; the JVM tests
did not reproduce it). Fix: `ZipWalk` canonicalizes `\` → `/` for logical
paths and `isUnsafePath` splits on both separators. Regression tests cover
the Windows/backslash archive and traversal cases. This also hardens the
walker for real Windows-built exports.

## State management

ViewModels expose `StateFlow` UI state via `stateIn(WhileSubscribed)`;
screens collect with `collectAsStateWithLifecycle()`. Factories via
`viewModelFactory { initializer { … } }` reading the `AppContainer`.
Year/semester context flows from DataStore and is shared between Settings
and the Academic tab.

## Permissions

No runtime permissions requested except Listener Mode's microphone path.
File access uses SAF
(`ActivityResultContracts.OpenDocument`); scoped storage, no broad storage
permission. `POST_NOTIFICATIONS` is deliberately NOT requested:
foreground-service notifications are exempt from the runtime notification
permission, so Listener Mode needs only `RECORD_AUDIO` (runtime) plus the
manifest `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` declarations
(microphone foreground-service type is mandatory for targetSdk 34+).

## Flashcards & Spaced Review (Phase 8)

**Schema (v7, additive `MIGRATION_6_7`):**

- `flashcard_decks` — one per semester (title, `semesterId`, `createdAt`).
- `flashcards` — `deckId` (FK CASCADE), `front` / `back` (verbatim only),
  `sourceChunkId` / `sourceQuestionId` / `sourceListenerSegmentId` (plain
  columns, no FKs into academic tables), `sourceLabel` (citation string),
  `contentKey` (deterministic: `chunk:<id>:<term>`, `quiz:<qid>`,
  `seg:<segId>`), `easeFactor` (initial 2.5), `intervalDays` (initial 0),
  `dueAt` (SINGLE canonical timestamp; epoch-day boundary), `suspended`,
  `createdAt`, `updatedAt`. Unique index on `(deckId, contentKey)` with
  `OnConflictStrategy.IGNORE` for idempotent regeneration.
- `flashcard_review_sessions` — `deckId` (plain, no FK), `startedAt`,
  `completedAt`, `status` (`IN_PROGRESS` / `COMPLETED` / `INTERRUPTED`),
  `reviewedCount`, `retainedCount`; index on `(deckId, status)`.
- `flashcard_review_events` — `sessionId` (FK CASCADE), `flashcardId`,
  `rating` (AGAIN/HARD/GOOD/EASY), `reviewedAt`, `previousEaseFactor`,
  `newEaseFactor`, `previousIntervalDays`, `newIntervalDays`, `retained`
  (boolean); index on `sessionId`.

Flashcard source references are **plain columns** — no foreign keys into
academic tables. `dueAt` is the single canonical scheduling timestamp
(epoch-day boundary, UTC). Identical source + seed ⇒ identical cards.

**Generation (`CardGenerator`) — verbatim only, no LLM/cloud:**

Three bounded semester-scoped pools (DAOs use LIMIT 200):
- Document chunks (`FlashcardDao.cardChunksOfSemester`): extracts
  candidate terms from chunk text, builds cloze cards (front = term,
  back = sentence with term blanked). `contentKey = "chunk:<chunkId>:<term>"`.
  Citation = `fileName · TYPE · PAGE n`.
- Quiz mistakes (`FlashcardDao.mistakeQuestionsOfSemester`): questions
  answered incorrectly in completed sessions (`isCorrect = 0`). Front =
  prompt, back = correct answer. `contentKey = "quiz:<questionId>"`.
  Citation = `srcFileName · TYPE · PAGE n`.
- READY listener segments (`FlashcardDao.readySegmentsOfSemester`):
  `transcriptStatus = 'ready'` only. Front = transcript, back = verbatim
  transcript (same text). `contentKey = "seg:<segmentId>"`.
  Citation = `Lecture segment #n`. PENDING/FAILED segments generate
  **zero** cards.

Generation is deterministic: identical corpus + seed ⇒ identical cards.
`buildDeck` is idempotent (reuses existing `flashcard_decks` row,
`contentKey` dedup on insert).

**Review scheduler (`ReviewScheduler`) — pure, testable, SM-2-lite:**

Ratings: AGAIN / HARD / GOOD / EASY.
- Ease factor bounds: [1.3, 2.8].
- Interval: AGAIN → 0; else interval × ease (capped at 36 500 days).
- `dueAt = dayStart(nowMs) + intervalDays * DAY_MS` (UTC day-boundary).
- Deterministic: no current-time dependence inside scheduling logic;
  `now` is passed in. Equal `dueAt` ordered by `id` for determinism.

**Persistence & Resume (`FlashcardRepository`):**

- `grade(card, rating, sessionId, now)` is a single Room `@Transaction`:
  card schedule update + `ReviewEvent` insert + session counters
  (`reviewedCount`, `retainedCount`) — all atomic so history can never
  disagree with card state.
- `startReview(deckId)` creates `IN_PROGRESS` session; `completeSession`
  / `interruptSession` close it with final counts.
- Resume: `latestInProgress(deckId)` + `reviewEvents(sessionId)` →
  filter queue by already-graded `flashcardId` → `currentIndex = 0`,
  `reviewedCount = events.size`, `retainedCount = events.count { it.retained }`.
  Graded cards (non-AGAIN) are excluded from the resumed queue (they are
  future-due); AGAIN cards remain due and re-appear.

**UI (`FlashcardScreen` — SYSTEM identity):**

States: `LOADING` / `NO_SEMESTER` / `NO_DECKS` / `IDLE` / `REVIEW` /
`RESULTS` / `ERROR`.
- Deck: semester, title, total/due/suspended counts, **Start Review**.
- Review: front → Reveal → back + citation → AGAIN/HARD/GOOD/EASY +
  Suspend Card.
- Results: reviewed, retained, retention rate, New Review / Back to Deck.

No fake mastery percentages. Dark futuristic RPG "SYSTEM" aesthetic.

**Tests at Phase 8 (251 total, 63 new):**

- Migration v6→v7, schema version 7, old data survival
- Deck/card persistence, deterministic generation, verbatim content
- `contentKey` deduplication, quiz mistakes, READY listener only
- PENDING/FAILED exclusion, all 4 ratings, ease bounds, interval math
- `dueAt`, day boundaries, due ordering, suspension
- Review event/session persistence, atomic grade, resume, interrupted
  review, completion/retained counts

---

## Progression Engine (Phase 9)

**No schema change** - Room stays v7, no migration: XP/level/streak are
derived from existing rows, so the engine can never drift from history.

- **Sources:** quiz XP from persisted `quiz_sessions.xpEarned`
  (Phase 6 rule: 10 XP per correct answer); flashcard XP from
  `flashcard_review_events` ratings - AGAIN=0, HARD=2, GOOD=5, EASY=8 -
  counted once per persisted event. No XP on display/resume; no
  fabrication; no backdating.
- **`ProgressionCalculator`** (pure, `data/progression/`, no Android):
  level formula `100*(N-1)^2` (L1=0, L2=100, L3=400, L4=900, ...,
  unbounded, integer-only `isqrt`), `levelFromXp`,
  `xpIntoCurrentLevel`, `xpRequiredForNextLevel` (level span), plus
  `xpToNextLevel` and the single `streakFrom(times, now)` day-index
  implementation. `QuizRepository.streakFrom` and
  `QuizRepository.XP_PER_CORRECT` now delegate to it - one streak, one
  quiz-XP constant.
- **DAOs (additive queries, no tables):**
  `FlashcardDao.reviewActivity` / `observeReviewActivity`
  (`ReviewActivityRow(rating, reviewedAt)`; weights stay in Kotlin,
  never in SQL) and `QuizDao.observeCompletionTimes` (the suspend
  `completionTimes` already existed).
- **`ProgressionRepository`** - the ONE authoritative path:
  `combine(observeSummary, observeCompletionTimes, observeReviewActivity)`
  into a `Progression(totalXp, level, xpIntoLevel, xpForLevel,
  xpToNextLevel, streak)` snapshot (`levelProgress` is a derived
  getter). `current()` is the one-shot used by tests.
- **Dashboard:** `DashboardViewModel` combines the module/year/semester
  scope with `progression.observe()` (reactive - any DB change
  re-renders); `DashboardScreen` shows the `Level N - M XP` header and
  a Progression card (Level, Total XP, `into / span`, progress bar,
  streak), with an honest Level 1 / 0 XP / 0-day empty state. Other
  tabs untouched.
- **Tests (285 total, 34 new Phase 9):** pure level/XP/streak table
  (`ProgressionCalculatorTest`) and aggregation over real in-memory
  Room (`ProgressionRepositoryTest`: empty DB, quiz-only XP,
  per-event XP, AGAIN=0, mixed totals, threshold levels,
  double-read idempotence, combined streak fields).

---

## Listener Mode (Phase 7)

Recording + transcript-ready session foundation. No speech-to-text exists in
this phase: segments honestly report `Transcript pending.`

- **Schema (v6):** `listener_sessions` (`semesterId` plain history column,
  `status` recording/paused/completed/interrupted/failed, `startedAt`,
  `completedAt`, nullable `audioPath`, `createdAt`; indices on semesterId +
  status) and `listener_segments` (`sessionId` FK CASCADE — segments belong
  to their session; `position`, monotonic `startedAtMs`/`durationMs`,
  `transcript` default `"Transcript pending."`, `transcriptStatus`
  pending/ready/failed; index on sessionId). `MIGRATION_5_6` is purely
  additive; Room schema `6.json` committed; migration tests cover
  v1→current … v5→current. Raw audio NEVER lives in SQLite — only
  `filesDir/listener/listener_<sessionId>_<wallTs>.m4a` paths.
- **`ListenerState`** — pure transition table (IDLE → REQUESTING_PERMISSION
  / RECORDING → PAUSED ⇄ → COMPLETED → IDLE; any active state → ERROR).
  Deliberately NO IDLE→ERROR edge: a failed *start* never activated a
  session, so the machine stays IDLE (the failed row carries the failure
  and a retry is a plain start); only active-session failures move to
  ERROR. Unit-tested without Android.
- **`ListenerRecorder`** — the transcription seam: `start/pause/resume/
  stop/release/maxAmplitude`. Production is MediaRecorder MPEG-4/AAC;
  tests use a fake + fake clocks. A stop-throw (nothing captured) still
  persists a completed session with `audioPath` cleared when the file is
  missing/empty.
- **`ListenerRepository`** — owns the machine + recorder + segment math
  (pause closes the span with exact monotonic duration; resume opens the
  next span at the current offset, so paused gaps never leak into
  segments). `rehydrate()` marks a process-killed open session
  `interrupted` (tail span closed with duration 0 = unknown, never
  fabricated) and returns it for the recovery UI; nothing is deleted.
- **`ListenerService`** — foreground *holder* only (owns no audio):
  microphone-type FGS with an ongoing notification + Stop action, started
  on record, stopped on close/fail (`START_NOT_STICKY` — a resurrected
  holder with no recorder would be a lie). The ViewModel drives
  repository + service together around every transition.
- **`ListenerViewModel` / `ListenerScreen`** — SYSTEM identity; states
  LOADING / NO_SEMESTER / IDLE / REQUESTING_PERMISSION (rationale,
  denied, permanently-denied + app-settings link) / RECORDING (timer,
  pause/stop, segment count, true amplitude meter) / PAUSED /
  SEGMENTS (session summary + per-segment rows + detail dialog) / ERROR
  (message + saved-segment recovery). Amplitude shows the real
  `maxAmplitude` value; when it reads 0 the UI says capture still runs
  and the file is the source of truth (no fake visualization).
- **Verifiability** — 23 new tests (state table, repository over real
  Room incl. exact pause math, failure honesty, scoping, rehydration,
  no-BLOB-columns, migration v5→current); total 188 green. Emulator
  session (2026-09-26, CE_Test API 36): install over Phase 6 data ran
  v5→v6 (`user_version 6`); permission dialog → grant → RECORDING with
  live timer and real amplitude (1394); FGS verified
  (`isForeground=true`, microphone type, `listener_recording` channel);
  pause (46s span) → resume → stop produced 2 PENDING segments with the
  6.3s paused gap exactly excluded; 677 KB `.m4a` app-private
  (`-rw-------`); force-stop + relaunch restored IDLE with 1 session;
  no FATALs. Emulator CANNOT prove: real-mic quality, OEM
  battery-killer behavior, Bluetooth routing — real device still
  required for those.