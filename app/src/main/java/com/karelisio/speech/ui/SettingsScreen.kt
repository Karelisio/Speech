package com.karelisio.speech.ui

import android.annotation.SuppressLint
import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoSizeSelectSmall
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsVoice
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.karelisio.speech.Appearance
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.audio.AudioPlayer
import com.karelisio.speech.audio.AudioRecorder
import com.karelisio.speech.audio.MicSource
import com.karelisio.speech.dictation.syncDictationService
import com.karelisio.speech.ui.components.GroupCard
import com.karelisio.speech.ui.components.GroupDivider
import com.karelisio.speech.ui.components.MenuRow
import com.karelisio.speech.ui.components.SectionTitle
import com.karelisio.speech.ui.components.SegmentedRow
import com.karelisio.speech.ui.components.SettingRow
import com.karelisio.speech.ui.components.SliderRow
import com.karelisio.speech.ui.components.SwitchRow
import com.karelisio.speech.ui.theme.ThemeMode
import com.karelisio.speech.update.UpdateState
import com.karelisio.speech.util.AppPermissions
import com.karelisio.speech.util.Prefs

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onOpenPermissions: () -> Unit, onOpenLog: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as SpeechApp
    val prefs = app.prefs
    // Les préférences ne sont pas observables : un compteur force la relecture après chaque changement.
    var rev by remember { mutableStateOf(0) }
    fun changed() { rev++ }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        @Suppress("UNUSED_EXPRESSION") rev // relit les préférences après chaque changement
        // --- Bulle ---
        val enabled by app.bubble.enabled.collectAsState()
        val size by app.bubble.sizeDp.collectAsState()
        val opacity by app.bubble.opacity.collectAsState()
        SectionTitle("Bulle")
        GroupCard {
            SwitchRow(Icons.Rounded.RecordVoiceOver, "Bulle de dictée", "Dans toutes les apps, dès qu'un champ est actif", enabled) {
                app.bubble.setEnabled(it)
                if (it) app.bubble.paused.value = false
                syncDictationService(context)
            }
            GroupDivider()
            SliderRow(Icons.Rounded.PhotoSizeSelectSmall, "Taille", "$size dp", size.toFloat(), 40f..80f, steps = 7) {
                app.bubble.setSize(it.toInt())
            }
            GroupDivider()
            SliderRow(Icons.Rounded.Opacity, "Opacité", "${(opacity * 100).toInt()} %", opacity, 0.4f..1f) { app.bubble.setOpacity(it) }
            GroupDivider()
            SwitchRow(Icons.Rounded.Keyboard, "Seulement avec le clavier", "Masquée si le clavier est fermé", prefs.bubbleOnlyWithKeyboard) {
                prefs.bubbleOnlyWithKeyboard = it; changed()
            }
            GroupDivider()
            SwitchRow(Icons.Rounded.Vibration, "Retour haptique", null, prefs.haptics) { prefs.haptics = it; changed() }
        }

        // --- Reconnaissance ---
        val modelStatus by app.models.status.collectAsState()
        val ready = remember(modelStatus) { app.models.readyModels() }
        val activeId = prefs.activeModelId?.takeIf { id -> ready.any { it.id == id } } ?: ready.firstOrNull()?.id
        SectionTitle("Reconnaissance")
        GroupCard {
            MenuRow(
                Icons.Rounded.Memory, "Modèle",
                ready.firstOrNull { it.id == activeId }?.name ?: "Aucun modèle téléchargé",
                ready.map { it.id to it.name },
            ) { prefs.activeModelId = it; changed() }
            GroupDivider()
            SegmentedRow(
                Icons.Rounded.Speed, "Threads", "Auto = ${Prefs.DEFAULT_THREADS} (le plus rapide mesuré)",
                listOf(0 to "Auto", 2 to "2", 3 to "3", 4 to "4", 6 to "6"), prefs.threads,
            ) { prefs.threads = it; changed() }
            GroupDivider()
            SegmentedRow(
                Icons.Rounded.Translate, "Langue", "Parakeet détecte la langue tout seul",
                listOf("fr" to "Français", "auto" to "Auto", "en" to "Anglais"), prefs.language,
            ) { prefs.language = it; changed() }
            GroupDivider()
            SwitchRow(Icons.Rounded.Tune, "Contexte audio réduit", "Whisper uniquement : plus rapide, moins fiable", prefs.whisperDynamicAudioCtx) {
                prefs.whisperDynamicAudioCtx = it; changed()
            }
        }

        // --- Audio ---
        SectionTitle("Micro")
        AudioGroup(app)

        // --- Apparence ---
        val appearance by app.appearance.collectAsState()
        SectionTitle("Apparence")
        GroupCard {
            SegmentedRow(
                Icons.Rounded.DarkMode, "Thème", null,
                ThemeMode.entries.map { it to it.label }, appearance.mode,
            ) { prefs.themeMode = it; app.appearance.value = Appearance(it, appearance.dynamicColor) }
            GroupDivider()
            SwitchRow(Icons.Rounded.Palette, "Couleurs du fond d'écran", "Material You ; sinon palette Speech", appearance.dynamicColor) {
                prefs.dynamicColor = it; app.appearance.value = Appearance(appearance.mode, it)
            }
        }

        // --- Système ---
        SectionTitle("Système")
        GroupCard {
            UpdateRow(app)
            GroupDivider()
            val missing = AppPermissions.steps().count { !AppPermissions.isGranted(context, it, prefs) }
            SettingRow(
                Icons.Rounded.Security, "Autorisations",
                if (missing == 0) "Tout est accordé" else "$missing à accorder",
                onClick = onOpenPermissions,
            ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }
            GroupDivider()
            SettingRow(Icons.AutoMirrored.Rounded.Article, "Journal", "Diagnostic à partager", onClick = onOpenLog) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
            }
            GroupDivider()
            SettingRow(Icons.Rounded.Info, "Speech ${app.updates.currentVersion}", "Dictée 100 % locale · Parakeet · whisper.cpp")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun AudioGroup(app: SpeechApp) {
    val context = LocalContext.current
    val prefs = app.prefs
    val recorder = app.recorder
    val report by recorder.lastReport.collectAsState()
    val level by recorder.level.collectAsState()
    val withMic = rememberWithMicPermission()
    var source by remember { mutableStateOf(prefs.micSource) }
    var rate by remember { mutableStateOf(prefs.captureRate) }
    var recording by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<FloatArray?>(null) }
    val unprocessedOk = remember {
        context.getSystemService(AudioManager::class.java)
            .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }
    fun apply() { if (!recorder.isRecording) recorder.release() }

    GroupCard {
        MenuRow(
            Icons.Rounded.SettingsVoice, "Source", source.label,
            MicSource.entries.map { it to it.label + if (it == MicSource.UNPROCESSED && !unprocessedOk) " (non garanti)" else "" },
        ) { source = it; prefs.micSource = it; apply() }
        GroupDivider()
        SegmentedRow(
            Icons.Rounded.Waves, "Capture", "48 kHz évite un défaut de rééchantillonnage du téléphone",
            listOf(48_000 to "48 kHz", AudioRecorder.SAMPLE_RATE to "16 kHz"), rate,
        ) { rate = it; prefs.captureRate = it; apply() }
        GroupDivider()
        SettingRow(
            Icons.Rounded.Mic, "Tester le micro",
            report?.summary() ?: "Enregistre quelques secondes puis réécoute",
        ) {
            FilledIconButton(
                onClick = {
                    if (recording) { last = recorder.stop(); recording = false }
                    else withMic { runCatching { recorder.start() }.onSuccess { recording = true } }
                },
                colors = if (recording) IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error) else IconButtonDefaults.filledIconButtonColors(),
            ) { Icon(if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic, null) }
            IconButton(onClick = { last?.let { AudioPlayer.play(it) } }, enabled = last != null && !recording) {
                Icon(Icons.Rounded.PlayArrow, "Réécouter")
            }
        }
        if (recording) {
            LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 12.dp))
        }
    }
}

