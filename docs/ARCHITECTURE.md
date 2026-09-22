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

## Database (v2)

Tables: `academic_years` → `semesters` → `modules` → `weeks` →
`academic_files`, all with `CASCADE` deletes and FK indices.
`academic_files` carries `sha256`, `indexed`, size/mtime columns for the
Phase 3 incremental engine and Phase 4 extractor, plus v2 additions:
`classType` (`LECTURE` / `TUTORIAL` / `WORKSHOP` / `OTHER`) and
`relativePath` (the logical path inside its module archive).

Future tables (chunks, pages/slides, topics, quizzes, cards, transcripts,
progress, similarity) reference these primary keys — no destructive
redesign planned.

## Migration strategy

- Bump `DATABASE_VERSION` and ship a Room `AutoMigration` specification for
  additive changes; run and export the schema on the first build so the
  generated migration is created from the previous exported version.
  `fallbackToDestructiveMigration()` is **banned** — a missing migration
  must fail loudly, never wipe academic data.
- `exportSchema = true`; schemas committed under `app/schemas/`.
- Migration tests: `DatabaseTest` asserts the current baseline
  (`schemaVersion_isTwo`); `MigrationTest` drives the real v1→v2
  `MigrationTestHelper` using the committed `1.json` schema, with the manual
  variant of the v1 `createSql` (which contains a `${TABLE_NAME}`
  placeholder the helper cannot substitute).

## Import pipeline (Phase 2)

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
(partial imports are resumable by re-importing; re-runs reuse
find-or-create rows — deduplication/reconciliation is Phase 3).

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