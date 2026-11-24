// JNI side of LocalTranslator: loads a GGUF with llama.cpp and generates greedily from a prompt, streaming the text.
// Strings cross as UTF-8 byte arrays, because JNI's string functions use modified UTF-8, which mangles characters
// outside the BMP (𠮟, emoji).
#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {

struct Session {
    llama_model * model;
    llama_context * ctx;
    llama_sampler * sampler;
    llama_batch_ext * batch;
};

void log_warnings(ggml_log_level level, const char * text, void *) {
    // Not >= WARN: CONT (the loading progress dots) is numbered above ERROR.
    if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR) {
        __android_log_write(ANDROID_LOG_WARN, "OCRClipLLM", text);
    }
}

void init_backend() {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(log_warnings, nullptr);
        llama_backend_init();
    });
}

std::string chars(JNIEnv * env, jstring s) {
    const char * utf = env->GetStringUTFChars(s, nullptr);
    std::string out(utf);
    env->ReleaseStringUTFChars(s, utf);
    return out;
}

std::string bytes(JNIEnv * env, jbyteArray a) {
    std::string out(env->GetArrayLength(a), '\0');
    env->GetByteArrayRegion(a, 0, (jsize) out.size(), reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray to_java(JNIEnv * env, const std::string & s) {
    jbyteArray a = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(a, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return a;
}

// Length of the longest prefix of s that doesn't end inside a UTF-8 sequence (a token can be part of a character).
size_t complete_utf8(const std::string & s) {
    size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; back++) {
        auto c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue; // continuation byte
        size_t need = c < 0x80 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : 4;
        return need <= back ? n : n - back;
    }
    return n;
}

void fill(llama_batch_ext * batch, const llama_token * tokens, int32_t n, llama_pos pos0) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < n; i++) {
        int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        llama_pos pos = pos0 + i;
        llama_batch_ext_set_pos(batch, idx, &pos);
    }
    llama_batch_ext_set_output_logits(batch, n - 1, true);
}

}  // namespace

extern "C" {

// Reads only the vocabulary and metadata. Returns the model's chat template, or null if it can't be read as a GGUF.
JNIEXPORT jbyteArray JNICALL
Java_com_local_ocrclip_LocalTranslator_nativeTemplate(JNIEnv * env, jclass, jstring path) {
    init_backend();
    llama_model_params params = llama_model_default_params();
    params.vocab_only = true;
    llama_model * model = llama_model_load_from_file(chars(env, path).c_str(), params);
    if (!model) return nullptr;
    const char * tmpl = llama_model_chat_template(model, nullptr);
    jbyteArray out = to_java(env, tmpl ? tmpl : "");
    llama_model_free(model);
    return out;
}

JNIEXPORT jlong JNICALL
Java_com_local_ocrclip_LocalTranslator_nativeLoad(JNIEnv * env, jclass, jstring path, jint threads, jint context) {
    init_backend();
    llama_model * model = llama_model_load_from_file(chars(env, path).c_str(), llama_model_default_params());
    if (!model) return 0;
    llama_context_params params = llama_context_default_params();
    params.n_ctx = context;
    params.n_batch = context; // the whole prompt goes in one batch
    params.n_threads = threads;
    params.n_threads_batch = threads;
    params.no_perf = true;
    llama_context * ctx = llama_init_from_model(model, params);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }
    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    return reinterpret_cast<jlong>(new Session{model, ctx, sampler, llama_batch_ext_init(ctx)});
}

JNIEXPORT void JNICALL
Java_com_local_ocrclip_LocalTranslator_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    llama_batch_ext_free(s->batch);
    llama_sampler_free(s->sampler);
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

// Generates from the UTF-8 prompt until end of generation, the context is full or onText(byte[]) returns false.
// onText gets each newly completed piece of text. Returns an error message, or null.
JNIEXPORT jstring JNICALL
Java_com_local_ocrclip_LocalTranslator_nativeGenerate(JNIEnv * env, jclass, jlong handle, jbyteArray prompt_utf8,
                                                       jobject on_text) {
    auto * s = reinterpret_cast<Session *>(handle);
    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    const int n_ctx = (int) llama_n_ctx(s->ctx);
    const std::string prompt = bytes(env, prompt_utf8);

    std::vector<llama_token> tokens(prompt.size() + 8);
    int n_prompt = llama_tokenize(vocab, prompt.data(), (int32_t) prompt.size(), tokens.data(),
                                  (int32_t) tokens.size(), true, true);
    if (n_prompt <= 0) return env->NewStringUTF("couldn't read the text");
    if (n_prompt > n_ctx / 2) return env->NewStringUTF("the text is too long");

    jmethodID callback = env->GetMethodID(env->GetObjectClass(on_text), "onText", "([B)Z");
    llama_memory_clear(llama_get_memory(s->ctx), true);
    llama_sampler_reset(s->sampler);
    fill(s->batch, tokens.data(), n_prompt, 0);

    std::string pending;
    int n_pos = 0, n_batch = n_prompt;
    while (n_pos + n_batch < n_ctx) {
        if (llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, s->batch) != 0) {
            return env->NewStringUTF("the model failed");
        }
        n_pos += n_batch;
        llama_token token = llama_sampler_sample(s->sampler, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char piece[256];
        int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (n > 0) pending.append(piece, n);
        size_t ready = complete_utf8(pending);
        if (ready > 0) {
            jbyteArray text = to_java(env, pending.substr(0, ready));
            jboolean go_on = env->CallBooleanMethod(on_text, callback, text);
            env->DeleteLocalRef(text);
            pending.erase(0, ready);
            if (env->ExceptionCheck() || !go_on) break;
        }
        fill(s->batch, &token, 1, n_pos);
        n_batch = 1;
    }
    return nullptr;
}

}  // extern "C"
