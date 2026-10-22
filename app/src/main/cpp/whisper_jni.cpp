#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>

#include "whisper.h"

#define TAG "MurmureWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Ctx {
    whisper_context *wctx = nullptr;
    std::atomic<bool> abort{false};
    float last_encode_ms = 0.f;
    float last_decode_ms = 0.f;
};

void log_cb(ggml_log_level level, const char *text, void *) {
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
             : ANDROID_LOG_DEBUG;
    __android_log_write(prio, TAG, text);
}

bool abort_cb(void *user) {
    return static_cast<Ctx *>(user)->abort.load(std::memory_order_relaxed);
}

std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_karelisio_murmure_stt_WhisperNative_init(JNIEnv *env, jobject, jstring jpath, jboolean flash_attn) {
    whisper_log_set(log_cb, nullptr);
    std::string path = jstr(env, jpath);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = flash_attn;
    whisper_context *wctx = whisper_init_from_file_with_params(path.c_str(), cp);
    if (wctx == nullptr) {
        LOGW("échec du chargement de %s", path.c_str());
        return 0;
    }
    auto *ctx = new Ctx();
    ctx->wctx = wctx;
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_karelisio_murmure_stt_WhisperNative_free(JNIEnv *, jobject, jlong ptr) {
    auto *ctx = reinterpret_cast<Ctx *>(ptr);
    if (ctx == nullptr) return;
    whisper_free(ctx->wctx);
    delete ctx;
}

JNIEXPORT void JNICALL
Java_com_karelisio_murmure_stt_WhisperNative_abort(JNIEnv *, jobject, jlong ptr) {
    auto *ctx = reinterpret_cast<Ctx *>(ptr);
    if (ctx != nullptr) ctx->abort.store(true);
}

// Retourne l'UTF-8 brut : NewStringUTF n'accepte que le « modified UTF-8 » et peut
// planter sur une séquence invalide ou un emoji.
JNIEXPORT jbyteArray JNICALL
Java_com_karelisio_murmure_stt_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong ptr, jfloatArray jsamples, jstring jlang, jint n_threads,
        jstring jprompt, jint audio_ctx, jint beam_size) {
    auto *ctx = reinterpret_cast<Ctx *>(ptr);
    if (ctx == nullptr) return nullptr;
    ctx->abort.store(false);

    std::string lang = jstr(env, jlang);
    std::string prompt = jstr(env, jprompt);

    whisper_full_params p = whisper_full_default_params(
            beam_size > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.n_threads = n_threads;
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.no_timestamps = true;
    p.no_context = true;
    p.single_segment = false;
    p.suppress_blank = true;
    p.suppress_nst = true;
    p.language = lang.empty() || lang == "auto" ? "auto" : lang.c_str();
    p.detect_language = false;
    p.translate = false;
    p.audio_ctx = audio_ctx;
    p.greedy.best_of = 1;
    p.beam_search.beam_size = beam_size > 1 ? beam_size : 1;
    // Pas de repli en température : il peut multiplier le temps de décodage par 5.
    p.temperature_inc = 0.0f;
    p.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = ctx;

    jsize n = env->GetArrayLength(jsamples);
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);
    whisper_reset_timings(ctx->wctx);
    int rc = whisper_full(ctx->wctx, p, samples, n);
    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);

    if (whisper_timings *t = whisper_get_timings(ctx->wctx)) {
        ctx->last_encode_ms = t->encode_ms;
        ctx->last_decode_ms = t->decode_ms;
        delete t;
    }
    if (rc != 0) {
        LOGW("whisper_full a échoué (%d)", rc);
        return nullptr;
    }

    std::string text;
    const int n_seg = whisper_full_n_segments(ctx->wctx);
    for (int i = 0; i < n_seg; ++i) text += whisper_full_get_segment_text(ctx->wctx, i);
    jbyteArray out = env->NewByteArray((jsize) text.size());
    env->SetByteArrayRegion(out, 0, (jsize) text.size(), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

JNIEXPORT jfloatArray JNICALL
Java_com_karelisio_murmure_stt_WhisperNative_lastTimings(JNIEnv *env, jobject, jlong ptr) {
    auto *ctx = reinterpret_cast<Ctx *>(ptr);
    jfloatArray out = env->NewFloatArray(2);
    if (ctx != nullptr) {
        jfloat v[2] = {ctx->last_encode_ms, ctx->last_decode_ms};
        env->SetFloatArrayRegion(out, 0, 2, v);
    }
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_karelisio_murmure_stt_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