@Composable
private fun UpdateRow(app: SpeechApp) {
    val updates = app.updates
    val state by updates.state.collectAsState()
    val subtitle = when (val s = state) {
        UpdateState.Idle -> "Version ${updates.currentVersion}"
        UpdateState.Checking -> "Recherche…"
        UpdateState.UpToDate -> "Speech est à jour"
        is UpdateState.Available -> "Version ${s.info.versionName} disponible · ${s.info.notes}"
        is UpdateState.Downloading -> "Téléchargement ${formatMb(s.done)} / ${formatMb(s.total)}"
        is UpdateState.ReadyToInstall -> "Version ${s.info.versionName} prête (checksum vérifié)"
        UpdateState.Installing -> "Installation… confirme dans la fenêtre Android"
        is UpdateState.Failed -> s.message
    }
    Column {
        SettingRow(Icons.Rounded.SystemUpdate, "Mise à jour", subtitle) {
            when (val s = state) {
                UpdateState.Idle, UpdateState.UpToDate, is UpdateState.Failed ->
                    FilledTonalButton(onClick = updates::check) { Text("Vérifier") }
                is UpdateState.Available -> Button(onClick = { updates.download(s.info) }) { Text("Télécharger") }
                is UpdateState.ReadyToInstall ->
                    if (updates.canInstall()) Button(onClick = updates::install) { Text("Installer") }
                    else Button(onClick = updates::openInstallPermission) { Text("Autoriser") }
                else -> {}
            }
        }
        val s = state
        if (s is UpdateState.Downloading || s == UpdateState.Checking || s == UpdateState.Installing) {
            val p = (s as? UpdateState.Downloading)?.let { if (it.total > 0) it.done.toFloat() / it.total else 0f }
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 12.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 12.dp))
        }
        if (s is UpdateState.ReadyToInstall && !updates.canInstall()) {
            Row(Modifier.padding(start = 72.dp, end = 16.dp, bottom = 12.dp)) {
                Text(
                    "Autorise Speech à installer des applications, puis appuie sur Installer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
