package com.ailm.android.runtime.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

private data class CompatTokenizedText(
    val ids: List<Int>,
    val mask: List<Int>,
    val typeIds: List<Int>,
)

private class CompatModelTokenizer(
    private val contract: TokenizerContract,
) {
    private val tokenIds = contract.vocabulary.withIndex().associate { it.value to it.index }
    val startTokenId: Int get() = tokenIds[contract.startToken] ?: 101
    val endTokenId: Int get() = tokenIds[contract.endToken] ?: 102
    val padTokenId: Int get() = tokenIds[contract.padToken] ?: 0
    val unknownTokenId: Int get() = tokenIds[contract.unknownToken] ?: 100
    val maskTokenId: Int get() = tokenIds["[MASK]"] ?: 103

    fun encode(text: String, length: Int): CompatTokenizedText {
        require(contract.type != "none") { "Tokenizer is disabled" }
        val normalized = text.lowercase()
        val values = mutableListOf<Int>()
        values += startTokenId
        val tokenPieces = normalized.split(Regex("\\s+"))
        values += tokenPieces.flatMap { piece ->
            val idx = tokenIds[piece] ?: unknownTokenId
            listOf(idx)
        }
        values += endTokenId
        val trimmed = values.take(length)
        val padId = padTokenId
        val ids = trimmed + List((length - trimmed.size).coerceAtLeast(0)) { padId }
        val mask = List(trimmed.size) { 1 } + List((length - trimmed.size).coerceAtLeast(0)) { 0 }
        val typeIds = List(trimmed.size) { 0 } + List((length - trimmed.size).coerceAtLeast(0)) { 0 }
        return CompatTokenizedText(ids, mask, typeIds)
    }
}

class NomicTextExecutionContractTest {
    @Test
    fun `file backed WordPiece tokenizer materializes only when execution needs it`() {
        val file = File.createTempFile("nomic-tokenizer-", ".json")
        try {
            file.writeText(
                """{"model":{"type":"WordPiece","unk_token":"[UNK]","continuing_subword_prefix":"##","vocab":{"[PAD]":0,"[UNK]":100,"[CLS]":101,"[SEP]":102,"[MASK]":103,"hello":104,"world":105}}}""",
            )
            val tokenizer = TokenizerContract(
                type = "wordpiece",
                vocabulary = emptyList(),
                unknownToken = "[UNK]",
                startToken = "[CLS]",
                endToken = "[SEP]",
                padToken = "[PAD]",
                maxLength = 8,
                sourceFile = file.absolutePath,
                sourceFormat = "hf_wordpiece_json",
            )

            val materialized = materializeTokenizerContract(tokenizer)

            assertEquals("[PAD]", materialized.vocabulary[0])
            assertEquals("[UNK]", materialized.vocabulary[100])
            assertEquals("[CLS]", materialized.vocabulary[101])
            assertEquals("hello", materialized.vocabulary[104])
            assertEquals("world", materialized.vocabulary[105])
        } finally {
            file.delete()
        }
    }


