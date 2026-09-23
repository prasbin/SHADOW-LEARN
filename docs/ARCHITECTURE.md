# Architecture — SHADOW LEARN

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
│   ├── screens/            # Dashboard, Academic, Settings + 5 placeholders
│   ├── dashboard/          # DashboardViewModel (DB counts + settings)
│   ├── settings/           # SettingsViewModel (CRUD years/semesters, prefs)
│   └── ingest/             # IngestViewModel (academic context + import state)
├── data/
│   ├── AppContainer        # minimal service locator (no DI framework yet)
│   ├── db/                 # Room entities, AcademicDao, ShadowLearnDatabase
│   ├── ingest/             # IngestFormat, ZipWalk, HierarchyPlan, IngestRepository
│   └── settings/           # SettingsRepository (DataStore)
└── util/                   # formatBytes (unit-tested)
```

## Database (v3)

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

Future tables (chunks, pages/slides, topics, quizzes, cards, transcripts,
progress, similarity) reference these primary keys — no destructive
redesign planned.

## Migration strategy

- Bump `DATABASE_VERSION` and ship a Room `AutoMigration` specification for
  additive changes; run and export the schema on the first build so the
  generated migration is created from the previous exported version.
  `fallbackToDestructiveMigration()` is **banned** — a missing migration
  must fail loudly, never wipe academic data.
- `exportSchema = true`; schemas committed under `app/schemas/`
  (`1.json`, `2.json`, `3.json`).
- Migration tests: `DatabaseTest` asserts the current baseline
  (`schemaVersion_isThree`); `MigrationTest` drives the real v1→current and
  v2→current paths with `MigrationTestHelper` using the committed `1.json`
  and `2.json` schemas — including the manual v1 `createSql` variant (which
  contains a `${TABLE_NAME}` placeholder the helper cannot substitute) — and
  asserts rows survive and v3 columns default correctly.

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
WorkManager stays available for Phase 4+ indexing.

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

No runtime permissions requested. File access uses SAF
(`ActivityResultContracts.OpenDocument`); scoped storage, no broad storage
permission. `RECORD_AUDIO` / `POST_NOTIFICATIONS` /
`FOREGROUND_SERVICE_MIC` arrive with Listener Mode and reminders (Phase 7+).