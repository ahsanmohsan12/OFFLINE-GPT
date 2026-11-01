// Real JNI bridge to llama.cpp C API
// Pinned llama.cpp commit: 785322d17f2d51e5185cf996a1f4e46d87640841
// Verified against app/src/main/cpp/llama.cpp/include/llama.h
//
// Low-end 32-bit Android defaults (fixed):
//   n_ctx            = 256
//   n_batch          = 64
//   n_ubatch         = 64
//   max_new_tokens   = 128
//   n_threads        = 2
//   load_mode        = LLAMA_LOAD_MODE_MMAP (mmap on, no mlock)
//   temp             = 0.7f
//   top_k            = 40
//   top_p            = 0.9f
//   repeat_penalty   = 1.1f

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "PocketGGUF_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int32_t kFixedContextSize   = 256;
constexpr int32_t kFixedBatchSize     = 64;
constexpr int32_t kFixedMaxNewTokens  = 128;
constexpr int32_t kFixedThreads       = 2;

constexpr float   kFixedTemp          = 0.7f;
constexpr int32_t kFixedTopK          = 40;
constexpr float   kFixedTopP          = 0.9f;
constexpr float   kFixedRepeatPenalty = 1.1f;
constexpr int32_t kPenaltyLastN       = 64;

struct EngineState {
    std::mutex      mutex;
    llama_model *   model = nullptr;
    llama_context * ctx   = nullptr;
    std::string     loaded_model_path;
    std::string     chat_template;
    bool            backend_initialized = false;
};

EngineState       g_engine;
std::atomic<bool> g_stop_requested{false};

void clear_batch(llama_batch & batch) {
    batch.n_tokens = 0;
}

void add_to_batch(
    llama_batch & batch,
    llama_token   id,
    llama_pos     pos,
    llama_seq_id  seq_id,
    bool          logits
) {
    const int32_t idx = batch.n_tokens;
    batch.token[idx]     = id;
    batch.pos[idx]       = pos;
    batch.n_seq_id[idx]  = 1;
    batch.seq_id[idx][0] = seq_id;
    batch.logits[idx]    = logits ? 1 : 0;
    batch.n_tokens++;
}

// Formats the conversation using the GGUF embedded chat template via llama_chat_apply_template.
// Falls back to ChatML / plain-text format if the GGUF does not carry a recognized template.
std::string format_chat_prompt(
    const std::string & custom_template,
    const std::vector<std::string> & roles,
    const std::vector<std::string> & contents
) {
    std::vector<llama_chat_message> messages;
    messages.reserve(roles.size());
    for (size_t i = 0; i < roles.size() && i < contents.size(); ++i) {
        messages.push_back(llama_chat_message{
            roles[i].c_str(),
            contents[i].c_str()
        });
    }

    const char * tmpl_ptr = custom_template.empty() ? nullptr : custom_template.c_str();

    int32_t required_len = llama_chat_apply_template(
        tmpl_ptr,
        messages.data(),
        messages.size(),
        true,
        nullptr,
        0
    );

    if (required_len > 0) {
        std::vector<char> buffer(static_cast<size_t>(required_len) + 1, '\0');
        int32_t written = llama_chat_apply_template(
            tmpl_ptr,
            messages.data(),
            messages.size(),
            true,
            buffer.data(),
            static_cast<int32_t>(buffer.size())
        );
        if (written > 0 && written <= static_cast<int32_t>(buffer.size())) {
            return std::string(buffer.data(), static_cast<size_t>(written));
        }
    }

    // ChatML fallback for SmolLM2-135M-Instruct Q4_K_M / Q8_0
    std::string fallback;
    fallback.reserve(512);
    for (size_t i = 0; i < messages.size(); ++i) {
        fallback += "<|im_start|>";
        fallback += messages[i].role;
        fallback += "\n";
        fallback += messages[i].content;
        fallback += "<|im_end|>\n";
    }
    fallback += "<|im_start|>assistant\n";
    return fallback;
}

