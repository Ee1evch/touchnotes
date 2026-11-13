# Оптимизация и общие атрибуты
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,SourceFile,LineNumberTable

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

# Parent domain models, schedule, grades, sync, security
-keep class app.nastavnik.parent.** { *; }
-keep class app.nastavnik.grades.** { *; }
-keep class app.nastavnik.schedule.** { *; }
-keep class app.nastavnik.sync.** { *; }

# Tink and compile-only annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn java.lang.invoke.MethodHandle
-dontwarn java.lang.invoke.MethodHandles$Lookup

# Entry points, activities, services
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.app.Application
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Strip verbose debug logs in release builds (v and d only, keep i, w, e)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
