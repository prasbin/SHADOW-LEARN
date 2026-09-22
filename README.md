# SHADOW LEARN — PRASBIN 2.0

Personal offline-first university learning OS (native Android).

- Package: `com.prasbin.shadowlearn`
- UI: Jetpack Compose (Material 3, dark futuristic theme)
- Data: Room (SQLite) + DataStore + Storage Access Framework
- Status: **Phase 1 foundation only** — no ZIP ingestion, extraction, AI,
  quizzes, Listener Mode, or similarity checking yet.

## Build

Requirements and exact commands: see [docs/SETUP.md](docs/SETUP.md).
Architecture and database: see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Current limitations (Phase 1)

- Academic screens are placeholders; only Dashboard counters, year/semester
  settings, and the SAF file-picker proof work with real data.
- XP / level / streak / progress are honest zeros until the Phase 6 engine.
- Export / Import are disabled placeholders.
- **REAL DEVICE TESTING: NOT YET PERFORMED** (verified on emulator only).

## Next

Phase 2 — Academic database + ZIP ingestion (recursive module/week walk).
Starts only on `NEXT`.
