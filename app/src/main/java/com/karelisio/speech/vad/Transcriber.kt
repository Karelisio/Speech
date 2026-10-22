package com.karelisio.speech.vad

import com.karelisio.speech.stt.SttEngine
import com.karelisio.speech.stt.SttRequest

/** Usage du VAD avant la reconnaissance. */
enum class VadMode(val label: String, val short: String) {
    OFF("Bloc entier", "bloc"),
    TRIM("Silences coupés", "VAD"),
    SEGMENTS("Pauses, en direct", "segments"),
}

data class Transcription(
    val text: String,
    /** false : le VAD n'a détecté aucune parole (rien à insérer). */
    val speech: Boolean,
    val vadMs: Long,
    /** Temps total de reconnaissance (tous segments). */
    val sttMs: Long,
    /** Temps du dernier segment : latence après le stop si les autres sont traités pendant l'enregistrement. */
    val tailMs: Long,
    val segments: Int,
)

/** Reconnaissance d'un enregistrement complet, avec ou sans VAD. */
object Transcriber {
    fun run(engine: SttEngine, vad: VadProcessor?, audio: FloatArray, mode: VadMode, request: SttRequest): Transcription {
        if (mode == VadMode.OFF || vad == null) {
            val r = engine.transcribe(audio, request)
            return Transcription(r.text, true, 0, r.processingMs, r.processingMs, 1)
        }
        val spans = vad.spans(audio)
        val vadMs = vad.lastMs
        if (spans.isEmpty()) return Transcription("", false, vadMs, 0, 0, 0)
        return when (mode) {
            VadMode.TRIM -> {
                val r = engine.transcribe(SpeechSpans.trim(audio, spans, VadProcessor.PAD_SAMPLES)!!, request)
                Transcription(r.text, true, vadMs, r.processingMs, r.processingMs, 1)
            }
            else -> {
                val results = SpeechSpans.segments(audio, spans, VadProcessor.PAD_SAMPLES).map { engine.transcribe(it, request) }
                Transcription(
                    text = results.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" "),
                    speech = true,
                    vadMs = vadMs,
                    sttMs = results.sumOf { it.processingMs },
                    tailMs = results.last().processingMs,
                    segments = results.size,
                )
            }
        }
    }
}
