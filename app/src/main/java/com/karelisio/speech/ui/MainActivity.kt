package com.karelisio.speech.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.dictation.syncDictationService
import com.karelisio.speech.ui.theme.SpeechTheme
import com.karelisio.speech.util.AppPermissions

private data class Tab(val title: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Dictée", Icons.Filled.Home),
    Tab("Modèles", Icons.AutoMirrored.Filled.List),
    Tab("Benchmark", Icons.Filled.Build),
    Tab("Réglages", Icons.Filled.Settings),
    Tab("Journal", Icons.Filled.Info),
)

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // Premier plan : seul moment où le service micro (bulle) peut démarrer.
        syncDictationService(this)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = (application as SpeechApp).prefs
        setContent {
            SpeechTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                // Assistant d'autorisations au lancement tant qu'il manque quelque chose.
                var showPermissions by rememberSaveable { mutableStateOf(!AppPermissions.allGranted(this, prefs)) }
                if (showPermissions) Scaffold(topBar = { TopAppBar(title = { Text("Speech — Autorisations") }) }) { padding ->
                    PermissionsScreen(Modifier.padding(padding), onDone = { showPermissions = false })
                } else Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Speech — ${TABS[tab].title}") },
                            actions = {
                                IconButton(onClick = { showPermissions = true }) {
                                    Icon(Icons.Filled.Lock, contentDescription = "Autorisations")
                                }
                            },
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            TABS.forEachIndexed { i, t ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Icon(t.icon, contentDescription = t.title) },
                                    label = { Text(t.title) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val m = Modifier.padding(padding)
                    when (tab) {
                        0 -> DictationScreen(m)
                        1 -> ModelsScreen(m)
                        2 -> BenchmarkScreen(m)
                        3 -> SettingsScreen(m, onOpenPermissions = { showPermissions = true })
                        else -> LogScreen(m)
                    }
                }
            }
        }
    }
}
