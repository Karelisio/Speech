package com.karelisio.speech.ui

import android.annotation.SuppressLint
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.stt.EngineConfig
import com.karelisio.speech.stt.SttRequest
import com.karelisio.speech.vad.Transcriber
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
        if (!recorder.isRecording) {
            runCatching { recorder.start() }
                .onSuccess { _ui.update { it.copy(recording = true, error = null) } }
                .onFailure { e -> _ui.update { it.copy(error = "Micro : ${e.message}") } }
            return
        }
        val tStop = SystemClock.elapsedRealtime()
        val audio = recorder.stop()
        lastAudio = audio
        _ui.update { it.copy(recording = false, processing = true, micOpenMs = recorder.lastStartLatencyMs) }
        val cfg = currentConfig()
        if (cfg == null) {
            _ui.update { it.copy(processing = false, error = "Aucun modèle téléchargé") }
            return
        }
        viewModelScope.launch {
            runCatching {
                app.engines.use(cfg) { Transcriber.run(it, app.vad(), audio, app.prefs.vadMode, SttRequest(app.prefs.language)) }
            }.onSuccess { r ->
                val latency = SystemClock.elapsedRealtime() - tStop
                AppLog.i("Dictation", "${cfg.label()} : ${audio.size / 16} ms d'audio → $latency ms après stop")
                _ui.update {
                    it.copy(processing = false, text = r.text, latencyMs = latency, audioMs = audio.size / 16L)
                }
            }.onFailure { e ->
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
