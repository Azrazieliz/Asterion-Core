package com.ailm.android.runtime.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelTaskSupportTest {
    private fun model(multimodal: Boolean, projector: Boolean) = AiModelDescriptor(
        modelId = "qwen-vl", version = "1", displayName = "Qwen VL",
        sizeBytes = 1840L * 1024 * 1024, hashSha256 = "",
        supportedTasks = listOf("text_generation"),
        requiredRuntime = "llama_cpp", supportedRuntimes = listOf("llama_cpp"),
        dependencies = emptyList(), requiredHardware = emptyMap(),
        compatibility = emptyMap(),
        metadata = mapOf(
            "llama_cpp" to mapOf("multimodal" to multimodal, "text_only" to !multimodal),
            "artifact_paths_by_role" to if (projector) mapOf("vision_projector" to "/models/mmproj.gguf") else emptyMap<String, String>(),
        ),
        source = "test", sourceUri = "", installed = true,
        installState = "installed", installPath = "/models/qwen.gguf",
        createdAtMs = 0L, updatedAtMs = 0L,
    )

    @Test fun legacyMultimodalQwenSupportsCaptioningWithoutReimport() {
        assertTrue(AiModelTaskSupport.supports(model(true, true), "captioning"))
        assertTrue(AiModelTaskSupport.supports(model(true, true), "character_recognition"))
    }

    @Test fun missingProjectorAndTextOnlyModelsDoNotMasqueradeAsVisionModels() {
        assertFalse(AiModelTaskSupport.supports(model(true, false), "captioning"))
        assertFalse(AiModelTaskSupport.supports(model(false, true), "captioning"))
    }

    @Test fun unrelatedCapabilitiesRemainRejected() {
        assertFalse(AiModelTaskSupport.supports(model(true, true), "aesthetic_scoring"))
    }
}
