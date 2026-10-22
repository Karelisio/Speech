package com.karelisio.speech.llm

/** Liaison JNI vers llama.cpp (llm/src/main/cpp/llama_jni.cpp). */
object LlamaNative {
    init {
        System.loadLibrary("llama_jni")
    }

    @JvmStatic external fun load(path: String, nCtx: Int, threads: Int, threadsBatch: Int): Long
    @JvmStatic external fun free(ptr: Long)
    @JvmStatic external fun abort(ptr: Long)
    /** Met le prompt système en cache (KV). */
    @JvmStatic external fun warm(ptr: Long, system: String): Boolean
    /** Remplit le cache KV avec (système + début du texte), sans générer. */
    @JvmStatic external fun prefill(ptr: Long, system: String, user: String): Boolean
    @JvmStatic external fun complete(ptr: Long, system: String, user: String, maxRatio: Float, extra: Int): ByteArray?
    /** [réutilisés, évalués, générés, ms prompt, ms génération]. */
    @JvmStatic external fun lastStats(ptr: Long): DoubleArray
    @JvmStatic external fun systemInfo(): String
}
