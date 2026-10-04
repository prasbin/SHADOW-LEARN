package com.prasbin.shadowlearn.data.intelligence

/**
 * I1 weakness interpretation (contract §§10–12, 14). PURE and
 * deterministic: same evidence + same now ⇒ same result. No Room, no
 * DataStore, no navigation, no mutation, no XP. Source-file granularity
 * only — no concepts, no scores, no percentages.
 *
 * Thresholds are I1-initial constants (contract §20 open question 2):
 * documented here, never silently tuned.
 */
object WeaknessEngine {

    /** Mistakes needed with session/day spread for OBSERVED (branch A). */
    const val OBSERVED_MISTAKES = 3
    /** Distinct sessions or calendar days required alongside. */
    const val OBSERVED_SESSIONS = 2
    /** AGAIN ratings needed on one source for OBSERVED (branch B). */
    const val OBSERVED_AGAIN = 3
    /** Recency window for branch-B AGAINs (days). */
    const val AGAIN_WINDOW_DAYS = 14
    /** Combined branch: mistakes + AGAINs each at least this many. */
    const val COMBINED_EACH = 2
    /** Window for the combined branch (days). */
    const val COMBINED_WINDOW_DAYS = 30
    /** Evidence older than this never counts toward OBSERVED (days). */
    const val STALE_DAYS = 60
    /** Correct answers in trailing window signalling recovery. */
    const val IMPROVE_CORRECT = 2
    /** Trailing window for improvement + recent-negative check (days). */
    const val IMPROVE_WINDOW_DAYS = 7

    const val DAY_MS = 86_400_000L

    /**
     * Canonical signal ordering (strongest evidence, most recent, then
     * stable file name). Shared with [RecommendationEngine] so both agree
     * even on unsorted inputs — determinism must not depend on callers.
     */
    val SIGNAL_ORDER: Comparator<WeaknessSignal> =
        compareByDescending<WeaknessSignal> { it.status.rank }
            .thenByDescending { it.mistakeCount + it.againCount }
            .thenByDescending { it.newestAt }
            .thenBy { it.fileName }

    /**
     * Evaluates grouped evidence into ranked signals. Groups key on
     * `file:<id>` when the file resolves, else `name:<snapshot>` (dangling
     * sources can only ever reach POSSIBLE). Empty input ⇒ empty output
     * (UNKNOWN is a screen state, not a signal).
     */
    fun evaluate(
        mistakes: List<MistakeEvidence>,
        corrects: List<CorrectEvidence>,
        agains: List<AgainEvidence>,
        scopes: Map<String, SourceScope?>,
        now: Long
    ): List<WeaknessSignal> {
        val staleCutoff = now - STALE_DAYS * DAY_MS
        val fresh = { at: Long -> at >= staleCutoff }
        data class Group(
            val mistakes: MutableList<MistakeEvidence> = mutableListOf(),
            val agains: MutableList<AgainEvidence> = mutableListOf(),
            var fileId: Long? = null,
            var name: String = "Unknown source"
        )
        val byKey = mutableMapOf<String, Group>()
        for (m in mistakes) {
            if (!fresh(m.observedAt)) continue
            val g = byKey.getOrPut(keyOf(m.academicFileId, m.srcFileName)) { Group() }
            g.mistakes.add(m)
            if (m.academicFileId != null) g.fileId = m.academicFileId
            g.name = m.srcFileName
        }
        for (a in agains) {
            if (!fresh(a.reviewedAt)) continue
            val g = byKey.getOrPut(keyOf(a.fileId, a.srcLabel)) { Group() }
            g.agains.add(a)
            if (a.fileId != null) g.fileId = a.fileId
            if (g.name == "Unknown source") g.name = a.srcLabel
        }
        if (byKey.isEmpty()) return emptyList()

        val recentCutoff = now - IMPROVE_WINDOW_DAYS * DAY_MS
        val combinedCutoff = now - COMBINED_WINDOW_DAYS * DAY_MS
        val againCutoff = now - AGAIN_WINDOW_DAYS * DAY_MS
        val correctsByKey = corrects.filter { fresh(it.observedAt) }
            .groupBy { keyOf(it.academicFileId, it.srcFileName) }
        val out = mutableListOf<WeaknessSignal>()
        for ((key, group) in byKey) {
            val scope = scopes[key]
            // Dangling = no resolvable file row (deleted file, legacy id,
            // or snapshot-only evidence). Promotion forbidden without one.
            val dangling = scope == null
            val groupMistakes = group.mistakes
            val groupAgains = group.agains
            val groupCorrects = correctsByKey[key] ?: emptyList()
            val mCount = groupMistakes.size
            val sessions = groupMistakes.map { it.sessionId }.toSet().size
            val days = groupMistakes.map { it.observedAt / DAY_MS }.toSet().size
            val recentAgains = groupAgains.count { it.reviewedAt >= againCutoff }
            val windowAgains = groupAgains.count { it.reviewedAt >= combinedCutoff }
            val windowMistakes = groupMistakes.count { it.observedAt >= combinedCutoff }
            val recentNegatives = groupMistakes.count { it.observedAt >= recentCutoff } +
                groupAgains.count { it.reviewedAt >= recentCutoff }
            val recentCorrects = groupCorrects.count { it.observedAt >= recentCutoff }
            val newest = maxOf(
                groupMistakes.maxOfOrNull { it.observedAt } ?: Long.MIN_VALUE,
                groupAgains.maxOfOrNull { it.reviewedAt } ?: Long.MIN_VALUE
            )
            val observed =
                (mCount >= OBSERVED_MISTAKES && (sessions >= OBSERVED_SESSIONS || days >= OBSERVED_SESSIONS)) ||
                    recentAgains >= OBSERVED_AGAIN ||
                    (windowMistakes >= COMBINED_EACH && windowAgains >= COMBINED_EACH)
            val status = when {
                dangling -> WeaknessStatus.POSSIBLE
                observed && recentNegatives == 0 && recentCorrects >= IMPROVE_CORRECT ->
                    WeaknessStatus.IMPROVING
                observed -> WeaknessStatus.OBSERVED
                else -> WeaknessStatus.POSSIBLE
            }
            out.add(
                WeaknessSignal(
                    fileId = scope?.fileId,
                    fileName = scope?.fileName ?: group.name,
                    weekLabel = scope?.weekLabel,
                    moduleName = scope?.moduleName,
                    status = status,
                    mistakeCount = mCount,
                    mistakeSessions = sessions,
                    againCount = groupAgains.size,
                    recentCorrects = recentCorrects,
                    newestAt = if (newest == Long.MIN_VALUE) now else newest,
                    dangling = dangling,
                    weekId = scope?.weekId
                )
            )
        }
        return out.sortedWith(SIGNAL_ORDER)
    }

