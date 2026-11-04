package com.prasbin.shadowlearn.data.intelligence

/**
 * I8 academic assistant intent contract (docs/I8_DESIGN_CONTRACT.md §5).
 *
 * Deterministic, offline, pure intent classification for the first vertical
 * slice. The assistant accepts only explicit, classified intents:
 *
 * - `EXPLAIN`     → the user asks to explain a topic
 *                   (e.g. "Explain photosynthesis").
 * - `REVISE`      → the user asks for study guidance
 *                   (e.g. "What should I revise next?").
 * - `UNSUPPORTED` → anything else, including unparseable or ambiguous
 *                   input; must surface the honest rephrasing state.
 *
 * Same input ⇒ same intent. No model, no network, no Room, no persistence.
 * Intent parsing stays isolated here so the UI never re-implements grammar.
 */
enum class AssistantIntentType {
    EXPLAIN,
    REVISE,
    UNSUPPORTED
}

data class AssistantIntent(
    val type: AssistantIntentType,
    val topic: String? = null
)

object AssistantEngine {

    private val reviseTriggers = listOf(
        "what should i study",
        "what should i revise",
        "should i revise",
        "should i study",
        "revise next",
        "study next",
        "my weaknesses",
        "my weak areas",
        "tell me my weaknesses",
        "what are my weaknesses",
        "weak areas",
        "weakness",
        "weak",
        "struggling"
    )

    private data class ExplainTrigger(val phrase: String, val offset: Int)

    private val explainTriggers = listOf(
        ExplainTrigger(phrase = "explain", offset = 7),
        ExplainTrigger(phrase = "what is", offset = 7),
        ExplainTrigger(phrase = "tell me about", offset = 13),
        ExplainTrigger(phrase = "define", offset = 7),
        ExplainTrigger(phrase = "describe", offset = 9)
    )

    /**
     * Classifies a typed user question into one assistant intent.
     * Topic is only set for [AssistantIntentType.EXPLAIN] (the subject to
     * ground the explanation on); revise intents need no topic.
     */
    fun classify(input: String): AssistantIntent {
        val text = input.trim().lowercase()
        if (text.isBlank()) {
            return AssistantIntent(AssistantIntentType.UNSUPPORTED, null)
        }
        for (trigger in reviseTriggers) {
            if (text.contains(trigger)) {
                return AssistantIntent(AssistantIntentType.REVISE, null)
            }
        }
        for (trigger in explainTriggers) {
            val idx = text.indexOf(trigger.phrase)
            if (idx < 0) continue
            val topic = text.substring(idx + trigger.offset).trim().trim('.', '?', '!', ':', ',', ';').trim()
            if (topic.isNotEmpty()) {
                return AssistantIntent(AssistantIntentType.EXPLAIN, topic)
            }
        }
        return AssistantIntent(AssistantIntentType.UNSUPPORTED, null)
    }
}
