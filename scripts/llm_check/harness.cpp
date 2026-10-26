// Vérifie sur PC le décodage spéculatif de llm/src/main/cpp/llama_jni.cpp : même texte que le
// décodage glouton classique, et gain de vitesse. Appelle les fonctions JNI telles quelles,
// avec un JNIEnv minimal. Usage : harness modele.gguf system.txt entrees.txt [threads]
#include <jni.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "llm/src/main/cpp/llama_jni.cpp"

namespace {

// Chaînes et tableaux Java représentés par des objets C++ (jamais libérés : programme court).
const char *GetStringUTFChars(JNIEnv *, jstring s, jboolean *) { return reinterpret_cast<std::string *>(s)->c_str(); }
void ReleaseStringUTFChars(JNIEnv *, jstring, const char *) {}
jstring NewStringUTF(JNIEnv *, const char *s) { return reinterpret_cast<jstring>(new std::string(s)); }
jbyteArray NewByteArray(JNIEnv *, jsize n) { return reinterpret_cast<jbyteArray>(new std::string((size_t) n, '\0')); }
void SetByteArrayRegion(JNIEnv *, jbyteArray a, jsize start, jsize n, const jbyte *buf) {
    reinterpret_cast<std::string *>(a)->replace((size_t) start, (size_t) n, reinterpret_cast<const char *>(buf), (size_t) n);
}
jdoubleArray NewDoubleArray(JNIEnv *, jsize n) { return reinterpret_cast<jdoubleArray>(new std::vector<double>((size_t) n)); }
void SetDoubleArrayRegion(JNIEnv *, jdoubleArray a, jsize start, jsize n, const jdouble *buf) {
    std::copy(buf, buf + n, reinterpret_cast<std::vector<double> *>(a)->begin() + start);
}

jstring js(const std::string &s) { return reinterpret_cast<jstring>(new std::string(s)); }

std::string read_file(const char *path) {
    std::ifstream f(path);
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

struct Run {
    std::string text;
    std::vector<double> stats;
};

} // namespace

Run complete(JNIEnv *env, jlong ptr, const std::string &system, const std::string &user, int draft) {
    // Repart du seul prompt système en cache : chaque variante démarre du même état.
    JNI_FN(nWarm)(env, nullptr, ptr, js(system));
    Run r;
    jbyteArray out = JNI_FN(nComplete)(env, nullptr, ptr, js(system), js(user), 1.5f, 16, draft);
    r.text = out ? *reinterpret_cast<std::string *>(out) : std::string("<échec>");
    r.stats = *reinterpret_cast<std::vector<double> *>(JNI_FN(nLastStats)(env, nullptr, ptr));
    return r;
}

int main(int argc, char **argv) {
    if (argc < 5) {
        std::fprintf(stderr, "usage : %s modele.gguf entrees.txt threads system.txt [autres-prompts.txt…]\n", argv[0]);
        return 2;
    }
    JNINativeInterface_ fns{};
    fns.GetStringUTFChars = GetStringUTFChars;
    fns.ReleaseStringUTFChars = ReleaseStringUTFChars;
    fns.NewStringUTF = NewStringUTF;
    fns.NewByteArray = NewByteArray;
    fns.SetByteArrayRegion = SetByteArrayRegion;
    fns.NewDoubleArray = NewDoubleArray;
    fns.SetDoubleArrayRegion = SetDoubleArrayRegion;
    JNIEnv env_storage{};
    env_storage.functions = &fns;
    JNIEnv *env = &env_storage;

    const int threads = std::atoi(argv[3]);
    jlong ptr = JNI_FN(nLoad)(env, nullptr, js(argv[1]), 2048, threads, threads, 0);
    if (!ptr) return 1;

    std::vector<std::string> inputs;
    {
        std::ifstream in(argv[2]);
        for (std::string line; std::getline(in, line);) if (!line.empty()) inputs.push_back(line);
    }
    const std::string system = read_file(argv[4]);

    // 1. Décodage : glouton (deux fois, pour le bruit numérique) contre brouillon de 4 et 8 tokens.
    const int drafts[] = {4, 8};
    double ms_plain = 0, ms_draft[2] = {0, 0};
    int same[2] = {0, 0}, acc[2] = {0, 0}, prop[2] = {0, 0}, stable = 0;
    std::printf("## Décodage spéculatif (%d threads)\n\n", threads);
    std::printf("| Entrée | glouton ms | brouillon 4 ms | brouillon 8 ms | accepté (8) | identique 4 / 8 |\n|---|---|---|---|---|---|\n");
    std::string details;
    for (const auto &line : inputs) {
        Run plain = complete(env, ptr, system, line, 0);
        Run again = complete(env, ptr, system, line, 0);
        const bool deterministic = plain.text == again.text;
        if (deterministic) stable++;
        Run spec[2];
        for (int k = 0; k < 2; ++k) {
            spec[k] = complete(env, ptr, system, line, drafts[k]);
            ms_draft[k] += spec[k].stats[4];
            acc[k] += (int) spec[k].stats[6];
            prop[k] += (int) spec[k].stats[5];
            if (spec[k].text == plain.text) same[k]++;
        }
        ms_plain += plain.stats[4];
        std::printf("| %.40s… | %.0f | %.0f | %.0f | %d/%d | %s / %s%s |\n", line.c_str(), plain.stats[4],
                    spec[0].stats[4], spec[1].stats[4], (int) spec[1].stats[6], (int) spec[1].stats[5],
                    spec[0].text == plain.text ? "oui" : "**non**", spec[1].text == plain.text ? "oui" : "**non**",
                    deterministic ? "" : " (glouton instable)");
        details += "- " + line + "\n  - glouton : " + plain.text + "\n";
        for (int k = 0; k < 2; ++k)
            if (spec[k].text != plain.text) details += "  - brouillon " + std::to_string(drafts[k]) + " : " + spec[k].text + "\n";
    }
    for (int k = 0; k < 2; ++k) {
        std::printf("\nBrouillon %d : génération %.0f ms → %.0f ms (×%.2f), accepté %d/%d (%.0f %%), identique %d/%zu",
                    drafts[k], ms_plain, ms_draft[k], ms_draft[k] > 0 ? ms_plain / ms_draft[k] : 0.0, acc[k], prop[k],
                    prop[k] > 0 ? 100.0 * acc[k] / prop[k] : 0.0, same[k], inputs.size());
    }
    std::printf("\n\n%s", details.c_str());

    // 2. Autres prompts système (comparaison de qualité, glouton).
    for (int a = 5; a < argc; ++a) {
        const std::string alt = read_file(argv[a]);
        std::printf("\n## Prompt %s\n\n", argv[a]);
        for (const auto &line : inputs) {
            Run r = complete(env, ptr, alt, line, 0);
            std::printf("- %s\n  - %s\n", line.c_str(), r.text.c_str());
        }
    }
    JNI_FN(nFree)(env, nullptr, ptr);
    // Un écart isolé vient du bruit numérique (lots de tailles différentes) sur une quasi-égalité ;
    // une majorité d'écarts signale un cache KV mal tenu.
    return 2 * same[1] >= (int) inputs.size() ? 0 : 1;
}
