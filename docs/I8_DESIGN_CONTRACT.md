# I8 — NATURAL ACADEMIC ASSISTANT DESIGN CONTRACT

**Status:** DESIGN ONLY — NOT IMPLEMENTED. No Kotlin changed for this document.
Implementation requires explicit acceptance.
**Baseline:** `ba72e09` (main), synced with `origin/main`.
**Remote:** `https://github.com/prasbin/SHADOW-LEARN.git`

> Governing docs: `docs/MASTER_REQUIREMENTS.md` (canonical) +
> `docs/INTELLIGENCE_LAYER.md` (subordinate) + `docs/I1_EVIDENCE_CONTRACT.md` +
> `docs/I6_DESIGN_CONTRACT.md`. In any conflict, STOP and report.

> Vocabulary (per `docs/I1_EVIDENCE_CONTRACT.md`): **RAW DATA** = a persisted row.
> **EVIDENCE** = a row (or join) interpreted as an academic signal, with provenance.
> **INTERPRETATION** = a labeled conclusion (e.g. OBSERVED WEAKNESS) justified by
> evidence meeting a threshold. **INTELLIGENCE** = an interpretation wired to an action.
> Rows are never conclusions.

---

## 1. Product Goal

To provide a natural-language interface for students to interact with their personal,
offline-first academic knowledge base. The assistant acts as a grounded academic
intelligence system, deriving answers from the student's own lecture materials and
study evidence through the existing I1–I7 engines — rather than a generic chatbot.

The product goal is **not** "an AI chat". It is: *"The SYSTEM knows what academic
material I have, understands evidence from what I have done, can show where I need
attention, can explain why, and can help me decide what to do next using my actual
material."*

---

## 2. Hard Requirements Summary

- Ground answers in the user's imported material and existing evidence (I1–I7); reuse
  the existing engines (`GroundedRetrievalRepository`, `ExplanationEngine`,
  `RecommendationEngine`, `WeaknessEngine`, `EvidenceRepository`, `RelationshipRepository`)
  — never a parallel implementation.
- Permanent distinction between **VERIFIED SOURCE CONTENT** and **DERIVED / GENERATED
  FRAMING** (§3).
- Honest missing-evidence states; never fabricate academic content or student state.
- Respect the current academic-year / semester scope as a hard boundary.
- Offline-first: no cloud, no network, no LLM, no embeddings, no new database tables or
  migrations, no API keys.
- No generic chatbot shell, decorative AI, autonomous actions, or unrelated expansion.

---

## 3. Verified vs Generated Content Boundary

This is a permanent architectural rule (MASTER REQUIREMENTS §8, INTELLIGENCE_LAYER §6,
I1 contract §13/§14, I6 contract §6).

- **VERIFIED** content = verbatim excerpt(s) from the user's indexed academic material
  retrieved by the existing I3 retrieval layer, or the persisted source metadata
  (file name, week/module scope, page). It carries provenance and may be presented as a
  source fact. Examples: the exact indexed sentence behind an answer; the source file
  name behind a weakness signal.
- **DERIVED / GENERATED FRAMING** = the assembled explanation text produced by the
  existing I4 `ExplanationEngine`: the leading sentences of retrieved chunks (document
  order) inside fixed template glue, capped at 3 sentences (`MAX_SENTENCES`). It is built
  only from retrieved chunks and cannot introduce facts absent from the material.

The assistant's answer surface therefore renders, in order:

1. A **GENERATED** explanation label + assembled text + grounding basis
   (`"GENERATED FROM YOUR ACADEMIC MATERIAL"`, grounding basis e.g. `"2 passages · cell-biology.pdf"`)
   — clearly derived framing on top of verified content.
2. **VERIFIED** source cards: each source carries the verbatim excerpt, file name, scope
   crumb (week/module), page, and chunk reference.

Rules:

- Generated content must never claim a source it did not come from, and must never
  visually impersonate source material (existing `ExplanationDialog` styling).
- Concatenated excerpts are **not** a summary. The I8 assistant must not upgrade to
  summarization; extractive assembly is the ceiling, and summarization is deferred
  (§5.3).

---

## 4. User Journeys and Entry Point

### Entry Point (Decision: SYSTEM HOME action)

A new **`ACADEMIC ASSISTANT`** action button will be added to the **`SYSTEM HOME`**
screen, inside the existing scrollable content area (the `DashboardScreen` content
list / section cards). It will NOT become a 6th bottom-navigation tab, and no FAB is
added in this slice. This preserves the existing five-group navigation
(HOME / ACADEMIC / STUDY / LISTEN / SYSTEM) and the persistent top-bar Search, per
MASTER REQUIREMENTS §21 (navigation hierarchy over flat tabs; never remove a working
destination without understanding its purpose).

