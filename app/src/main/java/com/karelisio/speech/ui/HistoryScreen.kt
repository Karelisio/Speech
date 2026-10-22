package com.karelisio.speech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.text.History
import com.karelisio.speech.text.HistoryEntry
import com.karelisio.speech.ui.components.IconBadge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("d MMM · HH:mm", Locale.FRANCE)

@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as SpeechApp
    val entries by app.history.entries.collectAsState()
    if (entries.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.History, size = 64)
                Text(
                    "Tes ${History.MAX} dernières dictées apparaîtront ici. Elles restent sur le téléphone.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${entries.size} dictée(s)", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { app.history.clear() }) { Text("Tout effacer") }
            }
        }
        items(entries, key = { it.time }) { e ->
            HistoryCard(e, onCopy = { copyText(context, "dictée", e.text) }, onDelete = { app.history.delete(e) })
        }
    }
}

@Composable
fun HistoryCard(e: HistoryEntry, onCopy: () -> Unit, onDelete: (() -> Unit)? = null) {
    val s = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = s.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${timeFormat.format(Date(e.time))} · ${e.latencyMs} ms",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = s.onSurfaceVariant,
                )
                IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, "Copier", tint = s.primary) }
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Supprimer") }
            }
            Text(e.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 12.dp))
            if (e.raw != e.text) {
                Text(
                    "Brut : ${e.raw}",
                    style = MaterialTheme.typography.bodySmall,
                    color = s.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, end = 12.dp),
                )
            }
        }
    }
}
