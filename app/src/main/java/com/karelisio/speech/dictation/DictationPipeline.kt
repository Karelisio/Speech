package com.karelisio.speech.dictation

import android.Manifest
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.clean.CleanResult
import com.karelisio.speech.stt.SttRequest
import com.karelisio.speech.util.AppLog
import com.karelisio.speech.vad.Transcriber
import com.karelisio.speech.vad.VadMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Résultat complet d'une dictée, de l'audio au texte final. */
data class DictationResult(
    /** Texte à insérer (nettoyé si le nettoyage IA est actif, dictionnaire appliqué). */
    val text: String,
    /** Transcription avant nettoyage IA (dictionnaire appliqué). */
    val raw: String,
    val speech: Boolean,
    val audio: FloatArray,
    val audioMs: Long,
    /** Stop → texte reconnu. */
    val sttMs: Long,
    /** Stop → texte final. */
    val totalMs: Long,
    val clean: CleanResult?,
    val mode: VadMode,
    val live: Boolean,
    val segments: Int,
    val vadMs: Long,
)

/**
 * Pipeline commun à la bulle et à l'essai rapide : micro → (VAD en direct) → reconnaissance
 * → dictionnaire → nettoyage IA → historique.
 */
class DictationPipeline(private val app: SpeechApp, private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "Pipeline"
        const val MIN_SAMPLES = 16_000 / 4 // < 250 ms : tap accidentel
    }

    private var live: LiveSession? = null

    var recordingStartedAt = 0L
        private set

    private fun request() = SttRequest(app.prefs.language, prompt = app.dictionary.whisperPrompt())

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        // Le modèle de nettoyage et son prompt système se préparent pendant qu'on parle.
        app.cleanerModelId()?.takeIf { app.cleaning.value }?.let { app.cleaners.warmUpAsync(it, app.prefs.llmThreads) }
        app.recorder.start()
        recordingStartedAt = SystemClock.elapsedRealtime()
        app.lastActivityAt = recordingStartedAt
        val cfg = app.dictationConfig()
        if (cfg != null && app.prefs.vadMode == VadMode.SEGMENTS) {
            app.vadModelFile()?.let { f ->
                live = runCatching { LiveSession(app, cfg, f, request(), scope).also { it.start() } }
                    .onFailure { AppLog.e(TAG, "transcription en direct indisponible", it) }
                    .getOrNull()
            }
        }
    }

    fun cancel() {
        if (app.recorder.isRecording) app.recorder.stop()
        live?.cancel()
        live = null
    }

    /** Arrête l'enregistrement et produit le texte final ; null si rien d'exploitable. */
    suspend fun stop(): DictationResult? {
        val tStop = SystemClock.elapsedRealtime()
        val audio = app.recorder.stop()
        val session = live
        live = null
        val cfg = app.dictationConfig()
        if (cfg == null || audio.size < MIN_SAMPLES) {
            session?.cancel()
            if (cfg == null) AppLog.w(TAG, "aucun modèle prêt")
            return null
        }
        val mode = app.prefs.vadMode
        val t = session?.finish(audio)
            ?: app.engines.use(cfg) { Transcriber.run(it, app.vad(), audio, mode, request()) }
        val raw = app.dictionary.apply(t.text)
        val tText = SystemClock.elapsedRealtime()

        val llm = app.cleanerModelId()
        val clean = if (raw.isNotBlank() && app.cleaning.value && llm != null) {
            app.cleaners.clean(llm, app.prefs.llmThreads, raw, app.dictionary.terms)
        } else null
        val final = clean?.text?.let { app.dictionary.apply(it) } ?: raw
        val tEnd = SystemClock.elapsedRealtime()
        app.lastActivityAt = tEnd

        val result = DictationResult(
            text = final, raw = raw, speech = t.speech, audio = audio, audioMs = audio.size / 16L,
            sttMs = tText - tStop, totalMs = tEnd - tStop, clean = clean, mode = mode,
            live = session != null, segments = t.segments, vadMs = t.vadMs,
        )
        AppLog.i(
            TAG,
            "audio ${result.audioMs} ms → texte ${result.sttMs} ms (${mode.short}${if (result.live) ", en direct" else ""}, " +
                "${t.segments} segment(s), VAD ${t.vadMs} ms)" +
                (clean?.let {
                    ", nettoyage ${it.totalMs} ms (${it.reusedTokens} tok en cache, ${it.promptTokens} évalués, " +
                        "${it.genTokens} générés, brouillon ${it.acceptedTokens}/${it.draftedTokens})" +
                        (it.reason?.let { why -> " — brut conservé : $why" } ?: "")
                } ?: "") + ", total ${result.totalMs} ms" + if (!t.speech) " — aucune parole" else "",
        )
        // Écriture de l'historique en tâche de fond : l'insertion n'attend pas le disque.
        if (final.isNotBlank()) scope.launch(Dispatchers.IO) { app.history.add(raw, final, result.totalMs) }
        return result
    }
}
