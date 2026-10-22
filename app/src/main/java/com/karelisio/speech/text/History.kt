package com.karelisio.speech.text

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(val time: Long, val raw: String, val text: String, val latencyMs: Long)

/** Historique local des [MAX] dernières dictées (brut + final), dans filesDir. */
class History(context: Context) {
    companion object {
        const val MAX = 50
    }

    private val file = File(context.filesDir, "history.json")
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries

    @Synchronized
    fun add(raw: String, text: String, latencyMs: Long) {
        save((listOf(HistoryEntry(System.currentTimeMillis(), raw, text, latencyMs)) + _entries.value).take(MAX))
    }

    @Synchronized
    fun delete(entry: HistoryEntry) = save(_entries.value - entry)

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("time", it.time).put("raw", it.raw).put("text", it.text).put("latencyMs", it.latencyMs))
        }
        runCatching { file.writeText(arr.toString()) }
        _entries.value = list
    }

    private fun load(): List<HistoryEntry> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HistoryEntry(o.getLong("time"), o.getString("raw"), o.getString("text"), o.optLong("latencyMs"))
        }
    }.getOrDefault(emptyList())
}
