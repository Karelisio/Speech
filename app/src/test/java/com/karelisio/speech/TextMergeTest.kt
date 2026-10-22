package com.karelisio.speech

import com.karelisio.speech.access.TextMerge
import org.junit.Assert.assertEquals
import org.junit.Test

class TextMergeTest {
    @Test fun emptyField() {
        val m = TextMerge.merge("", 0, 0, " Bonjour. ")
        assertEquals("Bonjour.", m.text)
        assertEquals(8, m.cursor)
    }

    @Test fun appendAddsSpace() {
        val m = TextMerge.merge("Salut", 5, 5, "ça va ?")
        assertEquals("Salut ça va ?", m.text)
        assertEquals(m.text.length, m.cursor)
    }

    @Test fun insertInMiddleKeepsRest() {
        val m = TextMerge.merge("Bonjour Marie", 7, 7, "chère")
        assertEquals("Bonjour chère Marie", m.text)
        assertEquals("Bonjour chère".length, m.cursor)
    }

    @Test fun replacesSelection() {
        val m = TextMerge.merge("Le chat dort", 3, 7, "chien")
        assertEquals("Le chien dort", m.text)
        assertEquals(8, m.cursor)
    }

    @Test fun unknownSelectionAppends() {
        assertEquals("abc def", TextMerge.merge("abc", -1, -1, "def").text)
    }

    @Test fun noSpaceBeforePunctuation() {
        assertEquals("Bonjour, toi", TextMerge.merge("Bonjour", 7, 7, ", toi").text)
    }

    @Test fun pieceIsWhatGetsPasted() {
        val m = TextMerge.merge("Bonjour Marie", 7, 7, "chère")
        assertEquals(" chère", m.piece)
        assertEquals(7, m.start)
    }

    @Test fun afterNewlineNoSpace() {
        assertEquals("Ligne\nSuite", TextMerge.merge("Ligne\n", 6, 6, "Suite").text)
    }
}
