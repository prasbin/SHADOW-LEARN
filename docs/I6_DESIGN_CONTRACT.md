# I6 CROSS-MATERIAL UNDERSTANDING — DESIGN CONTRACT ONLY

**Status:** PROPOSED CONTRACT — NOT IMPLEMENTED. No Kotlin changed for this
document. Implementation requires explicit acceptance.
**Baseline inspected:** `34395a7` (I5 complete). Governing docs:
`docs/MASTER_REQUIREMENTS.md` (canonical), `docs/INTELLIGENCE_LAYER.md`,
`docs/I1_EVIDENCE_CONTRACT.md` (all subordinate-checked, no conflicts found).

> A relationship is not true merely because two files contain similar words.

---

## 1. Product question

"How can SHADOW LEARN help the student understand how their current academic
material relates to other material they have actually studied, using only
evidence available inside SHADOW LEARN?"

Concrete journey: CURRENT MATERIAL / WEAKNESS → RELATED MATERIAL (max 3,
ranked, each with WHY) → OPEN SOURCE (existing hierarchy week route) →
study/practice with existing tools. The deliverable is trustworthy
navigation between related sources, not a similarity score.

## 2. User journey

1. Home shows an OBSERVED weak area for `cell-biology.pdf` (existing).
2. The weak-area row offers RELATED MATERIAL › alongside EXPLAIN / PRACTICE /
   OPEN SOURCE (new, gated — see §10).
3. A dialog lists up to 3 related files, each with scope crumb, WHY line
   (shared terms or structural fact + verbatim excerpt), and OPEN SOURCE ›.
4. Tapping a file opens its hierarchy week (existing route); Back returns.
5. Identical path from Status Learning Signals and from the explanation
   dialog (shared composable + shared repository function).

## 3. Relationship definition

```text
CrossMaterialRelationship
 ├── sourceFileId / relatedFileId   (never equal; §8)
 ├── relationshipType               (SAME_WEEK | SAME_MODULE | SHARED_CONTENT)
 ├── evidenceTerms                  (distinct significant terms, possibly empty)
 ├── evidenceChunkIds               (chunk rows proving the terms, possibly empty)
 ├── scope                          (semesterId + both weeks/modules)
 ├── status                         (VERIFIED | POSSIBLE; UNKNOWN = absent)
 └── reason                         (one-line human explanation)
```

Every field must be fillable from existing rows. No field may be inferred.

## 4. Relationship types

* **SAME_WEEK** — both files attached to the same week row (FK walk).
  Evidence: the shared week itself. Always VERIFIED when shown.
* **SAME_MODULE** — same module, different weeks. Evidence: the shared
  module. Always POSSIBLE (structure suggests, never proves).
* **SHARED_CONTENT** — ≥3 distinct significant terms each verified by ≥1
  FTS hit chunk in the related file (§6). VERIFIED.
* Explicitly FUTURE / NOT YET SUPPORTED: PREREQUISITE, CONTINUATION,
  SUPPORTING_MATERIAL (inference beyond evidence), RELATED as a vague
  catch-all (banned — every item must carry its type + evidence).

Adjacency (Week N vs N+1) is NEVER evidence by itself. Week numbers may be
displayed factually ("Week 2", "Week 3") but must never be worded as
prerequisite/continuation.

## 5. Evidence rules

* Structural evidence = hierarchy rows (week/module membership). No text needed.
* Content evidence = significant terms extracted from the SOURCE file's own
  indexed chunks (§6), each confirmed by an actual FTS hit in the CANDIDATE
  file. A term without a hit is not evidence.
* Minimums: VERIFIED content needs ≥3 distinct terms; 1–2 terms only count
  when a structural tie (same module) also exists → POSSIBLE; otherwise
  UNKNOWN (no item shown).
* Excerpts shown = verbatim chunk text from the existing pipeline
  (ExcerptGenerator output or full chunk text), never rewritten.
* Stopword/length policy (§6) is part of the evidence rule, not a tuning knob.

## 6. Provenance rules

Each relationship row shows: related file name + scope crumb
("Week 2 · Biology") + WHY line + one verbatim excerpt + OPEN SOURCE ›.
WHY wording is fixed templates:
* SAME_WEEK: "Same week · Week N" (+ shared-term suffix when also present:
  " · shared: term1, term2").
* SAME_MODULE: "Same module · ModuleName".
* SHARED_CONTENT: "Shared indexed terms: t1, t2, t3 (+k more)".
No free-form explanatory prose. No invented connections.

