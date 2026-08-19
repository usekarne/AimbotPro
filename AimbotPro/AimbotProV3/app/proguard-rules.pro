# AimbotPro v4 ProGuard / R8 rules

# ============================================================================
# TFLite — must keep the Interpreter API surface intact.
# ============================================================================
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.gpu.**$* { *; }
-dontwarn org.tensorflow.lite.**

# TFLite support library references AutoValue at compile-time via
# annotation processing, but never actually instantiates it at runtime
# (the auto-value-generated classes are already compiled into the .jar).
# Tell R8 to silently drop those references.
-dontwarn com.google.auto.value.**
-keep class com.google.auto.value.** { *; }

# ============================================================================
# Coroutines — keep volatile fields and internal suspend state.
# ============================================================================
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }

# ============================================================================
# Timber — keeps its tree-planting machinery intact. (Timber ships its own
# consumer rules, but adding defensive keeps here protects against future
# Timber versions changing the public API surface.)
# ============================================================================
-keep class timber.log.Timber { *; }
-keep class timber.log.Timber$* { *; }
-dontwarn org.jetbrains.annotations.**

# ============================================================================
# App code — keep public APIs and the Application class (referenced from
# AndroidManifest.xml via reflection by the platform).
# ============================================================================
-keep class com.webstrike.aimbotpro.App { *; }
-keep class com.webstrike.aimbotpro.MainActivity { *; }
-keep class com.webstrike.aimbotpro.service.CoreAimbotService { *; }
-keep class com.webstrike.aimbotpro.service.AimbotAccessibilityService { *; }

# Keep all *Service classes that might be referenced from the manifest.
-keep class * extends android.app.Service { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# Keep BuildConfig fields (referenced from Kotlin code at runtime).
-keep class com.webstrike.aimbotpro.BuildConfig { *; }

# ============================================================================
# General attributes
# ============================================================================
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ============================================================================
# R8 aggressive optimizations
# ============================================================================
# Allow R8 to remove unused default interface methods.
-allowaccessmodification
# Don't preverify — Android doesn't use JVM preverification.
-dontpreverify
# Re-package internal classes into a single namespace to defeat reflection
# probing by hostile callers (cosmetic but harder to reverse).
-repackageclasses ''
# Move View binding generated code into the app package.
-keepclassmembers class * extends androidx.viewbinding.ViewBinding {
    *;
}

# ============================================================================
# Strip all log levels except WARN/ERROR in release builds.
# (Logger.ReleaseTree already enforces this at runtime, but this strips the
# string literals too — keeps crash logs lean and prevents accidental PII
# leakage through verbose log statements.)
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
