package com.karelisio.speech.stt

/**
 * Phrases que Whisper invente sur le silence ou le bruit, héritées des sous-titres de son jeu
 * d'entraînement (« Sous-titres réalisés par la communauté d'Amara.org »…). Jamais dictées en
 * pratique : la phrase entière est retirée.
 */
object WhisperHallucinations {
    private val SENTENCE = Regex(
        """[^.!?…]*(amara\.org|sous-titres réalisés|sous-titrage st'|sous-titrage société radio-canada)[^.!?…]*[.!?…]*""",
        RegexOption.IGNORE_CASE,
    )
    private val SPACES = Regex("""\s{2,}""")

    fun strip(text: String): String {
        if (!SENTENCE.containsMatchIn(text)) return text
        return SENTENCE.replace(text, " ").replace(SPACES, " ").trim()
    }
}
