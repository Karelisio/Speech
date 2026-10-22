package com.karelisio.speech.stt

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.karelisio.speech.audio.AudioRecorder
import java.io.File

/** Parakeet TDT (NeMo) via sherpa-onnx / onnxruntime. Multilingue, sans paramètre de langue. */
class SherpaParakeetEngine(modelDir: File, override val config: EngineConfig) : SttEngine {
    private var recognizer: OfflineRecognizer? = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioRecorder.SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder.int8.onnx").absolutePath,
                    joiner = File(modelDir, "joiner.int8.onnx").absolutePath,
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                modelType = "nemo_transducer",
                numThreads = config.threads,
                provider = config.provider,
            ),
            decodingMethod = "greedy_search",
        ),
    )

    override fun transcribe(samples: FloatArray, request: SttRequest): SttResult {
        val r = checkNotNull(recognizer) { "Moteur libéré" }
        val t0 = SystemClock.elapsedRealtime()
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, AudioRecorder.SAMPLE_RATE)
            r.decode(stream)
            val text = r.getResult(stream).text.trim()
            return SttResult(text, SystemClock.elapsedRealtime() - t0)
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
    }
}
