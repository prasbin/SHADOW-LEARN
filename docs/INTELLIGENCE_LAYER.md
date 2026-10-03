# SHADOW LEARN — MASTER REQUIREMENT
## INTELLIGENCE LAYER

**Status:** DESIGN / GOVERNING REQUIREMENT
**Baseline:** `8e4cb1c feat: add system home quick actions`
**Remote:** `https://github.com/prasbin/SHADOW-LEARN.git`

> Recorded verbatim as a governing requirement. DESIGN ONLY — no I1
> implementation until the I1 Evidence Contract (§45) is reviewed and
> accepted. In any conflict between this document and
> `docs/MASTER_REQUIREMENTS.md`, STOP and report.

---

# 1. PURPOSE

The SHADOW LEARN Intelligence Layer exists to transform the existing academic data and study history into **grounded academic guidance**.

It must answer the student's practical questions:

> **What do I know?**
> **What am I weak at?**
> **What should I study next?**
> **Why does the system recommend it?**
> **What material should I use to understand it?**
> **How can I practice it?**
> **Did my state improve after I acted?**

The Intelligence Layer is therefore not a generic chatbot.

It is the reasoning layer connecting:

**ACADEMIC MATERIAL**
→ **UNDERSTANDING**
→ **EVIDENCE**
→ **WEAKNESS**
→ **OBJECTIVE**
→ **ACTION**
→ **RESULT**
→ **UPDATED STATE**

---

# 2. PRODUCT DEFINITION

SHADOW LEARN is a:

> **Personal Academic Intelligence Operating System**

The Intelligence Layer is the component that makes the existing system behave intelligently around the student's actual academic material and history.

The product must remain:

* student-centered
* academic-material-grounded
* offline-first
* privacy-first
* explainable
* evidence-based
* action-oriented
* mobile-first

---

# 3. WHAT "INTELLIGENCE" MEANS

For SHADOW LEARN, intelligence means:

### 3.1 OBSERVE

Understand available evidence from:

* imported academic files
* extracted document chunks
* hierarchy
* quiz attempts
* quiz mistakes
* flashcard reviews
* due cards
* listener sessions
* transcripts when available
* academic progress
* recent activity

---

### 3.2 INTERPRET

Transform those signals into meaningful academic state.

Examples:

* repeated mistakes associated with a source
* overdue review
* incomplete/recently accessed material
* material with available but unused practice
* lecture material connected to a weak area
* recent learning activity
* lack of sufficient evidence

Interpretation must always identify its evidence.

---

### 3.3 RECOMMEND

Produce actionable next steps.

Examples:

* review these cards
* revisit this lecture
* practice these questions
* continue this unfinished activity
* review a weak source
* understand this section before attempting more practice

Every recommendation must have a reason.

---

### 3.4 CONNECT

Connect academic evidence across the system.

Example:

**Quiz mistake**
→ source file
→ lecture/week/module
→ relevant extracted text
→ related flashcards
→ recommended practice

Another example:

**Listener transcript**
→ lecture
→ extracted/recorded content
→ review material
→ generated study artifacts

Cross-linking is a core intelligence capability.

---

# 4. THE CENTRAL INTELLIGENCE LOOP

Every intelligence-driven interaction should fit this model:

```text
OBSERVE
   ↓
IDENTIFY EVIDENCE
   ↓
INTERPRET STATE
   ↓
IDENTIFY NEED
   ↓
RECOMMEND ACTION
   ↓
STUDENT ACTS
   ↓
MEASURE RESULT
   ↓
UPDATE STATE
```

The system must never stop at:

> "Here is some information."

It should move toward:

> "Here is what is happening academically, why the system believes that, and what you can do next."

---

# 5. EVIDENCE FIRST

The Intelligence Layer must operate from **real SHADOW LEARN data**.

Evidence sources include:

### VERIFIED ACADEMIC CONTENT

* imported files
* extracted document text
* document chunks
* source metadata
* academic hierarchy

### VERIFIED STUDENT ACTIVITY

