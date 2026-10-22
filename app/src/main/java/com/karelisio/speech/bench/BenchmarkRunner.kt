package com.karelisio.speech.bench

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.karelisio.speech.models.ModelManager
import com.karelisio.speech.stt.EngineConfig
import com.karelisio.speech.stt.EngineFactory
import com.karelisio.speech.stt.SttEngine
import com.karelisio.speech.stt.SttRequest
import com.karelisio.speech.util.AppLog
import com.karelisio.speech.util.MemInfo
import com.karelisio.speech.vad.Transcriber
import com.karelisio.speech.vad.Transcription
import com.karelisio.speech.vad.VadMode
import com.karelisio.speech.vad.VadProcessor
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
    /** Latence après le stop, par répétition (bloc : tout ; segments : dernier segment + VAD). */
    val runsMs: List<Long>,
    val text: String,
    val encodeMs: Float?,
    val wer: Wer.Score?,
    /** Temps de reconnaissance total (tous segments), médiane. */
    val totalMs: Long = 0,
    val vadMs: Long = 0,
    val segments: Int = 1,
) {
    val medianMs: Long get() = runsMs.sorted()[runsMs.size / 2]
}

data class ConfigResult(
    val config: EngineConfig,
    val modelName: String,
    val mode: VadMode = VadMode.OFF,
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
    val label: String get() = config.label() + " · " + mode.short
    val totalAudioMs get() = clips.sumOf { it.durationMs }
    /** Facteur temps réel : calcul total / durée audio. */
    val rtf: Double get() = if (totalAudioMs == 0L) 0.0 else clips.sumOf { it.totalMs }.toDouble() / totalAudioMs
    /** Latence moyenne après le stop. */
    val meanLatencyMs: Long get() = if (clips.isEmpty()) 0 else clips.sumOf { it.medianMs } / clips.size
    /** Latence après le stop projetée pour une dictée de 10 s. */
    val latency10sMs: Long
        get() = if (mode == VadMode.SEGMENTS) meanLatencyMs // ne dépend que du dernier segment
        else if (totalAudioMs == 0L) 0 else clips.sumOf { it.medianMs } * 10_000 / totalAudioMs
    val meanVadMs: Long get() = if (clips.isEmpty()) 0 else clips.sumOf { it.vadMs } / clips.size
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
    val modes: List<VadMode> = listOf(VadMode.OFF),
    val repetitions: Int = 1,
    val language: String = "fr",
    /** Pause entre configurations pour limiter l'effet de la chauffe. */
    val cooldownMs: Long = 2_000,
)

/**
 * Exécute un [BenchPlan] séquentiellement. Chaque modèle est chargé une fois puis testé dans
 * chaque mode VAD (bloc entier, silences coupés, segments aux pauses).
 */
class BenchmarkRunner(
    private val context: Context,
    private val models: ModelManager,
    private val clipStore: BenchClips,
) {
    suspend fun run(
        plan: BenchPlan,
        vad: VadProcessor?,
        onProgress: (String, Float) -> Unit,
        onResult: (ConfigResult) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val audio = plan.clips.associate { it.id to clipStore.samples(it) }
        val modes = plan.modes.filter { it == VadMode.OFF || vad != null }.ifEmpty { listOf(VadMode.OFF) }
        val steps = plan.configs.size * (modes.size * plan.clips.size * plan.repetitions + 2)
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
            val results = try {
                tick("Chargement ${cfg.label()}")
                val t0 = SystemClock.elapsedRealtime()
                engine = EngineFactory.create(models, cfg)
                val loadMs = SystemClock.elapsedRealtime() - t0
                val rssLoaded = MemInfo.rssMb()

                // Préchauffage (non compté) : allocations, caches, montée en fréquence CPU.
                tick("Préchauffage ${cfg.label()}")
                plan.clips.firstOrNull()?.let { engine.transcribe(audio.getValue(it.id), SttRequest(plan.language)) }

                modes.map { mode ->
                    val clipResults = plan.clips.mapIndexed { ci, clip ->
                        val runs = mutableListOf<Long>()
                        val totals = mutableListOf<Long>()
                        var last: Transcription? = null
                        repeat(plan.repetitions) {
                            ensureActive()
                            tick("${cfg.label()} · ${mode.short} — clip ${ci + 1}/${plan.clips.size}")
                            val r = Transcriber.run(engine, vad, audio.getValue(clip.id), mode, SttRequest(plan.language))
                            runs += r.vadMs + r.tailMs
                            totals += r.sttMs
                            last = r
                        }
                        val r = last!!
                        ClipResult(
                            clipId = clip.id, durationMs = clip.durationMs, runsMs = runs, text = r.text,
                            encodeMs = null, wer = clip.reference?.let { Wer.score(it, r.text) },
                            totalMs = totals.sorted()[totals.size / 2], vadMs = r.vadMs, segments = r.segments,
                        )
                    }
                    base.copy(mode = mode, loadMs = loadMs, rssLoadedMb = rssLoaded, clips = clipResults)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e("Bench", "échec ${cfg.label()}", e)
                listOf(base.copy(error = e.message ?: e.javaClass.simpleName))
            } finally {
                engine?.close()
                sampler.cancel()
            }
            System.gc()
            delay(300)
            val rssAfter = MemInfo.rssMb()
            val thermalEnd = thermal()
            results.forEach { res ->
                val final = res.copy(rssPeakMb = peak.get(), rssAfterCloseMb = rssAfter, thermalEnd = thermalEnd)
                AppLog.i("Bench", "${final.label} : latence ${final.meanLatencyMs} ms, RTF %.3f, WER %s".format(
                    final.rtf, final.wer?.let { "%.1f %%".format(it.rate * 100) } ?: "—"))
                onResult(final)
            }
        }
        onProgress("Terminé", 1f)
    }

    private fun thermal(): Int = context.getSystemService(PowerManager::class.java).currentThermalStatus
}
