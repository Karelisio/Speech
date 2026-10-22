package com.karelisio.speech.bench

import android.content.Context
import android.net.Uri
import com.karelisio.speech.audio.AudioRecorder
import com.karelisio.speech.audio.Wav
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BenchClip(
    val id: String,
    val reference: String?,
    val durationMs: Long,
    val createdAt: Long,
    /** Réglage micro utilisé pour l'enregistrement (null : importé ou ancien clip). */
    val source: String? = null,
)

/** Clips audio enregistrés pour le benchmark (`filesDir/bench/clips`). */
class BenchClips(private val context: Context) {
    private val dir = File(context.filesDir, "bench/clips").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val _clips = MutableStateFlow(load())
    val clips: StateFlow<List<BenchClip>> = _clips

    fun file(clip: BenchClip) = File(dir, "${clip.id}.wav")
    fun samples(clip: BenchClip): FloatArray = Wav.read(file(clip))

    @Synchronized
    fun add(samples: FloatArray, reference: String?, source: String? = null): BenchClip {
        val clip = BenchClip(
            id = "clip_${System.currentTimeMillis()}",
            reference = reference?.trim()?.takeIf { it.isNotEmpty() },
            durationMs = samples.size * 1000L / AudioRecorder.SAMPLE_RATE,
            createdAt = System.currentTimeMillis(),
            source = source,
        )
        Wav.write(file(clip), samples)
        save(_clips.value + clip)
        return clip
    }

    fun import(uri: Uri, reference: String?): BenchClip {
        val samples = context.contentResolver.openInputStream(uri)!!.use { Wav.read(it) }
        return add(samples, reference, "importé")
    }

    @Synchronized
    fun updateReference(id: String, reference: String?) {
        save(_clips.value.map { if (it.id == id) it.copy(reference = reference?.trim()?.takeIf { r -> r.isNotEmpty() }) else it })
    }

    @Synchronized
    fun delete(id: String) {
        _clips.value.firstOrNull { it.id == id }?.let { file(it).delete() }
        save(_clips.value.filterNot { it.id == id })
    }

    private fun save(list: List<BenchClip>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("reference", it.reference ?: JSONObject.NULL)
                .put("durationMs", it.durationMs).put("createdAt", it.createdAt)
                .put("source", it.source ?: JSONObject.NULL))
        }
        index.writeText(arr.toString(2))
        _clips.value = list
    }

    private fun load(): List<BenchClip> = runCatching {
        val arr = JSONArray(index.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BenchClip(
                id = o.getString("id"),
                reference = if (o.isNull("reference")) null else o.getString("reference"),
                durationMs = o.getLong("durationMs"),
                createdAt = o.getLong("createdAt"),
                source = if (o.isNull("source")) null else o.optString("source"),
            )
        }.filter { File(dir, "${it.id}.wav").exists() }
    }.getOrDefault(emptyList())
}
