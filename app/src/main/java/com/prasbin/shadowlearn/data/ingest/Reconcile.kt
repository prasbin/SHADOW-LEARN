package com.prasbin.shadowlearn.data.ingest

import com.prasbin.shadowlearn.data.db.AcademicFile

/**
 * Phase 3 reconciliation classification — pure Kotlin, fully deterministic
 * (unit-tested without Android).
 *
 * Identity model (documented in docs/ARCHITECTURE.md):
 * - *Content identity* = SHA-256 of file bytes. App-global: identical bytes
 *   are one physical copy even at several positions.
 * - *Position identity* = `(semesterId, relativePath)`, where `relativePath`
 *   is the normalized logical path inside the archive (includes the module
 *   stem), so the same path always maps to the same academic position.
 *
 * For each planned file at position R with content hash C:
 * - a row exists at R and already holds C      → UNCHANGED (no new row, no
 *   new copy; a pre-v3 row is linked to content, a missing copy is repaired)
 * - a row exists at R holding something else   → CHANGED (the row is updated
 *   to point at content C)
 * - no row at R but C already exists somewhere in the same semester
 *                                             → DUPLICATE (a new reference
 *   row is created pointing at the existing physical copy — one copy total)
 * - otherwise                                  → NEW (row + physical copy
 *   created)
 *
 * Explicitly NOT implemented (see Phase 3 scope): removal/synchronization —
 * a partial import never deletes material that is absent from the archive.
 * Moves surface as DUPLICATE at the new position while the old reference
 * stays.
 */
enum class ReconcileOutcome { NEW, UNCHANGED, CHANGED, DUPLICATE }

object Reconcile {

    /**
 * Classifies a planned file given the two pre-queried lookups. The caller
 * queries [sameContentInSemester] by the current hash; the hash is checked
 * here again so the function is deterministic regardless of input.
 */
fun classify(atPosition: AcademicFile?, currentHash: String, sameContentInSemester: AcademicFile?): ReconcileOutcome =
        when {
            atPosition != null && atPosition.sha256 == currentHash -> ReconcileOutcome.UNCHANGED
            atPosition != null -> ReconcileOutcome.CHANGED
            sameContentInSemester != null && sameContentInSemester.sha256 == currentHash ->
                ReconcileOutcome.DUPLICATE
            else -> ReconcileOutcome.NEW
        }
}