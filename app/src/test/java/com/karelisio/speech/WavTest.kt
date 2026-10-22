package com.karelisio.speech

import com.karelisio.speech.audio.Wav
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class WavTest {
    @Test fun roundTrip16k() {
        val x = FloatArray(16000) { (0.5 * sin(2 * PI * 440 * it / 16000)).toFloat() }
        val f = File.createTempFile("speech", ".wav")
        Wav.write(f, x)
        val y = Wav.read(f)
        assertEquals(x.size, y.size)
        for (i in x.indices) assertEquals(x[i], y[i], 1e-4f)
        f.delete()
    }

    @Test fun resampleLength() {
        assertEquals(16000, Wav.resample(FloatArray(48000), 48000, 16000).size)
    }
}
