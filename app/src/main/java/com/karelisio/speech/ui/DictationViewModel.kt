package com.karelisio.speech.ui

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.stt.EngineConfig
import com.karelisio.speech.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DictationUi(
    val recording: Boolean = false,
    val processing: Boolean = false,
    val loading: Boolean = false,
    val text: String = "",
    /** Temps entre l'appui sur stop et le texte disponible. */
    val latencyMs: Long? = null,
    val audioMs: Long? = null,
    val micOpenMs: Long? = null,
    val loadMs: Long? = null,
    val error: String? = null,
    /** Transcription brute quand le nettoyage IA l'a modifiée. */
    val raw: String? = null,
    /** Durée du nettoyage IA (null : pas de nettoyage). */
    val cleanMs: Long? = null,
    /** Durée de la reconnaissance seule. */
    val sttMs: Long? = null,
    val cleanNote: String? = null,
    /** Nombre de segments transcrits pendant l'enregistrement (null : pas en direct). */
    val liveSegments: Int? = null,
)

class DictationViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SpeechApp
    val recorder = app.recorder
    private val _ui = MutableStateFlow(DictationUi())
    val ui: StateFlow<DictationUi> = _ui

    /** Dernier audio enregistré, pour pouvoir l'ajouter aux clips de benchmark. */
    var lastAudio: FloatArray? = null
        private set

    fun currentConfig(): EngineConfig? = app.dictationConfig()

    fun warmUp() {
        val cfg = currentConfig() ?: return
        if (app.engines.loadedConfig == cfg) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { app.engines.warmUp(cfg) }
                .onSuccess { _ui.update { it.copy(loading = false, loadMs = app.engines.lastLoadMs) } }
                .onFailure { e -> _ui.update { it.copy(loading = false, error = e.message) } }
        }
    }

    @SuppressLint("MissingPermission") // vérifiée par l'UI avant l'appel
    fun toggle() {
        if (_ui.value.processing) return
        val pipeline = app.pipeline
        if (!recorder.isRecording) {
            runCatching { pipeline.start() }
                .onSuccess { _ui.update { it.copy(recording = true, error = null) } }
                .onFailure { e -> _ui.update { it.copy(error = "Micro : ${e.message}") } }
            return
        }
        _ui.update { it.copy(recording = false, processing = true, micOpenMs = recorder.lastStartLatencyMs) }
        if (currentConfig() == null) {
            pipeline.cancel()
            _ui.update { it.copy(processing = false, error = "Aucun modèle téléchargé") }
            return
        }
        viewModelScope.launch {
            runCatching { pipeline.stop() }
                .onSuccess { r ->
                    if (r == null) {
                        _ui.update { it.copy(processing = false, error = "Enregistrement trop court") }
                        return@onSuccess
                    }
                    lastAudio = r.audio
                    val c = r.clean
                    _ui.update {
                        it.copy(
                            processing = false, text = r.text, latencyMs = r.totalMs, audioMs = r.audioMs,
                            raw = r.raw.takeIf { c != null && c.applied && r.text != r.raw },
                            cleanMs = c?.totalMs, sttMs = r.sttMs,
                            cleanNote = c?.reason?.let { why -> "Nettoyage ignoré : $why" },
                            liveSegments = if (r.live) r.segments else null,
                        )
                    }
                }
                .onFailure { e ->
                    AppLog.e("Dictation", "échec", e)
                    _ui.update { it.copy(processing = false, error = e.message) }
                }
        }
    }

    fun saveLastAsClip(reference: String?) {
        val a = lastAudio ?: return
        app.clips.add(a, reference)
    }
}
