package com.karelisio.speech.stt

/** Liaison JNI vers whisper.cpp (app/src/main/cpp/whisper_jni.cpp). */
object WhisperNative {
    init {
        System.loadLibrary("whisper_jni")
    }

    @JvmStatic external fun init(modelPath: String, flashAttn: Boolean): Long
    @JvmStatic external fun free(ctx: Long)
    @JvmStatic external fun abort(ctx: Long)
    @JvmStatic external fun transcribe(
        ctx: Long,
        samples: FloatArray,
        language: String,
        threads: Int,
        prompt: String?,
        audioCtx: Int,
        beamSize: Int,
    ): ByteArray?
    /** [encodeMs, decodeMsParToken] du dernier appel. */
    @JvmStatic external fun lastTimings(ctx: Long): FloatArray
    @JvmStatic external fun systemInfo(): String
}
