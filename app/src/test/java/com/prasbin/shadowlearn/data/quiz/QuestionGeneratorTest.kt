package com.prasbin.shadowlearn.data.quiz

import com.prasbin.shadowlearn.data.db.QuizChunkRow
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule-based generator tests (pure JVM): determinism, option integrity
 * ("nothing invented" — every option is verbatim from the corpus), the
 * true-false mutation guard, capacity bounds and citation provenance.
 */
class QuestionGeneratorTest {

    private fun chunk(id: Long, file: String, type: String, page: Long?, text: String) = QuizChunkRow(
        chunkId = id,
        academicFileId = file.length.toLong(),
        chunkIndex = 0,
        pageNumber = page,
        text = text,
        fileName = file,
        fileType = type
    )

    private val pool = listOf(
        chunk(1, "neural.pdf", "pdf", 1, "Neural networks learn by gradient descent and backpropagation. " +
            "The optimizer adjusts weights to reduce the loss each epoch. Deep learning relies on large datasets."),
        chunk(2, "notes.docx", "docx", 2, "Adversarial examples can fool a trained model. " +
            "Data augmentation expands the training set with transformations. Regularization prevents overfitting."),
        chunk(3, "ai.pptx", "pptx", 1, "Transformers use self-attention to process sequences. " +
            "Positional encodings preserve the order of tokens. Attention heads capture word relationships."),
        chunk(4, "protocols.txt", "txt", null, "Internet protocols route packets across the network. " +
            "Routers inspect packet headers to forward traffic correctly."),
        chunk(5, "chem.pdf", "pdf", 3, "Catalysts lower activation energy in chemical reactions. " +
            "Enzymes accelerate reactions without being consumed.")
    )

    private val ctx = QuestionGenerator.context(pool)
    private val poolText = pool.joinToString(" ").lowercase()

    private fun build(seed: Long): List<GeneratedQuestion> =
        QuestionGenerator.build(pool.first(), ctx, Random(seed))

    @Test
    fun sameSeedProducesIdenticalQuestions() {
        val a = build(7)
        val b = build(7)
        assertEquals(a.size, b.size)
        a.zip(b).forEach { (x, y) ->
            assertEquals(x.type, y.type)
            assertEquals(x.prompt, y.prompt)
            assertEquals(x.options, y.options)
            assertEquals(x.correctAnswer, y.correctAnswer)
            assertEquals(x.source.chunkId, y.source.chunkId)
            assertEquals(x.source.excerpt, y.source.excerpt)
        }
    }

    @Test
    fun differentSeedsShuffleOptionOrder() {
        val orders = (1L..8L).map { build(it).flatMap { q -> q.options }.joinToString("|") }
        assertTrue("Expected some shuffling across seeds", orders.distinct().size > 2)
    }

    @Test
    fun correctAnswerIsAlwaysAnOption() {
        (1L..12L).forEach { seed ->
            build(seed).forEach { q ->
                assertTrue("correct '${q.correctAnswer}' must be an option for $q.type", q.correctAnswer in q.options)
                assertEquals("options must be distinct", q.options.size, q.options.distinct().size)
            }
        }
    }

    @Test
    fun trueFalseUsesBinaryTokens() {
        (1L..12L).forEach { seed ->
            build(seed).filter { it.type == QuestionType.TRUE_FALSE }.forEach { q ->
                assertEquals(listOf("true", "false"), q.options)
                assertTrue(q.correctAnswer == "true" || q.correctAnswer == "false")
            }
        }
    }

    @Test
    fun mcqAndFillOptionsAreVerbatimFromCorpus() {
        (1L..12L).forEach { seed ->
            build(seed).filter { it.type != QuestionType.TRUE_FALSE }.forEach { q ->
                q.options.forEach { opt ->
                    // options are verbatim corpus strings; MCQ long segments may
                    // be display-truncated with a trailing ellipsis.
                    val body = opt.removeSuffix("\u2026")
                    assertTrue(
                        "option '$opt' must be a verbatim corpus string (not invented)",
                        poolText.contains(body.lowercase())
                    )
                }
            }
        }
    }

