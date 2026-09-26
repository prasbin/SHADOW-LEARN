package com.prasbin.shadowlearn.data.cards

import com.prasbin.shadowlearn.data.db.CardChunkRow
import com.prasbin.shadowlearn.data.db.MistakeRow
import com.prasbin.shadowlearn.data.db.ReadySegmentRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Phase 8 tests for the deterministic card generator. Verifies the three
 * sources (chunks, quiz mistakes, READY segments), contentKey determinism,
 * dedup, PENDING/FAILED exclusion, and verbatim content integrity.
 */
class CardGeneratorTest {

    private fun chunk(id: Long, text: String, page: Long? = 1) = CardChunkRow(
        chunkId = id, text = text, pageNumber = page,
        academicFileId = 1, fileName = "notes.pdf", fileType = "pdf",
        moduleName = "AI", weekNumber = 2
    )

    private fun mistake(id: Long, prompt: String, answer: String) = MistakeRow(
        questionId = id, prompt = prompt, correctAnswer = answer,
        srcFileName = "quiz.pdf", srcFileType = "pdf", srcPage = 3
    )

    private fun segment(id: Long, text: String, status: String = "ready") = ReadySegmentRow(
        segmentId = id, transcript = text, transcriptStatus = status
    )

    // ---- chunk generation -------------------------------------------------

    @Test
    fun chunkGeneratesCardsWithVerbatimText() {
        val text = "Neural networks learn representations through backpropagation."
        val cards = CardGenerator.fromChunks(listOf(chunk(1, text)))
        assertTrue(cards.isNotEmpty())
        val card = cards.first()
        // Back must be a verbatim substring of the source.
        assertTrue(text.contains(card.back))
    }

    @Test
    fun chunkGeneratesDeterministicContentKey() {
        val text = "Gradient descent minimizes the loss function."
        val a = CardGenerator.fromChunks(listOf(chunk(1, text)))
        val b = CardGenerator.fromChunks(listOf(chunk(1, text)))
        assertEquals(a.map { it.contentKey }, b.map { it.contentKey })
    }

    @Test
    fun chunkContentKeyIncludesChunkIdAndTerm() {
        val cards = CardGenerator.fromChunks(listOf(chunk(7, "Machine learning models generalize well.")))
        assertTrue(cards.all { it.contentKey.startsWith("chunk:7:") })
    }

    @Test
    fun identicalCorpusProducesIdenticalCards() {
        val chunks = listOf(chunk(1, "Deep learning uses multiple layers."), chunk(2, "Recurrent networks process sequences."))
        val a = CardGenerator.fromChunks(chunks, rng = Random(42))
        val b = CardGenerator.fromChunks(chunks, rng = Random(42))
        assertEquals(a.size, b.size)
        assertEquals(a.map { it.front }, b.map { it.front })
        assertEquals(a.map { it.back }, b.map { it.back })
        assertEquals(a.map { it.contentKey }, b.map { it.contentKey })
    }

    @Test
    fun chunkCardsHaveCitation() {
        val cards = CardGenerator.fromChunks(listOf(chunk(1, "Artificial intelligence encompasses reasoning.")))
        assertTrue(cards.first().sourceLabel.contains("notes.pdf"))
        assertTrue(cards.first().sourceLabel.contains("PDF"))
        assertTrue(cards.first().sourceLabel.contains("PAGE 1"))
    }

    @Test
    fun chunkDoesNotInventFacts() {
        val text = "Photosynthesis converts sunlight into chemical energy."
        val cards = CardGenerator.fromChunks(listOf(chunk(1, text)))
        for (card in cards) {
            // Back must literally appear in the source.
            assertTrue(text.contains(card.back))
        }
    }

    @Test
    fun perChunkCapIsRespected() {
        val text = "Alpha beta gamma delta epsilon zeta eta theta iota kappa."
        val cards = CardGenerator.fromChunks(listOf(chunk(1, text)), maxPerChunk = 2)
        val forChunk = cards.filter { it.contentKey.startsWith("chunk:1:") }
        assertTrue(forChunk.size <= 2)
    }

    // ---- mistake generation ------------------------------------------------