// Sanitize partial UTF-8 bytes so NewStringUTF is always valid Modified UTF-8
std::string sanitize_utf8(const std::string & input) {
    std::string out;
    out.reserve(input.size());
    const auto * bytes = reinterpret_cast<const unsigned char *>(input.data());
    const size_t len = input.size();
    size_t i = 0;
    while (i < len) {
        unsigned char c = bytes[i];
        if (c >= 0x01 && c <= 0x7F) {
            out.push_back(static_cast<char>(c));
            i += 1;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < len && (bytes[i + 1] & 0xC0) == 0x80) {
            out.append(reinterpret_cast<const char *>(bytes + i), 2);
            i += 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < len &&
                   (bytes[i + 1] & 0xC0) == 0x80 &&
                   (bytes[i + 2] & 0xC0) == 0x80) {
            out.append(reinterpret_cast<const char *>(bytes + i), 3);
            i += 3;
        } else if ((c & 0xF8) == 0xF0 && i + 3 < len &&
                   (bytes[i + 1] & 0xC0) == 0x80 &&
                   (bytes[i + 2] & 0xC0) == 0x80 &&
                   (bytes[i + 3] & 0xC0) == 0x80) {
            out.append("?");
            i += 4;
        } else {
            i += 1;
        }
    }
    return out;
}