The button is the primary action surface for the assistant while staying inside the
existing command-center loop (STATE → NEED → ACTION). Follow-up access from
recommendation / weak-area / explanation surfaces (EXPLAIN › / OPEN SOURCE › /
RELATED MATERIAL ›) already exist and will be reused by the assistant rather than
duplicated.

### User Journey (First Vertical Slice)

1. User navigates to `HOME`.
2. User taps the `ACADEMIC ASSISTANT` button.
3. `AssistantScreen` displays a typed input field (keyboard automatically focused) with
   a hint (e.g., "Ask a question about your current semester's materials...") and a Send
   affordance. Speech input is **deferred** (§5.3).
4. User types a natural language question and submits.
5. Assistant processes the question using existing intelligence layers (I1–I7).
6. Assistant displays a grounded answer in a chat-like transcript: question echo,
   generated explanation (when grounded), VERIFIED source cards, grounding basis.
7. Source cards expose actions: `OPEN SOURCE ›` (navigate to the cited academic
   material), `PRACTICE THIS MATERIAL ›` (targeted practice), `RELATED MATERIAL ›`
   (cross-material, when available).
8. User taps an action; navigation uses existing routes only.
9. User may type another question. Answers are self-contained per question in this
   slice — conversational context memory is deferred.

---

## 5. First Vertical Slice — Supported Question Intents

The first release supports a small, explicit set of intents, focused on core capabilities
that reuse existing I1–I7 components with minimal new complexity.

### 5.1 Explain [Topic/Term]

