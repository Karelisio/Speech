package com.karelisio.speech.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Décimation 48 kHz → 16 kHz en flux : passe-bas FIR (sinc fenêtré Blackman, coupure 7 kHz)
 * puis un échantillon sur trois. Sert à comparer avec le rééchantillonneur du téléphone.
 */
class Decimator3(private val taps: Int = 95, cutoffHz: Double = 7_000.0, inRate: Double = 48_000.0) {
    private val h = FloatArray(taps)
    private var buf = FloatArray(0)
    private val tail = FloatArray(taps - 1)
    private var phase = 0

    init {
        val fc = cutoffHz / inRate
        val mid = (taps - 1) / 2.0
        var sum = 0.0
        val tmp = DoubleArray(taps) { i ->
            val m = i - mid
            val sinc = if (m == 0.0) 2 * fc else sin(2 * PI * fc * m) / (PI * m)
            val w = 0.42 - 0.5 * cos(2 * PI * i / (taps - 1)) + 0.08 * cos(4 * PI * i / (taps - 1))
            (sinc * w).also { sum += it }
        }
        for (i in 0 until taps) h[i] = (tmp[i] / sum).toFloat()
    }

    /** Traite [n] échantillons à 48 kHz, écrit la sortie à 16 kHz dans [out] et renvoie sa longueur. */
    fun process(input: FloatArray, n: Int, out: FloatArray): Int {
        val len = taps - 1 + n
        if (buf.size < len) buf = FloatArray(len)
        System.arraycopy(tail, 0, buf, 0, taps - 1)
        System.arraycopy(input, 0, buf, taps - 1, n)
        var o = 0
        var k = taps - 1 + phase
        while (k < len) {
            var acc = 0f
            for (j in 0 until taps) acc += h[j] * buf[k - j]
            out[o++] = acc
            k += 3
        }
        phase = k - len
        System.arraycopy(buf, len - (taps - 1), tail, 0, taps - 1)
        return o
    }
}
