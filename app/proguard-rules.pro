# Classes appelées par JNI (sherpa-onnx lit les champs des configs par réflexion JNI).
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.karelisio.speech.stt.WhisperNative { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
