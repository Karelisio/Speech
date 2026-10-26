package com.karelisio.speech.stt

import android.os.SystemClock
import java.io.File

class WhisperEngine(modelFile: File, override val config: EngineConfig) : SttEngine {
    private var ctx: Long = WhisperNative.init(modelFile.absolutePath, flashAttn = true)

    init {
        check(ctx != 0L) { "Impossible de charger ${modelFile.name}" }
    }

    override fun transcribe(samples: FloatArray, request: SttRequest): SttResult {
        check(ctx != 0L) { "Moteur libéré" }
        val audioCtx = if (config.whisperDynamicAudioCtx) dynamicAudioCtx(samples.size) else 0
        val t0 = SystemClock.elapsedRealtime()
        val bytes = WhisperNative.transcribe(
            ctx, samples, request.language, config.threads, request.prompt, audioCtx, config.beamSize,
        ) ?: error("Échec de la transcription Whisper")
        val ms = SystemClock.elapsedRealtime() - t0
        val timings = WhisperNative.lastTimings(ctx)
        return SttResult(WhisperHallucinations.strip(String(bytes, Charsets.UTF_8).trim()), ms, timings.getOrNull(0))
    }

    override fun close() {
        if (ctx != 0L) {
            WhisperNative.free(ctx)
            ctx = 0L
        }
    }

    companion object {
        private const val FULL_CTX = 1500 // 30 s

        /**
         * Une trame d'encodeur = 20 ms (hop 10 ms, conv stride 2). On garde une marge et on
         * arrondit au multiple de 64 supérieur.
         */
        fun dynamicAudioCtx(nSamples: Int): Int {
            val frames = (nSamples + 319) / 320 + 32
            return ((frames + 63) / 64 * 64).coerceAtMost(FULL_CTX)
        }
    }
}