void free_engine_locked() {
    if (g_engine.ctx != nullptr) {
        llama_free(g_engine.ctx);
        g_engine.ctx = nullptr;
    }
    if (g_engine.model != nullptr) {
        llama_model_free(g_engine.model);
        g_engine.model = nullptr;
    }
    g_engine.loaded_model_path.clear();
    g_engine.chat_template.clear();
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_llama_LlamaBridge_nativeInitBackend(
    JNIEnv * /*env*/,
    jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(g_engine.mutex);
    if (!g_engine.backend_initialized) {
        llama_backend_init();
        g_engine.backend_initialized = true;
        LOGI("llama_backend_init() completed (commit 785322d17f2d51e5185cf996a1f4e46d87640841)");
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_llama_LlamaBridge_nativeLoadModel(
    JNIEnv * env,
    jobject /*thiz*/,
    jstring jModelPath
) {
    if (jModelPath == nullptr) {
        return -1;
    }

    const char * raw_path = env->GetStringUTFChars(jModelPath, nullptr);
    if (raw_path == nullptr) {
        return -2;
    }
    std::string model_path(raw_path);
    env->ReleaseStringUTFChars(jModelPath, raw_path);

    std::lock_guard<std::mutex> lock(g_engine.mutex);

    if (!g_engine.backend_initialized) {
        llama_backend_init();
        g_engine.backend_initialized = true;
    }

    // Free any previously loaded model/context first
    free_engine_locked();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;                     // CPU only on low-end ARMv7
    mparams.load_mode    = LLAMA_LOAD_MODE_MMAP;  // mmap on, no mlock

    LOGI("Loading GGUF model from %s (load_mode=LLAMA_LOAD_MODE_MMAP)", model_path.c_str());
    llama_model * model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("llama_model_load_from_file failed for %s", model_path.c_str());
        return -3;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = kFixedContextSize; // 256
    cparams.n_batch         = kFixedBatchSize;   // 64
    cparams.n_ubatch        = kFixedBatchSize;   // 64
    cparams.n_threads       = kFixedThreads;     // 2
    cparams.n_threads_batch = kFixedThreads;     // 2

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model failed (n_ctx=%d, n_batch=%d)", kFixedContextSize, kFixedBatchSize);
        llama_model_free(model);
        return -4;
    }

    // Read embedded chat template from GGUF metadata if present
    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl != nullptr && std::strlen(tmpl) > 0) {
        g_engine.chat_template.assign(tmpl);
        LOGI("Loaded GGUF chat template (%zu bytes)", g_engine.chat_template.size());
    } else {
        g_engine.chat_template.clear();
        LOGI("No embedded chat template in GGUF; using ChatML fallback");
    }

    g_engine.model = model;
    g_engine.ctx = ctx;
    g_engine.loaded_model_path = model_path;

    LOGI("Model and context initialized successfully (n_ctx=%u)", llama_n_ctx(ctx));
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_llama_LlamaBridge_nativeGetModelDescription(
    JNIEnv * env,
    jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(g_engine.mutex);
    if (g_engine.model == nullptr) {
        return env->NewStringUTF("No model loaded");
    }
    char desc[256];
    std::memset(desc, 0, sizeof(desc));
    llama_model_desc(g_engine.model, desc, sizeof(desc) - 1);
    std::string safe = sanitize_utf8(desc);
    return env->NewStringUTF(safe.c_str());
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_llama_LlamaBridge_nativeGenerateStream(
    JNIEnv * env,
    jobject /*thiz*/,
    jobjectArray jRoles,
    jobjectArray jContents,
    jobject jCallback
) {
    g_stop_requested.store(false);

    std::lock_guard<std::mutex> lock(g_engine.mutex);
    if (g_engine.model == nullptr || g_engine.ctx == nullptr) {
        LOGE("nativeGenerateStream called with no loaded model/context");
        return -1.0f;
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_engine.model);
    if (vocab == nullptr) {
        LOGE("llama_model_get_vocab returned nullptr");
        return -1.0f;
    }

    jclass callbackClass = env->GetObjectClass(jCallback);
    if (callbackClass == nullptr) {
        return -2.0f;
    }
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    if (onTokenMethod == nullptr) {
        return -3.0f;
    }

    const jsize n_msgs = (jRoles != nullptr && jContents != nullptr)
        ? std::min(env->GetArrayLength(jRoles), env->GetArrayLength(jContents))
        : 0;

    std::vector<std::string> roles;
    std::vector<std::string> contents;
    roles.reserve(static_cast<size_t>(n_msgs));
    contents.reserve(static_cast<size_t>(n_msgs));

    for (jsize i = 0; i < n_msgs; ++i) {
        auto jRole = static_cast<jstring>(env->GetObjectArrayElement(jRoles, i));
        auto jContent = static_cast<jstring>(env->GetObjectArrayElement(jContents, i));
        const char * cRole = jRole ? env->GetStringUTFChars(jRole, nullptr) : "user";
        const char * cContent = jContent ? env->GetStringUTFChars(jContent, nullptr) : "";
        roles.emplace_back(cRole ? cRole : "user");
        contents.emplace_back(cContent ? cContent : "");
        if (jRole && cRole) env->ReleaseStringUTFChars(jRole, cRole);
        if (jContent && cContent) env->ReleaseStringUTFChars(jContent, cContent);
        if (jRole) env->DeleteLocalRef(jRole);
        if (jContent) env->DeleteLocalRef(jContent);
    }

    const std::string formatted_prompt = format_chat_prompt(
        g_engine.chat_template,
        roles,
        contents
    );

    // Clear KV cache so full 256-token context window is available for this turn
    llama_memory_t mem = llama_get_memory(g_engine.ctx);
    if (mem != nullptr) {
        llama_memory_clear(mem, true);
    }

    // Tokenize formatted prompt
    int32_t max_prompt_tokens = kFixedContextSize - 32; // Reserve space for generation within 256 ctx
    if (max_prompt_tokens < 32) {
        max_prompt_tokens = 128;
    }

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(formatted_prompt.size() + 16));
    int32_t n_prompt = llama_tokenize(
        vocab,
        formatted_prompt.c_str(),
        static_cast<int32_t>(formatted_prompt.size()),
        prompt_tokens.data(),
        static_cast<int32_t>(prompt_tokens.size()),
        true,
        true
    );

    if (n_prompt < 0) {
        prompt_tokens.resize(static_cast<size_t>(-n_prompt));
        n_prompt = llama_tokenize(
            vocab,
            formatted_prompt.c_str(),
            static_cast<int32_t>(formatted_prompt.size()),
            prompt_tokens.data(),
            static_cast<int32_t>(prompt_tokens.size()),
            true,
            true
        );
    }

    if (n_prompt <= 0) {
        LOGE("llama_tokenize failed with result %d", n_prompt);
        return -4.0f;
    }

    prompt_tokens.resize(static_cast<size_t>(n_prompt));

    if (static_cast<int32_t>(prompt_tokens.size()) > max_prompt_tokens) {
        const size_t keep = static_cast<size_t>(max_prompt_tokens);
        prompt_tokens.erase(prompt_tokens.begin(), prompt_tokens.end() - static_cast<std::ptrdiff_t>(keep));
    }

    // Build sampler chain with exact low-end parameters:
    // repeat_penalty = 1.1f, top_k = 40, top_p = 0.9f, temp = 0.7f
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);

    llama_sampler_chain_add(
        smpl,
        llama_sampler_init_penalties(
            llama_vocab_n_tokens(vocab),
            kPenaltyLastN,
            kFixedRepeatPenalty,
            0.0f,
            0.0f
        )
    );
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(kFixedTopK));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(kFixedTopP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(kFixedTemp));
    llama_sampler_chain_add(
        smpl,
        llama_sampler_init_dist(static_cast<uint32_t>(
            std::chrono::steady_clock::now().time_since_epoch().count()
        ))
    );

    for (llama_token tok : prompt_tokens) {
        llama_sampler_accept(smpl, tok);
    }

    // Evaluate prompt in batches of kFixedBatchSize (64)
    llama_batch batch = llama_batch_init(kFixedBatchSize, 0, 1);
    int32_t n_past = 0;
    const int32_t total_prompt = static_cast<int32_t>(prompt_tokens.size());

    for (int32_t base = 0; base < total_prompt; base += kFixedBatchSize) {
        if (g_stop_requested.load()) {
            llama_batch_free(batch);
            llama_sampler_free(smpl);
            return 0.0f;
        }

        clear_batch(batch);
        const int32_t chunk = std::min(kFixedBatchSize, total_prompt - base);
        for (int32_t i = 0; i < chunk; ++i) {
            const bool is_last = (base + i == total_prompt - 1);
            add_to_batch(batch, prompt_tokens[static_cast<size_t>(base + i)], n_past + i, 0, is_last);
        }

        if (llama_decode(g_engine.ctx, batch) != 0) {
            LOGE("llama_decode failed during prompt evaluation at pos %d", n_past);
            llama_batch_free(batch);
            llama_sampler_free(smpl);
            return -5.0f;
        }
        n_past += chunk;
    }

    // Token generation loop (up to kFixedMaxNewTokens = 128 or until context limit 256)
    const auto gen_start = std::chrono::steady_clock::now();
    int32_t generated_tokens = 0;
    std::string pending_utf8;

    while (generated_tokens < kFixedMaxNewTokens && n_past < kFixedContextSize) {
        if (g_stop_requested.load()) {
            LOGI("Generation stopped by user at token %d", generated_tokens);
            break;
        }

        // llama_sampler_sample samples and accepts the token
        const llama_token new_token_id = llama_sampler_sample(smpl, g_engine.ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }

        char piece_buf[128];
        const int32_t piece_len = llama_token_to_piece(
            vocab,
            new_token_id,
            piece_buf,
            sizeof(piece_buf),
            0,
            false
        );

        if (piece_len > 0) {
            pending_utf8.append(piece_buf, static_cast<size_t>(piece_len));

            if (pending_utf8.find("<|im_end|>") != std::string::npos ||
                pending_utf8.find("<|endoftext|>") != std::string::npos) {
                break;
            }

            std::string clean_piece = sanitize_utf8(pending_utf8);
            if (!clean_piece.empty()) {
                jstring jPiece = env->NewStringUTF(clean_piece.c_str());
                if (jPiece != nullptr) {
                    env->CallVoidMethod(jCallback, onTokenMethod, jPiece);
                    env->DeleteLocalRef(jPiece);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        break;
                    }
                }
                pending_utf8.clear();
            }
        }

        generated_tokens++;

        clear_batch(batch);
        add_to_batch(batch, new_token_id, n_past, 0, true);
        n_past++;

        if (llama_decode(g_engine.ctx, batch) != 0) {
            LOGW("llama_decode stopped during token generation at pos %d", n_past);
            break;
        }
    }

    const auto gen_end = std::chrono::steady_clock::now();
    const double elapsed_sec = std::chrono::duration<double>(gen_end - gen_start).count();

    llama_batch_free(batch);
    llama_sampler_free(smpl);

    if (generated_tokens <= 0 || elapsed_sec <= 0.0001) {
        return 0.0f;
    }
    return static_cast<jfloat>(static_cast<double>(generated_tokens) / elapsed_sec);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llama_LlamaBridge_nativeStopGeneration(
    JNIEnv * /*env*/,
    jobject /*thiz*/
) {
    g_stop_requested.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llama_LlamaBridge_nativeFreeModel(
    JNIEnv * /*env*/,
    jobject /*thiz*/
) {
    g_stop_requested.store(true);
    std::lock_guard<std::mutex> lock(g_engine.mutex);
    free_engine_locked();
    if (g_engine.backend_initialized) {
        llama_backend_free();
        g_engine.backend_initialized = false;
    }
    LOGI("nativeFreeModel: freed model, context, and llama backend");
}
