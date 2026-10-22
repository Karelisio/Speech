package com.karelisio.speech.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.util.AppPermission
import com.karelisio.speech.util.AppPermissions
import com.karelisio.speech.ui.components.HeroCard
import com.karelisio.speech.ui.components.IconBadge
import com.karelisio.speech.ui.components.TagChip

private fun runtimePermission(p: AppPermission): String? = when (p) {
    AppPermission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
    AppPermission.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
    else -> null
}

/** Assistant d'autorisations, affiché au lancement tant qu'il manque quelque chose. */
@Composable
fun PermissionsScreen(modifier: Modifier = Modifier, onDone: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val prefs = (context.applicationContext as SpeechApp).prefs
    val steps = remember { AppPermissions.steps() }

    // Revérifie tout à chaque retour dans l'app (retour d'un écran de réglages système).
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val granted = remember(tick) { steps.associateWith { AppPermissions.isGranted(context, it, prefs) } }

    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    fun request(p: AppPermission) {
        val perm = runtimePermission(p)
        if (perm == null) {
            AppPermissions.openSettings(context, p)
            return
        }
        // Déjà refusée définitivement : Android n'affiche plus la boîte de dialogue.
        val blocked = prefs.wasAsked(perm) && activity?.shouldShowRequestPermissionRationale(perm) == false
        if (blocked) {
            AppPermissions.openSettings(context, p)
        } else {
            prefs.markAsked(perm)
            runtimeLauncher.launch(arrayOf(perm))
        }
    }

    // « Tout autoriser » : enchaîne les étapes manquantes, une seule tentative par étape.
    var chaining by remember { mutableStateOf(false) }
    val attempted = remember { mutableSetOf<AppPermission>() }
    LaunchedEffect(tick, chaining) {
        if (!chaining) return@LaunchedEffect
        val missing = steps.filter { granted[it] == false && !AppPermissions.isManual(it) && it !in attempted }
        if (missing.isEmpty()) {
            chaining = false
            return@LaunchedEffect
        }
        val runtime = missing.mapNotNull { p -> runtimePermission(p)?.let { p to it } }
        if (runtime.isNotEmpty()) {
            runtime.forEach { (p, perm) -> attempted += p; prefs.markAsked(perm) }
            runtimeLauncher.launch(runtime.map { it.second }.toTypedArray())
        } else {
            attempted += missing.first()
            request(missing.first())
        }
    }

    val allOk = steps.all { granted[it] == true }
    PermissionsContent(
        modifier = modifier,
        steps = steps,
        granted = granted,
        onRequest = ::request,
        onAll = { attempted.clear(); chaining = true; tick++ },
        onOpenSettings = { AppPermissions.openSettings(context, it) },
        onAppDetails = { AppPermissions.openAppDetails(context) },
        onConfirmManual = { prefs.magicOsConfirmed = !(granted[it] == true); tick++ },
        onDone = onDone,
        allOk = allOk,
    )
}

private fun iconFor(p: AppPermission): ImageVector = when (p) {
    AppPermission.MICROPHONE -> Icons.Rounded.Mic
    AppPermission.NOTIFICATIONS -> Icons.Rounded.Notifications
    AppPermission.BATTERY -> Icons.Rounded.BatteryChargingFull
    AppPermission.ACCESSIBILITY -> Icons.Rounded.AccessibilityNew
    AppPermission.MAGICOS -> Icons.Rounded.PhoneAndroid
}

@Composable
fun PermissionsContent(
    steps: List<AppPermission>,
    granted: Map<AppPermission, Boolean>,
    onRequest: (AppPermission) -> Unit,
    onAll: () -> Unit,
    onOpenSettings: (AppPermission) -> Unit,
    onAppDetails: () -> Unit,
    onConfirmManual: (AppPermission) -> Unit,
    onDone: () -> Unit,
    allOk: Boolean,
    modifier: Modifier = Modifier,
) {
    val s = MaterialTheme.colorScheme
    val done = steps.count { granted[it] == true }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(
                        if (allOk) Icons.Rounded.VerifiedUser else Icons.Rounded.Security,
                        container = s.surface.copy(alpha = 0.7f), content = s.primary, size = 48,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (allOk) "Tout est prêt" else "Presque prêt", style = MaterialTheme.typography.titleLarge, color = s.onPrimaryContainer)
                        Text("$done sur ${steps.size} autorisations", style = MaterialTheme.typography.bodyMedium, color = s.onPrimaryContainer.copy(alpha = 0.8f))
                    }
                }
                LinearProgressIndicator(
                    progress = { done.toFloat() / steps.size },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
                Text(
                    "Speech dicte dans n'importe quelle app. Tout reste sur le téléphone, rien n'est envoyé.",
                    style = MaterialTheme.typography.bodySmall, color = s.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
        if (!allOk) {
            Button(onClick = onAll, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Rounded.DoneAll, null); Spacer(Modifier.width(10.dp)); Text("Tout autoriser")
            }
        }
        steps.forEach { p ->
            val ok = granted[p] == true
            Surface(shape = MaterialTheme.shapes.large, color = s.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(
                            iconFor(p),
                            container = if (ok) s.tertiaryContainer else s.secondaryContainer,
                            content = if (ok) s.onTertiaryContainer else s.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(p.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (ok) TagChip("Accordé ✓", s.tertiaryContainer, s.onTertiaryContainer)
                        else TagChip("À faire", s.secondaryContainer, s.onSecondaryContainer)
                    }
                    if (!ok || AppPermissions.isManual(p)) {
                        Text(p.why, style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant)
                    }
                    when {
                        AppPermissions.isManual(p) -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { onOpenSettings(p) }) { Text("Ouvrir les réglages") }
                            TextButton(onClick = { onConfirmManual(p) }) { Text(if (ok) "Annuler" else "C'est fait") }
                        }
                        !ok && p == AppPermission.ACCESSIBILITY -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onRequest(p) }) { Text("Activer") }
                            OutlinedButton(onClick = onAppDetails) { Text("Infos de l'appli") }
                        }
                        !ok -> Button(onClick = { onRequest(p) }) { Text("Autoriser") }
                    }
                }
            }
        }
        if (allOk) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Commencer") }
        } else {
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Plus tard") }
        }
        Spacer(Modifier.height(8.dp))
    }
}
