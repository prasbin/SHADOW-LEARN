package com.prasbin.shadowlearn.data.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I1 WeaknessEngine contract tests (pure, deterministic, Room-free).
 * Thresholds are the I1-initial constants under test — changing them
 * requires a contract amendment, not a test edit.
 */
class WeaknessEngineTest {

    private val now = 1_704_000_000_000L
    private val day = WeaknessEngine.DAY_MS
    private val scopes = mapOf(
        "file:1" to SourceScope(1, "cell-biology.pdf", 10, "Week 3", 5, "Biology"),
        "file:2" to SourceScope(2, "genetics.pdf", 11, "Week 4", 5, "Biology")
    )

    private fun mistake(
        fileId: Long?,
        name: String,
        session: Long,
        daysAgo: Long,
        qid: Long = session * 100 + daysAgo
    ) = MistakeEvidence(
        questionId = qid, sessionId = session,
        academicFileId = fileId, chunkId = null,
        srcFileName = name, observedAt = now - daysAgo * day
    )

    private fun correct(fileId: Long?, name: String, session: Long, daysAgo: Long) =
        CorrectEvidence(
            questionId = 9000 + session * 10 + daysAgo, sessionId = session,
            academicFileId = fileId, srcFileName = name,
            observedAt = now - daysAgo * day
        )

    private fun again(fileId: Long?, label: String, daysAgo: Long, eid: Long = daysAgo) =
        AgainEvidence(
            eventId = eid, fileId = fileId, srcLabel = label,
            reviewedAt = now - daysAgo * day
        )

    @Test
    fun singleMistake_isPossible_neverObserved() {
        val out = WeaknessEngine.evaluate(
            listOf(mistake(1, "cell-biology.pdf", 1, 1)),
            emptyList(), emptyList(), scopes, now
        )
        assertEquals(1, out.size)
        assertEquals(WeaknessStatus.POSSIBLE, out[0].status)
        assertEquals("cell-biology.pdf", out[0].fileName)
    }

    @Test
    fun repeatedMistakesAcrossSessions_areObserved() {
        val out = WeaknessEngine.evaluate(
            listOf(
                mistake(1, "cell-biology.pdf", 1, 9),
                mistake(1, "cell-biology.pdf", 2, 5),
                mistake(1, "cell-biology.pdf", 3, 2),
                mistake(1, "cell-biology.pdf", 3, 1)
            ),
            emptyList(), emptyList(), scopes, now
        )
        assertEquals(1, out.size)
        assertEquals(WeaknessStatus.OBSERVED, out[0].status)
        assertEquals(4, out[0].mistakeCount)
        assertEquals(3, out[0].mistakeSessions)
    }

    @Test
    fun singleSessionCluster_staysPossible() {
        val out = WeaknessEngine.evaluate(
            listOf(
                mistake(1, "cell-biology.pdf", 1, 2),
                mistake(1, "cell-biology.pdf", 1, 2),
                mistake(1, "cell-biology.pdf", 1, 2)
            ),
            emptyList(), emptyList(), scopes, now
        )
        assertEquals(WeaknessStatus.POSSIBLE, out[0].status)
    }

    @Test
    fun isolatedAgain_isPossible_notWeakness() {
        val out = WeaknessEngine.evaluate(
            emptyList(), emptyList(),
            listOf(again(1, "cell-biology.pdf · PDF · PAGE 1", 1)),
            scopes, now
        )
        assertEquals(1, out.size)
        assertEquals(WeaknessStatus.POSSIBLE, out[0].status)
    }

    @Test
    fun repeatedAgains_areObserved() {
        val out = WeaknessEngine.evaluate(
            emptyList(), emptyList(),
            listOf(
                again(1, "cell-biology.pdf", 9),
                again(1, "cell-biology.pdf", 6),
                again(1, "cell-biology.pdf", 3),
                again(1, "cell-biology.pdf", 1)
            ),
            scopes, now
        )
        assertEquals(WeaknessStatus.OBSERVED, out[0].status)
        assertEquals(4, out[0].againCount)
    }

