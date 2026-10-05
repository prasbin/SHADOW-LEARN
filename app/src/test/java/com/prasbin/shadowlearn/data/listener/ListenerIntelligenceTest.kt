package com.prasbin.shadowlearn.data.listener

import com.prasbin.shadowlearn.data.db.ListenerSegment
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * I7 classification unit tests (pure, no Room, no FTS). Text rules only:
 * READY + verified chunk hit = ACADEMIC; short sparse READY = FILLER;
 * substantive-but-ungrounded READY = TRANSCRIPT_ONLY; anything else =
 * UNKNOWN. Voice characteristics never participate.
 */
class ListenerIntelligenceTest {

    private val classify = ListenerIntelligence.Companion::classify

    @Test
    fun academicRequiresRealChunkHit() {
        assertEquals(
            SegmentRole.ACADEMIC,
            classify("Photosynthesis converts light energy", "ready", true)
        )
        // Same text without a verified hit is NOT academic.
        assertEquals(
            SegmentRole.TRANSCRIPT_ONLY,
            classify(
                "Photosynthesis converts light energy in chloroplast thylakoid " +
                    "membranes producing glucose and oxygen.",
                "ready",
                false
            )
        )
    }

    @Test
    fun conversationalFillerIsFiller() {
        assertEquals(
            SegmentRole.FILLER,
            classify("Okay, let's move on.", "ready", false)
        )
    }

    @Test
    fun examContextIsNotFiller() {
        // Ordinary language carrying academic context must never be
        // dismissed as filler: without corpus grounding it is honestly
        // TRANSCRIPT_ONLY, still reviewable as lecture evidence.
        assertEquals(
            SegmentRole.TRANSCRIPT_ONLY,
            classify("Remember that this concept is important for the exam.", "ready", false)
        )
    }

    @Test
    fun nonReadyOrBlankIsUnknown() {
        assertEquals(SegmentRole.UNKNOWN, classify("Some text here.", "pending", false))
        assertEquals(SegmentRole.UNKNOWN, classify("Some text here.", "failed", true))
        assertEquals(
            SegmentRole.UNKNOWN,
            classify(ListenerSegment.TRANSCRIPT_PENDING, ListenerSegment.STATUS_PENDING, false)
        )
        assertEquals(SegmentRole.UNKNOWN, classify("   ", "ready", false))
    }

    @Test
    fun classificationIsDeterministic() {
        val text = "Mitosis divides one cell into two identical cells."
        assertEquals(
            classify(text, "ready", true),
            classify(text, "ready", true)
        )
    }

    @Test
    fun shortSubstantiveTextIsNotFiller() {
        // Short but term-dense text without a hit stays TRANSCRIPT_ONLY:
        // brevity alone never declares filler.
        assertEquals(
            SegmentRole.TRANSCRIPT_ONLY,
            classify("Mitosis divides cells through complex phases.", "ready", false)
        )
    }
}
