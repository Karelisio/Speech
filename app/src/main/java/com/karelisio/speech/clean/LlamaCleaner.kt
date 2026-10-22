package com.karelisio.speech.clean

import android.os.SystemClock
import com.karelisio.speech.llm.LlamaNative
import java.io.File

/** Nettoyage par un petit LLM (llama.cpp), prompt système conservé dans le cache KV. */
class LlamaCleaner(
    modelFile: File,
    override val modelId: String,
    threads: Int,
    override var system: String = CleanPrompt.SYSTEM,
) : TextCleaner {
    companion object {
        private const val N_CTX = 2048
        private const val MAX_RATIO = 1.5f
        private const val EXTRA_TOKENS = 16
    }

    private var ptr: Long = LlamaNative.load(modelFile.absolutePath, N_CTX, threads, threads)

    init {
        check(ptr != 0L) { "Impossible de charger ${modelFile.name}" }
    }

    private var warmedFor: String? = null

    @Synchronized
    override fun warm() {
        if (ptr == 0L || warmedFor == system) return
        if (LlamaNative.warm(ptr, system)) warmedFor = system
    }

    @Synchronized
    override fun prefill(partial: String) {
        if (ptr == 0L || partial.isBlank()) return
        warm()
        LlamaNative.prefill(ptr, system, partial.trim())
    }

    @Synchronized
    override fun clean(raw: String): CleanResult {
        val t0 = SystemClock.elapsedRealtime()
        if (raw.isBlank() || ptr == 0L) return CleanResult(raw, raw, false, 0, reason = "rien à nettoyer")
        warm()
        val bytes = LlamaNative.complete(ptr, system, raw.trim(), MAX_RATIO, EXTRA_TOKENS)
        val s = LlamaNative.lastStats(ptr)
        val ms = SystemClock.elapsedRealtime() - t0
        val stats = { text: String, applied: Boolean, reason: String? ->
            CleanResult(
                text, raw, applied, ms,
                promptMs = s.getOrElse(3) { 0.0 }, genMs = s.getOrElse(4) { 0.0 },
                reusedTokens = s.getOrElse(0) { 0.0 }.toInt(), promptTokens = s.getOrElse(1) { 0.0 }.toInt(),
                genTokens = s.getOrElse(2) { 0.0 }.toInt(), reason = reason,
            )
        }
        if (bytes == null) return stats(raw, false, "échec du modèle")
        val (clean, why) = CleanPrompt.sanitize(raw, String(bytes, Charsets.UTF_8))
        return if (clean == null) stats(raw, false, why) else stats(clean, true, null)
    }

    @Synchronized
    override fun close() {
        if (ptr != 0L) {
            LlamaNative.free(ptr)
            ptr = 0
        }
    }
}
