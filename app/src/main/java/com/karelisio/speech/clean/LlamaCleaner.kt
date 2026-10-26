package com.karelisio.speech.clean

import android.os.SystemClock
import com.karelisio.speech.llm.LlamaApi
import java.io.File

/** Nettoyage par un petit LLM (llama.cpp), prompt système conservé dans le cache KV. */
class LlamaCleaner(
    modelFile: File,
    override val modelId: String,
    threads: Int,
    override var system: String = CleanPrompt.SYSTEM,
    /** Bibliothèque llama.cpp : CPU ou GPU (OpenCL). */
    val api: LlamaApi,
) : TextCleaner {
    /** Tokens de brouillon vérifiés par passage (0 = décodage glouton classique). */
    @Volatile
    var draftMax: Int = DEFAULT_DRAFT

    companion object {
        private const val N_CTX = 2048
        private const val MAX_RATIO = 1.5f
        private const val EXTRA_TOKENS = 16
        const val DEFAULT_DRAFT = 8
    }

    private var ptr: Long = api.load(modelFile.absolutePath, N_CTX, threads, threads)

    init {
        check(ptr != 0L) { "Impossible de charger ${modelFile.name}" }
    }

    private var warmedFor: String? = null

    @Synchronized
    override fun warm() {
        if (ptr == 0L || warmedFor == system) return
        if (api.warm(ptr, system)) warmedFor = system
    }

    @Synchronized
    override fun prefill(partial: String) {
        if (ptr == 0L || partial.isBlank()) return
        warm()
        api.prefill(ptr, system, partial.trim())
    }

    @Synchronized
    override fun clean(raw: String): CleanResult {
        val t0 = SystemClock.elapsedRealtime()
        if (raw.isBlank() || ptr == 0L) return CleanResult(raw, raw, false, 0, reason = "rien à nettoyer")
        warm()
        val bytes = api.complete(ptr, system, raw.trim(), MAX_RATIO, EXTRA_TOKENS, draftMax)
        val s = api.lastStats(ptr)
        val ms = SystemClock.elapsedRealtime() - t0
        val stats = { text: String, applied: Boolean, reason: String? ->
            CleanResult(
                text, raw, applied, ms,
                promptMs = s.getOrElse(3) { 0.0 }, genMs = s.getOrElse(4) { 0.0 },
                reusedTokens = s.getOrElse(0) { 0.0 }.toInt(), promptTokens = s.getOrElse(1) { 0.0 }.toInt(),
                genTokens = s.getOrElse(2) { 0.0 }.toInt(), draftedTokens = s.getOrElse(5) { 0.0 }.toInt(),
                acceptedTokens = s.getOrElse(6) { 0.0 }.toInt(), reason = reason,
            )
        }
        if (bytes == null) return stats(raw, false, "échec du modèle")
        val (clean, why) = CleanPrompt.sanitize(raw, String(bytes, Charsets.UTF_8))
        return if (clean == null) stats(raw, false, why) else stats(clean, true, null)
    }

    @Synchronized
    override fun close() {
        if (ptr != 0L) {
            api.free(ptr)
            ptr = 0
        }
    }
}
