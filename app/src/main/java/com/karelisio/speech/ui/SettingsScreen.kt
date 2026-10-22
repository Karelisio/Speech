package com.karelisio.speech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.update.UpdateState

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onOpenPermissions: () -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        UpdateCard()
        BubbleSettingsCard()
        AudioSettingsCard()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Autorisations", style = MaterialTheme.typography.titleMedium)
                Text("Micro, notifications, batterie, accessibilité, réglages MagicOS.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onOpenPermissions) { Text("Vérifier les autorisations") }
            }
        }
    }
}

@Composable
private fun UpdateCard() {
    val app = LocalContext.current.applicationContext as SpeechApp
    val updates = app.updates
    val state by updates.state.collectAsState()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mise à jour", style = MaterialTheme.typography.titleMedium)
            Text("Version installée : ${updates.currentVersion}", style = MaterialTheme.typography.bodySmall)
            when (val s = state) {
                UpdateState.Idle -> Button(onClick = updates::check) { Text("Rechercher une mise à jour") }
                UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                UpdateState.UpToDate -> {
                    Text("Speech est à jour.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = updates::check) { Text("Vérifier à nouveau") }
                }
                is UpdateState.Available -> {
                    Text("Nouvelle version ${s.info.versionName} (${s.info.commit})", style = MaterialTheme.typography.bodyMedium)
                    if (s.info.notes.isNotBlank()) Text(s.info.notes, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { updates.download(s.info) }) { Text("Télécharger (${formatMb(s.info.apkSize)})") }
                }
                is UpdateState.Downloading -> {
                    Text("Téléchargement ${formatMb(s.done)} / ${formatMb(s.total)}", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is UpdateState.ReadyToInstall -> {
                    Text("Version ${s.info.versionName} prête (checksum vérifié).", style = MaterialTheme.typography.bodyMedium)
                    if (updates.canInstall()) {
                        Button(onClick = updates::install) { Text("Installer") }
                    } else {
                        Text(
                            "Autorise d'abord Speech à installer des applications, puis reviens ici.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = updates::openInstallPermission) { Text("Autoriser") }
                            OutlinedButton(onClick = updates::install) { Text("Installer") }
                        }
                    }
                }
                UpdateState.Installing -> {
                    Text("Installation… confirme dans la fenêtre Android.", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is UpdateState.Failed -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = updates::check) { Text("Réessayer") }
                }
            }
        }
    }
}
