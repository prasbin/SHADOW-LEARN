package com.prasbin.shadowlearn.data.ingest

import com.prasbin.shadowlearn.data.db.ClassType

/**
 * One walked archive entry (file or directory), with its logical path.
 *
 * [logicalPath] is the `/`-separated path from the semester archive root,
 * including any nested-`.zip` stems as segments, e.g.
 * `AI.zip/Week 1/Lecture/l1.pdf`. Directory entries end without a slash.
 */
data class WalkedEntry(
    val logicalPath: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0
)

/**
 * A file assigned to Module → Week → ClassType by [buildPlan].
 *
 * Directories that create structure without files are represented as
 * [PlannedModule] / [PlannedWeek] rows so empty folders stay visible.
 */
data class PlannedFile(
    val moduleName: String,
    val weekNumber: Int,
    val weekTitle: String,
    val classType: ClassType,
    val entry: WalkedEntry
)

data class PlannedModule(val name: String)
data class PlannedWeek(val moduleName: String, val weekNumber: Int, val weekTitle: String)

data class HierarchyPlan(
    val modules: List<PlannedModule>,
    val weeks: List<PlannedWeek>,
    val files: List<PlannedFile>
)

/**
 * Pure hierarchy builder: turns a flat walked-entry list into
 * Module → Week → ClassType assignments.
 *
 * Deterministic rules (also documented in docs/ARCHITECTURE.md):
 * 1. A single shared top-level wrapper directory is stripped.
 * 2. A nested `.zip` at semester-root level opens a module named after its
 *    stem; a nested `.zip` elsewhere is a transparent container, except a
 *    stem matching the Week pattern inside a module, which selects that week.
 * 3. A directory matching the Week pattern selects that week (same number
 *    merges); an empty week directory still creates the week.
 * 4. A Lecture/Tutorial/Workshop directory selects the class type for
 *    descendant files (a class directory also forces a week context).
 * 5. Other directories are transparent containers, except a top-level one,
 *    which opens a module named after it; an empty one still creates it.
 * 6. Files arriving with no module go to a module named [fallbackModule];
 *    files with no week go to week 1 titled "General".
 * 7. Names are preserved verbatim (trimmed only).
 */
fun buildPlan(
    entries: List<WalkedEntry>,
    fallbackModule: String
): HierarchyPlan {
    val stripped = stripSingleWrapper(entries)
    val modules = linkedMapOf<String, PlannedModule>()
    val weeks = linkedMapOf<Pair<String, Int>, PlannedWeek>()
    val files = mutableListOf<PlannedFile>()

    fun needModule(name: String): String {
        val clean = name.trim().ifEmpty { fallbackModule }
        modules.getOrPut(clean) { PlannedModule(clean) }
        return clean
    }

    fun needWeek(module: String, number: Int, title: String): PlannedWeek {
        val cleanTitle = title.trim().ifEmpty { "Week $number" }
        return weeks.getOrPut(module to number) { PlannedWeek(module, number, cleanTitle) }
    }

    data class Ctx(val module: String?, val week: Int?, val weekTitle: String, val classType: ClassType)

    for (entry in stripped.sortedBy { it.logicalPath }) {
        val segments = entry.logicalPath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) continue
        var ctx = Ctx(null, null, "", ClassType.OTHER)

        segments.forEachIndexed { index, rawSeg ->
            val seg = rawSeg.trim()
            if (seg.isEmpty()) return@forEachIndexed
            val isLast = index == segments.lastIndex
            val lastIsFile = !entry.isDirectory && isLast

            if (!lastIsFile && IngestFormat.isZip(seg)) {
                // Nested archive segment.
                val stem = IngestFormat.stemOf(seg)
                val weekNo = IngestFormat.parseWeekNumber(stem)
                if (ctx.module == null) {
                    ctx = ctx.copy(module = needModule(stem))
                } else if (weekNo != null && ctx.week == null) {
                    ctx = ctx.copy(week = weekNo, weekTitle = stem)
                    needWeek(ctx.module!!, weekNo, stem)
                }
                // Otherwise transparent container.
                return@forEachIndexed
            }

            if (!lastIsFile) {
                val weekNo = IngestFormat.parseWeekNumber(seg)
                if (weekNo != null) {
                    val mod = ctx.module ?: needModule(fallbackModule)
                    needWeek(mod, weekNo, seg)
                    ctx = Ctx(mod, weekNo, seg, ClassType.OTHER)
                    return@forEachIndexed
                }
                val cls = IngestFormat.parseClassType(seg)
                if (cls != null) {
                    val mod = ctx.module ?: needModule(fallbackModule)
                    val wNo = ctx.week ?: 1
                    val wTitle = ctx.weekTitle.ifEmpty { "General" }
                    needWeek(mod, wNo, wTitle)
                    ctx = Ctx(mod, wNo, wTitle, cls)
                    return@forEachIndexed
                }
                if (ctx.module == null) {
                    // Top-level directory opens a module.
                    ctx = ctx.copy(module = needModule(seg))
                }
                // Deeper unknown directories are transparent (dirOnly stays).
                return@forEachIndexed
            }

            // Last segment is a file.
            val mod = ctx.module ?: needModule(fallbackModule)
            val wNo = ctx.week ?: 1
            val wTitle = ctx.weekTitle.ifEmpty { "General" }
            needWeek(mod, wNo, wTitle)
            files.add(PlannedFile(mod, wNo, wTitle, ctx.classType, entry))
        }
        // Note: empty directory entries create their module/week rows via the
        // needModule/needWeek calls above; files only add file rows.
    }

    return HierarchyPlan(modules.values.toList(), weeks.values.toList(), files)
}

/**
 * Strips a single shared top-level wrapper directory (e.g. everything under
 * `Semester1/...`) so exporter wrappers don't become phantom modules.
 * Never strips a `.zip` top segment — that segment carries module context.
 */
fun stripSingleWrapper(entries: List<WalkedEntry>): List<WalkedEntry> {
    val tops = entries.map { it.logicalPath.substringBefore('/') }.toSet()
    if (tops.size != 1) return entries
    val top = tops.single()
    if (IngestFormat.isZip(top)) return entries
    // Only strip when the top is a real container (something lives under it);
    // a lone root-level file must never be removed.
    if (entries.none { it.logicalPath.startsWith("$top/") }) return entries
    val allUnderTop = entries.all { it.logicalPath == top || it.logicalPath.startsWith("$top/") }
    if (!allUnderTop) return entries
    // Don't strip when the top IS the module: if any direct child is a week
    // or class-type segment, the top segment carries module context and must
    // be kept (e.g. `ModA/Lecture/l.pdf`, `EmptyMod/Week 9/`).
    val children = entries.mapNotNull { e ->
        e.logicalPath.removePrefix("$top/").takeIf { it.isNotEmpty() }?.substringBefore('/')
    }.toSet()
    val topIsModule = children.any {
        IngestFormat.parseWeekNumber(it) != null || IngestFormat.parseClassType(it) != null
    }
    if (topIsModule) return entries
    return entries.mapNotNull { e ->
        when {
            e.logicalPath == top -> null
            e.logicalPath.startsWith("$top/") ->
                e.copy(logicalPath = e.logicalPath.removePrefix("$top/"))
            else -> e
        }
    }
}
