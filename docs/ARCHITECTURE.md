# Architecture — SHADOW LEARN (Phase 1)

## Stack

Native Kotlin. Single `:app` module by design — feature modules
(`core-data`, `core-ingest`, …) are introduced only when they carry real
functionality (Phase 2+). Current layers inside `:app`:

```text
com.prasbin.shadowlearn
├── MainActivity            # edge-to-edge host, theme wiring
├── navigation/             # Routes + AppNav (bottom bar, 8 destinations)
├── ui/
│   ├── theme/              # dark futuristic Material 3 theme
│   ├── components/         # SectionCard, StatRow
│   ├── screens/            # Dashboard, Academic, Settings + 5 placeholders
│   ├── dashboard/          # DashboardViewModel (DB counts + settings)
│   └── settings/           # SettingsViewModel (CRUD years/semesters, prefs)
├── data/
│   ├── AppContainer        # minimal service locator (no DI framework yet)
│   ├── db/                 # Room entities, AcademicDao, ShadowLearnDatabase
│   └── settings/           # SettingsRepository (DataStore)
└── util/                   # formatBytes (unit-tested)
```

## Database (v1)

Tables: `academic_years` → `semesters` → `modules` → `weeks` →
`academic_files`, all with `CASCADE` deletes and FK indices.
`academic_files` carries `sha256`, `indexed`, size/mtime columns that the
Phase 3 incremental engine and Phase 4 extractor will use directly.

Future tables (chunks, pages/slides, topics, quizzes, cards, transcripts,
progress, similarity) reference these primary keys — no destructive
redesign planned.

## Migration strategy

- Every schema change: bump `DATABASE_VERSION` + ship explicit
  `Migration(x, y)`. `fallbackToDestructiveMigration()` is **banned**
  (see `ShadowLearnDatabase`); a missing migration must fail loudly,
  never wipe academic data.
- `exportSchema = true`; schemas committed under `app/schemas/`.
- Migration tests: v1 baseline asserted in `DatabaseTest`
  (`schemaVersion_isOne`); `MigrationTestHelper` instrumented tests start
  with the v1→v2 migration (Phase 2/3).

## State management

ViewModels expose `StateFlow` UI state via `stateIn(WhileSubscribed)`;
screens collect with `collectAsStateWithLifecycle()`. Factories via
`viewModelFactory { initializer { … } }` reading the `AppContainer`.

## Permissions

Phase 1 requests none. `RECORD_AUDIO` / `POST_NOTIFICATIONS` /
`FOREGROUND_SERVICE_MIC` arrive with Listener Mode and reminders (Phase 7+).
File access uses SAF (`ActivityResultContracts.OpenDocument`); scoped
storage, no broad storage permission.