    @Test
    fun fillBlankPromptContainsBlankAndPresentsOriginalSentence() {
        build(3).filter { it.type == QuestionType.FILL_BLANK }.forEach { q ->
            assertTrue(q.prompt.contains("________"))
            assertTrue(
                "blank answer '${q.correctAnswer}' must appear in the cited excerpt",
                q.source.excerpt.lowercase().contains(q.correctAnswer.lowercase())
            )
        }
    }

    @Test
    fun trueFalseStatementsAreVerbatimOrProvablyNovel() {
        val corpus = QuestionGenerator.context(pool).segments.map { it.lowercase() }.toSet()
        var seenFalse = false
        var seenTrue = false
        (1L..40L).forEach { seed ->
            build(seed).filter { it.type == QuestionType.TRUE_FALSE }.forEach { q ->
                val statement = q.prompt.substringAfter("states: \"").substringBeforeLast("\"").lowercase()
                if (q.correctAnswer == "true") {
                    seenTrue = true
                    assertTrue("TRUE statement must be verbatim in the corpus: $statement", statement in corpus)
                } else {
                    seenFalse = true
                    assertFalse("FALSE statement must NOT be verbatim anywhere: $statement", statement in corpus)
                }
            }
        }
        // Both truth values must actually occur across seeds (a mixed quiz).
        assertTrue("expected at least one TRUE variant", seenTrue)
        assertTrue("expected at least one FALSE variant", seenFalse)
    }

    @Test
    fun everyQuestionCitesItsOwnChunk() {
        val first = pool.first()
        build(5).forEach { q ->
            assertEquals(first.chunkId, q.source.chunkId)
            assertEquals(first.academicFileId, q.source.academicFileId)
            assertEquals(first.fileName, q.source.fileName)
            assertEquals(first.fileType, q.source.fileType)
            assertEquals(first.pageNumber, q.source.pageNumber)
            assertTrue(q.source.excerpt.isNotEmpty())
        }
    }

    @Test
    fun capacityBoundsMatchBuildOutput() {
        val first = pool.first()
        val cap = QuestionGenerator.capacity(first)
        assertTrue(cap in 1..QuestionGenerator.MAX_PER_CHUNK)
        (1L..8L).forEach { seed ->
            val built = QuestionGenerator.build(first, ctx, Random(seed))
            assertTrue("built ${built.size} ≤ capacity $cap", built.size <= cap)
            assertTrue(built.size >= 1)
        }
    }

    @Test
    fun degenerateChunksYieldZeroCapacity() {
        val tiny = chunk(50, "short.txt", "txt", null, "hi")
        assertEquals(0, QuestionGenerator.capacity(tiny))
        assertTrue(QuestionGenerator.build(tiny, ctx, Random(1)).isEmpty())
    }

    @Test
    fun allThreeQuestionTypesAppearAcrossThePool() {
        val types = PoolBuilt.types(pool)
        assertTrue(types.contains(QuestionType.MCQ))
        assertTrue(types.contains(QuestionType.TRUE_FALSE))
        assertTrue(types.contains(QuestionType.FILL_BLANK))
    }

    @Test
    fun sessionContextIsDeterministicAndOrdered() {
        val a = QuestionGenerator.context(pool)
        val b = QuestionGenerator.context(pool)
        assertEquals(a.terms, b.terms)
        assertEquals(a.segments, b.segments)
        assertTrue(a.terms.isNotEmpty())
        assertTrue(a.segments.isNotEmpty())
        assertEquals(a.terms.size, a.terms.distinct().size)
        assertEquals(a.segments.size, a.segments.distinct().size)
    }

    /** Helps [allThreeQuestionTypesAppearAcrossThePool] inspect every chunk. */
    private object PoolBuilt {
        fun types(pool: List<QuizChunkRow>): Set<QuestionType> {
            val ctx = QuestionGenerator.context(pool)
            val seen = mutableSetOf<QuestionType>()
            pool.forEach { c ->
                QuestionGenerator.build(c, ctx, Random(2)).forEach { seen += it.type }
            }
            return seen
        }
    }
}