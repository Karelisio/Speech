package com.karelisio.speech.dictation

import android.annotation.SuppressLint
import android.os.SystemClock
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.stt.SttRequest
import com.karelisio.speech.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Résultat d'une tentative d'insertion dans le champ actif. */
enum class InsertOutcome { SET_TEXT, PASTE, CLIPBOARD_ONLY, EMPTY }

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

    @SuppressLint("MissingPermission") // l'autorisation micro est vérifiée par l'appelant
    fun start(): Boolean {
        if (_state.value != State.IDLE || app.recorder.isRecording) return false
        return runCatching { app.recorder.start() }
            .onSuccess { _state.value = State.RECORDING }
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
                val r = app.engines.use(cfg) { it.transcribe(audio, SttRequest(app.prefs.language)) }
                val tText = SystemClock.elapsedRealtime()
                val outcome = if (r.text.isBlank()) InsertOutcome.EMPTY
                else sink?.insert(r.text) ?: InsertOutcome.CLIPBOARD_ONLY
                val tInsert = SystemClock.elapsedRealtime()
                lastResult.value = r.text
                AppLog.i(
                    TAG,
                    "audio ${audio.size / 16} ms → texte ${tText - tStop} ms, inséré ${tInsert - tStop} ms ($outcome)",
                )
            } catch (e: Exception) {
                AppLog.e(TAG, "échec de la dictée", e)
            } finally {
                _state.value = State.IDLE
            }
        }
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
