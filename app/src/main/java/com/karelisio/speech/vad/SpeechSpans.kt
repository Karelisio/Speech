package com.karelisio.speech.vad

/** Intervalle de parole [start, end) en échantillons. */
data class Span(val start: Int, val end: Int) {
    val length get() = end - start
}

/** Découpage de l'audio à partir des intervalles de parole (logique pure, testée en JVM). */
object SpeechSpans {
    /** Élargit chaque intervalle de [pad] échantillons et fusionne ceux qui se chevauchent. */
    fun padAndMerge(spans: List<Span>, pad: Int, total: Int): List<Span> {
        val out = ArrayList<Span>()
        for (s in spans.sortedBy { it.start }) {
            val p = Span((s.start - pad).coerceAtLeast(0), (s.end + pad).coerceAtMost(total))
            val last = out.lastOrNull()
            if (last != null && p.start <= last.end) out[out.size - 1] = Span(last.start, maxOf(last.end, p.end))
            else out += p
        }
        return out
    }

    /** Audio du début de la première parole à la fin de la dernière (marge [pad]) ; null si aucune parole. */
    fun trim(samples: FloatArray, spans: List<Span>, pad: Int): FloatArray? {
        if (spans.isEmpty()) return null
        val start = (spans.minOf { it.start } - pad).coerceAtLeast(0)
        val end = (spans.maxOf { it.end } + pad).coerceAtMost(samples.size)
        return if (end > start) samples.copyOfRange(start, end) else null
    }

    /** Segments séparés aux pauses (marge [pad], chevauchements fusionnés). */
    fun segments(samples: FloatArray, spans: List<Span>, pad: Int): List<FloatArray> =
        padAndMerge(spans, pad, samples.size).map { samples.copyOfRange(it.start, it.end) }
}
