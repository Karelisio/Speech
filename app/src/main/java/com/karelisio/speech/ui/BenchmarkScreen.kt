package com.karelisio.speech.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ShortText
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.bench.BenchClip
import com.karelisio.speech.bench.ConfigResult
import com.karelisio.speech.bench.REFERENCE_SENTENCES
import com.karelisio.speech.ui.components.GroupCard
import com.karelisio.speech.ui.components.GroupDivider
import com.karelisio.speech.ui.components.LiveWaveform
import com.karelisio.speech.ui.components.SectionTitle
import com.karelisio.speech.ui.components.SegmentedRow
import com.karelisio.speech.ui.components.SettingRow
import com.karelisio.speech.ui.components.StatRow
import com.karelisio.speech.ui.components.SwitchRow
import com.karelisio.speech.ui.theme.RecordRed
import com.karelisio.speech.vad.VadMode

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
    val s = MaterialTheme.colorScheme

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
    val free = freeMode || nextSentence == null
    val currentRef = if (free) freeRef else nextSentence

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Mesure la latence, la RAM et la précision de chaque modèle sur tes propres phrases.",
            style = MaterialTheme.typography.bodyMedium,
            color = s.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        // --- Clips ---
        SectionTitle("1 · Clips de test")
        GroupCard {
            SettingRow(
                Icons.Rounded.LibraryMusic, "${clips.size} clip(s)",
                "%.1f s d'audio au total".format(clips.sumOf { it.durationMs } / 1000.0),
            ) {
                if (clips.isNotEmpty()) TextButton(onClick = vm::deleteAll, enabled = !ui.running) { Text("Tout effacer") }
            }
            GroupDivider()
            SwitchRow(Icons.Rounded.ShortText, "Phrase libre", "Sinon, lis les phrases proposées", free) {
                if (nextSentence != null) freeMode = it
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (free) {
                    OutlinedTextField(
                        freeRef, { freeRef = it }, Modifier.fillMaxWidth(),
                        label = { Text("Ce que tu vas dire (référence, optionnel)") },
                    )
                } else {
                    Surface(shape = MaterialTheme.shapes.medium, color = s.secondaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.FormatQuote, null, tint = s.onSecondaryContainer, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "À lire · ${REFERENCE_SENTENCES.indexOf(nextSentence) + 1}/${REFERENCE_SENTENCES.size}",
                                    style = MaterialTheme.typography.labelMedium, color = s.onSecondaryContainer,
                                )
                            }
                            Text(nextSentence!!, style = MaterialTheme.typography.titleMedium, color = s.onSecondaryContainer)
                        }
                    }
                }
                if (ui.recording) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { vm.stopRecording(currentRef); freeRef = "" },
                            colors = ButtonDefaults.buttonColors(containerColor = RecordRed),
                        ) { Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Arrêter") }
                        LiveWaveform(true, level, bars = 18, height = 32.dp)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { withMic { vm.startRecording() } }, enabled = !ui.running) {
                            Icon(Icons.Rounded.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Enregistrer")
                        }
                        OutlinedButton(onClick = { importer.launch(arrayOf("audio/*")) }, enabled = !ui.running) {
                            Icon(Icons.Rounded.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Importer")
                        }
                    }
                }
            }
            clips.forEachIndexed { i, c ->
                GroupDivider()
                ClipRow(i + 1, c, !ui.running, onPlay = { vm.play(c) }, onShare = { vm.share(context, c) },
                    onEdit = { editing = c }, onDelete = { vm.delete(c) })
            }
        }

        // --- Configurations ---
        SectionTitle("2 · Configurations")
        GroupCard {
            val ready = remember(status) { app.models.readyModels() }
            SettingRow(Icons.Rounded.Memory, "Modèles", if (ready.isEmpty()) "Aucun modèle téléchargé" else null)
            FlowRow(
                Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ready.forEach { m ->
                    val on = m.id in ui.selectedModels
                    FilterChip(
                        on, { vm.toggleModel(m.id) }, label = { Text(m.shortName) },
                        leadingIcon = if (on) ({ Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                    )
                }
            }
            GroupDivider()
            SettingRow(Icons.Rounded.Speed, "Threads", "Plusieurs choix = une configuration par valeur")
            FlowRow(
                Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vm.threadChoices.forEach { t -> FilterChip(t in ui.threads, { vm.toggleThreads(t) }, label = { Text("$t") }) }
            }
            GroupDivider()
            SettingRow(
                Icons.Rounded.GraphicEq, "Modes VAD",
                if (vm.vadReady) "Bloc entier, silences coupés, segments aux pauses" else "VAD non téléchargé : bloc entier seulement",
            )
            FlowRow(
                Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VadMode.entries.forEach { m ->
                    FilterChip(
                        m in ui.modes, { vm.toggleMode(m) }, label = { Text(m.label) },
                        enabled = m == VadMode.OFF || vm.vadReady,
                    )
                }
            }
            GroupDivider()
            SwitchRow(Icons.Rounded.Tune, "Whisper : contexte audio réduit", "Compare aussi la variante rapide", ui.compareWhisperCtx, vm::setCompareCtx)
            GroupDivider()
            SwitchRow(
                Icons.Rounded.AutoAwesome, "Nettoyage IA",
                if (vm.llmReady) "Mesure aussi le nettoyage (temps et taux d'erreur)" else "Aucun modèle de langage téléchargé",
                ui.clean && vm.llmReady,
            ) { if (vm.llmReady) vm.setClean(it) }
            GroupDivider()
            SegmentedRow(Icons.Rounded.Repeat, "Répétitions par clip", "La médiane est retenue", listOf(1 to "1", 3 to "3"), ui.repetitions, vm::setRepetitions)
            GroupDivider()
            val n = vm.plannedConfigs().size * (if (vm.vadReady) ui.modes.size else 1)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ui.running) {
                    LinearProgressIndicator(
                        progress = { ui.progress }, modifier = Modifier.fillMaxWidth().height(8.dp),
                        strokeCap = StrokeCap.Round, drawStopIndicator = {},
                    )
                    Text(ui.progressText, style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant)
                    OutlinedButton(onClick = vm::cancel) { Text("Annuler") }
                } else {
                    Button(onClick = vm::run, enabled = n > 0 && clips.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp))
                        Text("Lancer · $n config × ${clips.size} clip")
                    }
                }
            }
        }

        ui.message?.let { Text(it, color = s.error, modifier = Modifier.padding(horizontal = 8.dp)) }

        // --- Résultats ---
        if (ui.results.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("3 · Résultats", Modifier.weight(1f))
                IconButton(onClick = { copyText(context, "benchmark", vm.report()) }) { Icon(Icons.Rounded.ContentCopy, "Copier") }
                FilledTonalButton(onClick = { shareText(context, "Speech — benchmark", vm.report()) }) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Exporter")
                }
            }
            ui.results.forEach { r -> ResultCard(r, clips) }
        }
        Spacer(Modifier.height(16.dp))
    }

    editing?.let { clip ->
        var ref by remember(clip.id) { mutableStateOf(clip.reference ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            icon = { Icon(Icons.Rounded.Edit, null) },
            title = { Text("Texte de référence") },
            text = { OutlinedTextField(ref, { ref = it }, Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.updateReference(clip, ref); editing = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun ClipRow(
    index: Int,
    c: BenchClip,
    enabled: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(s.primaryContainer), contentAlignment = Alignment.Center) {
            Text("$index", style = MaterialTheme.typography.labelLarge, color = s.onPrimaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(c.reference ?: "(sans référence)", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "%.1f s".format(c.durationMs / 1000.0) + (c.source?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = s.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, "Écouter", tint = s.primary) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Plus") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Modifier la référence") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Partager le WAV") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { menu = false; onShare() })
                DropdownMenuItem(
                    text = { Text("Supprimer") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                    onClick = { menu = false; onDelete() }, enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(r: ConfigResult, clips: List<BenchClip>) {
    val s = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    GroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(r.label, style = MaterialTheme.typography.titleSmall)
            if (r.error != null) {
                Text("Erreur : ${r.error}", color = s.error, style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            StatRow(
                "moyenne" to "${r.meanLatencyMs} ms",
                "pour 10 s" to "${r.latency10sMs} ms",
                "erreurs" to (r.wer?.let { "%.1f %%".format(it.rate * 100) } ?: "—"),
            )
            r.meanCleanMs?.let { ms ->
                StatRow(
                    "nettoyage IA" to "$ms ms",
                    "erreurs après IA" to (r.cleanedWer?.let { "%.1f %%".format(it.rate * 100) } ?: "—"),
                )
            }
            Text(
                "RTF %.3f · VAD %d ms · chargement %d ms · RAM modèle %d Mo · pic %d Mo".format(
                    r.rtf, r.meanVadMs, r.loadMs, r.rssLoadedMb - r.rssBeforeMb, r.rssPeakMb,
                ),
                style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant,
            )
            TextButton(onClick = { open = !open }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Text(if (open) "Masquer les transcriptions" else "Voir les transcriptions")
            }
            AnimatedVisibility(open) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.clips.forEach { c ->
                        val idx = clips.indexOfFirst { it.id == c.clipId } + 1
                        Text(
                            "#$idx · ${c.medianMs} ms" + (c.wer?.let { " · %.0f %%".format(it.rate * 100) } ?: "") + " — ${c.text}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        c.cleanedText?.let { t ->
                            Text(
                                "   ✦ ${c.cleanMs} ms" + (c.cleanedWer?.let { " · %.0f %%".format(it.rate * 100) } ?: "") + " — $t",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
