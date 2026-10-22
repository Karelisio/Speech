package com.karelisio.speech.clean

data class CleanResult(
    /** Texte à insérer : nettoyé, ou brut si le garde-fou a rejeté la sortie du modèle. */
    val text: String,
    val raw: String,
    /** false : sortie rejetée (vide, trop longue…), le texte brut est conservé. */
    val applied: Boolean,
    val totalMs: Long,
    val promptMs: Double = 0.0,
    val genMs: Double = 0.0,
    val reusedTokens: Int = 0,
    val promptTokens: Int = 0,
    val genTokens: Int = 0,
    val reason: String? = null,
)

/** Nettoyage d'une transcription brute. Pas thread-safe : un appel à la fois. */
interface TextCleaner : AutoCloseable {
    val modelId: String
    /** Prépare le prompt système (cache KV) : à appeler dès le début de l'enregistrement. */
    fun warm()
    fun clean(raw: String): CleanResult
}
