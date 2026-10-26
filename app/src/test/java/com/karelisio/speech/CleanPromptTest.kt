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

    @Test fun rejectsChangedWord() {
        val (t, why) = CleanPrompt.sanitize("Peux-tu acheter du pain ce soir?", "Peux-je acheter du pain ce soir ?")
        assertNull(t)
        assertEquals("mot ajouté ou modifié : « je »", why)
        assertNull(CleanPrompt.sanitize("une bouteille de lait entrant ce soir", "Une bouteille de lait en rentrant ce soir.").first)
    }

    @Test fun rejectsDroppedNegation() {
        val (t, why) = CleanPrompt.sanitize("avant qu'ils ne soient publiés", "Avant qu'ils soient publiés.")
        assertNull(t)
        assertEquals("mot retiré : « ne »", why)
        assertEquals("mot retiré : « bonjour »", CleanPrompt.sanitize("Bonjour Marie, je t'envoie le compte rendu", "Je t'envoie le compte rendu.").second)
    }

    @Test fun acceptsCleaning() {
        val out = "Bonjour Jean-François, il faudrait que nous vérifiions les résultats avant midi."
        assertEquals(out, CleanPrompt.sanitize("euh bonjour Jean François bah en fait il faudrait que nous vérifions les les résultats avant midi", out).first)
        val moved = "Du coup, la réunion est déplacée à vendredi."
        assertEquals(moved, CleanPrompt.sanitize("euh alors du coup la réunion est déplacée à jeudi non pardon à vendredi", moved).first)
        // Auto-correction explicite : la négation annulée peut disparaître.
        assertEquals("Je viens demain.", CleanPrompt.sanitize("je viens pas non pardon je viens demain", "Je viens demain.").first)
    }

    @Test fun stopsAtNextExample() {
        assertEquals("Merci.", CleanPrompt.sanitize("merci", "Merci.\nEntrée : bla bla").first)
    }
}
