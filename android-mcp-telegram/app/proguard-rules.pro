# Keep BuildConfig so we can read credentials at runtime
-keep class com.androidmcptelegram.BuildConfig { *; }

# Keep accessibility service (Android must resolve it by name)
-keep class com.androidmcptelegram.service.McpAccessibilityService { *; }

# Keep service and receiver class names (Android resolves by name in manifest)
-keep class com.androidmcptelegram.service.McpService { *; }
-keep class com.androidmcptelegram.service.BootReceiver { *; }
-keep class com.androidmcptelegram.McpApplication { *; }
-keep class com.androidmcptelegram.MainActivity { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Ktor
-dontwarn io.ktor.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
