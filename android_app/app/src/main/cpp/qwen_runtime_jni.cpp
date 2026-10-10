#include <jni.h>

#include <atomic>
#include <cstring>
#include <limits>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"

namespace {

struct QwenHandle {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    mtmd_context * multimodal = nullptr;
    llama_sampler * sampler = nullptr;
    std::mutex mutex;
    std::atomic_bool cancelled{false};
};

std::string jstring_to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throw_runtime(JNIEnv * env, const std::string & message) {
    jclass error = env->FindClass("java/lang/IllegalStateException");
    if (error != nullptr) env->ThrowNew(error, message.c_str());
}

bool append_prompt(QwenHandle * handle, const std::string & user, std::vector<llama_token> & prompt_tokens, std::string & error) {
    const llama_vocab * vocab = llama_model_get_vocab(handle->model);
    const char * tmpl = llama_model_chat_template(handle->model, nullptr);
    if (tmpl == nullptr || tmpl[0] == '\0') {
        error = "chat_template_missing";
        return false;
    }
    llama_chat_message message{"user", user.c_str()};
    int32_t rendered_size = llama_chat_apply_template(tmpl, &message, 1, true, nullptr, 0);
    if (rendered_size <= 0) {
        error = "chat_template_render_failed";
        return false;
    }
    std::string rendered(static_cast<size_t>(rendered_size), '\0');
    if (llama_chat_apply_template(tmpl, &message, 1, true, rendered.data(), rendered_size + 1) < 0) {
        error = "chat_template_render_failed";
        return false;
    }
    const int32_t required = llama_tokenize(vocab, rendered.c_str(), static_cast<int32_t>(rendered.size()), nullptr, 0, true, true);
    if (required <= 0) {
        error = "tokenization_failed";
        return false;
    }
    prompt_tokens.resize(static_cast<size_t>(required));
    const int32_t actual = llama_tokenize(vocab, rendered.c_str(), static_cast<int32_t>(rendered.size()), prompt_tokens.data(), required, true, true);
    if (actual < 0) {
        error = "tokenization_failed";
        return false;
    }
    prompt_tokens.resize(static_cast<size_t>(actual));
    return true;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_ailm_android_runtime_ai_LlamaCppNative_nativeProbe(JNIEnv * env, jclass) {
    llama_backend_init();
    const char * version = llama_version();
    return env->NewStringUTF(version == nullptr ? "" : version);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ailm_android_runtime_ai_LlamaCppNative_nativeLoad(JNIEnv * env, jclass, jstring path, jint context_size, jint threads) {
    const std::string model_path = jstring_to_string(env, path);
    if (model_path.empty()) {
        throw_runtime(env, "model_load_failed: missing_path");
        return 0;
    }
    auto * handle = new QwenHandle();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    handle->model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (handle->model == nullptr) {
        delete handle;
        throw_runtime(env, "model_load_failed: llama_model_load_from_file");
        return 0;
    }
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context_size > 0 ? context_size : llama_model_n_ctx_train(handle->model));
    context_params.n_batch = 512;
    context_params.n_ubatch = 512;
    context_params.n_threads = threads > 0 ? threads : 2;
    context_params.n_threads_batch = context_params.n_threads;
    handle->context = llama_init_from_model(handle->model, context_params);
    if (handle->context == nullptr) {
        llama_model_free(handle->model);
        delete handle;
        throw_runtime(env, "context_creation_failed");
        return 0;
    }
    handle->sampler = llama_sampler_init_greedy();
    if (handle->sampler == nullptr) {
        llama_free(handle->context);
        llama_model_free(handle->model);
        delete handle;
        throw_runtime(env, "sampler_creation_failed");
        return 0;
    }
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ailm_android_runtime_ai_LlamaCppNative_nativeLoadMultimodal(JNIEnv * env, jclass, jstring path, jstring mmproj_path, jint context_size, jint threads) {
    const std::string model_path = jstring_to_string(env, path);
    const std::string projector_path = jstring_to_string(env, mmproj_path);
    if (model_path.empty() || projector_path.empty()) {
        throw_runtime(env, "multimodal_projector_missing");
        return 0;
    }
    auto * handle = new QwenHandle();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    handle->model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (handle->model == nullptr) {
        delete handle;
        throw_runtime(env, "model_load_failed: llama_model_load_from_file");
        return 0;
    }
    mtmd_context_params mm_params = mtmd_context_params_default();
    mm_params.use_gpu = false;
    mm_params.n_threads = threads > 0 ? threads : 2;
    mm_params.warmup = false;
    handle->multimodal = mtmd_init_from_file(projector_path.c_str(), handle->model, mm_params);
    if (handle->multimodal == nullptr || !mtmd_support_vision(handle->multimodal)) {
        if (handle->multimodal != nullptr) mtmd_free(handle->multimodal);
        llama_model_free(handle->model);
        delete handle;
        throw_runtime(env, "projector_load_failed: incompatible_or_nonvision_mmproj");
        return 0;
    }
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context_size > 0 ? context_size : llama_model_n_ctx_train(handle->model));
    context_params.n_batch = 512;
    context_params.n_ubatch = 512;
    context_params.n_threads = threads > 0 ? threads : 2;
    context_params.n_threads_batch = context_params.n_threads;
    handle->context = llama_init_from_model(handle->model, context_params);
    if (handle->context == nullptr) {
        mtmd_free(handle->multimodal);
        llama_model_free(handle->model);
        delete handle;
        throw_runtime(env, "context_creation_failed");
        return 0;
    }
    handle->sampler = llama_sampler_init_greedy();
    if (handle->sampler == nullptr) {
        llama_free(handle->context);
        mtmd_free(handle->multimodal);
        llama_model_free(handle->model);
        delete handle;
        throw_runtime(env, "sampler_creation_failed");
        return 0;
    }
    return reinterpret_cast<jlong>(handle);
}

bool decode_multimodal_chunk(QwenHandle * handle, const mtmd_input_chunk * chunk, llama_pos & position, bool output_logits) {
    const int n_embd = llama_model_n_embd_inp(handle->model);
    const size_t n_tokens = mtmd_input_chunk_get_n_tokens(chunk);
    if (n_tokens == 0) return true;
    const bool image = mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_IMAGE;
    llama_batch batch = llama_batch_init(static_cast<int32_t>(n_tokens), image ? n_embd : 0, 1);
    if (image) {
        if (mtmd_encode_chunk(handle->multimodal, chunk) != 0) {
            llama_batch_free(batch);
            return false;
        }
        const float * embeddings = mtmd_get_output_embd(handle->multimodal);
        if (embeddings == nullptr) {
            llama_batch_free(batch);
            return false;
        }
        std::memcpy(batch.embd, embeddings, n_tokens * static_cast<size_t>(n_embd) * sizeof(float));
    } else {
        size_t text_count = 0;
        const llama_token * tokens = mtmd_input_chunk_get_tokens_text(chunk, &text_count);
        if (tokens == nullptr || text_count != n_tokens) {
            llama_batch_free(batch);
            return false;
        }
        std::memcpy(batch.token, tokens, n_tokens * sizeof(llama_token));
    }
    for (size_t i = 0; i < n_tokens; ++i) {
        llama_pos pos = position + static_cast<llama_pos>(i);
        if (image && mtmd_decode_use_mrope(handle->multimodal)) {
            const mtmd_image_tokens * image_tokens = mtmd_input_chunk_get_tokens_image(chunk);
            const mtmd_decoder_pos decoder_pos = mtmd_image_tokens_get_decoder_pos(image_tokens, position, i);
            pos = static_cast<llama_pos>(decoder_pos.t);
        }
        batch.pos[i] = pos;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = output_logits && i + 1 == n_tokens;
    }
    const int result = llama_decode(handle->context, batch);
    llama_batch_free(batch);
    if (result != 0) return false;
    position += static_cast<llama_pos>(mtmd_input_chunk_get_n_pos(chunk));
    return true;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ailm_android_runtime_ai_LlamaCppNative_nativeGenerateMultimodal(JNIEnv * env, jclass, jlong raw_handle, jstring user_prompt, jbyteArray rgb, jint width, jint height, jint max_new_tokens) {
    auto * handle = reinterpret_cast<QwenHandle *>(raw_handle);
    if (handle == nullptr) {
        throw_runtime(env, "invalid_native_handle");
        return nullptr;
    }
    if (handle->multimodal == nullptr || width <= 0 || height <= 0) {
        throw_runtime(env, "multimodal_projector_missing");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    handle->cancelled.store(false);
    const jsize rgb_size = env->GetArrayLength(rgb);
    const int64_t expected_rgb_size =
        static_cast<int64_t>(width) * static_cast<int64_t>(height) * 3LL;
    if (expected_rgb_size <= 0 ||
        expected_rgb_size > static_cast<int64_t>(std::numeric_limits<jsize>::max()) ||
        static_cast<int64_t>(rgb_size) != expected_rgb_size) {
        throw_runtime(env, "unsupported_image_format: expected RGB8");
        return nullptr;
    }
    std::vector<unsigned char> pixels(static_cast<size_t>(rgb_size));
    env->GetByteArrayRegion(rgb, 0, rgb_size, reinterpret_cast<jbyte *>(pixels.data()));
    mtmd_bitmap * bitmap = mtmd_bitmap_init(static_cast<uint32_t>(width), static_cast<uint32_t>(height), pixels.data());
    if (bitmap == nullptr) {
        throw_runtime(env, "image_decode_failed");
        return nullptr;
    }
    const std::string marker = mtmd_default_marker();
    const std::string prompt_text = "<|im_start|>user\n" + marker + jstring_to_string(env, user_prompt) + "<|im_end|>\n<|im_start|>assistant\n";
    mtmd_input_text input_text{prompt_text.data(), prompt_text.size(), true, true};
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[] = {bitmap};
    if (mtmd_tokenize(handle->multimodal, chunks, &input_text, bitmaps, 1) != 0) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        throw_runtime(env, "multimodal_prompt_failed");
        return nullptr;
    }
    const int limit = max_new_tokens > 0 ? max_new_tokens : 1;
    const size_t chunk_count = mtmd_input_chunks_size(chunks);
    size_t required_positions = static_cast<size_t>(limit);
    for (size_t i = 0; i < chunk_count; ++i) {
        const mtmd_input_chunk * chunk = mtmd_input_chunks_get(chunks, i);
        required_positions += mtmd_input_chunk_get_n_pos(chunk);
    }
    if (required_positions > static_cast<size_t>(llama_n_ctx(handle->context))) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        throw_runtime(env, "prompt_too_long");
        return nullptr;
    }

    llama_pos position = 0;
    for (size_t i = 0; i < chunk_count; ++i) {
        const mtmd_input_chunk * chunk = mtmd_input_chunks_get(chunks, i);
        if (!decode_multimodal_chunk(handle, chunk, position, i + 1 == chunk_count)) {
            mtmd_input_chunks_free(chunks);
            mtmd_bitmap_free(bitmap);
            throw_runtime(env, "multimodal_prefill_failed");
            return nullptr;
        }
    }
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap);

    std::vector<llama_token> generated;
    const llama_vocab * vocab = llama_model_get_vocab(handle->model);
    for (int step = 0; step < limit && !handle->cancelled.load(); ++step) {
        llama_token token = llama_sampler_sample(handle->sampler, handle->context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        generated.push_back(token);
        llama_sampler_accept(handle->sampler, token);
        llama_batch next = llama_batch_get_one(&token, 1);
        if (llama_decode(handle->context, next) != 0) {
            throw_runtime(env, "decode_failed");
            return nullptr;
        }
    }
    if (handle->cancelled.load()) {
        throw_runtime(env, "cancelled");
        return nullptr;
    }
    std::string result(generated.size() * 8 + 1, '\0');
    const int32_t size = llama_detokenize(vocab, generated.data(), static_cast<int32_t>(generated.size()), result.data(), static_cast<int32_t>(result.size()), true, false);
    if (size < 0) {
        throw_runtime(env, "detokenization_failed");
        return nullptr;
    }
    result.resize(static_cast<size_t>(size));
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ailm_android_runtime_ai_LlamaCppNative_nativeGenerate(JNIEnv * env, jclass, jlong raw_handle, jstring user_prompt, jint max_new_tokens) {
    auto * handle = reinterpret_cast<QwenHandle *>(raw_handle);
    if (handle == nullptr) {
        throw_runtime(env, "invalid_native_handle");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    handle->cancelled.store(false);
    std::vector<llama_token> prompt_tokens;
    std::string error;
    if (!append_prompt(handle, jstring_to_string(env, user_prompt), prompt_tokens, error)) {
        throw_runtime(env, error);
        return nullptr;
    }
    if (prompt_tokens.size() + static_cast<size_t>(max_new_tokens > 0 ? max_new_tokens : 1) > llama_n_ctx(handle->context)) {
        throw_runtime(env, "prompt_too_long");
        return nullptr;
    }
    llama_batch batch = llama_batch_init(static_cast<int32_t>(prompt_tokens.size()), 0, 1);
    for (size_t i = 0; i < prompt_tokens.size(); ++i) {
        batch.token[i] = prompt_tokens[i];
        batch.pos[i] = static_cast<llama_pos>(i);
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = i + 1 == prompt_tokens.size();
    }
    if (llama_decode(handle->context, batch) != 0) {
        llama_batch_free(batch);
        throw_runtime(env, "prompt_eval_failed");
        return nullptr;
    }
    llama_batch_free(batch);

    std::vector<llama_token> generated;
    const llama_vocab * vocab = llama_model_get_vocab(handle->model);
    const int limit = max_new_tokens > 0 ? max_new_tokens : 1;
    for (int step = 0; step < limit && !handle->cancelled.load(); ++step) {
        llama_token token = llama_sampler_sample(handle->sampler, handle->context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        generated.push_back(token);
        llama_sampler_accept(handle->sampler, token);
        llama_batch next = llama_batch_get_one(&token, 1);
        if (llama_decode(handle->context, next) != 0) {
            throw_runtime(env, "decode_failed");
            return nullptr;
        }
    }
    if (handle->cancelled.load()) {
        throw_runtime(env, "cancelled");
        return nullptr;
    }
    std::string result;
    result.resize(generated.size() * 8 + 1);
    const int32_t size = llama_detokenize(vocab, generated.data(), static_cast<int32_t>(generated.size()), result.data(), static_cast<int32_t>(result.size()), true, false);
    if (size < 0) {
        throw_runtime(env, "detokenization_failed");
        return nullptr;
    }
    result.resize(static_cast<size_t>(size));
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_ailm_android_runtime_ai_LlamaCppNative_nativeCancel(JNIEnv *, jclass, jlong raw_handle) {
    auto * handle = reinterpret_cast<QwenHandle *>(raw_handle);
    if (handle != nullptr) handle->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ailm_android_runtime_ai_LlamaCppNative_nativeRelease(JNIEnv *, jclass, jlong raw_handle) {
    auto * handle = reinterpret_cast<QwenHandle *>(raw_handle);
    if (handle == nullptr) return;
    std::lock_guard<std::mutex> lock(handle->mutex);
    if (handle->sampler != nullptr) llama_sampler_free(handle->sampler);
    if (handle->context != nullptr) llama_free(handle->context);
    if (handle->multimodal != nullptr) mtmd_free(handle->multimodal);
    if (handle->model != nullptr) llama_model_free(handle->model);
    delete handle;
}
