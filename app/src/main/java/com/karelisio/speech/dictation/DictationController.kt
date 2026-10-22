package com.karelisio.speech.dictation

import android.annotation.SuppressLint
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * Cycle d'une dictée depuis la bulle : [DictationPipeline] puis insertion dans le champ actif.
 * Toutes les méthodes publiques s'appellent depuis le thread principal.
 */
class DictationController(private val app: SpeechApp) {
    enum class State { IDLE, RECORDING, PROCESSING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    val level get() = app.recorder.level
    private val pipeline get() = app.pipeline

    var sink: TextSink? = null

    /** Dernier texte inséré, pour l'affichage dans l'app. */
    val lastResult = MutableStateFlow<String?>(null)

    /** Moment où l'enregistrement a commencé (détection du double tap sur la bulle). */
    val recordingStartedAt get() = pipeline.recordingStartedAt

    @SuppressLint("MissingPermission") // l'autorisation micro est vérifiée par l'appelant
    fun start(): Boolean {
        if (_state.value != State.IDLE || app.recorder.isRecording) return false
        return runCatching { pipeline.start() }
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

    /** Arrête l'enregistrement, transcrit, nettoie puis insère le texte. */
    fun stop() {
        if (_state.value != State.RECORDING) return
        _state.value = State.PROCESSING
        scope.launch {
            try {
                val r = pipeline.stop() ?: return@launch
                if (r.text.isBlank()) {
                    AppLog.i(TAG, "rien à insérer")
                    return@launch
                }
                val outcome = sink?.insert(r.text) ?: InsertOutcome.CLIPBOARD_ONLY
                lastResult.value = r.text
                AppLog.i(TAG, "inséré ($outcome)")
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
            pipeline.cancel()
            _state.value = State.IDLE
            AppLog.i(TAG, "dictée annulée")
        }
    }

    companion object {
        private const val TAG = "Dictation"
    }
}
