#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <string>
#include <vector>

#include "llama.h"

// Le même source sert à deux bibliothèques : CPU (LlamaNative) et GPU OpenCL (LlamaNativeGpu).
#ifndef JNI_CLASS
#define JNI_CLASS com_karelisio_speech_llm_LlamaNative
#endif
#define JNI_CAT2(a, b) a##_##b
#define JNI_CAT(a, b) JNI_CAT2(a, b)
#define JNI_FN(name) JNI_CAT(JNI_CAT(Java, JNI_CLASS), name)

#define TAG "SpeechLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

using Clock = std::chrono::steady_clock;

double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

struct Llm {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_sampler *sampler = nullptr;
    std::string tmpl;
    // Tokens actuellement dans le cache KV (séquence 0) : préfixe réutilisable d'un appel à l'autre.
    std::vector<llama_token> cached;
    std::atomic<bool> abort{false};
    // Statistiques du dernier appel.
    int reused = 0, prefilled = 0, generated = 0;
    double prefill_ms = 0, gen_ms = 0;
};

void log_cb(ggml_log_level level, const char *text, void *) {
    if (level < GGML_LOG_LEVEL_WARN) return;
    __android_log_write(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, text);
}

bool abort_cb(void *data) { return static_cast<Llm *>(data)->abort.load(std::memory_order_relaxed); }

std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::vector<llama_token> tokenize(const Llm *l, const std::string &text, bool special) {
    int n = -llama_tokenize(l->vocab, text.c_str(), (int) text.size(), nullptr, 0, false, special);
    std::vector<llama_token> out(std::max(n, 0));
    if (n > 0) llama_tokenize(l->vocab, text.c_str(), (int) text.size(), out.data(), n, false, special);
    return out;
}

std::string format(const Llm *l, const std::string &system, const std::string *user) {
    std::vector<llama_chat_message> msgs = {{"system", system.c_str()}};
    if (user) msgs.push_back({"user", user->c_str()});
    const char *t = l->tmpl.empty() ? "chatml" : l->tmpl.c_str();
    std::vector<char> buf(4096 + 2 * (system.size() + (user ? user->size() : 0)));
    int n = llama_chat_apply_template(t, msgs.data(), msgs.size(), user != nullptr, buf.data(), (int) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(t, msgs.data(), msgs.size(), user != nullptr, buf.data(), (int) buf.size());
    }
    return n > 0 ? std::string(buf.data(), n) : std::string();
}