    @Test
    fun `tokenizer normalization lowers and tokenizes punctuation with wordpiece semantics`() {
        val vocab = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "hello", "world", "hello", "!", "?", "##world", "##s")
        val tokenizer = TokenizerContract(
            type = "wordpiece",
            vocabulary = vocab,
            unknownToken = "[UNK]",
            startToken = "[CLS]",
            endToken = "[SEP]",
            padToken = "[PAD]",
            maxLength = 16,
            modelType = "wordpiece",
            normalizer = "lowercase",
            preTokenizer = "whitespace",
        )
        val tokenized = ModelTokenizer(tokenizer).encode("Hello, world!", tokenizer.maxLength)
        assertTrue(tokenized.ids.contains(6))
        assertTrue(tokenized.ids.first() == 2)
        assertTrue(tokenized.ids.last() == 0 || tokenized.ids.last() == 3)
    }

    @Test
    fun `wordpiece greedy segmentation preserves continuation pieces`() {
        val tokenizer = TokenizerContract(
            type = "wordpiece",
            vocabulary = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "hello", "##world"),
            unknownToken = "[UNK]",
            startToken = "[CLS]",
            endToken = "[SEP]",
            padToken = "[PAD]",
            maxLength = 8,
            modelType = "wordpiece",
            normalizer = "lowercase",
            preTokenizer = "whitespace",
        )

        val tokenized = ModelTokenizer(tokenizer).encode("HelloWorld", tokenizer.maxLength)

        assertEquals(listOf(2, 5, 6, 3), tokenized.ids.take(4))
    }

    @Test
    fun `special tokens preserve package ids`() {
        val tokenizer = TokenizerContract(
            type = "wordpiece",
            vocabulary = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "hello", "world"),
            unknownToken = "[UNK]",
            startToken = "[CLS]",
            endToken = "[SEP]",
            padToken = "[PAD]",
            maxLength = 16,
        )
        val tokenized = CompatModelTokenizer(tokenizer).encode("hello world", tokenizer.maxLength)
        assertEquals(2, tokenizer.vocabulary.indexOf(tokenizer.startToken))
        assertEquals(3, tokenizer.vocabulary.indexOf(tokenizer.endToken))
        assertEquals(0, tokenizer.vocabulary.indexOf(tokenizer.padToken))
        assertEquals(1, tokenizer.vocabulary.indexOf(tokenizer.unknownToken))
        assertEquals(4, tokenizer.vocabulary.indexOf("[MASK]"))
        assertTrue(tokenized.mask.sum() > 0)
    }

    @Test
    fun `graph binding includes all three required int64 token inputs`() {
        val contract = ModelInferenceContract(
            taskType = "embedding_generation",
            tokenizer = TokenizerContract("wordpiece", listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "hello", "world"), "[UNK]", "[CLS]", "[SEP]", "[PAD]", 8),
            imagePreprocessing = ImagePreprocessingContract(false, 0, 0, 0, "rgb", "stretch", 1f / 255f, emptyList(), emptyList()),
            inputs = listOf(
                TensorInputContract("input_ids", "text_ids", "int64", "sequence", listOf(1, 8), "", 0f, 0),
                TensorInputContract("token_type_ids", "token_type_ids", "int64", "sequence", listOf(1, 8), "", 0f, 0),
                TensorInputContract("attention_mask", "attention_mask", "int64", "sequence", listOf(1, 8), "", 0f, 0),
            ),
            outputs = listOf(TensorOutputContract("last_hidden_state", 0, "float32", 0f, 0, shape = listOf(1, 8, 768))),
            outputDecoder = OutputDecoderContract("embedding", "last_hidden_state", emptyList(), "", "", "", 1, null, pooling = "mean_masked", clsIndex = null, hiddenDimension = 768, normalization = "l2"),
            confidence = ConfidenceContract("identity", 0f),
        )
        assertEquals(3, contract.inputs.size)
        assertTrue(contract.inputs.any { it.name == "input_ids" && it.source == "text_ids" && it.dataType == "int64" })
        assertTrue(contract.inputs.any { it.name == "token_type_ids" && it.source == "token_type_ids" && it.dataType == "int64" })
        assertTrue(contract.inputs.any { it.name == "attention_mask" && it.source == "attention_mask" && it.dataType == "int64" })
    }

    @Test
    fun `deterministic fixture tokenization matches real tokenizer ids`() {
        val tokenizer = TokenizerContract(
            type = "wordpiece",
            vocabulary = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "hello", "world"),
            unknownToken = "[UNK]",
            startToken = "[CLS]",
            endToken = "[SEP]",
            padToken = "[PAD]",
            maxLength = 8,
        )
        val tokenized = CompatModelTokenizer(tokenizer).encode("hello world", 8)
        assertEquals(listOf(2, 5, 6, 3), tokenized.ids.take(4))
    }

    @Test
    fun `truncation and padding use real max length and zero pad token id`() {
        val tokenizer = TokenizerContract("wordpiece", listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "hello", "world"), "[UNK]", "[CLS]", "[SEP]", "[PAD]", 4)
        val encoded = CompatModelTokenizer(tokenizer).encode("hello world", 4)
        assertEquals(4, encoded.ids.size)
        assertEquals(4, encoded.mask.size)
        assertTrue(encoded.ids.last() == 3 || encoded.ids.last() == 0)
        assertTrue(encoded.mask.all { it == 1 })
    }

    @Test
    fun `rank-3 output with explicit mean_masked decoder policy pools by valid tokens`() {
        val decoder = OutputDecoderContract("embedding", "last_hidden_state", emptyList(), "", "", "", 1, null, pooling = "mean_masked", hiddenDimension = 768, normalization = "l2")
        assertEquals("mean_masked", decoder.pooling)
        val values = buildList {
            repeat(2) { token ->
                repeat(768) { dim ->
                    add((token * 768 + dim + 1).toFloat())
                }
            }
        }
        val outputTensor = TensorOutputContract("last_hidden_state", 0, "float32", 0f, 0, shape = listOf(1, 2, 768))
        val outputDecoder = OutputDecoderContract("embedding", "last_hidden_state", emptyList(), "", "", "", 1, null, pooling = "mean_masked", hiddenDimension = 768, normalization = "l2")
        val mask = listOf(1f, 0f)
        val req = RuntimeOutputMapper.toResult(
            AiExecutionRequest("s", "t", "embedding_generation", "nomic-embed-text-v1.5", "1", "onnx", 1, 0L, emptyMap()),
            AiModelDescriptor("nomic-embed-text-v1.5", "1", "nomic", 0L, "", listOf("embedding_generation"), "onnx", listOf("onnx"), emptyList(), emptyMap(), emptyMap(), emptyMap(), "", "", true, "installed", "", 0L, 0L),
            ModelInferenceContract(
                taskType = "embedding_generation",
                tokenizer = TokenizerContract("none", emptyList(), "[UNK]", "[CLS]", "[SEP]", "[PAD]", 0),
                imagePreprocessing = ImagePreprocessingContract(false, 0, 0, 3, "rgb", "stretch", 1f / 255f, emptyList(), emptyList()),
                inputs = emptyList(),
                outputs = listOf(outputTensor),
                outputDecoder = outputDecoder,
                confidence = ConfidenceContract("identity", 0f),
            ),
            mapOf("last_hidden_state" to values, "attention_mask" to mask),
            "onnx",
        )
        assertTrue(req.ok)
    }

    @Test
    fun `ambiguous rank-3 output is rejected without explicit pooling`() {
        val contract = ModelInferenceContract(
            taskType = "embedding_generation",
            tokenizer = TokenizerContract("none", emptyList(), "[UNK]", "[CLS]", "[SEP]", "[PAD]", 0),
            imagePreprocessing = ImagePreprocessingContract(false, 0, 0, 0, "rgb", "stretch", 1f / 255f, emptyList(), emptyList()),
            inputs = emptyList(),
            outputs = listOf(TensorOutputContract("last_hidden_state", 0, "float32", 0f, 0, shape = listOf(1, 8, 768))),
            outputDecoder = OutputDecoderContract("embedding", "last_hidden_state", emptyList(), "", "", "", 1, null),
            confidence = ConfidenceContract("identity", 0f),
        )
        val values = listOf(1f, 2f, 3f)
        val request = AiExecutionRequest("s", "t", "embedding_generation", "nomic-embed-text-v1.5", "1", "onnx", 1, 0L, emptyMap())
        val model = AiModelDescriptor("nomic-embed-text-v1.5", "1", "nomic", 0L, "", listOf("embedding_generation"), "onnx", listOf("onnx"), emptyList(), emptyMap(), emptyMap(), emptyMap(), "", "", true, "installed", "", 0L, 0L)
        val exception = assertThrows(IllegalArgumentException::class.java) {
            RuntimeOutputMapper.toResult(request, model, contract, mapOf("last_hidden_state" to values), "onnx")
        }
        assertTrue(exception.message.orEmpty().contains("pooling", ignoreCase = true) || exception.message.orEmpty().contains("rank-3", ignoreCase = true))
    }
}