    private fun keyOf(fileId: Long?, name: String): String =
        if (fileId != null && fileId > 0) "file:$fileId" else "name:$name"
}

/** I1 signal states (contract §14). RESOLVED deliberately absent. */
enum class WeaknessStatus(val rank: Int) {
    OBSERVED(3),
    IMPROVING(2),
    POSSIBLE(1)
}

/** One ranked file-level signal with its explainable basis. */
data class WeaknessSignal(
    val fileId: Long?,
    val fileName: String,
    val weekLabel: String?,
    val moduleName: String?,
    val status: WeaknessStatus,
    val mistakeCount: Int,
    val mistakeSessions: Int,
    val againCount: Int,
    val recentCorrects: Int,
    val newestAt: Long,
    val dangling: Boolean,
    /** Owning week for OPEN SOURCE routing; null when unresolvable. */
    val weekId: Long? = null
) {
    /** Scope crumb for display ("Week 2 · Artificial Intelligence" or null). */
    fun scopeCrumb(): String? {
        val parts = listOfNotNull(weekLabel, moduleName)
        return parts.joinToString(" · ").ifEmpty { null }
    }

    /** Home row label: the source, never a concept. */
    fun homeLabel(): String = fileName

    /** Home row detail: scope + WHY counts + recency (single line). */
    fun homeDetail(now: Long): String {
        val crumb = scopeCrumb()?.let { "$it — " } ?: ""
        val bits = mutableListOf<String>()
        if (mistakeCount > 0) {
            bits.add(
                if (mistakeCount == 1) "1 wrong"
                else "$mistakeCount wrong across $mistakeSessions session${if (mistakeSessions == 1) "" else "s"}"
            )
        }
        if (againCount > 0) {
            bits.add(if (againCount == 1) "1 AGAIN" else "$againCount AGAIN")
        }
        if (status == WeaknessStatus.IMPROVING) {
            bits.add("$recentCorrects correct lately")
        }
        if (dangling) bits.add("source removed")
        val daysAgo = ((now - newestAt) / WeaknessEngine.DAY_MS).coerceAtLeast(0)
        val recency = if (daysAgo == 0L) "today" else "$daysAgo day${if (daysAgo == 1L) "" else "s"} ago"
        return "$crumb${bits.joinToString(" · ")}, last $recency"
    }

    /** Status line for the Academic Status learning-signals section. */
    fun statusLine(now: Long): String {
        val tag = when (status) {
            WeaknessStatus.OBSERVED -> "OBSERVED"
            WeaknessStatus.IMPROVING -> "IMPROVING"
            WeaknessStatus.POSSIBLE -> "POSSIBLE"
        }
        return "$tag — ${homeDetail(now)}"
    }

    /** Count-based evidence phrase for recommendations (no recency claim). */
    fun evidenceSummary(): String {
        val bits = mutableListOf<String>()
        if (mistakeCount > 0) {
            bits.add(
                "$mistakeCount incorrect answer${if (mistakeCount == 1) "" else "s"} " +
                    "across $mistakeSessions session${if (mistakeSessions == 1) "" else "s"}"
            )
        }
        if (againCount > 0) {
            bits.add("$againCount AGAIN review${if (againCount == 1) "" else "s"}")
        }
        return bits.joinToString(" + ").ifEmpty { "limited evidence" }
    }
}
