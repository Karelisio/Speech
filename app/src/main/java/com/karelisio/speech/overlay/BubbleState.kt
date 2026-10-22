package com.karelisio.speech.overlay

import com.karelisio.speech.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** État partagé de la bulle entre l'app, le service micro et le service d'accessibilité. */
class BubbleState(private val prefs: Prefs) {
    private val _enabled = MutableStateFlow(prefs.bubbleEnabled)
    val enabled: StateFlow<Boolean> = _enabled
    /** Pause depuis la notification : bulle masquée, service conservé. */
    val paused = MutableStateFlow(false)
    val sizeDp = MutableStateFlow(prefs.bubbleSizeDp)
    val opacity = MutableStateFlow(prefs.bubbleOpacity)

    fun setEnabled(v: Boolean) {
        prefs.bubbleEnabled = v
        _enabled.value = v
    }

    fun setSize(dp: Int) {
        prefs.bubbleSizeDp = dp
        sizeDp.value = dp
    }

    fun setOpacity(v: Float) {
        prefs.bubbleOpacity = v
        opacity.value = v
    }
}
