package com.ailm.android.runtime.ai

/**
 * Single source of truth for the model-task contract shared by model planning
 * and execution validation. Importer versions before v2.0.7 sometimes
 * registered a multimodal Qwen-VL GGUF as text_generation only even though
 * captioning/semantic tasks were synthesized in its metadata.
 */
internal object AiModelTaskSupport {
    private val VISION_SEMANTIC_TASKS = setOf(
        "captioning",
        "series_recognition",
        "character_recognition",
        "tag_prediction",
        "normalization",
    )

    fun supports(model: AiModelDescriptor, taskType: String): Boolean {
        val normalized = AiTaskTypes.normalize(taskType)
        val declared = model.supportedTasks.map(AiTaskTypes::normalize).toSet()
        if (normalized in declared) return true
        if (normalized !in VISION_SEMANTIC_TASKS) return false

        val llama = model.metadata["llama_cpp"] as? Map<*, *> ?: return false
        if (llama["multimodal"] != true || llama["text_only"] == true) return false
        val runtimes = (model.supportedRuntimes + model.requiredRuntime)
            .map { it.trim().lowercase().replace('-', '_') }
        if ("llama_cpp" !in runtimes) return false
        val projector = (model.metadata["artifact_paths_by_role"] as? Map<*, *>)
            ?.get("vision_projector")?.toString().orEmpty()
        // Readiness must not claim vision execution on text-only Qwen.
        if (projector.isBlank()) return false
        return "text_generation" in declared || "captioning" in declared
    }
}
