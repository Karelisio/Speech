package com.karelisio.speech.clean

import android.os.SystemClock
import com.karelisio.speech.llm.LlamaApi
import com.karelisio.speech.llm.LlamaNative
import com.karelisio.speech.llm.LlamaNativeGpu
import com.karelisio.speech.models.ModelManager
import com.karelisio.speech.util.AppLog
import com.karelisio.speech.util.MemInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Garde un nettoyeur chargé et son prompt système en cache, comme [com.karelisio.speech.stt.EngineHolder]. */
class CleanerHolder(private val models: ModelManager, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var cleaner: TextCleaner? = null
    private var threads = 0
    private var gpu = false

    /** Accélérateur demandé pour le nettoyage (Réglages) : GPU OpenCL si disponible. */
    @Volatile
    var useGpu: Boolean = false

    /** Accélérateur réellement utilisé par le modèle chargé. */
    val backendName: String? get() = (cleaner as? LlamaCleaner)?.api?.name

    /** Dictionnaire personnel courant, inclus dans le prompt système. */
    @Volatile
    var terms: List<String> = emptyList()

    var lastLoadMs: Long = 0
        private set

    val loadedModelId: String? get() = cleaner?.modelId

    private fun obtain(modelId: String, nThreads: Int): TextCleaner {
        cleaner?.let { if (it.modelId == modelId && threads == nThreads && gpu == useGpu) return it }
        cleaner?.close()
        cleaner = null
        val info = requireNotNull(models.find(modelId)) { "Modèle inconnu : $modelId" }
        check(models.isReady(modelId)) { "Modèle non téléchargé : ${info.name}" }
        val rss0 = MemInfo.rssMb()
        val t0 = SystemClock.elapsedRealtime()
        val api: LlamaApi = if (useGpu && LlamaNativeGpu.available) LlamaNativeGpu else {
            if (useGpu) AppLog.w("Clean", "GPU indisponible (${LlamaNativeGpu.loadError}), repli sur le CPU")
            LlamaNative
        }
        val c = LlamaCleaner(File(models.dir(info), info.files.first().path), modelId, nThreads, CleanPrompt.build(terms), api)
        c.warm()
        lastLoadMs = SystemClock.elapsedRealtime() - t0
        AppLog.i("Clean", "chargé $modelId (${api.name}, $nThreads t) + prompt système en $lastLoadMs ms, RSS +${MemInfo.rssMb() - rss0} Mo")
        cleaner = c
        threads = nThreads
        gpu = useGpu
        return c
    }

    suspend fun clean(modelId: String, nThreads: Int, raw: String, terms: List<String> = this.terms): CleanResult =
        mutex.withLock {
            withContext(Dispatchers.Default) {
                obtain(modelId, nThreads).apply { system = CleanPrompt.build(terms) }.clean(raw)
            }
        }

    /** Chargement + prompt système en tâche de fond (ex. au début de l'enregistrement). */
    fun warmUpAsync(modelId: String, nThreads: Int) {
        if (cleaner?.modelId == modelId && threads == nThreads && gpu == useGpu) return
        scope.launch {
            mutex.withLock {
                withContext(Dispatchers.Default) {
                    runCatching { obtain(modelId, nThreads) }.onFailure { AppLog.e("Clean", "préchargement impossible", it) }
                }
            }
        }
    }

    /** Pré-calcul du début de la dictée ; ignoré si le modèle est occupé (jamais bloquant). */
    fun prefillAsync(modelId: String, nThreads: Int, partial: String, terms: List<String> = this.terms) {
        scope.launch {
            if (!mutex.tryLock()) return@launch
            try {
                withContext(Dispatchers.Default) {
                    runCatching { obtain(modelId, nThreads).apply { system = CleanPrompt.build(terms) }.prefill(partial) }
                }
            } finally {
                mutex.unlock()
            }
        }
    }

    suspend fun release() = mutex.withLock {
        cleaner?.close()
        cleaner = null
    }
}
