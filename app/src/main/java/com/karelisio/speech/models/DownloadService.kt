package com.karelisio.speech.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Garde le process en vie (et le Wi-Fi éveillé) pendant les téléchargements de modèles. */
class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 2

        fun intent(context: Context) = Intent(context, DownloadService::class.java)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Téléchargements", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Préparation…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "speech:download").apply { acquire(3 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "speech:download").apply { acquire() }

        val models = (application as SpeechApp).models
        scope.launch {
            models.status.collectLatest { map ->
                if (!models.hasActiveDownloads()) {
                    stopSelf()
                    return@collectLatest
                }
                val (id, st) = map.entries.firstOrNull { it.value is ModelStatus.Downloading }?.toPair()
                    ?: map.entries.first { it.value is ModelStatus.Queued || it.value is ModelStatus.Verifying }.toPair()
                val name = models.find(id)?.name ?: id
                val n = when (st) {
                    is ModelStatus.Downloading -> notification(
                        "$name — ${st.done / 1_000_000} / ${st.total / 1_000_000} Mo",
                        (st.done / 1_000_000).toInt(), (st.total / 1_000_000).toInt(),
                    )
                    ModelStatus.Verifying -> notification("$name — vérification du checksum", 0, 0)
                    else -> notification("$name — en attente", 0, 0)
                }
                nm.notify(NOTIF_ID, n)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun notification(text: String, progress: Int, max: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Téléchargement des modèles")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(max, progress, max == 0)
            .build()

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
