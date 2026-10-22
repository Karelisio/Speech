package com.karelisio.speech.dictation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.karelisio.speech.R
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.ui.MainActivity
import com.karelisio.speech.util.AppLog
import com.karelisio.speech.util.MemInfo
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Service de premier plan de type `microphone`. Démarré depuis l'app au premier plan
 * (règle Android 14+ sur l'accès micro en arrière-plan), il garde le droit d'utiliser le
 * micro et le modèle chargé tant que la bulle est active. Le micro n'est ouvert que
 * pendant un enregistrement.
 */
class DictationService : Service() {
    companion object {
        private const val CHANNEL = "dictation"
        private const val NOTIF_ID = 1
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_DISABLE = "disable"

        @Volatile
        var isRunning = false
            private set

        /** À appeler depuis une activité au premier plan. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DictationService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DictationService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as SpeechApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Service de dictée", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIF_ID, notification(paused = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        isRunning = true
        AppLog.i("Service", "service de dictée démarré")
        scope.launch {
            combine(app.bubble.paused, app.controller.state) { p, s -> p to s }.collect { (paused, _) ->
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(paused))
            }
        }
        // AudioRecord créé d'avance : le micro s'ouvre sans délai au premier tap.
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) runCatching { app.recorder.prepare() }
        // Modèle chargé une fois et gardé en mémoire tant que le service tourne.
        scope.launch { app.dictationConfig()?.let { runCatching { app.engines.warmUp(it) } } }
        // Option « libérer la RAM » : modèles déchargés après N minutes sans dictée.
        scope.launch {
            while (true) {
                delay(60_000)
                val minutes = app.prefs.releaseAfterMin
                if (minutes <= 0 || app.recorder.isRecording) continue
                val idle = SystemClock.elapsedRealtime() - app.lastActivityAt
                if (idle < minutes * 60_000L) continue
                if (app.engines.loadedConfig == null && app.cleaners.loadedModelId == null) continue
                val before = MemInfo.rssMb()
                app.engines.release()
                app.cleaners.release()
                System.gc()
                AppLog.i("Service", "RAM libérée après $minutes min d'inactivité : $before → ${MemInfo.rssMb()} Mo")
            }
        }
        // Idem pour le nettoyage IA : modèle chargé et prompt système déjà en cache KV.
        if (app.cleaningActive()) app.cleaners.warmUpAsync(app.cleanerModelId()!!, app.prefs.llmThreads)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { AppLog.i("Service", "action notification : $it") }
        when (intent?.action) {
            ACTION_PAUSE -> app.bubble.paused.value = true
            ACTION_RESUME -> app.bubble.paused.value = false
            ACTION_DISABLE -> {
                app.bubble.setEnabled(false)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun action(action: String) = PendingIntent.getService(
        this, action.hashCode(), Intent(this, DictationService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(paused: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (paused) "Speech en pause" else "Speech prêt")
            .setContentText(if (paused) "Bulle masquée" else "Touchez la bulle dans un champ de texte pour dicter")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, if (paused) "Reprendre" else "Pause", action(if (paused) ACTION_RESUME else ACTION_PAUSE))
            .addAction(0, "Désactiver", action(ACTION_DISABLE))
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        app.controller.cancel()
        app.recorder.release()
        scope.cancel()
        AppLog.i("Service", "service de dictée arrêté")
        super.onDestroy()
    }
}

/**
 * Aligne le service micro sur l'état voulu. À appeler depuis une activité au premier plan :
 * c'est la seule situation où Android 14+ autorise un service `microphone` à démarrer.
 */
fun syncDictationService(context: Context) {
    val app = context.applicationContext as SpeechApp
    val micOk = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val wanted = app.bubble.enabled.value && micOk &&
        com.karelisio.speech.access.SpeechAccessibilityService.isEnabled(context)
    when {
        wanted && !DictationService.isRunning -> runCatching { DictationService.start(context) }
            .onFailure { AppLog.e("Service", "démarrage du service micro refusé", it) }
        !wanted && DictationService.isRunning -> {
            val why = when {
                !app.bubble.enabled.value -> "bulle désactivée"
                !micOk -> "micro non autorisé"
                else -> "accessibilité désactivée"
            }
            AppLog.i("Service", "arrêt demandé : $why")
            DictationService.stop(context)
        }
    }
}
