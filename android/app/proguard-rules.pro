# OkHttp ships its own consumer ProGuard rules; these suppress R8 warnings
# for optional platform-detection classes OkHttp references reflectively
# but that are never present or loaded on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.coroutines' debug agent hook is a desktop/JVM-only facility, not
# present at runtime on Android.
-dontwarn kotlinx.coroutines.debug.**

# WidgetState is deserialized from org.json field-by-field in code, not via
# reflection, so no explicit -keep is required for it beyond the default
# application-class rules AGP already applies.

# --- WorkManager -----------------------------------------------------------
# WorkManager's default WorkerFactory instantiates ListenableWorker
# subclasses reflectively (Class.forName + getDeclaredConstructor) when a
# scheduled job actually runs - that call site is invisible to R8's static
# reachability analysis, so without an explicit rule R8 can (and in past
# work-runtime releases has been known to) strip or rename the
# (Context, WorkerParameters) constructor as "unused." work-runtime's own
# consumer-rules.txt is expected to cover this, but it is pinned here
# explicitly as defense in depth: a missing/renamed constructor here fails
# silently at runtime (WorkManager just never runs the job) with no build-
# time error to catch it.
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.dotheart.widget.work.WidgetSyncWorker { *; }

# CoupleWidgetProvider and DotHeartApp are both referenced only by class
# name from AndroidManifest.xml (android:name=".CoupleWidgetProvider" /
# ".DotHeartApp"), a reference R8 already honors automatically via AGP's
# generated manifest-component keep rules - no explicit rule is required
# for either, and none is added here to avoid a redundant/rotting rule.

# --- Kotlin coroutines / general Kotlin metadata ---------------------------
# Preserves generic signatures and Kotlin's compiler-generated metadata
# annotations, which kotlinx.coroutines' suspend-function machinery and
# Kotlin reflection both rely on; R8 full mode strips these by default
# unless explicitly kept.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
