package com.prasbin.shadowlearn.data.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I8 assistant intent classification tests (pure, deterministic).
 * Covers intent parsing behavior for the first vertical slice: EXPLAIN,
 * REVISE, UNSUPPORTED, topic extraction, and determinism.
 */
class AssistantEngineTest {

    @Test
    fun reviseIntent_fromWeaknessQuestion() {
        val out = AssistantEngine.classify("What should I revise next?")
        assertEquals(AssistantIntentType.REVISE, out.type)
        assertNull(out.topic)
    }

    @Test
    fun reviseIntent_fromWeakAreasQuestion() {
        val out = AssistantEngine.classify("Tell me my weaknesses")
        assertEquals(AssistantIntentType.REVISE, out.type)
        assertNull(out.topic)
    }

    @Test
    fun reviseIntent_fromStrugglingPhrase() {
        val out = AssistantEngine.classify("I have been struggling with mitosis")
        assertEquals(AssistantIntentType.REVISE, out.type)
        assertNull(out.topic)
    }

    @Test
    fun explainIntent_extractsTopic() {
        val out = AssistantEngine.classify("Explain photosynthesis")
        assertEquals(AssistantIntentType.EXPLAIN, out.type)
        assertEquals("photosynthesis", out.topic)
    }

    @Test
    fun explainIntent_whatIs_extractsTopic() {
        val out = AssistantEngine.classify("What is neural network")
        assertEquals(AssistantIntentType.EXPLAIN, out.type)
        assertEquals("neural network", out.topic)
    }

    @Test
    fun unsupportedInput_isNotMisclassified() {
        val out = AssistantEngine.classify("Hello there")
        assertEquals(AssistantIntentType.UNSUPPORTED, out.type)
        assertNull(out.topic)
    }

    @Test
    fun explainWithoutExtractableTopic_isUnsupported() {
        val out = AssistantEngine.classify("Explain")
        assertEquals(AssistantIntentType.UNSUPPORTED, out.type)
        assertNull(out.topic)
    }

    @Test
    fun classify_isDeterministic() {
        val first = AssistantEngine.classify("Explain photosynthesis")
        val second = AssistantEngine.classify("Explain photosynthesis")
        assertEquals(first, second)
        assertEquals(AssistantIntentType.EXPLAIN, first.type)
        assertEquals("photosynthesis", first.topic)
    }

    @Test
    fun explainTopic_isTrimmedOfPunctuation() {
        val out = AssistantEngine.classify("Explain photosynthesis!")
        assertEquals(AssistantIntentType.EXPLAIN, out.type)
        assertEquals("photosynthesis", out.topic)
    }
}
