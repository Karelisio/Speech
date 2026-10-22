package com.karelisio.speech.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.access.SpeechAccessibilityService
import com.karelisio.speech.dictation.DictationService
import com.karelisio.speech.dictation.syncDictationService
import com.karelisio.speech.ui.components.GroupCard
import com.karelisio.speech.ui.components.GroupDivider
import com.karelisio.speech.ui.components.HeroCard
import com.karelisio.speech.ui.components.IconBadge
import com.karelisio.speech.ui.components.LiveWaveform
import com.karelisio.speech.ui.components.MicOrb
import com.karelisio.speech.ui.components.OrbState
import com.karelisio.speech.ui.components.SectionTitle
import com.karelisio.speech.ui.components.SettingRow
import com.karelisio.speech.ui.components.StatRow
import com.karelisio.speech.text.HistoryEntry
import com.karelisio.speech.util.AppPermissions

/** État affiché sur l'accueil (sans dépendance Android, pour les aperçus). */
data class HomeStatus(
    val bubbleEnabled: Boolean,
    val paused: Boolean,
    val serviceRunning: Boolean,
    val missingPermissions: Int,
    val modelName: String?,
    val threads: Int,
    val captureLabel: String,
    val modelLoaded: Boolean,
    /** Nom court du modèle de nettoyage IA actif (null : nettoyage désactivé ou indisponible). */
    val cleaner: String? = null,
)

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenPermissions: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenHistory: () -> Unit = {},
    vm: DictationViewModel = viewModel(),
) {
    val context = LocalContext.current
    val app = context.applicationContext as SpeechApp
    val ui by vm.ui.collectAsState()
    val level by vm.recorder.level.collectAsState()
    val capture by vm.recorder.lastReport.collectAsState()
    val modelStatus by app.models.status.collectAsState()
    val enabled by app.bubble.enabled.collectAsState()
    val paused by app.bubble.paused.collectAsState()
    val cleaningOn by app.cleaning.collectAsState()
    val withMic = rememberWithMicPermission()
    var showSave by remember { mutableStateOf(false) }

    val cfg = remember(modelStatus) { vm.currentConfig() }
    LaunchedEffect(cfg) { vm.warmUp() }
    val status = HomeStatus(
        bubbleEnabled = enabled,
        paused = paused,
        serviceRunning = DictationService.isRunning,
        missingPermissions = AppPermissions.steps().count { !AppPermissions.isGranted(context, it, app.prefs) },
        modelName = cfg?.let { app.models.find(it.modelId)?.shortName },
        threads = cfg?.threads ?: 0,
        captureLabel = if (app.prefs.captureRate == 48_000) "48 kHz" else "16 kHz",
        modelLoaded = cfg != null && app.engines.loadedConfig == cfg,
        cleaner = app.cleanerModelId()?.takeIf { cleaningOn }?.let { app.models.find(it)?.shortName?.removeSuffix(" Instruct") },
    )
    HomeContent(
        modifier = modifier,
        status = status,
        ui = ui,
        level = level,
        captureSummary = capture?.summary(),
        onToggleBubble = {
            app.bubble.setEnabled(it)
            if (it) app.bubble.paused.value = false
            syncDictationService(context)
        },
        onOrb = { withMic { vm.toggle() } },
        onCopy = { copyText(context, "dictée", ui.text) },
        onAddToBench = { showSave = true },
        onOpenPermissions = onOpenPermissions,
        onOpenModels = onOpenModels,
        accessibilityOn = SpeechAccessibilityService.isEnabled(context),
        recent = app.history.entries.collectAsState().value.take(3),
        onCopyEntry = { copyText(context, "dictée", it.text) },
        onOpenHistory = onOpenHistory,
    )

    if (showSave) {
        var ref by remember { mutableStateOf(ui.text) }
        AlertDialog(
            onDismissRequest = { showSave = false },
            icon = { Icon(Icons.Rounded.LibraryAdd, null) },
            title = { Text("Ajouter au benchmark") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Corrige le texte pour qu'il corresponde exactement à ce que tu as dit : il sert de référence pour le taux d'erreur.")
                    OutlinedTextField(ref, { ref = it }, Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveLastAsClip(ref); showSave = false }) { Text("Ajouter") } },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Annuler") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeContent(
    status: HomeStatus,
    ui: DictationUi,
    level: Float,
    captureSummary: String?,
    onToggleBubble: (Boolean) -> Unit,
    onOrb: () -> Unit,
    onCopy: () -> Unit,
    onAddToBench: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
    accessibilityOn: Boolean = true,
    recent: List<HistoryEntry> = emptyList(),
    onCopyEntry: (HistoryEntry) -> Unit = {},
    onOpenHistory: () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (status.modelName == null || status.missingPermissions > 0) {
            SetupCard(status, onOpenPermissions, onOpenModels)
        }

        // --- Bulle ---
        HeroCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(
                        Icons.Rounded.RecordVoiceOver,
                        container = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        content = MaterialTheme.colorScheme.primary,
                        size = 48,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Bulle de dictée", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            when {
                                !status.bubbleEnabled -> "Désactivée"
                                !accessibilityOn -> "En attente de l'accessibilité"
                                status.paused -> "En pause"
                                status.serviceRunning -> "Prête dans toutes tes apps"
                                else -> "Démarrage…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                    Switch(status.bubbleEnabled, onToggleBubble)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroChip(status.modelName ?: "Aucun modèle")
                    if (status.modelName != null) HeroChip("${status.threads} threads")
                    HeroChip(status.captureLabel)
                    status.cleaner?.let { HeroChip("✦ $it") }
                    if (status.modelLoaded) HeroChip("En mémoire ✓")
                }
            }
        }

        // --- Essai rapide ---
        SectionTitle("Essai rapide")
        GroupCard {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val orb = when {
                    status.modelName == null -> OrbState.DISABLED
                    ui.processing || ui.loading -> OrbState.PROCESSING
                    ui.recording -> OrbState.RECORDING
                    else -> OrbState.IDLE
                }
                MicOrb(orb, level, onOrb, size = 132.dp)
                LiveWaveform(ui.recording, level)
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        status.modelName == null -> "Télécharge un modèle pour commencer"
                        ui.loading -> "Chargement du modèle…"
                        ui.recording -> "Je t'écoute… touche pour arrêter"
                        ui.processing -> "Transcription…"
                        else -> "Touche le micro et parle"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        AnimatedVisibility(ui.text.isNotEmpty() || ui.latencyMs != null, enter = fadeIn() + expandVertically()) {
            GroupCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SelectionContainer {
                        Text(ui.text.ifEmpty { "(rien entendu)" }, style = MaterialTheme.typography.bodyLarge)
                    }
                    ui.raw?.let { raw ->
                        Text(
                            "Brut : $raw",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ui.cleanNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    val audio = ui.audioMs ?: 0
                    val lat = ui.latencyMs ?: 0
                    ui.liveSegments?.let {
                        Text(
                            "Transcrit en direct : $it segment(s), seule la fin restait à traiter au stop.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (ui.cleanMs != null) {
                        StatRow(
                            "total" to "$lat ms",
                            "transcription" to "${ui.sttMs ?: 0} ms",
                            "nettoyage IA" to "${ui.cleanMs} ms",
                        )
                    } else {
                        StatRow(
                            "latence" to "$lat ms",
                            "audio" to "%.1f s".format(audio / 1000.0),
                            "temps réel" to if (audio > 0) "×%.2f".format(lat.toDouble() / audio) else "—",
                        )
                    }
                    captureSummary?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onCopy) {
                            Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copier")
                        }
                        FilledTonalButton(onClick = onAddToBench) {
                            Icon(Icons.Rounded.LibraryAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Benchmark")
                        }
                    }
                }
            }
        }

        // --- Historique ---
        if (recent.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Dernières dictées", Modifier.weight(1f))
                TextButton(onClick = onOpenHistory) { Text("Tout voir") }
            }
            recent.forEach { e -> HistoryCard(e, onCopy = { onCopyEntry(e) }) }
        }

        // --- Gestes ---
        SectionTitle("Utiliser la bulle")
        GroupCard {
            SettingRow(Icons.Rounded.TouchApp, "Toucher", "Démarrer, puis arrêter et insérer le texte")
            GroupDivider()
            SettingRow(Icons.Rounded.PanTool, "Appui long", "Maintenir pour parler, relâcher pour insérer")
            GroupDivider()
            SettingRow(Icons.Rounded.Close, "Glisser vers ✕", "Masquer la bulle dans cette app")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HeroChip(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SetupCard(status: HomeStatus, onOpenPermissions: () -> Unit, onOpenModels: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(10.dp))
                Text("Encore une étape", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            if (status.missingPermissions > 0) {
                Text(
                    "${status.missingPermissions} autorisation(s) manquante(s) pour dicter dans les autres apps.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (status.modelName == null) {
                Text(
                    "Aucun modèle de reconnaissance vocale téléchargé.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.missingPermissions > 0) Button(onClick = onOpenPermissions) { Text("Autorisations") }
                if (status.modelName == null) {
                    Button(onClick = onOpenModels) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Modèles")
                    }
                }
            }
        }
    }
}

