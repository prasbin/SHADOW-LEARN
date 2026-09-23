package com.prasbin.shadowlearn.data.ingest

import com.prasbin.shadowlearn.data.db.AcademicFile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Deterministic reconciliation classification (pure, no Android).
 *
 * Model: content identity = SHA-256; position identity = (semester,
 * relativePath). The same inputs MUST always yield the same outcome.
 */
class ReconcileTest {

    private fun file(sha: String, relativePath: String = "Mod.zip/Week 1/Lecture/x.pdf") =
        AcademicFile(
            weekId = 1,
            fileName = relativePath.substringAfterLast('/'),
            filePath = "/store/$sha",
            fileType = "pdf",
            sha256 = sha,
            relativePath = relativePath
        )

    @Test
    fun noRowAtPositionAndNoContentInSemester_isNew() {
        assertEquals(ReconcileOutcome.NEW, Reconcile.classify(null, "aaa", null))
    }

    @Test
    fun rowAtPositionWithSameHash_isUnchanged() {
        val at = file("aaa")
        assertEquals(ReconcileOutcome.UNCHANGED, Reconcile.classify(at, "aaa", file("aaa", "Other.zip/Week 2/...")))
    }

    @Test
    fun rowAtPositionWithDifferentHash_isChanged() {
        val at = file("aaa")
        assertEquals(ReconcileOutcome.CHANGED, Reconcile.classify(at, "bbb", file("bbb")))
    }

    @Test
    fun positionChange_winsOverSameContentElsewhere() {
        val at = file("aaa")
        // Same new content also exists at another position: position must win.
        assertEquals(ReconcileOutcome.CHANGED, Reconcile.classify(at, "bbb", file("bbb")))
    }

    @Test
    fun noRowAtPositionButContentInSemester_isDuplicate() {
        val same = file("ccc", "Mod.zip/Week 2/Tutorial/t.docx")
        assertEquals(ReconcileOutcome.DUPLICATE, Reconcile.classify(null, "ccc", same))
    }

    @Test
    fun unrelatedContentElsewhere_isStillNew() {
        val same = file("eee")
        assertEquals(ReconcileOutcome.NEW, Reconcile.classify(null, "ddd", same))
    }

    @Test
    fun classify_isDeterministic() {
        val shuffle = listOf(
            Triple(null, "aaa", null),
            Triple(file("aaa"), "aaa", null),
            Triple(file("aaa"), "bbb", null),
            Triple(null, "ccc", file("ccc"))
        ).shuffled()
        val expected = shuffle.map { Reconcile.classify(it.first, it.second, it.third) }
        repeat(5) {
            assertEquals(expected, shuffle.map { Reconcile.classify(it.first, it.second, it.third) })
        }
    }
}