    @Test
    fun staleEvidence_doesNotCount() {
        val out = WeaknessEngine.evaluate(
            listOf(
                mistake(1, "cell-biology.pdf", 1, 70),
                mistake(1, "cell-biology.pdf", 2, 65),
                mistake(1, "cell-biology.pdf", 3, 61)
            ),
            emptyList(), emptyList(), scopes, now
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun recovery_afterObserved_isImproving() {
        val out = WeaknessEngine.evaluate(
            listOf(
                mistake(1, "cell-biology.pdf", 1, 20),
                mistake(1, "cell-biology.pdf", 2, 15),
                mistake(1, "cell-biology.pdf", 3, 12)
            ),
            listOf(
                correct(1, "cell-biology.pdf", 4, 3),
                correct(1, "cell-biology.pdf", 4, 2),
                correct(1, "cell-biology.pdf", 5, 1)
            ),
            emptyList(), scopes, now
        )
        assertEquals(WeaknessStatus.IMPROVING, out[0].status)
    }

    @Test
    fun newMistake_resetsImprovingToObserved() {
        val out = WeaknessEngine.evaluate(
            listOf(
                mistake(1, "cell-biology.pdf", 1, 20),
                mistake(1, "cell-biology.pdf", 2, 15),
                mistake(1, "cell-biology.pdf", 3, 12),
                mistake(1, "cell-biology.pdf", 6, 1)
            ),
            listOf(
                correct(1, "cell-biology.pdf", 4, 3),
                correct(1, "cell-biology.pdf", 5, 2)
            ),
            emptyList(), scopes, now
        )
        assertEquals(WeaknessStatus.OBSERVED, out[0].status)
    }

    @Test
    fun noEvidence_meansUnknown_emptyList() {
        assertTrue(WeaknessEngine.evaluate(emptyList(), emptyList(), emptyList(), scopes, now).isEmpty())
    }

    @Test
    fun danglingSource_isCappedAtPossible() {
        val out = WeaknessEngine.evaluate(
            listOf(
                mistake(null, "deleted.pdf", 1, 9),
                mistake(null, "deleted.pdf", 2, 5),
                mistake(null, "deleted.pdf", 3, 3),
                mistake(null, "deleted.pdf", 4, 2),
                mistake(null, "deleted.pdf", 5, 1)
            ),
            emptyList(), emptyList(), scopes, now
        )
        assertEquals(1, out.size)
        assertEquals(WeaknessStatus.POSSIBLE, out[0].status)
        assertEquals("deleted.pdf", out[0].fileName)
        assertTrue(out[0].dangling)
    }

    @Test
    fun multipleFiles_stayAttachedToCorrectSources() {
        val out = WeaknessEngine.evaluate(
            listOf(
                mistake(1, "cell-biology.pdf", 1, 9),
                mistake(1, "cell-biology.pdf", 2, 5),
                mistake(1, "cell-biology.pdf", 3, 2),
                mistake(2, "genetics.pdf", 9, 1)
            ),
            emptyList(), emptyList(), scopes, now
        )
        assertEquals(2, out.size)
        assertEquals(WeaknessStatus.OBSERVED, out[0].status)
        assertEquals("cell-biology.pdf", out[0].fileName)
        assertEquals(WeaknessStatus.POSSIBLE, out[1].status)
        assertEquals("genetics.pdf", out[1].fileName)
    }

    @Test
    fun sameInput_alwaysProducesSameResult() {
        val mistakes = listOf(
            mistake(1, "cell-biology.pdf", 1, 9),
            mistake(1, "cell-biology.pdf", 2, 5),
            mistake(1, "cell-biology.pdf", 3, 2)
        )
        val first = WeaknessEngine.evaluate(mistakes, emptyList(), emptyList(), scopes, now)
        val second = WeaknessEngine.evaluate(mistakes, emptyList(), emptyList(), scopes, now)
        assertEquals(first, second)
    }

    @Test
    fun homeAndStatus_shareOneResult() {
        val out = WeaknessEngine.evaluate(
            listOf(
                mistake(1, "cell-biology.pdf", 1, 9),
                mistake(1, "cell-biology.pdf", 2, 5),
                mistake(1, "cell-biology.pdf", 3, 2),
                mistake(2, "genetics.pdf", 9, 1)
            ),
            emptyList(), emptyList(), scopes, now
        )
        // Home rows and Status lines derive from the same signals.
        val homeRows = out.map { it.homeLabel() to it.homeDetail(now) }
        val statusLines = out.map { it.homeLabel() to it.statusLine(now) }
        assertEquals(homeRows.map { it.first }, statusLines.map { it.first })
        assertEquals(listOf("cell-biology.pdf", "genetics.pdf"), homeRows.map { it.first })
        assertTrue(statusLines[0].second.startsWith("OBSERVED"))
        assertTrue(statusLines[1].second.startsWith("POSSIBLE"))
        assertTrue(homeRows[0].second.contains("Week 3"))
    }
}
