package com.prasbin.shadowlearn.data.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I4 explanation engine tests (pure, deterministic). The engine is
 * extractive by construction: output sentences are always substrings of
 * input chunks, so general-knowledge contamination is structurally
 * impossible — asserted, not assumed.
 */
class ExplanationEngineTest {

    private fun chunk(
        id: Long,
        text: String,
        file: Long = 7,
        name: String = "cell-biology.pdf",
        index: Int = 0
    ) = RetrievedChunk(
        chunkId = id, academicFileId = file, fileName = name, excerpt = text,
        pageNumber = 2, chunkIndex = index,
        weekLabel = "Week 3", moduleName = "Biology", weekId = 42
    )

    private fun retrieved(vararg chunks: RetrievedChunk) =
        RetrievalResult.Retrieved(chunks.toList())

    @Test
    fun oneChunk_isExplainedFromItsSentences() {
        val out = ExplanationEngine.explain(
            "Explain the material associated with this weak area.",
            retrieved(chunk(1, "Mitosis divides one cell into two identical cells. It has several phases."))
        )
        assertEquals(ExplanationStatus.EXPLAINED, out.status)
        assertTrue(out.explanation.contains("Mitosis divides one cell into two identical cells."))
        assertEquals(1, out.sources.size)
        assertEquals("cell-biology.pdf", out.sources[0].fileName)
        assertEquals(1L, out.sources[0].chunkId)
        assertEquals(42L, out.sources[0].weekId)
    }

    @Test
    fun multipleChunks_combineDeterministicallyInOrder() {
        val a = retrieved(
            chunk(2, "Second one. Second two.", index = 1),
            chunk(1, "First one. First two.", index = 0)
        )
        val first = ExplanationEngine.explain("request", a)
        val second = ExplanationEngine.explain("request", a)
        assertEquals(first, second)
        assertEquals(ExplanationStatus.EXPLAINED, first.status)
        // Chunk order respected, max 3 sentences total.
        val text = first.explanation
        assertTrue(text.indexOf("First one.") < text.indexOf("First two."))
        assertTrue(text.indexOf("First two.") < text.indexOf("Second one."))
        assertTrue(!text.contains("Second two."))
        assertEquals("3 passages · cell-biology.pdf", first.groundingBasis)
    }

    @Test
    fun explanationContainsOnlySourceSupportedInformation() {
        val text = "Osmosis moves water across membranes."
        val out = ExplanationEngine.explain(
            "request",
            retrieved(chunk(1, text))
        )
        // Every explanation sentence is a substring of some chunk.
        val sentences = ExplanationEngine.splitSentences(out.explanation.substringAfter(": "))
        assertTrue(sentences.isNotEmpty())
        for (s in sentences) {
            assertTrue(text.contains(s))
        }
    }

    @Test
    fun unsupportedFact_isNeverIntroduced() {
        // Source supports A; general knowledge B ("diffusion") is true but absent.
        val out = ExplanationEngine.explain(
            "request",
            retrieved(chunk(1, "Osmosis moves water across membranes."))
        )
        assertTrue(!out.explanation.contains("iffusion", ignoreCase = true))
        assertTrue(out.sources.all { it.excerpt.contains("Osmosis") })
    }

    @Test
    fun missingSource_mapsNoSource() {
        val out = ExplanationEngine.explain("request", RetrievalResult.NoSource)
        assertEquals(ExplanationStatus.NO_SOURCE, out.status)
        assertEquals("", out.explanation)
        assertTrue(out.sources.isEmpty())
    }

    @Test
    fun fileWithoutChunks_mapsSourceNotIndexed() {
        val out = ExplanationEngine.explain("request", RetrievalResult.SourceNotIndexed)
        assertEquals(ExplanationStatus.SOURCE_NOT_INDEXED, out.status)
        assertEquals("", out.explanation)
    }

    @Test
    fun noMatch_mapsNoMatch() {
        val out = ExplanationEngine.explain("request", RetrievalResult.NoMatch)
        assertEquals(ExplanationStatus.NO_MATCH, out.status)
        assertEquals("", out.explanation)
    }

    @Test
    fun blankChunks_yieldInsufficientEvidence() {
        val out = ExplanationEngine.explain(
            "request",
            retrieved(chunk(1, "   "))
        )
        assertEquals(ExplanationStatus.INSUFFICIENT_EVIDENCE, out.status)
        assertEquals("", out.explanation)
        assertTrue(out.sources.isEmpty())
    }

    @Test
    fun provenance_survivesExactly() {
        val out = ExplanationEngine.explain(
            "request",
            retrieved(chunk(9, "Alpha. Beta.", file = 3, name = "genetics.pdf", index = 4))
        )
        val src = out.sources.single()
        assertEquals(3L, src.academicFileId)
        assertEquals("genetics.pdf", src.fileName)
        assertEquals(9L, src.chunkId)
        assertEquals(4, src.chunkIndex)
        assertEquals(2L, src.pageNumber)
        assertEquals("Week 3", src.weekLabel)
        assertEquals("Biology", src.moduleName)
    }

    @Test
    fun generatedIsLabeled_notQuotedAsSource() {
        val out = ExplanationEngine.explain(
            "request",
            retrieved(chunk(1, "Mitosis divides cells."))
        )
        // Template glue is fixed and factual-claim-free; sentences verbatim.
        assertTrue(out.explanation.startsWith("Based on 1 passage from cell-biology.pdf: "))
    }
}
