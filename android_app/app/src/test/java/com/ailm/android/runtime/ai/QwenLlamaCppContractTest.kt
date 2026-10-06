package com.ailm.android.runtime.ai

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class QwenLlamaCppContractTest {
    @Test
    fun `qwen coder metadata selects real llama cpp text backend`() {
        val bridge = FakeLlamaBridge()
        val backend = LlamaCppBackend({ _, _ -> qwenModel() }, context = nullContext(), bridge = bridge)

        assertEquals(AiRuntimeProviderState.AVAILABLE, backend.providerState)
        assertTrue(backend.supportsModel(qwenModel()))
        assertEquals("LLAMA.CPP Runtime", backend.queryCapabilities().backendName)
    }

    @Test
    fun `qwen vl metadata selects multimodal backend with projector role`() {
        val backend = LlamaCppBackend({ _, _ -> qwenVlModel() }, context = nullContext(), bridge = FakeLlamaBridge())

        assertTrue(backend.supportsModel(qwenVlModel()))
        assertEquals(43L, (runSuspend { backend.loadModel(qwenVlModel()) }!!.metadata["native_handle"] as Number).toLong())
    }

    @Test
    fun `llama cpp advertises only native qwen execution tasks`() {
        val backend = LlamaCppBackend({ _, _ -> qwenVlModel() }, context = nullContext(), bridge = FakeLlamaBridge())
        val tasks = backend.queryCapabilities().supportedTasks

        assertTrue("character_recognition" in tasks)
        assertTrue("tag_prediction" in tasks)
        assertTrue("captioning" in tasks)
        assertFalse("nsfw_classification" in tasks)
        assertFalse("embedding_generation" in tasks)
    }

    @Test
    fun `qwen vl missing projector is rejected without text fallback`() {
        val model = qwenVlModel().copy(metadata = qwenVlModel().metadata - "artifact_paths_by_role")
        val bridge = FakeLlamaBridge()
        val backend = LlamaCppBackend({ _, _ -> model }, context = nullContext(), bridge = bridge)

        assertTrue(backend.supportsModel(model))
        assertEquals(null, runSuspend { backend.loadModel(model) })
        assertEquals(0, bridge.loadCalls)
    }

    @Test
    fun `llama cpp descriptor validation uses native contract instead of tensor contract`() {
        assertTrue(ModelInferenceContract.validationIssues(qwenModel()).isEmpty())
        assertTrue(ModelInferenceContract.validationIssues(qwenVlModel()).isEmpty())
    }

    @Test
    fun `llama cpp multimodal descriptor validation still requires projector role`() {
        val model = qwenVlModel().copy(metadata = qwenVlModel().metadata - "artifact_paths_by_role")
        val issues = ModelInferenceContract.validationIssues(model)

        assertTrue(issues.any { issue ->
            issue.code == "inference_contract_invalid" &&
                issue.message.contains("vision_projector")
        })
    }

    @Test
    fun `onnx descriptor still requires tensor inference contract`() {
        val model = qwenModel().copy(
            requiredRuntime = "onnx",
            supportedRuntimes = listOf("onnx"),
            installPath = "qwen.onnx",
            metadata = emptyMap(),
        )

        assertTrue(ModelInferenceContract.validationIssues(model).any { it.code == "inference_contract_invalid" })
    }

    @Test
    fun `text request uses native generation and preserves utf8 output`() {
        val bridge = FakeLlamaBridge(generated = "你好, Qwen")
        val backend = LlamaCppBackend({ _, _ -> qwenModel() }, context = nullContext(), bridge = bridge)
        val result = runSuspend {
            backend.execute(
                AiExecutionRequest("session", "task", "text_generation", "qwen", "1", "llama_cpp", 1, 0L, mapOf("text" to "Write a greeting", "max_new_tokens" to 8)),
                AiProgressReporter { _, _ -> },
            )
        }

        assertTrue(result.ok)
        assertEquals("你好, Qwen", result.details["generated_text"])
        assertEquals(1, bridge.loadCalls)
        assertEquals(0, bridge.releaseCalls)
        assertTrue(runSuspend { backend.release() })
        assertEquals(1, bridge.releaseCalls)
    }

    @Test
    fun `sequential qwen requests reuse one native model load`() {
        val bridge = FakeLlamaBridge(generated = "ok")
        val backend = LlamaCppBackend({ _, _ -> qwenModel() }, context = nullContext(), bridge = bridge)

        repeat(2) { index ->
            val result = runSuspend {
                backend.execute(
                    AiExecutionRequest("session-$index", "task-$index", "text_generation", "qwen", "1", "llama_cpp", 1, 0L, mapOf("text" to "hello")),
                    AiProgressReporter { _, _ -> },
                )
            }
            assertTrue(result.ok)
        }

        assertEquals(1, bridge.loadCalls)
        assertEquals(0, bridge.releaseCalls)
        assertTrue(runSuspend { backend.release() })
        assertEquals(1, bridge.releaseCalls)
    }

    @Test
    fun `missing model is reported without native load`() {
        val bridge = FakeLlamaBridge()
        val backend = LlamaCppBackend({ _, _ -> null }, context = nullContext(), bridge = bridge)
        val result = runSuspend {
            backend.execute(
                AiExecutionRequest("session", "task", "text_generation", "qwen", "1", "llama_cpp", 1, 0L, mapOf("text" to "hello")),
                AiProgressReporter { _, _ -> },
            )
        }

        assertFalse(result.ok)
        assertEquals(0, bridge.loadCalls)
    }

    @Test
    fun `native bridge cancellation targets active handle`() {
        val bridge = FakeLlamaBridge()
        val backend = LlamaCppBackend({ _, _ -> qwenModel() }, context = nullContext(), bridge = bridge)
        val handle = runSuspend { backend.loadModel(qwenModel()) }!!
        assertTrue(runSuspend { backend.cancel("missing") }.not())
        assertTrue(runSuspend { backend.unloadModel(handle) })
        assertEquals(1, bridge.releaseCalls)
    }

    private fun qwenModel(): AiModelDescriptor = model(
        metadata = mapOf(
            "llama_cpp" to mapOf("text_only" to true, "architecture" to "qwen2", "chat_template_source" to "gguf_embedded"),
            "context_length" to 32768,
            "vocab_size" to 151936,
            "bos_token_id" to 151643,
            "eos_token_id" to 151645,
        ),
    ).copy(supportedTasks = listOf("text_generation", "prompt_generation", "normalization"))

    private fun qwenVlModel(): AiModelDescriptor = model(
        metadata = mapOf(
            "llama_cpp" to mapOf("text_only" to false, "multimodal" to true, "architecture" to "qwen2vl", "projector_role" to "mmproj.gguf"),
            "artifact_paths_by_role" to mapOf("text_model" to "qwen-vl.gguf", "vision_projector" to "mmproj.gguf"),
        ),
        installPath = "qwen-vl.gguf",
    ).copy(
        supportedTasks = listOf(
            "text_generation",
            "prompt_generation",
            "captioning",
            "series_recognition",
            "character_recognition",
            "tag_prediction",
            "normalization",
        ),
    )

    private fun model(metadata: Map<String, Any>, installPath: String = "qwen.gguf"): AiModelDescriptor = AiModelDescriptor(
        modelId = "qwen",
        version = "1",
        displayName = "Qwen2.5-Coder",
        sizeBytes = 1024,
        hashSha256 = "",
        supportedTasks = listOf("text_generation"),
        requiredRuntime = "llama_cpp",
        supportedRuntimes = listOf("llama_cpp"),
        dependencies = emptyList(),
        requiredHardware = emptyMap(),
        compatibility = emptyMap(),
        metadata = metadata,
        source = "local",
        sourceUri = "",
        installed = true,
        installState = "installed",
        installPath = installPath,
        createdAtMs = 0L,
        updatedAtMs = 0L,
    )

    private fun nullContext(): Context = ContextWrapper(null)

    private fun <T> runSuspend(block: suspend () -> T): T {
        var value: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                value = result
            }
        })
        return value!!.getOrThrow()
    }

    private class FakeLlamaBridge(private val generated: String = "ok") : LlamaCppRuntimeBridge {
        var loadCalls = 0
        var releaseCalls = 0

        override fun probe(): String = "0.4.1"
        override fun loadModel(path: String, contextSize: Int, threads: Int): Long {
            loadCalls += 1
            return 42L
        }
        override fun loadMultimodalModel(path: String, mmprojPath: String, contextSize: Int, threads: Int): Long {
            loadCalls += 1
            return 43L
        }
        override fun generate(handle: Long, prompt: String, maxNewTokens: Int): String = generated
        override fun generateMultimodal(handle: Long, prompt: String, rgb: ByteArray, width: Int, height: Int, maxNewTokens: Int): String = generated
        override fun cancel(handle: Long) = Unit
        override fun release(handle: Long) {
            releaseCalls += 1
        }
    }
}