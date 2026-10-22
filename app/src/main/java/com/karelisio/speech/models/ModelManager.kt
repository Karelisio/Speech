package com.karelisio.speech.models

import android.content.Context
import androidx.core.content.ContextCompat
import com.karelisio.speech.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface ModelStatus {
    data object Absent : ModelStatus
    data object Queued : ModelStatus
    data class Downloading(val done: Long, val total: Long) : ModelStatus
    data object Verifying : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

/**
 * Téléchargement des modèles depuis Hugging Face vers `filesDir/models/<id>/`.
 * Reprise par en-tête Range sur un fichier `.part`, puis vérification SHA-256.
 */
class ModelManager(private val context: Context, private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "Models"
        private const val READY_MARKER = ".complete"
        private const val BUF = 256 * 1024
    }

    val catalog: List<ModelInfo> =
        ModelCatalog.parse(context.assets.open("models.json").bufferedReader().use { it.readText() })

    private val root = File(context.filesDir, "models")
    private val _status = MutableStateFlow(catalog.associate { it.id to initialStatus(it) })
    val status: StateFlow<Map<String, ModelStatus>> = _status

    init {
        // Modèles retirés du catalogue (ex. whisper q5_0) : on libère la place.
        root.listFiles()?.filter { d -> d.isDirectory && catalog.none { it.id == d.name } }?.forEach {
            AppLog.i(TAG, "suppression du modèle obsolète ${it.name}")
            it.deleteRecursively()
        }
    }

    private val jobs = mutableMapOf<String, Job>()
    private val queue = Mutex() // un téléchargement à la fois : toute la bande passante pour le premier

    fun find(id: String): ModelInfo? = catalog.firstOrNull { it.id == id }
    fun dir(model: ModelInfo) = File(root, model.id)
    fun isReady(id: String) = _status.value[id] == ModelStatus.Ready
    fun readyModels() = catalog.filter { isReady(it.id) }

    fun sizeOnDisk(model: ModelInfo): Long =
        dir(model).listFiles()?.sumOf { it.length() } ?: 0L

    private fun initialStatus(m: ModelInfo): ModelStatus =
        if (File(dir(m), READY_MARKER).exists()) ModelStatus.Ready else ModelStatus.Absent

    private fun set(id: String, s: ModelStatus) = _status.update { it + (id to s) }

    fun hasActiveDownloads() = _status.value.values.any {
        it is ModelStatus.Queued || it is ModelStatus.Downloading || it is ModelStatus.Verifying
    }

    @Synchronized
    fun download(id: String) {
        val model = find(id) ?: return
        if (jobs[id]?.isActive == true || isReady(id)) return
        set(id, ModelStatus.Queued)
        ContextCompat.startForegroundService(context, DownloadService.intent(context))
        jobs[id] = scope.launch(Dispatchers.IO) {
            queue.withLock {
                try {
                    downloadModel(model)
                    set(id, ModelStatus.Ready)
                    AppLog.i(TAG, "${model.id} prêt (${sizeOnDisk(model) / 1_000_000} Mo)")
                } catch (e: CancellationException) {
                    set(id, ModelStatus.Absent)
                    throw e
                } catch (e: Exception) {
                    AppLog.e(TAG, "échec du téléchargement de ${model.id}", e)
                    set(id, ModelStatus.Failed(e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    @Synchronized
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        set(id, ModelStatus.Absent)
    }

    fun delete(id: String) {
        val model = find(id) ?: return
        cancel(id)
        dir(model).deleteRecursively()
        set(id, ModelStatus.Absent)
        AppLog.i(TAG, "${model.id} supprimé")
    }

    private suspend fun downloadModel(model: ModelInfo) {
        val dir = dir(model).apply { mkdirs() }
        File(dir, READY_MARKER).delete()
        val sizes = model.files.map { it.size }.toLongArray()
        val done = LongArray(model.files.size)
        fun report() = set(model.id, ModelStatus.Downloading(done.sum(), sizes.sum()))

        model.files.forEachIndexed { i, f ->
            val dest = File(dir, f.path)
            if (dest.exists() && (f.size == 0L || dest.length() == f.size)) {
                done[i] = dest.length()
                if (sizes[i] == 0L) sizes[i] = done[i]
                report()
                return@forEachIndexed
            }
            val part = File(dir, f.path + ".part")
            fetch(model.url(f), part, f.size) { have, total ->
                done[i] = have
                if (sizes[i] == 0L && total > 0) sizes[i] = total
                report()
            }
            set(model.id, ModelStatus.Verifying)
            val expected = f.sha256
            if (expected != null) {
                val actual = sha256(part)
                if (actual != expected) {
                    part.delete()
                    throw IOException("Checksum invalide pour ${f.path}")
                }
            } else {
                AppLog.w(TAG, "${f.path} : pas de SHA-256 dans le catalogue, vérification de la taille seulement")
            }
            if (f.size > 0 && part.length() != f.size) throw IOException("Taille inattendue pour ${f.path}")
            check(part.renameTo(dest)) { "Renommage impossible : ${dest.name}" }
        }
        File(dir, READY_MARKER).writeText(model.files.joinToString("\n") { "${it.path} ${it.sha256 ?: "?"}" })
    }

    private suspend fun fetch(url: String, part: File, expectedSize: Long, progress: (Long, Long) -> Unit) {
        var have = part.length()
        if (expectedSize > 0 && have > expectedSize) { part.delete(); have = 0 }
        if (expectedSize > 0 && have == expectedSize) { progress(have, expectedSize); return }

        val conn = open(url, have)
        try {
            val code = conn.responseCode
            when {
                code == 416 && have > 0 -> { part.delete(); throw IOException("Reprise refusée, nouvel essai nécessaire") }
                code == HttpURLConnection.HTTP_PARTIAL -> AppLog.i(TAG, "reprise de ${part.name} à ${have / 1_000_000} Mo")
                code == HttpURLConnection.HTTP_OK -> have = 0
                else -> throw IOException("HTTP $code pour $url")
            }
            val total = if (expectedSize > 0) expectedSize else have + conn.contentLengthLong.coerceAtLeast(0)
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(have)
                out.seek(have)
                conn.inputStream.use { input ->
                    val buf = ByteArray(BUF)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have - lastReport > 2_000_000) { progress(have, total); lastReport = have }
                    }
                }
            }
            progress(have, total)
            if (total > 0 && have < total) throw IOException("Téléchargement interrompu (${have}/${total})")
        } finally {
            conn.disconnect()
        }
    }

    /** Suit les redirections à la main pour conserver l'en-tête Range (HF → CDN). */
    private fun open(url: String, from: Long): HttpURLConnection {
        var current = url
        repeat(10) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Speech/1.0")
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirection sans Location")
                current = URL(URL(current), loc).toString()
                c.disconnect()
            } else {
                return c
            }
        }
        throw IOException("Trop de redirections")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
