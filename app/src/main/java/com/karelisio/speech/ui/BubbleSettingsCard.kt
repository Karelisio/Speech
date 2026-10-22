package com.karelisio.speech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.karelisio.speech.access.SpeechAccessibilityService
import com.karelisio.speech.dictation.DictationService
import com.karelisio.speech.dictation.syncDictationService

/** Réglages de la bulle de dictée (activation, taille, opacité, retour haptique). */
@Composable
fun BubbleSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as SpeechApp
    val enabled by app.bubble.enabled.collectAsState()
    val paused by app.bubble.paused.collectAsState()
    val size by app.bubble.sizeDp.collectAsState()
    val opacity by app.bubble.opacity.collectAsState()
    val lastText by app.controller.lastResult.collectAsState()
    var haptics by remember { mutableStateOf(app.prefs.haptics) }
    var onlyKeyboard by remember { mutableStateOf(app.prefs.bubbleOnlyWithKeyboard) }
    val accessOk = SpeechAccessibilityService.isEnabled(context)

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bulle de dictée", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(enabled, {
                    app.bubble.setEnabled(it)
                    if (it) app.bubble.paused.value = false
                    syncDictationService(context)
                })
            }
            Text(
                when {
                    !accessOk -> "Accessibilité désactivée : active Speech dans les autorisations (cadenas en haut)."
                    !enabled -> "Désactivée."
                    paused -> "En pause (notification)."
                    DictationService.isRunning -> "Active : touche un champ de texte dans n'importe quelle app. " +
                        "Tap = démarrer / arrêter, appui long = maintenir pour parler, glisser vers ✕ = masquer."
                    else -> "Service micro en cours de démarrage…"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Taille : $size dp", style = MaterialTheme.typography.labelMedium)
            Slider(size.toFloat(), { app.bubble.setSize(it.toInt()) }, valueRange = 40f..80f, steps = 7)
            Text("Opacité : ${(opacity * 100).toInt()} %", style = MaterialTheme.typography.labelMedium)
            Slider(opacity, { app.bubble.setOpacity(it) }, valueRange = 0.4f..1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Seulement quand le clavier est ouvert", Modifier.weight(1f))
                Switch(onlyKeyboard, { onlyKeyboard = it; app.prefs.bubbleOnlyWithKeyboard = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Retour haptique", Modifier.weight(1f))
                Switch(haptics, { haptics = it; app.prefs.haptics = it })
            }
            lastText?.let { Text("Dernière dictée : $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
