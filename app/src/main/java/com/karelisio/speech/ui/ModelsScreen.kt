package com.karelisio.speech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.models.ModelStatus

@Composable
fun ModelsScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as SpeechApp
    val status by app.models.status.collectAsState()
    val free = app.filesDir.usableSpace
    var active by remember { mutableStateOf(app.prefs.activeModelId) }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Espace libre : ${formatMb(free)}. Les modèles sont téléchargés depuis Hugging Face " +
                    "(reprise automatique si la connexion coupe, vérification SHA-256).",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        items(app.models.catalog, key = { it.id }) { m ->
            val st = status[m.id] ?: ModelStatus.Absent
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium)
                    Text(m.description, style = MaterialTheme.typography.bodySmall)
                    val size = if (st == ModelStatus.Ready) app.models.sizeOnDisk(m) else m.totalSize
                    Text(
                        (if (size > 0) formatMb(size) else "taille inconnue") + " · " + when (st) {
                            ModelStatus.Absent -> "non téléchargé"
                            ModelStatus.Queued -> "en attente"
                            is ModelStatus.Downloading -> "téléchargement ${formatMb(st.done)} / ${formatMb(st.total)}"
                            ModelStatus.Verifying -> "vérification du checksum…"
                            ModelStatus.Ready -> "prêt" +  if (active == m.id) " · actif" else ""
                            is ModelStatus.Failed -> "échec : ${st.message}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    when (st) {
                        is ModelStatus.Downloading -> LinearProgressIndicator(
                            progress = { if (st.total > 0) st.done.toFloat() / st.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ModelStatus.Verifying, ModelStatus.Queued -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        else -> {}
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (st) {
                            ModelStatus.Absent, is ModelStatus.Failed ->
                                Button(onClick = { app.models.download(m.id) }) { Text("Télécharger") }
                            ModelStatus.Queued, is ModelStatus.Downloading, ModelStatus.Verifying ->
                                OutlinedButton(onClick = { app.models.cancel(m.id) }) { Text("Pause") }
                            ModelStatus.Ready -> {
                                Button(onClick = { app.prefs.activeModelId = m.id; active = m.id }) { Text("Utiliser") }
                                OutlinedButton(onClick = { app.models.delete(m.id) }) { Text("Supprimer") }
                            }
                        }
                    }
                }
            }
        }
    }
}
