package com.karelisio.speech.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("speech", Context.MODE_PRIVATE)

    var activeModelId: String?
        get() = sp.getString("active_model", null)
        set(v) = sp.edit { putString("active_model", v) }

    /** 0 = automatique (nombre de cœurs performance). */
    var threads: Int
        get() = sp.getInt("threads", 0)
        set(v) = sp.edit { putInt("threads", v) }

    var language: String
        get() = sp.getString("language", "fr") ?: "fr"
        set(v) = sp.edit { putString("language", v) }

    var whisperDynamicAudioCtx: Boolean
        get() = sp.getBoolean("whisper_dyn_ctx", false)
        set(v) = sp.edit { putBoolean("whisper_dyn_ctx", v) }

    fun effectiveThreads(): Int = threads.takeIf { it > 0 } ?: DeviceInfo.performanceCoreCount()
}
