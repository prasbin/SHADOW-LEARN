package com.prasbin.shadowlearn.data.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 extractor tests (pure JVM): per-format real-bytes extraction,
 * page/slide references, honest failures and chunking.
 */
class ExtractorsTest {

    @Test
    fun pdf_twoPagesWithReferencesAndOperators() {
        val doc = PdfTextExtractor.extract(Fixtures.pdfPages())
        assertEquals(2, doc.sections.size)
        assertEquals(1L, doc.sections[0].pageNumber)
        assertEquals(2L, doc.sections[1].pageNumber)
        assertTrue(doc.sections[0].text.contains("Neural networks"))
        assertTrue(doc.sections[0].text.contains("gradient descent"))
        assertTrue(doc.sections[0].text.contains("backpropagation"))
        assertTrue(doc.sections[1].text.contains("optimization"))
        assertTrue(doc.sections[1].text.contains("algorithms"))
    }

    @Test
    fun pdf_noTextPages_yieldsEmptyDocumentNotError() {
        val doc = PdfTextExtractor.extract(Fixtures.pdfNoText())
        assertTrue(doc.sections.isNotEmpty())
        assertEquals("", doc.sections.first().text.trim())
    }

    @Test
    fun pdf_garbage_failsLoudly() {
        try {
            PdfTextExtractor.extract("this is not a pdf at all".toByteArray())
            throw AssertionError("expected ExtractionException")
        } catch (e: ExtractionException) {
            assertTrue(e.message!!.contains("no objects"))
        }
    }

    @Test
    fun docx_paragraphsAndPagesByPageBreak() {
        val doc = OoxmlTextExtractor.extract(Fixtures.docxPages(), OoxmlTextExtractor.Kind.DOCX)
        assertEquals(2, doc.sections.size)
        assertEquals(1L, doc.sections[0].pageNumber)
        assertEquals(2L, doc.sections[1].pageNumber)
        assertTrue(doc.sections[0].text.contains("Alpha beta gamma"))
        assertTrue(doc.sections[0].text.contains("Delta"))
        // Page break ends the second section; 'Eta theta' opens page 2.
        assertTrue(doc.sections[1].text.contains("Eta theta"))
        assertFalse(doc.sections[1].text.contains("Alpha"))
    }

    @Test
    fun docx_missingPart_failsLoudly() {
        try {
            OoxmlTextExtractor.extract(Fixtures.docxMissingPartBytes(), OoxmlTextExtractor.Kind.DOCX)
            throw AssertionError("expected ExtractionException")
        } catch (e: ExtractionException) {
            assertTrue(e.message!!.contains("document.xml"))
        }
    }

    @Test
    fun pptx_twoSlidesWithSlideReferences() {
        val doc = OoxmlTextExtractor.extract(Fixtures.pptxSlides(), OoxmlTextExtractor.Kind.PPTX)
        assertEquals(2, doc.sections.size)
        assertEquals(1L, doc.sections[0].pageNumber)
        assertEquals(2L, doc.sections[1].pageNumber)
        assertTrue(doc.sections[0].text.contains("Neural networks"))
        assertTrue(doc.sections[1].text.contains("backpropagation"))
    }

    @Test
    fun txt_singleSectionNoPage() {
        val doc = PlainTextExtractor.extract(Fixtures.txtBytes("hello extraction world"))
        assertEquals(1, doc.sections.size)
        assertNull(doc.sections[0].pageNumber)
        assertTrue(doc.sections[0].text.contains("hello extraction world"))
    }

    @Test
    fun registry_dispatchAndUnsupportedLegacy() {
        assertEquals(ExtractionFormat.Kind.PDF, ExtractionFormat.kindFor("pdf"))
        assertEquals(ExtractionFormat.Kind.DOCX, ExtractionFormat.kindFor("docx"))
        assertEquals(ExtractionFormat.Kind.PPTX, ExtractionFormat.kindFor("pptx"))
        assertEquals(ExtractionFormat.Kind.TEXT, ExtractionFormat.kindFor("java"))
        assertEquals(ExtractionFormat.Kind.TEXT, ExtractionFormat.kindFor("md"))
        assertEquals(ExtractionFormat.Kind.UNSUPPORTED, ExtractionFormat.kindFor("doc"))
        assertEquals(ExtractionFormat.Kind.UNSUPPORTED, ExtractionFormat.kindFor("ppt"))

        assertTrue(ExtractionFormat.extract(ExtractionFormat.Kind.TEXT, Fixtures.txtBytes("x y")).charCount > 0)
        val unsupported = try {
            ExtractionFormat.extract(ExtractionFormat.Kind.UNSUPPORTED, byteArrayOf(1, 2, 3))
            null
        } catch (e: ExtractionException) {
            e
        }
        assertTrue(unsupported != null && unsupported.message!!.contains("not supported"))
    }

    @Test
    fun chunker_smallSectionsOnePieceKeepsPage() {
        val doc = PlainTextExtractor.extract(Fixtures.txtBytes("one two three"))
        val pieces = TextChunker.chunk(doc)
        assertEquals(1, pieces.size)
        assertEquals(0, pieces[0].index)
        assertNull(pieces[0].pageNumber)
        assertEquals("one two three", pieces[0].text)
    }

    @Test
    fun chunker_longSectionSplitsOnWhitespaceBoundedByMax() {
        val doc = PlainTextExtractor.extract(Fixtures.txtLongBytes())
        val pieces = TextChunker.chunk(doc)
        assertTrue("expected multiple pieces, got ${pieces.size}", pieces.size > 1)
        pieces.forEachIndexed { i, p ->
            assertEquals(i, p.index)
            assertTrue("piece len ${p.text.length}", p.text.length <= TextChunker.MAX_CHUNK_CHARS)
            assertFalse("no trailing space", p.text.endsWith(" "))
            assertFalse("no leading space", p.text.startsWith(" "))
        }
        // Ordering is contiguous: first word of piece k+1 follows last piece k.
        val joined = pieces.joinToString(" ") { it.text }
        assertTrue(joined.startsWith("alpha bravo charlie"))
    }

    @Test
    fun chunker_whitespaceOnlyDocumentProducesNoPieces() {
        val doc = PlainTextExtractor.extract(Fixtures.whitespaceTxtBytes())
        assertEquals(0, TextChunker.chunk(doc).size)
    }

    @Test
    fun chunker_pdfPiecesKeepPageReference() {
        val doc = PdfTextExtractor.extract(Fixtures.pdfPages())
        val pieces = TextChunker.chunk(doc)
        pieces.forEach { p -> assertTrue(p.pageNumber != null) }
    }
}