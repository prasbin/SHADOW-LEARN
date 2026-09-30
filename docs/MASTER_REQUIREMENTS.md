# SHADOW LEARN — MASTER REQUIREMENTS

> Canonical source of truth for all future OpenCode sessions.
> Every future prompt MUST begin with reading this file.
> Every small session MUST end with a MASTER REQUIREMENTS CHECK.
> If this file and a prompt ever disagree: STOP and report the conflict.

## 1. Product identity

SHADOW LEARN is a **personal academic intelligence operating system**
(native Android, offline-first) — NOT a collection of unrelated academic
tools. Import, search, quiz, flashcards, listening, transcription,
progression, and backup are SUPPORTING SYSTEMS. The product is the
system that understands the student's material and guides their learning.

## 2. Product goal

A student opens the app and immediately understands their current
academic state: where they are, what needs attention, what to do next —
then acts (study / review / quiz / listen / search) and sees progress
update. Every feature must feed this loop, not sit beside it.

## 3. Non-goals

- Cloud services, accounts, sync, or analytics/telemetry.
- Network-dependent features (INTERNET permission stays absent).
- Copyrighted franchise assets (no Solo Leveling artwork/characters/logos).
- Fabricated academic content, fake success states, or invented data.
- Feature accumulation without UX validation.

## 4. UX principles

- Answer first: current state → objectives → recommended actions.
- Every screen has a purpose, a primary action, and honest empty/error states.
- No screen exists merely because a database table exists.
- Connected, not parallel: quiz mistakes feed review, transcripts feed
  cards, activity feeds progress, weakness feeds practice.
- Mobile-first, touch-friendly, readable, no clutter.

## 5. SYSTEM/HUD design principles

- Dark-first futuristic command-center identity (original SHADOW LEARN
  design; Solo Leveling "SYSTEM" concept is a general reference only).
- Hierarchy, status indicators, progress bars, objectives, XP/level,
  alerts, system messages, information density, sense of control.
- Restrained neon accents; purposeful panels/cards; readable typography;
  strong spacing. No generic Material-app look, no meaningless glow,
  no giant empty areas, no inconsistent card styles.

## 6. Academic hierarchy

Canonical and non-negotiable:

YEAR → SEMESTER → MODULE → WEEK → LECTURE / TUTORIAL / WORKSHOP → FILE

It must stay visible and meaningful everywhere — never flatten material
into a document list.

## 7. Intelligence principles

The system moves toward answering "what should I study now?" from real
data: weak-concept detection, source-grounded explanation, lecture
understanding, cross-lecture connections, weakness-driven practice.
Intelligence features follow UX validation (outside-in), never speculative
backend-first builds.

## 8. Verified vs AI-generated content rules

- VERIFIED academic content (imports, transcripts, citations) is the
  ground truth and must carry provenance (file/page/segment refs).
- AI-generated content MUST be clearly labeled as generated and MUST
  NEVER claim a source file it did not come from.
- Never fabricate curriculum, transcripts, XP, or success states.
- Empty/missing data gets honest empty states, never zeros that imply
  measured values (except genuinely derived 0, e.g. empty-progress 0%).

## 9. Feature priorities

PRIORITY A — core experience: SYSTEM HOME/command center, hierarchy,
current state, recommendations, progression, knowledge navigation,
search, quiz/flashcards in the learning loop.
PRIORITY B — intelligence: weakness detection, grounded explanations,
lecture understanding, summaries, weakness-driven questions,
cross-lecture connections.
PRIORITY C — support systems: Listener, offline STT, export/import,
backup, release hardening. Preserved, but must serve the product.

## 10. Architecture principles

- Reuse before inventing (seams, repositories, reconcile semantics).
- Single source of truth per domain; UI never queries Room directly.
- Deterministic, testable, offline pure logic where possible.
- Additive Room changes only; `fallbackToDestructiveMigration` banned.
- No new large dependencies without measured justification.
- Prefer NO code change; a change needs a real defect or a validated
  UX requirement.

## 11. Offline-first requirement

The app works fully offline: academic data, transcripts, audio, Vosk
model, transcription, export/import all local. No INTERNET permission.
Verified by manifest audit + a dedicated unit test.

## 12. Privacy requirement

Mic audio, transcripts, academic files stay app-private/on-device.
SAF only for user-chosen import/export. No analytics, no telemetry,
no secrets in the repo, no keystores/passwords committed.

## 13. Current verified implementation

- Phase 14 HEAD `fd0818a`; Room v7; 382/382 unit tests; ~78 MB APK
  (41 MB Vosk model + ~20 MB native .so, arm64-v8a + x86_64).
- SYSTEM HOME ships: state, objectives, recommendation, weak areas,
  activity, focus — all aggregated from real rows (SystemHomeRepository);
  387/387 unit tests; verified on CE_Test via view hierarchy.
- Hierarchy navigation ships: Home Academic Status → Years → Semesters →
  Modules → Weeks → Files, all from existing Room reads (no migration);
  honest empty states, CURRENT scope markers, file rows show provenance
  only (no viewer yet); 395/395 unit tests; full journey verified on
  CE_Test via view hierarchy.
- Progress destination ships as ACADEMIC STATUS: same Phase 12
  percent/basis, milestone presence flags, same Phase 9 level/XP/streak,
  scope names, OPEN ACADEMIC MATERIAL action; 398/398 unit tests;
  verified on CE_Test against Home values via view hierarchy.
