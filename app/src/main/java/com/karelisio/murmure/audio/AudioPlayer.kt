package com.karelisio.murmure.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Lecture d'un clip pour vérifier l'enregistrement. */
object AudioPlayer {
    private var track: AudioTrack? = null

    @Synchronized
    fun play(samples: FloatArray) {
        stop()
        if (samples.isEmpty()) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioRecorder.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 4)
            .build()
        t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        t.play()
        track = t
    }

    @Synchronized
    fun stop() {
        track?.runCatching { stop(); release() }
        track = null
    }
}
