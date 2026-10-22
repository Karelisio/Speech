package com.karelisio.speech.dictation

import android.os.SystemClock
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.stt.EngineConfig
import com.karelisio.speech.stt.SttRequest
import com.karelisio.speech.util.AppLog
import com.karelisio.speech.vad.Span
import com.karelisio.speech.vad.Transcription
import com.karelisio.speech.vad.VadProcessor
import com.karelisio.speech.vad.VadStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Transcription pendant l'enregistrement : le VAD découpe l'audio aux pauses et chaque
 * segment est transcrit dès qu'il se termine. Au stop, il ne reste que le dernier segment.
 */
class LiveSession(
    private val app: SpeechApp,
    private val cfg: EngineConfig,
    vadFile: File,
    private val request: SttRequest,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "Live"
        private const val POLL_MS = 100L
    }

    private val vad = VadStream(vadFile)
    private val mutex = Mutex()
    private val texts = ArrayList<String>()
    private var fed = 0
    private var sttMs = 0L
    private var job: Job? = null

    fun start() {
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(POLL_MS)
                mutex.withLock { pump(app.recorder.snapshot(fed), final = false) }
            }
        }
    }

    private suspend fun pump(audio: FloatArray, final: Boolean) {
        fed += audio.size
        val spans = vad.accept(audio) + if (final) vad.flush() else emptyList()
        for (s in spans) transcribe(s)
    }

    private suspend fun transcribe(span: Span) {
        val pad = VadProcessor.PAD_SAMPLES
        val segment = app.recorder.slice(span.start - pad, span.end + pad)
        if (segment.isEmpty()) return
        val r = app.engines.use(cfg) { it.transcribe(segment, request) }
        sttMs += r.processingMs
        if (r.text.isNotBlank()) texts += r.text.trim()
        AppLog.i(TAG, "segment ${texts.size} (${segment.size / 16} ms) transcrit en ${r.processingMs} ms pendant l'enregistrement")
        // Le nettoyage IA traite déjà le début du texte pendant qu'on continue de parler.
        val llm = app.cleanerModelId()
        if (app.cleaning.value && llm != null && texts.isNotEmpty()) {
            val d = app.dictionary
            app.cleaners.prefillAsync(llm, app.prefs.llmThreads, d.apply(texts.joinToString(" ")), d.terms)
        }
    }

    /** Termine avec l'audio complet : seul le dernier segment reste à transcrire. */
    suspend fun finish(audio: FloatArray): Transcription {
        job?.cancelAndJoin()
        val t0 = SystemClock.elapsedRealtime()
        mutex.withLock { pump(audio.copyOfRange(fed.coerceAtMost(audio.size), audio.size), final = true) }
        vad.close()
        val tail = SystemClock.elapsedRealtime() - t0
        return Transcription(
            text = texts.joinToString(" "),
            speech = texts.isNotEmpty(),
            vadMs = vad.totalMs,
            sttMs = sttMs,
            tailMs = tail,
            segments = texts.size,
        )
    }

    fun cancel() {
        job?.cancel()
        scope.launch { mutex.withLock { vad.close() } }
    }
}
