package com.karelisio.speech.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.karelisio.speech.ui.components.LocalReduceMotion
import org.junit.Rule
import androidx.compose.runtime.CompositionLocalProvider
import com.karelisio.speech.ui.components.LocalReduceMotion
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.karelisio.speech.models.ModelCatalog
import com.karelisio.speech.models.ModelStatus
import com.karelisio.speech.ui.AppShell
import com.karelisio.speech.ui.DictationUi
import com.karelisio.speech.ui.HomeContent
import com.karelisio.speech.ui.HomeStatus
import com.karelisio.speech.ui.ModelsContent
import com.karelisio.speech.ui.PermissionsContent
import com.karelisio.speech.ui.Sub
import com.karelisio.speech.ui.Tab
import com.karelisio.speech.ui.theme.SpeechTheme
import com.karelisio.speech.ui.theme.ThemeMode
import com.karelisio.speech.util.AppPermission
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Captures d'écran de l'UI (./gradlew -Pscreenshots testDebugUnitTest --tests '*Screenshots*'). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class Screenshots {
    @get:Rule val rule = createComposeRule()

    /** Animations infinies coupées (LocalReduceMotion) : sinon la capture attend indéfiniment. */
    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                SpeechTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                    Surface(Modifier.fillMaxSize()) { content() }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private val status = HomeStatus(
        bubbleEnabled = true, paused = false, serviceRunning = true, missingPermissions = 0,
        modelName = "Parakeet TDT 0.6B v3", threads = 4, captureLabel = "48 kHz", modelLoaded = true,
    )

    @Composable
    private fun home(status: HomeStatus, ui: DictationUi, level: Float = 0f) = HomeContent(
        status, ui, level, captureSummary = null,
        onToggleBubble = {}, onOrb = {}, onCopy = {}, onAddToBench = {}, onOpenPermissions = {}, onOpenModels = {},
        recent = listOf(
            com.karelisio.speech.text.HistoryEntry(1_760_000_000_000, "euh je pense qu'on pourrait se voir mardi non pardon mercredi", "Je pense qu'on pourrait se voir mercredi.", 820),
            com.karelisio.speech.text.HistoryEntry(1_759_990_000_000, "Bonjour Marie, je t'envoie le compte rendu.", "Bonjour Marie, je t'envoie le compte rendu.", 410),
        ),
    )

    @Test @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    fun homeCleanedTall() = shot("home_cleaned_tall") {
        home(
            status.copy(cleaner = "Qwen 2.5 1.5B"),
            DictationUi(
                text = "Je pense qu'on pourrait se voir mercredi.", raw = "euh je pense qu'on pourrait bah se voir mardi non pardon mercredi",
                latencyMs = 905, audioMs = 5200, sttMs = 312, cleanMs = 593, liveSegments = 2,
            ),
        )
    }

    @Test fun homeIdle() = shot("home_idle") { home(status, DictationUi()) }

    @Test fun homeResult() = shot("home_result") {
        home(status, DictationUi(text = "Bonjour Marie, je t'envoie le compte rendu de la réunion de ce matin avant midi.", latencyMs = 412, audioMs = 6000))
    }

    @Test fun homeRecordingDark() = shot("home_recording_dark", dark = true) { home(status, DictationUi(recording = true), level = 0.6f) }

    @Test fun homeSetup() = shot("home_setup") {
        home(status.copy(missingPermissions = 2, modelName = null, modelLoaded = false, serviceRunning = false), DictationUi())
    }

    private val catalog by lazy { ModelCatalog.parse(File("src/main/assets/models.json").readText()) }

    @Composable
    private fun modelsContent() = ModelsContent(
        models = catalog,
        status = mapOf(
            catalog[0].id to ModelStatus.Ready,
            catalog[1].id to ModelStatus.Downloading(312_000_000, 874_188_075),
            catalog[2].id to ModelStatus.Absent,
            catalog[3].id to ModelStatus.Ready,
        ),
        activeId = catalog[0].id, freeBytes = 24_000_000_000, sizeOnDisk = { it.totalSize },
        onDownload = {}, onCancel = {}, onDelete = {}, onUse = {},
    )

    @Test fun models() = shot("models") { modelsContent() }

    @Test @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun modelsTall() = shot("models_tall") { modelsContent() }
    @Test fun modelsDark() = shot("models_dark", dark = true) { modelsContent() }

    @Test fun permissions() = shot("permissions") {
        PermissionsContent(
            steps = AppPermission.entries.toList(),
            granted = mapOf(AppPermission.MICROPHONE to true, AppPermission.NOTIFICATIONS to true),
            onRequest = {}, onAll = {}, onOpenSettings = {}, onAppDetails = {}, onConfirmManual = {}, onDone = {}, allOk = false,
        )
    }

    @Test fun settingsShell() = shot("settings") { AppShell(Tab.SETTINGS, null, {}, {}) }
    @Test fun settingsShellDark() = shot("settings_dark", dark = true) { AppShell(Tab.SETTINGS, null, {}, {}) }
    @Test fun benchShell() = shot("bench") { AppShell(Tab.BENCH, null, {}, {}) }
    @Test fun homeShell() = shot("home_shell") { AppShell(Tab.HOME, null, {}, {}) }
    @Test fun logShell() = shot("log") { AppShell(Tab.SETTINGS, Sub.LOG, {}, {}) }

    @Test @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun settingsTall() = shot("settings_tall") { com.karelisio.speech.ui.SettingsScreen(onOpenPermissions = {}) }

    @Test @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun benchTall() = shot("bench_tall") { com.karelisio.speech.ui.BenchmarkScreen() }

    @Test fun iconAndBubble() = shot("icon_bubble") {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp),
        ) {
            androidx.compose.foundation.layout.Box(Modifier.size(160.dp).clip(androidx.compose.foundation.shape.CircleShape)) {
                androidx.compose.foundation.Image(painterResource(com.karelisio.speech.R.drawable.ic_launcher_background), null, Modifier.size(160.dp))
                androidx.compose.foundation.Image(painterResource(com.karelisio.speech.R.drawable.ic_launcher_foreground), null, Modifier.size(160.dp))
            }
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                listOf(
                    com.karelisio.speech.dictation.DictationController.State.IDLE to 0f,
                    com.karelisio.speech.dictation.DictationController.State.RECORDING to 0.7f,
                    com.karelisio.speech.dictation.DictationController.State.PROCESSING to 0f,
                ).forEach { (st, lvl) ->
                    androidx.compose.ui.viewinterop.AndroidView({ ctx ->
                        com.karelisio.speech.overlay.BubbleView(ctx, (56 * ctx.resources.displayMetrics.density).toInt()).apply {
                            state = st; level = lvl
                        }
                    })
                }
            }
        }
    }
}