## 7. Semester-scope rules

* Candidate enumeration walks ONLY the requested semester
  (`getModules(semesterId)` → `getWeeks` → `getFiles`; all exist).
* FTS confirmation uses `SearchRepository.search(term, semesterId)` —
  scoping enforced inside the existing query layer.
* Hierarchy resolution (`file → week → module → semester`) re-verifies each
  candidate; any candidate outside the semester is dropped (CROSS_SEMESTER
  exclusion by construction, proven by test — not a runtime status).
* Self-match (`relatedFileId == sourceFileId`) is excluded before ranking.

## 8. Retrieval strategy

Reuse only: `GroundedRetrievalRepository` chunk/file reads,
`SearchRepository.search` (prefix terms, implicit AND, deterministic sort),
`ExcerptGenerator`, `AcademicDao` hierarchy getters. No new index, no SQL
beyond what exists (candidate enumeration uses existing getters; chunk text
uses `chunksForFile`; single-term FTS uses the existing search path).

Term extraction (pure, deterministic): tokenize source chunks
(`[\p{L}\p{N}]+`, lowercase), keep length ≥ 5, drop an embedded stopword set
(~40 common English words + academic filler: lecture, chapter, introduction,
example, figure, table, section, page, slide, notes, summary, overview —
documented in code), dedupe, rank by frequency then alphabetically, cap 12.
Per term: one FTS query; aggregate hits per file (distinct terms + chunk ids).
Cap total FTS calls at 12 per source. All offline, all existing machinery.

## 9. Ranking/deduplication rules

* Order: status rank (VERIFIED first) → shared-term count desc →
  weekNumber asc → fileName asc. Fully deterministic; no randomness, no row
  order dependence.
* Max 3 relationships per source (matches the max-3-chunks convention).
* Deduplicate mirrored content by `sha256`: same-content files collapse to
  one entry (first fileName alphabetically); never two rows for one byte
  stream. Do not touch Phase 3 reconciliation logic — read `sha256` only.
* Identical chunks across files cannot double-count: terms are a SET per
  candidate file.

## 10. Failure states

* NO_RELATIONSHIPS — no candidate meets any rule (the normal single-file
  case). UI: RELATED MATERIAL button hidden entirely (no dead button).
* INSUFFICIENT_EVIDENCE — candidates exist (e.g. same-module files) but
  below display bar. Button hidden; nothing shown (indistinguishable from
  NO_RELATIONSHIPS to the student — both mean "nothing to show").
* SOURCE_NOT_INDEXED — source file has zero chunks: no term extraction
  possible → no button. (Structural siblings technically knowable; design
  decision: without indexed content there is nothing to UNDERSTAND —
  hierarchy browsing already covers navigation.)
* NO_MATCH — FTS path attempted, zero usable hits → no button.
* CROSS_SEMESTER_BLOCKED — guaranteed excluded by construction (§7);
  proven by test, never displayed.

## 11. UI contract

* One shared `RelatedMaterialDialog` (AlertDialog, existing SYSTEM style):
  title RELATED MATERIAL, ≤3 rows (file + scope + WHY + verbatim excerpt +
  OPEN SOURCE › 48dp), Close. Same composable from Home weak rows, Status
  signals, and ExplanationDialog (which gains an optional RELATED MATERIAL ›
  entry point alongside PRACTICE — caller-gated, no new destinations).
* Entry buttons labeled RELATED MATERIAL ›, 48dp, shown ONLY when the
  precomputed list is non-empty. No new bottom-nav destination, no chat UI,
  no glow/AI branding. 360dp-safe (vertical list, wrapping filenames).
* Home/Status do NOT gain new sections — buttons attach to existing rows.

## 12. Architecture

```text
Academic Data (existing tables)
  → RelationshipRepository.relatedFor(semesterId, sourceFileId)  [NEW, thin]
  → CrossMaterialEngine.rank(...)                                 [NEW, pure]
  → UI (shared dialog + row buttons)
```

* `RelationshipRepository` (data/intelligence): candidate enumeration
  (existing DAO getters), term extraction input (chunk texts via existing
  `chunksForFile`), FTS confirmation (existing `SearchRepository`), scope
  verification (existing hierarchy walk). No new tables/DAOs/columns.
* `CrossMaterialEngine` (pure object): term extraction, aggregation,
  thresholds, ranking, dedup. No Room/network/navigation; fully unit-testable.
