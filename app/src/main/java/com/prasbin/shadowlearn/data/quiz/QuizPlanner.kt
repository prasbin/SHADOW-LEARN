package com.prasbin.shadowlearn.data.quiz

import kotlin.random.Random

/**
 * Session planner — the "complexity-aware ring" of Phase 6.
 *
 * Inputs: every eligible (chunkId, capacity) pair of the semester, the
 * requested session length, and the set of chunk ids used by recent
 * completed sessions (the ring). A chunk is never used beyond its capacity,
 * and pass-2 top-ups are round-robin so coverage is maximized.
 *
 * Recency policy ("recent chunk ids due-more"): chunks from the last few
 * completed sessions are selected FIRST at equal capacity — they are "due"
 * for review at peak spacing; slots left over go to fresh chunks, so the
 * pool is always covered over time. Both groups are pre-shuffled with the
 * seeded [rng], keeping plans reproducible.
 */
object QuizPlanner {

    /** One chunk's share of a planned session, in [id] order. */
    data class Assignment(val chunkId: Long, val questionCount: Int)

    /**
     * Plans a session of up to [requested] questions (may return fewer —
     * never more — when the semester's chunks cannot supply that many).
     */
    fun plan(
        eligible: List<Pair<Long, Int>>,
        requested: Int,
        recentChunkIds: Set<Long>,
        rng: Random
    ): List<Assignment> {
        if (requested <= 0 || eligible.isEmpty()) return emptyList()

        val (recent, fresh) = eligible.partition { it.first in recentChunkIds }
        val ordered = (recent.shuffled(rng) + fresh.shuffled(rng))

        // Pass 1: one question per chosen chunk, recency-first, until the
        // target chunk count or the pool is exhausted.
        val selected = mutableListOf<Pair<Long, Int>>()
        var spread = 0
        for ((id, cap) in ordered) {
            if (spread >= requested) break
            if (cap <= 0) continue
            selected += id to cap
            spread++
        }
        if (selected.isEmpty()) return emptyList()

        // Pass 2: top up towards [requested] with remaining capacity,
        // round-robin so a single chunk never hogs the session.
        val counts = mutableListOf(1); repeat(selected.size - 1) { counts += 1 }
        var total = spread
        while (total < requested) {
            var added = false
            for (i in selected.indices) {
                if (total >= requested) break
                if (counts[i] < selected[i].second) {
                    counts[i]++
                    total++
                    added = true
                }
            }
            if (!added) break
        }
        return selected.indices.map { Assignment(selected[it].first, counts[it]) }
    }
}