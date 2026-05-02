#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Global state ──────────────────────────────────────────────────────────────
static llama_model*   g_model   = nullptr;
static llama_context* g_ctx     = nullptr;
static llama_sampler* g_sampler = nullptr;
static mtmd_context*  g_mtmd    = nullptr;

// Streaming callback
static JavaVM*   g_jvm         = nullptr;
static jobject   g_callback    = nullptr;
static jmethodID g_onToken_mid = nullptr;

// ── Helpers ───────────────────────────────────────────────────────────────────
static std::string jstring2str(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

static void emit_token(const std::string& token) {
    if (!g_jvm || !g_callback || !g_onToken_mid) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    jstring jtoken = env->NewStringUTF(token.c_str());
    env->CallVoidMethod(g_callback, g_onToken_mid, jtoken);
    env->DeleteLocalRef(jtoken);
    if (attached) g_jvm->DetachCurrentThread();
}

static void free_sampler() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
}

static void init_sampler() {
    free_sampler();
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
}

// ── JNI_OnLoad ────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    llama_backend_init();
    LOGI("llama backend initialised");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    llama_backend_free();
}

// ── loadModel(modelPath, mmprojPath, nCtx, nThreads) ─────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_abdullahsolutions_kancil_LlamaEngine_loadModel(
        JNIEnv* env, jobject,
        jstring jmodelPath, jstring jmmprojPath, jint nCtx, jint nThreads)
{
    std::string modelPath  = jstring2str(env, jmodelPath);
    std::string mmprojPath = jstring2str(env, jmmprojPath);
    LOGI("Loading model:  %s", modelPath.c_str());
    LOGI("Loading mmproj: %s", mmprojPath.c_str());

    // Clean up any previously loaded model
    free_sampler();
    if (g_mtmd)  { mtmd_free(g_mtmd);        g_mtmd  = nullptr; }
    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    // Load text model
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (!g_model) { LOGE("Failed to load model"); return JNI_FALSE; }

    // Create llama context
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t)nCtx;
    cparams.n_threads       = (uint32_t)nThreads;
    cparams.n_threads_batch = (uint32_t)nThreads;
    cparams.n_batch         = 2048;
    cparams.n_ubatch        = 512;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model); g_model = nullptr;
        return JNI_FALSE;
    }

    // Load multimodal projector
    mtmd_context_params vparams = mtmd_context_params_default();
    vparams.use_gpu   = false;
    vparams.n_threads = (int)nThreads;
    vparams.warmup    = false;

    g_mtmd = mtmd_init_from_file(mmprojPath.c_str(), g_model, vparams);
    if (!g_mtmd) {
        LOGE("Failed to load mmproj");
        llama_free(g_ctx);         g_ctx   = nullptr;
        llama_model_free(g_model); g_model = nullptr;
        return JNI_FALSE;
    }

    init_sampler();
    LOGI("Model + mmproj loaded successfully");
    return JNI_TRUE;
}

// ── setTokenCallback ──────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_abdullahsolutions_kancil_LlamaEngine_setTokenCallback(
        JNIEnv* env, jobject, jobject callback)
{
    if (g_callback) env->DeleteGlobalRef(g_callback);
    g_callback = env->NewGlobalRef(callback);
    jclass cls = env->GetObjectClass(callback);
    g_onToken_mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
}

