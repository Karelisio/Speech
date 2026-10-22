package com.karelisio.speech

import android.app.Application
import com.karelisio.speech.audio.AudioRecorder
import com.karelisio.speech.bench.BenchClips
import com.karelisio.speech.clean.CleanerHolder
import com.karelisio.speech.dictation.DictationController
import com.karelisio.speech.dictation.DictationPipeline
import com.karelisio.speech.overlay.BubbleState
import com.karelisio.speech.stt.EngineConfig
import com.karelisio.speech.text.Dictionary
import com.karelisio.speech.text.History
import com.karelisio.speech.update.UpdateManager
import com.karelisio.speech.vad.VadProcessor
import com.karelisio.speech.models.ModelManager
import com.karelisio.speech.stt.EngineHolder
import com.karelisio.speech.util.AppLog
import com.karelisio.speech.util.DeviceInfo
import com.karelisio.speech.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import com.karelisio.speech.ui.theme.ThemeMode

class SpeechApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var prefs: Prefs
        private set
    lateinit var models: ModelManager
        private set
    lateinit var engines: EngineHolder
        private set
    lateinit var clips: BenchClips
        private set
    /** Un seul enregistreur partagé : le micro n'est jamais ouvert deux fois. */
    val recorder = AudioRecorder { prefs.captureConfig() }
    lateinit var bubble: BubbleState
        private set
    lateinit var controller: DictationController
        private set
    lateinit var updates: UpdateManager
        private set
    lateinit var cleaners: CleanerHolder
        private set
    /** Nettoyage IA activé (réglage global, bascule rapide par double tap sur la bulle). */
    val cleaning = MutableStateFlow(true)

    lateinit var history: History
        private set
    /** Dernière activité de dictée (libération de la RAM après inactivité). */
    @Volatile
    var lastActivityAt: Long = android.os.SystemClock.elapsedRealtime()

    /** Pipeline de dictée partagé (bulle et essai rapide). */
    lateinit var pipeline: DictationPipeline
        private set
    /** Dictionnaire personnel (relu à chaque modification). */
    @Volatile
    var dictionary: Dictionary = Dictionary(emptyList())
        private set

    fun setDictionary(raw: String) {
        prefs.dictionary = raw
        dictionary = Dictionary.parse(raw)
        cleaners.terms = dictionary.terms
    }

    /** Thème choisi dans Réglages → Apparence. */
    val appearance = MutableStateFlow(Appearance(ThemeMode.SYSTEM, true))

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        prefs = Prefs(this)
        models = ModelManager(this, appScope)
        engines = EngineHolder(models)
        clips = BenchClips(this)
        bubble = BubbleState(prefs)
        updates = UpdateManager(this, appScope)
        appearance.value = Appearance(prefs.themeMode, prefs.dynamicColor)
        cleaners = CleanerHolder(models, appScope)
        cleaning.value = prefs.cleaningEnabled
        history = History(this)
        dictionary = Dictionary.parse(prefs.dictionary)
        cleaners.terms = dictionary.terms
        cleaners.useGpu = prefs.llmGpu
        pipeline = DictationPipeline(this, appScope)
        controller = DictationController(this)
        AppLog.i("App", "démarrage\n" + DeviceInfo.summary(this))
    }

    private var vadProcessor: VadProcessor? = null

    /** VAD Silero, chargé à la première utilisation (≈ 2 Mo) ; null tant que le modèle n'est pas téléchargé. */
    @Synchronized
    fun vad(): VadProcessor? {
        vadProcessor?.let { return it }
        val m = models.vadModel?.takeIf { models.isReady(it.id) } ?: return null
        return runCatching { VadProcessor(java.io.File(models.dir(m), m.files.first().path)) }
            .onFailure { AppLog.e("VAD", "chargement impossible", it) }
            .getOrNull()
            ?.also { vadProcessor = it }
    }

    /** Fichier du VAD Silero s'il est téléchargé. */
    fun vadModelFile(): java.io.File? =
        models.vadModel?.takeIf { models.isReady(it.id) }?.let { java.io.File(models.dir(it), it.files.first().path) }

    /** Modèle de nettoyage à utiliser (choisi, sinon le premier téléchargé) ; null si aucun. */
    fun cleanerModelId(): String? =
        prefs.activeLlmId?.takeIf { models.isReady(it) } ?: models.readyLlms().firstOrNull()?.id

    /** Nettoyage effectif : activé et modèle disponible. */
    fun cleaningActive(): Boolean = cleaning.value && cleanerModelId() != null

    fun setCleaning(enabled: Boolean) {
        prefs.cleaningEnabled = enabled
        cleaning.value = enabled
    }

    /** Configuration du moteur de dictée : modèle actif (Parakeet par défaut) et threads. */
    fun dictationConfig(): EngineConfig? {
        val id = prefs.activeModelId?.takeIf { models.isReady(it) }
            ?: models.readyModels().firstOrNull()?.id
            ?: return null
        return EngineConfig(
            modelId = id,
            threads = prefs.effectiveThreads(),
            whisperDynamicAudioCtx = prefs.whisperDynamicAudioCtx,
            provider = if (models.find(id)?.engine == com.karelisio.speech.models.EngineKind.PARAKEET) prefs.sttProvider else "cpu",
        )
    }
}

data class Appearance(val mode: ThemeMode, val dynamicColor: Boolean)