    @Test
    fun mistakeGeneratesReviewCard() {
        val cards = CardGenerator.fromMistakes(listOf(mistake(42, "What is supervised learning?", "Learning from labelled data.")))
        assertEquals(1, cards.size)
        assertEquals("What is supervised learning?", cards[0].front)
        assertTrue(cards[0].back.contains("Learning from labelled data."))
        assertEquals("quiz:42", cards[0].contentKey)
    }

    @Test
    fun mistakeContentKeyUsesQuestionId() {
        val cards = CardGenerator.fromMistakes(listOf(mistake(99, "q", "a")))
        assertEquals("quiz:99", cards[0].contentKey)
    }

    @Test
    fun mistakeHasSourceCitation() {
        val cards = CardGenerator.fromMistakes(listOf(mistake(1, "q", "a")))
        assertTrue(cards[0].sourceLabel.contains("quiz.pdf"))
        assertTrue(cards[0].sourceLabel.contains("PAGE 3"))
    }

    @Test
    fun multipleMistakesGenerateDistinctKeys() {
        val cards = CardGenerator.fromMistakes(listOf(mistake(1, "q1", "a1"), mistake(2, "q2", "a2")))
        val keys = cards.map { it.contentKey }.toSet()
        assertEquals(cards.size, keys.size)
    }

    // ---- listener READY generation -----------------------------------------

    @Test
    fun readySegmentGeneratesLectureCard() {
        val text = "The professor explained gradient descent in detail during the lecture."
        val cards = CardGenerator.fromSegments(listOf(segment(5, text)))
        assertEquals(1, cards.size)
        assertEquals("seg:5", cards[0].contentKey)
        assertTrue(cards[0].back.contains("gradient descent"))
    }

    @Test
    fun pendingSegmentGeneratesZeroCards() {
        val cards = CardGenerator.fromSegments(listOf(segment(1, "Some transcript text here.", status = "pending")))
        assertTrue(cards.isEmpty())
    }

    @Test
    fun failedSegmentGeneratesZeroCards() {
        val cards = CardGenerator.fromSegments(listOf(segment(1, "Some transcript text here.", status = "failed")))
        assertTrue(cards.isEmpty())
    }

    @Test
    fun emptySegmentGeneratesZeroCards() {
        val cards = CardGenerator.fromSegments(listOf(segment(1, "", status = "ready")))
        assertTrue(cards.isEmpty())
    }

    @Test
    fun tooShortSegmentGeneratesZeroCards() {
        val cards = CardGenerator.fromSegments(listOf(segment(1, "hi", status = "ready")))
        assertTrue(cards.isEmpty())
    }

    @Test
    fun readySegmentContentKeyUsesSegmentId() {
        val cards = CardGenerator.fromSegments(listOf(segment(12, "A sufficiently long transcript for card generation.")))
        assertEquals("seg:12", cards[0].contentKey)
    }

    // ---- mixed generation --------------------------------------------------

    @Test
    fun allSourcesCombinedProduceDistinctKeys() {
        val chunks = listOf(chunk(1, "Neural networks are powerful function approximators."))
        val mistakes = listOf(mistake(10, "What is AI?", "Artificial intelligence."))
        val segments = listOf(segment(20, "Today we covered optimization algorithms in depth."))
        val all = CardGenerator.fromChunks(chunks) +
                CardGenerator.fromMistakes(mistakes) +
                CardGenerator.fromSegments(segments)
        val keys = all.map { it.contentKey }.toSet()
        assertEquals(all.size, keys.size)
    }

    @Test
    fun emptyInputsProduceEmptyOutput() {
        assertTrue(CardGenerator.fromChunks(emptyList()).isEmpty())
        assertTrue(CardGenerator.fromMistakes(emptyList()).isEmpty())
        assertTrue(CardGenerator.fromSegments(emptyList()).isEmpty())
    }

    // ---- citation ----------------------------------------------------------

    @Test
    fun citationIncludesPageNumber() {
        assertEquals("notes.pdf · PDF · PAGE 3", CardGenerator.citation("notes.pdf", "pdf", 3))
    }

    @Test
    fun citationUsesSlideForPptx() {
        assertEquals("slides.pptx · PPTX · SLIDE 5", CardGenerator.citation("slides.pptx", "pptx", 5))
    }

    @Test
    fun citationOmitsPageWhenNull() {
        assertEquals("notes.pdf · PDF", CardGenerator.citation("notes.pdf", "pdf", null))
    }
}
