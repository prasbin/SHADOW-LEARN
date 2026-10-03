# I1 EVIDENCE CONTRACT — DESIGN ONLY

**Status:** PROPOSED CONTRACT — NOT IMPLEMENTED. No Kotlin changed for this
document. Implementation requires explicit acceptance.
**Baseline inspected:** `c91a568` (408/408 tests). Governing docs:
`docs/MASTER_REQUIREMENTS.md` (canonical) + `docs/INTELLIGENCE_LAYER.md`
(subordinate). No conflicts found between them during this design session.

> Vocabulary for this document:
> **RAW DATA** = a persisted row. **EVIDENCE** = a row (or join) interpreted
> as an academic signal, with provenance. **INTERPRETATION** = a labeled
> conclusion (e.g. OBSERVED WEAKNESS) justified by evidence meeting a
> threshold. **INTELLIGENCE** = an interpretation wired to an action.
> Rows are never conclusions.

---

## 1. Purpose

Define exactly what SHADOW LEARN trusts as evidence for I1 (Evidence &
Weakness Foundation), how that evidence connects to academic scope, when it
justifies calling something a weakness, and what Home / Academic Status may
display. I1 delivers reliable source-level evidence only: no scores, no
percentages, no concepts, no AI.

---

## 2. Current Data Inventory

All tables live in Room v7, offline, app-private. History tables use PLAIN
(non-FK) scope references so academic re-imports never cascade-delete history.

**Academic hierarchy (FK-chained, CASCADE):**
`academic_years(id, name, sortOrder)` → `semesters(id, yearId, name,
sortOrder)` → `modules(id, semesterId, name, code?)` → `weeks(id, moduleId,
weekNumber, title?)` → `academic_files(id, weekId, fileName, filePath,
fileType, sha256, classType, relativePath, fileSize, lastModified, indexed,
createdAt, updatedAt, sourceFileId?)`.

**Content & extraction:** `source_files(id, sha256 UNIQUE, storedPath,
fileSize, refCount, createdAt)`; `extraction_meta(academicFileId PK, sha256,
status EXTRACTED/FAILED, format, charCount, chunkCount, error?,
startedAt, completedAt?)`; `document_chunks(id, academicFileId→CASCADE,
chunkIndex, pageNumber?, text, charCount, createdAt)` + FTS mirror
`document_fts` (rowid = chunk id).

**Assessment:** `quiz_sessions(id, semesterId PLAIN, seed, totalQuestions,
correctCount, xpEarned, streak, status in_progress/completed, startedAt,
completedAt?)`; `quiz_questions(id, sessionId→CASCADE, position, chunkId
PLAIN, academicFileId PLAIN, questionType MCQ/TRUE_FALSE/FILL_BLANK, prompt,
optionsJson?, correctAnswer, userAnswer?, isCorrect?, srcFileName,
srcFileType, srcPage?, srcExcerpt)`.

**Review:** `flashcard_decks(id, semesterId PLAIN, title, createdAt)`;
`flashcards(id, deckId→CASCADE, front, back, sourceChunkId?, sourceQuestionId?,
sourceListenerSegmentId?, sourceLabel, contentKey UNIQUE per deck, easeFactor
[1.3,2.8], intervalDays [0,36500], dueAt, suspended, createdAt, updatedAt)`;
`flashcard_review_sessions(id, deckId PLAIN, startedAt, completedAt?,
reviewedCount, retainedCount, status IN_PROGRESS/COMPLETED/INTERRUPTED)`;
`flashcard_review_events(id, sessionId→CASCADE, flashcardId PLAIN, rating
AGAIN/HARD/GOOD/EASY, reviewedAt, previousEaseFactor, newEaseFactor,
previousIntervalDays, newIntervalDays, retained)`.

**Listener:** `listener_sessions(id, semesterId PLAIN, status
recording/paused/completed/interrupted/failed, startedAt, completedAt?,
audioPath?, createdAt)`; `listener_segments(id, sessionId→CASCADE, position,
startedAtMs, durationMs, transcript, transcriptStatus pending/ready/failed)`.

