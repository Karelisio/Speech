package com.karelisio.speech

import com.karelisio.speech.text.Dictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictionaryTest {
    private val d = Dictionary(listOf("Jean-François", "Karelisio", "MagicOS", "Marie", "Wispr Flow"))

    @Test fun fixesCaseAndHyphen() {
        assertEquals("Appelle Jean-François demain.", d.apply("Appelle jean françois demain."))
    }

    @Test fun fuzzyLongTerm() {
        assertEquals("Le projet Karelisio avance.", d.apply("Le projet carelisio avance."))
        assertEquals("Mise à jour MagicOS", d.apply("Mise à jour magic os"))
    }

    @Test fun shortTermsNeedExactMatch() {
        assertEquals("Mon mari est marié.", d.apply("Mon mari est marié."))
        assertEquals("Bonjour Marie", d.apply("Bonjour marie"))
    }

    @Test fun multiWordTerm() {
        assertEquals("J'utilise Wispr Flow.", d.apply("J'utilise wisper flow."))
    }

    @Test fun untouchedTextStaysIdentical() {
        val t = "Rien à voir ici, vraiment."
        assertEquals(t, d.apply(t))
    }

    @Test fun whisperPrompt() {
        assertNull(Dictionary(emptyList()).whisperPrompt())
        assertEquals("Vocabulaire : A, B.", Dictionary(listOf("A", " B ", "")).whisperPrompt())
    }
}