* quiz answers
* quiz completion
* flashcard reviews
* review ratings
* due cards
* listener session state
* transcript state
* recent activity
* progression
* academic progress

### GENERATED OUTPUT

Anything produced through reasoning/generation must be explicitly classified as generated.

---

# 6. VERIFIED VS GENERATED FIREWALL

This is a permanent architectural rule.

## VERIFIED

Information directly supported by stored academic material or measured student/system state.

Examples:

> "You answered 3 of 6 quiz questions incorrectly."

> "This question came from `cell-biology.pdf`."

> "You have 8 cards due."

> "This lecture contains the indexed passage."

These may be presented as system facts.

---

## GENERATED

Anything synthesized, inferred, summarized, explained, paraphrased, or newly created.

Examples:

* explanations
* summaries
* conceptual connections
* study plans
* generated questions
* generated flashcards
* recommendations based on multiple signals

Generated content must carry an explicit generated identity.

Example visual label:

`GENERATED`

It must never visually impersonate source material.

---

# 7. NO FABRICATION RULE

The Intelligence Layer must never invent academic facts.

If the available evidence does not support an answer:

> **INSUFFICIENT EVIDENCE**

must be a valid system outcome.

The system must prefer:

> "I don't have enough verified material to answer that."

over:

> a plausible but unsupported explanation.

This rule applies to:

* explanations
* summaries
* recommendations
* weakness detection
* cross-links
* generated questions
* academic assistant responses

---

# 8. WEAKNESS MODEL

Weakness detection is a core intelligence capability.

However, the system must distinguish:

### OBSERVED WEAKNESS

Directly supported by repeated measurable evidence.

Example:

A source/question area has repeated incorrect quiz answers.

### POSSIBLE WEAKNESS

There is a weak signal but insufficient evidence.

Example:

A topic has one incorrect answer.

### UNKNOWN

There is insufficient evidence to make a meaningful assessment.

The system must not convert every mistake into:

> "You are weak at X."

---

# 9. WEAKNESS GRANULARITY

Intelligence should evolve progressively.

## Level 1 — SOURCE WEAKNESS

Already supported by existing architecture.

Example:

`cell-biology.pdf`
→ 4 mistakes

---

## Level 2 — SECTION / CHUNK WEAKNESS

Identify repeated problems around specific indexed material.

Example:

`cell-biology.pdf`
→ Cell Membrane section
→ repeated mistakes

---

## Level 3 — CONCEPT WEAKNESS

Only introduce when there is sufficient grounded evidence.

Example:

`Osmosis`
→ repeated mistakes
→ supporting lecture passages
→ supporting questions

Concept extraction must not be fabricated.

---

## Level 4 — CROSS-MATERIAL WEAKNESS

Connect the same concept across:

* lectures
* modules
* weeks
* quizzes
* flashcards
* listener material

This is a later capability.

Do not implement higher levels before lower-level evidence is reliable.

---

# 10. CONFIDENCE / EVIDENCE REQUIREMENT

Every intelligence signal must have an evidence basis.

The system should be able to answer:

> "Why did you conclude this?"

Examples:

```text
WHY THIS IS FLAGGED

3 incorrect quiz answers
2 repeated flashcard AGAIN ratings
Source: cell-biology.pdf
```

or:

```text
WHY THIS IS RECOMMENDED

8 cards are due
Last reviewed: 4 days ago
```

Do not expose arbitrary mathematical confidence scores unless they have a defined meaning.

Avoid fake percentages such as:

> "87% likely weak"

unless a formal, validated model exists.

---

# 11. RECOMMENDATION ENGINE

The recommendation engine must be:

* deterministic where possible
* explainable
* grounded in actual state
* action-oriented
* scoped to the student's selected academic context

Initial recommendation priority should remain aligned with the existing Home model:

1. due review
2. resumable activity
3. repeated recent mistakes
4. pending transcripts
5. untouched available material
6. honest empty/setup guidance

This ordering may evolve only through an explicit requirement change.

Recommendations must expose:

### ACTION

What the student can do.

### REASON

Why it is recommended.

### EVIDENCE

