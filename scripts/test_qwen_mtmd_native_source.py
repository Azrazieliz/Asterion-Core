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
assert "system_available_bytes" in back
assert "val maxDimension = 768" in back
print("Qwen multimodal native batching and diagnostics: OK")
