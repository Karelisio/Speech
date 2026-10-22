package com.karelisio.speech.audio

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lecture / écriture WAV minimale (PCM 16 bits ou float 32 bits). */
object Wav {
    fun write(file: File, samples: FloatArray, sampleRate: Int = AudioRecorder.SAMPLE_RATE) {
        val dataLen = samples.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(dataLen)
        for (s in samples) bb.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        file.parentFile?.mkdirs()
        file.writeBytes(bb.array())
    }

    fun read(file: File): FloatArray = file.inputStream().buffered().use { read(it) }

    /** Lit un WAV et le convertit en mono 16 kHz. */
    fun read(input: InputStream): FloatArray {
        val bytes = DataInputStream(input).readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Pas un fichier WAV" }
        var pos = 12
        var format = 0
        var channels = 1
        var rate = AudioRecorder.SAMPLE_RATE
        var bits = 16
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = bb.getShort(body).toInt() and 0xffff
                    channels = bb.getShort(body + 2).toInt()
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                    if (format == 0xFFFE && len >= 26) format = bb.getShort(body + 24).toInt() and 0xffff
                }
                "data" -> {
                    dataOff = body
                    dataLen = minOf(len.takeIf { it >= 0 } ?: Int.MAX_VALUE, bytes.size - body)
                }
            }
            if (dataOff >= 0) break
            pos = body + len + (len and 1)
        }
        require(dataOff >= 0) { "Bloc data introuvable" }
        val frameBytes = bits / 8 * channels
        val frames = dataLen / frameBytes
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                val o = dataOff + f * frameBytes + c * (bits / 8)
                acc += when {
                    format == 3 && bits == 32 -> bb.getFloat(o)
                    bits == 16 -> bb.getShort(o) / 32768f
                    bits == 32 -> bb.getInt(o) / 2147483648f
                    bits == 8 -> ((bytes[o].toInt() and 0xff) - 128) / 128f
                    else -> error("Format WAV non supporté ($format, $bits bits)")
                }
            }
            mono[f] = acc / channels
        }
        return if (rate == AudioRecorder.SAMPLE_RATE) mono else resample(mono, rate, AudioRecorder.SAMPLE_RATE)
    }

    /** Rééchantillonnage linéaire (suffisant pour importer des clips de test). */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        val n = (x.size.toLong() * to / from).toInt()
        val out = FloatArray(n)
        val ratio = from.toDouble() / to
        for (i in 0 until n) {
            val p = i * ratio
            val i0 = p.toInt().coerceAtMost(x.size - 1)
            val i1 = (i0 + 1).coerceAtMost(x.size - 1)
            val frac = (p - i0).toFloat()
            out[i] = x[i0] * (1 - frac) + x[i1] * frac
        }
        return out
    }
}
