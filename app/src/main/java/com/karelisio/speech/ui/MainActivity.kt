package com.karelisio.speech.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.dictation.syncDictationService
import com.karelisio.speech.ui.theme.SpeechTheme
import com.karelisio.speech.util.AppPermissions

enum class Tab(val title: String, val selected: ImageVector, val icon: ImageVector) {
    HOME("Accueil", Icons.Rounded.Mic, Icons.Outlined.Mic),
    MODELS("Modèles", Icons.Rounded.Download, Icons.Outlined.Download),
    BENCH("Benchmark", Icons.Rounded.Speed, Icons.Outlined.Speed),
    SETTINGS("Réglages", Icons.Rounded.Tune, Icons.Outlined.Tune),
}

/** Écrans plein écran ouverts par-dessus les onglets. */
enum class Sub(val title: String) { PERMISSIONS("Autorisations"), LOG("Journal") }

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // Premier plan : seul moment où le service micro (bulle) peut démarrer.
        syncDictationService(this)
        // Le VAD accompagne les modèles déjà téléchargés (premier plan : service autorisé).
        (application as SpeechApp).models.ensureVad()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SpeechApp
        val startWithPermissions = !AppPermissions.allGranted(this, app.prefs)
        setContent {
            val appearance by app.appearance.collectAsState()
            SpeechTheme(appearance.mode, appearance.dynamicColor) {
                var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
                var sub by rememberSaveable { mutableStateOf(if (startWithPermissions) Sub.PERMISSIONS else null) }
                AppShell(tab, sub, onTab = { tab = it }, onSub = { sub = it })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(tab: Tab, sub: Sub?, onTab: (Tab) -> Unit, onSub: (Sub?) -> Unit) {
    BackHandler(enabled = sub != null) { onSub(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text(sub?.title ?: if (tab == Tab.HOME) "Speech" else tab.title) },
                navigationIcon = {
                    if (sub != null) IconButton(onClick = { onSub(null) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            if (sub == null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { onTab(t) },
                            icon = { Icon(if (tab == t) t.selected else t.icon, contentDescription = t.title) },
                            label = { Text(t.title) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        AnimatedContent(sub to tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { (s, t) ->
            when (s) {
                Sub.PERMISSIONS -> PermissionsScreen(m, onDone = { onSub(null) })
                Sub.LOG -> LogScreen(m)
                null -> when (t) {
                    Tab.HOME -> HomeScreen(m, onOpenPermissions = { onSub(Sub.PERMISSIONS) }, onOpenModels = { onTab(Tab.MODELS) })
                    Tab.MODELS -> ModelsScreen(m)
                    Tab.BENCH -> BenchmarkScreen(m)
                    Tab.SETTINGS -> SettingsScreen(m, onOpenPermissions = { onSub(Sub.PERMISSIONS) }, onOpenLog = { onSub(Sub.LOG) })
                }
            }
        }
    }
}
