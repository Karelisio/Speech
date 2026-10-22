package com.karelisio.speech.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.bench.BenchClip
import com.karelisio.speech.bench.ConfigResult
import com.karelisio.speech.bench.REFERENCE_SENTENCES

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BenchmarkScreen(modifier: Modifier = Modifier, vm: BenchmarkViewModel = viewModel()) {
    val context = LocalContext.current
    val app = context.applicationContext as SpeechApp
    val ui by vm.ui.collectAsState()
    val clips by vm.clips.collectAsState()
    val status by app.models.status.collectAsState()
    val level by vm.recorder.level.collectAsState()
    val withMic = rememberWithMicPermission()
    var freeMode by remember { mutableStateOf(false) }
    var freeRef by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<BenchClip?>(null) }

    val view = LocalView.current
    DisposableEffect(ui.running) {
        view.keepScreenOn = ui.running
        onDispose { view.keepScreenOn = false }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importWav(uri, null)
    }

    val recordedRefs = clips.mapNotNull { it.reference }.toSet()
    val nextSentence = REFERENCE_SENTENCES.firstOrNull { it !in recordedRefs }
    val currentRef = if (freeMode || nextSentence == null) freeRef else nextSentence

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- Enregistrement des clips ---
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Clips de test (${clips.size}, ${"%.1f".format(clips.sumOf { it.durationMs } / 1000.0)} s)",
                    style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Phrase libre", Modifier.weight(1f))
                    Switch(freeMode || nextSentence == null, { freeMode = it }, enabled = nextSentence != null)
                }
                if (freeMode || nextSentence == null) {
                    OutlinedTextField(freeRef, { freeRef = it }, Modifier.fillMaxWidth(),
                        label = { Text("Texte de référence (optionnel, pour le WER)") })
                } else {
                    Text("Lis à voix haute (${REFERENCE_SENTENCES.indexOf(nextSentence) + 1}/${REFERENCE_SENTENCES.size}) :",
                        style = MaterialTheme.typography.labelMedium)
                    Text("« $nextSentence »", style = MaterialTheme.typography.bodyLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ui.recording) {
                        Button(onClick = { vm.stopRecording(currentRef); freeRef = "" }) { Text("Stop") }
                        LinearProgressIndicator(progress = { level }, modifier = Modifier.weight(1f))
                    } else {
                        Button(onClick = { withMic { vm.startRecording() } }, enabled = !ui.running) { Text("Enregistrer") }
                        OutlinedButton(onClick = { importer.launch(arrayOf("audio/*")) }, enabled = !ui.running) { Text("Importer WAV") }
                    }
                }
                clips.forEachIndexed { i, c ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("#${i + 1} · ${"%.1f".format(c.durationMs / 1000.0)} s" + (c.source?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.labelMedium)
                            Text(c.reference ?: "(sans référence)", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        TextButton(onClick = { vm.play(c) }) { Text("▶") }
                        TextButton(onClick = { vm.share(context, c) }) { Text("⇪") }
                        TextButton(onClick = { editing = c }) { Text("Réf.") }
                        TextButton(onClick = { vm.delete(c) }, enabled = !ui.running) { Text("✕") }
                    }
                }
            }
        }

        // --- Configurations ---
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. Configurations", style = MaterialTheme.typography.titleMedium)
                val ready = remember(status) { app.models.readyModels() }
                if (ready.isEmpty()) Text("Aucun modèle téléchargé.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ready.forEach { m ->
                        FilterChip(m.id in ui.selectedModels, { vm.toggleModel(m.id) }, label = { Text(m.name) })
                    }
                }
                Text("Threads", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.threadChoices.forEach { t ->
                        FilterChip(t in ui.threads, { vm.toggleThreads(t) }, label = { Text("$t") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Whisper : comparer audio_ctx plein / dynamique", Modifier.weight(1f))
                    Switch(ui.compareWhisperCtx, vm::setCompareCtx)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Répétitions par clip", Modifier.weight(1f))
                    listOf(1, 3).forEach { n -> FilterChip(ui.repetitions == n, { vm.setRepetitions(n) }, label = { Text("$n") }) }
                }
                val n = vm.plannedConfigs().size
                Text("$n configuration(s) × ${clips.size} clip(s)", style = MaterialTheme.typography.bodySmall)
                if (ui.running) {
                    LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
                    Text(ui.progressText, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = vm::cancel) { Text("Annuler") }
                } else {
                    Button(onClick = vm::run, enabled = n > 0 && clips.isNotEmpty()) { Text("Lancer le benchmark") }
                }
            }
        }

        ui.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        // --- Résultats ---
        if (ui.results.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("3. Résultats", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { shareText(context, "Speech — benchmark", vm.report()) }) { Text("Exporter") }
                        OutlinedButton(onClick = { copyText(context, "benchmark", vm.report()) }) { Text("Copier") }
                    }
                    ui.results.forEach { r -> ResultBlock(r, clips) }
                }
            }
        }
    }

    editing?.let { clip ->
        var ref by remember(clip.id) { mutableStateOf(clip.reference ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Texte de référence") },
            text = { OutlinedTextField(ref, { ref = it }, Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.updateReference(clip, ref); editing = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun ResultBlock(r: ConfigResult, clips: List<BenchClip>) {
    HorizontalDivider()
    Text(r.config.label(), style = MaterialTheme.typography.titleSmall)
    if (r.error != null) {
        Text("Erreur : ${r.error}", color = MaterialTheme.colorScheme.error)
        return
    }
    Text(
        "Lat. moy. ${r.meanLatencyMs} ms · 10 s → ${r.latency10sMs} ms · RTF ${"%.3f".format(r.rtf)}\n" +
            "WER ${r.wer?.let { "%.1f %%".format(it.rate * 100) } ?: "—"} · chargement ${r.loadMs} ms\n" +
            "RAM modèle ${r.rssLoadedMb - r.rssBeforeMb} Mo · pic ${r.rssPeakMb} Mo · après libération ${r.rssAfterCloseMb} Mo",
        style = MaterialTheme.typography.bodySmall,
    )
    r.clips.forEach { c ->
        val idx = clips.indexOfFirst { it.id == c.clipId } + 1
        Text(
            "#$idx (${c.medianMs} ms${c.wer?.let { ", WER %.0f %%".format(it.rate * 100) } ?: ""}) : ${c.text}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
