package com.karelisio.speech.text

import java.text.Normalizer
import java.util.Locale

/**
 * Dictionnaire personnel : mots et noms propres à respecter.
 * - Whisper : injectés dans le prompt initial.
 * - Nettoyage IA : listés dans le prompt système.
 * - Tous moteurs : correction après coup des variantes proches (casse, accents, trait
 *   d'union, petite faute de transcription) par distance d'édition.
 */
class Dictionary(terms: List<String>) {
    val terms: List<String> = terms.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private data class Entry(val term: String, val words: Int, val exact: String, val loose: String)

    private val entries = this.terms.map { t ->
        Entry(t, t.split(Regex("[\\s-]+")).size, exactKey(t), looseKey(t))
    }

    fun whisperPrompt(): String? = if (terms.isEmpty()) null else "Vocabulaire : ${terms.joinToString(", ")}."

    /** Remplace dans [text] les variantes reconnues des termes du dictionnaire. */
    fun apply(text: String): String {
        if (entries.isEmpty() || text.isBlank()) return text
        val words = WORD.findAll(text).toList()
        if (words.isEmpty()) return text
        val out = StringBuilder()
        var cursor = 0
        var i = 0
        while (i < words.size) {
            val match = bestMatch(words, i)
            if (match != null) {
                val (entry, k) = match
                out.append(text, cursor, words[i].range.first).append(entry.term)
                cursor = words[i + k - 1].range.last + 1
                i += k
            } else {
                i++
            }
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    private fun bestMatch(words: List<MatchResult>, i: Int): Pair<Entry, Int>? {
        var best: Pair<Entry, Int>? = null
        var bestScore = 0.0
        for (e in entries) {
            for (k in maxOf(1, e.words - 1)..(e.words + 1)) {
                if (i + k > words.size) break
                val candidate = words.subList(i, i + k).joinToString(" ") { it.value }
                val score = similarity(e, candidate)
                if (score > bestScore) {
                    bestScore = score
                    best = e to k
                }
            }
        }
        return best?.takeIf { bestScore > 0 }
    }

    /** 1 = identique (à la casse / au séparateur près) ; 0 = pas de correspondance. */
    private fun similarity(e: Entry, candidate: String): Double {
        val exact = exactKey(candidate)
        if (exact == e.exact) return if (candidate.replace('-', ' ') == e.term.replace('-', ' ')) 0.0 else 1.0
        // Correspondance approchée seulement pour les termes assez longs (évite « mari » → « Marie »).
        if (e.loose.length < 6) return 0.0
        val loose = looseKey(candidate)
        val d = levenshtein(loose, e.loose)
        val sim = 1.0 - d.toDouble() / maxOf(loose.length, e.loose.length)
        return if (sim >= 0.85) sim * 0.99 else 0.0
    }

    companion object {
        private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’]*")

        /** Minuscules, séparateurs ignorés, accents conservés. */
        private fun exactKey(s: String) = s.lowercase(Locale.FRENCH).replace(Regex("[\\s'’-]+"), "")

        /** Comme [exactKey] mais sans accents (comparaison approchée). */
        private fun looseKey(s: String) =
            Normalizer.normalize(exactKey(s), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

        fun levenshtein(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1, prev[j] + 1, cur[j - 1] + 1)
                }
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }

        fun parse(raw: String) = Dictionary(raw.split('\n', ',', ';'))
    }
}
