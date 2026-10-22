package com.karelisio.murmure.stt

import android.os.SystemClock
import com.karelisio.murmure.models.ModelManager
import com.karelisio.murmure.util.AppLog
import com.karelisio.murmure.util.MemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Garde un seul moteur chargé (« warm ») : le modèle est lu une fois, puis réutilisé
 * pour toutes les dictées tant que la configuration ne change pas.
 */
class EngineHolder(private val models: ModelManager) {
    private val mutex = Mutex()
    private var engine: SttEngine? = null

    var lastLoadMs: Long = 0
        private set

    suspend fun <T> use(config: EngineConfig, block: (SttEngine) -> T): T = mutex.withLock {
        withContext(Dispatchers.Default) { block(obtain(config)) }
    }

    /** Précharge le moteur pour que la première dictée ne paie pas le chargement. */
    suspend fun warmUp(config: EngineConfig) = mutex.withLock {
        withContext(Dispatchers.Default) { obtain(config) }
    }

    private fun obtain(config: EngineConfig): SttEngine {
        engine?.let { if (it.config == config) return it }
        closeCurrent()
        val rss0 = MemInfo.rssMb()
        val t0 = SystemClock.elapsedRealtime()
        val e = EngineFactory.create(models, config)
        lastLoadMs = SystemClock.elapsedRealtime() - t0
        AppLog.i("Engine", "chargé ${config.label()} en $lastLoadMs ms, RSS +${MemInfo.rssMb() - rss0} Mo")
        engine = e
        return e
    }

    suspend fun release() = mutex.withLock { closeCurrent() }

    private fun closeCurrent() {
        engine?.let {
            it.close()
            AppLog.i("Engine", "libéré ${it.config.label()}")
        }
        engine = null
    }

    val loadedConfig: EngineConfig? get() = engine?.config
}
