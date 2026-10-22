package com.karelisio.speech

import com.karelisio.speech.clean.CleanPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CleanPromptTest {
    @Test fun stripsPrefixAndQuotes() {
        assertEquals("Bonjour.", CleanPrompt.sanitize("euh bonjour", "Sortie : « Bonjour. »").first)
    }

    @Test fun rejectsEmpty() {
        assertNull(CleanPrompt.sanitize("bonjour", "   ").first)
    }

    @Test fun rejectsTooLong() {
        val (t, why) = CleanPrompt.sanitize("oui", "Oui, bien sûr, voici une réponse détaillée à ta question.")
        assertNull(t)
        assertEquals(true, why!!.startsWith("sortie trop longue"))
    }

    @Test fun stopsAtNextExample() {
        assertEquals("Merci.", CleanPrompt.sanitize("merci", "Merci.\nEntrée : bla bla").first)
    }
}
