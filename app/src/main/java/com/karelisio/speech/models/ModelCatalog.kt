package com.karelisio.speech.models

import org.json.JSONObject

enum class EngineKind { WHISPER, PARAKEET }

data class ModelFile(val path: String, val size: Long, val sha256: String?)

data class ModelInfo(
    val id: String,
    val name: String,
    val engine: EngineKind,
    val description: String,
    val repo: String,
    val revision: String,
    val files: List<ModelFile>,
    val tags: List<String> = emptyList(),
) {
    /** Nom court pour les étiquettes (« Parakeet TDT 0.6B v3 »). */
    val shortName: String get() = name.substringBefore(" ·")
    val totalSize: Long get() = files.sumOf { it.size }
    fun url(f: ModelFile) = "https://huggingface.co/$repo/resolve/$revision/${f.path}"
}

object ModelCatalog {
    fun parse(json: String): List<ModelInfo> {
        val arr = JSONObject(json).getJSONArray("models")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val files = o.getJSONArray("files")
            ModelInfo(
                id = o.getString("id"),
                name = o.getString("name"),
                engine = EngineKind.valueOf(o.getString("engine").uppercase()),
                description = o.optString("description"),
                repo = o.getString("repo"),
                revision = o.optString("revision", "main"),
                tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.getString(it) } } ?: emptyList(),
                files = (0 until files.length()).map { j ->
                    val f = files.getJSONObject(j)
                    ModelFile(
                        path = f.getString("path"),
                        size = f.optLong("size", 0L),
                        sha256 = if (f.isNull("sha256")) null else f.getString("sha256").lowercase(),
                    )
                },
            )
        }
    }
}
