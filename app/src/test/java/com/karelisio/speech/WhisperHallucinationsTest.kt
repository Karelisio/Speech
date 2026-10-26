package com.karelisio.speech

import com.karelisio.speech.stt.WhisperHallucinations
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperHallucinationsTest {
    @Test
    fun removesSubtitleCredits() {
        assertEquals("", WhisperHallucinations.strip("Sous-titres réalisés par la communauté d'Amara.org"))
        assertEquals("", WhisperHallucinations.strip(" Sous-titrage ST' 501 "))
        assertEquals(
            "Je serai là à 18 h.",
            WhisperHallucinations.strip("Je serai là à 18 h. Sous-titres réalisés para la communauté d'Amara.org"),
        )
    }

    @Test
    fun keepsNormalText() {
        for (t in listOf(
            "Bonjour, peux-tu m'envoyer le titre du document ? Merci.",
            "Ajoute un sous-titre à la vidéo. Le sous-titrage est fini.",
        )) assertEquals(t, WhisperHallucinations.strip(t))
    }
}