// ── generateWithImage(prompt, imageBytes_nullable, maxTokens) ─────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_abdullahsolutions_kancil_LlamaEngine_generateWithImage(
        JNIEnv* env, jobject,
        jstring jprompt, jbyteArray jimageBytes, jint maxTokens)
{
    if (!g_model || !g_ctx) return env->NewStringUTF("[Error: model not loaded]");

    std::string prompt = jstring2str(env, jprompt);
    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    // Reset KV cache and sampler for fresh generation
    llama_memory_t mem = llama_get_memory(g_ctx);
    if (mem) llama_memory_clear(mem, false);
    llama_sampler_reset(g_sampler);

    llama_pos n_past = 0;

    if (jimageBytes != nullptr && g_mtmd != nullptr) {
        // ── Multimodal path ───────────────────────────────────────────────────
        jsize    imgLen   = env->GetArrayLength(jimageBytes);
        jbyte*   imgBytes = env->GetByteArrayElements(jimageBytes, nullptr);

        mtmd_bitmap* bitmap = mtmd_helper_bitmap_init_from_buf(
            g_mtmd, reinterpret_cast<const unsigned char*>(imgBytes), (size_t)imgLen);

        env->ReleaseByteArrayElements(jimageBytes, imgBytes, JNI_ABORT);

        if (!bitmap) {
            LOGE("Failed to decode image bytes");
            return env->NewStringUTF("[Error: failed to decode image]");
        }

        mtmd_input_text  input_text = {prompt.c_str(), /*add_special=*/true, /*parse_special=*/true};
        mtmd_input_chunks* chunks   = mtmd_input_chunks_init();
        const mtmd_bitmap* bitmaps[] = {bitmap};

        int ret = mtmd_tokenize(g_mtmd, chunks, &input_text, bitmaps, 1);
        if (ret != 0) {
            LOGE("mtmd_tokenize failed: %d", ret);
            mtmd_input_chunks_free(chunks);
            mtmd_bitmap_free(bitmap);
            return env->NewStringUTF("[Error: tokenize failed]");
        }

        ret = mtmd_helper_eval_chunks(
            g_mtmd, g_ctx, chunks,
            /*n_past=*/0, /*seq_id=*/0, /*n_batch=*/2048,
            /*logits_last=*/true, &n_past);

        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);

        if (ret != 0) {
            LOGE("mtmd_helper_eval_chunks failed: %d", ret);
            return env->NewStringUTF("[Error: vision eval failed]");
        }
    } else {
        // ── Text-only path ────────────────────────────────────────────────────
        std::vector<llama_token> tokens(prompt.size() + 64);
        int n_tokens = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                                      tokens.data(), (int)tokens.size(), true, true);
        if (n_tokens < 0) {
            tokens.resize(-n_tokens);
            n_tokens = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                                      tokens.data(), (int)tokens.size(), true, true);
        }
        tokens.resize(n_tokens);

        llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)n_tokens);
        if (llama_decode(g_ctx, batch) != 0) {
            return env->NewStringUTF("[Error: decode failed]");
        }
        n_past = (llama_pos)n_tokens;
    }

    // ── Token generation loop ─────────────────────────────────────────────────
    std::string result;

    for (int i = 0; i < maxTokens; ++i) {
        llama_token new_token = llama_sampler_sample(g_sampler, g_ctx, -1);
        // llama_vocab_is_eog catches EOS *and* model-specific stop tokens
        // (e.g. Gemma's <end_of_turn>) so we don't bleed control tokens into output
        if (llama_vocab_is_eog(vocab, new_token)) break;

        char buf[256] = {};
        int len = llama_token_to_piece(vocab, new_token, buf, sizeof(buf) - 1, 0, true);
        if (len > 0) {
            std::string piece(buf, len);
            // Belt-and-suspenders: also stop on the literal stop-token strings for
            // models (e.g. Gemma) whose stop IDs aren't caught by llama_vocab_is_eog.
            if (piece == "<end_of_turn>" || piece == "<eos>" ||
                piece == "<|end|>"       || piece == "<|endoftext|>") break;
            result += piece;
            emit_token(piece);
        }

        llama_batch next = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_ctx, next) != 0) break;
        n_past++;
    }

    return env->NewStringUTF(result.c_str());
}

// ── freeModel ─────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_abdullahsolutions_kancil_LlamaEngine_freeModel(JNIEnv*, jobject)
{
    free_sampler();
    if (g_mtmd)  { mtmd_free(g_mtmd);        g_mtmd  = nullptr; }
    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    LOGI("Model freed");
}
