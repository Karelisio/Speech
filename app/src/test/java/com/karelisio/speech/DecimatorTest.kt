package com.karelisio.speech

import com.karelisio.speech.audio.Decimator3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class DecimatorTest {
    private fun rmsAfter(freq: Double): Double {
        val d = Decimator3()
        val n = 48_000
        val x = FloatArray(n) { sin(2 * PI * freq * it / 48_000).toFloat() }
        val out = FloatArray(n / 3 + 2)
        val len = d.process(x, n, out)
        assertEquals(16_000, len)
        val s = out.copyOfRange(200, len) // ignore le transitoire du filtre
        return sqrt(s.sumOf { it.toDouble() * it } / s.size)
    }

    @Test fun passband1kHz() {
        assertEquals(1 / sqrt(2.0), rmsAfter(1_000.0), 0.02)
    }

    @Test fun stopband12kHz() {
        assertTrue(rmsAfter(12_000.0) < 0.01) // > 40 dB d'atténuation
    }

    @Test fun streamingMatchesSinglePass() {
        val x = FloatArray(9_000) { sin(2 * PI * 440 * it / 48_000).toFloat() }
        val ref = FloatArray(3_100)
        val refLen = Decimator3().process(x, x.size, ref)
        val d = Decimator3()
        val got = ArrayList<Float>()
        var i = 0
        while (i < x.size) {
            val m = minOf(512, x.size - i)
            val o = FloatArray(m / 3 + 2)
            val k = d.process(x.copyOfRange(i, i + m), m, o)
            for (j in 0 until k) got += o[j]
            i += m
        }
        assertEquals(refLen, got.size)
        for (j in 0 until refLen) assertEquals(ref[j], got[j], 1e-5f)
    }
}