What data supports the recommendation.

---

# 12. RECOMMENDATION MUST NOT BECOME MANIPULATION

The system should assist the student's academic decisions.

It must not pretend that one recommendation is objectively mandatory.

Use language such as:

> `RECOMMENDED`

> `CONSIDER REVIEWING`

> `NEXT USEFUL ACTION`

rather than unsupported certainty such as:

> `YOU MUST STUDY THIS`

unless the statement is simply describing an explicit user-created requirement.

---

# 13. ACADEMIC ASSISTANT

The future academic assistant must be grounded in SHADOW LEARN material.

A question such as:

> "Explain this concept."

should follow:

```text
USER QUESTION
↓
IDENTIFY ACADEMIC SCOPE
↓
SEARCH VERIFIED MATERIAL
↓
RETRIEVE RELEVANT EVIDENCE
↓
GENERATE EXPLANATION
↓
ATTACH SOURCE PROVENANCE
```

If relevant evidence cannot be retrieved:

```text
INSUFFICIENT VERIFIED MATERIAL
```

must be possible.

---

# 14. SOURCE-GROUNDED EXPLANATIONS

When an explanation is based on academic material, the system should expose:

* source file
* relevant location/page/slide when available
* relevant excerpt/chunk when appropriate
* generated status

Example:

```text
GENERATED EXPLANATION

Based on:
cell-biology.pdf
Week 3
Lecture section

[Explanation]
```

The generated explanation must remain visually distinct from the verified source.

---

# 15. CROSS-LECTURE UNDERSTANDING

A major long-term goal is:

> **Connect what the student is learning now with what they learned earlier.**

Example:

```text
CURRENT:
Cell Membrane

RELATED EARLIER MATERIAL:
Diffusion
Osmosis
Transport Mechanisms
```

Every connection must be evidence-backed.

Do not infer relationships merely because two words sound similar.

Connections should originate from:

* shared indexed evidence
* explicit academic hierarchy
* verified source references
* validated semantic retrieval when such a system exists

---

# 16. PRACTICE LOOP

Intelligence must connect weakness to practice.

Example:

```text
WEAK AREA
↓
SUPPORTING SOURCE
↓
EXPLANATION / REVIEW
↓
TARGETED QUESTIONS
↓
RESULT
↓
UPDATED WEAKNESS STATE
```

The system must not generate unlimited generic quizzes.

Questions should be grounded in the student's actual academic material.

---

# 17. FLASHCARD INTELLIGENCE

Flashcards are not an isolated feature.

They become part of the intelligence loop.

Examples:

```text
WEAK AREA
→ generate/recommend relevant cards
```

```text
LISTENER CONTENT
→ grounded study cards
```

```text
QUIZ MISTAKE
→ targeted review card
```

Existing deterministic/verbatim card behavior must remain intact unless a future requirement explicitly authorizes generated cards.

Generated cards must be marked:

`GENERATED`

and retain provenance.

---

# 18. LISTENER INTELLIGENCE

Listener/STT exists to support academic understanding.

The long-term loop is:

```text
LECTURE RECORDING
↓
TRANSCRIPTION
↓
SEGMENTATION
↓
ACADEMIC RELEVANCE
↓
SUMMARY / UNDERSTANDING
↓
CARDS / PRACTICE
↓
UPDATED ACADEMIC STATE
```

The system must distinguish:

* recording
* transcription
* verified transcript
* generated summary
* generated cards

Do not treat generated summaries as original lecture content.

---

# 19. OFFLINE-FIRST INTELLIGENCE

The core Intelligence Layer must continue functioning without cloud access wherever technically feasible.

Existing rules remain:

* no mandatory cloud dependency
* no mandatory account
* no academic content upload
* no hidden network calls
* no analytics requirement

If a future capability genuinely requires an external model/service, it must be explicitly isolated and documented.

It must never silently become a dependency of the core academic system.

---

# 20. AI MODEL BOUNDARY

AI must be treated as an implementation mechanism, not the product definition.

Do not begin with:

> "Let's add an LLM."

