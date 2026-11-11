package com.ailm.android.runtime

/**
 * Preserve independent image enrichment failures as diagnostics, but do not
 * let optional tasks suppress valid Fusion visual results. Safety, visual
 * classifier and unknown failures remain blocking for file organization.
 */
internal object AutomationStageFailurePolicy {
    private val optionalEnrichment = setOf(
        "ocr", "embedding_generation", "aesthetic_scoring",
    )

    fun isBlocking(stageType: String, verifiedNativeTags: Boolean): Boolean =
        stageType !in optionalEnrichment &&
            !(stageType == "qwen_semantic_bundle" && verifiedNativeTags)

    fun blocking(
        failures: List<Map<String, Any>>, verifiedNativeTags: Boolean,
    ): List<Map<String, Any>> = failures.filter { failure ->
        isBlocking(failure["stage_type"]?.toString().orEmpty(), verifiedNativeTags)
    }
}
