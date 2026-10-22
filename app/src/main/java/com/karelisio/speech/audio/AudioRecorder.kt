package com.karelisio.speech.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import com.karelisio.speech.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Sources micro testables : le traitement appliqué par le constructeur varie selon la source. */
enum class MicSource(val id: Int, val label: String) {
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, "Reconnaissance vocale"),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED, "Brut (non traité)"),
    VOICE_PERFORMANCE(MediaRecorder.AudioSource.VOICE_PERFORMANCE, "Performance vocale"),
    MIC(MediaRecorder.AudioSource.MIC, "Micro standard"),
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER, "Caméscope"),
}

data class CaptureConfig(val source: MicSource, val captureRate: Int) {
    val label get() = "${source.label} · ${captureRate / 1000} kHz" + if (captureRate != AudioRecorder.SAMPLE_RATE) " → 16 kHz (Speech)" else ""
}

/** Diagnostic d'un enregistrement, pour repérer traitements, pertes et saturation. */
data class CaptureReport(
    val config: CaptureConfig,
    val deviceRate: Int,
    val deviceName: String,
    val effects: List<String>,
    val silenced: Boolean,
    val durationMs: Long,
    val audioMs: Long,
    val peakDb: Double,
    val rmsDb: Double,
    val clippedPct: Double,
) {
    /** Écart entre le temps écoulé et l'audio reçu (> 0 = échantillons perdus). */
    val missingMs get() = (durationMs - audioMs).coerceAtLeast(0)

    /**
     * Audio reçu / temps écoulé. ~1 attendu (un peu moins : ouverture du micro).
     * Au-delà de 1,05 le flux contient des échantillons en trop (rééchantillonnage défaillant).
     */
    val rateRatio: Double get() = if (durationMs <= 0) 1.0 else audioMs.toDouble() / durationMs
    val rateSuspicious: Boolean get() = durationMs > 1000 && (rateRatio > 1.05 || rateRatio < 0.85)

    fun summary() = buildString {
        append(config.label)
        append(" · micro ${deviceRate} Hz")
        if (deviceName.isNotEmpty()) append(" ($deviceName)")
        append(" · effets : ").append(if (effects.isEmpty()) "aucun" else effects.joinToString())
        append("\naudio ${audioMs} ms / ${durationMs} ms écoulés (×%.2f)".format(rateRatio))
        if (rateSuspicious) append(" ⚠ débit incohérent : essaie la capture 48 kHz")
        append(" · crête %.1f dBFS · RMS %.1f dBFS · saturé %.2f %%".format(peakDb, rmsDb, clippedPct))
        if (silenced) append(" · ⚠ micro coupé par le système")
    }
}

/**
 * Capture PCM float mono 16 kHz (entrée native de Whisper / Parakeet).
 *
 * L'[AudioRecord] est créé à l'avance par [prepare] : au tap, seul `startRecording()`
 * reste à faire, ce qui ouvre le micro sans délai perceptible.
 */
class AudioRecorder(private val configProvider: () -> CaptureConfig = { CaptureConfig(MicSource.VOICE_RECOGNITION, SAMPLE_RATE) }) {
    companion object {
        const val SAMPLE_RATE = 16_000
        private const val TAG = "Audio"
        private const val CHUNK_MS = 32 // cadence de mise à jour du niveau sonore
    }

    private var record: AudioRecord? = null
    private var recordConfig: CaptureConfig? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val buffer = FloatBuffer()

    private val _level = MutableStateFlow(0f)
    /** Niveau RMS normalisé 0..1, pour l'animation. */
    val level: StateFlow<Float> = _level

    /** Délai mesuré entre l'appel à [start] et le premier échantillon reçu. */
    var lastStartLatencyMs: Long = 0
        private set

    private val _lastReport = MutableStateFlow<CaptureReport?>(null)
    val lastReport: StateFlow<CaptureReport?> = _lastReport

    private var tStart = 0L
    @Volatile private var deviceRate = 0
    @Volatile private var deviceName = ""
    @Volatile private var effects: List<String> = emptyList()
    @Volatile private var silenced = false

