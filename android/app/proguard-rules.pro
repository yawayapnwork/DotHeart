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
