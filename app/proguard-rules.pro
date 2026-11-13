# Оптимизация и общие атрибуты
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,JavascriptInterface,SourceFile,LineNumberTable

# Kotlin Metadata и Coroutines
-keep class kotlin.Metadata { *; }
-keepclassmembers class * extends kotlin.coroutines.jvm.internal.ContinuationImpl {
    <fields>;
}

# Kotlinx Serialization
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keepclassmembers class **$$serializer {
    *;
}
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * implements kotlinx.serialization.internal.GeneratedSerializer {
    *;
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-dontnote kotlinx.serialization.SerializationKt

# WebView JavaScript Interfaces
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Tink and compile-only annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.errorprone.annotations.MustBeClosed
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn androidx.test.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn java.lang.invoke.MethodHandle
-dontwarn java.lang.invoke.MethodHandles$Lookup

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Room Database
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# Domain models, sync, schedule, grades, session, ink, notes, study, security, ai
-keep class app.nastavnik.data.** { *; }
-keep class app.nastavnik.grades.** { *; }
-keep class app.nastavnik.schedule.** { *; }
-keep class app.nastavnik.sync.** { *; }
-keep class app.nastavnik.study.** { *; }
-keep class app.nastavnik.ink.** { *; }
-keep class app.nastavnik.notes.** { *; }
-keep class app.nastavnik.security.** { *; }
-keep class app.nastavnik.session.** { *; }
-keep class app.nastavnik.ai.** { *; }

# Entry points, activities, services, receivers, application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.app.Application
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# UI entry points and theme tokens for Compose
-keep class app.nastavnik.ui.theme.** { *; }
-keep class app.nastavnik.ui.Brand { *; }
-keep class app.nastavnik.ui.MainActivity { *; }
-keep class app.nastavnik.ui.StudentShellKt { *; }
-keep class app.nastavnik.overlay.BubbleService { *; }
-keep class app.nastavnik.overlay.AnswerOverlay { *; }

# Strip verbose debug logs in release builds (v and d only, keep i, w, e)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