    val isRecording get() = running

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun prepare() {
        val cfg = configProvider()
        if (record != null && recordConfig == cfg) return
        record?.release()
        val minBuf = AudioRecord.getMinBufferSize(cfg.captureRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val r = AudioRecord.Builder()
            .setAudioSource(cfg.source.id)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(cfg.captureRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, cfg.captureRate * 4)) // ≥ 250 ms
            .build()
        check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord non initialisé (${cfg.label})" }
        record = r
        recordConfig = cfg
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running) return
        prepare()
        val r = record!!
        val cfg = recordConfig!!
        buffer.clear()
        deviceRate = 0; deviceName = ""; effects = emptyList(); silenced = false
        tStart = SystemClock.elapsedRealtime()
        r.startRecording()
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = FloatArray(cfg.captureRate * CHUNK_MS / 1000)
            val decimator = if (cfg.captureRate == 3 * SAMPLE_RATE) Decimator3() else null
            val down = FloatArray(chunk.size / 3 + 2)
            var first = true
            while (running) {
                val n = r.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                if (first) {
                    lastStartLatencyMs = SystemClock.elapsedRealtime() - tStart
                    first = false
                    inspect(r)
                }
                val (data, m) = if (decimator != null) down to decimator.process(chunk, n, down) else chunk to n
                synchronized(buffer) { buffer.append(data, m) }
                var sum = 0.0
                for (i in 0 until m) sum += data[i] * data[i]
                _level.value = if (m > 0) (sqrt(sum / m) * 8).toFloat().coerceIn(0f, 1f) else 0f
            }
        }, "speech-audio").apply { start() }
        AppLog.i(TAG, "enregistrement démarré (${cfg.label})")
    }

    /** Format réel du micro et effets appliqués par le système (Android 10+). */
    private fun inspect(r: AudioRecord) {
        runCatching {
            r.activeRecordingConfiguration?.let { c ->
                deviceRate = c.format.sampleRate
                effects = (c.effects + c.clientEffects).map { it.name }.distinct()
                silenced = c.isClientSilenced
                deviceName = c.audioDevice?.productName?.toString() ?: ""
            }
        }
    }

    /** Arrête la capture et renvoie tout l'audio enregistré. */
    fun stop(): FloatArray {
        if (!running) return FloatArray(0)
        running = false
        record?.stop()
        thread?.join(500)
        thread = null
        _level.value = 0f
        val out = synchronized(buffer) { buffer.toArray() }
        val report = analyze(out, SystemClock.elapsedRealtime() - tStart)
        _lastReport.value = report
        val msg = "enregistrement arrêté, ouverture micro ${lastStartLatencyMs} ms\n" + report.summary()
        if (report.rateSuspicious) AppLog.w(TAG, msg) else AppLog.i(TAG, msg)
        return out
    }

    private fun analyze(x: FloatArray, elapsedMs: Long): CaptureReport {
        var peak = 0f
        var sum = 0.0
        var clipped = 0
        for (v in x) {
            val a = abs(v)
            if (a > peak) peak = a
            if (a >= 0.99f) clipped++
            sum += v * v
        }
        fun db(v: Double) = if (v <= 1e-9) -120.0 else 20 * log10(v)
        return CaptureReport(
            config = recordConfig ?: configProvider(),
            deviceRate = deviceRate,
            deviceName = deviceName,
            effects = effects,
            silenced = silenced,
            durationMs = elapsedMs,
            audioMs = x.size * 1000L / SAMPLE_RATE,
            peakDb = db(peak.toDouble()),
            rmsDb = db(if (x.isEmpty()) 0.0 else sqrt(sum / x.size)),
            clippedPct = if (x.isEmpty()) 0.0 else 100.0 * clipped / x.size,
        )
    }

    /** Copie de l'audio capturé depuis [from] (pour la transcription pendant l'enregistrement). */
    fun snapshot(from: Int = 0): FloatArray = synchronized(buffer) { buffer.copyRange(from) }

    /** Copie de [from, to) de l'enregistrement en cours ou du dernier (bornée à ce qui existe). */
    fun slice(from: Int, to: Int): FloatArray = synchronized(buffer) { buffer.copyRange(from, to) }

    /** Libère l'AudioRecord (changement de réglage, arrêt du service). */
    fun release() {
        if (running) stop()
        record?.release()
        record = null
        recordConfig = null
    }
}

/** Tampon float extensible sans boxing. */
class FloatBuffer(initial: Int = AudioRecorder.SAMPLE_RATE * 30) {
    private var data = FloatArray(initial)
    var size = 0
        private set

    fun append(src: FloatArray, n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
        System.arraycopy(src, 0, data, size, n)
        size += n
    }

    fun clear() { size = 0 }
    fun toArray(): FloatArray = data.copyOf(size)
    fun copyRange(from: Int, to: Int = size): FloatArray {
        val a = from.coerceIn(0, size)
        return data.copyOfRange(a, to.coerceIn(a, size))
    }
}
