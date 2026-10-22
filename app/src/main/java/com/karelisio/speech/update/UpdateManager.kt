package com.karelisio.speech.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.karelisio.speech.BuildConfig
import com.karelisio.speech.util.AppLog
import com.karelisio.speech.util.sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val commit: String,
    val notes: String,
    val apkSha256: String,
    val apkSize: Long,
    val apkUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val done: Long, val total: Long) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Mise à jour in-app depuis la release GitHub « dev-latest » : lecture de version.json,
 * téléchargement de l'APK, vérification SHA-256, installation via PackageInstaller.
 * Uniquement à la demande de l'utilisateur (aucun appel réseau automatique).
 */
class UpdateManager(private val context: Context, private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "Update"
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var job: Job? = null
    private val apkFile get() = File(context.cacheDir, "update/speech.apk")

    val currentVersion: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})"

    fun check() {
        if (job?.isActive == true) return
        _state.value = UpdateState.Checking
        job = scope.launch(Dispatchers.IO) {
            _state.value = try {
                val json = JSONObject(get(BuildConfig.UPDATE_URL).use { it.inputStream.bufferedReader().readText() })
                val info = UpdateInfo(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.getString("versionName"),
                    commit = json.optString("commit"),
                    notes = json.optString("notes"),
                    apkSha256 = json.getString("apkSha256").lowercase(),
                    apkSize = json.optLong("apkSize"),
                    apkUrl = BuildConfig.UPDATE_URL.substringBeforeLast('/') + "/speech.apk",
                )
                AppLog.i(TAG, "dernière version ${info.versionName} (${info.commit}), installée ${currentVersion}")
                if (info.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(info) else UpdateState.UpToDate
            } catch (e: Exception) {
                AppLog.e(TAG, "vérification impossible", e)
                UpdateState.Failed("Vérification impossible : ${e.message}")
            }
        }
    }

    fun download(info: UpdateInfo) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val f = apkFile.apply { parentFile?.mkdirs(); delete() }
                get(info.apkUrl).use { conn ->
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
                    var done = 0L
                    f.outputStream().use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                _state.value = UpdateState.Downloading(info, done, total)
                            }
                        }
                    }
                }
                if (sha256(f) != info.apkSha256) {
                    f.delete()
                    throw IOException("checksum de l'APK invalide")
                }
                _state.value = UpdateState.ReadyToInstall(info)
                AppLog.i(TAG, "APK ${info.versionName} téléchargé et vérifié")
            } catch (e: Exception) {
                AppLog.e(TAG, "téléchargement impossible", e)
                _state.value = UpdateState.Failed("Téléchargement impossible : ${e.message}")
            }
        }
    }

    /** Android exige que l'utilisateur autorise Speech à installer des apps. */
    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install() {
        val f = apkFile
        if (!f.exists()) return
        _state.value = UpdateState.Installing
        scope.launch(Dispatchers.IO) {
            try {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("speech.apk", 0, f.length()).use { out ->
                        f.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val pending = PendingIntent.getBroadcast(
                        context, id, Intent(context, UpdateReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    withContext(Dispatchers.Main) { session.commit(pending.intentSender) }
                }
                AppLog.i(TAG, "session d'installation $id envoyée")
            } catch (e: Exception) {
                AppLog.e(TAG, "installation impossible", e)
                _state.value = UpdateState.Failed("Installation impossible : ${e.message}")
            }
        }
    }

    internal fun onInstallFailed(message: String) {
        _state.value = UpdateState.Failed(message)
    }

    private fun get(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true // github.com → objects.githubusercontent.com (https → https)
        c.setRequestProperty("User-Agent", "Speech/${BuildConfig.VERSION_NAME}")
        c.setRequestProperty("Cache-Control", "no-cache")
        val code = c.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect()
            throw IOException("HTTP $code")
        }
        return c
    }
}

private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
    try { block(this) } finally { disconnect() }
