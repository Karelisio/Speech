package com.karelisio.speech.vad

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.karelisio.speech.audio.AudioRecorder
import java.io.File

/**
 * Silero VAD (sherpa-onnx). Détecte les intervalles de parole d'un enregistrement.
 * Pauses ≥ [MIN_SILENCE_S] = coupure ; segments plafonnés à [MAX_SPEECH_S] pour borner la latence.
 */
class VadProcessor(modelFile: File) : AutoCloseable {
    companion object {
        private const val WINDOW = 512 // 32 ms à 16 kHz, taille attendue par Silero
        const val MIN_SILENCE_S = 0.5f
        const val MAX_SPEECH_S = 20f
        /** Marge conservée autour de la parole : Silero coupe au ras des mots. */
        const val PAD_SAMPLES = AudioRecorder.SAMPLE_RATE / 4 // 250 ms
    }

    private var vad: Vad? = Vad(
        assetManager = null,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelFile.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = MIN_SILENCE_S,
                minSpeechDuration = 0.2f,
                windowSize = WINDOW,
                maxSpeechDuration = MAX_SPEECH_S,
            ),
            sampleRate = AudioRecorder.SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    /** Durée du dernier appel à [spans], en ms. */
    var lastMs: Long = 0
        private set

    @Synchronized
    fun spans(samples: FloatArray): List<Span> {
        val v = checkNotNull(vad) { "VAD libéré" }
        val t0 = SystemClock.elapsedRealtime()
        v.reset()
        val out = ArrayList<Span>()
        fun drain() {
            while (!v.empty()) {
                val seg = v.front()
                out += Span(seg.start, seg.start + seg.samples.size)
                v.pop()
            }
        }
        var i = 0
        val chunk = FloatArray(WINDOW)
        while (i + WINDOW <= samples.size) {
            System.arraycopy(samples, i, chunk, 0, WINDOW)
            v.acceptWaveform(chunk)
            drain()
            i += WINDOW
        }
        v.flush()
        drain()
        lastMs = SystemClock.elapsedRealtime() - t0
        return out
    }

    @Synchronized
    override fun close() {
        vad?.release()
        vad = null
    }
}
