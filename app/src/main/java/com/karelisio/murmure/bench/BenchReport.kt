package com.karelisio.murmure.bench

import android.content.Context
import com.karelisio.murmure.stt.WhisperNative
import com.karelisio.murmure.util.DeviceInfo
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
        appendLine("# Murmure — benchmark ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRANCE).format(Date())}")
        appendLine()
        appendLine("```")
        appendLine(DeviceInfo.summary(context))
        appendLine("whisper.cpp : " + runCatching { WhisperNative.systemInfo().trim() }.getOrDefault("?"))
        appendLine("```")
        val usedClips = clips.filter { c -> results.any { r -> r.clips.any { it.clipId == c.id } } }
        appendLine("Clips : ${usedClips.size} (${"%.1f".format(usedClips.sumOf { it.durationMs } / 1000.0)} s au total)" +
            (plan?.let { ", répétitions : ${it.repetitions}, langue : ${it.language}" } ?: ""))
        appendLine("Mode : transcription en un bloc après le stop (latence = temps de traitement).")
        appendLine()
        appendLine("| Config | Charg. ms | Lat. moy. ms | Lat. 10 s ms | RTF | RAM modèle Mo | RAM pic Mo | Après libération Mo | WER | Therm. |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|")
        for (r in results) {
            if (r.error != null) {
                appendLine("| ${r.config.label()} | ERREUR : ${r.error} |||||||||")
                continue
            }
            appendLine(
                "| ${r.config.label()} | ${r.loadMs} | ${r.meanLatencyMs} | ${r.latency10sMs} | ${"%.3f".format(r.rtf)} | " +
                    "${r.rssLoadedMb - r.rssBeforeMb} | ${r.rssPeakMb} | ${r.rssAfterCloseMb} | " +
                    "${r.wer?.let { "%.1f %%".format(it.rate * 100) } ?: "—"} | " +
                    "${thermalLabel(r.thermalStart)}→${thermalLabel(r.thermalEnd)} |"
            )
        }
        appendLine()
        appendLine("## Transcriptions")
        usedClips.forEachIndexed { i, clip ->
            appendLine()
            appendLine("### Clip ${i + 1} (${"%.1f".format(clip.durationMs / 1000.0)} s)")
            appendLine("Réf. : ${clip.reference ?: "(aucune)"}")
            for (r in results) {
                val c = r.clips.firstOrNull { it.clipId == clip.id } ?: continue
                val wer = c.wer?.let { " · WER %.0f %%".format(it.rate * 100) } ?: ""
                val enc = c.encodeMs?.let { " · enc %.0f ms".format(it) } ?: ""
                val runs = if (c.runsMs.size > 1) " · runs ${c.runsMs.joinToString("/")}" else ""
                appendLine("- **${r.config.label()}** (${c.medianMs} ms$enc$runs$wer) : ${c.text}")
            }
        }
    }
}
