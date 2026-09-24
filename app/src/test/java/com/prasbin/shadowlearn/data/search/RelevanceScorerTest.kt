package com.prasbin.shadowlearn.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Determinism + behavior of the SHADOW LEARN heuristic relevance score
 * (see RelevanceScorer kdoc for the formula).
 */
class RelevanceScorerTest {

    private fun score(
        string: String,
        text: String,
        fileName: String = "notes.pdf",
        module: String = "AI",
        charCount: Int = text.length
    ) = RelevanceScorer.score(SearchQuery.parse(string), text, fileName, module, charCount)

    @Test
    fun blankQueryScoresZero() {
        assertEquals(0.0, score("", "neural networks"), 0.0)
        assertEquals(0.0, score("   ", "neural networks"), 0.0)
    }

    @Test
    fun exactMatchOutscoresPartialOnly() {
        val exact = score("gradient", "gradient descent training")
        val partial = score("gradient", "gradients descent training")
        assertTrue(exact > partial)
    }

    @Test
    fun multiTermCoverageDominatesSingleTerm() {
        val both = score("neural network", "neural network training loop")
        val one = score("neural network", "neural training loop")
        assertTrue(both > one)
    }

    @Test
    fun moreOccurrencesScoreHigher() {
        val many = score("gradient", "gradient gradient gradient descent")
        val one = score("gradient", "gradient descent")
        assertTrue(many > one)
    }

    @Test
    fun fileNameMatchBoostsScore() {
        val inTitle = score("neural", "backpropagation", fileName = "neural-networks.pdf")
        val absent = score("neural", "backpropagation", fileName = "slides.pdf")
        assertTrue(inTitle > absent)
    }

    @Test
    fun moduleNameMatchBoostsScore() {
        val inModule = score("deep", "learning is hard", fileName = "notes.pdf", module = "Deep Learning")
        val absent = score("deep", "learning is hard", fileName = "notes.pdf", module = "Intro")
        assertTrue(inModule > absent)
    }

    @Test
    fun lengthNormalizationPreventsLongChunkDominance() {
        val longPadding = "filler ".repeat(200)
        val short = score("gradient", "gradient descent")
        val longButSimilar = score("gradient", longPadding + " gradient descent")
        assertTrue(short > longButSimilar)
    }

    @Test
    fun scoringIsDeterministic() {
        val text = "neural networks learn by gradient descent and backpropagation"
        val a = score("network gradient", text)
        repeat(5) { assertEquals(a, score("network gradient", text), 0.0) }
    }

    @Test
    fun equalScoresForIdenticalInputs() {
        val a = score("neural", "neural network", fileName = "n.pdf", module = "M")
        val b = score("neural", "neural network", fileName = "n.pdf", module = "M")
        assertEquals(a, b, 0.0)
    }

    @Test
    fun prefixOccurrencesCountAndAreDeterministic() {
        val text = "networks net neural"
        val s1 = score("net", text)
        val s2 = score("net", text)
        assertEquals(s1, s2, 0.0)
        assertTrue(s1 > 0.0)
    }

    @Test
    fun caseInsensitiveMatching() {
        val upper = score("gradient", "GRADIENT descent")
        val lower = score("gradient", "gradient descent")
        assertEquals(upper, lower, 0.0)
    }
}