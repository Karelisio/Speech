package com.karelisio.speech.clean

import android.os.SystemClock
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

    var lastLoadMs: Long = 0
        private set

    val loadedModelId: String? get() = cleaner?.modelId

    private fun obtain(modelId: String, nThreads: Int): TextCleaner {
        cleaner?.let { if (it.modelId == modelId && threads == nThreads) return it }
        cleaner?.close()
        cleaner = null
        val info = requireNotNull(models.find(modelId)) { "Modèle inconnu : $modelId" }
        check(models.isReady(modelId)) { "Modèle non téléchargé : ${info.name}" }
        val rss0 = MemInfo.rssMb()
        val t0 = SystemClock.elapsedRealtime()
        val c = LlamaCleaner(File(models.dir(info), info.files.first().path), modelId, nThreads)
        c.warm()
        lastLoadMs = SystemClock.elapsedRealtime() - t0
        AppLog.i("Clean", "chargé $modelId ($nThreads t) + prompt système en $lastLoadMs ms, RSS +${MemInfo.rssMb() - rss0} Mo")
        cleaner = c
        threads = nThreads
        return c
    }

    suspend fun clean(modelId: String, nThreads: Int, raw: String): CleanResult = mutex.withLock {
        withContext(Dispatchers.Default) { obtain(modelId, nThreads).clean(raw) }
    }

    /** Chargement + prompt système en tâche de fond (ex. au début de l'enregistrement). */
    fun warmUpAsync(modelId: String, nThreads: Int) {
        if (cleaner?.modelId == modelId && threads == nThreads) return
        scope.launch {
            mutex.withLock {
                withContext(Dispatchers.Default) {
                    runCatching { obtain(modelId, nThreads) }.onFailure { AppLog.e("Clean", "préchargement impossible", it) }
                }
            }
        }
    }

    suspend fun release() = mutex.withLock {
        cleaner?.close()
        cleaner = null
    }
}
