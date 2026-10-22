# Classes appelées par JNI (sherpa-onnx lit les champs des configs par réflexion JNI).
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.karelisio.speech.stt.WhisperNative { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Release : retire les vérifications de nullité générées par Kotlin pour les paramètres et
# les valeurs Java (le typage est déjà vérifié à la compilation). `!!` reste intact.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNullParameter(java.lang.Object, java.lang.String);
    public static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    public static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    public static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    public static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String);
    public static void checkFieldIsNotNull(java.lang.Object, java.lang.String);
}

# Agressivité R8 maximale sur le code de l'app (les classes JNI restent gardées plus haut).
-allowaccessmodification
-repackageclasses
