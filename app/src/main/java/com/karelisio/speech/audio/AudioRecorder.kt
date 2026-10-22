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
import kotlin.math.sqrt

/**
 * Capture PCM float mono 16 kHz (entrée native de Whisper / Parakeet).
 *
 * L'[AudioRecord] est créé à l'avance par [prepare] : au tap, seul `startRecording()`
 * reste à faire, ce qui ouvre le micro sans délai perceptible.
 */
class AudioRecorder {
    companion object {
        const val SAMPLE_RATE = 16_000
        private const val TAG = "Audio"
        private const val CHUNK = 512 // 32 ms : cadence de mise à jour du niveau sonore
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val buffer = FloatBuffer()

    private val _level = MutableStateFlow(0f)
    /** Niveau RMS normalisé 0..1, pour l'animation. */
    val level: StateFlow<Float> = _level

    /** Délai mesuré entre l'appel à [start] et le premier échantillon reçu. */
    var lastStartLatencyMs: Long = 0
        private set

    val isRecording get() = running

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun prepare() {
        if (record != null) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val r = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * 4)) // ≥ 250 ms
            .build()
        check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord non initialisé" }
        record = r
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running) return
        prepare()
        val r = record!!
        buffer.clear()
        val t0 = SystemClock.elapsedRealtime()
        r.startRecording()
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = FloatArray(CHUNK)
            var first = true
            while (running) {
                val n = r.read(chunk, 0, CHUNK, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                if (first) {
                    lastStartLatencyMs = SystemClock.elapsedRealtime() - t0
                    first = false
                }
                synchronized(buffer) { buffer.append(chunk, n) }
                var sum = 0.0
                for (i in 0 until n) sum += chunk[i] * chunk[i]
                _level.value = (sqrt(sum / n) * 8).toFloat().coerceIn(0f, 1f)
            }
        }, "speech-audio").apply { start() }
        AppLog.i(TAG, "enregistrement démarré")
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
        AppLog.i(TAG, "enregistrement arrêté : ${out.size / (SAMPLE_RATE / 1000)} ms, ouverture micro ${lastStartLatencyMs} ms")
        return out
    }

    /** Copie de l'audio capturé jusqu'ici (pour la transcription pendant l'enregistrement). */
    fun snapshot(from: Int = 0): FloatArray = synchronized(buffer) { buffer.copyRange(from) }

    fun release() {
        if (running) stop()
        record?.release()
        record = null
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
    fun copyRange(from: Int): FloatArray = data.copyOfRange(from.coerceIn(0, size), size)
}
