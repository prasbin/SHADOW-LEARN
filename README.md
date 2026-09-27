# SHADOW LEARN â€” PRASBIN 2.0

Personal offline-first university learning OS (native Android).

- Package: `com.prasbin.shadowlearn`
- UI: Jetpack Compose (Material 3, dark futuristic theme)
- Data: Room (SQLite) + DataStore + Storage Access Framework
- Status: **Phase 10 - Listener transcription seam.** On-device STT
  abstraction over recorded sessions (verbatim READY or honest FAILED,
  never fabricated). Text, OOXML/.docx/
  .pptx, and PDF extraction with per-file, per-page chunks and an
  incremental FTS index (FTS5 auto-falls back to FTS4), fast honest
  academic search, a fully offline quiz engine over the current
  semester (true/false, fill-the-blank and multiple choice generated
  from verbatim excerpts, every option a real phrase from the material,
  every question citing its source), and now lecture recording with
  transcript-ready sessions/segments (speech-to-text NOT yet
  implemented). No AI or similarity checking yet (later phases).

## Features (Phase 3)

- Hierarchical academic database: Year â†’ Semester â†’ Module â†’ Week â†’ File
  (Room v3, additive AutoMigrations v1â†’v2â†’v3; new `source_files` store).
- Deterministic incremental import. Each re-import classifies every file:
  **New / Unchanged / Changed / Duplicate / Skipped / Failed**, with a
  per-import summary (content SHA-256 identity; position identity =
  semester + module-relative path).
  - Identical re-import â†’ all `Unchanged`, no new rows, no new copies.
  - Edited file â†’ `Changed` (old copy released, new copy stored).
  - Same content at a new path â†’ `Duplicate` (reuses one physical copy;
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
    found.`) â€” per-file isolation means one bad file never blocks the
    import.
  - `document_chunks` gives each piece a `chunkIndex`, optional
    `pageNumber`, and `charCount`; a PDF is one chunk per page, a PPTX one
    chunk per slide, a DOCX one chunk per page break.
  - Re-import of the *same* file â†’ `Skipped (unchanged)` (no re-extract).
    *Changed* file â†’ old chunks + old FTS rows removed, re-extracted, and
    re-indexed in one transaction. Falls `Failed` files retry on the next
    import.
- Full-text search index without dependencies: `FtsIndex` probes for FTS5
  and falls back to FTS4 on engines that lack it (real devices compile
  SQLite without FTS5, so the FTS4 path *is* the production path). FTS
  rowids are chunk ids, so every hit resolves to a file.
- The DB can be inspected live (rooted emulator):
  `sqlite3 â€¦/databases/shadowlearn.db` â†’ `user_version=4`, exact
  `extraction_meta` rows, `document_chunks` with pageNumber, and
  `SELECT â€¦ FROM document_fts WHERE document_fts MATCH 'â€¦'` hit counts.

## Features (Phase 5)

- **Academic search, fully local.** A dedicated Search tab over the FTS
  index (same `FtsIndex` abstraction from Phase 4, so FTS5 *and* the
  production FTS4 path are both exercised).
  - Query sanitization (`SearchQuery`) â€” lowercase, dedupe, cap 8 terms Ã—
    64 chars, keep only `\p{L}\p{N}` letters; output is always
    space-separated *prefix* terms (`gradient* descent*`). A trailing `*`
    on every term doubles as injection defense: reserved FTS operator
    words (`or`, `and`, `not`) can never be parsed as operators.
  - Implicit-AND handling that works on every engine: the Android
    framework SQLite (API 36, verified on device) does **not** treat a
    bare `AND` keyword as an operator (it matches the literal word
    â€œandâ€), so the expression form deliberately stays FTS4's
    space-implicit AND â€” identical results on real FTS5 too.
  - Deterministic **â€œSHADOW LEARN heuristic relevanceâ€**
    (`RelevanceScorer`): `(100Â·coverage + 12Â·exact + 6Â·prefix +
    80Â·fileNameHit + 30Â·moduleHit) / (1 + ln(1+len)/10)`. Long chunks are
    normalized; ties break `score â†“ â†’ fileName â†‘ â†’ chunkId â†‘`.
  - Spring-scoped results (`SearchDao.resolveChunks` joins chunk â†’ file â†’
    module â†’ semester in one query): searching never crosses into another
    semester's material, and the scope headline (â€œYear 2 Â· Semester 1â€)
    tracks the Settings tab live, re-running the query on change.
  - Excerpts with highlight offsets generator (`ExcerptGenerator`): a
    60-char lead-in + 100-char window around the first hit, collapsed
    whitespace, sanitized control characters, bounded merged highlight
    ranges so the UI can render matches bold.
  - Honest UI states: EMPTY / NO_SEMESTER / NO_INDEXED / SEARCHING /
    RESULTS / NO_RESULTS / ERROR â€” a query in a semester with no indexed
    content says exactly that.
- Search UI in the dark futuristic SYSTEM identity: result cards with
  type chips (PDF/PPTX/DOCX/TXT), `PAGE n` / `SLIDE n` refs, highlighted
  excerpt, relative relevance bar, tap-to-open detail dialog, and a clear
  (A-) button.

## Features (Phase 6)

- **Rule-based Daily Quiz, fully offline** (Room v5 tables `quiz_sessions` +
  `quiz_questions`, `MIGRATION_4_5`). A quiz is generated deterministically
  from the current semester's chunk pool:
  - `QuestionGenerator` â€” three question kinds from one pool:
    **TRUE/FALSE** (mutation of a verbatim sentence), **FILL THE BLANK**
    (a term blanked from a sentence), and **MULTIPLE CHOICE** (a verbatim
    sentence as the answer with corpus-sourced distractors). Every option
    is a real phrase from the indexed files â€” never invented â€” and each
    question records its source file/type/page (+ the exact verbatim
    excerpt as the citation).
  - Complexity-aware `QuizPlanner`: chunks eligible for each kind are
    matched, capacity-bounded (â‰¤3 questions/chunk), a recency ring skips
    the last ~30 completed chunks, pass-2 round-robins top-ups so a small
    pool still fills the session, and chunk slot rotation (`chunkId % 3`)
    guarantees type variety across truncated sessions.
  - Deterministic `Random(seed)` per session (seed persisted); at most
    one in-progress session resumes across process death.
  - Honest scoring: `+10 XP` per correct answer, streak computed from
    real completion timestamps only (`streakFrom`), and a persisted
    summary â€” SCORE / BEST / XP / STREAK on the idle screen.
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

## Features (Phase 8)

- **Flashcards & SM-2-lite spaced review** (Room v7, additive
  `MIGRATION_6_7`; tables: `flashcard_decks`, `flashcards`,
  `flashcard_review_sessions`, `flashcard_review_events`).
  - **Verbatim-only generation** â€” no LLM, no cloud. Three bounded
    semester-scoped sources:
    1. Document chunks â†’ cloze cards (front = term, back = sentence).
       `contentKey = "chunk:<id>:<term>"`, citation = `fileName Â· TYPE Â· PAGE n`.
    2. Quiz mistakes (`isCorrect = 0`) â†’ review cards (front = prompt,
       back = correct answer). `contentKey = "quiz:<questionId>"`,
       citation = `srcFileName Â· TYPE Â· PAGE n`.
    3. READY listener segments only (`transcriptStatus = 'ready'`).
       PENDING/FAILED generate **zero** cards. Front/back = verbatim
       transcript. `contentKey = "seg:<segmentId>"`, citation =
       `Lecture segment #n`.
  - **Idempotent deck build** â€” reuses existing `flashcard_decks` row,
    `(deckId, contentKey)` unique index with `IGNORE` dedup.
  - **SM-2-lite scheduler** (pure, deterministic):
    - Ratings: AGAIN / HARD / GOOD / EASY.
    - Ease factor bounds [1.3, 2.8]; interval math; `dueAt` = UTC
      day-boundary (single canonical timestamp).
    - No current-time dependence inside scheduling logic; `now` passed in.
  - **Atomic review persistence** (`grade` in one `@Transaction`): card
    schedule update + `ReviewEvent` + session counters (`reviewedCount`,
    `retainedCount`) â€” history never disagrees with card state.
  - **Resume** â€” `latestInProgress` + events filter already-graded cards;
    graded (non-AGAIN) excluded from queue, AGAIN re-appear; counts
    restored from events.
  - **UI states**: LOADING / NO_SEMESTER / NO_DECKS / IDLE / REVIEW /
    RESULTS / ERROR. SYSTEM identity (dark futuristic RPG "SYSTEM"
    aesthetic). Deck: semester, title, total/due/suspended, Start Review.
    Review: front â†’ Reveal â†’ back + citation â†’ AGAIN/HARD/GOOD/EASY +
    Suspend. Results: reviewed, retained, retention rate, New Review /
    Back to Deck. No fake mastery percentages.
  - **63 new Phase 8 tests** (migration v6â†’v7, schema v7, old data
    survival, deck/card persistence, deterministic generation, verbatim
    content, contentKey dedup, quiz mistakes, READY-only listener,
    PENDING/FAILED exclusion, all 4 ratings, ease bounds, interval
    math, dueAt day-boundaries, due ordering, suspension, review
    event/session persistence, atomic grade, resume, interrupted review,
    completion/retained counts). Total: **251 green**.
  - **Emulator CE_Test API 36 verified**: install over Phase 7 data ran
    v6â†’v7 migration (`user_version 7`, all prior data survived).
    Cards tab renders real UI (not ComingSoon): 5 cards (4 chunk + 1
    READY segment; PENDING/FAILED excluded). Start Review â†’ Reveal â†’
    citation â†’ GOOD â†’ interval 0â†’1, dueAt tomorrow day-boundary.
    Force-stop mid-review (2/3 graded) â†’ relaunch â†’ Resume Review â†’
    resumes at next card (graded excluded), counts preserved. Complete
    remaining 2 (GOOD) â†’ Results: Reviewed 3, Retained 3, 100%. DB:
    2 COMPLETED sessions, 4 events, all cards intervalDays=1,
    dueAt=tomorrow day-boundary. No FATALs.

## Features (Phase 9)

- **Progression engine, fully derived** (no new tables, no migration -
  Room stays v7). Single authoritative path `ProgressionRepository`
  over existing persisted rows; the UI never computes progression.
  - XP: quiz 10 XP per correct answer (`quiz_sessions.xpEarned`
    snapshots); flashcard review events AGAIN=0 / HARD=2 / GOOD=5 /
    EASY=8, counted once per persisted event (display/resume never
    double-awards).
  - Level: pure `100*(N-1)^2` formula (L1=0, L2=100, L3=400, L4=900,
    L5=1600, ...), unbounded, integer-only; helpers `levelFromXp`,
    `xpIntoCurrentLevel`, `xpRequiredForNextLevel`.
  - Streak: consecutive calendar days ending today (or yesterday when
    today is quiet); same-day activity counts once; gaps reset. One
    shared implementation (`QuizRepository.streakFrom` and
    `XP_PER_CORRECT` now delegate to it).
  - Dashboard Home (SYSTEM identity): "Level N - M XP" header, new
    Progression card (Level, Total XP, `into / span` toward next,
    progress bar, streak); honest Level 1 / 0 XP / 0-day empty state.
  - **34 new Phase 9 tests** (level thresholds/threshold-1/large XP,
    progress math, all XP weights, streak table incl. mixed quiz+card
    days, repository aggregation over real Room incl. empty DB and
    double-read idempotence). Total: **285 green**.
  - **Emulator CE_Test API 36 verified**: install over Phase 8 data
    (no migration, `user_version` stays 7). Seeded device held 4 GOOD
    events + 0 quiz rows - dashboard showed Level 1 - 20 XP, 20/100,
    1-day streak (independently recomputed). Graded one EASY via
    Resume Review - 28 XP, 28/100, 2-day streak. Seeded a completed
    40-XP quiz row - 68 XP, 68/100, streak 2. Force-stop + relaunch
    kept every value. No FATALs.

## Features (Phase 10)

- **Listener transcription seam, fully offline** (no new tables, no
  migration - Room stays v7). `Transcriber` interface mirrors the
  `ListenerRecorder` seam: the repository only talks to the seam, so a
  bundled on-device engine can replace the default without touching
  session/segment logic.
  - `ListenerTranscriptionRepository.transcribeSession(sessionId)`:
    resolves audio through the parent `ListenerSession.audioPath`
    (segments carry no audio path of their own), transcribes each
    `pending` segment in position order, persists READY with verbatim
    text or FAILED with an honest reason via guarded
    `ListenerDao.setTranscript` (refuses non-pending rows). READY/FAILED
    rows are never retried or overwritten - repeated calls are
    idempotent. Missing session/audio, empty audio, engine failure, and
    transcriber throws all resolve to honest FAILED rows, never crashes.
  - Production default `UnavailableTranscriber` reports no on-device
    engine / unsupported language instead of fabricating text (Android's
    platform recognizer is absent on CE_Test and takes no stored file;
    bundling a full engine was deliberately not shipped).
  - Listener UI: explicit per-session TRANSCRIBE button (user-initiated
    only, never background), TRANSCRIBING progress, READY/PENDING/FAILED
    counts, per-row status chips, honest result messages. READY rows
    flow into Phase 8 `CardGenerator.fromSegments` unchanged;
    PENDING/FAILED still feed zero cards.
  - **20 new Phase 10 tests** (`TranscriptionTest` over real Room with a
    fake engine: verbatim persistence, pending->ready, honest failure
    reasons, missing/empty audio, unavailable production transcriber,
    idempotence, scoping, no-BLOB storage, ready->cards integration).
    Total: **305 green**.
  - **Emulator CE_Test API 36 verified**: install over Phase 9 data
    (`user_version` stays 7, all rows survive). Recorded a real 2-minute
    session (2 segments, live amplitude), stopped, tapped TRANSCRIBE -
    both segments FAILED honestly ("No on-device speech engine is
    installed. Audio stays on the device."), UI + DB agree, seeded
    session untouched. Force-stop + relaunch preserved rows. Cards tab
    intact (deck 5, due 5). No app FATALs.

## Features (Phase 7)

- **Listener Mode foundation, fully offline** (Room v6 tables
  `listener_sessions` + `listener_segments`, `MIGRATION_5_6`).
  Lecture recording with transcript-ready segments; **speech-to-text is
  NOT implemented in this phase** â€” segments honestly report
  â€œTranscript pending.â€
  - `ListenerState` â€” explicit deterministic machine (IDLE â†’
    REQUESTING_PERMISSION / RECORDING â‡„ PAUSED â†’ COMPLETED â†’ IDLE;
    active states â†’ ERROR). No IDLEâ†’ERROR edge by design: a failed
    *start* never activated a session, so the machine stays IDLE and a
    retry is a plain start (the failed row carries the failure).
  - `ListenerRecorder` â€” the transcription seam (`start/pause/resume/
    stop/release/maxAmplitude`); production is MediaRecorder MPEG-4/AAC.
  - `ListenerRepository` â€” owns the machine + recorder + exact monotonic
    segment math (paused gaps never leak into segments); `rehydrate()`
    marks a process-killed session `interrupted` without deleting
    anything. Raw audio lives in app-private
    `filesDir/listener/listener_<id>_<ts>.m4a`, never in SQLite.
  - `ListenerService` â€” microphone-type foreground service with an
    ongoing notification + Stop action (owns no audio itself).
    Manifest: `RECORD_AUDIO` (runtime) + `FOREGROUND_SERVICE` /
    `FOREGROUND_SERVICE_MICROPHONE`; `POST_NOTIFICATIONS` deliberately
    not requested (FGS notifications are exempt).
- Listener UI in the SYSTEM identity: permission rationale/denied/
  permanently-denied (+ app-settings link) states, live timer, true
  amplitude meter, pause/resume/stop-finish, session summary, segment
  list with time ranges + PENDING chips, and a detail dialog labeled
  â€œTranscript pending.â€

## Build

Requirements and exact commands: see [docs/SETUP.md](docs/SETUP.md).
Architecture and database / ingest / extraction pipeline: see
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Status-line: `.\gradlew.bat testDebugUnitTest assembleDebug` (**305 unit
tests**, including Robolectric Room migrations + end-to-end ingest,
reconciliation, extraction, Search, the quiz engine over a real FTS4
index, Listener Mode sessions/segments, Flashcards/SM-2-lite
spaced review, the Phase 9 progression engine, and Phase 10
listener transcription).

## Testing status

## Testing status

- **127 unit tests green**: format rules, walk/plan (nesting, wrappers,
  empty folders, duplicates, unsafe paths, backslash archives),
  deterministic `Reconcile.classify` table, extractor codecs (txt/docx/
  pptx/pdf incl. garbage + truncated + legacy-binary inputs),
  incremental `ExtractionRepository` semantics (skip/change/fail),
  Robolectric Room migrations (v1â†’v4, v2â†’v4, v3â†’v4 against committed
  schemas), FtsIndex (FTS5 and FTS4 fallback paths), and end-to-end ingest
  runs with real ZIP bytes. Phase 5 adds 52 tests: query parsing/
  sanitization, heuristic scoring determinism, excerpt generation, and
  `SearchRepository` end-to-end against a real in-memory FTS4 index
  (scoring order, ties, scope isolation, page/slide refs, no-indexed
  reporting) plus FTS5-path tests (mult-term implicit AND and injection
  sanitization on real sqlite-jdbc FTS5).
- **Emulator verification performed** (`CE_Test`, API 36): a fresh v4
  install seeded Year 2 / Semester 1 and the full incremental session was
  driven end-to-end via SAF â€” imports reconciled; extraction produced
  EXTRACTED/FAILED metadata and indexed 8 chunks across txt/pdf/docx/pptx;
  FTS `MATCH` returned correct rowid+text for `optimization`,
  `backpropagation`, `network`, `gradient`. v3â†’v4 migration ran on device
  preserving every pre-existing row; re-importing a *changed* archive
  re-extracted only the edited PDF (`Changed: 1`, `Extracted: 1`,
  `Skipped: 4`), replacing its chunk + FTS rows. DB inspected live as
  `user_version 4`. (Device-found bugs fixed: Android's XML factory
  rejecting Apache XXE features â†’ best-effort feature application; PDF
  object-skip off-by-one at EOF; missing `/Filter /FlateDecode` in the
  test fixtures â†’ extractor is spec-correct, fixtures fixed.)
- **Emulator verification performed â€” Phase 5 Search** (`CE_Test`,
  API 36): Search tab renders in the SYSTEM identity; query â€œgradientâ€
  returned 3 results ranked ai.pptx â€º neural.pdf â€º readme.txt with type
  chips, `PAGE/SLIDE` refs, highlighted excerpts, relevance indicators
  and a working detail dialog; nonexistent terms show an honest
  â€œNo resultsâ€ state; switching semester in Settings live-switched the
  scope headline and produced the correct NO_INDEXED state for the empty
  semester (repo-scope, never cross-semester results); adb text-input
  swallowing quarantined the dex/query typing but the production query
  path was exercised directly against the device index: `forward*
  network*` (implicit AND, the exact sanitized expression) hits only the
  PDF chunk containing both terms, `print*` hits the `.py` chunk, and
  single prefixes behave identically. ALSO verified on device: this
  Android build's FTS4 parses bare `OR`/`NOT`/`NEAR` and space-implicit
  AND but **not** a bare `AND` keyword â€” hence the expression form.
- **REAL DEVICE TESTING: NOT YET PERFORMED.**
- **Emulator verification performed â€” Phase 6 Quiz** (`CE_Test`,
  API 36): a fresh install (post `pm clear`) rebuilt the semester via SAF
  (Year 2 / Semester 1, 8 indexed chunks from the txt/pdf/docx/pptx
  fixtures) and a full 10-question quiz was driven end-to-end over the
  persisted session plan: all three kinds appeared (TRUE/FALSE, FILL THE
  BLANK, MULTIPLE CHOICE), every question verified CORRECT feedback plus
  its verbatim citation (`SOURCE â€¦ PAGE n` / `SLIDE n`), SCORE tracked
  1/1â†’10/10, RESULTS rendered `10 / 10 Â· 100% Â· XP +100 Â· STREAK 1 DAYS`
  with per-question review rows (`â€¦ CORRECT Â· neural.pdf Â· PDF Â· PAGE 2`,
  `readme.txt Â· TXT`, etc.), NEW QUIZ returned to an idle summary showing
  SCORE 10/10, BEST 10, XP 100, STREAK 1 DAYS, 1 completed session.
  Live DB inspection confirmed `quiz_sessions`: `total=10, correct=10,
  xp=100, streak=1, status=completed` and that **every**
  `quiz_questions.userAnswer == correctAnswer` with `isCorrect=1`, TF
  rows persisting `optionsJson=NULL` (UI renders the fixed TRUE/FALSE
  pair) and fill/MCQ rows persisting the verbatim option list. (The
   empty-state guard was also exercised: a single 1-chunk import could not
   build a quiz â†’ honest â€œadd more materialâ€ state; Dashboard XP/level/
   streak stay honest zeros until the progression engine.)
- **Emulator re-verification (2026-09-26, `CE_Test`, API 36, current
  build):** installed `app-debug.apk` over the existing Phase 6 data and
  drove a second full 10-question session via `input tap` + `uiautomator
  dump`: Q1 answered wrong on purpose â†’ INCORRECT feedback with
  `Correct answer: â€¦`, `SOURCE notes.docx Â· PAGE 1` and the verbatim
  excerpt; SCORE tracked 0/1â†’3/4â†’4/10 across FILL/MCQ/TRUE-FALSE
  questions; RESULTS rendered `4 / 10 Â· 40% Â· +40 XP Â· STREAK 2 DAYS`
  with per-question review rows and citations. `am force-stop` +
  relaunch: cold start clean, Quiz idle restored from real rows
  (SCORE 4/10, BEST 10, XP 140, STREAK 2 DAYS, 2 completed sessions).
  On-device sqlite3 confirmed `quiz_sessions (10,4,40,2,completed)` and
  per-question `userAnswer`/`isCorrect` persistence. No FATALs.
  Unit suite for this pass: **165/165** (adds
  `completedQuizRemainsReviewableAfterCorpusDeletion`, proving a
  completed session stays fully reviewable after its academic rows and
  chunks are deleted).
- **Emulator verification performed â€” Phase 7 Listener Mode**
  (`CE_Test`, API 36, current build over Phase 6 data): install ran
  v5â†’v6 (`user_version 6`, quiz rows intact); Listener IDLE renders in
  the SYSTEM identity with the live semester scope; START â†’ permission
  rationale â†’ system dialog â†’ grant â†’ RECORDING with a live timer and
  real amplitude (1394/32767 on the emulator mic); foreground service
  verified (`isForeground=true`, microphone type, `listener_recording`
  channel, Stop action); PAUSE (46 s span) â†’ RESUME â†’ STOP produced
  2 PENDING segments with the 6.3 s paused gap exactly excluded;
  677 KB `.m4a` stored app-private (`-rw-------`); segment detail shows
  "Transcript pending." with the no-STT-yet disclaimer; `am force-stop`
  + relaunch restored IDLE with 1 persisted session; no FATALs.
  Emulator CANNOT prove real-mic quality, OEM battery-killer behavior,
  or Bluetooth routing â€” those need a physical device.
  Unit suite for this pass: **188/188** (adds 23 Listener tests:
  state table, repository lifecycle incl. exact pause math, failure
  honesty, scoping, rehydration, no-BLOB-columns, v5â†’v6 migration).
- **Emulator verification performed â€” Phase 8 Flashcards**
  (`CE_Test`, API 36, current build over Phase 7 data): install ran
  v6â†’v7 (`user_version 7`, all prior data survived). Cards tab renders
  real UI (not ComingSoon): `FLASHCARDS` title, deck/semester/total/due/
  suspended counts, Start Review. Deck built from seeded Phase 7 data (4
  chunk cards + 1 READY segment card; PENDING/FAILED excluded). Start
  Review â†’ Reveal â†’ citation (`lecture1.pdf Â· PDF Â· PAGE 1`) â†’
  AGAIN/HARD/GOOD/EASY â†’ GOOD grades card (interval 0â†’1, dueAt
  tomorrow day-boundary). Force-stop mid-review (2/3 cards graded) â†’
  relaunch â†’ deck shows "Due now: 2" + **Resume Review** â†’ resumes at
  next card (previously graded cards excluded), counts preserved
  (reviewed=1, retained=1). Complete remaining 2 (GOOD) â†’ Results:
  Reviewed 3, Retained 3, 100%. DB verified: 2 COMPLETED sessions, 4
  events, all 5 cards intervalDays=1, dueAt=tomorrow day-boundary. No
  FATAL crashes.
  Unit suite for this pass: **251/251** (adds 63 Phase 8 tests:
  migration v6â†’v7, schema v7, old data survival, deck/card persistence,
  deterministic generation, verbatim content, contentKey dedup, quiz
  mistakes, READY-only listener, PENDING/FAILED exclusion, all 4
  ratings, ease bounds, interval math, dueAt day-boundaries, due
  ordering, suspension, review event/session persistence, atomic grade,
   resume, interrupted review, completion/retained counts).
- **Emulator verification performed - Phase 9 Progression**
  (`CE_Test`, API 36, current build over Phase 8 data, no migration):
  seeded device held 4 GOOD review events + 0 quiz rows; Home showed
  `Level 1 - 20 XP`, `Total XP: 20`, `20 / 100`, `Streak: 1 day`
  (independently recomputed from `reviewedAt` day indices). Resumed
  review, graded one EASY -> 28 XP, 28/100, 2-day streak. Seeded a
  completed 40-XP quiz row -> 68 XP, 68/100, streak 2. Force-stop +
  relaunch kept every value. No FATAL crashes (only the pre-existing
  unrelated `droid.bluetooth` daemon abort seen since Phase 8).
  Unit suite for this pass: **285/285** (adds 34 Phase 9 tests: level
  thresholds/threshold-1/large XP, progress math, quiz + rating XP
  weights, streak table incl. mixed quiz+card days, repository
  aggregation over real Room incl. empty DB, in-progress exclusion,
  and double-read idempotence).
- **Emulator verification performed - Phase 10 Transcription**
  (`CE_Test`, API 36, current build over Phase 9 data, no migration,
  `user_version` stays 7): recorded a real 2-minute session (2
  segments, live amplitude 141), stopped, tapped TRANSCRIBE - both
  segments FAILED honestly ("No on-device speech engine is installed.
  Audio stays on the device."), UI summary + per-row reasons + DB
  agree, seeded session untouched. Force-stop + relaunch preserved
  rows; Cards tab intact (deck 5, due 5). No app FATALs (only the
  pre-existing unrelated `droid.bluetooth` daemon abort).
  Unit suite for this pass: **305/305** (adds 20 Phase 10 tests:
  verbatim READY persistence, pending->ready, honest failure reasons,
  missing/empty audio, unavailable production transcriber, no
  overwrite/retry, ordering, scoping, no-BLOB storage, ready->cards
  integration, idempotence).

## Current limitations

- XP / level / streak on the Home dashboard are now derived from real
  persisted quiz + flashcard activity; Progress % stays an honest 0 until
  a progression rule needs it.
- Export / Import of saved archives is not yet implemented.
- Listen records + transcribes via an explicit per-session pass, but no
  real on-device speech engine is bundled yet: without one, segments
  report the honest unavailability reason instead of invented text.
- Search covers the current semester's indexed *chunks* only; unindexed
  file types (`.rtf`, OLE `.doc`, garbage files) are explained per file by
  the extraction metadata and never silently â€œmatch nothingâ€.

## Next

Phase 11 - the ONE next step (see the Phase 10 hand-off prompt).


