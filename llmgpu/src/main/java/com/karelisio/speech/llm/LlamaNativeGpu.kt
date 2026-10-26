package com.karelisio.speech.llm

/**
 * llama.cpp avec le backend OpenCL (noyaux Adreno). La bibliothèque dépend du libOpenCL.so du
 * téléphone : son chargement peut échouer, d'où [available] avant tout usage.
 */
object LlamaNativeGpu : LlamaApi {
    /** Couches envoyées au GPU (toutes). */
    private const val GPU_LAYERS = 999

    /** null si disponible, sinon la raison de l'échec du chargement. */
    val loadError: String? by lazy {
        runCatching { System.loadLibrary("llama_jni_gpu") }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
    }
    val available: Boolean get() = loadError == null

    override val name = "GPU (OpenCL)"

    override fun load(path: String, nCtx: Int, threads: Int, threadsBatch: Int): Long {
        check(available) { "GPU indisponible : $loadError" }
        return nLoad(path, nCtx, threads, threadsBatch, GPU_LAYERS)
    }
    override fun free(ptr: Long) = nFree(ptr)
    override fun abort(ptr: Long) = nAbort(ptr)
    override fun warm(ptr: Long, system: String) = nWarm(ptr, system)
    override fun prefill(ptr: Long, system: String, user: String) = nPrefill(ptr, system, user)
    override fun complete(ptr: Long, system: String, user: String, maxRatio: Float, extra: Int, draftMax: Int) =
        nComplete(ptr, system, user, maxRatio, extra, draftMax)
    override fun lastStats(ptr: Long) = nLastStats(ptr)
    override fun systemInfo() = if (available) nSystemInfo() else "indisponible : $loadError"

    @JvmStatic private external fun nLoad(path: String, nCtx: Int, threads: Int, threadsBatch: Int, gpuLayers: Int): Long
    @JvmStatic private external fun nFree(ptr: Long)
    @JvmStatic private external fun nAbort(ptr: Long)
    @JvmStatic private external fun nWarm(ptr: Long, system: String): Boolean
    @JvmStatic private external fun nPrefill(ptr: Long, system: String, user: String): Boolean
    @JvmStatic private external fun nComplete(ptr: Long, system: String, user: String, maxRatio: Float, extra: Int, draftMax: Int): ByteArray?
    @JvmStatic private external fun nLastStats(ptr: Long): DoubleArray
    @JvmStatic private external fun nSystemInfo(): String
}
