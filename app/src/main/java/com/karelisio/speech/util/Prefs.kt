package com.karelisio.speech.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

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
