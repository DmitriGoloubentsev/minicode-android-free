# Apache MINA SSHD
-keep class org.apache.sshd.** { *; }
-dontwarn org.apache.sshd.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**

# Sherpa-ONNX (JNI looks up fields by name — must not be obfuscated)
-keep class com.k2fsa.sherpa.onnx.** { *; }

# sora-editor TextMate (uses reflection for grammar/theme registry)
-keep class io.github.rosemoe.sora.** { *; }
-keep class org.eclipse.tm4e.** { *; }
-keep class org.eclipse.jdt.** { *; }
# Oniguruma regex engine (used by tm4e for TextMate grammars)
-keep class org.jcodings.** { *; }
-keep class org.joni.** { *; }
-dontwarn kotlin.Cloneable$DefaultImpls

# Strip debug and verbose logging in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

