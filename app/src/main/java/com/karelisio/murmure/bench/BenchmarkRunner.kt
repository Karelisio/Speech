package com.karelisio.murmure.bench

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.karelisio.murmure.models.ModelManager
import com.karelisio.murmure.stt.EngineConfig
import com.karelisio.murmure.stt.EngineFactory
import com.karelisio.murmure.stt.SttEngine
import com.karelisio.murmure.stt.SttRequest
import com.karelisio.murmure.util.AppLog
import com.karelisio.murmure.util.MemInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class ClipResult(
    val clipId: String,
    val durationMs: Long,
    val runsMs: List<Long>,
    val text: String,
    val encodeMs: Float?,
    val wer: Wer.Score?,
) {
    val medianMs: Long get() = runsMs.sorted()[runsMs.size / 2]
}

data class ConfigResult(
    val config: EngineConfig,
    val modelName: String,
    val loadMs: Long = 0,
    val rssBeforeMb: Long = 0,
    val rssLoadedMb: Long = 0,
    val rssPeakMb: Long = 0,
    val rssAfterCloseMb: Long = 0,
    val thermalStart: Int = 0,
    val thermalEnd: Int = 0,
    val clips: List<ClipResult> = emptyList(),
    val error: String? = null,
) {
    val totalAudioMs get() = clips.sumOf { it.durationMs }
    val totalProcMs get() = clips.sumOf { it.medianMs }
    val rtf: Double get() = if (totalAudioMs == 0L) 0.0 else totalProcMs.toDouble() / totalAudioMs
    val meanLatencyMs: Long get() = if (clips.isEmpty()) 0 else totalProcMs / clips.size
    /** Latence projetée pour une dictée de 10 s traitée en un bloc. */
    val latency10sMs: Long get() = (rtf * 10_000).toLong()
    val wer: Wer.Score?
        get() {
            val scored = clips.mapNotNull { it.wer }
            if (scored.isEmpty()) return null
            return Wer.Score(scored.sumOf { it.errors }, scored.sumOf { it.refWords })
        }
}

data class BenchPlan(
    val configs: List<EngineConfig>,
    val clips: List<BenchClip>,
    val repetitions: Int = 1,
    val language: String = "fr",
    /** Pause entre configurations pour limiter l'effet de la chauffe. */
    val cooldownMs: Long = 2_000,
)

/** Exécute un [BenchPlan] séquentiellement ; transcription d'un bloc complet par clip. */
class BenchmarkRunner(
    private val context: Context,
    private val models: ModelManager,
    private val clipStore: BenchClips,
) {
    suspend fun run(
        plan: BenchPlan,
        onProgress: (String, Float) -> Unit,
        onResult: (ConfigResult) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val audio = plan.clips.associate { it.id to clipStore.samples(it) }
        val steps = plan.configs.size * (plan.clips.size * plan.repetitions + 2)
        var step = 0
        fun tick(msg: String) = onProgress(msg, step++.toFloat() / steps)

        plan.configs.forEachIndexed { idx, cfg ->
            ensureActive()
            val name = models.find(cfg.modelId)?.name ?: cfg.modelId
            if (idx > 0 && plan.cooldownMs > 0) {
                onProgress("Pause de refroidissement…", step.toFloat() / steps)
                delay(plan.cooldownMs)
            }
            System.gc()
            val base = ConfigResult(cfg, name, rssBeforeMb = MemInfo.rssMb(), thermalStart = thermal())
            val peak = AtomicLong(base.rssBeforeMb)
            val sampler = CoroutineScope(Dispatchers.IO).launch {
                while (isActive) {
                    peak.accumulateAndGet(MemInfo.rssMb()) { a, b -> maxOf(a, b) }
                    delay(25)
                }
            }
            var engine: SttEngine? = null
            val result = try {
                tick("Chargement ${cfg.label()}")
                val t0 = SystemClock.elapsedRealtime()
                engine = EngineFactory.create(models, cfg)
                val loadMs = SystemClock.elapsedRealtime() - t0
                val rssLoaded = MemInfo.rssMb()

                // Préchauffage (non compté) : allocations, caches, montée en fréquence CPU.
                tick("Préchauffage ${cfg.label()}")
                plan.clips.firstOrNull()?.let { engine.transcribe(audio.getValue(it.id), SttRequest(plan.language)) }

                val clipResults = plan.clips.map { clip ->
                    val runs = mutableListOf<Long>()
                    var text = ""
                    var enc: Float? = null
                    repeat(plan.repetitions) {
                        ensureActive()
                        tick("${cfg.label()} — clip ${plan.clips.indexOf(clip) + 1}/${plan.clips.size}")
                        val r = engine.transcribe(audio.getValue(clip.id), SttRequest(plan.language))
                        runs += r.processingMs
                        text = r.text
                        enc = r.encodeMs
                    }
                    ClipResult(clip.id, clip.durationMs, runs, text, enc, clip.reference?.let { Wer.score(it, text) })
                }
                base.copy(loadMs = loadMs, rssLoadedMb = rssLoaded, clips = clipResults)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e("Bench", "échec ${cfg.label()}", e)
                base.copy(error = e.message ?: e.javaClass.simpleName)
            } finally {
                engine?.close()
                sampler.cancel()
            }
            System.gc()
            delay(300)
            val final = result.copy(rssPeakMb = peak.get(), rssAfterCloseMb = MemInfo.rssMb(), thermalEnd = thermal())
            AppLog.i("Bench", "${cfg.label()} : RTF %.3f, lat. moy. %d ms, WER %s".format(
                final.rtf, final.meanLatencyMs, final.wer?.let { "%.1f %%".format(it.rate * 100) } ?: "—"))
            onResult(final)
        }
        onProgress("Terminé", 1f)
    }

    private fun thermal(): Int = context.getSystemService(PowerManager::class.java).currentThermalStatus
}
