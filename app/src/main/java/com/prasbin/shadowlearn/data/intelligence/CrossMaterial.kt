package com.prasbin.shadowlearn.data.intelligence

/**
 * I6 cross-material relationship contract (docs/I6_DESIGN_CONTRACT.md).
 * Three relationship types, two statuses, no scores. UNKNOWN is
 * represented by absence from the result list — never emitted.
 */
enum class RelationshipType {
    SAME_WEEK,
    SAME_MODULE,
    SHARED_CONTENT
}

enum class RelationshipStatus {
    VERIFIED,
    POSSIBLE
}

/**
 * One ranked related material. Carries everything the future UI needs:
 * identity, type, status, evidence-level WHY, verbatim excerpt (empty for
 * structural-only relationships — never fabricated), and the owning week
 * for existing hierarchy routing.
 */
data class RelatedMaterial(
    val sourceFileId: Long,
    val relatedFileId: Long,
    val relatedFileName: String,
    val type: RelationshipType,
    val status: RelationshipStatus,
    val reason: String,
    val matchedTerms: List<String>,
    val evidenceChunkIds: List<Long>,
    val excerpt: String,
    val relatedWeekId: Long?,
    val relatedWeekLabel: String?,
    val relatedModuleName: String?
)
