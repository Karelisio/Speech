package com.karelisio.speech.ui

import android.annotation.SuppressLint
import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.audio.AudioPlayer
import com.karelisio.speech.audio.AudioRecorder
import com.karelisio.speech.audio.MicSource

/** Réglages du micro + test d'écoute avec diagnostic (effets système, pertes, saturation). */
@SuppressLint("MissingPermission")
@Composable
fun AudioSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as SpeechApp
    val recorder = app.recorder
    val report by recorder.lastReport.collectAsState()
    val level by recorder.level.collectAsState()
    val withMic = rememberWithMicPermission()
    var source by remember { mutableStateOf(app.prefs.micSource) }
    var rate by remember { mutableStateOf(app.prefs.captureRate) }
    var recording by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<FloatArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val unprocessedOk = remember {
        context.getSystemService(AudioManager::class.java)
            .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }

    fun apply() {
        if (!recorder.isRecording) recorder.release() // recréé avec le nouveau réglage au prochain enregistrement
    }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Audio", style = MaterialTheme.typography.titleMedium)
            Text(
                "Selon la source, le téléphone applique ou non sa réduction de bruit. Enregistre un test " +
                    "avec chaque réglage, réécoute, et compare le diagnostic.",
                style = MaterialTheme.typography.bodySmall,
            )
            Dropdown(
                "Source : ${source.label}",
                MicSource.entries.map {
                    it to it.label + if (it == MicSource.UNPROCESSED && !unprocessedOk) " (non garanti sur ce téléphone)" else ""
                },
            ) { source = it; app.prefs.micSource = it; apply() }
            Dropdown(
                "Capture : " + if (rate == AudioRecorder.SAMPLE_RATE) "16 kHz (téléphone)" else "48 kHz + filtre Speech",
                listOf(AudioRecorder.SAMPLE_RATE to "16 kHz (rééchantillonné par le téléphone)", 48_000 to "48 kHz + filtre Speech"),
            ) { rate = it; app.prefs.captureRate = it; apply() }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (recording) {
                    Button(onClick = { last = recorder.stop(); recording = false }) { Text("Stop") }
                    LinearProgressIndicator(progress = { level }, modifier = Modifier.weight(1f))
                } else {
                    Button(onClick = {
                        withMic {
                            runCatching { recorder.start() }
                                .onSuccess { recording = true; error = null }
                                .onFailure { error = it.message }
                        }
                    }) { Text("Tester") }
                    OutlinedButton(onClick = { last?.let { AudioPlayer.play(it) } }, enabled = last != null) { Text("Réécouter") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            report?.let { Text(it.summary(), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
