package com.karelisio.speech

import android.app.Application
import com.karelisio.speech.audio.AudioRecorder
import com.karelisio.speech.bench.BenchClips
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

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        prefs = Prefs(this)
        models = ModelManager(this, appScope)
        engines = EngineHolder(models)
        clips = BenchClips(this)
        AppLog.i("App", "démarrage\n" + DeviceInfo.summary(this))
    }
}
