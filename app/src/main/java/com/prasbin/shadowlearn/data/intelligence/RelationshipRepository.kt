package com.prasbin.shadowlearn.data.intelligence

import com.prasbin.shadowlearn.data.db.AcademicDao
import com.prasbin.shadowlearn.data.db.ExtractionDao
import com.prasbin.shadowlearn.data.search.ExcerptGenerator
import com.prasbin.shadowlearn.data.search.SearchOutcome
import com.prasbin.shadowlearn.data.search.SearchRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * I6 cross-material relationships — the single shared entry point for the
 * future Home/Status/Explanation surfaces (all consume this; never
 * separate logic). Data acquisition lives here; interpretation lives in
 * [CrossMaterialEngine] (pure). Semester isolation is enforced three
 * times: candidate enumeration walks only the requested semester, FTS
 * confirmation is semester-scoped, and every candidate re-verifies
 * hierarchy ownership before ranking.
 *
 * Stateless, side-effect free, no tables, no migrations. On demand only —
 * never called from snapshot paths (keeps Home snapshot cost flat).
 */
class RelationshipRepository(
    private val academicDao: AcademicDao,
    private val extractionDao: ExtractionDao,
    private val search: SearchRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Up to 3 ranked related materials for one source file. Empty list =
     * UNKNOWN (no button shown). Never throws for missing data — every
     * unresolvable step degrades to absence.
     */
    suspend fun relatedFor(semesterId: Long, sourceFileId: Long): List<RelatedMaterial> =
        withContext(dispatcher) {
            val source = academicDao.file(sourceFileId) ?: return@withContext emptyList()
            val sourceWeek = academicDao.week(source.weekId) ?: return@withContext emptyList()
            val sourceModule = academicDao.module(sourceWeek.moduleId) ?: return@withContext emptyList()
            if (sourceModule.semesterId != semesterId) return@withContext emptyList()
            // No indexed content → nothing to understand (hierarchy browsing
            // already covers pure navigation; contract §10).
            if (extractionDao.chunksForFile(sourceFileId).isEmpty()) return@withContext emptyList()

            data class Candidate(val fileId: Long, val weekId: Long, val moduleId: Long)
            val enumerated = mutableListOf<Candidate>()
            for (module in academicDao.getModules(semesterId)) {
                for (week in academicDao.getWeeks(module.id)) {
                    for (file in academicDao.getFiles(week.id)) {
                        if (file.id == sourceFileId) continue
                        if (file.sha256.isNotEmpty() && file.sha256 == source.sha256) continue
                        enumerated.add(Candidate(file.id, week.id, module.id))
                    }
                }
            }
            if (enumerated.isEmpty()) return@withContext emptyList()

            val terms = CrossMaterialEngine.extractTerms(
                extractionDao.chunksForFile(sourceFileId).map { it.text }
            )
            // FTS confirmation per term (bounded: ≤12 indexed lookups).
            val hitsByFile = mutableMapOf<Long, MutableMap<String, MutableList<Long>>>()
            for (term in terms) {
                when (val outcome = search.search(term, semesterId)) {
                    is SearchOutcome.Failed -> { /* term unusable; contributes nothing */ }
                    is SearchOutcome.Results -> {
                        for (hit in outcome.results) {
                            hitsByFile
                                .getOrPut(hit.academicFileId) { mutableMapOf() }
                                .getOrPut(term) { mutableListOf() }
                                .add(hit.chunkId)
                        }
                    }
                }
            }
            val infos = enumerated.mapNotNull { (fileId, weekId, moduleId) ->
                val file = academicDao.file(fileId) ?: return@mapNotNull null
                val week = academicDao.week(weekId) ?: return@mapNotNull null
                val module = academicDao.module(moduleId) ?: return@mapNotNull null
                if (module.semesterId != semesterId) return@mapNotNull null
                val perTerm = hitsByFile[fileId] ?: emptyMap()
                CrossMaterialEngine.CandidateInfo(
                    fileId = fileId,
                    fileName = file.fileName,
                    sha256 = file.sha256,
                    weekId = weekId,
                    weekNumber = week.weekNumber,
                    moduleId = moduleId,
                    moduleName = module.name,
                    matchedTerms = perTerm.keys.sorted(),
                    matchedChunkIds = perTerm.values.flatten().distinct().take(5)
                )
            }
            val sourceInfo = CrossMaterialEngine.SourceInfo(
                fileId = sourceFileId,
                sha256 = source.sha256,
                weekId = sourceWeek.id,
                weekNumber = sourceWeek.weekNumber,
                moduleId = sourceModule.id
            )
            CrossMaterialEngine.evaluate(sourceInfo, infos).map { ranked ->
                val c = ranked.candidate
                val excerpt = if (c.matchedTerms.isEmpty()) {
                    ""
                } else {
                    val chunkId = c.matchedChunkIds.firstOrNull()
                    val text = chunkId?.let {
                        runCatching { extractionDao.chunk(it) }.getOrNull()
                    }?.text
                    if (text.isNullOrBlank()) "" else ExcerptGenerator.generate(
                        text,
                        c.matchedTerms.take(3)
                    ).text
                }
                RelatedMaterial(
                    sourceFileId = sourceFileId,
                    relatedFileId = c.fileId,
                    relatedFileName = c.fileName,
                    type = ranked.type,
                    status = ranked.status,
                    reason = reasonFor(ranked.type, c),
                    matchedTerms = c.matchedTerms,
                    evidenceChunkIds = c.matchedChunkIds,
                    excerpt = excerpt,
                    relatedWeekId = c.weekId,
                    relatedWeekLabel = "Week ${c.weekNumber}",
                    relatedModuleName = c.moduleName
                )
            }
        }

    private fun reasonFor(
        type: RelationshipType,
        c: CrossMaterialEngine.CandidateInfo
    ): String = when (type) {
        RelationshipType.SAME_WEEK ->
            "Same academic week · Week ${c.weekNumber}" + sharedSuffix(c.matchedTerms)
        RelationshipType.SAME_MODULE ->
            "Same module · ${c.moduleName}" + sharedSuffix(c.matchedTerms)
        RelationshipType.SHARED_CONTENT -> {
            val shown = c.matchedTerms.take(3).joinToString(", ")
            val more = if (c.matchedTerms.size > 3) " (+${c.matchedTerms.size - 3} more)" else ""
            "${c.matchedTerms.size} shared indexed terms: $shown$more"
        }
    }

    private fun sharedSuffix(terms: List<String>): String {
        if (terms.isEmpty()) return ""
        val shown = terms.take(3).joinToString(", ")
        val more = if (terms.size > 3) " (+${terms.size - 3} more)" else ""
        return " · shared: $shown$more"
    }
}
