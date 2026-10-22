package com.karelisio.speech.dictation

import android.annotation.SuppressLint
import android.os.SystemClock
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.stt.SttRequest
import com.karelisio.speech.vad.Transcriber
import com.karelisio.speech.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Résultat d'une tentative d'insertion dans le champ actif. */
enum class InsertOutcome { INPUT_CONNECTION, SET_TEXT, PASTE, CLIPBOARD_ONLY, EMPTY }

/** Insère le texte dicté dans le champ en focus (implémenté par le service d'accessibilité). */
fun interface TextSink {
    suspend fun insert(text: String): InsertOutcome
}

/**
 * Cycle d'une dictée depuis la bulle : micro → transcription → insertion.
 * Toutes les méthodes publiques s'appellent depuis le thread principal.
 */
class DictationController(private val app: SpeechApp) {
    enum class State { IDLE, RECORDING, PROCESSING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    val level get() = app.recorder.level

    private var job: Job? = null
    var sink: TextSink? = null

    /** Dernier résultat, pour l'affichage dans l'app. */
    val lastResult = MutableStateFlow<String?>(null)
    /** Moment où l'enregistrement a commencé (détection du double tap sur la bulle). */
    var recordingStartedAt = 0L
        private set

    @SuppressLint("MissingPermission") // l'autorisation micro est vérifiée par l'appelant
    fun start(): Boolean {
        if (_state.value != State.IDLE || app.recorder.isRecording) return false
        // Le modèle de nettoyage et son prompt système se préparent pendant qu'on parle.
        if (app.cleaningActive()) app.cleaners.warmUpAsync(app.cleanerModelId()!!, app.prefs.llmThreads)
        return runCatching { app.recorder.start() }
            .onSuccess { _state.value = State.RECORDING; recordingStartedAt = SystemClock.elapsedRealtime() }
            .onFailure { AppLog.e(TAG, "micro indisponible", it) }
            .isSuccess
    }

    fun toggle() {
        when (_state.value) {
            State.IDLE -> start()
            State.RECORDING -> stop()
            State.PROCESSING -> Unit
        }
    }

    /** Arrête l'enregistrement, transcrit puis insère le texte. */
    fun stop() {
        if (_state.value != State.RECORDING) return
        val tStop = SystemClock.elapsedRealtime()
        val audio = app.recorder.stop()
        val cfg = app.dictationConfig()
        if (cfg == null || audio.size < MIN_SAMPLES) {
            _state.value = State.IDLE
            if (cfg == null) AppLog.w(TAG, "aucun modèle prêt")
            return
        }
        _state.value = State.PROCESSING
        job = scope.launch {
            try {
                val mode = app.prefs.vadMode
                val r = app.engines.use(cfg) {
                    Transcriber.run(it, app.vad(), audio, mode, SttRequest(app.prefs.language))
                }
                val tText = SystemClock.elapsedRealtime()
                val llm = app.cleanerModelId()
                val cleaned = if (r.text.isNotBlank() && app.cleaning.value && llm != null) {
                    app.cleaners.clean(llm, app.prefs.llmThreads, r.text).also {
                        AppLog.i(
                            TAG,
                            "nettoyage ${it.totalMs} ms (prompt ${it.promptTokens} tok ${"%.0f".format(it.promptMs)} ms, " +
                                "${it.reusedTokens} en cache, ${it.genTokens} générés ${"%.0f".format(it.genMs)} ms)" +
                                (it.reason?.let { why -> " — brut conservé : $why" } ?: ""),
                        )
                    }.text
                } else {
                    r.text
                }
                val tClean = SystemClock.elapsedRealtime()
                val outcome = if (cleaned.isBlank()) InsertOutcome.EMPTY
                else sink?.insert(cleaned) ?: InsertOutcome.CLIPBOARD_ONLY
                val tInsert = SystemClock.elapsedRealtime()
                lastResult.value = cleaned
                AppLog.i(
                    TAG,
                    "audio ${audio.size / 16} ms → texte ${tText - tStop} ms (VAD ${r.vadMs} ms, ${mode.short}, " +
                        "${r.segments} segment(s)), nettoyé ${tClean - tStop} ms, inséré ${tInsert - tStop} ms ($outcome)" +
                        if (!r.speech) " — aucune parole détectée" else "",
                )
            } catch (e: Exception) {
                AppLog.e(TAG, "échec de la dictée", e)
            } finally {
                _state.value = State.IDLE
            }
        }
    }

    /** Bascule rapide du nettoyage IA (double tap sur la bulle). */
    fun toggleCleaning(): Boolean {
        val on = !app.cleaning.value
        app.setCleaning(on)
        AppLog.i(TAG, "nettoyage IA ${if (on) "activé" else "désactivé"}")
        return on
    }

    /** Abandonne l'enregistrement en cours sans transcrire. */
    fun cancel() {
        if (_state.value == State.RECORDING) {
            app.recorder.stop()
            _state.value = State.IDLE
            AppLog.i(TAG, "dictée annulée")
        }
    }

    companion object {
        private const val TAG = "Dictation"
        private const val MIN_SAMPLES = 16_000 / 4 // < 250 ms : tap accidentel
    }
}
