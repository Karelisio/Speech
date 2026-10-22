package com.karelisio.speech.ui

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.audio.AudioPlayer
import com.karelisio.speech.bench.BenchClip
import com.karelisio.speech.bench.BenchPlan
import com.karelisio.speech.bench.BenchReport
import com.karelisio.speech.bench.BenchmarkRunner
import com.karelisio.speech.bench.ConfigResult
import com.karelisio.speech.models.EngineKind
import com.karelisio.speech.stt.EngineConfig
import com.karelisio.speech.util.AppLog
import com.karelisio.speech.util.Prefs
import com.karelisio.speech.vad.VadMode
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
    val modes: Set<VadMode> = setOf(VadMode.OFF, VadMode.TRIM, VadMode.SEGMENTS),
    val clean: Boolean = false,
    val endurance: com.karelisio.speech.bench.EnduranceResult? = null,
    val running: Boolean = false,
    val progressText: String = "",
    val progress: Float = 0f,
    val results: List<ConfigResult> = emptyList(),
    val recording: Boolean = false,
    val message: String? = null,
)

class BenchmarkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SpeechApp
    val clips = app.clips.clips
    val recorder = app.recorder
    private val runner = BenchmarkRunner(app, app.models, app.clips)
    private var job: Job? = null
    private var lastPlan: BenchPlan? = null

    val threadChoices: List<Int> = listOf(1, 2, 3, 4, 5, 6, 8)
        .filter { it <= Runtime.getRuntime().availableProcessors() }

    private val _ui = MutableStateFlow(
        BenchUi(
            selectedModels = app.models.readyModels().map { it.id }.toSet(),
            threads = setOf(Prefs.DEFAULT_THREADS),
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
    fun toggleMode(m: VadMode) = _ui.update {
        it.copy(modes = if (m in it.modes && it.modes.size > 1) it.modes - m else it.modes + m)
    }
    fun setClean(v: Boolean) = _ui.update { it.copy(clean = v) }
    val llmReady: Boolean get() = app.cleanerModelId() != null
    val vadReady: Boolean get() = app.models.vadModel?.let { app.models.isReady(it.id) } == true

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
        val modes = VadMode.entries.filter { it in _ui.value.modes }
        val plan = BenchPlan(configs, clipList, modes, _ui.value.repetitions, app.prefs.language)
        lastPlan = plan
        _ui.update { it.copy(running = true, results = emptyList(), message = null, progress = 0f) }
        job = viewModelScope.launch {
            AppLog.i("Bench", "début : ${configs.size} configs × ${clipList.size} clips")
            app.engines.release() // mesures RAM propres : aucun autre modèle chargé
            try {
                val llm = app.cleanerModelId()?.takeIf { _ui.value.clean }
                val threads = app.prefs.llmThreads
                runner.run(
                    plan,
                    app.vad(),
                    llm?.let { id -> { text: String -> app.cleaners.clean(id, threads, text) } },
                    onProgress = { msg, p -> _ui.update { it.copy(progressText = msg, progress = p) } },
                    onResult = { r -> _ui.update { it.copy(results = it.results + r) } },
                )
            } finally {
                _ui.update { it.copy(running = false) }
            }
        }
    }

    /** 100 dictées d'affilée avec le modèle actif (fuites mémoire). */
    fun runEndurance() {
        if (job?.isActive == true) return
        val cfg = app.dictationConfig()
        val clipList = clips.value
        if (cfg == null || clipList.isEmpty()) {
            _ui.update { it.copy(message = "Il faut un modèle téléchargé et au moins un clip.") }
            return
        }
        _ui.update { it.copy(running = true, endurance = null, message = null, progress = 0f) }
        job = viewModelScope.launch {
            app.engines.release()
            val llm = app.cleanerModelId()?.takeIf { _ui.value.clean }
            val threads = app.prefs.llmThreads
            try {
                val r = runner.endurance(
                    cfg, clipList, 100, app.vad(), app.prefs.vadMode, app.prefs.language,
                    llm?.let { id -> { text: String -> app.cleaners.clean(id, threads, text) } },
                ) { msg, p -> _ui.update { it.copy(progressText = msg, progress = p) } }
                _ui.update { it.copy(endurance = r) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.update { it.copy(message = "Endurance : ${e.message}") }
            } finally {
                _ui.update { it.copy(running = false) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _ui.update { it.copy(running = false, progressText = "Annulé") }
    }

    fun report(): String = BenchReport.build(app, lastPlan, _ui.value.results, clips.value, _ui.value.endurance)

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
        val source = recorder.lastReport.value?.config?.label
        viewModelScope.launch(Dispatchers.IO) { app.clips.add(audio, reference, source) }
    }

    fun importWav(uri: Uri, reference: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.clips.import(uri, reference) }
                .onFailure { e -> _ui.update { it.copy(message = "Import impossible : ${e.message}") } }
        }
    }

    fun share(context: android.content.Context, clip: BenchClip) = shareWav(context, app.clips.file(clip))

    fun play(clip: BenchClip) {
        viewModelScope.launch(Dispatchers.IO) { AudioPlayer.play(app.clips.samples(clip)) }
    }

    fun delete(clip: BenchClip) = app.clips.delete(clip.id)
    fun deleteAll() = app.clips.deleteAll()
    fun updateReference(clip: BenchClip, ref: String?) = app.clips.updateReference(clip.id, ref)
    fun dismissMessage() = _ui.update { it.copy(message = null) }
}
