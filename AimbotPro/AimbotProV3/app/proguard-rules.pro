# AimbotPro v4.1 ProGuard / R8 rules
# Comprehensive keep rules — every class in the app is explicitly kept to prevent
# R8 from stripping or repackaging classes that are accessed via Kotlin reflection,
# coroutines, ViewBinding, or TFLite JNI callbacks.

# ============================================================================
# TFLite — must keep the entire Interpreter API surface intact.
# ============================================================================
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.gpu.**$* { *; }
-dontwarn org.tensorflow.lite.**

# TFLite support library references AutoValue at compile-time via
# annotation processing, but never actually instantiates it at runtime.
-dontwarn com.google.auto.value.**
-keep class com.google.auto.value.** { *; }

# ============================================================================
# Coroutines — keep coroutine machinery intact (R8 can break state machines).
# ============================================================================
-keepclassmembers class kotlinx.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
# Keep the entire coroutines core — state machines reference these by reflection.
-keep class kotlinx.coroutines.internal.DispatchedContinuation { *; }
-keep class kotlinx.coroutines.CancellableContinuationImpl { *; }
-keep class kotlinx.coroutines.AbstractCoroutine { *; }

# ============================================================================
# Timber — keeps its tree-planting machinery intact.
# ============================================================================
-keep class timber.log.Timber { *; }
-keep class timber.log.Timber$* { *; }
-dontwarn org.jetbrains.annotations.**

# ============================================================================
# App code — KEEP EVERYTHING. This app is not a library; there is zero benefit
# to stripping internal classes, and enormous risk of breaking Kotlin reflection,
# coroutine state machines, ViewBinding, and TFLite.
# ============================================================================
-keep class com.webstrike.aimbotpro.** { *; }
-keepclassmembers class com.webstrike.aimbotpro.** { *; }

# ViewBinding generated code
-keepclassmembers class * extends androidx.viewbinding.ViewBinding {
    *;
}

# Keep BuildConfig fields (referenced from Kotlin code at runtime).
-keep class com.webstrike.aimbotpro.BuildConfig { *; }

# Keep all Service classes (referenced from AndroidManifest.xml by class name).
-keep class * extends android.app.Service { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# ============================================================================
# General attributes — required for Kotlin and AndroidX.
# ============================================================================
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes EnclosingMethod
-keepattributes InnerClasses
# CRITICAL: Keep Kotlin Metadata annotation — R8 uses it to understand
# Kotlin-specific constructs (data classes, sealed classes, etc.).
-keep @interface kotlin.Metadata { *; }
-keepclassmembers class ** {
    @kotlin.Metadata *;
}

# ============================================================================
# R8 optimizations — safe subset.
# ============================================================================
# Allow R8 to modify access levels (package-private widening).
-allowaccessmodification
# Don't preverify — Android doesn't use JVM preverification.
-dontpreverify

# DO NOT use -repackageclasses — it breaks Kotlin object singletons,
# coroutine state machines, and ViewBinding in combination with
# -allowaccessmodification. The ~200 KB size saving is not worth the
# instability.

# ============================================================================
# Strip verbose/info log calls in release builds.
# ============================================================================
-assumenosideeffects class com.webstrike.aimbotpro.utils.Logger {
    public void v(...);
    public void d(...);
    public void i(...);
}
-assumenosideeffects class timber.log.Timber$Tree {
    public void v(...);
    public void d(...);
    public void i(...);
}
