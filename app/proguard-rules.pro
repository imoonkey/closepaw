# Global

# Keep line numbers for stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep all annotations so reflection-based libs keep working
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ── Kotlin Serialization ────────────────────────────────────────────────
# Required so @Serializable classes survive R8 and kotlinx.serialization
# can find their generated ${ClassName}$Companion serializers.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}

# Keep @Serializable classes + their companion + generated serializers
-if @kotlinx.serialization.Serializable class **
-keep class <1> {
    static <1>$Companion Companion;
    *** Companion;
    <init>(...);
    <fields>;
}
-keep,allowobfuscation,allowshrinking class kotlinx.serialization.KSerializer
-keep,allowobfuscation,allowshrinking class * implements kotlinx.serialization.KSerializer { *; }
-keepclasseswithmembers class **$$serializer {
    public static ** INSTANCE;
    *** descriptor;
}

# App's own serializable models (covers subpackages)
-keep @kotlinx.serialization.Serializable class com.moonkey.androidagent.** { *; }
-keep class com.moonkey.androidagent.**$$serializer { *; }

# ── Shizuku AIDL ────────────────────────────────────────────────────────
# AIDL-generated Stub/Proxy classes use reflection and must be kept.
-keep class moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }
-keep class android.os.IShellCallback { *; }
-keep class android.os.IRemoteCallback { *; }

# Hidden-framework AIDL stubs regenerated in the app (virtual display path).
# IVirtualDisplayCallback is instantiated directly and passed via reflection
# into IDisplayManager.createVirtualDisplay — R8 must not rename it.
-keep class android.hardware.display.IVirtualDisplayCallback { *; }
-keep class android.hardware.display.IVirtualDisplayCallback$* { *; }
-keep class android.hardware.display.IDisplayManager { *; }
-keep class android.hardware.display.IDisplayManager$* { *; }
-keep class android.hardware.display.VirtualDisplayConfig { *; }
-keep class android.hardware.display.VirtualDisplayConfig$* { *; }

# The app's own virtualdisplay package relies on reflective getMethod() calls
# keyed by class name — keep members so those lookups keep matching.
-keep class com.moonkey.androidagent.platform.virtualdisplay.** { *; }

# ── OpenAI SDK (openai-java) ────────────────────────────────────────────
# The SDK uses Jackson for JSON (de)serialization with heavy reflection
# on @JsonProperty/@JsonCreator/@JsonValue/@JsonDeserialize.
-keep class com.openai.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class com.fasterxml.jackson.** { *; }
-keepclasseswithmembers class * {
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
}
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <methods>;
}
-dontwarn com.fasterxml.jackson.**
-dontwarn com.openai.**

# OkHttp / Okio used by the OpenAI SDK transport
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Leap SDK (LiquidAI) ─────────────────────────────────────────────────
# Keeps JNI entry points and model classes the native side references by
# name. Wildcard is conservative — the SDK is small and shrinking native
# bindings has a high blast radius for a small APK win.
-keep class ai.liquid.leap.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn ai.liquid.leap.**

# ── HiddenApiBypass ─────────────────────────────────────────────────────
-keep class org.lsposed.hiddenapibypass.** { *; }

# ── Compose ─────────────────────────────────────────────────────────────
# Default rules ship with AGP; add extras for @Stable / composable lookups.
-keep class androidx.compose.runtime.** { *; }

# ── Coroutines ──────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler

# ── App entry points referenced by manifest/XML/accessibility config ────
-keep class com.moonkey.androidagent.AgentService { *; }
-keep class com.moonkey.androidagent.MainActivity { *; }
-keep class com.moonkey.androidagent.SessionRecordingService { *; }

# Keep all Services / BroadcastReceivers / Providers / Activities
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.accessibilityservice.AccessibilityService

# Warnings we explicitly accept
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
