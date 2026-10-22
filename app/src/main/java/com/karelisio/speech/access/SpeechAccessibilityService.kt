package com.karelisio.speech.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.dictation.DictationController.State
import com.karelisio.speech.dictation.DictationService
import com.karelisio.speech.dictation.InsertOutcome
import com.karelisio.speech.dictation.MicTrampolineActivity
import com.karelisio.speech.dictation.TextSink
import com.karelisio.speech.overlay.BubbleController
import com.karelisio.speech.util.AppLog
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Détecte le champ éditable en focus (affiche / masque la bulle) et insère le texte dicté
 * à la position du curseur.
 */
class SpeechAccessibilityService : AccessibilityService(), BubbleController.Listener, TextSink {
    companion object {
        private const val TAG = "Access"

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, SpeechAccessibilityService::class.java)
            return context.getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.let { s -> s.packageName == me.packageName && s.name == me.className } }
        }
    }

    private val app get() = application as SpeechApp
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bubble: BubbleController

    private var editableFocused = false
    private var focusedPackage: String? = null
    /** Bulle masquée via la zone ✕ : elle revient dans une autre app. */
    private var dismissedPackage: String? = null
    private var lastEditable: AccessibilityNodeInfo? = null

    private val recheck = Runnable { refreshFocus() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        bubble = BubbleController(this, app.prefs, this)
        app.controller.sink = this
        AppLog.i(TAG, "service d'accessibilité connecté")
        scope.launch {
            var previous = State.IDLE
            app.controller.state.collect { s ->
                bubble.setState(s)
                if (previous == State.RECORDING && s != State.RECORDING) bubble.setLevel(0f)
                previous = s
                updateVisibility()
            }
        }
        scope.launch { app.controller.level.collect { bubble.setLevel(it) } }
        scope.launch {
            combine(app.bubble.enabled, app.bubble.paused) { e, p -> e to p }.collect { updateVisibility() }
        }
        scope.launch {
            combine(app.bubble.sizeDp, app.bubble.opacity) { s, o -> s to o }.collect { (s, o) -> bubble.applyAppearance(s, o) }
        }
        refreshFocus()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Regroupe les rafales d'événements (focus + sélection + fenêtre) en une seule vérification.
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, 60)
    }

    private fun refreshFocus() {
        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        val editable = node != null && node.isEditable && !node.isPassword
        val pkg = node?.packageName?.toString()
        if (editable) lastEditable = node
        if (pkg != null && pkg != focusedPackage) {
            focusedPackage = pkg
            if (pkg != dismissedPackage) dismissedPackage = null
        }
        if (editable != editableFocused) {
            editableFocused = editable
            AppLog.i(TAG, if (editable) "champ éditable : $pkg" else "plus de champ éditable")
        }
        updateVisibility()
    }

    private fun updateVisibility() {
        if (!::bubble.isInitialized) return
        val busy = app.controller.state.value != State.IDLE
        val wanted = app.bubble.enabled.value && !app.bubble.paused.value &&
            (busy || (editableFocused && focusedPackage != dismissedPackage))
        if (wanted) bubble.show() else bubble.hide()
    }

    // --- Gestes de la bulle ---

    /** Le micro n'est utilisable en arrière-plan que si le service de premier plan tourne. */
    private fun ensureMicService(): Boolean {
        if (DictationService.isRunning) return true
        AppLog.i(TAG, "service micro inactif : relance via l'activité relais")
        startActivity(Intent(this, MicTrampolineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }

    override fun onTap() {
        if (app.dictationConfig() == null) {
            Toast.makeText(this, "Speech : aucun modèle téléchargé", Toast.LENGTH_SHORT).show()
            return
        }
        if (app.controller.state.value == State.IDLE && !ensureMicService()) return
        app.controller.toggle()
    }

    override fun onHoldStart() {
        if (app.controller.state.value != State.IDLE) return
        if (app.dictationConfig() == null || !ensureMicService()) return
        app.controller.start()
    }

    override fun onHoldEnd() = app.controller.stop()

    override fun onDismiss() {
        app.controller.cancel()
        dismissedPackage = focusedPackage
        updateVisibility()
    }

    // --- Insertion ---

    override suspend fun insert(text: String): InsertOutcome {
        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.takeIf { it.isEditable }
            ?: lastEditable?.takeIf { it.refresh() && it.isEditable }
        if (node == null) {
            copyToClipboard(text)
            Toast.makeText(this, "Aucun champ actif : texte copié", Toast.LENGTH_SHORT).show()
            return InsertOutcome.CLIPBOARD_ONLY
        }
        val pkg = node.packageName
        val raw = node.text?.toString() ?: ""
        val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString() else null
        val showingHint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
        val existing = if (showingHint || (hint != null && raw == hint)) "" else raw
        val merged = TextMerge.merge(existing, node.textSelectionStart, node.textSelectionEnd, text)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged.text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            delay(120) // certaines apps (WebView) appliquent le texte de façon asynchrone
            node.refresh()
            if ((node.text?.toString() ?: "").contains(text.trim())) {
                val sel = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, merged.cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, merged.cursor)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
                AppLog.i(TAG, "insertion SET_TEXT dans $pkg")
                return InsertOutcome.SET_TEXT
            }
        }
        return paste(node, merged.piece, pkg)
    }

    /** Repli (WebView, champs custom) : presse-papiers + ACTION_PASTE, puis restauration. */
    private suspend fun paste(node: AccessibilityNodeInfo, piece: String, pkg: CharSequence?): InsertOutcome {
        val cm = getSystemService(ClipboardManager::class.java)
        // Android 10+ : lecture souvent refusée hors premier plan → restauration impossible dans ce cas.
        val previous = runCatching { cm.primaryClip }.getOrNull()
        copyToClipboard(piece)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        delay(400)
        if (previous != null) runCatching { cm.setPrimaryClip(previous) }
        AppLog.i(TAG, "insertion PASTE dans $pkg : ${if (ok) "ok" else "échec"}, presse-papiers ${if (previous != null) "restauré" else "non restaurable"}")
        if (!ok) {
            copyToClipboard(piece)
            Toast.makeText(this, "Insertion impossible : texte copié", Toast.LENGTH_SHORT).show()
            return InsertOutcome.CLIPBOARD_ONLY
        }
        return InsertOutcome.PASTE
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Speech", text))
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        handler.removeCallbacks(recheck)
        if (::bubble.isInitialized) bubble.hide()
        if (app.controller.sink === this) app.controller.sink = null
        scope.cancel()
    }
}