**Derived-only state (no tables):** XP/level/streak (from quiz
`completedAt` + review `reviewedAt`; quiz +10/correct; AGAIN 0 / HARD 2 /
GOOD 5 / EASY 8; level step 100·(N−1)²); academic progress milestones
(import/extraction/quiz/review presence); DataStore holds
`currentYearId`/`currentSemesterId` + preferences.

**Existing DAO projections reused:** `MistakeRow(questionId, prompt,
correctAnswer, srcFileName, srcFileType, srcPage?)`,
`ReadySegmentRow(segmentId, transcript, transcriptStatus)`,
`ReviewActivityRow(rating, reviewedAt)`, `QuizSummaryRow`,
per-deck `dueCount/dueCards/latestInProgress/suspendedCount/lectureCardCount`,
`pendingSegmentCount`, `filesOfSemester`, hierarchy getters + `module/week/file(id)`.

---

## 3. Evidence Definitions

Minimum useful set: **five** types. A sixth candidate, ActivityEvidence
(Home's recent-activity feed), is REJECTED as a separate type — it is a UI
projection over import/completion/review/listen rows, not a distinct kind.

### AcademicContentEvidence
* Purpose: prove material exists and is referenceable.
* Source data: `academic_files` + `document_chunks` + `extraction_meta` + hierarchy chain.
* Identity: `academicFileId`.
* Scope: file → week → module → semester → year (FK walk).
* Timestamp: file `createdAt`; chunk `createdAt`.
* Provenance: fileName, classType, relativePath, pageNumber (chunks).
* Observed (rows exist), never inferred.

### AssessmentEvidence
* Purpose: what the student answered, right or wrong, and from where.
* Source data: `quiz_questions` (answered rows: `userAnswer NOT NULL`) joined to `quiz_sessions`.
* Identity: `questionId`.
* Scope: session `semesterId` (PLAIN snapshot) + question `academicFileId`/`chunkId` for file/chunk resolution.
* Timestamp: session `completedAt` (per-question answered time is MISSING — NOT CURRENTLY AVAILABLE; recency is session-grained).
* Provenance: `srcFileName/srcFileType/srcPage/srcExcerpt` snapshot (survives re-import/deletion).
* Observed.

### ReviewEvidence
* Purpose: how the student rated known cards, and when.
* Source data: `flashcard_review_events` + parent card via `flashcardId`.
* Identity: `eventId`.
* Scope: deck `semesterId`; file scope via card `sourceChunkId/sourceQuestionId` resolution (see §8).
* Timestamp: `reviewedAt` (event-grained, precise).
* Provenance: card `sourceLabel`; rating; prev→new ease/interval.
* Observed. Rating semantics stay exactly as the engine defines them
  (AGAIN = not retained, XP 0/2/5/8 are progression rules, NOT weakness scores).

### ListenerEvidence
* Purpose: what was captured and what became usable.
* Source data: `listener_sessions` + `listener_segments`.
* Identity: `sessionId` / `segmentId`.
* Scope: session `semesterId` ONLY. There is deliberately no segment→file/week link (MISSING — NOT CURRENTLY AVAILABLE).
* Timestamp: session `startedAt/completedAt`; segment offsets are monotonic, not wall time.
* Provenance: READY `transcript` text; FAILED reason honesty preserved.
* Observed.

### ProgressEvidence
* Purpose: measured state change (did acting help?).
* Source data: progress milestones (presence flags), XP/level/streak derivations, due-count deltas, completed/review counts.
* Identity: `(semesterId, observedAt)` snapshot key.
* Scope: current semester.
* Timestamp: observation time.
* Provenance: the milestone rows / counts behind each value.
* Derived from observed rows, never stored.

---

## 4. Room Source Mapping

| Evidence | Existing source | Relevant fields | Transformation |
|---|---|---|---|
| AcademicContent | `academic_files` + hierarchy FK walk | fileName, classType, relativePath, weekId→…→yearId | none (direct) |
| AcademicContent | `document_chunks` + `extraction_meta` | chunkIndex, pageNumber, text; status=EXTRACTED | filter EXTRACTED only |
| Assessment | `quiz_questions` + `quiz_sessions` | userAnswer, isCorrect, chunkId, academicFileId, src*; session semesterId, status=completed, completedAt | answered-only; recency = session completedAt |
| Review | `flashcard_review_events` | rating, reviewedAt, flashcardId | join card for source (card may be deleted → source unresolvable, count retained) |
| Review | `flashcards` (queue state) | dueAt, suspended, sourceLabel, sourceChunkId, deckId | due = dueAt ≤ now AND NOT suspended |
| Listener | `listener_sessions` + `listener_segments` | status; transcriptStatus, transcript | READY/FAILED/PENDING split; no hierarchy join possible |
| Progress | milestones + XP/level/streak derivations | presence flags; completedAt/reviewedAt streams | existing calculators reused verbatim |

Required-but-absent fields: per-question answered timestamp; segment→file link; concept labels. All marked MISSING, never worked around with string parsing.

---

## 5. Quiz Mistake Evidence

* Correctness: `quiz_questions.isCorrect` (`true/false/null`; null = unanswered, excluded).
* Selected answer: `userAnswer` (null until answered).
* Correct answer: `correctAnswer`.
* Source material: `academicFileId` (PLAIN) + `chunkId` (PLAIN) + snapshot citation (`srcFileName/srcFileType/srcPage/srcExcerpt`).
* Session link: `sessionId` → `quiz_sessions` (semesterId scope, seed for reproducibility).
* Completion: session `status=completed`; score = `correctCount/totalQuestions`.
* Timestamps: session `startedAt/completedAt` only (question-grained time MISSING).
* Source-file association: YES (direct `academicFileId` + snapshot name).
* Chunk association: YES via `chunkId` (requires a resolving query; `MistakeRow` projection currently drops it — see §18).
* Concept association: NO (MISSING — NOT CURRENTLY AVAILABLE; never invent labels).

Concrete representation: one AssessmentEvidence per answered-incorrect question: `{questionId, sessionId, semesterId, academicFileId?, chunkId?, srcFileName, srcPage?, sessionCompletedAt}`.

---

## 6. Flashcard Review Evidence

* Event identity: `flashcard_review_events.id`; card: `flashcardId` (PLAIN — survives card deletion); deck/session: `sessionId` → `flashcard_review_sessions` → `deckId` (PLAIN) → deck `semesterId`.
* Rating/timestamp/interval/ease: `rating`, `reviewedAt`, prev→new ease/interval — all on the event row.
* Source/content key: card `sourceChunkId?/sourceQuestionId?/sourceListenerSegmentId?`, `sourceLabel`, `contentKey`.
* Lecture association: `sourceListenerSegmentId != null` (+ existing `lectureCardCount`).
* Ratings as evidence: YES — individual ratings are first-class evidence rows.
* Multiple events per card retained: YES (append-only; history never reconstructed).
* Latest vs historical: distinguishable (`reviewedAt` ordering per `flashcardId`).
* I1 contribution: AGAIN events grouped by resolved source feed weakness counting; HARD/GOOD/EASY feed ProgressEvidence (retention/activity), NOT weakness. SM-2-lite behavior untouched; XP values (0/2/5/8) remain progression-only and are never treated as weakness scores. `AGAIN = weak` is FORBIDDEN without the §10 threshold + context.

---

## 7. Listener Evidence

* Sessions/segments/transcript state/text/relationships: per §2 inventory; READY text is verbatim engine output, FAILED carries honest reason, PENDING is explicitly not evidence of content.
* I1 contribution (Level 1): counts by status per semester (READY segments = practicable material; FAILED/PENDING = honest non-evidence); READY→card linkage already exists via `sourceListenerSegmentId` + `lectureCardCount`.
* No hierarchy association is possible (semester scope only) — listener evidence can trigger practice actions but never file/week weakness labels.

---

## 8. Source-Link Graph

```
QUIZ QUESTION --academicFileId/chunkId (DIRECT, plain cols)--> FILE / CHUNK
              --srcFileName snapshot (DIRECT, survives deletion)--> FILE NAME
              --session.semesterId (DIRECT)--> SEMESTER scope
FLASHCARD --sourceChunkId (DIRECT)--> CHUNK --> FILE (DIRECT via chunk.academicFileId)
          --sourceQuestionId (DIRECT)--> QUIZ QUESTION --> (above)
          --sourceListenerSegmentId (DIRECT)--> SEGMENT (semester scope only)
          --sourceLabel (DIRECT snapshot; parse NEVER — no string-derived links)
          --deck.semesterId (DIRECT)--> SEMESTER scope
          --week/module/file finer scope: RESOLVED multi-hop via chunk→file→week
             (requires resolving query; UNAVAILABLE when card deleted or source ids null)
LISTENER SEGMENT --session.semesterId (DIRECT)--> SEMESTER scope only
              --file/week/module: UNAVAILABLE (no such columns; do not infer)
FILE --weekId (DIRECT FK)--> WEEK --> MODULE --> SEMESTER --> YEAR (all DIRECT)
```

Rules: DIRECT = column/FK exists today. RESOLVED = derivable via existing IDs without new columns (needs a described query, §18). UNAVAILABLE = no information (segment→file, any→concept, question-grained time). RESOLVED links must degrade gracefully: if any hop is null/deleted, the evidence keeps its coarser scope and is marked accordingly — never dropped silently, never guessed.

---

## 9. Scope Rules

* Hard boundary: the **current semester** (DataStore scope). OBSERVED weakness, recommendations, and Home/Status displays use current-semester evidence only — matching decks, quiz `semesterId`, listener `semesterId`, and the Home snapshot.
* Roll-up: file → week → module by FK walk (DIRECT); module → semester trivially. A file-level signal MAY surface at week/module level by naming the parent (e.g. "Week 2 · 3 files need attention") only when ≥2 distinct files in that parent each meet POSSIBLE or one meets OBSERVED — prevents single-file noise from becoming a module verdict.
* No silent mixing: `Year 1 Semester 1` evidence never merges into `Year 2 Semester 4` signals. Cross-semester history is excluded from I1 current-scope state entirely.
* Historical labeling: if a future iteration compares semesters, each fact must carry its semester label ("earlier semester: …"). Not implemented in I1.

---

## 10. Observed Weakness Definition

`OBSERVED WEAKNESS` (source-file granularity — the only I1 level) requires, within the current semester:

* **A.** ≥3 incorrect quiz answers against the same source file, spanning **≥2 distinct quiz sessions or ≥2 distinct calendar days** (session `completedAt` granularity); OR
* **B.** ≥3 AGAIN ratings on cards resolving to the same source file within **14 days** (`reviewedAt` precise); OR
* **C.** Combined: ≥2 mistakes + ≥2 AGAINs on the same source (any order, 30-day window).

Why these numbers (not arbitrary): 3+ observations exclude single slips; 2+ sessions/days exclude one bad sitting (a deterministic stand-in for "repeated"); 14/30-day windows match semester-scale recency while review `reviewedAt` stays precise. Thresholds are I1-initial and must be re-validated against on-device behavior, not tuned silently.

Six signal questions: (1) evidence = rows in §5–6; (2) counts above; (3) windows above; (4) scope = source file within current semester, rolled up per §9; (5) conflicts → POSSIBLE + both facts shown (a later correct answer does not erase mistakes, it starts IMPROVING, §15); (6) explanation = counts by type + source + recency (see §15 WHY block).

---

## 11. Possible Weakness Definition

`POSSIBLE WEAKNESS`: a real signal below OBSERVED bar — 1–2 mistakes on a source; a single AGAIN; a single-session mistake cluster (≥2 wrong in one sitting = worth watching, not concluding); mistakes whose source file row is deleted (snapshot name survives → signal kept, marked "source removed"). Display: counted, labeled POSSIBLE, never promoted. `UNKNOWN`: zero assessment AND zero review evidence in scope — displayed as unknown, never as "no weakness".

---

## 12. Insufficient Evidence Rules

The system must output INSUFFICIENT EVIDENCE (not a shrug) when: one isolated mistake; one AGAIN; source linkage unresolvable AND no snapshot name; no repeated evidence; conflicting evidence with no repeat on either side; all evidence older than 60 days (stale — shown as history, excluded from OBSERVED counting); content outside current scope. Each case states what IS known, e.g.: "POSSIBLE WEAKNESS — 1 recent incorrect answer from cell-biology.pdf. Evidence is currently insufficient for an observed weakness." Staleness (60 days) is an I1-initial constant tied to semester cadence — flagged for re-validation (see §20).

---

## 13. Evidence Contract

Conceptual (not yet classes):

```text
Evidence
 ├── id          — source row id + type prefix (e.g. "q:1042", "rev:8871")
 ├── type        — ASSESSMENT | REVIEW | CONTENT | LISTENER | PROGRESS
 ├── scope      — (yearId, semesterId, moduleId?, weekId?, fileId?) — fileId null where unresolvable
 ├── observedAt — event time (reviewedAt; session completedAt for quiz; createdAt for content)
 ├── provenance — sourceLabel-equivalent: file name + page/slide + excerpt/chunk ref where present
 ├── payload    — type-specific facts (answer vs correct; rating + ease/interval delta; transcript status)
 └── strength   — what makes it meaningful: repetition count + distinct sessions/days + recency window
```

Explainability: any Evidence renders a one-line WHY (counts, dates, source). Lifecycle: ALL DERIVED (see §17).

---

## 14. Weakness Contract

```text
WeaknessSignal
 ├── WHAT    — source file (+ week/module parents for display)
 ├── WHY     — "3 incorrect answers across 2 sessions + 2 AGAIN reviews (last 9 days)"
 ├── EVIDENCE— the Evidence ids + rows behind each count (inspectable)
 ├── SCOPE   — semesterId (+ fileId; null fileId forbidden for OBSERVED)
 ├── RECENCY — newest + oldest contributing timestamps
 ├── ACTION  — Cards review of that source (existing mistake/AGAIN card path)
 ├── STATUS  — UNKNOWN | POSSIBLE | OBSERVED | IMPROVING (RESOLVED deferred, see below)
```

States justified now: UNKNOWN (no evidence), POSSIBLE (signal < bar), OBSERVED (§10 bar met), IMPROVING (bar was met AND trailing-7-day evidence on same source shows ≥2 correct answers/reviews with zero new mistakes/AGAINs). RESOLVED is NOT defined in I1 — "sustained recovery" needs a window definition we refuse to invent here (open question, §20).

---

## 15. Home UI Contract

Home Weak Areas upgrades in place (max 4, existing layout): each item shows source name + scope crumb (Week/Module), a WHY line ("3 wrong · 2 AGAIN · last 4d"), status tint (OBSERVED accent, POSSIBLE muted), and the existing CARDS action (mistake/AGAIN cards already flow there — no new destination). Empty: "No weak areas — nothing repeated yet." (NOT "no weakness"). Insufficient-evidence: POSSIBLE items render muted with their evidence line; UNKNOWN renders the empty state. No percentages, no scores, no concepts. Navigation target: CARDS in all I1 cases (sources without cards fall back to existing hierarchy file context — no new viewer).

---

## 16. Academic Status UI Contract

Status is NOT redesigned. One additive section proposed: "Learning signals" (max 3 items, same WeaknessSignal objects as Home, collapsed WHY), placed below milestones, above scope. It also gains the IMPROVING state display ("Improving: Biology — 3 correct this week, no new mistakes") since Status owns progress narrative. No analytics dashboard, no charts, no new numbers beyond existing percent/basis/XP.

---

## 17. Derived vs Persisted Decision

**Everything I1 is DERIVED.** Per-object: Evidence objects (recomputable joins over indexed tables; data volumes are semester-scale) — DERIVED. WeaknessSignal (pure function of evidence + now) — DERIVED. Threshold constants — code constants, not rows. No object meets the persistence test (cannot-recompute / cross-session need / schema requirement): deck rebuilds already re-derive cards; review/quiz history persists as source rows. **No new tables. No migrations.** If a future iteration proves recomputation too expensive on-device, that iteration must bring measurements — not assumptions.

---

## 18. Architecture Impact

* Reusable (exact): `FlashcardDao.mistakeQuestionsOfSemester/readySegmentsOfSemester/reviewActivity/dueCount/dueCards/latestInProgress`, `QuizDao.sessionsOfSemester/completedCount/observeSummary`, `ExtractionDao.totalChunkCount/observeChunkCount`, `AcademicDao` hierarchy getters + `module/week/file(id)`, `CardGenerator.fromMistakes/fromSegments`, `ProgressionCalculator` XP/level/streak, `ProgressCalculator` milestones, `SystemHomeRepository` weak-area/recommendation/activity builders (I1 extracts and reuses their logic — no parallel implementation).
* New (conceptual names only): `data/intelligence/EvidenceRepository` (aggregates DAO rows → Evidence), `WeaknessEngine` (pure thresholds → WeaknessSignal), fed into existing Home/Status VMs. No GodViewModel, no `AIRepository`.
* DAO queries (English only, no SQL): incorrect quiz questions with their sessions' completion times in a semester; AGAIN events joined to their cards' source ids in a semester; due cards with source labels per deck; mistake counts grouped by source file with distinct-session counts.
* Repositories: EvidenceRepository owns aggregation; existing repos untouched.
* Schema: NONE expected. UI: Home Weak Areas upgrade + one Status section (contracts §§15–16). AI: NONE. Network: NONE.

---

## 19. Validation Scenarios

* **A — One mistake:** 1 wrong answer, source X → POSSIBLE (X, "1 incorrect answer"), Home shows muted item or empty-state counts it as watching; never OBSERVED.
* **B — Repeated mistakes:** 4 wrong on source X across 3 sessions/3 days → OBSERVED (X), WHY lists counts + span, action CARDS.
* **C — Repeated AGAIN:** 1 AGAIN on a card → POSSIBLE only ("1 lapsed review"); 4 AGAINs on same-source cards in 10 days → OBSERVED. Single AGAIN never concludes.
* **D — Improvement:** source X met OBSERVED, then 3 correct answers + clean reviews in 7 days, zero new negatives → IMPROVING; a new mistake resets to OBSERVED with updated WHY.
* **E — No evidence:** fresh semester, no quiz/review → UNKNOWN ("No weak areas — nothing repeated yet."), never "NO WEAKNESS".
* **F — Deleted source:** mistakes reference removed file (snapshot name survives, academicFileId dangling) → POSSIBLE with "source removed" note; promotion to OBSERVED FORBIDDEN without a resolvable file.
* **G — Cross-scope isolation:** mistakes in Semester 1 + clean Semester 2 → Semester 2 Home shows UNKNOWN; no merged verdict.

---

## 20. Open Questions

1. RESOLVED state definition (sustained-recovery window) — deferred, needs real usage data.
2. Staleness constant (60 days) and windows (14/30/7 days) — I1-initial, must be re-validated on-device, never silently tuned.
3. Week/module roll-up display thresholds (§9 "≥2 POSSIBLE files" rule) — proposed, unvalidated.
4. Weight of READY-transcript-derived cards in weakness counting (currently: excluded from AGAIN grouping unless rated — decide at implementation).
5. Multiple decks per semester (DAO allows; UI assumes one) — I1 aggregates across decks or asserts single-deck invariant at implementation.
6. `MistakeRow` dropping `chunkId` — resolving query (§18) restores it; confirm no projection change ripples into `CardGenerator`.

---

## 21. Explicit Non-Goals

No mastery/confidence/intelligence/brain scores or percentages; no concepts/topics; no LLM/embeddings/vectors/cloud; no chatbot/assistant; no predictions (grades, exams); no automatic decisions; no cross-semester verdicts; no new tables/migrations; no string-parsed provenance; no silent threshold tuning; no analytics dashboards. I1 succeeds when Home can truthfully say *which sources need attention, why (counts + recency), and what to do next* — nothing more.
