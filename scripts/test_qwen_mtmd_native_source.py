"""Static regression check for Qwen JNI contract; Gradle also builds JNI."""
from pathlib import Path
src = Path("android_app/app/src/main/cpp/qwen_runtime_jni.cpp").read_text()
back = Path("android_app/app/src/main/java/com/ailm/android/runtime/ai/NativeRuntimeBackends.kt").read_text()
assert '#include "mtmd-helper.h"' in src
assert "mtmd_helper_eval_chunk_single(" in src
assert "bool decode_multimodal_chunk(" not in src
assert "multimodal_context_exceeded:" in src
assert "multimodal_generation_decode_failed:" in src
assert "type_name" in src and "chunk_tokens=" in src
assert "next.pos[0] = position++;" in src
assert "mtmd_tokenize_status=" in src
assert "n_past=" in src and "prompt_tokens=" in src
assert "expected_rgb_size" in src
assert "qwen_semantic_json_invalid:" in back
# Under the Fusion-native contract, Qwen only supplies secondary caption
# and content-routing evidence. It may not invent canonical tag IDs, apparent
# ages or unverifiable anatomical observations.
assert "Do not output canonical IDs, tag IDs or character names." in back
assert "Never invent confidence values." in back
assert "Canonical visual tagging is performed by the native Fusion classifier." in back
assert "sex, body sizes, or occluded traits" not in back

assert "system_available_bytes" in back
assert "val imageSideLimit = when" in back
assert "contextTokens <= 4096 -> 192" in back
assert "contextTokens <= 8192 -> 256" in back
assert "readSingleRgbImage(request.payload, imageSideLimit)" in back
assert "val fallback = downsampleRgb(image, 176)" in back
assert '"multimodal_prefill_failed" in error' in back
assert "position, 0, 128, i + 1 == chunk_count, &position" in src
assert "JsonCompletionDetector" in src
assert 'prompt_text.find("STRICT JSON only")' in src
assert "if (expect_json || expect_compact)" in src
assert "context_params.n_ubatch = 128" in src
assert "val defaultContext = 4096" in back
automation = Path("android_app/app/src/main/java/com/ailm/android/runtime/StandaloneRuntime.kt").read_text()
assert 'failedStages.isNotEmpty() -> mapOf(' in automation
assert '"status" to "deferred_incomplete_analysis"' in automation
assert '"max_new_tokens" to 320' in automation
assert 'if (qwenFallback || payload["secondary_semantic_review"] == true)' in automation
assert 'expect_compact' in src
assert 'compact_completion.consume' in src
assert "FusionVisualLabelMapper(fusionTaxonomy).evaluate(" in automation
assert "val actualTags = nativeTagIds" in automation
assert 'reviewType = "visual_observation_review"' in automation
assert '"candidate_tags" to normalizedSuggestions' in automation
assert '"source" to "qwen_unverified"' in automation
print("Qwen multimodal native batching and diagnostics: OK")
