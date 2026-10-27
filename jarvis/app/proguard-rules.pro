# Porcupine uses JNI
-keep class ai.picovoice.** { *; }
# Room
-keep class * extends androidx.room.RoomDatabase
# Accessibility / notification services are referenced from the manifest
-keep class com.jarvis.agent.accessibility.** { *; }
-keep class com.jarvis.agent.notifications.** { *; }
-dontwarn org.jspecify.annotations.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
