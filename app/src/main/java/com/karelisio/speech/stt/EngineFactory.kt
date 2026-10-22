package com.karelisio.speech.stt

import com.karelisio.speech.models.EngineKind
import com.karelisio.speech.models.ModelManager
import java.io.File

object EngineFactory {
    /** Charge le modèle en mémoire (opération lente : à appeler hors du thread principal). */
    fun create(models: ModelManager, config: EngineConfig): SttEngine {
        val info = requireNotNull(models.find(config.modelId)) { "Modèle inconnu : ${config.modelId}" }
        check(models.isReady(info.id)) { "Modèle non téléchargé : ${info.name}" }
        val dir = models.dir(info)
        return when (info.engine) {
            EngineKind.WHISPER -> WhisperEngine(File(dir, info.files.first().path), config)
            EngineKind.PARAKEET -> SherpaParakeetEngine(dir, config)
        }
    }
}
