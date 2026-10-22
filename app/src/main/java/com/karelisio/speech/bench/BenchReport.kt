package com.karelisio.speech.bench

import android.content.Context
import com.karelisio.speech.stt.WhisperNative
import com.karelisio.speech.util.DeviceInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rapport texte (Markdown) à partager en un tap. */
object BenchReport {
    fun thermalLabel(s: Int) = when (s) {
        0 -> "ok"; 1 -> "léger"; 2 -> "modéré"; 3 -> "sévère"; 4 -> "critique"; 5 -> "urgence"; 6 -> "arrêt"
        else -> s.toString()
    }

    fun build(context: Context, plan: BenchPlan?, results: List<ConfigResult>, clips: List<BenchClip>): String = buildString {
        appendLine("# Speech — benchmark ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRANCE).format(Date())}")
        appendLine()
        appendLine("```")
        appendLine(DeviceInfo.summary(context))
        appendLine("whisper.cpp : " + runCatching { WhisperNative.systemInfo().trim() }.getOrDefault("?"))
        appendLine("```")
        val usedClips = clips.filter { c -> results.any { r -> r.clips.any { it.clipId == c.id } } }
        appendLine("Clips : ${usedClips.size} (${"%.1f".format(usedClips.sumOf { it.durationMs } / 1000.0)} s au total)" +
            (plan?.let { ", répétitions : ${it.repetitions}, langue : ${it.language}" } ?: ""))
        appendLine("Capture micro : " + (usedClips.mapNotNull { it.source }.distinct().joinToString().ifEmpty { "non renseignée" }))
        appendLine("Modes : bloc = tout l'audio ; VAD = silences coupés (VAD inclus dans la latence) ;")
        appendLine("segments = découpe aux pauses, latence = dernier segment + VAD (transcription pendant l'enregistrement simulée).")
        appendLine()
        appendLine("| Config | Charg. ms | Lat. moy. ms | Lat. 10 s ms | VAD ms | Seg. | RTF | RAM modèle Mo | RAM pic Mo | Après libération Mo | WER | Therm. |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
        for (r in results) {
            if (r.error != null) {
                appendLine("| ${r.label} | ERREUR : ${r.error} |||||||||||")
                continue
            }
            val seg = if (r.clips.isEmpty()) 0.0 else r.clips.sumOf { it.segments }.toDouble() / r.clips.size
            appendLine(
                "| ${r.label} | ${r.loadMs} | ${r.meanLatencyMs} | ${r.latency10sMs} | ${r.meanVadMs} | ${"%.1f".format(seg)} | " +
                    "${"%.3f".format(r.rtf)} | ${r.rssLoadedMb - r.rssBeforeMb} | ${r.rssPeakMb} | ${r.rssAfterCloseMb} | " +
                    "${r.wer?.let { "%.1f %%".format(it.rate * 100) } ?: "—"} | " +
                    "${thermalLabel(r.thermalStart)}→${thermalLabel(r.thermalEnd)} |"
            )
        }
        appendLine()
        appendLine("## Transcriptions")
        usedClips.forEachIndexed { i, clip ->
            appendLine()
            appendLine("### Clip ${i + 1} (${"%.1f".format(clip.durationMs / 1000.0)} s${clip.source?.let { ", $it" } ?: ""})")
            appendLine("Réf. : ${clip.reference ?: "(aucune)"}")
            for (r in results) {
                val c = r.clips.firstOrNull { it.clipId == clip.id } ?: continue
                val wer = c.wer?.let { " · WER %.0f %%".format(it.rate * 100) } ?: ""
                val enc = c.encodeMs?.let { " · enc %.0f ms".format(it) } ?: ""
                val runs = if (c.runsMs.size > 1) " · runs ${c.runsMs.joinToString("/")}" else ""
                val segs = if (c.segments > 1) " · ${c.segments} seg." else ""
                appendLine("- **${r.label}** (${c.medianMs} ms$enc$runs$segs$wer) : ${c.text}")
            }
        }
    }
}
