# I8 — NATURAL ACADEMIC ASSISTANT DESIGN CONTRACT

## 1. Product Goal
To provide a natural-language interface for students to interact with their personal, offline-first academic knowledge base. The assistant should act as a grounded academic intelligence system, deriving answers from the user's own lecture materials and study evidence, rather than a generic chatbot.

## 2. Hard Requirements Summary
- Ground answers in user's imported material and existing evidence (I1-I7).
- Clearly distinguish verified content from generated content.
- Show sources and allow "Open Source" navigation.
- Honestly report missing evidence (empty corpus, no matches, stale data, unsupported questions).
- Respect current academic-year/semester scope.
- Maintain offline-first behavior (no cloud, network, LLMs, API keys).
- Reuse existing intelligence components (I1-I7).
- Integrate with existing 5-group navigation and SYSTEM HUD visual language.
- No generic chatbot shell, decorative AI, autonomous actions, or unrelated expansion.

## 3. User Journeys and Entry Point

### Entry Point
A dedicated "Assistant" button or tab will be added to the bottom navigation bar, replacing one of the existing tabs or being introduced as a 6th tab. Given the constraint to keep the existing five-group navigation, a new top-level icon on the `HOME` screen seems most appropriate for the first iteration. A floating action button (FAB) on relevant screens (e.g., Search results, Academic hierarchy, Study) could also be considered for quick access to context-aware questions.

**Decision Needed:** Exact UI entry point (e.g., new button on Home, or dedicated tab replacing an existing one). For initial design, assume a simple entry point accessible from the `HOME` screen, leading to a new `AssistantScreen`.

### User Journey
1. User navigates to the Assistant via its entry point.
2. `AssistantScreen` displays a prompt to the user to type or speak a question.
3. User inputs a natural language question.
4. Assistant processes the question using existing intelligence layers.
5. Assistant displays a grounded answer, including citations and "OPEN SOURCE" links where applicable.
6. User can tap "OPEN SOURCE" to navigate to the relevant academic material.
7. User can ask follow-up questions.

## 4. Supported Question Intents

Initial small, explicit set of intents, primarily extractive or summary-based:

1.  **Explain [Topic/Term]:**
    *   **Retrieval:** Use `I3_RETRIEVAL` (exact-chunk → file → scoped-FTS) with the extracted topic/term as the query.
    *   **Evidence Validation:** Requires FTS matches within the current semester's indexed corpus.
    *   **Answer:** Extractive snippet(s) from `I4_EXPLANATIONS` (lead sentences + fixed glue) or direct search results. If multiple relevant chunks, provide a concise summary or the top 1-3 most relevant snippets.
    *   **Citation:** Direct link to `Routes.lecture(fileId, chunkId)`.
    *   **Honest Responses:**
        *   "I couldn't find relevant information about '[Topic/Term]' in your current semester's materials." (No FTS matches).
        *   "Your current semester has no indexed academic materials. Please import some files first." (Empty corpus).

2.  **Summarize [File/Week/Module]:**
    *   **Retrieval:** Use `I3_RETRIEVAL` (scoped-FTS for entire file/week/module) to gather all relevant chunks.
    *   **Evidence Validation:** Requires successfully indexed chunks within the specified scope.
    *   **Answer:** A concatenated summary of key sentences from the most relevant chunks (e.g., top 5-10 chunks by `RelevanceScorer` from `SearchDao`). This will be a simple extractive summary, not generative.
    *   **Citation:** Links to `Routes.hierarchyFile(fileId)` or `Routes.hierarchyWeek(weekId)` etc.
    *   **Honest Responses:**
        *   "I couldn't find any indexed content for [File/Week/Module] in your current semester."
        *   "Your current semester has no indexed academic materials. Please import some files first."

3.  **What should I revise next? / My weaknesses:**
    *   **Retrieval:** Directly query `I2_RECOMMENDATIONS` (RecommendationEngine) for the primary recommendation and `I1_EVIDENCE_CONTRACT` (WeaknessEngine) for weakness signals.
    *   **Evidence Validation:** Requires existing quiz/review history.
    *   **Answer:** Present the existing `RecommendationEngine` output.
    *   **Citation:** Implicitly link to relevant study actions (e.g., "Review due cards," "Practice this material," with navigation to `Routes.FLASHCARDS` or `Routes.practiceQuiz`).
    *   **Honest Responses:**
        *   "I need more study activity (quizzes or flashcard reviews) to identify your revision needs." (Insufficient evidence).

4.  **How does [Concept A] connect to [Concept B]?**
    *   **Retrieval:** Use `I6_CROSS_MATERIAL_ENGINE` to find shared terms and related files/chunks. Query `I3_RETRIEVAL` for both concepts.
    *   **Evidence Validation:** Requires FTS matches for both concepts and shared terms.
    *   **Answer:** Provide snippets from `I4_EXPLANATIONS` for both concepts, highlighting shared terms and listing connecting materials from `I6`. The connection explanation will be extractive, concatenating snippets and listing shared terms.
    *   **Citation:** Links to relevant files/chunks via `Routes.lecture(fileId, chunkId)`.
    *   **Honest Responses:**
        *   "I couldn't find relevant material for one or both concepts in your current semester."
        *   "I found material for '[Concept A]' but no shared terms or connections to '[Concept B]'."

