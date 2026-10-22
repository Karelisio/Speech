package com.karelisio.speech

import android.app.Application
import com.karelisio.speech.audio.AudioRecorder
import com.karelisio.speech.bench.BenchClips
import com.karelisio.speech.dictation.DictationController
import com.karelisio.speech.overlay.BubbleState
import com.karelisio.speech.stt.EngineConfig
import com.karelisio.speech.update.UpdateManager
import com.karelisio.speech.models.ModelManager
import com.karelisio.speech.stt.EngineHolder
import com.karelisio.speech.util.AppLog
import com.karelisio.speech.util.DeviceInfo
import com.karelisio.speech.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    val recorder = AudioRecorder()
    lateinit var bubble: BubbleState
        private set
    lateinit var controller: DictationController
        private set
    lateinit var updates: UpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        prefs = Prefs(this)
        models = ModelManager(this, appScope)
        engines = EngineHolder(models)
        clips = BenchClips(this)
        bubble = BubbleState(prefs)
        controller = DictationController(this)
        updates = UpdateManager(this, appScope)
        AppLog.i("App", "démarrage\n" + DeviceInfo.summary(this))
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
        )
    }
}