- Navigation consolidated into 5 SYSTEM groups (HOME / ACADEMIC / STUDY /
  LISTEN / SYSTEM) with persistent top-bar Search; all legacy routes kept
  and resolving into groups; 400/400 unit tests; full journey verified on
  Redmi Note 14 5G with screenshots.
- Cards redesigned as ACADEMIC REVIEW: scope strip, prominent due count,
  dominant Start/Resume, honest zero-due with real next actions, semantic
  48dp ratings, demoted suspend, shared components; engine untouched;
  400/400 unit tests; due/review/zero-due journey verified on Redmi.
- Working: ZIP ingest + reconcile, extraction (PDF/DOCX/PPTX/TXT),
  FTS4/FTS5 search, deterministic quiz, XP/levels/streak, SM-2-lite
  flashcards + resume, Listener recording + segments, offline Vosk
  transcription seam (English small model; honest FAILED otherwise),
  lecture cards, derived progress, portable backup, GitHub workflow.

## 14. Known gaps

- 5 bottom groups; Home rows and Academic Status cross-navigate into them.
- Concept-level weakness detection not yet possible (no topic
  taxonomy); Home shows file-level signals only, labeled as such.
- Semester scope UX is fragile (autocomplete junk observed on device).
- REAL DEVICE TEST: only partial (Phase 15 FAIL: USB instability).
- Release signing/packaging unvalidated; no Play Store readiness claim.

## 15. UI roadmap

1. SYSTEM HOME (state, objectives, recommendations, weak areas, activity).
2. Hierarchy navigation (Year→…→File drill-down).
3. Module / Week-Lecture views.
4. Unified study/review flow entry.
5. Quiz, flashcards, Listener, Search, Progression refinements in the
   loop (fix Progress placeholder as part of Home, not as a silo).

## 16. Intelligence roadmap

Weakness signal → grounded explanation → lecture understanding →
weakness-driven practice → cross-lecture links. Each step UX-validated
first; verified-vs-generated rules (§8) apply throughout.

## 17. Testing expectations

- Full suite green (`testDebugUnitTest --rerun-tasks`, never UP-TO-DATE
  as evidence); never delete/weaken tests for PASS.
- New behavior needs focused tests (Room-backed where stateful,
  seam doubles where native/hardware is involved).
- Honest-failure paths tested as first-class behavior.
- Emulator proves logic/persistence; device proves hardware paths.

## 18. GitHub workflow

- Remote `https://github.com/prasbin/SHADOW-LEARN.git`, branch `main`.
- Never reset/force-push/rewrite history. Small scoped commits
  (`feat:`/`docs:`). Push, then verify `git rev-parse HEAD` ==
  `git ls-remote origin main`. Never claim backup without that check.

## 19. Small-session workflow

Before: read THIS file; state SESSION OBJECTIVE, EXPECTED
USER-VISIBLE RESULT, REQUIREMENTS AFFECTED. Work only on that.
After: MASTER REQUIREMENTS CHECK (below). On drift: STOP, correct,
then continue. No silent scope expansion — report-first for any new
feature/table/service/API/redesign/phase.

## 20. MASTER REQUIREMENTS CHECK requirement

End EVERY small session with:

```
MASTER REQUIREMENTS CHECK — SESSION <N>

[PASS]
- requirement:
- evidence:

[PARTIAL]
- requirement:
- current state:
- required correction:

[FAIL]
- requirement:
- violation:
- immediate action:

DRIFT CHECK:
- NONE
OR
- <specific drift>

NEXT ACTION:
- <single next action>
```

This rule is permanent and survives all future sessions.

## 21. UI/UX acceptance and phone-first validation (permanent)

A feature is NOT complete when code compiles + tests pass. It is
complete only when product purpose + UI/UX + real data + functionality
+ phone usability + tests + master requirements all agree.

- Unified design system: dark-first command-center identity; semantic
  colors, one typography scale, one spacing rhythm, shared components.
  No screen invents its own styles, sizes, or spacing.
- Action-first screens: every screen defines its primary question,
  primary action, secondary action, and exit/back path. No isolated
  feature screens — every screen connects to the student's next action.
- System shell: shared header / body / bottom navigation / back /
  transitions / dialogs / status conventions; hierarchy screens always
  answer where am I, what can I open, what next.
- Navigation hierarchy over flat tabs: bottom navigation must
  communicate product structure (Home / Academic / Study / Listen /
  System direction), not N equal-weight destinations. Never remove a
  working destination without understanding its purpose.
- Verified vs generated distinction is visual: verified academic
  content carries provenance; generated content is labeled and never
  mimics lecture sources.
- Phone-first validation: the target is the user's physical Android
  phone (portrait, touch, ~48dp targets, system bars, notches).
  Every UI session: build APK → CE_Test → phone when available →
  visually inspect → correct. Never claim phone validation from the
  emulator alone; mark pending honestly.

## 22. UI session workflow (permanent)

1. Define user problem. 2. Define desired interaction. 3. Define
visual result. 4. Inspect existing implementation. 5. Implement
smallest vertical slice. 6. Build APK. 7. Install on CE_Test.
8. Install on phone when available. 9. Visually inspect. 10. Correct.
11. Test. 12. Master Requirements Check. 13. GitHub backup. 14. STOP.

No more database-feature → tests → next-feature without
product/UX validation.