## 5. Verified-Content vs. Generated-Content Boundary
- All answers will be **extractive** (direct quotes from user's material) or **derived** (computed from existing internal intelligence, e.g., recommendations).
- There will be **no generative AI** at this phase. All text presented as "explanation" or "summary" will be a direct subset of indexed text, possibly concatenated or reordered.
- Every piece of information will link back to its verifiable source.

## 6. Offline Implementation Options and Trade-offs

### Option 1: Intent-based Routing + Existing Intelligence (Chosen Approach for I8)
- **Mechanism:** Natural language question is parsed for keywords/phrases to identify one of the supported intents (e.g., "explain X", "summarize Y", "revise").
- **Implementation:** Simple regex or string matching on the user input.
- **Pros:**
    *   Leverages existing I1-I7 components directly.
    *   Absolutely offline-first with no new model dependencies.
    *   Predictable and deterministic behavior.
    *   Minimal APK size/performance impact.
- **Cons:**
    *   Limited to explicit intents; not a "conversational" AI.
    *   Requires precise phrasing from the user for recognition.
- **Trade-offs:** Prioritizes robustness, offline guarantees, and minimal resource impact over broad conversational capability. This aligns with hard requirements.

### Option 2: On-device Embeddings + Semantic Search (Future Iteration)
- **Mechanism:** Embed user question and all academic chunks into vectors; perform nearest-neighbor search.
- **Pros:** More robust to phrasing variations, potentially broader understanding.
- **Cons:**
    *   Requires a new on-device embedding model (significant APK size/memory).
    *   Model inference adds latency.
    *   Requires careful design to maintain privacy/offline-first (no model updates from network).
    *   Increases complexity (vector database, quantization, model loading).
- **Trade-offs:** Higher intelligence at the cost of resource footprint and complexity. **Not for I8.**

### Option 3: On-device Small Language Model (Future Iteration)
- **Mechanism:** Fine-tuned small LLM on device to generate answers from retrieved context.
- **Pros:** Truly generative, more conversational.
- **Cons:**
    *   Very high APK size/memory/performance cost (even small LLMs are large).
    *   Difficult to guarantee groundedness and honesty without complex RAG.
    *   Hard to achieve desired "SYSTEM HUD" tone and not sound generic.
- **Trade-offs:** Highest intelligence but highest cost/risk. **Not for I8.**

## 7. Privacy, Performance, Memory, and APK Size Implications

- **Privacy:** Unchanged (all processing remains local, no data leaves device).
- **Performance:** Minimal impact. Intent classification (regex) is fast. Existing I1-I7 calls are already optimized.
- **Memory:** Minimal impact. No new large data structures or models loaded.
- **APK Size:** Minimal impact. New code is primarily logic for intent routing and UI.

## 8. UI States and Acceptance Criteria (360dp phone-width)

### AssistantScreen
- **Initial State:** Text input field with a hint (e.g., "Ask me a question about your academic material..."). Microphone icon for speech input (leveraging Vosk I7).
- **Loading State:** Progress indicator, e.g., "Thinking..."
- **Answer Display:**
    *   Question is echoed.
    *   Answer text (extractive/derived) is displayed, with bolding for matched terms.
    *   Citations/source links are clearly visible.
    *   "OPEN SOURCE" button/link navigates to `Routes.lecture(fileId, chunkId)`.
- **Error States / Honest Responses:** Specific messages for:
    *   "I couldn't understand your question. Please try rephrasing it." (No intent matched).
    *   "I couldn't find relevant information about '[Topic/Term]' in your current semester's materials."
    *   "Your current semester has no indexed academic materials. Please import some files first."
    *   "I need more study activity (quizzes or flashcard reviews) to identify your revision needs."
    *   "I found material for '[Concept A]' but no shared terms or connections to '[Concept B]'."
- **360dp behavior:** Layout will be responsive, using existing Compose patterns for small screens. Max-width constraints on text, scrolling, and safe areas will ensure readability.

## 9. Test Strategy
- **Unit Tests:**
    *   `AssistantEngineTest`: Pure unit tests for intent recognition and dispatch logic (mocking I1-I7 components).
    *   `AssistantViewModelTest`: ViewModel interactions and state flows.
    *   `AssistantScreenTest`: Compose UI tests for state rendering and interaction.
- **Integration Tests (Room/FTS):**
    *   Tests covering `AssistantEngine` calling real I3/I6 components over a seeded Room database.
- **Manual Device Validation:**
    *   On Redmi Note 14 5G: Verify UI, input (text/speech), honest responses for empty/missing data, and correct navigation via "OPEN SOURCE". Test each supported intent with various inputs.

## 10. Non-Goals and Unresolved Decisions

### Non-Goals for I8 (First Iteration)
- Generative AI / LLM integration (either local or cloud).
- Semantic search (no on-device embedding models).
- Broader conversational abilities beyond explicit intents.
- Autonomous actions (e.g., "start a quiz for me").
- Personalized "mastery scores" or "confidence levels" (beyond existing honest weakness signals).
- Cross-semester questions (current semester scope is a hard boundary).

### Unresolved Decisions
- **Exact UI Entry Point:** Deciding between a new button on the Home screen or a dedicated tab (if an existing tab can be repurposed).
- **Speech Input Integration:** How the Vosk transcript from `I7` (if Assistant uses speech input) is passed to the Assistant for intent parsing. (Likely direct text input, for now, with speech-to-text as a separate user action that populates the text field).

## 11. Estimated Sessions and Time
- Design (this session): 1 session.
- Implementation:
    *   UI entry point + `AssistantScreen` (scaffold): 1-2 sessions.
    *   `AssistantEngine` (intent parser + dispatcher): 2-3 sessions.
    *   Wiring each intent to existing I1-I7 components: 3-4 sessions.
    *   UI states, error handling, "OPEN SOURCE" navigation: 2-3 sessions.
    *   Testing (unit, integration, manual device): 3-4 sessions.
- Total estimated implementation: **~11-16 sessions** (approx. 2-3 weeks, assuming average 1.5 sessions/day).

## 12. Next Action
Present this I8 design for review and approval. Once approved, the next action will be to implement the "User Journeys and Entry Point" (starting with the UI scaffolding).
