package com.karelisio.murmure

import android.app.Application
import com.karelisio.murmure.audio.AudioRecorder
import com.karelisio.murmure.bench.BenchClips
import com.karelisio.murmure.models.ModelManager
import com.karelisio.murmure.stt.EngineHolder
import com.karelisio.murmure.util.AppLog
import com.karelisio.murmure.util.DeviceInfo
import com.karelisio.murmure.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MurmureApp : Application() {
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
