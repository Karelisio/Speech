package com.karelisio.speech.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.util.Prefs

@Composable
fun DictationScreen(modifier: Modifier = Modifier, vm: DictationViewModel = viewModel()) {
    val context = LocalContext.current
    val app = context.applicationContext as SpeechApp
    val ui by vm.ui.collectAsState()
    val level by vm.recorder.level.collectAsState()
    val status by app.models.status.collectAsState()
    val withMic = rememberWithMicPermission()
    var refresh by remember { mutableIntStateOf(0) } // force la recomposition après un changement de réglage
    var showSave by remember { mutableStateOf(false) }

    val ready = remember(status) { app.models.readyModels() }
    val cfg = remember(status, refresh) { vm.currentConfig() }
    LaunchedEffect(cfg) { vm.warmUp() }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ready.isEmpty()) {
                    Text("Aucun modèle téléchargé : va dans l'onglet Modèles.")
                } else {
                    val active = ready.firstOrNull { it.id == cfg?.modelId }
                    Dropdown("Modèle : ${active?.name ?: "—"}", ready.map { it.id to it.name }) {
                        app.prefs.activeModelId = it; refresh++
                    }
                    val def = Prefs.DEFAULT_THREADS
                    Dropdown(
                        "Threads : " + if (app.prefs.threads == 0) "auto ($def)" else "${app.prefs.threads}",
                        listOf(0 to "auto ($def)") + (1..Runtime.getRuntime().availableProcessors()).map { it to "$it" },
                    ) { app.prefs.threads = it; refresh++ }
                    Dropdown(
                        "Langue : ${app.prefs.language}",
                        listOf("fr" to "Français", "auto" to "Détection auto", "en" to "Anglais"),
                    ) { app.prefs.language = it; refresh++ }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Whisper : audio_ctx dynamique", Modifier.weight(1f))
                        Switch(app.prefs.whisperDynamicAudioCtx, { app.prefs.whisperDynamicAudioCtx = it; refresh++ })
                    }
                }
            }
        }

        val scale by animateFloatAsState(if (ui.recording) 1f + level * 0.6f else 1f, label = "level")
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(120.dp).scale(scale).clip(CircleShape)
                    .background(if (ui.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    .clickable(enabled = ready.isNotEmpty() && !ui.processing) { withMic { vm.toggle() } },
                contentAlignment = Alignment.Center,
            ) {
                if (ui.processing || ui.loading) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (ui.recording) "Stop" else "Parler", color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        Text(
            when {
                ui.loading -> "Chargement du modèle…"
                ui.recording -> "Enregistrement… touche pour arrêter"
                ui.processing -> "Transcription…"
                else -> "Touche pour dicter"
            },
            Modifier.align(Alignment.CenterHorizontally),
        )

        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (ui.text.isNotEmpty() || ui.latencyMs != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer { Text(ui.text.ifEmpty { "(vide)" }, style = MaterialTheme.typography.bodyLarge) }
                    val audio = ui.audioMs ?: 0
                    val lat = ui.latencyMs ?: 0
                    Text(
                        "Latence après stop : $lat ms · audio ${"%.1f".format(audio / 1000.0)} s · " +
                            "RTF ${if (audio > 0) "%.3f".format(lat.toDouble() / audio) else "—"}\n" +
                            "Ouverture micro : ${ui.micOpenMs ?: "—"} ms · chargement modèle : ${ui.loadMs ?: "—"} ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyText(context, "dictée", ui.text) }) { Text("Copier") }
                        OutlinedButton(onClick = { showSave = true }) { Text("Ajouter au benchmark") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showSave) {
        var ref by remember { mutableStateOf(ui.text) }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Ajouter aux clips de benchmark") },
            text = {
                Column {
                    Text("Corrige le texte pour qu'il corresponde exactement à ce que tu as dit (référence pour le WER).")
                    OutlinedTextField(ref, { ref = it }, Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveLastAsClip(ref); showSave = false }) { Text("Ajouter") } },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Annuler") } },
        )
    }
}
