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
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
        private const val SURROUNDING = 200
        private const val DOUBLE_TAP_MS = 350L

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
    private var keyboardShown = false
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
            combine(app.cleaning, app.models.status) { on, _ -> on && app.cleanerModelId() != null }
                .collect { bubble.setCleaning(it) }
        }
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
        val node = findInputNode()
        val editable = node != null
        val pkg = node?.packageName?.toString()
        if (editable) lastEditable = node
        if (pkg != null && pkg != focusedPackage) {
            focusedPackage = pkg
            if (pkg != dismissedPackage) dismissedPackage = null
        }
        val ime = imeVisible()
        if (editable != editableFocused || ime != keyboardShown) {
            editableFocused = editable
            keyboardShown = ime
            AppLog.i(TAG, if (editable) "champ éditable : $pkg (${node?.describe()}), clavier ${if (ime) "ouvert" else "fermé"}" else "plus de champ éditable")
        }
        updateVisibility()
    }

    /** Le clavier est-il affiché ? (la liste des fenêtres n'inclut l'IME que s'il est visible) */
    private fun imeVisible(): Boolean =
        runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(true)

    /** Champ de saisie en focus, toutes fenêtres confondues (dialogues, bottom sheets…). */
    private fun findInputNode(): AccessibilityNodeInfo? {
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.let { if (it.isTextInput()) return it }
        val all = runCatching { windows }.getOrNull().orEmpty()
        for (w in all) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD || w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val f = runCatching { w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            if (f != null && f.isTextInput()) return f
        }
        return null
    }

    private fun AccessibilityNodeInfo.isTextInput(): Boolean =
        !isPassword && (isEditable || actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })

    private fun AccessibilityNodeInfo.describe(): String =
        "${className?.toString()?.substringAfterLast('.')}${viewIdResourceName?.let { " #" + it.substringAfter(":id/") } ?: ""}" +
            ", editable=$isEditable, setText=${actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }}" +
            ", paste=${actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE }}"

    private fun updateVisibility() {
        if (!::bubble.isInitialized) return
        val busy = app.controller.state.value != State.IDLE
        val fieldReady = editableFocused && focusedPackage != dismissedPackage &&
            (keyboardShown || !app.prefs.bubbleOnlyWithKeyboard)
        val wanted = app.bubble.enabled.value && !app.bubble.paused.value && (busy || fieldReady)
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
        // Double tap (2ᵉ tap juste après le démarrage) : bascule du nettoyage IA, sans dicter.
        val c = app.controller
        if (c.state.value == State.RECORDING && SystemClock.elapsedRealtime() - c.recordingStartedAt < DOUBLE_TAP_MS) {
            c.cancel()
            val on = c.toggleCleaning()
            val msg = when {
                on && app.cleanerModelId() == null -> "Nettoyage IA activé (aucun modèle téléchargé)"
                on -> "Nettoyage IA activé"
                else -> "Nettoyage IA désactivé"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }
        if (c.state.value == State.IDLE && !ensureMicService()) return
        c.toggle()
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
        // Voie principale (Android 13+) : le canal de saisie du clavier, comme une frappe.
        // Fonctionne même quand l'app refuse les actions d'accessibilité sur ses vues.
        if (Build.VERSION.SDK_INT >= 33 && commitViaInputConnection(text)) return InsertOutcome.INPUT_CONNECTION
        val candidates = inputCandidates()
        if (candidates.isEmpty()) {
            copyToClipboard(text)
            AppLog.w(TAG, "insertion : aucun champ trouvé (app ${focusedPackage})")
            Toast.makeText(this, "Aucun champ actif : texte copié", Toast.LENGTH_SHORT).show()
            return InsertOutcome.CLIPBOARD_ONLY
        }
        // Essaie chaque candidat (le champ en focus d'abord) : certaines apps recréent la vue
        // du champ, l'ancienne référence refuse alors toutes les actions.
        for ((i, c) in candidates.withIndex()) {
            val (node, origin) = c
            if (trySetText(node, text, "${i + 1}/${candidates.size} $origin")) return InsertOutcome.SET_TEXT
        }
        // Dernière chance : un tap sur le champ lui redonne le focus, puis nouvel essai.
        val (first, origin) = candidates.first()
        val clicked = first.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        AppLog.i(TAG, "clic sur le champ : ${if (clicked) "ok" else "refusé"}")
        if (clicked) {
            delay(150)
            first.refresh()
            if (trySetText(first, text, "après clic, $origin")) return InsertOutcome.SET_TEXT
        }
        val before = currentText(first)
        val merged = TextMerge.merge(before, first.textSelectionStart, first.textSelectionEnd, text)
        AppLog.w(TAG, "→ SET_TEXT refusé partout, repli PASTE")
        return paste(first, merged, before, text, first.packageName)
    }

    /**
     * Insère via [android.accessibilityservice.InputMethod] (flag `flagInputMethodEditor`) :
     * lit le texte autour du curseur pour les espaces, puis `commitText` remplace la sélection.
     */
    @androidx.annotation.RequiresApi(33)
    private suspend fun commitViaInputConnection(text: String): Boolean {
        val ic = inputMethod?.currentInputConnection
        if (ic == null) {
            AppLog.w(TAG, "InputConnection indisponible (aucun champ relié au clavier)")
            return false
        }
        val st = runCatching { ic.getSurroundingText(SURROUNDING, SURROUNDING, 0) }.getOrNull()
        val piece = if (st != null && st.selectionStart >= 0) {
            TextMerge.merge(st.text.toString(), st.selectionStart, st.selectionEnd, text).piece
        } else {
            text.trim()
        }
        val pkg = inputMethod?.currentInputEditorInfo?.packageName ?: focusedPackage
        AppLog.i(
            TAG,
            "insertion InputConnection dans $pkg : contexte ${st?.text?.length ?: -1} car., " +
                "sel=${st?.selectionStart}..${st?.selectionEnd}",
        )
        runCatching { ic.commitText(piece, 1, null) }.onFailure {
            AppLog.e(TAG, "→ commitText a échoué", it)
            return false
        }
        // Vérification quand l'app expose son texte ; sinon on fait confiance au canal clavier
        // (un second essai par une autre voie risquerait de dupliquer le texte).
        val needle = piece.trim().takeLast(30)
        var after: String? = null
        var seen = false
        for (i in 0 until 3) {
            delay(80)
            after = runCatching { ic.getSurroundingText(SURROUNDING, 0, 0) }.getOrNull()?.text?.toString()
            seen = after?.contains(needle) == true
            if (after == null || seen) break
        }
        AppLog.i(TAG, "→ commitText envoyé, ${if (after == null) "texte non lisible" else if (seen) "texte vérifié" else "texte non retrouvé"}")
        if (after != null && !seen) {
            // Filet de sécurité sans risque de doublon : le texte reste disponible à coller.
            copyToClipboard(piece.trim())
            Toast.makeText(this, "Texte envoyé (et copié par sécurité)", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private suspend fun trySetText(node: AccessibilityNodeInfo, text: String, origin: String): Boolean {
        val fresh = node.refresh()
        val before = currentText(node)
        val merged = TextMerge.merge(before, node.textSelectionStart, node.textSelectionEnd, text)
        AppLog.i(
            TAG,
            "insertion [$origin] ${node.packageName} : ${node.describe()}, focus=${node.isFocused}, " +
                "visible=${node.isVisibleToUser}, refresh=$fresh, fenêtre=${node.windowId}, " +
                "sel=${node.textSelectionStart}..${node.textSelectionEnd}, ${before.length} car.",
        )
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged.text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && awaitText(node, before, text)) {
            setCursor(node, merged.cursor)
            AppLog.i(TAG, "→ SET_TEXT ok")
            return true
        }
        AppLog.w(TAG, "→ SET_TEXT ${if (ok) "sans effet visible" else "refusé"}")
        return false
    }

    /**
     * Champs candidats, du plus probable au moins probable : focus de saisie global, focus par
     * fenêtre (fenêtre active d'abord), champs éditables ayant le focus, dernier champ vu.
     */
    private fun inputCandidates(): List<Pair<AccessibilityNodeInfo, String>> {
        val first = collectCandidates()
        if (first.isEmpty() || first.any { it.first.refresh() }) return first
        // Toutes les références viennent du cache et ne correspondent plus à une vue réelle
        // (refresh() échoue) : on vide le cache et on recherche le champ, y compris par son id.
        val ids = first.mapNotNull { it.first.viewIdResourceName }.distinct()
        val cleared = if (Build.VERSION.SDK_INT >= 33) clearCache() else false
        AppLog.w(TAG, "références périmées (${first.size}), vidage du cache : $cleared, recherche par id $ids")
        val again = collectCandidates(ids)
        return again.ifEmpty { first }
    }

    private fun collectCandidates(viewIds: List<String> = emptyList()): List<Pair<AccessibilityNodeInfo, String>> {
        val out = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        fun add(n: AccessibilityNodeInfo?, origin: String) {
            if (n == null || !n.isTextInput()) return
            if (out.none { it.first == n }) out += n to origin
        }
        add(runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull(), "focus")
        val ws = runCatching { windows }.getOrNull().orEmpty()
            .filter { it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .sortedByDescending { it.isActive || it.isFocused }
        for (w in ws) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            add(runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull(), "fenêtre ${w.id}")
            for (id in viewIds) {
                runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull().orEmpty()
                    .forEach { add(it, "id ${id.substringAfter(":id/")} fenêtre ${w.id}") }
            }
        }
        // Parcours complet (coûteux en IPC) seulement si aucun candidat n'a réellement le focus.
        if (out.none { it.first.isFocused }) {
            ws.firstOrNull()?.let { w ->
                runCatching { w.root }.getOrNull()?.let { root ->
                    collectEditable(root, 0) { n -> if (n.isFocused) add(n, "parcours fenêtre ${w.id}") }
                }
            }
        }
        if (viewIds.isEmpty()) add(lastEditable?.takeIf { it.refresh() }, "dernier vu")
        return out.take(5)
    }

    private fun collectEditable(n: AccessibilityNodeInfo, depth: Int, onEditable: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 40) return
        if (n.isEditable) onEditable(n)
        for (i in 0 until n.childCount) {
            val c = runCatching { n.getChild(i) }.getOrNull() ?: continue
            collectEditable(c, depth + 1, onEditable)
        }
    }

    /** Texte réel du champ (sans le texte d'indication affiché quand il est vide). */
    private fun currentText(node: AccessibilityNodeInfo): String {
        val raw = node.text?.toString() ?: ""
        val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString() else null
        val showingHint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
        return if (showingHint || (hint != null && raw == hint)) "" else raw
    }

    /** Attend (jusqu'à ~500 ms) que le champ reflète l'insertion : certaines apps l'appliquent en différé. */
    private suspend fun awaitText(node: AccessibilityNodeInfo, before: String, inserted: String): Boolean {
        val needle = inserted.trim().take(40)
        repeat(6) {
            delay(80)
            node.refresh()
            val now = node.text?.toString() ?: ""
            if (now.contains(needle) || (now != before && now.length >= before.length + needle.length / 2)) return true
        }
        return false
    }

    private fun setCursor(node: AccessibilityNodeInfo, pos: Int) {
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    /** Repli (WebView, champs custom) : presse-papiers + ACTION_PASTE, puis restauration. */
    private suspend fun paste(
        node: AccessibilityNodeInfo,
        merged: Merged,
        before: String,
        text: String,
        pkg: CharSequence?,
    ): InsertOutcome {
        val cm = getSystemService(ClipboardManager::class.java)
        // Android 10+ : lecture souvent refusée hors premier plan → restauration impossible dans ce cas.
        val previous = runCatching { cm.primaryClip }.getOrNull()
        copyToClipboard(merged.piece)
        // Le champ doit avoir le focus et le curseur au bon endroit (la sélection d'origine).
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (node.textSelectionStart >= 0) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, merged.start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, merged.end)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        var ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (!ok) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(100)
            ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        val visible = ok && awaitText(node, before, text)
        delay(300)
        if (previous != null) runCatching { cm.setPrimaryClip(previous) }
        AppLog.i(TAG, "→ PASTE ${if (ok) "accepté" else "refusé"}, texte ${if (visible) "visible" else "non vérifié"}, presse-papiers ${if (previous != null) "restauré" else "non restaurable"} ($pkg)")
        if (!ok) {
            copyToClipboard(merged.piece)
            Toast.makeText(this, "Insertion impossible dans cette app : texte copié", Toast.LENGTH_SHORT).show()
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
