package com.karelisio.speech.dictation

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.karelisio.speech.SpeechApp
import com.karelisio.speech.util.AppLog

/**
 * Activité invisible lancée par la bulle quand le service micro n'est plus actif (process
 * tué, redémarrage) : un service micro ne peut démarrer que depuis le premier plan.
 * Elle démarre le service, lance l'enregistrement et se ferme aussitôt.
 */
class MicTrampolineActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { DictationService.start(this) }
            .onFailure { AppLog.e("Service", "relance du service impossible", it) }
        (application as SpeechApp).controller.start()
        // Rester au premier plan le temps que le service passe en foreground (accès micro).
        window.decorView.postDelayed({
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }, 400)
    }
}
