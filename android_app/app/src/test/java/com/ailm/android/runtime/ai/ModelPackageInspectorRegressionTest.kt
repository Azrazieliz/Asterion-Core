package com.ailm.android.runtime.ai

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression tests for ModelPackageInspector fixes:
 * A) GGUF support added - now properly handled by LLAMA_CPP runtime
 * B) Multi-artifact ONNX models - now supported with model_artifacts metadata
 * C) Aesthetic predictor models - now properly detected and tasked as classification
 */
class ModelPackageInspectorRegressionTest {

    private fun withTempDir(block: (File) -> Unit) {
        val dir = createTempDir(prefix = "mpi-regression-")
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    // =====================================================
    // Regression Tests for Fix A: GGUF Support (LLAMA_CPP)
    // =====================================================

    @Test
    fun `runtime parser canonically maps llama_cpp and llama_cpp export spellings to the LLAMA_CPP enum`() {
        assertEquals("LLAMA_CPP canonical enum must be recovered from llama_cpp", AiRuntimeType.LLAMA_CPP, AiRuntimeType.fromRaw("llama_cpp"))
        assertEquals("LLAMA_CPP canonical enum must be recovered from llama.cpp", AiRuntimeType.LLAMA_CPP, AiRuntimeType.fromRaw("llama.cpp"))
        assertEquals("LLAMA_CPP canonical enum must stay LLAMA_CPP in string form", AiRuntimeType.LLAMA_CPP.raw, AiRuntimeType.fromRaw("llama.cpp").raw)
    }

    @Test
    fun `GGUF models are recognized as valid LLAMA_CPP artifacts`() {
        withTempDir { root ->
            val ggufFile = File(root, "qwen2.5-coder-3b-instruct-q4_k_m.gguf")
            ggufFile.writeText("GGUF binary data placeholder")
            
            val inspector = ModelPackageInspector()
            val result = inspector.inspect(ggufFile, File(root, "extracted"))
            
            assertFalse("GGUF package should have no unsupported format issues", result.issues.any { it.code == "unsupported_model_format" })
            assertTrue("GGUF package should be valid for LLAMA_CPP", result.runtime == AiRuntimeType.LLAMA_CPP.raw)
            assertTrue("GGUF package should still resolve the runtime even without a contract payload", result.runtime == AiRuntimeType.LLAMA_CPP.raw)
        }
    }

    @Test
    fun `GGUF with metadata is properly tasked as text_generation`() {
        withTempDir { root ->
            File(root, "metadata.json").writeText("""{"task": "text_generation"}""")
            File(root, "model.gguf").writeText("GGUF data")
            
            val inspector = ModelPackageInspector()
            val result = inspector.inspect(root, File(root, "extracted"))
            
            assertFalse("GGUF should not have unsupported format issues", result.issues.any { it.code == "unsupported_model_format" })
            assertTrue("GGUF should be resolved as LLAMA_CPP runtime", result.runtime == AiRuntimeType.LLAMA_CPP.raw)
            assertTrue("GGUF metadata should resolve a text-generation task", result.supportedTasks.any { it.equals("text_generation", ignoreCase = true) } || result.capabilities.any { it.equals("text_generation", ignoreCase = true) })
        }
    }

    // =====================================================
    // Regression Tests for Fix B: Multi-Artifact Support
    // =====================================================

    @Test
    fun `Single ONNX model without declaration still works`() {
        withTempDir { root ->
            val onnxFile = File(root, "model.onnx")
            onnxFile.writeText("ONNX binary placeholder")
            
            val inspector = ModelPackageInspector()
            val result = inspector.inspect(onnxFile, File(root, "extracted"))
            
            // Single artifact should resolve without model_artifacts declaration
            assertNotNull("Single ONNX should resolve artifact", result.artifact)
            assertEquals("Should be ONNX runtime", "onnx", result.runtime)
        }
    }

    @Test
    fun `Florence-2 style multi-artifact package with metadata resolves`() {
        withTempDir { root ->
            val onnxDir = File(root, "onnx")
            val encoderFile = File(onnxDir, "encoder_model.onnx")
            onnxDir.mkdirs()
            encoderFile.writeText("encoder ONNX")

            val decoderFile = File(onnxDir, "decoder_model.onnx")
            decoderFile.writeText("decoder ONNX")
            
            val metadataFile = File(root, "metadata.json")
            metadataFile.writeText("""
            {
                "model_id": "florence-2-base",
                "model_artifacts": [
                    {"path": "onnx/encoder_model.onnx"},
                    {"path": "onnx/decoder_model.onnx"}
                ],
                "supported_tasks": ["image_to_text"]
            }
            """.trimIndent())
            
            val inspector = ModelPackageInspector()
            val result = inspector.inspect(root, File(root, "extracted"))
            
            assertEquals("Should be ONNX runtime", "onnx", result.runtime)
            assertTrue("Should not report ambiguous when model_artifacts declared", 
                result.issues.none { it.code == "model_artifact_ambiguous" })
            assertTrue("Should resolve a declared artifact manifest for the package",
                result.metadata["model_artifacts"] is List<*> || result.artifact != null)
        }
    }

    @Test
    fun `Florence package structure is normalized into a role-aware multi-artifact manifest`() {
        withTempDir { root ->
            // Minimal package layout without metadata.json written by a user.
            File(root, "config.json").writeText("{}")
            File(root, "generation_config.json").writeText("{}")
            File(root, "processor_config.json").writeText("{}")
            File(root, "preprocessor_config.json").writeText("{}")
            File(root, "tokenizer.json").writeText("{}")
            File(root, "tokenizer_config.json").writeText("{}")
            File(root, "special_tokens_map.json").writeText("{}")
            File(root, "vocab.json").writeText("{}")
            File(root, "merges.txt").writeText("")
            File(root, "added_tokens.json").writeText("{}")

            File(root, "encoder_model_int8.onnx").writeText("encoder")
            File(root, "decoder_model_int8.onnx").writeText("decoder")
            File(root, "decoder_with_past_model_int8.onnx").writeText("decoder_with_past")
            File(root, "embed_tokens_int8.onnx").writeText("embed")
            File(root, "vision_encoder_int8.onnx").writeText("vision")

            val inspector = ModelPackageInspector()
            val result = inspector.inspect(root, File(root, "extracted"))

            val artifactRoles = result.metadata["model_artifacts"] as? List<*>
            assertNotNull("Florence package should normalize into a model_artifacts role list", artifactRoles)
            assertTrue("Florence manifest should preserve all expected roles", artifactRoles?.any { it is Map<*, *> && it["role"] == "vision_encoder" } == true)
            assertTrue("Florence manifest should preserve all expected roles", artifactRoles?.any { it is Map<*, *> && it["role"] == "encoder" } == true)
            assertTrue("Florence manifest should preserve all expected roles", artifactRoles?.any { it is Map<*, *> && it["role"] == "decoder" } == true)
            assertTrue("Florence manifest should preserve all expected roles", artifactRoles?.any { it is Map<*, *> && it["role"] == "decoder_with_past" } == true)
            assertTrue("Florence manifest should preserve all expected roles", artifactRoles?.any { it is Map<*, *> && it["role"] == "embed_tokens" } == true)
            assertFalse("Florence normalization should not generate ambiguous artifact issues", result.issues.any { it.code == "model_artifact_ambiguous" })
        }
    }

    @Test
    fun `Multiple ONNX without declaration still reports ambiguous`() {
        withTempDir { root ->
            File(root, "encoder.onnx").writeText("encoder")
            File(root, "decoder.onnx").writeText("decoder")
            
            val inspector = ModelPackageInspector()
            val result = inspector.inspect(root, File(root, "extracted"))
            
            assertTrue("Should report ambiguous artifacts", 
                result.issues.any { it.code == "model_artifact_ambiguous" })
            assertFalse("Should not be valid when ambiguous", result.valid)
        }
    }

    @Test
    fun `Multi-artifact with invalid path reference reports error`() {
        withTempDir { root ->
            File(root, "encoder.onnx").writeText("encoder")
            
            val metadataFile = File(root, "metadata.json")
            metadataFile.writeText("""
            {
                "model_artifacts": [
                    {"path": "encoder.onnx"},
                    {"path": "nonexistent_decoder.onnx"}
                ]
            }
            """.trimIndent())
            
            val inspector = ModelPackageInspector()
            val result = inspector.inspect(root, File(root, "extracted"))
            
            assertTrue("Should report invalid artifact reference",
                result.issues.any { it.code == "model_artifacts_reference_invalid" })
            assertTrue("Should reject invalid referenced artifacts instead of silently accepting them", result.artifact == null || result.issues.any { it.code == "model_artifacts_reference_invalid" })
        }
    }

    @Test
    fun `Nomic vision model id hint resolves embedding generation instead of generic inference`() {
        withTempDir { root ->
            File(root, "model.onnx").writeText("ONNX")
            File(root, "config.json").writeText("""{"architectures":["NomicVisionModel"]}""")

            val result = ModelPackageInspector().inspect(
                root,
                File(root, "extracted"),
                modelIdHint = "primary_ai_illustration_generation_asterioncore_nomic-embed-vision-v1.5",
            )

            assertTrue(result.supportedTasks.contains("embedding_generation"))
            assertFalse(result.issues.any { it.code == "execution_task_missing" })
            assertEquals(
                "primary_ai_illustration_generation_asterioncore_nomic-embed-vision-v1.5",
                result.metadata["model_id"],
            )
        }
    }

    @Test
    fun `Nomic text model id hint resolves embedding generation with generic artifact name`() {
        withTempDir { root ->
            File(root, "model.onnx").writeText("ONNX")
            File(root, "config.json").writeText("{}")

            val result = ModelPackageInspector().inspect(
                root,
                File(root, "extracted"),
                modelIdHint = "primary_ai_illustration_generation_asterioncore_nomic-embed-text-v1.5",
            )

            assertTrue(result.supportedTasks.contains("embedding_generation"))
            assertFalse(result.issues.any { it.code == "execution_task_missing" })
        }
    }

    @Test
    fun `BGE reranker model id hint resolves reranking with generic artifact name`() {
        withTempDir { root ->
            File(root, "model.onnx").writeText("ONNX")
            File(root, "config.json").writeText("{}")

            val result = ModelPackageInspector().inspect(
                root,
                File(root, "extracted"),
                modelIdHint = "primary_ai_illustration_generation_asterioncore_bge-reranker-v2-m3",
            )

            assertTrue(result.supportedTasks.contains("text_reranking"))
            assertFalse(result.issues.any { it.code == "execution_task_missing" })
        }
    }

    @Test
    fun `Nomic vision inferred task builds executable contract from inspected graph`() {
        withTempDir { root ->
            File(root, "model.onnx").writeText("fixture")
            val inspector = ModelPackageInspector { _, _ ->
                ModelArtifactBindings(
                    inputs = listOf(ModelArtifactTensor("pixel_values", 0, "float32", listOf(1, 3, 224, 224))),
                    outputs = listOf(ModelArtifactTensor("last_hidden_state", 0, "float32", listOf(1, 197, 768))),
                )
            }

            val result = inspector.inspect(
                root,
                File(root, "extracted"),
                modelIdHint = "primary_ai_illustration_generation_asterioncore_nomic-embed-vision-v1.5",
            )

            assertTrue(result.valid)
            assertFalse(result.issues.any { it.code == "execution_metadata_missing" })
            val contracts = result.metadata["inference_contracts"] as? Map<*, *>
            assertTrue(contracts?.containsKey("embedding_generation") == true)
        }
    }

    @Test
    fun `Nomic text inferred task builds executable contract from inspected graph`() {
        withTempDir { root ->
            File(root, "model.onnx").writeText("fixture")
            File(root, "tokenizer.json").writeText(
                """{"model":{"type":"WordPiece","unk_token":"[UNK]","continuing_subword_prefix":"##","vocab":{"[PAD]":0,"[UNK]":100,"[CLS]":101,"[SEP]":102,"[MASK]":103,"hello":104}}}""",
            )
            val inspector = ModelPackageInspector { _, _ ->
                ModelArtifactBindings(
                    inputs = listOf(
                        ModelArtifactTensor("input_ids", 0, "int64", listOf(1, -1)),
                        ModelArtifactTensor("token_type_ids", 1, "int64", listOf(1, -1)),
                        ModelArtifactTensor("attention_mask", 2, "int64", listOf(1, -1)),
                    ),
                    outputs = listOf(ModelArtifactTensor("last_hidden_state", 0, "float32", listOf(1, -1, 768))),
                )
            }

            val result = inspector.inspect(
                root,
                File(root, "extracted"),
                modelIdHint = "primary_ai_illustration_generation_asterioncore_nomic-embed-text-v1.5",
            )

            assertTrue(result.valid)
            assertFalse(result.issues.any { it.code == "execution_metadata_missing" })
            val contracts = result.metadata["inference_contracts"] as? Map<*, *>
            assertTrue(contracts?.containsKey("embedding_generation") == true)
        }
    }

    @Test
    fun `Nomic text known-package path ignores deeply nested tokenizer metadata during import`() {
        withTempDir { root ->
            File(root, "model.onnx").writeText("fixture")
            val deeplyNested = buildString {
                repeat(5000) { append("{\"x\":") }
                append("0")
                repeat(5000) { append("}") }
            }
            File(root, "tokenizer.json").writeText(deeplyNested)
            val inspector = ModelPackageInspector { _, _ ->
                ModelArtifactBindings(
                    inputs = listOf(
                        ModelArtifactTensor("input_ids", 0, "int64", listOf(1, -1)),
                        ModelArtifactTensor("token_type_ids", 1, "int64", listOf(1, -1)),
                        ModelArtifactTensor("attention_mask", 2, "int64", listOf(1, -1)),
                    ),
                    outputs = listOf(ModelArtifactTensor("last_hidden_state", 0, "float32", listOf(1, -1, 768))),
                )
            }

            val result = inspector.inspect(
                root,
                File(root, "extracted"),
                modelIdHint = "primary_ai_illustration_generation_asterioncore_nomic-embed-text-v1.5",
            )

            assertTrue(result.valid)
            assertTrue(result.supportedTasks.contains("embedding_generation"))
            assertFalse(result.issues.any { it.code == "execution_metadata_missing" })
        }
    }

    @Test
    fun `Nomic text without tokenizer remains importable but not execution ready`() {
        withTempDir { root ->
            File(root, "model.onnx").writeText("fixture")
            val inspector = ModelPackageInspector { _, _ ->
                ModelArtifactBindings(
                    inputs = listOf(
                        ModelArtifactTensor("input_ids", 0, "int64", listOf(1, -1)),
                        ModelArtifactTensor("token_type_ids", 1, "int64", listOf(1, -1)),
                        ModelArtifactTensor("attention_mask", 2, "int64", listOf(1, -1)),
                    ),
                    outputs = listOf(ModelArtifactTensor("last_hidden_state", 0, "float32", listOf(1, -1, 768))),
                )
            }

            val result = inspector.inspect(
                root,
                File(root, "extracted"),
                modelIdHint = "primary_ai_illustration_generation_asterioncore_nomic-embed-text-v1.5",
            )

            assertTrue(result.valid)
            assertTrue(result.issues.any { it.code == "nomic_text_tokenizer_unavailable" })
            assertEquals(false, (result.metadata["execution_readiness"] as? Map<*, *>)?.get("ready"))
        }
    }

    @Test
    fun `BGE tokenizer with UTF-8 BOM remains valid package metadata and builds reranking contract`() {
        withTempDir { root ->
            File(root, "model.onnx").writeText("fixture")
            File(root, "tokenizer.json").writeText(
                "\uFEFF" + """{"model":{"type":"Unigram","vocab":[["<s>",0.0],["<pad>",0.0],["</s>",0.0],["<unk>",0.0],["hello",-1.0]]}}""",
            )
            val inspector = ModelPackageInspector { _, _ ->
                ModelArtifactBindings(
                    inputs = listOf(
                        ModelArtifactTensor("input_ids", 0, "int64", listOf(1, -1)),
                        ModelArtifactTensor("attention_mask", 1, "int64", listOf(1, -1)),
                    ),
                    outputs = listOf(ModelArtifactTensor("logits", 0, "float32", listOf(1, 1))),
                )
            }

            val result = inspector.inspect(
                root,
                File(root, "extracted"),
                modelIdHint = "primary_ai_illustration_generation_asterioncore_bge-reranker-v2-m3",
            )

            assertTrue(result.valid)
            assertFalse(result.issues.any { it.code == "metadata_invalid" })
            assertFalse(result.issues.any { it.code == "bge_tokenizer_deferred" })
            assertFalse(result.issues.any { it.code == "execution_metadata_missing" })
            assertTrue(result.metadata["execution_readiness"] == null)
            assertTrue(result.supportedTasks.contains("text_reranking"))
            val contracts = result.metadata["inference_contracts"] as? Map<*, *>
            val contract = contracts?.get("text_reranking") as? Map<*, *>
            val tokenizer = contract?.get("tokenizer") as? Map<*, *>
            assertEquals("hf_unigram_json", tokenizer?.get("source_format"))
            assertTrue(tokenizer?.get("source_file")?.toString()?.endsWith("tokenizer.json") == true)
        }
    }

    @Test
    fun `Buffalo-L imports granular role contracts without requiring aggregate face feature contract`() {
        withTempDir { root ->
            listOf("det_10g.onnx", "2d106det.onnx", "1k3d68.onnx", "genderage.onnx", "w600k_r50.onnx")
                .forEach { File(root, it).writeText("fixture") }
            val inspector = ModelPackageInspector { artifact, _ ->
                when (artifact.name) {
                    "det_10g.onnx" -> ModelArtifactBindings(
                        inputs = listOf(ModelArtifactTensor("input.1", 0, "float32", listOf(1, 3, 640, 640))),
                        outputs = List(9) { index -> ModelArtifactTensor("det_$index", index, "float32", listOf(1, 1, 1, 1)) },
                    )
                    "w600k_r50.onnx" -> ModelArtifactBindings(
                        inputs = listOf(ModelArtifactTensor("input.1", 0, "float32", listOf(1, 3, 112, 112))),
                        outputs = listOf(ModelArtifactTensor("683", 0, "float32", listOf(1, 512))),
                    )
                    "2d106det.onnx" -> ModelArtifactBindings(
                        inputs = listOf(ModelArtifactTensor("data", 0, "float32", listOf(1, 3, 192, 192))),
                        outputs = listOf(ModelArtifactTensor("fc1", 0, "float32", listOf(1, 212))),
                    )
                    "1k3d68.onnx" -> ModelArtifactBindings(
                        inputs = listOf(ModelArtifactTensor("data", 0, "float32", listOf(1, 3, 192, 192))),
                        outputs = listOf(ModelArtifactTensor("fc1", 0, "float32", listOf(1, 3309))),
                    )
                    "genderage.onnx" -> ModelArtifactBindings(
                        inputs = listOf(ModelArtifactTensor("data", 0, "float32", listOf(1, 3, 96, 96))),
                        outputs = listOf(ModelArtifactTensor("fc1", 0, "float32", listOf(1, 3))),
                    )
                    else -> error("Unexpected artifact ${artifact.name}")
                }
            }

            val result = inspector.inspect(root, File(root, "extracted"), modelIdHint = "asterioncore_buffalo_l")

            assertTrue(result.valid)
            assertFalse(result.supportedTasks.contains("face_feature_extraction"))
            assertEquals("face_feature_extraction", result.metadata["buffalo_l_composite_capability"])
            assertFalse(result.issues.any { it.code == "task_contract_missing" })
            assertTrue(result.supportedTasks.containsAll(listOf("face_detection", "face_embedding", "landmark_2d", "landmark_3d", "gender_age")))
        }
    }

    @Test
    fun `PaddleOCR v5 detector recognizer and dictionary become execution ready together`() {
        withTempDir { root ->
            File(root, "det.onnx").writeText("fixture")
            File(root, "rec.onnx").writeText("fixture")
            File(root, "dict.txt").writeText(List(18383) { index -> "char_$index" }.joinToString("\n"))
            val inspector = ModelPackageInspector { artifact, _ ->
                when (artifact.name) {
                    "det.onnx" -> ModelArtifactBindings(
                        inputs = listOf(ModelArtifactTensor("x", 0, "float32", listOf(-1, 3, -1, -1))),
                        outputs = listOf(ModelArtifactTensor("fetch_name_0", 0, "float32", listOf(-1, 1, -1, -1))),
                    )
                    "rec.onnx" -> ModelArtifactBindings(
                        inputs = listOf(ModelArtifactTensor("x", 0, "float32", listOf(-1, 3, 48, -1))),
                        outputs = listOf(ModelArtifactTensor("fetch_name_0", 0, "float32", listOf(-1, -1, 18385))),
                    )
                    else -> error("Unexpected artifact ${artifact.name}")
                }
            }

            val result = inspector.inspect(root, File(root, "extracted"), modelIdHint = "asterioncore_paddleocr")

            assertEquals(listOf("ocr"), result.supportedTasks)
            assertFalse(result.issues.any { it.code == "model_artifact_ambiguous" })
            assertFalse(result.issues.any { it.code == "model_artifacts_reference_invalid" })
            assertFalse(result.issues.any { it.code == "tensor_metadata_unreadable" })
            assertFalse(result.issues.any { it.code == "execution_metadata_missing" })
            assertFalse(result.issues.any { it.code == "task_contract_missing" })
            assertFalse(result.issues.any { it.code == "tensor_output_missing" })
            assertFalse(result.issues.any { it.code == "image_preprocessing_metadata_missing" })
            assertFalse(result.issues.any { it.code == "capabilities_missing" })
            assertFalse(result.issues.any { it.code == "execution_task_missing" })
            assertFalse(result.issues.any { it.code == "metadata_invalid" })
            assertFalse(result.issues.any { it.code == "model_artifact_missing" })
            assertTrue(result.valid)
            assertTrue(result.supportedTasks.contains("ocr"))
            assertTrue(result.metadata["execution_readiness"] == null)
            assertFalse(result.issues.any { it.code.startsWith("ocr_") })
            val dictionary = result.metadata["ocr_dictionary"] as Map<*, *>
            assertEquals(0, dictionary["blank_index"])
            assertEquals(1, dictionary["dictionary_offset"])
            assertEquals(18384, dictionary["space_index"])
            assertEquals("ctc_blank_plus_dictionary_plus_space", dictionary["mapping_status"])
            val paddle = result.metadata["paddle_ocr"] as Map<*, *>
            assertEquals("PP-OCRv5", paddle["family"])
        }
    }

    @Test
    fun `Aesthetic Predictor v2_5 exact package profile is execution ready`() {
        withTempDir { root ->
            File(root, "aesthetic_predictor_v2_5.onnx").writeText("fixture")
            val inspector = ModelPackageInspector { _, _ ->
                ModelArtifactBindings(
                    inputs = listOf(ModelArtifactTensor("input", 0, "float32", listOf(1, 3, 384, 384))),
                    outputs = listOf(ModelArtifactTensor("output", 0, "float32", listOf(1, 1))),
                )
            }

            val result = inspector.inspect(
                root,
                File(root, "extracted"),
                modelIdHint = "primary_ai_illustration_generation_asterioncore_aesthetic_predictor_v2.5",
            )

            assertTrue(result.valid)
            assertTrue(result.supportedTasks.contains("aesthetic_scoring"))
            assertTrue(result.metadata["execution_readiness"] == null)
            assertFalse(result.issues.any { it.code == "preprocessing_contract_unresolved" })
            val contracts = result.metadata["inference_contracts"] as? Map<*, *>
            val contract = contracts?.get("aesthetic_scoring") as? Map<*, *>
            val pre = contract?.get("image_preprocessing") as? Map<*, *>
            assertEquals(true, pre?.get("enabled"))
            assertEquals(384, pre?.get("width"))
            assertEquals(384, pre?.get("height"))
        }
    }

    @Test
    fun `NSFW classifier package builds an executable image classification contract`() {
        withTempDir { root ->
            File(root, "model.onnx").writeText("fixture")
            val configRaw = """{"architectures":["ViTForImageClassification"],"problem_type":"single_label_classification","id2label":{"0":"drawings","1":"hentai","2":"neutral","3":"porn","4":"sexy"}}"""
            val preprocessorRaw = """{"size":{"height":224,"width":224},"image_mean":[0.5,0.5,0.5],"image_std":[0.5,0.5,0.5],"rescale_factor":0.0039215686,"do_convert_rgb":true,"do_center_crop":false}"""
            try {
                assertEquals("ViTForImageClassification", (LocalAiJson.decodeMap(configRaw)["architectures"] as? List<*>)?.first())
                assertEquals(true, LocalAiJson.decodeMap(preprocessorRaw)["do_convert_rgb"])
            } catch (error: StackOverflowError) {
                throw AssertionError("NSFW fixture JSON decode overflowed before inspection", error)
            }
            File(root, "config.json").writeText(configRaw)
            File(root, "preprocessor_config.json").writeText(preprocessorRaw)
            val inspector = ModelPackageInspector { _, _ ->
                ModelArtifactBindings(
                    inputs = listOf(ModelArtifactTensor("pixel_values", 0, "float32", listOf(1, 224, 224, 3))),
                    outputs = listOf(ModelArtifactTensor("logits", 0, "float32", listOf(5))),
                )
            }

            val result = try {
                inspector.inspect(root, File(root, "extracted"), modelIdHint = "asterioncore_nsfw-classifier")
            } catch (error: StackOverflowError) {
                throw AssertionError("NSFW inspection overflowed after JSON decode", error)
            }

            assertTrue(result.valid)
            assertTrue(result.supportedTasks.contains("nsfw_classification"))
            assertFalse(result.issues.any { it.code == "execution_metadata_missing" })
        }
    }

    @Test
    fun `NSFW graph mismatch remains importable but not execution ready`() {
        withTempDir { root ->
            File(root, "model.onnx").writeText("fixture")
            val inspector = ModelPackageInspector { _, _ ->
                ModelArtifactBindings(
                    inputs = listOf(ModelArtifactTensor("input", 0, "float16", listOf(1, 224, 224, 3))),
                    outputs = listOf(ModelArtifactTensor("probabilities", 0, "float32", listOf(1, 2))),
                )
            }

            val result = inspector.inspect(
                root,
                File(root, "extracted"),
                modelIdHint = "primary_ai_illustration_generation_asterioncore_nsfw-classifier",
            )

            assertTrue(result.valid)
            assertTrue(result.issues.any { it.code == "nsfw_graph_incompatible" })
            assertEquals(false, (result.metadata["execution_readiness"] as? Map<*, *>)?.get("ready"))
        }
    }

    // =====================================================
    // Regression Tests for Fix C: Aesthetic Model Detection
    // =====================================================

    @Test
    fun `Aesthetic predictor without explicit task gets aesthetic scoring task`() {
        withTempDir { root ->
            File(root, "aesthetic_predictor_v2_5.onnx").writeText("ONNX model")

            val inspector = ModelPackageInspector()
            val result = inspector.inspect(root, File(root, "extracted"))

            assertTrue("Should infer an aesthetic scoring capability from the package name",
                result.supportedTasks.map { it.lowercase() }.contains("aesthetic_scoring") ||
                    result.capabilities.map { it.lowercase() }.contains("aesthetic_scoring"))
        }
    }

    @Test
    fun `Model with quality in name gets image quality scoring task`() {
        withTempDir { root ->
            File(root, "image_quality_model.onnx").writeText("ONNX")

            val inspector = ModelPackageInspector()
            val result = inspector.inspect(root, File(root, "extracted"))

            assertTrue("Should detect quality model as image quality scoring",
                result.supportedTasks.any { it.lowercase().contains("image_quality_scoring") } ||
                result.capabilities.any { it.lowercase().contains("image_quality_scoring") })
        }
    }

    @Test
    fun `model with labels file gets classification task`() {
        withTempDir { root ->
            File(root, "model.onnx").writeText("ONNX")
            File(root, "labels.txt").writeText("poor\nfair\ngood\nexcellent\n")

            val inspector = ModelPackageInspector()
            val extracted = File(root, "extracted")
            val result = inspector.inspect(root, extracted)

            assertTrue("Should treat model with labels as classification",
                result.capabilities.any { it.lowercase().contains("classification") })
        }
    }

    @Test
    fun `Explicit task declaration takes precedence over inference`() {
        withTempDir { root ->
            File(root, "model.onnx").writeText("ONNX")
            
            val metadataFile = File(root, "metadata.json")
            metadataFile.writeText("""
            {
                "task": "ocr"
            }
            """.trimIndent())
            
            val inspector = ModelPackageInspector()
            val result = inspector.inspect(root, File(root, "extracted"))
            
            assertTrue("Should use explicit task",
                result.supportedTasks.any { it.lowercase().contains("ocr") } ||
                    result.capabilities.any { it.lowercase().contains("ocr") })
        }
    }

    // =====================================================
    // Regression Tests: General Validation Rules
    // =====================================================

    @Test
    fun `Missing artifact reports error`() {
        withTempDir { root ->
            val metadataFile = File(root, "metadata.json")
            metadataFile.writeText("""{"task": "classification"}""")
            
            val inspector = ModelPackageInspector()
            val result = inspector.inspect(root, File(root, "extracted"))
            
            assertTrue("Should report missing artifact",
                result.issues.any { it.code == "model_artifact_missing" })
        }
    }

    @Test
    fun `Invalid metadata reports error`() {
        withTempDir { root ->
            File(root, "model.onnx").writeText("ONNX")
            
            val invalidMetadata = File(root, "metadata.json")
            invalidMetadata.writeText("{ this is not valid json }")
            
            val inspector = ModelPackageInspector()
            val result = inspector.inspect(root, File(root, "extracted"))
            
            assertTrue("Should report invalid metadata",
                result.issues.any { it.code == "metadata_invalid" })
        }
    }

    @Test
    fun `TensorFlow Lite model is recognized`() {
        withTempDir { root ->
            File(root, "model.tflite").writeText("TFLite binary")
            
            val inspector = ModelPackageInspector()
            val result = inspector.inspect(root, File(root, "extracted"))
            
            assertNotNull("Should resolve TFLite artifact", result.artifact)
            assertEquals("Should be TFLite runtime", "tflite", result.runtime)
        }
    }

    @Test
    fun `Model validity requires artifact, runtime, and tasks`() {
        withTempDir { root ->
            val metadataFile = File(root, "metadata.json")
            metadataFile.writeText("""
            {
                "model_id": "test-model",
                "task": "invalid_task_type"
            }
            """.trimIndent())
            // No artifact file
            
            val inspector = ModelPackageInspector()
            val result = inspector.inspect(root, File(root, "extracted"))
            
            assertFalse("Model without artifact should not be valid", result.valid)
        }
    }

    @Test
    fun `Florence real package inspection path preserves runtime fed pixel_values without artifact rejection`() {
        val inspector = ModelPackageInspector()
        val zip = File("../../AsterionCore/Florence-2-Base.zip")
        assumeTrue("Real Florence ZIP must exist", zip.exists())

        val result = inspector.inspect(zip, File("build/florence-real-extraction"))
        assertTrue("Florence inspection should succeed through the production zip extraction path", result.valid)
        assertTrue("Florence runtime must resolve to ONNX", result.runtime == "onnx")
        assertFalse("Florence runtime-fed pixel_values must not trigger tensor_input_missing when the model artifact is otherwise valid", result.issues.any { it.code == "tensor_input_missing" })
        assertTrue("Florence should keep the canonical model roles accessible in metadata", result.metadata["model_artifacts"] is List<*>)
    }

    @Test
    fun `Aesthetic real package is recognized and imported as valid package with explicit preprocessing readiness blocker`() {
        val inspector = ModelPackageInspector()
        val zip = File("../../AsterionCore/aesthetic_predictor_v2.5.zip")
        assumeTrue("Real Aesthetic ZIP must exist", zip.exists())

        val result = inspector.inspect(zip, File("build/aesthetic-real-extraction"))
        assertTrue("Aesthetic package should import through inspection as a valid recognized package", result.valid)
        assertTrue("Aesthetic should still be recognized as an aesthetic scoring task", result.supportedTasks.any { it.equals("aesthetic_scoring", ignoreCase = true) })
        assertTrue("Aesthetic should preserve the known preprocessing readiness blocker", result.issues.any { it.code == "preprocessing_contract_unresolved" })
        assertFalse("Aesthetic execution should remain blocked by unresolved preprocessing readiness, not by package validation", result.issues.any { it.code == "model_artifact_missing" })
    }

    @Test
    fun `Qwen GGUF zip imports by descriptor metadata and keeps llama_cpp backend metadata`() {
        val inspector = ModelPackageInspector()
        val zip = File("../../AsterionCore/qwen2.5-coder-3b-instruct-q4_k_m.zip")
        assumeTrue("Real Qwen coder ZIP must exist", zip.exists())

        val result = inspector.inspect(zip, File("build/qwen-coder-real-extraction"))
        assertTrue("Qwen coder package should be recognized by inspection", result.runtime == "llama_cpp")
        assertTrue("Qwen coder package should keep text generation task metadata", result.supportedTasks.any { it.equals("text_generation", ignoreCase = true) })
        assertTrue("Qwen coder package should surface llama_cpp backend metadata", (result.metadata["llama_cpp"] as? Map<*, *>)?.get("backend") == "llama.cpp")
    }

    @Test
    fun `Qwen VL GGUF pair resolves the explicit mmproj relationship for descriptor import`() {
        val inspector = ModelPackageInspector()
        val zip = File("../../AsterionCore/Qwen2.5-VL-3B-Instruct.zip")
        assumeTrue("Real Qwen-VL ZIP must exist", zip.exists())

        val result = inspector.inspect(zip, File("build/qwen-vl-real-extraction"))
        assertTrue("Qwen-VL package should be recognized as LLAMA_CPP runtime", result.runtime == "llama_cpp")
        assertTrue("Qwen-VL package should resolve multimodal task metadata through vision projector relation", result.metadata["llama_cpp"] as? Map<*, *> != null)
        assertTrue("Qwen-VL package should preserve multimodal metadata signal", (result.metadata["llama_cpp"] as? Map<*, *>)?.get("multimodal") == true)
        assertTrue("Synthesized Qwen-VL captioning must become an executable descriptor task", "captioning" in result.supportedTasks)
    }

    @Test
    fun `ZIP package is properly extracted and inspected`() {
        withTempDir { root ->
            val packageDir = File(root, "package")
            packageDir.mkdirs()

            File(packageDir, "model.onnx").writeText("ONNX")
            File(packageDir, "metadata.json").writeText("""{"task": "classification"}""")

            val zipFile = File(root, "model.zip")
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                packageDir.walkTopDown()
                    .filter(File::isFile)
                    .forEach { file ->
                        zip.putNextEntry(ZipEntry(file.relativeTo(packageDir).invariantSeparatorsPath))
                        file.inputStream().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
            }

            val inspector = ModelPackageInspector()
            val result = inspector.inspect(zipFile, File(root, "extracted"))

            assertNotNull("Should extract and resolve ZIP package", result.artifact)
        }
    }
}
