# Project-specific R8 / ProGuard rules for Chatso

# Preserve stack trace line numbers while minimizing source file string overhead
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JNA & UniFFI bindings used by iroh-kmp (native Rust QUIC/relay transport)
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class app.azula.iroh.** { *; }
-dontwarn java.awt.**
-dontwarn javax.naming.**

# Room entities, DAOs, and Database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Keep BuildConfig version metadata used by UpdateChecker
-keepclassmembers class com.example.BuildConfig {
    public static final java.lang.String VERSION_NAME;
    public static final int VERSION_CODE;
}
