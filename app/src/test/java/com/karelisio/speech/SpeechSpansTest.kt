package com.karelisio.speech

import com.karelisio.speech.vad.Span
import com.karelisio.speech.vad.SpeechSpans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechSpansTest {
    @Test fun mergeOverlappingAfterPadding() {
        val out = SpeechSpans.padAndMerge(listOf(Span(100, 200), Span(230, 300), Span(600, 700)), pad = 20, total = 1000)
        assertEquals(listOf(Span(80, 320), Span(580, 720)), out)
    }

    @Test fun padIsClampedToBounds() {
        assertEquals(listOf(Span(0, 50)), SpeechSpans.padAndMerge(listOf(Span(5, 45)), pad = 10, total = 50))
    }

    @Test fun trimKeepsFirstToLastSpeech() {
        val x = FloatArray(1000) { it.toFloat() }
        val t = SpeechSpans.trim(x, listOf(Span(200, 300), Span(500, 600)), pad = 50)!!
        assertEquals(500, t.size)
        assertEquals(150f, t.first())
        assertEquals(649f, t.last())
    }

    @Test fun noSpeechGivesNull() {
        assertNull(SpeechSpans.trim(FloatArray(100), emptyList(), pad = 10))
    }

    @Test fun segmentsFollowPauses() {
        val x = FloatArray(1000)
        val segs = SpeechSpans.segments(x, listOf(Span(100, 200), Span(600, 700)), pad = 10)
        assertEquals(listOf(120, 120), segs.map { it.size })
    }
}
