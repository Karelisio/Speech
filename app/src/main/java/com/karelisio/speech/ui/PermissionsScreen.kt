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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.util.AppPermission
import com.karelisio.speech.util.AppPermissions

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
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Speech a besoin de ces autorisations pour dicter dans n'importe quelle app. " +
                "Tout reste sur le téléphone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!allOk) {
            Button(onClick = { attempted.clear(); chaining = true; tick++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Tout autoriser")
            }
        }
        steps.forEach { p ->
            val ok = granted[p] == true
            Card(
                Modifier.fillMaxWidth(),
                colors = if (ok) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                else CardDefaults.cardColors(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text((if (ok) "✓ " else "• ") + p.title, style = MaterialTheme.typography.titleMedium)
                    Text(p.why, style = MaterialTheme.typography.bodySmall)
                    if (AppPermissions.isManual(p)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { AppPermissions.openSettings(context, p) }) { Text("Ouvrir les réglages") }
                            TextButton(onClick = { prefs.magicOsConfirmed = !ok; tick++ }) {
                                Text(if (ok) "Annuler" else "C'est fait")
                            }
                        }
                    } else if (!ok) {
                        Button(onClick = { request(p) }) { Text("Autoriser") }
                    }
                }
            }
        }
        Text(
            "L'accès à l'accessibilité (insertion du texte dans les autres apps) sera ajouté avec la bulle.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (allOk) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Continuer") }
        } else {
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Plus tard") }
        }
    }
}
