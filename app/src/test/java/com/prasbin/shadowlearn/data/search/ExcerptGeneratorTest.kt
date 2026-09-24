package com.prasbin.shadowlearn.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excerpt generation: context, truncation, sanitization, highlight offsets. */
class ExcerptGeneratorTest {

    private fun generate(text: String, terms: List<String>) =
        ExcerptGenerator.generate(text, terms)

    @Test
    fun termContextVisibleInExcerpt() {
        val text = "The quick brown fox jumps over the lazy dog. Neural networks dominate modern ML. " +
            "Deep learning relies on gradient descent." + " tail ".repeat(30)
        val excerpt = generate(text, listOf("gradient")).text
        assertTrue(excerpt.contains("gradient"))
    }

    @Test
    fun longContentIsTruncated() {
        val text = ("word ".repeat(500)) + "neural networks" + (" tail ".repeat(500))
        val excerpt = generate(text, listOf("neural")).text
        assertTrue(excerpt.length <= ExcerptGenerator.DEFAULT_WINDOW_BEFORE + ExcerptGenerator.DEFAULT_WINDOW_AFTER + 2)
        assertTrue(excerpt.startsWith("…"))
        assertTrue(excerpt.endsWith("…"))
    }

    @Test
    fun noTermFallbackShowsLeadingWindow() {
        val text = "Alpha beta gamma " .repeat(100) + "delta"
        val excerpt = generate(text, listOf("zzz")).text
        assertTrue(excerpt.contains("Alpha beta"))
    }

    @Test
    fun controlCharactersAreSanitized() {
        val text = "neural\u0000network\u0007\r\n\t gradient"
        val excerpt = generate(text, listOf("gradient")).text
        assertFalse(excerpt.contains('\u0000'))
        assertFalse(excerpt.contains('\u0007'))
        assertFalse(excerpt.contains('\r'))
    }

    @Test
    fun whitespaceIsCollapsed() {
        val text = "neural     networks      and    gradient   "
        val excerpt = generate(text, listOf("gradient")).text
        assertFalse(excerpt.contains("  "))
    }

    @Test
    fun highlightRangesPointAtTerm() {
        val text = "neural networks are a class of models. Neural methods scale."
        val excerpt = generate(text, listOf("neural"))
        assertTrue(excerpt.highlightRanges.isNotEmpty())
        excerpt.highlightRanges.forEach { r ->
            assertEquals("neural", excerpt.text.substring(r.first, r.last + 1).lowercase())
        }
    }

    @Test
    fun highlightsMergedAndBounded() {
        val text = ("neural ").repeat(10) + "gradient"
        val excerpt = generate(text, listOf("neural"))
        assertTrue(excerpt.highlightRanges.size <= ExcerptGenerator.MAX_HIGHLIGHTS)
        excerpt.highlightRanges.forEach { r ->
            assertTrue(r.first in 0 until excerpt.text.length)
            assertTrue(r.last in 0 until excerpt.text.length)
        }
    }

    @Test
    fun emptyTextProducesEmptyExcerpt() {
        val excerpt = generate("", listOf("neural"))
        assertEquals("", excerpt.text)
        assertTrue(excerpt.highlightRanges.isEmpty())
    }
}