Begin with:

> "What academic decision does the student need help making?"

Possible implementations may include:

* deterministic rules
* information retrieval
* local models
* embeddings
* semantic similarity
* LLM generation
* hybrid pipelines

The implementation is subordinate to the product requirement.

---

# 21. NO AI FOR THINGS THAT RULES SOLVE

Do not use an LLM where deterministic data is sufficient.

Examples:

* due-card count
* XP
* level
* streak
* quiz score
* source file counts
* progress
* recent activity
* recommendation based purely on explicit state

These should remain deterministic.

AI should be introduced where it provides genuine value:

* semantic understanding
* grounded explanation
* summarization
* concept connection
* natural-language interaction
* useful synthesis

---

# 22. INTELLIGENCE ARCHITECTURE

The conceptual architecture should become:

```text
ACADEMIC DATA
      ↓
EVIDENCE LAYER
      ↓
RETRIEVAL / SIGNAL EXTRACTION
      ↓
INTELLIGENCE STATE
      ↓
RECOMMENDATION / EXPLANATION
      ↓
ACTION
      ↓
MEASURED RESULT
      ↓
ACADEMIC STATE
```

Each layer must have a clear responsibility.

Do not create a single giant:

`AIRepository`

or:

`GodViewModel`

containing every intelligence behavior.

---

# 23. EVIDENCE LAYER

The Evidence Layer should eventually provide normalized evidence objects such as:

```text
AcademicEvidence
StudentActivityEvidence
AssessmentEvidence
ReviewEvidence
ListenerEvidence
ProgressEvidence
```

The exact implementation must be designed before coding.

Evidence should retain provenance.

---

# 24. INTELLIGENCE STATE

The Intelligence Layer should eventually derive explicit state such as:

```text
CurrentFocus
WeakArea
LearningNeed
Recommendation
EvidenceBasis
AcademicConnection
```

These are conceptual requirements.

Do NOT blindly create classes/tables for every item.

First determine which can be derived from existing data.

Prefer:

> derived state

over:

> duplicated persisted state.

---

# 25. PERSISTENCE RULE

Do not create database tables for intelligence state unless there is a demonstrated need for persistence.

Prefer deriving intelligence from existing source data.

Persist only information that:

1. cannot reasonably be recomputed,
2. has clear product value,
3. requires persistence across sessions,
4. has an explicit schema requirement.

No migration should be introduced merely because a concept exists.

---

# 26. DETERMINISM AND REPRODUCIBILITY

For deterministic intelligence:

Same source state
+
same rules
==========

same result.

Recommendations should therefore be reproducible.

Generated AI output may vary, but its:

* source evidence
* scope
* generation status
* provenance

must remain reproducible and inspectable.

---

# 27. INTELLIGENCE UI

Intelligence must appear inside the SYSTEM experience.

Do not create a generic:

`AI CHAT`

screen and call that the Intelligence Layer.

The primary experience should remain:

**STATE → NEED → ACTION**

The user should encounter intelligence through:

* Home
* Academic Status
* Weak Areas
* source material
* Quiz results
* Cards
* Listener
* Search
* future academic assistant

A conversational assistant may eventually exist, but it is not the product itself.

---

# 28. SYSTEM HOME INTELLIGENCE

Home should eventually answer:

### CURRENT STATE

Where am I academically?

### CURRENT FOCUS

What am I currently working on?

### WEAK AREAS

What evidence suggests I need attention?

### RECOMMENDATION

What useful action should I consider next?

### WHY

What evidence supports that recommendation?

### RESULT

What changed after I acted?

The existing Home UI is the command center for this loop.

---

# 29. INTELLIGENCE MUST IMPROVE HOME, NOT REPLACE IT

Future intelligence features should feed the existing Home rather than create parallel dashboards.

For example:

```text
Weakness Engine
      ↓
Home Weak Areas

Recommendation Engine
      ↓
Home System Recommendation

Learning State
      ↓
Home Current Focus

Academic Understanding
      ↓
Home Objectives
```

This prevents another siloed feature architecture.

---

