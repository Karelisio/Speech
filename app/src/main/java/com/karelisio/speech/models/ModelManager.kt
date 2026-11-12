package com.karelisio.speech.models

import android.content.Context
import androidx.core.content.ContextCompat
import com.karelisio.speech.util.AppLog
import com.karelisio.speech.util.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
        /** Essais par fichier : une coupure réseau reprend où elle s'était arrêtée. */
        private const val ATTEMPTS = 5
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
    /** Modèles de reconnaissance vocale prêts (hors VAD). */
    fun readyModels() = catalog.filter { it.isStt && isReady(it.id) }

    val vadModel: ModelInfo? get() = catalog.firstOrNull { it.engine == EngineKind.VAD }

    /** Modèles de langage (nettoyage) prêts. */
    fun readyLlms() = catalog.filter { it.engine == EngineKind.LLM && isReady(it.id) }

    /** Le VAD (643 Ko) accompagne tout modèle de reconnaissance ; à appeler depuis le premier plan. */
    fun ensureVad() {
        val vad = vadModel ?: return
        if (readyModels().isNotEmpty() && _status.value[vad.id] is ModelStatus.Absent) download(vad.id)
    }

    fun sizeOnDisk(model: ModelInfo): Long =
        dir(model).listFiles()?.sumOf { it.length() } ?: 0L

    private fun markerText(m: ModelInfo) = m.files.joinToString("\n") { "${it.path} ${it.sha256 ?: "?"}" }

    /**
     * Prêt seulement si le marqueur correspond au catalogue actuel (même fichiers, même SHA-256)
     * et que les fichiers sont là : une mise à jour du catalogue ne laisse pas d'anciens poids.
     * Un « ? » (ancien catalogue sans SHA) est accepté pour ne pas forcer de re-téléchargement.
     */
    private fun initialStatus(m: ModelInfo): ModelStatus {
        val lines = runCatching { File(dir(m), READY_MARKER).readLines() }.getOrNull() ?: return ModelStatus.Absent
        val ok = lines.size == m.files.size && m.files.zip(lines).all { (f, line) ->
            val (path, sha) = line.split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "?" } }
            path == f.path && (sha == "?" || f.sha256 == null || sha == f.sha256) && File(dir(m), f.path).exists()
        }
        if (!ok) AppLog.w(TAG, "${m.id} : marqueur périmé ou fichiers manquants, à retélécharger")
        return if (ok) ModelStatus.Ready else ModelStatus.Absent
    }

    private fun set(id: String, s: ModelStatus) = _status.update { it + (id to s) }

    fun hasActiveDownloads() = _status.value.values.any {
        it is ModelStatus.Queued || it is ModelStatus.Downloading || it is ModelStatus.Verifying
    }

    @Synchronized
    fun download(id: String) {
        val model = find(id) ?: return
        if (jobs[id]?.isActive == true || isReady(id)) return
        set(id, ModelStatus.Queued)
        runCatching { ContextCompat.startForegroundService(context, DownloadService.intent(context)) }
            .onFailure { AppLog.w(TAG, "service de téléchargement non démarré (arrière-plan)", it) }
        // Le VAD suit automatiquement le premier modèle de reconnaissance.
        if (model.isStt) vadModel?.takeIf { _status.value[it.id] is ModelStatus.Absent }?.let { vad ->
            set(vad.id, ModelStatus.Queued)
            jobs[vad.id] = scope.launch(Dispatchers.IO) { queue.withLock { runDownload(vad) } }
        }
        jobs[id] = scope.launch(Dispatchers.IO) { queue.withLock { runDownload(model) } }
    }

    private suspend fun runDownload(model: ModelInfo) {
        val me = currentCoroutineContext()[Job]
        // Un job annulé puis remplacé (Annuler → Télécharger) ne doit pas écraser le statut du nouveau.
        fun setIfCurrent(s: ModelStatus) = synchronized(this) { if (jobs[model.id] === me) set(model.id, s) }
        try {
            downloadModel(model)
            setIfCurrent(ModelStatus.Ready)
            AppLog.i(TAG, "${model.id} prêt (${sizeOnDisk(model) / 1_000_000} Mo)")
        } catch (e: CancellationException) {
            throw e // cancel() a déjà remis le statut à Absent
        } catch (e: Exception) {
            AppLog.e(TAG, "échec du téléchargement de ${model.id}", e)
            setIfCurrent(ModelStatus.Failed(e.message ?: e.javaClass.simpleName))
        } finally {
            synchronized(this) { if (jobs[model.id] === me) jobs.remove(model.id) }
        }
    }

    /** Annule et efface la partie déjà téléchargée (sinon la place n'est jamais rendue). */
    @Synchronized
    fun cancel(id: String) {
        val job = jobs.remove(id)
        job?.cancel()
        set(id, ModelStatus.Absent)
        val model = find(id) ?: return
        if (job != null) scope.launch(Dispatchers.IO) {
            job.join()
            dir(model).listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        }
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
            fetchWithRetry(model.url(f), part, f.size) { have, total ->
                done[i] = have
                if (sizes[i] == 0L && total > 0) sizes[i] = total
                report()
            }
            set(model.id, ModelStatus.Verifying)
            val expected = f.sha256
            if (expected != null) {
                val ctx = currentCoroutineContext()
                val actual = sha256(part) { ctx.ensureActive() }
                if (actual != expected) {
                    part.delete()
                    throw IOException("Checksum invalide pour ${f.path}")
                }
            } else {
                AppLog.w(TAG, "${f.path} : pas de SHA-256 dans le catalogue, vérification de la taille seulement")
            }
            if (f.size > 0 && part.length() != f.size) throw IOException("Taille inattendue pour ${f.path}")
            currentCoroutineContext().ensureActive() // annulé pendant la vérification : rien n'est validé
            check(part.renameTo(dest)) { "Renommage impossible : ${dest.name}" }
        }
        currentCoroutineContext().ensureActive()
        File(dir, READY_MARKER).writeText(markerText(model))
    }

    private suspend fun fetchWithRetry(url: String, part: File, expectedSize: Long, progress: (Long, Long) -> Unit) {
        var attempt = 1
        while (true) {
            val before = part.length()
            try {
                return fetch(url, part, expectedSize, progress)
            } catch (e: IOException) {
                if (part.length() > before) attempt = 1 // l'essai a avancé : nouveau crédit d'essais
                if (attempt >= ATTEMPTS) throw e
                val wait = 2_000L shl (attempt - 1)
                AppLog.w(TAG, "${part.name} : ${e.message} — reprise dans ${wait / 1000} s (essai ${attempt + 1}/$ATTEMPTS)")
                delay(wait)
                attempt++
            }
        }
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
}
