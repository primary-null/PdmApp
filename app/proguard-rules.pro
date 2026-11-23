# Local R8 APK. Debug-signed so it can replace the lab debug install.
# Keep reflection surfaces that Retrofit, kotlinx.serialization and EncryptedSharedPreferences use.

-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault

# kotlinx.serialization
-dontwarn kotlinx.serialization.**
-keep,includedescriptorclasses class com.pdm.app.**$$serializer { *; }
-keepclassmembers class com.pdm.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.pdm.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class kotlinx.serialization.json.** { *; }

# Retrofit + coroutines
-dontwarn retrofit2.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.internal.publicsuffix.PublicSuffixDatabase { *; }

# Compose / ViewModel factory (AndroidViewModel)
-keep class com.pdm.app.ui.FleetViewModel { *; }
-keep class com.pdm.app.PdmApp { *; }

# System surfaces named from the manifest are already kept by AGP.
# WebView JS bridge used by the terminal.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Tink / EncryptedSharedPreferences pull errorprone annotations that are compile-only.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