* Reuse mandated: `GroundedRetrievalRepository` file/chunk reads where they
  fit (do not duplicate chunk fetching), `SearchRepository`,
  `ExcerptGenerator`, hierarchy resolution, `Routes.hierarchyWeek`.
* Derived relationships only — nothing persisted (same rule as I1/I2).

## 13. Data flow

Weakness signal (has `fileId`) → `relatedFor` enumerates same-semester
files (excluding self + same-sha) → structural types assigned by week/module
comparison → term extraction from source chunks → FTS confirmation per term
→ aggregation → engine ranking → max 3 → dialog. Cost bound: 1 enumeration
(3 small queries) + ≤12 indexed FTS lookups + chunk reads for shown files
only. Computed on demand when the user opens the RELATED MATERIAL surface
( NOT in Home snapshot — keeps snapshot cost flat), keyed by fileId so Home
and Status share results.

## 14. Weakness integration

Consumes `WeaknessSignal.fileId` (OBSERVED/POSSIBLE/IMPROVING all eligible —
related material is navigation, not a verdict; IMPROVING is explicitly fine
here, unlike corrective practice). Does NOT alter weakness semantics,
statuses, or thresholds. No new persistence, no mastery model.

## 15. Recommendation integration

NO new recommendation priority and NO change to the R1–R7 order.
RELATED MATERIAL is a secondary action originating from existing weakness /
source surfaces (like OPEN SOURCE), not a recommendation type. Documented
explicitly so a future session cannot "wire it into R2" without a contract
amendment.

## 16. Test contract

* Scope: same-semester relationship found; cross-semester twin excluded
  (identical text, other semester).
* Evidence: VERIFIED requires real term hits (assert hit chunk ids);
  insufficient (1–2 terms, no structural tie) yields nothing; fabricated
  relationship impossible by construction (assert empty on unrelated files).
* Retrieval: deterministic repeat equality; self-match excluded; max-3 cap
  with 5 eligible files; same-sha dedup (two identical files → one entry).
* Provenance: file/chunk/scope resolve; deleted source → honest empty;
  FTS index directly asserted (chunk text present in `document_fts`).
* Structure: same-week pair → SAME_WEEK VERIFIED; same-module pair →
  SAME_MODULE POSSIBLE; adjacent-week pair with no shared terms → NOTHING
  (proves adjacency ≠ evidence).
* Weakness integration: OBSERVED signal file yields relationships; no
  weakness rows mutated (mistake/AGAIN counts unchanged after call).
* Regression (must stay green, no behavior change): Home, Status, Search,
  Hierarchy, Quiz, Cards, Listener, I5 targeted practice, I1–I4 suites.

## 17. Device validation contract

Using existing real data (never seeded fakes): (1) source file with a real
weakness → RELATED MATERIAL visible; (2) related file opens at its correct
week with real content; (3) WHY terms verifiably present in both files'
indexed text (spot-check via Search); (4) Back returns; (5) single-file
semester → no button; (6) logcat clean. If live data lacks multi-file
semesters with overlapping terms, record as limitation — do not import
fixtures to force a screenshot.

## 18. Explicit non-goals

Embeddings/vector DB/semantic similarity/LLM/chatbot/prerequisite or
continuation inference/concept ontology/mastery or confidence scores/cross-
semester links/predictions/new tables/new routes/new tabs/new permissions/
network/cloud/analytics/persisted relationships/generic "related" carousels.

## 19. Open questions

1. Threshold tuning (≥3 terms, length≥5, stopword set, cap 12/3) — I6-initial,
   must be re-validated on-device against real corpora, never silently tuned.
2. On-demand vs precompute: on-demand keeps snapshot cheap but adds
   tap-latency (bounded, ~tens of ms); revisit only with measurements.
3. Whether SAME_WEEK deserves VERIFIED vs POSSIBLE — leaning VERIFIED
   (curriculum fact, not inference); flagged for acceptance review.
4. Related files with zero indexed chunks but structural ties — currently
   no button (§10); acceptable because hierarchy browsing covers it.
5. Stopword list maintenance across languages (current corpora are English;
   non-English terms pass through on length alone — acceptable, documented).

## 20. Future work that is NOT part of I6

Concept extraction/ontology, prerequisite graphs, semantic embeddings,
listener-transcript relatedness, I7 listener intelligence, I8 assistant,
cross-semester comparison views, relationship persistence/caching,
explanation text that *reasons about* relationships (I4 stays extractive).
