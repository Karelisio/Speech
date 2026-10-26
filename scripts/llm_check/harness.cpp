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

int main(int argc, char **argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage : %s modele.gguf system.txt entrees.txt [threads]\n", argv[0]);
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

    const std::string system = read_file(argv[2]);
    const int threads = argc > 4 ? std::atoi(argv[4]) : 4;
    jlong ptr = JNI_FN(nLoad)(env, nullptr, js(argv[1]), 2048, threads, threads, 0);
    if (!ptr) return 1;
    JNI_FN(nWarm)(env, nullptr, ptr, js(system));

    std::ifstream in(argv[3]);
    std::string line;
    int mismatches = 0, drafted = 0, accepted = 0, tokens = 0;
    double ms_plain = 0, ms_draft = 0;
    std::printf("| Entrée | glouton ms | spéculatif ms | brouillon | identique |\n|---|---|---|---|---|\n");
    std::string details;
    while (std::getline(in, line)) {
        if (line.empty()) continue;
        Run runs[2];
        const int drafts[2] = {0, 8};
        for (int k = 0; k < 2; ++k) {
            jbyteArray out = JNI_FN(nComplete)(env, nullptr, ptr, js(system), js(line), 1.5f, 16, drafts[k]);
            runs[k].text = out ? *reinterpret_cast<std::string *>(out) : std::string("<échec>");
            runs[k].stats = *reinterpret_cast<std::vector<double> *>(JNI_FN(nLastStats)(env, nullptr, ptr));
        }
        const bool same = runs[0].text == runs[1].text;
        if (!same) mismatches++;
        ms_plain += runs[0].stats[4];
        ms_draft += runs[1].stats[4];
        tokens += (int) runs[1].stats[2];
        drafted += (int) runs[1].stats[5];
        accepted += (int) runs[1].stats[6];
        std::printf("| %.40s… | %.0f | %.0f | %d/%d | %s |\n", line.c_str(), runs[0].stats[4], runs[1].stats[4],
                    (int) runs[1].stats[6], (int) runs[1].stats[5], same ? "oui" : "**NON**");
        details += "- " + line + "\n  - glouton : " + runs[0].text + "\n";
        if (!same) details += "  - spéculatif : " + runs[1].text + "\n";
    }
    std::printf("\nGénération : %.0f ms → %.0f ms (×%.2f), %d tokens, brouillon accepté %d/%d (%.0f %%)\n\n%s",
                ms_plain, ms_draft, ms_draft > 0 ? ms_plain / ms_draft : 0.0, tokens, accepted, drafted,
                drafted > 0 ? 100.0 * accepted / drafted : 0.0, details.c_str());
    JNI_FN(nFree)(env, nullptr, ptr);
    return mismatches == 0 ? 0 : 1;
}
