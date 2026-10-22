package com.karelisio.speech.bench

import java.util.Locale

/** Taux d'erreur sur les mots (substitutions + insertions + suppressions) / mots de référence. */
object Wer {
    data class Score(val errors: Int, val refWords: Int) {
        val rate: Double get() = if (refWords == 0) 0.0 else errors.toDouble() / refWords
    }

    /**
     * Minuscules, apostrophes typographiques unifiées, élisions et traits d'union séparés,
     * ponctuation retirée. Les accents sont conservés : une faute d'accent est une erreur.
     */
    fun normalize(s: String): List<String> =
        s.lowercase(Locale.FRENCH)
            .replace('’', '\'').replace('‘', '\'').replace('`', '\'')
            .replace("'", "' ")
            .map { c -> if (c.isLetterOrDigit() || c == '\'') c else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }

    fun score(reference: String, hypothesis: String): Score {
        val r = normalize(reference)
        val h = normalize(hypothesis)
        var prev = IntArray(h.size + 1) { it }
        var cur = IntArray(h.size + 1)
        for (i in 1..r.size) {
            cur[0] = i
            for (j in 1..h.size) {
                val sub = prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return Score(prev[h.size], r.size)
    }
}
