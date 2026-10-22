package com.karelisio.speech.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.models.EngineKind
import com.karelisio.speech.models.ModelInfo
import com.karelisio.speech.models.ModelStatus
import com.karelisio.speech.ui.components.IconBadge
import com.karelisio.speech.ui.components.TagChip

@Composable
fun ModelsScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as SpeechApp
    val status by app.models.status.collectAsState()
    var active by remember { mutableStateOf(app.prefs.activeModelId ?: app.models.readyModels().firstOrNull()?.id) }
    ModelsContent(
        modifier = modifier,
        models = app.models.catalog,
        status = status,
        activeId = active,
        freeBytes = app.filesDir.usableSpace,
        sizeOnDisk = { app.models.sizeOnDisk(it) },
        onDownload = { app.models.download(it) },
        onCancel = { app.models.cancel(it) },
        onDelete = { app.models.delete(it) },
        onUse = { app.prefs.activeModelId = it; active = it },
    )
}

@Composable
fun ModelsContent(
    models: List<ModelInfo>,
    status: Map<String, ModelStatus>,
    activeId: String?,
    freeBytes: Long,
    sizeOnDisk: (ModelInfo) -> Long,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onUse: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val used = models.filter { status[it.id] == ModelStatus.Ready }.sumOf(sizeOnDisk)
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StorageCard(used, freeBytes) }
        items(models, key = { it.id }) { m ->
            ModelCard(
                m, status[m.id] ?: ModelStatus.Absent, active = m.id == activeId,
                size = if (status[m.id] == ModelStatus.Ready) sizeOnDisk(m) else m.totalSize,
                onDownload = { onDownload(m.id) }, onCancel = { onCancel(m.id) },
                onDelete = { onDelete(m.id) }, onUse = { onUse(m.id) },
            )
        }
        item {
            Text(
                "Téléchargés depuis Hugging Face, une seule fois. Reprise automatique si la connexion coupe, " +
                    "vérification SHA-256. Ensuite tout fonctionne hors ligne.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun StorageCard(used: Long, free: Long) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.SdStorage)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Stockage", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(
                    progress = { if (used + free > 0) used.toFloat() / (used + free) else 0f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
                Text(
                    "Modèles ${formatMb(used)} · libre ${formatMb(free)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    m: ModelInfo,
    st: ModelStatus,
    active: Boolean,
    size: Long,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onUse: () -> Unit,
) {
    val s = MaterialTheme.colorScheme
    val ready = st == ModelStatus.Ready
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (active && ready) s.secondaryContainer.copy(alpha = 0.55f) else s.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    if (m.engine == EngineKind.PARAKEET) Icons.Rounded.Bolt else Icons.Rounded.GraphicEq,
                    container = s.primaryContainer, content = s.onPrimaryContainer, size = 48,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.shortName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        (if (m.engine == EngineKind.PARAKEET) "sherpa-onnx" else "whisper.cpp") +
                            " · " + m.name.substringAfter("· ", "") + " · " + formatMb(size),
                        style = MaterialTheme.typography.bodySmall,
                        color = s.onSurfaceVariant,
                    )
                }
                if (active && ready) Icon(Icons.Rounded.CheckCircle, "Actif", tint = s.primary)
            }
            if (m.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    m.tags.forEach { t ->
                        val (bg, fg) = when (t) {
                            "Recommandé" -> s.primary to s.onPrimary
                            "Rapide", "Léger" -> s.tertiaryContainer to s.onTertiaryContainer
                            "Lent sur CPU" -> s.errorContainer to s.onErrorContainer
                            else -> s.secondaryContainer to s.onSecondaryContainer
                        }
                        TagChip(t, bg, fg)
                    }
                }
            }
            Text(m.description, style = MaterialTheme.typography.bodyMedium, color = s.onSurfaceVariant)

            when (st) {
                is ModelStatus.Downloading -> {
                    val p = if (st.total > 0) st.done.toFloat() / st.total else 0f
                    LinearProgressIndicator(
                        progress = { p }, modifier = Modifier.fillMaxWidth().height(8.dp),
                        strokeCap = StrokeCap.Round, drawStopIndicator = {},
                    )
                    Text("${(p * 100).toInt()} % · ${formatMb(st.done)} / ${formatMb(st.total)}", style = MaterialTheme.typography.labelMedium)
                }
                ModelStatus.Verifying, ModelStatus.Queued -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp), strokeCap = StrokeCap.Round)
                    Text(if (st == ModelStatus.Queued) "En attente…" else "Vérification du checksum…", style = MaterialTheme.typography.labelMedium)
                }
                is ModelStatus.Failed -> Text("Échec : ${st.message}", style = MaterialTheme.typography.bodySmall, color = s.error)
                else -> {}
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (st) {
                    ModelStatus.Absent, is ModelStatus.Failed -> Button(onClick = onDownload) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (st is ModelStatus.Failed) "Réessayer" else "Télécharger")
                    }
                    ModelStatus.Queued, is ModelStatus.Downloading, ModelStatus.Verifying -> OutlinedButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Pause")
                    }
                    ModelStatus.Ready -> {
                        if (active) {
                            Text("Modèle actif", style = MaterialTheme.typography.labelLarge, color = s.primary, modifier = Modifier.padding(start = 4.dp))
                        } else {
                            FilledTonalButton(onClick = onUse) { Text("Utiliser") }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Supprimer", tint = s.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}