# 30. SCOPING

Intelligence must respect:

```text
YEAR
→ SEMESTER
→ MODULE
→ WEEK
→ LECTURE/TUTORIAL/WORKSHOP
→ FILE
```

A recommendation or weakness signal must not silently mix unrelated academic scopes.

When broader evidence is intentionally used, the UI must make that scope clear.

---

# 31. TEMPORAL AWARENESS

Academic state changes over time.

Intelligence should eventually understand:

* recent mistakes
* repeated mistakes
* overdue reviews
* recent lectures
* recent listening
* recent activity
* improvement after practice

However:

> Recent ≠ important

and:

> old ≠ irrelevant.

Time must be used as evidence, not as an arbitrary ranking mechanism.

---

# 32. NO PRETEND MASTERY

The system must not claim:

> "You have mastered this."

based only on a small number of interactions.

It should use evidence-qualified language:

* observed
* improving
* needs review
* repeated mistakes
* insufficient evidence
* recently practiced

Mastery claims require an explicit future requirement and sufficient evidence.

---

# 33. INTELLIGENCE QUALITY RULES

Every intelligence feature must pass:

### Grounded

Can the result be traced to real evidence?

### Useful

Does it help the student make an academic decision?

### Explainable

Can the system explain why it produced it?

### Actionable

Is there a meaningful next action?

### Honest

Does it communicate uncertainty?

### Scoped

Is the academic context correct?

### Reproducible

Can the underlying evidence be inspected?

If a feature fails these criteria, it is not ready.

---

# 34. INTELLIGENCE DEVELOPMENT ORDER

Do not build everything simultaneously.

The intelligence roadmap should proceed in this order:

## I1 — Evidence & Weakness Foundation

Build reliable evidence aggregation and source-level weakness signals.

Goal:

> "Show me where my evidence says I need attention."

---

## I2 — Grounded Recommendations

Turn evidence into explainable actions.

Goal:

> "Tell me what useful action I can take next, and why."

---

## I3 — Grounded Retrieval

Improve search/retrieval around relevant academic material.

Goal:

> "Find the actual material relevant to my question or weakness."

---

## I4 — Grounded Explanations

Generate explanations from retrieved verified material.

Goal:

> "Explain this using my actual academic material."

---

## I5 — Weakness-Driven Practice

Connect weaknesses to targeted practice.

Goal:

> "Help me practice what I actually struggle with."

---

## I6 — Cross-Material Understanding

Connect related material across lectures/modules/weeks.

Goal:

> "Show me how what I'm learning now connects to earlier material."

---

## I7 — Listener Intelligence

Transform transcripts into academically useful understanding and study artifacts.

Goal:

> "Turn what I heard in class into useful academic knowledge."

---

## I8 — Natural Academic Assistant

Provide natural-language interaction over the grounded intelligence system.

Goal:

> "Let me ask my academic system questions naturally."

This is **last**, not first.

---

# 35. WHAT WE MUST NOT BUILD YET

Until the above foundation is proven, do NOT build:

* generic AI chatbot
* autonomous agent
* cloud AI dependency
* predictive grades
* exam predictions
* unsupported mastery scores
* personality profiling
* emotional inference
* fabricated concept maps
* automatic academic decisions
* generic internet answers presented as academic truth
* decorative AI dashboards
* AI for the sake of AI

---

# 36. PRIVACY

Academic material and student activity are private system data.

The Intelligence Layer must not:

* upload academic files without explicit user action
* transmit recordings without explicit user action
* silently call external AI APIs
* collect analytics
* expose private academic history

Any future external model integration requires explicit architectural approval.

---

# 37. FAILURE STATES

Intelligence failure must be honest.

Possible states include:

```text
READY
INSUFFICIENT EVIDENCE
NO RELEVANT MATERIAL
PROCESSING
GENERATED
FAILED
```

Never convert failure into a confident answer.

---

# 38. INTELLIGENCE ACCEPTANCE TEST

An Intelligence feature is not PASS merely because:

* the code compiles
* an AI model responds
* a database query works
* a test passes

