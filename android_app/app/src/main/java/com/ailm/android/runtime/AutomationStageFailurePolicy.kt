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

    fun hasVisualEvidence(
        imageSucceeded: Boolean, imageTags: Collection<String>, verifiedCropTags: Collection<String>,
    ): Boolean = verifiedCropTags.isNotEmpty() || (imageSucceeded && imageTags.isNotEmpty())

    fun isBlocking(stageType: String, verifiedNativeTags: Boolean): Boolean =
        stageType !in optionalEnrichment

    fun blocking(
        failures: List<Map<String, Any>>, verifiedNativeTags: Boolean,
    ): List<Map<String, Any>> = failures.filter { failure ->
        isBlocking(failure["stage_type"]?.toString().orEmpty(), verifiedNativeTags)
    }
}
