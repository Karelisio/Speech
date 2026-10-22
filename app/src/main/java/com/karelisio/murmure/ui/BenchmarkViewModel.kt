package com.karelisio.murmure.ui

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.karelisio.murmure.MurmureApp
import com.karelisio.murmure.audio.AudioPlayer
import com.karelisio.murmure.bench.BenchClip
import com.karelisio.murmure.bench.BenchPlan
import com.karelisio.murmure.bench.BenchReport
import com.karelisio.murmure.bench.BenchmarkRunner
import com.karelisio.murmure.bench.ConfigResult
import com.karelisio.murmure.models.EngineKind
import com.karelisio.murmure.stt.EngineConfig
import com.karelisio.murmure.util.AppLog
import com.karelisio.murmure.util.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BenchUi(
    val selectedModels: Set<String> = emptySet(),
    val threads: Set<Int> = emptySet(),
    val compareWhisperCtx: Boolean = false,
    val repetitions: Int = 1,
    val running: Boolean = false,
    val progressText: String = "",
    val progress: Float = 0f,
    val results: List<ConfigResult> = emptyList(),
    val recording: Boolean = false,
    val message: String? = null,
)

class BenchmarkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MurmureApp
    val clips = app.clips.clips
    val recorder = app.recorder
    private val runner = BenchmarkRunner(app, app.models, app.clips)
    private var job: Job? = null
    private var lastPlan: BenchPlan? = null

    val threadChoices: List<Int> = (listOf(2, 4, 6, 8) + DeviceInfo.performanceCoreCount())
        .filter { it <= Runtime.getRuntime().availableProcessors() }
        .distinct().sorted()

    private val _ui = MutableStateFlow(
        BenchUi(
            selectedModels = app.models.readyModels().map { it.id }.toSet(),
            threads = setOf(DeviceInfo.performanceCoreCount()),
        )
    )
    val ui: StateFlow<BenchUi> = _ui

    fun toggleModel(id: String) = _ui.update {
        it.copy(selectedModels = if (id in it.selectedModels) it.selectedModels - id else it.selectedModels + id)
    }

    fun toggleThreads(n: Int) = _ui.update {
        it.copy(threads = if (n in it.threads) it.threads - n else it.threads + n)
    }

    fun setCompareCtx(v: Boolean) = _ui.update { it.copy(compareWhisperCtx = v) }
    fun setRepetitions(n: Int) = _ui.update { it.copy(repetitions = n) }

    fun plannedConfigs(): List<EngineConfig> {
        val s = _ui.value
        return s.selectedModels.filter { app.models.isReady(it) }.flatMap { id ->
            val kind = app.models.find(id)!!.engine
            s.threads.sorted().flatMap { t ->
                val ctxModes = if (kind == EngineKind.WHISPER && s.compareWhisperCtx) listOf(false, true) else listOf(false)
                ctxModes.map { dyn -> EngineConfig(id, t, whisperDynamicAudioCtx = dyn) }
            }
        }
    }

    fun run() {
        if (job?.isActive == true) return
        val configs = plannedConfigs()
        val clipList = clips.value
        if (configs.isEmpty() || clipList.isEmpty()) {
            _ui.update { it.copy(message = "Sélectionne au moins un modèle téléchargé et enregistre au moins un clip.") }
            return
        }
        val plan = BenchPlan(configs, clipList, _ui.value.repetitions, app.prefs.language)
        lastPlan = plan
        _ui.update { it.copy(running = true, results = emptyList(), message = null, progress = 0f) }
        job = viewModelScope.launch {
            AppLog.i("Bench", "début : ${configs.size} configs × ${clipList.size} clips")
            app.engines.release() // mesures RAM propres : aucun autre modèle chargé
            try {
                runner.run(
                    plan,
                    onProgress = { msg, p -> _ui.update { it.copy(progressText = msg, progress = p) } },
                    onResult = { r -> _ui.update { it.copy(results = it.results + r) } },
                )
            } finally {
                _ui.update { it.copy(running = false) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _ui.update { it.copy(running = false, progressText = "Annulé") }
    }

    fun report(): String = BenchReport.build(app, lastPlan, _ui.value.results, clips.value)

    @SuppressLint("MissingPermission")
    fun startRecording() {
        runCatching { recorder.start() }
            .onSuccess { _ui.update { it.copy(recording = true) } }
            .onFailure { e -> _ui.update { it.copy(message = "Micro : ${e.message}") } }
    }

    fun stopRecording(reference: String?) {
        val audio = recorder.stop()
        _ui.update { it.copy(recording = false) }
        if (audio.size < 16_000 / 2) {
            _ui.update { it.copy(message = "Clip trop court") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) { app.clips.add(audio, reference) }
    }

    fun importWav(uri: Uri, reference: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.clips.import(uri, reference) }
                .onFailure { e -> _ui.update { it.copy(message = "Import impossible : ${e.message}") } }
        }
    }

    fun play(clip: BenchClip) {
        viewModelScope.launch(Dispatchers.IO) { AudioPlayer.play(app.clips.samples(clip)) }
    }

    fun delete(clip: BenchClip) = app.clips.delete(clip.id)
    fun updateReference(clip: BenchClip, ref: String?) = app.clips.updateReference(clip.id, ref)
    fun dismissMessage() = _ui.update { it.copy(message = null) }
}
