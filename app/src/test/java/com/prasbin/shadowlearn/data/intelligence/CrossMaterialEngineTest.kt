package com.prasbin.shadowlearn.data.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I6 pure engine tests (no Room, no Android): term extraction and
 * deterministic ranking. FTS confirmation and hierarchy walks are the
 * repository's job, covered by RelationshipRepositoryTest.
 */
class CrossMaterialEngineTest {

    @Test
    fun extractTerms_filtersShortWordsAndStopwords() {
        val terms = CrossMaterialEngine.extractTerms(
            listOf("The study of lecture notes and figure table")
        )
        assertTrue(terms.isEmpty())
    }

    @Test
    fun extractTerms_ranksFrequencyThenAlphabeticalAndCaps() {
        val terms = CrossMaterialEngine.extractTerms(
            listOf("zebra alpha zebra gamma alpha zebra delta epsilon theta kappa lambda")
        )
        // zebra x3 first, alpha x2 second, then singles alphabetical, cap 12.
        assertEquals("zebra", terms[0])
        assertEquals("alpha", terms[1])
        assertEquals(listOf("delta", "epsilon", "gamma", "kappa", "lambda", "theta"), terms.drop(2))
        assertTrue(terms.size <= CrossMaterialEngine.MAX_TERMS)
    }

    @Test
    fun extractTerms_isDeterministic() {
        val texts = listOf("Osmosis moves water across membranes slowly.")
        assertEquals(
            CrossMaterialEngine.extractTerms(texts),
            CrossMaterialEngine.extractTerms(texts)
        )
    }

    private fun source() = CrossMaterialEngine.SourceInfo(
        fileId = 1, sha256 = "aaa", weekId = 10, weekNumber = 1, moduleId = 100
    )

    private fun candidate(
        id: Long,
        weekId: Long = 20,
        weekNumber: Int = 2,
        moduleId: Long = 200,
        terms: List<String> = emptyList(),
        sha: String = "sha-$id"
    ) = CrossMaterialEngine.CandidateInfo(
        fileId = id, fileName = "f$id.pdf", sha256 = sha,
        weekId = weekId, weekNumber = weekNumber,
        moduleId = moduleId, moduleName = "M$moduleId",
        matchedTerms = terms, matchedChunkIds = terms.indices.map { it.toLong() }
    )

    @Test
    fun sameWeekBeatsContentAndModule() {
        val out = CrossMaterialEngine.evaluate(
            source(),
            listOf(
                candidate(2, moduleId = 100, weekId = 11, weekNumber = 2,
                    terms = listOf("a", "b", "c", "d", "e")),
                candidate(3, weekId = 10, weekNumber = 1, moduleId = 100),
                candidate(4, moduleId = 100, weekId = 12, weekNumber = 3)
            )
        )
        assertEquals(3, out.size)
        assertEquals(RelationshipType.SAME_WEEK, out[0].type)
        assertEquals(3L, out[0].candidate.fileId)
        assertEquals(RelationshipType.SHARED_CONTENT, out[1].type)
        assertEquals(RelationshipType.SAME_MODULE, out[2].type)
    }

    @Test
    fun selfAndMirrorsAreExcluded() {
        val out = CrossMaterialEngine.evaluate(
            source(),
            listOf(
                candidate(1, weekId = 10, weekNumber = 1, moduleId = 100, sha = "aaa"),
                candidate(5, weekId = 10, weekNumber = 1, moduleId = 100, sha = "aaa"),
                candidate(6, weekId = 10, weekNumber = 1, moduleId = 100)
            )
        )
        assertEquals(listOf(6L), out.map { it.candidate.fileId })
    }

    @Test
    fun weakContentAloneProducesNothing() {
        val out = CrossMaterialEngine.evaluate(
            source(),
            listOf(
                candidate(7, weekId = 30, weekNumber = 5, moduleId = 300,
                    terms = listOf("only", "twoxx"))
            )
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun orderingIsFullyDeterministic() {
        fun run() = CrossMaterialEngine.evaluate(
            source(),
            listOf(
                candidate(9, weekId = 10, weekNumber = 1, moduleId = 100),
                candidate(8, weekId = 10, weekNumber = 1, moduleId = 100),
                candidate(7, moduleId = 200, weekId = 21, weekNumber = 2,
                    terms = listOf("a", "b", "c", "d"))
            )
        )
        val first = run().map { it.candidate.fileId }
        val second = run().map { it.candidate.fileId }
        assertEquals(first, second)
        assertEquals(listOf(8L, 9L, 7L), first)
    }

    @Test
    fun maxThreeRelationships() {
        val out = CrossMaterialEngine.evaluate(
            source(),
            (2L..8L).map {
                candidate(it, weekId = 10, weekNumber = 1, moduleId = 100)
            }
        )
        assertEquals(3, out.size)
    }
}
