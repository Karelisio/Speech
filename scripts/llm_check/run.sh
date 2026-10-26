#!/usr/bin/env bash
# Décodage spéculatif du nettoyage (llm/src/main/cpp/llama_jni.cpp), vérifié sur PC : même texte
# qu'en glouton classique, et gain de vitesse. Usage : scripts/llm_check/run.sh modele.gguf
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${OUT:-$ROOT/build/llm_check}
MODEL=$1
JAVA_INC=${JAVA_HOME:?JAVA_HOME requis pour jni.h}/include
mkdir -p "$OUT"

cmake -S "$ROOT/third_party/llama.cpp" -B "$OUT/llama" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
    -DGGML_OPENMP=OFF -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_SERVER=OFF > "$OUT/cmake.log"
cmake --build "$OUT/llama" --target llama -j"$(nproc)" > "$OUT/build.log"
libs=()
for l in llama ggml ggml-cpu ggml-base; do libs+=("$(find "$OUT/llama/" -name "lib$l.a" | head -1)"); done
g++ -std=c++17 -O2 -I"$ROOT" -I"$ROOT/scripts/llm_check/stub" -I"$JAVA_INC" -I"$JAVA_INC/linux" \
    -I"$ROOT/third_party/llama.cpp/include" -I"$ROOT/third_party/llama.cpp/ggml/include" \
    "$ROOT/scripts/llm_check/harness.cpp" -o "$OUT/harness" -Wl,--start-group "${libs[@]}" -Wl,--end-group -lpthread -ldl

# Même prompt système que l'app.
python3 - "$ROOT/app/src/main/java/com/karelisio/speech/clean/CleanPrompt.kt" > "$OUT/system.txt" <<'PY'
import sys
s = open(sys.argv[1], encoding="utf-8").read()
print(s.split('val SYSTEM = """', 1)[1].split('""".trim()', 1)[0].strip(), end="")
PY
# Prompts à comparer en plus (fichiers .txt de scripts/llm_check/prompts/, s'il y en a).
extra=()
for f in "$ROOT"/scripts/llm_check/prompts/*.txt; do [ -f "$f" ] && extra+=("$f"); done
"$OUT/harness" "$MODEL" "$ROOT/scripts/llm_check/inputs.txt" "$(nproc)" "$OUT/system.txt" "${extra[@]}"
