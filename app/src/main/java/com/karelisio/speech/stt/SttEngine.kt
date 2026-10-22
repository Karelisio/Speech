package com.karelisio.speech.stt

/** Paramètres d'une instance de moteur (fixés au chargement). */
data class EngineConfig(
    val modelId: String,
    val threads: Int,
    /** Whisper uniquement : réduit le contexte audio de l'encodeur à la durée réelle du clip. */
    val whisperDynamicAudioCtx: Boolean = false,
    /** Whisper uniquement : 1 = glouton. */
    val beamSize: Int = 1,
    /** sherpa-onnx uniquement : "cpu", "nnapi"… */
    val provider: String = "cpu",
) {
    fun label(): String = buildString {
        append(modelId).append(" · ").append(threads).append("t")
        if (whisperDynamicAudioCtx) append(" · ctx-dyn")
        if (beamSize > 1) append(" · beam").append(beamSize)
        if (provider != "cpu") append(" · ").append(provider)
    }
}

data class SttRequest(
    val language: String = "fr",
    /** Prompt initial Whisper (dictionnaire perso). Ignoré par Parakeet. */
    val prompt: String? = null,
)

data class SttResult(
    val text: String,
    /** Temps de traitement mesuré autour de l'appel natif. */
    val processingMs: Long,
    /** Whisper : temps de l'encodeur (ms), pour voir la répartition encodeur/décodeur. */
    val encodeMs: Float? = null,
)

/** Moteur de reconnaissance vocale. Pas thread-safe : un appel à la fois par instance. */
interface SttEngine : AutoCloseable {
    val config: EngineConfig
    fun transcribe(samples: FloatArray, request: SttRequest = SttRequest()): SttResult
}