- **Input:** user types a question containing a clear topic or term (e.g., "Explain
  photosynthesis", "What is neural network?").
- **Intent Recognition:** deterministic intent parse in the new `AssistantEngine`
  (regex / keyword matching, offline, no model). Patterns cover explicit-phrase intents
  (`explain`, `what is`, …) plus topic extraction; a failed extraction maps to the
  honest rephrasing state.
- **Retrieval:** `GroundedRetrievalRepository.retrieve(RetrievalRequest(semesterId, query = topic, maxChunks = 3))`
  — exact chunk → exact file → scoped FTS resolution order; semester enforced inside the
  retrieval layer (anything outside the current semester is `NoMatch`, never substituted).
- **Evidence Validation:** the retrieval result must be `Retrieved`; otherwise map to the
  honest failure states (`NoSource`, `SourceNotIndexed`, `NoMatch`, `InsufficientEvidence`).
- **Response:** `GroundedRetrievalRepository.explain(label, request)` → `GroundedExplanation`
  via the pure `ExplanationEngine`. `EXPLAINED` → generated answer + source cards;
  otherwise the status-specific honest state.
- **Source Representation:** source cards from `GroundedExplanation.sources`
  (`ExplanationSource`: fileName, scope crumb, pageNumber, verbatim excerpt, chunkId,
  weekId). `OPEN SOURCE ›` → existing hierarchy week route for the owning week
  (`Routes.hierarchyWeek(weekId)`); `PRACTICE THIS MATERIAL ›` →
  `Routes.practiceQuiz(fileId)` when the owning file is practical.
- **Honest Responses:**
  - Unparseable: "I couldn't understand your question. Please try rephrasing it."
  - No source: "No academic source is connected to this evidence."
  - Source not indexed: "The source file exists but has no indexed text yet."
  - No match: "No matching indexed material in the current semester."
  - Insufficient evidence: "SHADOW LEARN could not find enough indexed material in your
    current semester to explain this safely." (with `OPEN ACADEMIC MATERIAL` / `SEARCH
    MATERIAL` actions).
  - Empty corpus: "Your current semester has no indexed academic materials. Please
    import some files first."

### 5.2 What should I revise next? / My weaknesses

- **Input:** user types a question indicating a need for study guidance or weakness
  assessment (e.g., "What should I study next?", "My weak areas", "Tell me my weaknesses").
- **Intent Recognition:** deterministic keyword matching (`revise`, `study next`,
  `weakness`, `struggling`, …).
- **Retrieval:** existing single shared read — `EvidenceRepository.weaknessSignals(semesterId, now)`
  → pure `WeaknessEngine.evaluate(...)` → `List<WeaknessSignal>`; plus
  `RecommendationEngine.recommend(RecommendationInput(...))` for the primary
  recommendation. Both scoped to the current semester.
- **Evidence Validation:** requires existing quiz/review history and indexed academic
  material to produce meaningful answers; otherwise the honest insufficient-evidence state:
  "I need more study activity (quizzes or flashcard reviews) to identify your revision
  needs."
- **Response Model (Derived):** present the existing `RecommendationEngine` output
  (ACTION + REASON + EVIDENCE) and a concise list of `WeaknessSignal`s (WHAT + WHY + STATUS).
  Framed as derived (computed from real rows and system rules), never a mastery claim;
  language like `RECOMMENDED` / `CONSIDER REVIEWING` / `NEXT USEFUL ACTION`.
- **Source Representation / Navigation:** `PRACTICE THIS MATERIAL ›` →
  `Routes.practiceQuiz(fileId)` (I5 targeted practice, gated by indexed-chunk
  availability); review actions → `Routes.FLASHCARDS`; weakness sources → `OPEN SOURCE ›`
  via `Routes.hierarchyWeek(weekId)`; related materials → I6 `RelatedMaterialDialog`
  (max 3, hidden when empty).

### 5.3 Deferred Intents (explicitly deferred from I8)

The following are **explicitly deferred** for the I8 slice; none are part of the first
implementation slice:

1. **Summarize [File/Week/Module]:**
   - **Reason for deferral:** extractive assembly of retrieved excerpts is not a coherent
     summary; presenting concatenated excerpts as a summary would violate the genuine-
     summary requirement and the VERIFIED/GENERATED boundary.
   - **Future work:** a dedicated extractive summarization component or a future on-device
     model; must not ship as "concatenation = summary".

2. **How does [Concept A] connect to [Concept B]?**
   - **Reason for deferral:** conversational two-concept handling and connection
     explanations; the I6 cross-material engine exists but its presentation in a
     conversational flow is future work.

Deferred as well (out of I8 scope): cross-semester questions, speech input, cloud AI,
LLMs, embeddings / semantic search, new database tables / migrations, autonomous actions,
multi-turn conversational context, generic conversational ability.

---

## 6. Offline Implementation Options and Trade-offs

### Option 1: Intent-based Routing + Existing Intelligence (Chosen for I8)

- **Mechanism:** new deterministic intent parser / dispatcher (`AssistantEngine`) parses
  the typed question (regex / keyword matching) and dispatches to the existing I1–I7
  components.
- **Pros:** leverages existing components directly; fully offline-first; deterministic;
  minimal resource impact; predictable behavior.
- **Cons:** limited to explicit intents; phrasing-sensitive.
- **Trade-offs:** robustness/offline guarantees prioritized over conversational breadth.

### Option 2: On-device Embeddings + Semantic Search (Future Iteration) — **DEFERRED**

### Option 3: On-device Small Language Model (Future Iteration) — **DEFERRED**

---

## 7. Privacy, Performance, Memory, and APK Size Implications

- **Privacy:** unchanged — all processing remains local; no data leaves the device.
- **Performance:** minimal impact; intent classification is fast deterministic parsing;
  existing I1–I7 calls are reused.
- **Memory:** minimal impact; no new large data structures or models.
- **APK Size:** minimal impact; primarily new routing + UI logic.

---

## 8. UI States, 360dp Behavior, and Accessibility

### AssistantScreen

- **Initial State:** typed input field (speech deferred), focused keyboard, hint, Send
  button. Microphone icon is **not** part of this slice.
- **Loading State:** progress indicator while grounded retrieval + explanation run.
- **Answer Display:**
  - Question is echoed.
  - Answer text (generated framing / verified excerpts) displayed; generated framing
    explicitly labeled.
  - Grounding basis shown.
  - Source cards with VERIFIED excerpts and provenance; actions: `OPEN SOURCE ›`,
    `PRACTICE THIS MATERIAL ›`, `RELATED MATERIAL ›` (when available).
- **Error / Empty / Honest States:** mapped to the honest response set (§5.1/§5.2), plus
  empty-corpus and insufficient-study-activity states.

### 360dp Behavior

Layout uses existing Compose patterns for small screens; max-width constraints, scrolling
(`heightIn`, scroll state), safe areas; touch targets ≥ 48dp; readable typography.

### Accessibility

- ≥ 48dp touch targets for input/send/actions.
- Content descriptions / labels for input and actions (TalkBack semantics).
- Text-based status labels alongside any color tint (no color-only meaning).

---

## 9. Implementation Audit — I1–I7 (VERIFIED / UNVERIFIED / DEVIATION)

Every design reference was checked against actual source at baseline `ba72e09`.

| Design reference | Actual source (verified) | Verdict |
|---|---|---|
| `GroundedRetrievalRepository.retrieve` / `RetrievalRequest` / `RetrievalResult` | `data/intelligence/Retrieval.kt`, `GroundedRetrievalRepository.kt` | **VERIFIED** |
| `GroundedRetrievalRepository.explain` / `explainAll` | same | **VERIFIED** |
| `ExplanationEngine` / `ExplanationStatus` / `GroundedExplanation` / `ExplanationSource` | `data/intelligence/ExplanationEngine.kt` | **VERIFIED** |
| `ExplanationDialog` | `ui/components/ExplanationDialog.kt` | **VERIFIED** |
| `RecommendationEngine` / `RecommendationInput` / `Recommendation` | `data/intelligence/RecommendationEngine.kt`, `data/home/SystemHomeRepository.kt` | **VERIFIED** |
| `EvidenceRepository.weaknessSignals` / `WeaknessEngine` / `WeaknessSignal` | `data/intelligence/EvidenceRepository.kt`, `WeaknessEngine.kt`, `data/intelligence/Evidence.kt` | **VERIFIED** |
| `RelationshipRepository.relatedFor` / `RelatedMaterial` | `data/intelligence/RelationshipRepository.kt`, `data/intelligence/CrossMaterial.kt` | **VERIFIED** |
| `SearchRepository.search` / `SearchOutcome` / `indexedChunkCount` | `data/search/SearchRepository.kt` | **VERIFIED** |
| `QuizRepository.planForWeakness` / `TargetedPracticeOutcome` | `data/quiz/QuizRepository.kt` | **VERIFIED** |
| Assistant classes (`AssistantEngine`, `AssistantScreen`, `AssistantViewModel`) | **do not exist yet** | **UNVERIFIED** — forward references only; required investigation none (they will be created in the I8 implementation slice) |
| Assistant intent grammar quality | not yet implemented | **UNVERIFIED** — regex/keyword limitations to be validated on device |

**DEVIATIONS FOUND IN THE PREVIOUS DRAFT (corrected here, not papered over):**

1. The earlier draft cited `Routes.lecture(fileId, chunkId)`. **No such route exists.**
   The verified destination is the owning hierarchy week route:
   `Routes.hierarchyWeek(weekId)` (via `hierarchyWeekRoute`), and targeted practice is
   `Routes.practiceQuiz(fileId)` = `"quiz/practice/{fileId}"`. The revised contract uses
   the actual route names.
2. Speech input was listed in early UI states; the confirmed slice decision is typed-only
   input with speech deferred.

---

## 10. Test Strategy

- **Unit Tests:** `AssistantEngineTest` (pure intent recognition/dispatch, seams over
  I1–I7); `AssistantViewModelTest` (state flows); `AssistantScreenTest` (Compose UI).
- **Integration Tests (Room/FTS):** assistant engine → real `GroundedRetrievalRepository`
  + `ExplanationEngine` over a seeded Room database.
- **Regression Tests:** existing I1–I7 suites must stay green (I1 426/426, I2 443/443,
  I3 455/455, I4 466/466, I5 479/479, I6 498/498 engine + 500/500 UI), full suite green
  via `testDebugUnitTest --rerun-tasks`; honest-failure paths tested as first-class
  behavior; never delete/weaken tests to pass.

---

## 11. Acceptance Criteria for the First Slice

- AC-1: `SYSTEM HOME` → `ACADEMIC ASSISTANT` entry exists; no new bottom-nav tab.
- AC-2: `AssistantScreen` typed input only (speech deferred); focused keyboard; Send.
- AC-3: `Explain [Topic]` returns a grounded answer via existing I3/I4; generated framing
  labeled; VERIFIED source cards; `OPEN SOURCE ›` → hierarchy week route; practice link.
- AC-4: `What should I revise next?` uses existing I2/I1 outputs; honest insufficient-
  evidence state; navigates to existing practice/review flows.
- AC-5: Honest states for no-source / not-indexed / no-match / insufficient-evidence /
  empty-corpus / insufficient-activity; semester isolation respected.
- AC-6: Deferrals honored (no summarization, no cross-concept, no speech, no cloud/LLM/
  embeddings, no new tables/migrations).
- AC-7: Offline-only behavior; no new dependencies.
- AC-8: New focused tests + regression suites green.

---

## 12. Estimated Sessions and Time

- Design (this session): 1 session.
- Implementation:
  - UI entry point + `AssistantScreen` scaffold: 1–2 sessions.
  - `AssistantEngine` (intent parser + dispatcher): 2–3 sessions.
  - Wiring `Explain [Topic]` → retrieval/explanation/source cards: 2–3 sessions.
  - Wiring `What should I revise next?` → recommendation/evidence + practice nav: 2–3 sessions.
  - UI honest states, actions, `OPEN SOURCE` navigation: 2–3 sessions.
  - Testing (unit, integration, manual device): 3–4 sessions.
- Total estimated implementation: **~11–16 sessions** (approx. 2–3 weeks at ~1.5 sessions/day).

### Proposed Implementation Session Sequence (small slices)

1. Design approval → `AssistantEngine` scaffold + typed-input screen skeleton.
2. `Explain [Topic]` wiring (retrieval → explanation → source cards → honest states).
3. `What should I revise next?` wiring (evidence → recommendation → practice nav).
4. Shared UI states/actions + regression tests + device validation.

---

## 13. Next Action

Present this I8 design for review and approval. Once approved, the next action is to
implement the "User Journeys and Entry Point" starting with the UI scaffolding.
