# Apache MINA SSHD
-keep class org.apache.sshd.** { *; }
-dontwarn org.apache.sshd.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**

# Strip debug and verbose logging in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