/** Évalue `tokens` en réutilisant le plus long préfixe déjà présent dans le cache KV. */
bool prefill(Llm *l, const std::vector<llama_token> &tokens) {
    size_t lcp = 0;
    while (lcp < l->cached.size() && lcp < tokens.size() && l->cached[lcp] == tokens[lcp]) lcp++;
    // Il faut évaluer au moins un token pour obtenir des logits.
    if (lcp == tokens.size() && lcp > 0) lcp--;
    llama_memory_seq_rm(llama_get_memory(l->ctx), 0, (llama_pos) lcp, -1);
    l->cached.resize(lcp);
    l->reused = (int) lcp;
    l->prefilled = (int) (tokens.size() - lcp);

    const int n_batch = (int) llama_n_batch(l->ctx);
    for (size_t i = lcp; i < tokens.size(); i += n_batch) {
        int n = std::min<int>(n_batch, (int) (tokens.size() - i));
        llama_batch b = llama_batch_get_one(const_cast<llama_token *>(tokens.data() + i), n);
        if (llama_decode(l->ctx, b) != 0) {
            LOGW("échec de l'évaluation du prompt");
            l->cached.clear();
            llama_memory_clear(llama_get_memory(l->ctx), true);
            return false;
        }
        l->cached.insert(l->cached.end(), tokens.begin() + i, tokens.begin() + i + n);
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
JNI_FN(nLoad)(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint threads, jint threads_batch, jint gpu_layers) {
    llama_log_set(log_cb, nullptr);
    llama_backend_init();
    std::string path = jstr(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = gpu_layers;
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        LOGW("chargement impossible : %s", path.c_str());
        return 0;
    }
    auto *l = new Llm();
    l->model = model;
    l->vocab = llama_model_get_vocab(model);
    if (const char *t = llama_model_chat_template(model, nullptr)) l->tmpl = t;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        LOGI("périphérique ggml : %s (%s)", ggml_backend_dev_name(d), ggml_backend_dev_description(d));
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads_batch;
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = l;
    l->ctx = llama_init_from_model(model, cp);
    if (!l->ctx) {
        llama_model_free(model);
        delete l;
        return 0;
    }
    // Glouton : déterministe et le plus rapide (« température basse »).
    l->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(l->sampler, llama_sampler_init_greedy());
    return reinterpret_cast<jlong>(l);
}

JNIEXPORT void JNICALL
JNI_FN(nFree)(JNIEnv *, jobject, jlong ptr) {
    auto *l = reinterpret_cast<Llm *>(ptr);
    if (!l) return;
    llama_sampler_free(l->sampler);
    llama_free(l->ctx);
    llama_model_free(l->model);
    delete l;
}

JNIEXPORT void JNICALL
JNI_FN(nAbort)(JNIEnv *, jobject, jlong ptr) {
    if (auto *l = reinterpret_cast<Llm *>(ptr)) l->abort.store(true);
}

/** Évalue le prompt système seul et le garde en cache : les dictées suivantes ne le recalculent pas. */
JNIEXPORT jboolean JNICALL
JNI_FN(nWarm)(JNIEnv *env, jobject, jlong ptr, jstring jsystem) {
    auto *l = reinterpret_cast<Llm *>(ptr);
    if (!l) return JNI_FALSE;
    l->abort.store(false);
    std::string prefix = format(l, jstr(env, jsystem), nullptr);
    auto t0 = Clock::now();
    bool ok = prefill(l, tokenize(l, prefix, true));
    l->prefill_ms = ms_since(t0);
    l->gen_ms = 0;
    l->generated = 0;
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * Réponse de l'assistant à (système, utilisateur), génération gloutonne limitée à
 * `max_ratio` × tokens d'entrée + `extra`. Renvoie l'UTF-8 brut (null en cas d'échec).
 */
JNIEXPORT jbyteArray JNICALL
JNI_FN(nComplete)(
        JNIEnv *env, jobject, jlong ptr, jstring jsystem, jstring juser, jfloat max_ratio, jint extra) {
    auto *l = reinterpret_cast<Llm *>(ptr);
    if (!l) return nullptr;
    l->abort.store(false);
    std::string user = jstr(env, juser);
    std::string prompt = format(l, jstr(env, jsystem), &user);
    if (prompt.empty()) return nullptr;
    std::vector<llama_token> tokens = tokenize(l, prompt, true);
    const int user_tokens = (int) tokenize(l, user, false).size();
    int max_new = (int) (user_tokens * max_ratio) + extra;
    const int n_ctx = (int) llama_n_ctx(l->ctx);
    if ((int) tokens.size() + max_new > n_ctx) max_new = n_ctx - (int) tokens.size();
    if (max_new <= 0) return nullptr;

    auto t0 = Clock::now();
    if (!prefill(l, tokens)) return nullptr;
    l->prefill_ms = ms_since(t0);

    auto t1 = Clock::now();
    llama_sampler_reset(l->sampler);
    std::string out;
    char piece[256];
    int g = 0;
    for (; g < max_new; ++g) {
        llama_token tok = llama_sampler_sample(l->sampler, l->ctx, -1);
        if (llama_vocab_is_eog(l->vocab, tok)) break;
        int n = llama_token_to_piece(l->vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) out.append(piece, n);
        llama_batch b = llama_batch_get_one(&tok, 1);
        if (llama_decode(l->ctx, b) != 0) break;
        l->cached.push_back(tok);
    }
    l->generated = g;
    l->gen_ms = ms_since(t1);

    jbyteArray arr = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(arr, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}

/**
 * Évalue (système + début de la dictée) sans générer : appelé pendant l'enregistrement, il
 * remplit le cache KV pour que l'appel final ne traite plus que la fin du texte.
 */
JNIEXPORT jboolean JNICALL
JNI_FN(nPrefill)(JNIEnv *env, jobject, jlong ptr, jstring jsystem, jstring juser) {
    auto *l = reinterpret_cast<Llm *>(ptr);
    if (!l) return JNI_FALSE;
    l->abort.store(false);
    std::string user = jstr(env, juser);
    std::string prompt = format(l, jstr(env, jsystem), &user);
    if (prompt.empty()) return JNI_FALSE;
    std::vector<llama_token> tokens = tokenize(l, prompt, true);
    if ((int) tokens.size() >= (int) llama_n_ctx(l->ctx) / 2) return JNI_FALSE;
    auto t0 = Clock::now();
    bool ok = prefill(l, tokens);
    l->prefill_ms = ms_since(t0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** [tokens réutilisés, tokens évalués, tokens générés, ms prompt, ms génération] du dernier appel. */
JNIEXPORT jdoubleArray JNICALL
JNI_FN(nLastStats)(JNIEnv *env, jobject, jlong ptr) {
    auto *l = reinterpret_cast<Llm *>(ptr);
    jdoubleArray arr = env->NewDoubleArray(5);
    if (l) {
        jdouble v[5] = {(double) l->reused, (double) l->prefilled, (double) l->generated, l->prefill_ms, l->gen_ms};
        env->SetDoubleArrayRegion(arr, 0, 5, v);
    }
    return arr;
}

JNIEXPORT jstring JNICALL
JNI_FN(nSystemInfo)(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

} // extern "C"
