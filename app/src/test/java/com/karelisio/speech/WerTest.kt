package com.karelisio.speech

import com.karelisio.speech.bench.Wer
import org.junit.Assert.assertEquals
import org.junit.Test

class WerTest {
    @Test fun identicalIgnoringCaseAndPunctuation() {
        val s = Wer.score("Bonjour Marie, ça va ?", "bonjour marie ça va")
        assertEquals(0, s.errors)
        assertEquals(4, s.refWords)
    }

    @Test fun elisionsAndHyphensAreSplit() {
        assertEquals(listOf("l'", "homme", "rappelle", "moi"), Wer.normalize("L’homme rappelle-moi"))
    }

    @Test fun countsSubstitutionInsertionDeletion() {
        // sub (chat→chien) + del (noir) + ins (très)
        val s = Wer.score("le chat noir dort", "le chien dort très")
        assertEquals(3, s.errors)
        assertEquals(4, s.refWords)
    }

    @Test fun accentsMatter() {
        assertEquals(1, Wer.score("déjà", "deja").errors)
    }
}