It must demonstrate:

```text
REAL ACADEMIC DATA
↓
REAL EVIDENCE
↓
INTELLIGENCE RESULT
↓
EXPLANATION / BASIS
↓
REAL ACTION
↓
MEASURED RESULT
```

The complete loop must be validated wherever applicable.

---

# 39. SESSION MANAGEMENT RULE

Every intelligence development session must follow:

```text
MASTER REQUIREMENT
↓
PRODUCT QUESTION
↓
UX CONTRACT
↓
EVIDENCE CONTRACT
↓
ARCHITECTURE
↓
IMPLEMENTATION
↓
VALIDATION
↓
MASTER REQUIREMENTS CHECK
↓
GITHUB BACKUP
↓
REPORT
↓
STOP
```

Do not allow:

```text
feature idea
→ code
→ more features
→ AI
→ database
→ endless scope
```

---

# 40. INTELLIGENCE SESSION SIZE

One session should implement **one meaningful intelligence capability**.

Examples:

* source-level weakness evidence
* recommendation evidence model
* grounded retrieval contract
* explanation evidence contract

Do not implement an entire intelligence subsystem in one session.

Estimated target:

**1 focused session per capability**, followed by validation.

---

# 41. MANDATORY GITHUB RULE

Every intelligence session must:

1. verify remote
2. verify branch
3. preserve local history
4. commit appropriate changes
5. push `origin main`
6. verify local HEAD
7. verify `git ls-remote origin main`
8. confirm exact SHA equality

No force-push.

No history rewriting.

No claiming backup without verification.

---

# 42. PERMANENT DRIFT CHECK

Before every future intelligence session, ask:

> **Does this make SHADOW LEARN better at understanding the student's academic state and helping them take a grounded next action?**

If the answer is no:

**STOP.**

If the feature primarily adds:

* technical complexity
* decorative UI
* generic AI behavior
* isolated utilities
* impressive demos
* statistics without decisions

then it is likely product drift.

---

# 43. FINAL PRODUCT TEST

The Intelligence Layer is ultimately successful when a student can open SHADOW LEARN and reasonably experience:

> **"The SYSTEM knows what academic material I have, understands evidence from what I have done, can show where I need attention, can explain why, and can help me decide what to do next using my actual material."**

That is the destination.

Not:

> "SHADOW LEARN has an AI chatbot."

---

# 44. CURRENT IMPLEMENTATION BOUNDARY

At baseline `8e4cb1c`:

Already implemented and verified:

* academic hierarchy
* academic ingestion/reconciliation
* extraction
* search
* quiz
* flashcards
* listener recording
* offline STT engine
* progression
* academic progress
* export/import
* SYSTEM Home
* cross-link actions
* five-group navigation
* Home Quick Actions

The next implementation work must build **on top of this verified engine room**.

Do not rewrite stable foundations unless a concrete intelligence requirement proves they are insufficient.

---

# 45. IMMEDIATE NEXT STEP

Before implementing I1:

**DESIGN ONLY.**

The next session must define the:

### I1 EVIDENCE CONTRACT

Specifically:

1. What evidence objects exist?
2. What existing Room rows feed each evidence type?
3. How are quiz mistakes represented?
4. How are flashcard review signals represented?
5. How are source files/chunks connected to mistakes?
6. What constitutes an observed weakness?
7. What constitutes insufficient evidence?
8. How is evidence scoped by Year/Semester/Module/Week/File?
9. What exact UI should Home/Academic Status display?
10. Which parts are derived versus persisted?

Do **not** implement I1 until this contract is reviewed and accepted.

---

# MASTER PRINCIPLE

## SHADOW LEARN DOES NOT EXIST TO GENERATE AI.

## SHADOW LEARN EXISTS TO UNDERSTAND THE STUDENT'S ACADEMIC STATE AND HELP THEM ACT ON IT.

AI is only one possible mechanism inside that system.

The system must remain:

**GROUNDED → EXPLAINABLE → ACTIONABLE → HONEST → PRIVATE → STUDENT-CONTROLLED**
