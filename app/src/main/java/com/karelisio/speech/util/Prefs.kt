package com.karelisio.speech.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.karelisio.speech.audio.AudioRecorder
import com.karelisio.speech.audio.CaptureConfig
import com.karelisio.speech.audio.MicSource
import com.karelisio.speech.ui.theme.ThemeMode
import com.karelisio.speech.vad.VadMode

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("speech", Context.MODE_PRIVATE)

    var activeModelId: String?
        get() = sp.getString("active_model", null)
        set(v) = sp.edit { putString("active_model", v) }

    /** 0 = valeur par défaut mesurée ([DEFAULT_THREADS]). */
    var threads: Int
        get() = sp.getInt("threads", 0)
        set(v) = sp.edit { putInt("threads", v) }

    var language: String
        get() = sp.getString("language", "fr") ?: "fr"
        set(v) = sp.edit { putString("language", v) }

    var whisperDynamicAudioCtx: Boolean
        get() = sp.getBoolean("whisper_dyn_ctx", false)
        set(v) = sp.edit { putBoolean("whisper_dyn_ctx", v) }

    /** Réglages MagicOS (lancement auto, arrière-plan) confirmés par l'utilisateur. */
    var magicOsConfirmed: Boolean
        get() = sp.getBoolean("magicos_ok", false)
        set(v) = sp.edit { putBoolean("magicos_ok", v) }

    var bubbleEnabled: Boolean
        get() = sp.getBoolean("bubble_enabled", true)
        set(v) = sp.edit { putBoolean("bubble_enabled", v) }

    var bubbleSizeDp: Int
        get() = sp.getInt("bubble_size", 56)
        set(v) = sp.edit { putInt("bubble_size", v) }

    var bubbleOpacity: Float
        get() = sp.getFloat("bubble_opacity", 0.9f)
        set(v) = sp.edit { putFloat("bubble_opacity", v) }

    /** Bulle affichée seulement quand le clavier est ouvert (un champ peut garder le focus sans clavier). */
    var bubbleOnlyWithKeyboard: Boolean
        get() = sp.getBoolean("bubble_only_keyboard", true)
        set(v) = sp.edit { putBoolean("bubble_only_keyboard", v) }

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit { putBoolean("haptics", v) }

    /** Position de la bulle : bord (true = droite) et ordonnée en fraction de la hauteur. */
    var bubbleRight: Boolean
        get() = sp.getBoolean("bubble_right", true)
        set(v) = sp.edit { putBoolean("bubble_right", v) }

    var bubbleY: Float
        get() = sp.getFloat("bubble_y", 0.35f)
        set(v) = sp.edit { putFloat("bubble_y", v) }

    var micSource: MicSource
        get() = runCatching { MicSource.valueOf(sp.getString("mic_source", null)!!) }.getOrDefault(MicSource.VOICE_RECOGNITION)
        set(v) = sp.edit { putString("mic_source", v.name) }

    /**
     * 16000 = rééchantillonnage par le téléphone ; 48000 = capture native + filtre de Speech.
     * Mesure Magic7 Pro : en 16 kHz le téléphone livre ~18 % d'échantillons en trop (fragments
     * dupliqués, son « ventilateur ») ; en 48 kHz le débit est exact. 48 kHz par défaut.
     */
    var captureRate: Int
        get() = sp.getInt("capture_rate_v2", 48_000)
        set(v) = sp.edit { putInt("capture_rate_v2", v) }

    fun captureConfig() = CaptureConfig(micSource, captureRate)

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(sp.getString("theme_mode", null)!!) }.getOrDefault(ThemeMode.SYSTEM)
        set(v) = sp.edit { putString("theme_mode", v.name) }

    var dynamicColor: Boolean
        get() = sp.getBoolean("dynamic_color", true)
        set(v) = sp.edit { putBoolean("dynamic_color", v) }

    /** Mesure à confirmer au benchmark ; « silences coupés » évite d'insérer du texte quand rien n'est dit. */
    var vadMode: VadMode
        get() = runCatching { VadMode.valueOf(sp.getString("vad_mode", null)!!) }.getOrDefault(VadMode.TRIM)
        set(v) = sp.edit { putString("vad_mode", v.name) }

    /** Nettoyage IA demandé (effectif seulement si un modèle de langage est téléchargé). */
    var cleaningEnabled: Boolean
        get() = sp.getBoolean("cleaning", true)
        set(v) = sp.edit { putBoolean("cleaning", v) }

    var activeLlmId: String?
        get() = sp.getString("active_llm", null)
        set(v) = sp.edit { putString("active_llm", v) }

    var llmThreads: Int
        get() = sp.getInt("llm_threads", DEFAULT_THREADS)
        set(v) = sp.edit { putInt("llm_threads", v) }

    fun wasAsked(permission: String) = sp.getBoolean("asked_$permission", false)
    fun markAsked(permission: String) = sp.edit { putBoolean("asked_$permission", true) }

    fun effectiveThreads(): Int = threads.takeIf { it > 0 } ?: DEFAULT_THREADS

    companion object {
        /**
         * Benchmark Magic7 Pro (étape 1) : Parakeet 397 ms à 4 threads, 681 ms à 6, 849 ms à 8 ;
         * Whisper s'effondre à 8 threads. 4 est le meilleur compromis mesuré.
         */
        const val DEFAULT_THREADS = 4
    }
}
