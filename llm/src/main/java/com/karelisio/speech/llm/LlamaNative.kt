package com.karelisio.speech.llm

/** Opérations llama.cpp communes aux bibliothèques CPU et GPU. */
interface LlamaApi {
    val name: String
    fun load(path: String, nCtx: Int, threads: Int, threadsBatch: Int): Long
    fun free(ptr: Long)
    fun abort(ptr: Long)
    /** Met le prompt système en cache (KV). */
    fun warm(ptr: Long, system: String): Boolean
    /** Remplit le cache KV avec (système + début du texte), sans générer. */
    fun prefill(ptr: Long, system: String, user: String): Boolean
    /** [draftMax] > 0 : décodage spéculatif, jusqu'à [draftMax] tokens recopiés de [user] vérifiés par passage. */
    fun complete(ptr: Long, system: String, user: String, maxRatio: Float, extra: Int, draftMax: Int): ByteArray?
    /** [réutilisés, évalués, générés, ms prompt, ms génération, brouillon proposé, brouillon accepté]. */
    fun lastStats(ptr: Long): DoubleArray
    fun systemInfo(): String
}

/** Liaison JNI vers llama.cpp sur CPU (llm/src/main/cpp/llama_jni.cpp). */
object LlamaNative : LlamaApi {
    init {
        System.loadLibrary("llama_jni")
    }

    override val name = "CPU"

    override fun load(path: String, nCtx: Int, threads: Int, threadsBatch: Int) = nLoad(path, nCtx, threads, threadsBatch, 0)
    override fun free(ptr: Long) = nFree(ptr)
    override fun abort(ptr: Long) = nAbort(ptr)
    override fun warm(ptr: Long, system: String) = nWarm(ptr, system)
    override fun prefill(ptr: Long, system: String, user: String) = nPrefill(ptr, system, user)
    override fun complete(ptr: Long, system: String, user: String, maxRatio: Float, extra: Int, draftMax: Int) =
        nComplete(ptr, system, user, maxRatio, extra, draftMax)
    override fun lastStats(ptr: Long) = nLastStats(ptr)
    override fun systemInfo() = nSystemInfo()

    @JvmStatic private external fun nLoad(path: String, nCtx: Int, threads: Int, threadsBatch: Int, gpuLayers: Int): Long
    @JvmStatic private external fun nFree(ptr: Long)
    @JvmStatic private external fun nAbort(ptr: Long)
    @JvmStatic private external fun nWarm(ptr: Long, system: String): Boolean
    @JvmStatic private external fun nPrefill(ptr: Long, system: String, user: String): Boolean
    @JvmStatic private external fun nComplete(ptr: Long, system: String, user: String, maxRatio: Float, extra: Int, draftMax: Int): ByteArray?
    @JvmStatic private external fun nLastStats(ptr: Long): DoubleArray
    @JvmStatic private external fun nSystemInfo(): String
}
